package com.liskovsoft.smartyoutubetv2.common.subtitle.translation;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONException;
import org.json.JSONObject;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.util.Map;
import java.util.WeakHashMap;

/** Global connection persistence on the settings worker. Snapshots never expose a plaintext Key. */
public final class CaptionConnectionStore {
    public enum KeyEdit { KEEP, REPLACE, CLEAR }
    public static final class Snapshot {
        public final CaptionConnection connection;
        public final long revision;
        public final boolean valid;
        public final boolean hasKey;
        public final boolean persistentKey;
        private final CaptionConnectionStore mOwner;
        private final State mState;
        private TranslationCoordinator.Profile mProfile;
        private String mTarget;
        private Snapshot(CaptionConnectionStore owner, State state, boolean valid) {
            mOwner = owner;
            mState = state;
            connection = state.connection;
            revision = state.revision;
            this.valid = valid;
            hasKey = state.credential != null && state.credential.getKey() != null;
            persistentKey = hasKey && state.encrypted != null;
        }

        /** Pure in-memory resolution; repeated presentation changes preserve the same request namespace. */
        public synchronized TranslationCoordinator.Profile profile(String targetLanguage) {
            if (!valid || connection == null || !hasKey || mState.credential.getKey() == null || !TranslationBatch.language(targetLanguage)) return null;
            if (mProfile == null || !targetLanguage.equals(mTarget)) {
                mTarget = targetLanguage;
                mProfile = new TranslationCoordinator.Profile(connection.wire, mState.credential, revision, null,
                        targetLanguage, "", connection.batch, connection.maxCalls);
            }
            return mProfile;
        }
    }

    private static final class Credential implements TranslationCoordinator.ScopedCredentials {
        private final TranslationCoordinator.CredentialScope mCacheScope = new TranslationCoordinator.CredentialScope();
        private volatile String mKey;
        Credential(String key) { mKey = key; }
        public String getKey() { return mKey; }
        public TranslationCoordinator.CredentialScope getCaptionCacheScope() { return mCacheScope; }
        void revoke() { mCacheScope.revoke(); mKey = null; }
    }
    private static final class State {
        final CaptionConnection connection;
        final Credential credential;
        final String encrypted;
        final String raw;
        final long revision;
        State(CaptionConnection connection, Credential credential, String encrypted, String raw, long revision) {
            this.connection = connection;
            this.credential = credential;
            this.encrypted = encrypted;
            this.raw = raw;
            this.revision = revision;
        }
    }
    private static final class Cached {
        State good;
        boolean failed;
        Cached(State good) { this.good = good; }
    }
    // Values do not retain a Store/SharedPreferences: no reference cycle through weak keys.
    private static final Map<SharedPreferences, Cached> CACHE = new WeakHashMap<>();
    private static final String DATA = "connection";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private final SharedPreferences mPreferences;
    private final CaptionSecretCipher mCipher;
    private final String mNamespace;
    private boolean mClosed;

    public CaptionConnectionStore(Context context, String nativeProfileScope) {
        this(context.getApplicationContext().getSharedPreferences(name(nativeProfileScope), Context.MODE_PRIVATE),
                CaptionSecretCipher.create(name(nativeProfileScope)), name(nativeProfileScope));
    }
    CaptionConnectionStore(SharedPreferences preferences, CaptionSecretCipher cipher, String namespace) {
        if (preferences == null || cipher == null || namespace == null) throw TranslationBatch.invalid();
        mPreferences = preferences;
        mCipher = cipher;
        mNamespace = namespace;
    }
    private static String name(String scope) { return CaptionPreferenceStore.storageName(scope).replace("caption_preferences_", "caption_connection_"); }

    public Snapshot load() {
        synchronized (CaptionConnectionStore.class) {
            if (mClosed) throw new IllegalStateException("Caption connection store is closed");
            Cached cached = CACHE.get(mPreferences);
            if (cached != null && cached.failed) return new Snapshot(this, cached.good, false);
            String raw;
            try { raw = mPreferences.getString(DATA, null); }
            catch (RuntimeException ignored) { return new Snapshot(this, empty(null), false); }
            if (cached != null && same(raw, cached.good.raw)) return new Snapshot(this, cached.good, true);
            if (cached != null && cached.good.credential != null) cached.good.credential.revoke();
            State state;
            try { state = raw == null ? empty(null) : decode(raw); }
            catch (RuntimeException ignored) { return new Snapshot(this, empty(raw), false); }
            CACHE.put(mPreferences, new Cached(state));
            return new Snapshot(this, state, true);
        }
    }

    /** Empty editor means KEEP, not clear. A different endpoint cannot silently reuse the old Key. */
    public Snapshot save(Snapshot expected, CaptionConnection connection, KeyEdit edit, String replacement) {
        if (connection == null || edit == null || edit == KeyEdit.REPLACE && !validKey(replacement) ||
                edit != KeyEdit.REPLACE && replacement != null) throw TranslationBatch.invalid();
        synchronized (CaptionConnectionStore.class) {
            if (!current(expected) || expected.revision == Long.MAX_VALUE) return null;
            State before = expected.mState;
            if (edit == KeyEdit.KEEP && before.encrypted != null && before.credential == null) return null;
            if (edit == KeyEdit.KEEP && before.connection != null && (before.credential != null || before.encrypted != null) &&
                    !before.connection.wire.endpoint.equals(connection.wire.endpoint)) return null;
            if (edit == KeyEdit.KEEP && before.connection != null && config(before.connection).toString().equals(config(connection).toString())) return expected;
            String key = edit == KeyEdit.REPLACE ? replacement : edit == KeyEdit.CLEAR || before.credential == null ? null : before.credential.getKey();
            long revision = before.revision + 1;
            String encrypted = null;
            String config = config(connection).toString();
            if (key != null) {
                try { encrypted = mCipher.seal(key, binding(config, revision)); }
                catch (GeneralSecurityException | RuntimeException ignored) {
                    if (edit == KeyEdit.KEEP && before.encrypted != null) return null;
                    // New/replaced Keys may remain memory-only, but never downgrade a kept persistent Key.
                }
            }
            String raw;
            try {
                raw = new JSONObject().put("version", 1).put("revision", Long.toString(revision))
                        .put("config", new JSONObject(config)).put("secret", encrypted == null ? JSONObject.NULL : encrypted).toString();
            } catch (JSONException ignored) { throw TranslationBatch.invalid(); }
            if (raw.getBytes(UTF8).length > 65536) return null;
            Cached cached = new Cached(before);
            cached.failed = true;
            CACHE.put(mPreferences, cached);
            boolean success;
            try { success = mPreferences.edit().putString(DATA, raw).commit(); }
            catch (RuntimeException ignored) { success = false; }
            if (!success) { restore(before.raw); return null; }
            State next = new State(connection, key == null ? null : new Credential(key), encrypted, raw, revision);
            if (before.credential != null) before.credential.revoke();
            CACHE.put(mPreferences, new Cached(next));
            return new Snapshot(this, next, true);
        }
    }

    /** Explicitly remove an unreadable connection too; callers must confirm this destructive user action. */
    public Snapshot clear(Snapshot expected) {
        synchronized (CaptionConnectionStore.class) {
            if (!matches(expected)) return null;
            Cached cached = CACHE.get(mPreferences);
            if (cached != null && cached.failed) return null;
            State before = expected.mState;
            if (before.revision == Long.MAX_VALUE) return null;
            long revision = before.revision + 1;
            String raw;
            try {
                raw = new JSONObject().put("version", 1).put("revision", Long.toString(revision))
                        .put("config", JSONObject.NULL).put("secret", JSONObject.NULL).toString();
            } catch (JSONException ignored) { throw TranslationBatch.invalid(); }
            cached = new Cached(before);
            cached.failed = true;
            CACHE.put(mPreferences, cached);
            if (!restore(raw)) { restore(before.raw); return null; }
            if (before.credential != null) before.credential.revoke();
            State next = new State(null, null, null, raw, revision);
            CACHE.put(mPreferences, new Cached(next));
            return new Snapshot(this, next, true);
        }
    }
    public Snapshot recoverFailedWrite() {
        synchronized (CaptionConnectionStore.class) {
            if (mClosed) return null;
            Cached cached = CACHE.get(mPreferences);
            if (cached != null && cached.failed) {
                if (!restore(cached.good.raw)) return null;
                cached.failed = false;
            }
            return load();
        }
    }
    public void close() { synchronized (CaptionConnectionStore.class) { mClosed = true; } }
    private boolean current(Snapshot expected) {
        Cached cached = CACHE.get(mPreferences);
        return expected != null && expected.valid && (cached == null || !cached.failed) && matches(expected);
    }
    private boolean matches(Snapshot expected) {
        if (mClosed || expected == null || expected.mOwner != this) return false;
        try { return same(mPreferences.getString(DATA, null), expected.mState.raw); }
        catch (RuntimeException ignored) { return false; }
    }
    private boolean restore(String raw) {
        try {
            SharedPreferences.Editor editor = mPreferences.edit();
            if (raw == null) editor.remove(DATA); else editor.putString(DATA, raw);
            return editor.commit();
        } catch (RuntimeException ignored) { return false; }
    }
    private State decode(String raw) {
        if (raw.length() > 65536 || raw.getBytes(UTF8).length > 65536) throw TranslationBatch.invalid();
        JSONObject object = BoundedJson.object(raw);
        keys(object, "version", "revision", "config", "secret");
        if (number(object, "version") != 1) throw TranslationBatch.invalid();
        String rev = BoundedJson.string(object, "revision");
        if (rev == null || !rev.matches("[0-9]{1,19}")) throw TranslationBatch.invalid();
        long revision = Long.parseLong(rev);
        if (object.isNull("config")) {
            if (!object.isNull("secret")) throw TranslationBatch.invalid();
            return new State(null, null, null, raw, revision);
        }
        JSONObject encoded = object.optJSONObject("config");
        keys(encoded, "endpoint", "model", "tokens", "requestBytes", "responseBytes", "tokenField", "thinking", "items", "source", "context", "translated", "calls");
        CaptionConnection connection = new CaptionConnection(new ChatCompletionAdapter.Config(required(encoded, "endpoint"), required(encoded, "model"),
                number(encoded, "tokens"), number(encoded, "requestBytes"), number(encoded, "responseBytes"),
                ChatCompletionAdapter.TokenField.valueOf(required(encoded, "tokenField")), ChatCompletionAdapter.Thinking.valueOf(required(encoded, "thinking"))),
                new TranslationBatch.Limits(number(encoded, "items"), number(encoded, "source"), number(encoded, "context"), number(encoded, "translated")), number(encoded, "calls"));
        String encrypted = object.isNull("secret") ? null : required(object, "secret");
        String key = null;
        if (encrypted != null) {
            if (encrypted.length() > 32768) throw TranslationBatch.invalid();
            try { key = mCipher.open(encrypted, binding(config(connection).toString(), revision)); }
            catch (GeneralSecurityException | RuntimeException ignored) { /* Unavailable/invalid secret remains on disk for explicit replacement/clear. */ }
            if (key != null && !validKey(key)) key = null;
        }
        return new State(connection, key == null ? null : new Credential(key), encrypted, raw, revision);
    }
    private String binding(String config, long revision) { return mNamespace + "\n" + revision + "\n" + config; }
    private static JSONObject config(CaptionConnection c) {
        try {
            return new JSONObject().put("endpoint", c.wire.endpoint.toString()).put("model", c.wire.model).put("tokens", c.wire.maxOutputTokens)
                    .put("requestBytes", c.wire.requestBytes).put("responseBytes", c.wire.responseBytes).put("tokenField", c.wire.tokenField.name())
                    .put("thinking", c.wire.thinking.name()).put("items", c.batch.items).put("source", c.batch.sourceCodePoints)
                    .put("context", c.batch.contextCodePoints).put("translated", c.batch.translatedCodePoints).put("calls", c.maxCalls);
        } catch (JSONException ignored) { throw TranslationBatch.invalid(); }
    }
    static boolean validKey(String key) {
        if (key == null || key.isEmpty() || key.length() > 8192) return false;
        for (int i = 0; i < key.length(); i++) if (key.charAt(i) < 33 || key.charAt(i) > 126) return false;
        return true;
    }
    private static State empty(String raw) { return new State(null, null, null, raw, 0); }
    private static boolean same(String a, String b) { return a == null ? b == null : a.equals(b); }
    private static String required(JSONObject object, String key) {
        String value = BoundedJson.string(object, key);
        if (value == null) throw TranslationBatch.invalid();
        return value;
    }
    private static int number(JSONObject object, String key) {
        Object value = object.opt(key);
        if (!(value instanceof Number)) throw TranslationBatch.invalid();
        try { return new java.math.BigDecimal(value.toString()).intValueExact(); }
        catch (ArithmeticException ignored) { throw TranslationBatch.invalid(); }
    }
    private static void keys(JSONObject object, String... names) {
        if (object == null || object.length() != names.length) throw TranslationBatch.invalid();
        for (String name : names) if (!object.has(name)) throw TranslationBatch.invalid();
    }
}
