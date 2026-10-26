package com.liskovsoft.smartyoutubetv2.common.subtitle.translation;

import com.liskovsoft.smartyoutubetv2.common.subtitle.session.CaptionSession;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.SegmentationProposal;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.SemanticGroupPlanner;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Optional bounded source-boundary work, executed by the translation worker, not a second request pool. */
final class CaptionBoundaryRefiner {
    private static final int MAX_TRIED = 128;
    private static final long TRIED_TTL_MS = 600000;
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final SemanticGroupPlanner.Limits PLAN_LIMITS = new SemanticGroupPlanner.Limits(4096, 32768, 32768);
    private static final SegmentationProposal.Limits PROPOSAL_LIMITS = new SegmentationProposal.Limits(512, 4096, 1024, 64);

    static final class Outcome {
        final boolean changed;
        final CaptionBoundaryClient.Result response;
        Outcome(boolean changed, CaptionBoundaryClient.Result response) { this.changed = changed; this.response = response; }
    }

    final class Pending {
        final CaptionSession session;
        final CaptionSession.Snapshot base;
        final SegmentationProposal proposal;
        final CaptionSession.AnalysisTicket ticket;
        final CaptionSession.Guard admission;
        final CaptionBoundaryClient.Job job;
        final Key key;
        final int maxUnitCodePoints;
        private volatile boolean cancelled;
        Pending(CaptionSession session, CaptionSession.Snapshot base, SegmentationProposal proposal,
                CaptionSession.AnalysisTicket ticket, CaptionSession.Guard admission, CaptionBoundaryClient.Job job, Key key,
                int maxUnitCodePoints) {
            this.session = session;
            this.base = base;
            this.proposal = proposal;
            this.ticket = ticket;
            this.admission = admission;
            this.job = job;
            this.key = key;
            this.maxUnitCodePoints = maxUnitCodePoints;
        }
        void cancel() { cancelled = true; job.cancel(); }
        Outcome execute() {
            if (cancelled || !reserve(key)) return new Outcome(false, null);
            CaptionBoundaryClient.Result response = job.execute();
            boolean committed = false;
            if (!cancelled && response.protocol != null && response.protocol.complete()) {
                // Refinement must not turn bounded display groups into a whole-screen paragraph.
                // Reject the optional decision as a whole; source text and its clocks stay unchanged.
                for (SegmentationProposal.AcceptedUnit unit : response.protocol.decision.units) {
                    if (unit.originalText.codePointCount(0, unit.originalText.length()) > maxUnitCodePoints) {
                        return new Outcome(false, response);
                    }
                }
                CaptionSession.Guard current = action -> {
                    if (cancelled) return false;
                    return admission.run(() -> { if (!cancelled) action.run(); });
                };
                committed = session.publishSegmentation(ticket, proposal, response.protocol.decision, PLAN_LIMITS, current);
            }
            return new Outcome(committed && session.getSnapshot() != base, response);
        }
    }

    private static final class Key {
        final CaptionSession.CacheScope source;
        final TranslationCoordinator.CredentialScope credentials;
        final byte[] bytes;
        Key(CaptionSession.CacheScope source, TranslationCoordinator.CredentialScope credentials, byte[] bytes) {
            this.source = source;
            this.credentials = credentials;
            this.bytes = bytes;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key key = (Key) other;
            return source == key.source && credentials == key.credentials && Arrays.equals(bytes, key.bytes);
        }
        @Override public int hashCode() {
            return 31 * (31 * System.identityHashCode(source) + System.identityHashCode(credentials)) + Arrays.hashCode(bytes);
        }
    }

    private final CaptionBoundaryClient mClient;
    private final TranslationCoordinator.Clock mClock;
    private final Map<Key, Long> mTried = new LinkedHashMap<>();

    CaptionBoundaryRefiner(CaptionHttpTransport transport, TranslationCoordinator.Clock clock) {
        mClient = new CaptionBoundaryClient(transport);
        mClock = clock;
    }

    Pending prepare(CaptionSession session, CaptionSession.Snapshot snapshot, TranslationCoordinator.Profile profile,
                    List<TranslationBatch.Item> missing, CaptionSession.Guard admission) {
        if (snapshot == null || profile == null || admission == null || missing.isEmpty() ||
                snapshot.transcript.normalizationVersion() != 1) return null;
        int first = missing.get(0).groupIndex, end = first, codePoints = 0, maxUnitCodePoints = 0;
        int flow = snapshot.groups.getFlowIndex(first);
        for (TranslationBatch.Item item : missing) {
            if (end - first == 4 || item.groupIndex != end || snapshot.groups.getFlowIndex(end) != flow ||
                    snapshot.groups.getGroups().get(end).boundary == SemanticGroupPlanner.Boundary.TYPED) break;
            int points = item.source.codePointCount(0, item.source.length());
            if (points > 512 - codePoints) break;
            codePoints += points;
            maxUnitCodePoints = Math.max(maxUnitCodePoints, points);
            end++;
        }
        if (end == first) return null;
        Key key = key(snapshot, profile, first, end);
        synchronized (this) { expire(); if (mTried.containsKey(key)) return null; }
        try {
            SegmentationProposal proposal = SegmentationProposal.prepareBlock(snapshot.transcript, snapshot.groups,
                    first, end, 64, PROPOSAL_LIMITS);
            CaptionBoundaryAdapter.encode(proposal, profile.wire, profile.sourceLanguage);
            CaptionSession.AnalysisTicket ticket = session.beginAnalysis(snapshot);
            if (ticket == null) return null;
            return new Pending(session, snapshot, proposal, ticket, admission,
                    mClient.newJob(proposal, profile.wire, profile.credentials, profile.sourceLanguage), key, maxUnitCodePoints);
        } catch (IllegalArgumentException overBudget) {
            return null;
        }
    }

    private synchronized boolean reserve(Key key) {
        expire();
        if (mTried.containsKey(key)) return false;
        while (mTried.size() >= MAX_TRIED) mTried.remove(mTried.keySet().iterator().next());
        long now = Math.max(0, mClock.nowMs());
        mTried.put(key, now > Long.MAX_VALUE - TRIED_TTL_MS ? Long.MAX_VALUE : now + TRIED_TTL_MS);
        return true;
    }
    private void expire() {
        long now = Math.max(0, mClock.nowMs());
        Iterator<Map.Entry<Key, Long>> iterator = mTried.entrySet().iterator();
        while (iterator.hasNext()) if (now >= iterator.next().getValue()) iterator.remove();
    }

    private static Key key(CaptionSession.Snapshot snapshot, TranslationCoordinator.Profile profile, int first, int end) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            text(hash, "caption-boundaries-1");
            text(hash, profile.wire.endpoint.toString());
            text(hash, profile.wire.model);
            text(hash, profile.wire.thinking.name());
            text(hash, profile.wire.tokenField.name());
            text(hash, profile.sourceLanguage);
            text(hash, profile.wire.requestBytes + "/" + profile.wire.responseBytes + "/" + Math.min(512, profile.wire.maxOutputTokens));
            for (int group = first; group < end; group++) {
                text(hash, snapshot.groups.getText(group));
                hash.update(SemanticTranslationCache.slot(snapshot, group));
            }
            return new Key(snapshot.track.cacheScope, profile.cacheScope, hash.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Caption boundary digest unavailable");
        }
    }
    private static void text(MessageDigest digest, String value) {
        byte[] bytes = value == null ? new byte[0] : value.getBytes(UTF8);
        digest.update((byte) (bytes.length >>> 24)); digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8)); digest.update((byte) bytes.length);
        digest.update(bytes);
    }
}
