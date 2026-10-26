package com.liskovsoft.smartyoutubetv2.common.subtitle.translation;

import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.SegmentationProposal;

/** A one-exchange optional boundary request. The caller owns scheduling and commit-time admission. */
final class CaptionBoundaryClient {
    static final class Result {
        final CaptionHttpTransport.Status status;
        final CaptionBoundaryAdapter.Result protocol;
        final int calls;
        final int httpCode;
        final Long retryAfterMs;
        Result(CaptionHttpTransport.Status status, CaptionBoundaryAdapter.Result protocol,
               int calls, int httpCode, Long retryAfterMs) {
            this.status = status;
            this.protocol = protocol;
            this.calls = calls;
            this.httpCode = httpCode;
            this.retryAfterMs = retryAfterMs;
        }
    }

    final class Job {
        private final SegmentationProposal mProposal;
        private final ChatCompletionAdapter.Config mConfig;
        private final ChatTranslationClient.Credentials mCredentials;
        private final String mLanguage;
        private CaptionHttpTransport.Exchange mExchange;
        private boolean mCancelled, mExecuted;
        Job(SegmentationProposal proposal, ChatCompletionAdapter.Config config,
            ChatTranslationClient.Credentials credentials, String language) {
            mProposal = proposal;
            mConfig = config;
            mCredentials = credentials;
            mLanguage = language;
        }
        synchronized void cancel() {
            mCancelled = true;
            if (mExchange != null) mExchange.cancel();
        }
        Result execute() {
            synchronized (this) {
                if (mExecuted) throw new IllegalStateException("Caption boundary job already executed");
                mExecuted = true;
                if (mCancelled) return new Result(CaptionHttpTransport.Status.CANCELLED, null, 0, 0, null);
            }
            CaptionHttpTransport.Result response;
            try {
                byte[] body = CaptionBoundaryAdapter.encode(mProposal, mConfig, mLanguage);
                CaptionHttpTransport.Exchange exchange = mTransport.exchange(mConfig, mCredentials, body);
                synchronized (this) {
                    if (mCancelled) return new Result(CaptionHttpTransport.Status.CANCELLED, null, 0, 0, null);
                    mExchange = exchange;
                }
                try { response = exchange.execute(); }
                finally { synchronized (this) { mExchange = null; } }
            } catch (RuntimeException invalid) {
                return new Result(CaptionHttpTransport.Status.INVALID, null, 0, 0, null);
            }
            CaptionBoundaryAdapter.Result decoded = response.status == CaptionHttpTransport.Status.RECEIVED ?
                    CaptionBoundaryAdapter.decode(mProposal, mConfig, response.body) : null;
            synchronized (this) {
                return new Result(mCancelled ? CaptionHttpTransport.Status.CANCELLED : response.status,
                        mCancelled ? null : decoded, response.calls, response.httpCode, response.retryAfterMs);
            }
        }
    }

    private final CaptionHttpTransport mTransport;
    CaptionBoundaryClient(CaptionHttpTransport transport) {
        if (transport == null) throw TranslationBatch.invalid();
        mTransport = transport.withCallTimeout(2500);
    }
    Job newJob(SegmentationProposal proposal, ChatCompletionAdapter.Config config,
               ChatTranslationClient.Credentials credentials, String sourceLanguage) {
        if (proposal == null || config == null || credentials == null) throw TranslationBatch.invalid();
        return new Job(proposal, config, credentials, sourceLanguage);
    }
}
