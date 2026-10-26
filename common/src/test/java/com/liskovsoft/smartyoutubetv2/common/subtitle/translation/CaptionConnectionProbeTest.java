package com.liskovsoft.smartyoutubetv2.common.subtitle.translation;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 27)
public class CaptionConnectionProbeTest {
    @Test public void explicitProbeUsesOnlyOneSyntheticSentenceAndDoesNotPersist() throws Exception {
        CaptionConnectionStore store = store();
        CaptionConnectionEdit edit = form("test", "0");
        AtomicInteger calls = new AtomicInteger();
        try {
            CaptionConnectionStore.Snapshot before = store.load();
            ChatTranslationClient client = client(calls, false);
            ChatTranslationClient.Job job = CaptionConnectionProbe.create(edit, before, "zh", client);
            assertEquals(0, calls.get());
            ChatTranslationClient.Result result = job.execute();
            assertEquals(ChatTranslationClient.Status.COMPLETE, result.status);
            assertEquals(1, calls.get());
            assertEquals(1, result.calls);
            CaptionConnectionStore.Snapshot after = store.load();
            assertEquals(before.revision, after.revision);
            assertNull(after.connection);
            assertFalse(after.hasKey);
        } finally { edit.discard(); store.close(); }
    }

    @Test public void discardBeforeExecutionPreventsAnyTransportCall() throws Exception {
        CaptionConnectionStore store = store();
        CaptionConnectionEdit edit = form("test", "0");
        AtomicInteger calls = new AtomicInteger();
        try {
            ChatTranslationClient.Job job = CaptionConnectionProbe.create(edit, store.load(), "zh", client(calls, false));
            edit.discard();
            ChatTranslationClient.Result result = job.execute();
            assertEquals(0, calls.get());
            assertEquals(0, result.calls);
            assertNotEquals(ChatTranslationClient.Status.COMPLETE, result.status);
        } finally { edit.discard(); store.close(); }
    }

    @Test public void incompleteProbeDoesNotRecoverOrSpendASecondRequestAndStaleRevisionIsRejected() throws Exception {
        CaptionConnectionStore store = store();
        CaptionConnectionEdit edit = form("test", "0"), stale = form("test", "1"), save = form("save", "0");
        AtomicInteger calls = new AtomicInteger();
        try {
            CaptionConnectionStore.Snapshot before = store.load();
            ChatTranslationClient client = client(calls, true);
            assertEquals(1, CaptionConnectionProbe.create(edit, before, "zh", client).execute().calls);
            assertEquals(1, calls.get());
            try { CaptionConnectionProbe.create(stale, before, "zh", client); fail(); }
            catch (IllegalArgumentException expected) { }
            try { CaptionConnectionProbe.create(save, before, "zh", client); fail(); }
            catch (IllegalArgumentException expected) { }
            assertEquals(1, calls.get());
            assertEquals(before.revision, store.load().revision);
        } finally { edit.discard(); stale.discard(); save.discard(); store.close(); }
    }

    private static ChatTranslationClient client(AtomicInteger calls, boolean truncate) {
        return new ChatTranslationClient(new OkHttpClient.Builder().addInterceptor(chain -> {
            calls.incrementAndGet();
            try {
                Buffer buffer = new Buffer(); chain.request().body().writeTo(buffer);
                JSONObject body = BoundedJson.object(buffer.readUtf8());
                assertEquals(64, body.getInt("max_tokens"));
                JSONObject input = BoundedJson.object(body.getJSONArray("messages").getJSONObject(1).getString("content"));
                JSONArray items = input.getJSONArray("items");
                assertEquals(1, items.length());
                assertEquals("Connection test.", items.getJSONObject(0).getString("source"));
                JSONObject output = new JSONObject().put("translations", new JSONArray().put(new JSONObject()
                        .put("id", items.getJSONObject(0).getString("id")).put("text", "连接测试。")));
                JSONObject response = new JSONObject().put("choices", new JSONArray().put(new JSONObject()
                        .put("finish_reason", truncate ? "length" : "stop")
                        .put("message", new JSONObject().put("content", output.toString()))));
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("synthetic fixture")
                        .body(ResponseBody.create(MediaType.parse("application/json"), response.toString())).build();
            } catch (Exception error) { throw new AssertionError(error); }
        }).build());
    }
    private static CaptionConnectionEdit form(String operation, String revision) throws Exception {
        JSONObject body = new JSONObject().put("version", 1).put("revision", revision).put("operation", operation)
                .put("endpoint", "https://example.invalid/chat").put("model", "synthetic-fixture-model").put("tokens", 1024)
                .put("thinking", "OMIT").put("keyEdit", "REPLACE").put("key", "synthetic-fixture-not-a-real-key");
        return CaptionConnectionEdit.parse(body.toString().getBytes(Charset.forName("UTF-8")), null);
    }
    private static CaptionConnectionStore store() {
        CaptionSecretCipher unavailable = new CaptionSecretCipher() {
            public String seal(String secret, String binding) throws GeneralSecurityException { throw new GeneralSecurityException(); }
            public String open(String encrypted, String binding) throws GeneralSecurityException { throw new GeneralSecurityException(); }
        };
        return new CaptionConnectionStore(RuntimeEnvironment.getApplication().getSharedPreferences("probe-" + UUID.randomUUID(), Context.MODE_PRIVATE),
                unavailable, "synthetic-probe");
    }
}
