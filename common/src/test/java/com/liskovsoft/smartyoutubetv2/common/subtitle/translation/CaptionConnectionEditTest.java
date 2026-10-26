package com.liskovsoft.smartyoutubetv2.common.subtitle.translation;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 27)
public class CaptionConnectionEditTest {
    private static final String FIXTURE_KEY = "synthetic-phone-form-key-not-a-real-key";

    @Test public void boundedFormPreservesHiddenConnectionBudgetsAndExactRevision() throws Exception {
        CaptionConnection previous = new CaptionConnection(new ChatCompletionAdapter.Config("https://example.invalid/chat", "before", 99,
                8192, 16384, ChatCompletionAdapter.TokenField.MAX_COMPLETION_TOKENS, ChatCompletionAdapter.Thinking.OMIT),
                new TranslationBatch.Limits(2, 100, 20, 200), 3);
        JSONObject body = form().put("revision", "9007199254740993").put("model", "模型名称");
        CaptionConnectionEdit edit = parse(body, previous);
        try {
            assertEquals(9007199254740993L, edit.revision);
            assertEquals("模型名称", edit.connection.wire.model);
            assertSame(previous.batch, edit.connection.batch);
            assertEquals(previous.maxCalls, edit.connection.maxCalls);
            assertEquals(previous.wire.requestBytes, edit.connection.wire.requestBytes);
            assertEquals(previous.wire.responseBytes, edit.connection.wire.responseBytes);
            assertEquals(previous.wire.tokenField, edit.connection.wire.tokenField);
        } finally { edit.discard(); }
    }

    @Test public void replacementIsTransientAndDiscardRevokesTheTestCredential() throws Exception {
        CaptionConnectionEdit edit = parse(form().put("operation", "test"), null);
        ChatTranslationClient.Credentials credential = edit.testCredentials(null, "zh");
        assertNotNull(credential);
        assertEquals(FIXTURE_KEY, credential.getKey());
        assertFalse(edit.toString().contains(FIXTURE_KEY));
        edit.discard();
        assertNull(edit.replacement());
        assertNull(credential.getKey());
    }

    @Test public void readStateNeverReturnsStoredKeyAndKeepCannotTestAnotherEndpoint() throws Exception {
        SharedPreferences preferences = RuntimeEnvironment.getApplication().getSharedPreferences("connection-edit-" + UUID.randomUUID(), Context.MODE_PRIVATE);
        CaptionSecretCipher unavailable = new CaptionSecretCipher() {
            public String seal(String secret, String binding) throws GeneralSecurityException { throw new GeneralSecurityException(); }
            public String open(String encrypted, String binding) throws GeneralSecurityException { throw new GeneralSecurityException(); }
        };
        CaptionConnectionStore store = new CaptionConnectionStore(preferences, unavailable, "synthetic-phone-form");
        try {
            CaptionConnection connection = CaptionConnection.basic("https://example.invalid/chat", "fixture-model", 1024, ChatCompletionAdapter.Thinking.OMIT);
            CaptionConnectionStore.Snapshot saved = store.save(store.load(), connection, CaptionConnectionStore.KeyEdit.REPLACE, FIXTURE_KEY);
            assertNotNull(saved);
            JSONObject state = new JSONObject(CaptionConnectionEdit.state(saved));
            assertEquals("MEMORY_ONLY", state.getString("keyState"));
            assertFalse(state.toString().contains(FIXTURE_KEY));
            assertFalse(state.has("key"));
            CaptionConnectionEdit keep = parse(form().put("operation", "test").put("keyEdit", "KEEP").put("key", JSONObject.NULL), connection);
            ChatTranslationClient.Credentials kept = keep.testCredentials(saved, "zh");
            assertNotNull(kept);
            assertEquals(FIXTURE_KEY, kept.getKey());
            keep.discard();
            assertNull(kept.getKey());
            assertNull(keep.testCredentials(saved, "zh"));
            assertEquals(FIXTURE_KEY, saved.profile("zh").credentials.getKey());
            CaptionConnectionEdit changed = parse(form().put("operation", "test").put("endpoint", "https://other.invalid/chat")
                    .put("keyEdit", "KEEP").put("key", JSONObject.NULL), connection);
            try { assertNull(changed.testCredentials(saved, "zh")); } finally { changed.discard(); }
            CaptionConnectionEdit clear = parse(form().put("operation", "test").put("keyEdit", "CLEAR").put("key", JSONObject.NULL), connection);
            try { assertNull(clear.testCredentials(saved, "zh")); } finally { clear.discard(); }
        } finally { store.close(); }
    }

    @Test public void clearConnectionHasNoHiddenFormFieldsOrSecret() throws Exception {
        CaptionConnectionEdit edit = parse(new JSONObject().put("version", 1).put("revision", "0").put("operation", "clear"), null);
        assertEquals(CaptionConnectionEdit.Operation.CLEAR, edit.operation);
        assertNull(edit.connection);
        assertNull(edit.keyEdit);
        assertNull(edit.replacement());
        rejected("{\"version\":1,\"revision\":\"0\",\"operation\":\"clear\",\"key\":null}");
    }

    @Test public void malformedOrAmbiguousInputsAreRejectedWithoutEchoingTheirContent() throws Exception {
        String valid = form().toString();
        rejected(valid.replace("\"version\":1", "\"version\":1,\"version\":1"));
        rejected(valid + "{}");
        rejected(form().put("tokens", "1024").toString());
        rejected(form().put("tokens", 1.5).toString());
        rejected(form().put("tokens", true).toString());
        rejected(form().put("tokens", 0).toString());
        rejected(form().put("tokens", 1048577).toString());
        rejected(form().put("revision", 1).toString());
        rejected(form().put("revision", "01").toString());
        rejected(form().put("revision", "9223372036854775808").toString());
        rejected(form().put("extra", false).toString());
        rejected(form().put("operation", "anything").toString());
        rejected(form().put("keyEdit", "KEEP").toString());
        rejected(form().put("keyEdit", "CLEAR").toString());
        rejected(form().put("key", "").toString());
        rejected(form().put("key", FIXTURE_KEY + "\n").toString());
        rejected(form().put("key", " " + FIXTURE_KEY).toString());
        rejected(form().put("key", "非ASCII").toString());
        rejected(form().put("endpoint", "http://example.invalid/chat").toString());
        rejected(form().put("endpoint", "https://user:password@example.invalid/chat").toString());
        rejected(form().put("endpoint", "https://example.invalid/chat?key=synthetic").toString());
        rejected(form().put("thinking", "unknown").toString());
        try { CaptionConnectionEdit.parse(new byte[]{(byte) 0xc3, (byte) 0x28}, null); fail(); }
        catch (IllegalArgumentException expected) { }
        try { CaptionConnectionEdit.parse(new byte[32769], null); fail(); }
        catch (IllegalArgumentException expected) { }
    }

    private static CaptionConnectionEdit parse(JSONObject body, CaptionConnection previous) {
        return CaptionConnectionEdit.parse(body.toString().getBytes(Charset.forName("UTF-8")), previous);
    }
    private static JSONObject form() throws Exception {
        return new JSONObject().put("version", 1).put("revision", "0").put("operation", "save")
                .put("endpoint", "https://example.invalid/chat").put("model", "fixture-model").put("tokens", 1024)
                .put("thinking", "OMIT").put("keyEdit", "REPLACE").put("key", FIXTURE_KEY);
    }
    private static void rejected(String json) {
        try { CaptionConnectionEdit.parse(json.getBytes(Charset.forName("UTF-8")), null); fail("Expected invalid form"); }
        catch (IllegalArgumentException expected) { assertFalse(String.valueOf(expected.getMessage()).contains(FIXTURE_KEY)); }
    }
}
