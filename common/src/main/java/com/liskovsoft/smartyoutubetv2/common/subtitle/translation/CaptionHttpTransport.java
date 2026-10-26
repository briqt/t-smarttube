package com.liskovsoft.smartyoutubetv2.common.subtitle.translation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ProtocolException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Authenticator;
import okhttp3.Call;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Shared bounded HTTPS exchange policy for translation and boundary protocols. Never logs body/key/errors. */
final class CaptionHttpTransport {
    enum Status { RECEIVED, AUTH_ERROR, RATE_LIMITED, HTTP_ERROR, NETWORK_ERROR, CANCELLED, INVALID }

    static final class Result {
        final Status status;
        final byte[] body;
        final int calls;
        final int httpCode;
        final Long retryAfterMs;
        Result(Status status, byte[] body, int calls, int httpCode, Long retryAfterMs) {
            this.status = status;
            this.body = body;
            this.calls = calls;
            this.httpCode = httpCode;
            this.retryAfterMs = retryAfterMs;
        }
    }

    private static final class WireBudget { final AtomicInteger entries = new AtomicInteger(); }
    private static final Interceptor WIRE_GUARD = chain -> {
        WireBudget budget = chain.request().tag(WireBudget.class);
        if (budget == null || budget.entries.incrementAndGet() != 1) throw new ProtocolException("Caption exchange limit reached");
        Response response = chain.proceed(chain.request());
        // OkHttp can follow 503 Retry-After:0 independently of connection retries. Scheduling
        // belongs to the caller; preserve the HTTP code/body but forbid this hidden second POST.
        if (response.code() == 503 && "0".equals(response.header("Retry-After"))) {
            return response.newBuilder().removeHeader("Retry-After").build();
        }
        return response;
    };

    private final OkHttpClient mClient;

    CaptionHttpTransport(OkHttpClient client) {
        OkHttpClient.Builder builder = client.newBuilder().followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE);
        if (!builder.networkInterceptors().contains(WIRE_GUARD)) builder.addNetworkInterceptor(WIRE_GUARD);
        mClient = builder.build();
    }

    CaptionHttpTransport withCallTimeout(long milliseconds) {
        if (milliseconds <= 0 || milliseconds > 45000) throw TranslationBatch.invalid();
        return new CaptionHttpTransport(mClient.newBuilder().callTimeout(milliseconds, TimeUnit.MILLISECONDS).build());
    }

    Exchange exchange(ChatCompletionAdapter.Config config, ChatTranslationClient.Credentials credentials, byte[] body) {
        if (config == null || credentials == null || body == null || body.length == 0 || body.length > config.requestBytes) {
            throw TranslationBatch.invalid();
        }
        return new Exchange(config, credentials, body.clone());
    }

    final class Exchange {
        private final ChatCompletionAdapter.Config mConfig;
        private final ChatTranslationClient.Credentials mCredentials;
        private final byte[] mBody;
        private boolean mCancelled, mExecuted;
        private Call mCall;
        Exchange(ChatCompletionAdapter.Config config, ChatTranslationClient.Credentials credentials, byte[] body) {
            mConfig = config;
            mCredentials = credentials;
            mBody = body;
        }

        synchronized void cancel() {
            mCancelled = true;
            if (mCall != null) mCall.cancel();
        }
        private synchronized boolean cancelled() { return mCancelled; }

        Result execute() {
            synchronized (this) {
                if (mExecuted) throw new IllegalStateException("Caption exchange already executed");
                mExecuted = true;
                if (mCancelled) return new Result(Status.CANCELLED, null, 0, 0, null);
            }
            Status status = Status.INVALID;
            int calls = 0, code = 0;
            Long retryAfter = null;
            byte[] bytes = null;
            try {
                String key = mCredentials.getKey();
                if (key == null || key.isEmpty() || key.length() > 8192 || !headerSafe(key)) {
                    return new Result(cancelled() ? Status.CANCELLED : Status.AUTH_ERROR, null, 0, 0, null);
                }
                Request request = new Request.Builder().url(mConfig.endpoint).header("Authorization", "Bearer " + key)
                        .header("Accept", "application/json").tag(WireBudget.class, new WireBudget())
                        .post(RequestBody.create(MediaType.parse("application/json; charset=utf-8"), mBody)).build();
                Call call = mClient.newCall(request);
                synchronized (this) {
                    if (mCancelled) return new Result(Status.CANCELLED, null, 0, 0, null);
                    mCall = call;
                }
                calls = 1;
                try (Response response = call.execute()) {
                    code = response.code();
                    if (code == 401 || code == 403) status = Status.AUTH_ERROR;
                    else if (code == 429) { status = Status.RATE_LIMITED; retryAfter = retryAfter(response.header("Retry-After")); }
                    else if (!response.isSuccessful()) status = Status.HTTP_ERROR;
                    else { bytes = read(response.body(), mConfig.responseBytes); status = Status.RECEIVED; }
                }
            } catch (IOException ignored) {
                status = Status.NETWORK_ERROR;
            } catch (RuntimeException ignored) {
                status = Status.INVALID;
            } finally {
                synchronized (this) { mCall = null; }
            }
            if (cancelled()) { status = Status.CANCELLED; bytes = null; }
            return new Result(status, bytes, calls, code, retryAfter);
        }
    }

    private static byte[] read(ResponseBody body, int maxBytes) throws IOException {
        if (body == null || body.contentLength() > maxBytes) return null;
        try (InputStream input = body.byteStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer, 0, Math.min(buffer.length, maxBytes - output.size() + 1))) != -1) {
                if (count > maxBytes - output.size()) return null;
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static boolean headerSafe(String key) {
        for (int i = 0; i < key.length(); i++) if (key.charAt(i) <= 32 || key.charAt(i) >= 127) return false;
        return true;
    }
    private static Long retryAfter(String value) {
        if (value == null || value.isEmpty() || value.length() > 6) return null;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) < '0' || value.charAt(i) > '9') return null;
        return Math.min(Long.parseLong(value), 3600) * 1000L;
    }
}
