package com.liskovsoft.smartyoutubetv2.common.subtitle.translation;

import android.content.Context;
import android.content.SharedPreferences;

import com.liskovsoft.smartyoutubetv2.common.subtitle.presentation.NativeCueComposer;
import com.liskovsoft.smartyoutubetv2.common.subtitle.source.CaptionSource;

import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** Settings-worker persistence, bound to the caller's existing native user profile; never a secret store. */
public final class CaptionPreferenceStore {
    private static final String DATA = "settings";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    public static final class Limits {
        public final int channels;
        public final int bytes;
        public Limits(int channels, int bytes) {
            if (channels <= 0 || channels > 8192 || bytes <= 0 || bytes > 1024 * 1024) throw CaptionPreferences.invalid();
            this.channels = channels;
            this.bytes = bytes;
        }
    }

    public static final class Snapshot {
        public final CaptionPreferences preferences;
        /** Invalid/unknown-version data is retained, not silently overwritten with defaults. */
        public final boolean valid;
        private final CaptionPreferenceStore mOwner;
        private final String mRaw;
        private final long mRevision;
        private Snapshot(CaptionPreferenceStore owner, CaptionPreferences preferences, boolean valid, String raw, long revision) {
            mOwner = owner;
            this.preferences = preferences;
            this.valid = valid;
            mRaw = raw;
            mRevision = revision;
        }
    }

    private static final class WriteFailure {
        final CaptionPreferences preferences;
        final String raw;
        final long revision;
        WriteFailure(Snapshot before) {
            preferences = before.preferences;
            raw = before.mRaw;
            revision = before.mRevision;
        }
    }

    // SharedPreferences publishes memory before reporting disk failure; all in-process owners must quarantine it.
    private static final Map<SharedPreferences, WriteFailure> WRITES = new WeakHashMap<>();
    private final SharedPreferences mPreferences;
    private final Limits mLimits;
    private boolean mClosed;

    /** nativeProfileScope must come from the existing account/profile owner, not an API Key. */
    public CaptionPreferenceStore(Context context, String nativeProfileScope, Limits limits) {
        this(open(context, nativeProfileScope), limits);
    }

    CaptionPreferenceStore(SharedPreferences preferences, Limits limits) {
        if (preferences == null || limits == null) throw CaptionPreferences.invalid();
        mPreferences = preferences;
        mLimits = limits;
    }

    private static SharedPreferences open(Context context, String scope) {
        if (context == null) throw CaptionPreferences.invalid();
        return context.getApplicationContext().getSharedPreferences(storageName(scope), Context.MODE_PRIVATE);
    }

    public Snapshot load() {
        synchronized (CaptionPreferenceStore.class) {
            if (mClosed) throw new IllegalStateException("Caption preference store is closed");
            WriteFailure failure = WRITES.get(mPreferences);
            if (failure != null) return new Snapshot(this, failure.preferences, false, failure.raw, failure.revision);
            String raw;
            try { raw = mPreferences.getString(DATA, null); }
            catch (ClassCastException ignored) { return new Snapshot(this, defaults(), false, null, 0); }
            if (raw == null) return new Snapshot(this, defaults(), true, null, 0);
            try { return decode(raw); }
            catch (IllegalArgumentException ignored) { return new Snapshot(this, defaults(), false, raw, 0); }
        }
    }

    public Snapshot updateGlobal(Snapshot expected, CaptionPreferences.Global global) {
        if (global == null) throw CaptionPreferences.invalid();
        synchronized (CaptionPreferenceStore.class) {
            if (!current(expected)) return null;
            return save(new CaptionPreferences(global, expected.preferences.channels()), expected);
        }
    }

    public Snapshot updateChannel(Snapshot expected, String channelId, CaptionPreferences.Channel channel) {
        if (!CaptionPreferences.identifier(channelId) || channel == null) throw CaptionPreferences.invalid();
        synchronized (CaptionPreferenceStore.class) {
            if (!current(expected)) return null;
            Map<String, CaptionPreferences.Channel> channels = new LinkedHashMap<>(expected.preferences.channels());
            channels.put(channelId, channel);
            if (channels.size() > mLimits.channels) return null;
            return save(new CaptionPreferences(expected.preferences.global, channels), expected);
        }
    }

    public Snapshot resetChannel(Snapshot expected, String channelId) {
        if (!CaptionPreferences.identifier(channelId)) throw CaptionPreferences.invalid();
        synchronized (CaptionPreferenceStore.class) {
            if (!current(expected)) return null;
            Map<String, CaptionPreferences.Channel> channels = new LinkedHashMap<>(expected.preferences.channels());
            channels.remove(channelId);
            return save(new CaptionPreferences(expected.preferences.global, channels), expected);
        }
    }

    public void close() {
        synchronized (CaptionPreferenceStore.class) { mClosed = true; }
    }

    private boolean current(Snapshot expected) {
        if (mClosed || WRITES.containsKey(mPreferences) || expected == null || expected.mOwner != this || !expected.valid) return false;
        try {
            String actual = mPreferences.getString(DATA, null);
            return actual == null ? expected.mRaw == null : actual.equals(expected.mRaw);
        } catch (ClassCastException ignored) { return false; }
    }

    private Snapshot save(CaptionPreferences preferences, Snapshot before) {
        long revision = before.mRevision;
        if (revision == Long.MAX_VALUE) return null;
        String raw;
        try { raw = encode(preferences, revision + 1); }
        catch (IllegalArgumentException ignored) { return null; }
        WriteFailure failure = new WriteFailure(before);
        WRITES.put(mPreferences, failure);
        boolean saved;
        try { saved = mPreferences.edit().putString(DATA, raw).commit(); }
        catch (RuntimeException ignored) { saved = false; }
        if (!saved) {
            restore(failure.raw); // Best effort memory/disk rollback; quarantine stays until explicit recovery.
            return null;
        }
        WRITES.remove(mPreferences);
        return new Snapshot(this, preferences, true, raw, revision + 1);
    }

    /** Retry the last known-good data only, never silently commit the abandoned failed edit. */
    public Snapshot recoverFailedWrite() {
        synchronized (CaptionPreferenceStore.class) {
            if (mClosed) return null;
            WriteFailure failure = WRITES.get(mPreferences);
            if (failure == null) return load();
            if (!restore(failure.raw)) return null;
            WRITES.remove(mPreferences);
            return load();
        }
    }

    private boolean restore(String raw) {
        try {
            SharedPreferences.Editor editor = mPreferences.edit();
            if (raw == null) editor.remove(DATA);
            else editor.putString(DATA, raw);
            return editor.commit();
        } catch (RuntimeException ignored) { return false; }
    }

    private String encode(CaptionPreferences preferences, long revision) {
        try {
            CaptionPreferences.Global global = preferences.global;
            JSONObject object = new JSONObject().put("version", 1).put("revision", Long.toString(revision));
            object.put("global", new JSONObject().put("target", nullable(global.targetLanguage))
                    .put("mode", global.mode.name()).put("order", global.order.name()).put("connection", nullable(global.connectionId)));
            JSONObject channels = new JSONObject();
            for (Map.Entry<String, CaptionPreferences.Channel> entry : preferences.channels().entrySet()) {
                CaptionPreferences.Channel channel = entry.getValue();
                channels.put(entry.getKey(), new JSONObject().put("translate", channel.translate)
                        .put("language", nullable(channel.subtitleLanguage)).put("kind", channel.subtitleKind.name()));
            }
            object.put("channels", channels);
            String result = object.toString();
            if (result.getBytes(UTF8).length > mLimits.bytes) throw CaptionPreferences.invalid();
            return result;
        } catch (JSONException ignored) { throw CaptionPreferences.invalid(); }
    }

    private Snapshot decode(String source) {
        if (source.length() > mLimits.bytes || source.getBytes(UTF8).length > mLimits.bytes) throw CaptionPreferences.invalid();
        try {
            JSONObject root = BoundedJson.object(source);
            keys(root, "version", "revision", "global", "channels");
            String revisionText = required(root, "revision");
            if (!revisionText.matches("[0-9]{1,19}")) throw CaptionPreferences.invalid();
            long revision = Long.parseLong(revisionText);
            Object version = root.opt("version");
            if (!(version instanceof Number) || new BigDecimal(version.toString()).compareTo(BigDecimal.ONE) != 0) throw CaptionPreferences.invalid();
            JSONObject global = root.optJSONObject("global"), channels = root.optJSONObject("channels");
            keys(global, "target", "mode", "order", "connection");
            if (channels == null || channels.length() > mLimits.channels) throw CaptionPreferences.invalid();
            CaptionPreferences.Global settings = new CaptionPreferences.Global(optional(global, "target"),
                    NativeCueComposer.Mode.valueOf(required(global, "mode")),
                    NativeCueComposer.Order.valueOf(required(global, "order")), optional(global, "connection"));
            Map<String, CaptionPreferences.Channel> result = new LinkedHashMap<>();
            Iterator<String> names = channels.keys();
            while (names.hasNext()) {
                String id = names.next();
                if (!CaptionPreferences.identifier(id)) throw CaptionPreferences.invalid();
                JSONObject channel = channels.optJSONObject(id);
                keys(channel, "translate", "language", "kind");
                Object enabled = channel.opt("translate");
                if (!(enabled instanceof Boolean)) throw CaptionPreferences.invalid();
                result.put(id, new CaptionPreferences.Channel((Boolean) enabled, optional(channel, "language"),
                        CaptionSource.TextKind.valueOf(required(channel, "kind"))));
            }
            return new Snapshot(this, new CaptionPreferences(settings, result), true, source, revision);
        } catch (RuntimeException ignored) { throw CaptionPreferences.invalid(); }
    }

    private static void keys(JSONObject object, String... keys) {
        if (object == null || object.length() != keys.length) throw CaptionPreferences.invalid();
        for (String key : keys) if (!object.has(key)) throw CaptionPreferences.invalid();
    }

    private static Object nullable(String value) { return value == null ? JSONObject.NULL : value; }
    private static String optional(JSONObject object, String key) {
        if (object.isNull(key)) return null;
        return required(object, key);
    }
    private static String required(JSONObject object, String key) {
        String value = BoundedJson.string(object, key);
        if (value == null) throw CaptionPreferences.invalid();
        return value;
    }
    private static CaptionPreferences defaults() {
        return new CaptionPreferences(CaptionPreferences.Global.defaults(), Collections.emptyMap());
    }

    static String storageName(String nativeProfileScope) {
        if (nativeProfileScope == null || nativeProfileScope.isEmpty() || nativeProfileScope.length() > 4096 ||
                !TranslationBatch.validText(nativeProfileScope)) throw CaptionPreferences.invalid();
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(nativeProfileScope.getBytes(UTF8));
            StringBuilder name = new StringBuilder("caption_preferences_");
            for (byte b : hash) { name.append(Character.forDigit((b & 255) >>> 4, 16)); name.append(Character.forDigit(b & 15, 16)); }
            return name.toString();
        } catch (NoSuchAlgorithmException ignored) { throw CaptionPreferences.invalid(); }
    }
}
