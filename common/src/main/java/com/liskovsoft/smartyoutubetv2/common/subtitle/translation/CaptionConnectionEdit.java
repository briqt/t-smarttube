package com.liskovsoft.smartyoutubetv2.common.subtitle.translation;

import org.json.JSONException;
import org.json.JSONObject;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/** Bounded, transient phone-form input. State responses deliberately exclude credentials. */
public final class CaptionConnectionEdit {
    public enum Operation { SAVE, TEST, CLEAR }
    public final Operation operation;
    public final long revision;
    public final CaptionConnection connection;
    public final CaptionConnectionStore.KeyEdit keyEdit;
    private volatile String mReplacement;
    private volatile boolean mDiscarded;

    private CaptionConnectionEdit(Operation operation, long revision, CaptionConnection connection,
                                  CaptionConnectionStore.KeyEdit keyEdit, String replacement) {
        this.operation = operation;
        this.revision = revision;
        this.connection = connection;
        this.keyEdit = keyEdit;
        mReplacement = replacement;
    }

    public static CaptionConnectionEdit parse(byte[] utf8, CaptionConnection previous) {
        if (utf8 == null || utf8.length > 32768) throw TranslationBatch.invalid();
        String source;
        try {
            source = Charset.forName("UTF-8").newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(utf8)).toString();
        } catch (CharacterCodingException ignored) { throw TranslationBatch.invalid(); }
        JSONObject body = BoundedJson.object(source);
        if (integer(body, "version") != 1) throw TranslationBatch.invalid();
        String rawRevision = required(body, "revision");
        if (!rawRevision.matches("0|[1-9][0-9]{0,18}")) throw TranslationBatch.invalid();
        long revision;
        try { revision = Long.parseLong(rawRevision); }
        catch (NumberFormatException ignored) { throw TranslationBatch.invalid(); }
        String requested = required(body, "operation");
        Operation operation = "save".equals(requested) ? Operation.SAVE : "test".equals(requested) ? Operation.TEST :
                "clear".equals(requested) ? Operation.CLEAR : null;
        if (operation == null) throw TranslationBatch.invalid();
        if (operation == Operation.CLEAR) {
            keys(body, "version", "revision", "operation");
            return new CaptionConnectionEdit(operation, revision, null, null, null);
        }
        keys(body, "version", "revision", "operation", "endpoint", "model", "tokens", "thinking", "keyEdit", "key");
        String endpoint = required(body, "endpoint").trim();
        String model = required(body, "model").trim();
        if (endpoint.length() > 4096) throw TranslationBatch.invalid();
        ChatCompletionAdapter.Thinking thinking;
        CaptionConnectionStore.KeyEdit edit;
        try {
            thinking = ChatCompletionAdapter.Thinking.valueOf(required(body, "thinking"));
            edit = CaptionConnectionStore.KeyEdit.valueOf(required(body, "keyEdit"));
        } catch (IllegalArgumentException ignored) { throw TranslationBatch.invalid(); }
        String replacement = BoundedJson.string(body, "key");
        if (edit == CaptionConnectionStore.KeyEdit.REPLACE) {
            if (!CaptionConnectionStore.validKey(replacement)) throw TranslationBatch.invalid();
        } else if (!body.isNull("key")) throw TranslationBatch.invalid();
        CaptionConnection connection;
        int tokens = integer(body, "tokens");
        if (previous == null) {
            connection = CaptionConnection.basic(endpoint, model, tokens, thinking);
        } else {
            ChatCompletionAdapter.Config before = previous.wire;
            connection = new CaptionConnection(new ChatCompletionAdapter.Config(endpoint, model, tokens,
                    before.requestBytes, before.responseBytes, before.tokenField, thinking), previous.batch, previous.maxCalls);
        }
        return new CaptionConnectionEdit(operation, revision, connection, edit, replacement);
    }

    public String replacement() { return mReplacement; }
    public void discard() { mDiscarded = true; mReplacement = null; }

    /** A test must never send a stored credential to a newly entered endpoint. */
    public ChatTranslationClient.Credentials testCredentials(CaptionConnectionStore.Snapshot saved, String target) {
        if (mDiscarded || operation != Operation.TEST || connection == null || keyEdit == CaptionConnectionStore.KeyEdit.CLEAR) return null;
        if (keyEdit == CaptionConnectionStore.KeyEdit.REPLACE) {
            return mReplacement == null ? null : this::replacement;
        }
        if (saved == null || saved.connection == null || !saved.connection.wire.endpoint.equals(connection.wire.endpoint)) return null;
        TranslationCoordinator.Profile profile = saved.profile(target);
        return profile == null ? null : () -> mDiscarded ? null : profile.credentials.getKey();
    }

    public static String state(CaptionConnectionStore.Snapshot saved) {
        if (saved == null) throw TranslationBatch.invalid();
        try {
            JSONObject body = new JSONObject().put("version", 1).put("revision", Long.toString(saved.revision))
                    .put("valid", saved.valid).put("keyState", keyState(saved));
            CaptionConnection connection = saved.connection;
            if (connection == null) body.put("connection", JSONObject.NULL);
            else body.put("connection", new JSONObject().put("endpoint", connection.wire.endpoint.toString())
                    .put("model", connection.wire.model).put("tokens", connection.wire.maxOutputTokens)
                    .put("thinking", connection.wire.thinking.name()));
            return body.toString();
        } catch (JSONException ignored) { throw TranslationBatch.invalid(); }
    }

    public static String keyState(CaptionConnectionStore.Snapshot saved) {
        return !saved.hasKey ? "NONE" : saved.persistentKey ? "PERSISTENT" : "MEMORY_ONLY";
    }

    private static int integer(JSONObject body, String key) {
        Object value = body.opt(key);
        if (!(value instanceof BigDecimal)) throw TranslationBatch.invalid();
        try { return ((BigDecimal) value).intValueExact(); }
        catch (ArithmeticException ignored) { throw TranslationBatch.invalid(); }
    }
    private static String required(JSONObject body, String key) {
        String value = BoundedJson.string(body, key);
        if (value == null) throw TranslationBatch.invalid();
        return value;
    }
    private static void keys(JSONObject body, String... expected) {
        Set<String> names = new HashSet<>(Arrays.asList(expected));
        if (body.length() != names.size()) throw TranslationBatch.invalid();
        Iterator<String> actual = body.keys();
        while (actual.hasNext()) if (!names.contains(actual.next())) throw TranslationBatch.invalid();
    }
}
