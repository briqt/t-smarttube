package com.liskovsoft.smartyoutubetv2.common.subtitle.translation;

import okhttp3.Call;
import okhttp3.Connection;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.Test;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class CaptionHttpTransportTest {
    private static final ChatCompletionAdapter.Config CONFIG = new ChatCompletionAdapter.Config(
            "https://example.invalid/chat/completions", "fixture", 512, 1024, 1024,
            ChatCompletionAdapter.TokenField.MAX_TOKENS, ChatCompletionAdapter.Thinking.OMIT);

    @Test
    public void requestBodyIsOwnedAndEachExchangeIsOneShot() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CaptionHttpTransport transport = new CaptionHttpTransport(new OkHttpClient.Builder().addInterceptor(chain -> {
            calls.incrementAndGet();
            Buffer data = new Buffer(); chain.request().body().writeTo(data);
            assertEquals("{}", data.readUtf8());
            assertEquals("Bearer synthetic-transport-key", chain.request().header("Authorization"));
            return response(chain.request(), 200, "{}", null);
        }).build());
        byte[] body = "{}".getBytes(Charset.forName("UTF-8"));
        CaptionHttpTransport.Exchange exchange = transport.exchange(CONFIG, () -> "synthetic-transport-key", body);
        body[0] = '!';
        CaptionHttpTransport.Result result = exchange.execute();
        assertEquals(CaptionHttpTransport.Status.RECEIVED, result.status);
        assertEquals(1, result.calls);
        assertEquals(200, result.httpCode);
        assertEquals("{}", new String(result.body, Charset.forName("UTF-8")));
        assertEquals(1, calls.get());
        try { exchange.execute(); fail(); } catch (IllegalStateException expected) { }
    }

    @Test
    public void cancellationBeforeExecutionDoesNotObtainCredentialsOrAttemptTransport() {
        AtomicInteger credentials = new AtomicInteger(), calls = new AtomicInteger();
        CaptionHttpTransport transport = new CaptionHttpTransport(new OkHttpClient.Builder().addInterceptor(chain -> {
            calls.incrementAndGet(); return response(chain.request(), 200, "{}", null);
        }).build());
        CaptionHttpTransport.Exchange exchange = transport.exchange(CONFIG, () -> {
            credentials.incrementAndGet(); return "synthetic-transport-key";
        }, new byte[]{1});
        exchange.cancel();
        CaptionHttpTransport.Result result = exchange.execute();
        assertEquals(CaptionHttpTransport.Status.CANCELLED, result.status);
        assertEquals(0, result.calls);
        assertEquals(0, calls.get());
        assertEquals(0, credentials.get());
    }

    @Test
    public void invalidCredentialsDoNotReachTheHttpClient() {
        AtomicInteger calls = new AtomicInteger();
        CaptionHttpTransport transport = new CaptionHttpTransport(new OkHttpClient.Builder().addInterceptor(chain -> {
            calls.incrementAndGet(); return response(chain.request(), 200, "{}", null);
        }).build());
        for (String key : new String[]{null, "", "key\nheader", "key space", "密钥"}) {
            CaptionHttpTransport.Result result = transport.exchange(CONFIG, () -> key, new byte[]{1}).execute();
            assertEquals(CaptionHttpTransport.Status.AUTH_ERROR, result.status);
            assertEquals(0, result.calls);
        }
        assertEquals(0, calls.get());
    }

    @Test
    public void httpClassesAndRetryAfterArePreservedWithoutAutomaticRecovery() {
        for (int code : new int[]{401, 403, 429, 503}) {
            AtomicInteger calls = new AtomicInteger();
            CaptionHttpTransport transport = new CaptionHttpTransport(new OkHttpClient.Builder().addInterceptor(chain -> {
                calls.incrementAndGet(); return response(chain.request(), code, "{}", "7");
            }).build());
            CaptionHttpTransport.Result result = transport.exchange(CONFIG, () -> "synthetic-key", new byte[]{1}).execute();
            assertEquals(code, result.httpCode);
            assertEquals(1, result.calls);
            assertEquals(1, calls.get());
            assertEquals(code == 429 ? CaptionHttpTransport.Status.RATE_LIMITED :
                    code == 503 ? CaptionHttpTransport.Status.HTTP_ERROR : CaptionHttpTransport.Status.AUTH_ERROR, result.status);
            if (code == 429) assertEquals(Long.valueOf(7000), result.retryAfterMs);
            assertNull(result.body);
        }
    }

    @Test
    public void translationCallsHaveAnOverallTimeoutWithoutWeakeningShorterDeadlines() throws Exception {
        Field field = CaptionHttpTransport.class.getDeclaredField("mClient");
        field.setAccessible(true);
        for (int configured : new int[]{0, 8000, 120000}) {
            CaptionHttpTransport transport = new CaptionHttpTransport(new OkHttpClient.Builder()
                    .callTimeout(configured, TimeUnit.MILLISECONDS).build());
            OkHttpClient client = (OkHttpClient) field.get(transport);
            assertEquals(configured == 8000 ? 8000 : 45000, client.callTimeoutMillis());
        }
    }

    @Test
    public void retryAfterIsPreservedForServiceUnavailableAndHttpDates() {
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US);
        format.setTimeZone(java.util.TimeZone.getTimeZone("GMT"));
        String future = format.format(new java.util.Date(System.currentTimeMillis() + 120000));
        for (int code : new int[]{429, 503}) {
            for (String header : new String[]{"120", future}) {
                CaptionHttpTransport transport = new CaptionHttpTransport(new OkHttpClient.Builder().addInterceptor(chain ->
                        response(chain.request(), code, "{}", header)).build());
                CaptionHttpTransport.Result result = transport.exchange(CONFIG, () -> "synthetic-key", new byte[]{1}).execute();
                assertNotNull("Retry-After must survive HTTP " + code + " and date syntax", result.retryAfterMs);
                assertTrue(result.retryAfterMs >= 118000 && result.retryAfterMs <= 120000);
            }
        }
    }

    @Test
    public void retryAfterDatesNumbersAndInvalidValuesStayBounded() {
        long before = 1445412360000L;
        assertEquals(Long.valueOf(120000), CaptionHttpTransport.retryAfter("Wed, 21 Oct 2015 07:28:00 GMT", before));
        assertEquals(Long.valueOf(0), CaptionHttpTransport.retryAfter("Wed, 21 Oct 2015 07:28:00 GMT", before + 120000));
        assertEquals(Long.valueOf(0), CaptionHttpTransport.retryAfter("0", before));
        assertEquals(Long.valueOf(3_600_000), CaptionHttpTransport.retryAfter("99999999999999999999999", before));
        for (String invalid : new String[]{null, "", "-1", "1.5", "tomorrow", "Wed, 32 Oct 2015 07:28:00 GMT"}) {
            assertNull(CaptionHttpTransport.retryAfter(invalid, before));
        }
    }

    @Test
    public void responseBytesAreBoundedAndTransportExceptionsHaveNoBodyOrMessageLeak() {
        CaptionHttpTransport huge = new CaptionHttpTransport(new OkHttpClient.Builder().addInterceptor(chain ->
                response(chain.request(), 200, new String(new char[1025]).replace('\0', 'x'), null)).build());
        CaptionHttpTransport.Result tooLarge = huge.exchange(CONFIG, () -> "synthetic-key", new byte[]{1}).execute();
        assertEquals(CaptionHttpTransport.Status.RECEIVED, tooLarge.status);
        assertNull(tooLarge.body);
        CaptionHttpTransport broken = new CaptionHttpTransport(new OkHttpClient.Builder().addInterceptor(chain -> {
            throw new IOException("synthetic sensitive diagnostic must not propagate");
        }).build());
        CaptionHttpTransport.Result failed = broken.exchange(CONFIG, () -> "synthetic-key", new byte[]{1}).execute();
        assertEquals(CaptionHttpTransport.Status.NETWORK_ERROR, failed.status);
        assertNull(failed.body);
        assertEquals(1, failed.calls);
    }

    @Test
    public void theActuallyRegisteredNetworkGuardAllowsOneProceedAndStopsHiddenRetry() throws Exception {
        AtomicReference<Request> captured = new AtomicReference<>();
        AtomicReference<Call> call = new AtomicReference<>();
        CaptionHttpTransport transport = new CaptionHttpTransport(new OkHttpClient.Builder().addInterceptor(chain -> {
            captured.set(chain.request()); call.set(chain.call()); return response(chain.request(), 200, "{}", null);
        }).build());
        transport.exchange(CONFIG, () -> "synthetic-key", new byte[]{1}).execute();
        Field field = CaptionHttpTransport.class.getDeclaredField("mClient"); field.setAccessible(true);
        OkHttpClient actual = (OkHttpClient) field.get(transport);
        assertFalse(actual.followRedirects());
        assertFalse(actual.followSslRedirects());
        assertFalse(actual.retryOnConnectionFailure());
        assertSame(okhttp3.Authenticator.NONE, actual.authenticator());
        Field guardField = CaptionHttpTransport.class.getDeclaredField("WIRE_GUARD"); guardField.setAccessible(true);
        Interceptor guard = (Interceptor) guardField.get(null);
        assertTrue(actual.networkInterceptors().contains(guard));
        Chain fakeNetwork = new Chain(captured.get(), call.get());
        try (Response first = guard.intercept(fakeNetwork)) {
            assertEquals(503, first.code());
            assertNull("Retry-After:0 must not trigger OkHttp's unbudgeted POST replay", first.header("Retry-After"));
            assertEquals("{}", first.body().string());
        }
        try { guard.intercept(fakeNetwork); fail("Expected blocked second network proceed"); }
        catch (java.net.ProtocolException expected) { }
        assertEquals(1, fakeNetwork.proceeds);
        CaptionHttpTransport fork = transport.withCallTimeout(2500);
        OkHttpClient forked = (OkHttpClient) field.get(fork);
        assertEquals(1, forked.networkInterceptors().size());
        assertEquals(2500, forked.callTimeoutMillis());
    }

    private static Response response(Request request, int code, String body, String retryAfter) {
        Response.Builder response = new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code)
                .message("synthetic").body(ResponseBody.create(MediaType.parse("application/json"), body));
        if (retryAfter != null) response.header("Retry-After", retryAfter);
        return response.build();
    }
    private static final class Chain implements Interceptor.Chain {
        final Request request;
        final Call call;
        int proceeds;
        Chain(Request request, Call call) { this.request = request; this.call = call; }
        public Request request() { return request; }
        public Response proceed(Request request) { proceeds++; return response(request, 503, "{}", "0"); }
        public Connection connection() { return null; }
        public Call call() { return call; }
        public int connectTimeoutMillis() { return 1000; }
        public Interceptor.Chain withConnectTimeout(int timeout, TimeUnit unit) { return this; }
        public int readTimeoutMillis() { return 1000; }
        public Interceptor.Chain withReadTimeout(int timeout, TimeUnit unit) { return this; }
        public int writeTimeoutMillis() { return 1000; }
        public Interceptor.Chain withWriteTimeout(int timeout, TimeUnit unit) { return this; }
    }
}
