package com.liskovsoft.smartyoutubetv2.common.subtitle.translation;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

import static com.liskovsoft.smartyoutubetv2.common.subtitle.translation.ChatCompletionAdapterTest.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 27)
public class ChatTranslationClientTest {
    @Test
    public void fakeTransportGetsFinalPostAndOnlyMissingItemsAreRetried() throws Exception {
        List<JSONObject> bodies = new ArrayList<>();
        int[] calls = {0};
        ChatTranslationClient client = client(chain -> {
            assertEquals("POST", chain.request().method());
            assertEquals("Bearer synthetic-fixture-key", chain.request().header("Authorization"));
            Buffer buffer = new Buffer();
            chain.request().body().writeTo(buffer);
            bodies.add(BoundedJson.object(buffer.readUtf8()));
            try {
                return response(chain.request(), 200, envelope("stop", calls[0]++ == 0 ? output("g1", "第二") : output("g0", "第一"), null));
            } catch (Exception e) { throw new AssertionError(e); }
        });
        TranslationBatch batch = batch("first", "second");
        ChatTranslationClient.Result result = client.newJob(batch, config(), () -> "synthetic-fixture-key", 3).execute();
        assertEquals(ChatTranslationClient.Status.COMPLETE, result.status);
        assertEquals(2, result.calls);
        assertSame(batch, result.batch);
        assertEquals(Arrays.asList("g0", "g1"), new ArrayList<>(result.translations.keySet()));
        JSONObject retryInput = BoundedJson.object(bodies.get(1).getJSONArray("messages").getJSONObject(1).getString("content"));
        assertEquals(1, retryInput.getJSONArray("items").length());
        assertEquals("g0", retryInput.getJSONArray("items").getJSONObject(0).getString("id"));
    }

    @Test
    public void recoverySharesOneOverallDeadlineAndKeepsCompletedItemsWhenItExpires() {
        for (long elapsedMs : new long[]{44000, 45000}) {
            long[] now = {0};
            int[] calls = {0};
            ChatTranslationClient client = new ChatTranslationClient(new OkHttpClient.Builder().addInterceptor(chain -> {
                int call = calls[0]++;
                assertEquals(TimeUnit.MILLISECONDS.toNanos(call == 0 ? 45000 : 1000), chain.call().timeout().timeoutNanos());
                now[0] = TimeUnit.MILLISECONDS.toNanos(elapsedMs);
                try {
                    return response(chain.request(), 200, envelope("stop", output(call == 0 ? "g0" : "g1", "译文"), null));
                } catch (Exception e) { throw new AssertionError(e); }
            }).build(), () -> now[0]);
            ChatTranslationClient.Result result = client.newJob(batch("first", "second"), config(), () -> "synthetic", 2).execute();
            assertEquals(elapsedMs == 45000 ? 1 : 2, calls[0]);
            assertEquals(elapsedMs == 45000 ? ChatTranslationClient.Status.NETWORK_ERROR : ChatTranslationClient.Status.COMPLETE, result.status);
            assertEquals("译文", result.translations.get("g0"));
            assertEquals(elapsedMs == 45000 ? 1 : 2, result.translations.size());
        }
    }

    @Test
    public void truncatedBatchSplitsWithinHardCallBudgetAndNeverAcceptsTruncatedPrefix() throws Exception {
        int[] calls = {0};
        ChatTranslationClient client = client(chain -> {
            int call = calls[0]++;
            try {
                byte[] body = call == 0 ? envelope("length", output("g0", "truncated"), null) :
                        envelope("stop", output(call == 1 ? "g0" : "g1", call == 1 ? "第一" : "第二"), null);
                return response(chain.request(), 200, body);
            } catch (Exception e) { throw new AssertionError(e); }
        });
        ChatTranslationClient.Result result = client.newJob(batch("first", "second"), config(), () -> "synthetic", 2).execute();
        assertEquals(ChatTranslationClient.Status.INCOMPLETE, result.status);
        assertEquals(2, result.calls);
        assertEquals(1, result.translations.size());
        assertEquals("第一", result.translations.get("g0"));
        assertFalse(result.translations.containsValue("truncated"));
    }

    @Test
    public void authRateLimitServerAndRedirectStatusesNeverImmediatelyRetryOrFollow() {
        for (int code : new int[]{401, 403, 429, 500, 302}) {
            int[] calls = {0};
            ChatTranslationClient client = client(chain -> {
                calls[0]++;
                return response(chain.request(), code, new byte[0]).newBuilder()
                        .header("Retry-After", "12").header("Location", "https://other.invalid/steal").build();
            });
            ChatTranslationClient.Result result = client.newJob(batch("first"), config(), () -> "synthetic", 8).execute();
            assertEquals(1, calls[0]);
            assertEquals(1, result.calls);
            assertTrue(result.translations.isEmpty());
            assertEquals(code == 401 || code == 403 ? ChatTranslationClient.Status.AUTH_ERROR :
                    code == 429 ? ChatTranslationClient.Status.RATE_LIMITED : ChatTranslationClient.Status.HTTP_ERROR, result.status);
            assertEquals(code == 429 ? Long.valueOf(12000) : null, result.retryAfterMs);
        }
    }

    @Test
    public void refusalAndUnknownCompletionDoNotTriggerProtocolRecovery() {
        for (String reason : new String[]{"content_filter", "aborted", "novel"}) {
            int[] calls = {0};
            ChatTranslationClient client = client(chain -> {
                calls[0]++;
                try { return response(chain.request(), 200, envelope(reason, output("g0", "not accepted"), null)); }
                catch (Exception e) { throw new AssertionError(e); }
            });
            assertTrue(client.newJob(batch("first"), config(), () -> "synthetic", 8).execute().translations.isEmpty());
            assertEquals(1, calls[0]);
        }
    }

    @Test
    public void invalidPayloadAndNetworkFailureAreBoundedAndSanitized() {
        int[] calls = {0};
        ChatTranslationClient invalid = client(chain -> {
            calls[0]++;
            return response(chain.request(), 200, "not-json-secret".getBytes(utf8()));
        });
        ChatTranslationClient.Result result = invalid.newJob(batch("first"), config(), () -> "synthetic", 2).execute();
        assertEquals(2, calls[0]);
        assertEquals(ChatTranslationClient.Status.INCOMPLETE, result.status);
        assertTrue(result.translations.isEmpty());
        ChatTranslationClient broken = client(chain -> { throw new IOException("sensitive-url-and-body"); });
        assertEquals(ChatTranslationClient.Status.NETWORK_ERROR,
                broken.newJob(batch("first"), config(), () -> "synthetic", 8).execute().status);
    }

    @Test
    public void emptyBatchAndCancelledJobSpendNoCredentialOrHttpCall() {
        ChatTranslationClient client = client(chain -> { throw new AssertionError("Network should not run"); });
        ChatTranslationClient.Result empty = client.newJob(batch("   ", "..."), config(), () -> {
            throw new AssertionError("Credentials should not be read");
        }, 1).execute();
        assertEquals(ChatTranslationClient.Status.COMPLETE, empty.status);
        assertEquals(0, empty.calls);
        ChatTranslationClient.Job job = client.newJob(batch("first"), config(), () -> "synthetic", 1);
        job.cancel();
        ChatTranslationClient.Result cancelled = job.execute();
        assertEquals(ChatTranslationClient.Status.CANCELLED, cancelled.status);
        assertEquals(0, cancelled.calls);
    }

    @Test
    public void cancellationDuringResponseCannotPublishItsTranslationAndJobIsOneShot() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        ChatTranslationClient client = client(chain -> {
            entered.countDown();
            try {
                if (!resume.await(5, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting for fixture cancellation");
                return response(chain.request(), 200, envelope("stop", output("g0", "late"), null));
            } catch (InterruptedException e) { throw new IOException("Interrupted fixture"); }
            catch (Exception e) { throw new AssertionError(e); }
        });
        ChatTranslationClient.Job job = client.newJob(batch("first"), config(), () -> "synthetic", 2);
        AtomicReference<ChatTranslationClient.Result> result = new AtomicReference<>();
        Thread worker = new Thread(() -> result.set(job.execute()));
        worker.start();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            job.cancel();
        } finally { resume.countDown(); }
        worker.join(5000);
        assertFalse(worker.isAlive());
        assertEquals(ChatTranslationClient.Status.CANCELLED, result.get().status);
        assertTrue(result.get().translations.isEmpty());
        try { job.execute(); fail(); } catch (IllegalStateException expected) { }
    }

    @Test
    public void unsafeCredentialsNeverBecomeHeadersAndResponseBodyLimitIsEnforced() {
        ChatTranslationClient noNetwork = client(chain -> { throw new AssertionError("Unsafe key must not be sent"); });
        for (String key : new String[]{"", "line\nbreak", "has space"}) {
            assertEquals(ChatTranslationClient.Status.AUTH_ERROR,
                    noNetwork.newJob(batch("first"), config(), () -> key, 1).execute().status);
        }
        ChatTranslationClient tooLarge = client(chain -> response(chain.request(), 200, new byte[65537]));
        ChatTranslationClient.Result result = tooLarge.newJob(batch("first"), config(), () -> "synthetic", 1).execute();
        assertTrue(result.translations.isEmpty());
        assertEquals(1, result.calls);
    }

    @Test
    public void cancellationDuringRecoveryDiscardsPreviouslyCompletedItemsOfThatJob() throws Exception {
        AtomicReference<ChatTranslationClient.Job> holder = new AtomicReference<>();
        int[] calls = {0};
        ChatTranslationClient client = client(chain -> {
            try {
                boolean first = calls[0]++ == 0;
                if (!first) holder.get().cancel();
                return response(chain.request(), 200, envelope("stop", output(first ? "g0" : "g1", "译文"), null));
            } catch (Exception e) { throw new AssertionError(e); }
        });
        ChatTranslationClient.Job job = client.newJob(batch("first", "second"), config(), () -> "synthetic", 3);
        holder.set(job);
        ChatTranslationClient.Result result = job.execute();
        assertEquals(ChatTranslationClient.Status.CANCELLED, result.status);
        assertTrue(result.translations.isEmpty());
        assertEquals(2, result.calls);
    }

    @Test
    public void recoverySharesOneTotalOutputBudgetAcrossAllAttempts() throws Exception {
        int[] calls = {0};
        ChatTranslationClient client = client(chain -> {
            try {
                return response(chain.request(), 200, envelope("stop",
                        output(calls[0]++ == 0 ? "g0" : "g1", "abcdefghij"), null));
            } catch (Exception e) { throw new AssertionError(e); }
        });
        TranslationBatch batch = new TranslationBatch(plan("first", "second"), Arrays.asList(0, 1), null, "zh", "",
                new TranslationBatch.Limits(2, 100, 0, 10));
        ChatTranslationClient.Result result = client.newJob(batch, config(), () -> "synthetic", 2).execute();
        assertEquals(ChatTranslationClient.Status.INVALID, result.status);
        assertEquals(1, result.translations.size());
        assertEquals("abcdefghij", result.translations.get("g0"));
        assertFalse(result.translations.containsKey("g1"));
    }

    private static ChatTranslationClient client(Interceptor interceptor) {
        return new ChatTranslationClient(new OkHttpClient.Builder().addInterceptor(interceptor).build());
    }

    private static Response response(Request request, int code, byte[] data) {
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("synthetic")
                .body(ResponseBody.create(MediaType.parse("application/json"), data)).build();
    }
}
