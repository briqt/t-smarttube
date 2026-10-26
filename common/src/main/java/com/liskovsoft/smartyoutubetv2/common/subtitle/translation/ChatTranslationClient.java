package com.liskovsoft.smartyoutubetv2.common.subtitle.translation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

/** Blocking worker-side transport. No default endpoint, credentials, automatic HTTP retry, or logging. */
public final class ChatTranslationClient {
    public enum Status { COMPLETE, INCOMPLETE, AUTH_ERROR, RATE_LIMITED, HTTP_ERROR, NETWORK_ERROR, CANCELLED, INVALID }
    public interface Credentials { String getKey(); }

    public static final class Result {
        public final TranslationBatch batch;
        public final Status status;
        public final Map<String, String> translations;
        public final int calls;
        public final Long retryAfterMs;
        public final int httpCode;
        public final ChatCompletionAdapter.State completionState;

        private Result(TranslationBatch batch, Status status, Map<String, String> translations, int calls, Long retryAfterMs,
                       int httpCode, ChatCompletionAdapter.State completionState) {
            this.batch = batch;
            this.status = status;
            this.translations = Collections.unmodifiableMap(new LinkedHashMap<>(translations));
            this.calls = calls;
            this.retryAfterMs = retryAfterMs;
            this.httpCode = httpCode;
            this.completionState = completionState;
        }
    }

    public final class Job {
        private final TranslationBatch mBatch;
        private final ChatCompletionAdapter.Config mConfig;
        private final Credentials mCredentials;
        private final int mMaxCalls;
        private boolean mCancelled;
        private boolean mExecuted;
        private CaptionHttpTransport.Exchange mExchange;

        private Job(TranslationBatch batch, ChatCompletionAdapter.Config config, Credentials credentials, int maxCalls) {
            mBatch = batch;
            mConfig = config;
            mCredentials = credentials;
            mMaxCalls = maxCalls;
        }

        public synchronized void cancel() {
            mCancelled = true;
            if (mExchange != null) mExchange.cancel();
        }

        private synchronized boolean cancelled() {
            return mCancelled;
        }

        /** One-shot. Partial successes survive bounded missing-item recovery, never another provider. */
        public Result execute() {
            synchronized (this) {
                if (mExecuted) throw new IllegalStateException("Caption translation job already executed");
                mExecuted = true;
            }
            Map<String, String> translations = new LinkedHashMap<>();
            Deque<TranslationBatch> pending = new ArrayDeque<>();
            if (!mBatch.getItems().isEmpty()) pending.add(mBatch);
            int calls = 0;
            int translatedCharacters = 0;
            Status status = Status.INCOMPLETE;
            Long retryAfter = null;
            int httpCode = 0;
            ChatCompletionAdapter.State completionState = null;
            while (!pending.isEmpty() && calls < mMaxCalls) {
                if (cancelled()) { status = Status.CANCELLED; break; }
                httpCode = 0;
                completionState = null;
                TranslationBatch batch = pending.removeFirst();
                byte[] body;
                try {
                    body = ChatCompletionAdapter.encode(batch, mConfig);
                } catch (RuntimeException ignored) {
                    status = Status.INVALID;
                    break;
                }
                ChatCompletionAdapter.Result decoded;
                try {
                    CaptionHttpTransport.Exchange exchange = mTransport.exchange(mConfig, mCredentials, body);
                    synchronized (this) {
                        if (mCancelled) { status = Status.CANCELLED; break; }
                        mExchange = exchange;
                    }
                    CaptionHttpTransport.Result response;
                    try { response = exchange.execute(); }
                    finally { synchronized (this) { mExchange = null; } }
                    calls += response.calls;
                    httpCode = response.httpCode;
                    retryAfter = response.retryAfterMs;
                    completionState = null;
                    if (response.status != CaptionHttpTransport.Status.RECEIVED) {
                        status = Status.valueOf(response.status.name());
                        break;
                    }
                    decoded = ChatCompletionAdapter.decode(batch, mConfig, response.body);
                    completionState = decoded.state;
                } catch (RuntimeException ignored) {
                    status = Status.INVALID;
                    break;
                }
                if (cancelled()) { status = Status.CANCELLED; break; }
                int addedCharacters = 0;
                for (String text : decoded.translations.values()) addedCharacters += text.codePointCount(0, text.length());
                if (addedCharacters > mBatch.limits.translatedCodePoints - translatedCharacters) {
                    status = Status.INVALID;
                    break;
                }
                translatedCharacters += addedCharacters;
                translations.putAll(decoded.translations);
                if (decoded.state == ChatCompletionAdapter.State.COMPLETE) continue;
                if (decoded.state == ChatCompletionAdapter.State.REFUSED || decoded.state == ChatCompletionAdapter.State.FILTERED ||
                        decoded.state == ChatCompletionAdapter.State.FAILED || decoded.state == ChatCompletionAdapter.State.UNKNOWN) break;
                List<TranslationBatch.Item> missing = new ArrayList<>();
                for (TranslationBatch.Item item : batch.getItems()) {
                    if (!decoded.translations.containsKey(item.id)) missing.add(item);
                }
                // Splitting mitigates truncation/protocol failure. A singleton can retry only within the call budget.
                int middle = (missing.size() + 1) / 2;
                if (middle < missing.size()) pending.addFirst(batch.subset(missing.subList(middle, missing.size())));
                if (middle > 0) pending.addFirst(batch.subset(missing.subList(0, middle)));
            }
            synchronized (this) {
                if (mCancelled) {
                    status = Status.CANCELLED;
                    translations.clear();
                } else if (translations.size() == mBatch.getItems().size()) status = Status.COMPLETE;
                Map<String, String> ordered = new LinkedHashMap<>();
                for (TranslationBatch.Item item : mBatch.getItems()) {
                    if (translations.containsKey(item.id)) ordered.put(item.id, translations.get(item.id));
                }
                return new Result(mBatch, status, ordered, calls, retryAfter, httpCode, completionState);
            }
        }
    }

    private final CaptionHttpTransport mTransport;

    public ChatTranslationClient() {
        this(new OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS).build());
    }

    // Test transport injection stays package-local; production does not inherit logging interceptors.
    ChatTranslationClient(OkHttpClient client) {
        mTransport = new CaptionHttpTransport(client);
    }

    CaptionHttpTransport transport() { return mTransport; }

    static Result failure(TranslationBatch batch, Status status, int calls, int httpCode, Long retryAfter) {
        if (batch == null || status == null || status == Status.COMPLETE || status == Status.INCOMPLETE || calls < 0) {
            throw TranslationBatch.invalid();
        }
        return new Result(batch, status, Collections.emptyMap(), calls, retryAfter, httpCode, null);
    }

    public Job newJob(TranslationBatch batch, ChatCompletionAdapter.Config config, Credentials credentials, int maxCalls) {
        if (batch == null || config == null || credentials == null || maxCalls <= 0 || maxCalls > 8) throw TranslationBatch.invalid();
        return new Job(batch, config, credentials, maxCalls);
    }

}
