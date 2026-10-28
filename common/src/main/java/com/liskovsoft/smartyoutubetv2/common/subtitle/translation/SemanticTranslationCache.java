package com.liskovsoft.smartyoutubetv2.common.subtitle.translation;

import com.liskovsoft.smartyoutubetv2.common.subtitle.session.CaptionSession;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.MappedTranscript;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.SemanticGroupPlanner;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Coordinator-owned bounded frozen data and completed Strings, never old document/request objects. */
final class SemanticTranslationCache {
    private static final int REQUEST_CONTRACT = 2; // Per-item context data/prompt contract.
    private static final int MAPPING_CONTRACT = 2; // Primary source ranges, not later display echoes.
    private static final Charset UTF8 = Charset.forName("UTF-8");

    static final class Key {
        private final CaptionSession.CacheScope source;
        private final TranslationCoordinator.CredentialScope credential;
        final byte[] digest;
        private final byte[] requestSemantics;
        private final int hash;
        private Key(CaptionSession.CacheScope source, TranslationCoordinator.CredentialScope credential, byte[] digest,
                    byte[] requestSemantics) {
            this.source = source;
            this.credential = credential;
            this.digest = digest;
            this.requestSemantics = requestSemantics.clone();
            hash = 31 * (31 * System.identityHashCode(source) + System.identityHashCode(credential)) + Arrays.hashCode(digest);
        }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key key = (Key) other;
            return source == key.source && credential == key.credential && Arrays.equals(digest, key.digest);
        }
    }

    static final class Window {
        private final byte[] digest;
        private Window(byte[] digest) { this.digest = digest; }
        @Override public int hashCode() { return Arrays.hashCode(digest); }
        @Override public boolean equals(Object other) {
            return other instanceof Window && Arrays.equals(digest, ((Window) other).digest);
        }
    }

    static final class Record {
        final Key base;
        final Key key;
        final CaptionNeighborContext.Frozen frozen; // null is context-admission pressure, not empty data.
        final long expiresAtMs;
        final Window pressureWindow;
        Record(Key base, Key key, CaptionNeighborContext.Frozen frozen, long expiresAtMs, Window pressureWindow) {
            this.base = base;
            this.key = key;
            this.frozen = frozen;
            this.expiresAtMs = expiresAtMs;
            this.pressureWindow = pressureWindow;
        }
    }

    static final class Entry {
        final String text;
        final long expiresAtMs;
        private final int characters;
        private final Window window;
        private Entry(String text, long expiresAtMs, int characters, Window window) {
            this.text = text;
            this.expiresAtMs = expiresAtMs;
            this.characters = characters;
            this.window = window;
        }
    }

    private final TranslationCoordinator.Limits mLimits;
    private final Map<Key, Entry> mEntries = new LinkedHashMap<>();
    private final Map<Key, Record> mRecords = new LinkedHashMap<>();
    private final Map<Key, Record> mByKey = new LinkedHashMap<>();
    private int mCharacters;
    private int mValues;
    private int mContextCharacters;
    private int mDependencyBytes;

    SemanticTranslationCache(TranslationCoordinator.Limits limits) { mLimits = limits; }

    static byte[] requestSemantics(TranslationCoordinator.Profile profile) {
        Digest digest = new Digest();
        digest.number(REQUEST_CONTRACT);
        digest.string(profile.wire.endpoint.toString());
        digest.string(profile.wire.model);
        digest.number(profile.wire.maxOutputTokens);
        digest.number(profile.wire.requestBytes);
        digest.number(profile.wire.responseBytes);
        digest.string(profile.wire.tokenField.name());
        digest.string(profile.wire.thinking.name());
        digest.string(profile.wire.service.name());
        digest.string(profile.wire.workspaceId);
        digest.string(CaptionModelPolicy.REVISION);
        digest.number(profile.credentialRevision);
        digest.string(profile.sourceLanguage);
        digest.string(profile.targetLanguage);
        digest.string(profile.context);
        // Deliberately retain existing conservative resource-configuration namespaces in this step.
        digest.number(profile.batchLimits.items);
        digest.number(profile.batchLimits.sourceCodePoints);
        digest.number(profile.batchLimits.contextCodePoints);
        digest.number(profile.batchLimits.translatedCodePoints);
        digest.number(profile.maxCalls);
        return digest.finish();
    }

    static Key key(CaptionSession.Snapshot snapshot, TranslationCoordinator.Profile profile, int index) {
        Digest digest = new Digest();
        digest.number(MAPPING_CONTRACT);
        digest.number(snapshot.transcript.normalizationVersion());
        digest.bytes(profile.semantics);
        digest.string(snapshot.clock.mime);
        digest.number(snapshot.clock.offsetUs);
        digest.number(snapshot.clock.durationUs);
        digest.string(snapshot.timeline.getOrigin().name());
        digest.string(snapshot.groups.getText(index));
        SemanticGroupPlanner.Group group = snapshot.groups.getGroups().get(index);
        digest.string(group.boundary.name());
        digest.number(group.slices.size());
        for (MappedTranscript.Slice slice : group.slices) {
            digest.number(slice.start);
            digest.number(slice.end);
            List<MappedTranscript.Reference> references = primary(snapshot.transcript.references(slice));
            digest.number(references.size());
            for (MappedTranscript.Reference reference : references) {
                digest.number(reference.textStart);
                digest.number(reference.textEnd);
                digest.number(reference.wholeRun ? 1 : 0);
                timing(digest, reference.origin);
            }
        }
        return new Key(snapshot.track.cacheScope, profile.cacheScope, digest.finish(), profile.semantics);
    }

    /** Position and mapping shape, not text length/ordinal. Only unique slots prove literal revisions. */
    static byte[] slot(CaptionSession.Snapshot snapshot, int index) {
        Digest digest = new Digest();
        digest.number(MAPPING_CONTRACT);
        digest.number(snapshot.transcript.normalizationVersion());
        digest.string(snapshot.clock.mime);
        digest.number(snapshot.clock.offsetUs);
        digest.number(snapshot.clock.durationUs);
        digest.string(snapshot.timeline.getOrigin().name());
        List<MappedTranscript.Reference> references = primary(snapshot.groups.getReferences(index));
        digest.number(references.size());
        for (MappedTranscript.Reference reference : references) {
            digest.number(reference.wholeRun ? 1 : 0);
            if (!reference.wholeRun) {
                digest.number(reference.textStart);
                digest.number(reference.textEnd);
            }
            timing(digest, reference.origin);
        }
        return digest.finish();
    }

    private static List<MappedTranscript.Reference> primary(List<MappedTranscript.Reference> references) {
        List<MappedTranscript.Reference> primary = new ArrayList<>();
        for (MappedTranscript.Reference reference : references) if (!reference.origin.echo) primary.add(reference);
        return primary;
    }

    private static void timing(Digest digest, MappedTranscript.Origin origin) {
        digest.number(origin.activeStartUs);
        digest.number(origin.activeEndUs);
        digest.number(origin.timingStartUs);
        digest.number(origin.timingEndUs);
        digest.string(origin.timingKind.name());
        digest.number(origin.lineBreak ? 1 : 0);
    }

    private static Key contextual(Key base, CaptionNeighborContext.Frozen frozen) {
        Digest digest = new Digest();
        digest.bytes(base.digest);
        digest.number(frozen == null ? 0 : 1);
        if (frozen != null) digest.bytes(frozen.fingerprint);
        return new Key(base.source, base.credential, digest.finish(), base.requestSemantics);
    }

    static Window window(List<Key> keys) {
        Digest digest = new Digest();
        digest.number(keys.size());
        for (Key key : keys) digest.bytes(key.digest);
        return new Window(digest.finish());
    }

    /** Worker admission only. Proposed text and all hashes were prepared outside the admission locks. */
    Record resolve(Key base, CaptionNeighborContext.Frozen proposed, Key proposedKey,
                   long now, List<Key> priority, Window window, boolean allowNew) {
        if (proposedKey == null) return null;
        // The final request-context variant is the record identity. Resolving another occurrence
        // with the same base must never remove this variant's data, value or pressure marker.
        Record old = mByKey.get(proposedKey);
        long expires = add(now, mLimits.cacheTtlMs);
        if (old != null) {
            if (now < old.expiresAtMs && base.credential.active()) {
                if (old.frozen == null && old.pressureWindow.equals(window)) return old;
                if (old.frozen != null && proposed != null) {
                    if (old.frozen == proposed) return old;
                    // Freshly verified association, equal actual sent data: retain paid value and TTL.
                    mContextCharacters -= old.frozen.codePoints;
                    mDependencyBytes -= old.frozen.dependencyBytes();
                    Record refreshed = new Record(base, old.key, proposed, old.expiresAtMs, null);
                    putRecord(refreshed);
                    return refreshed;
                }
                if (!allowNew || proposed == null) return null;
                expires = old.expiresAtMs; // Priority change may retry pressure, never extend its TTL.
            }
            removeRecord(old);
        }
        if (!allowNew || proposed == null) return null;
        while (mRecords.size() >= mLimits.cacheEntries * 2) {
            Record victim = victim(priority, proposedKey, false);
            if (victim == null) return null;
            removeRecord(victim);
        }
        while (proposed.codePoints > contextLimit() - mContextCharacters) {
            Record victim = victim(priority, proposedKey, true);
            if (victim == null) break;
            if (priority.contains(victim.key)) {
                // Keep this exact context variant suppressed to its original deadline.
                Record marker = new Record(victim.base, victim.key, null, victim.expiresAtMs, window);
                removeRecord(victim);
                putRecord(marker);
            } else removeRecord(victim);
        }
        boolean fits = proposed.codePoints <= contextLimit() - mContextCharacters;
        Record record = new Record(base, proposedKey, fits ? proposed : null, expires, fits ? null : window);
        putRecord(record);
        return record;
    }

    // Prepared on the worker; never called by Frame/key queries or provider completion.
    static Key contextKey(Key base, CaptionNeighborContext.Frozen frozen) { return contextual(base, frozen); }

    private Record victim(List<Key> priority, Key incoming, boolean textOnly) {
        int incomingRank = priority.indexOf(incoming);
        for (Record record : mRecords.values()) {
            if (textOnly && (record.frozen == null || record.frozen.codePoints == 0)) continue;
            int rank = priority.indexOf(record.key);
            if (rank < 0 || incomingRank >= 0 && rank > incomingRank) return record;
        }
        return null;
    }

    private int contextLimit() { return Math.min(2 * mLimits.cacheCodePoints, 2 * 1024 * 1024); }

    private void putRecord(Record record) {
        mRecords.put(record.key, record);
        mByKey.put(record.key, record);
        if (record.frozen != null) {
            mContextCharacters += record.frozen.codePoints;
            mDependencyBytes += record.frozen.dependencyBytes();
        }
        // At most 2*cacheEntries records, each <=4 dependencies of two fixed SHA-256 values.
        if (mDependencyBytes > mLimits.cacheEntries * 2 * CaptionNeighborContext.DEPENDENCIES * 64) {
            throw new IllegalStateException("Caption context dependency budget");
        }
    }

    private void removeRecord(Record record) {
        mRecords.remove(record.key);
        mByKey.remove(record.key);
        remove(record.key);
        if (record.frozen != null) {
            mContextCharacters -= record.frozen.codePoints;
            mDependencyBytes -= record.frozen.dependencyBytes();
        }
    }

    /** Base projection retained for single-variant diagnostics; admission uses the full variants. */
    Map<Key, Record> records() {
        Map<Key, Record> result = new LinkedHashMap<>();
        for (Record record : mRecords.values()) result.put(record.base, record);
        return result;
    }
    Map<Key, Record> variants() { return new LinkedHashMap<>(mRecords); }

    /** Scalar-only feasibility check, not a new freeze or a claim that the new document matches. */
    boolean hasCompletedDomain(CaptionSession.CacheScope source, TranslationCoordinator.CredentialScope credential,
                               byte[] semantics) {
        for (Record record : mRecords.values()) {
            if (record.base.source == source && record.base.credential == credential &&
                    Arrays.equals(record.base.requestSemantics, semantics) && get(record.key) != null) return true;
        }
        return false;
    }

    boolean prepared(Key key) { return key != null && mByKey.containsKey(key); }
    Record record(Key key) { return mByKey.get(key); }
    Entry get(Key key) {
        Entry entry = mEntries.get(key);
        return prepared(key) && entry != null && entry.text != null ? entry : null;
    }

    boolean suppressed(Key key, Window window) {
        Record record = mByKey.get(key);
        if (record != null && record.frozen == null && record.pressureWindow.equals(window)) return true;
        Entry entry = mEntries.get(key);
        return entry != null && entry.text == null && (entry.window == null || entry.window.equals(window));
    }

    boolean store(Key key, String text, long now, List<Key> priority, Window window) {
        Record record = mByKey.get(key);
        if (record == null || record.frozen == null || now >= record.expiresAtMs) return false;
        if (get(key) != null) return true;
        int characters = text.codePointCount(0, text.length());
        if (characters > mLimits.cacheCodePoints) { suppress(key, null, now); return false; }
        while (mValues >= mLimits.cacheEntries || characters > mLimits.cacheCodePoints - mCharacters) {
            Key victim = null;
            int incomingRank = priority.indexOf(key);
            for (Map.Entry<Key, Entry> entry : mEntries.entrySet()) {
                if (entry.getValue().text == null) continue;
                int rank = priority.indexOf(entry.getKey());
                if (rank < 0 || incomingRank >= 0 && rank > incomingRank) { victim = entry.getKey(); break; }
            }
            if (victim == null) { suppress(key, window, now); return false; }
            remove(victim);
            if (priority.contains(victim)) suppress(victim, window, now);
        }
        put(key, new Entry(text, Math.min(add(now, mLimits.cacheTtlMs), record.expiresAtMs), characters, null));
        return true;
    }

    void suppress(Key key, Window window, long now) {
        Record record = mByKey.get(key);
        if (record == null || now >= record.expiresAtMs) return;
        Entry old = mEntries.get(key);
        if (old != null && old.text != null) return;
        long expires = old == null ? record.expiresAtMs : Math.min(old.expiresAtMs, record.expiresAtMs);
        put(key, new Entry(null, expires, 0, window));
    }

    boolean expire(long now) {
        List<Record> expired = new ArrayList<>();
        for (Record record : mRecords.values()) {
            if (now >= record.expiresAtMs || !record.base.credential.active()) expired.add(record);
        }
        for (Record record : expired) removeRecord(record);
        return !expired.isEmpty();
    }

    void clear() {
        mEntries.clear(); mRecords.clear(); mByKey.clear();
        mCharacters = 0; mValues = 0; mContextCharacters = 0; mDependencyBytes = 0;
    }

    private void put(Key key, Entry entry) {
        remove(key);
        mEntries.put(key, entry); // <= record count; there is no orphan marker table with another lifetime.
        if (entry.text != null) { mCharacters += entry.characters; mValues++; }
    }

    private void remove(Key key) {
        Entry old = mEntries.remove(key);
        if (old != null && old.text != null) { mCharacters -= old.characters; mValues--; }
    }

    private static long add(long value, long positive) {
        return value > Long.MAX_VALUE - positive ? Long.MAX_VALUE : value + positive;
    }

    /** Length-prefixed canonical fields; never receives a Key, Cookie, or signed caption URL. */
    static final class Digest {
        private final MessageDigest digest;
        Digest() {
            try { digest = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 unavailable", error); }
        }
        void number(long value) {
            for (int shift = 56; shift >= 0; shift -= 8) digest.update((byte) (value >>> shift));
        }
        void string(String value) {
            if (value == null) number(-1);
            else bytes(value.getBytes(UTF8));
        }
        void bytes(byte[] value) { number(value.length); digest.update(value); }
        byte[] finish() { return digest.digest(); }
    }
}
