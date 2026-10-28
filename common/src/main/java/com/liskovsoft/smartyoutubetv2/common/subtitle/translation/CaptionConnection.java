package com.liskovsoft.smartyoutubetv2.common.subtitle.translation;

/** Global request settings only. Credentials and channel preferences are deliberately separate. */
public final class CaptionConnection {
    public static final String PRIMARY = "primary";
    public final ChatCompletionAdapter.Config wire;
    public final TranslationBatch.Limits batch;
    public final int maxCalls;

    public CaptionConnection(ChatCompletionAdapter.Config wire, TranslationBatch.Limits batch, int maxCalls) {
        if (wire == null || batch == null || maxCalls < 1 || maxCalls > 8) throw TranslationBatch.invalid();
        this.wire = wire;
        this.batch = batch;
        this.maxCalls = maxCalls;
    }

    /** Explicit initial budgets for the first native connection editor, not inferred provider capabilities. */
    public static CaptionConnection basic(String endpoint, String model, int outputTokens, ChatCompletionAdapter.Thinking thinking) {
        return basic(endpoint, model, outputTokens, thinking, CaptionService.CUSTOM_CHAT, null);
    }

    public static CaptionConnection basic(String endpoint, String model, int outputTokens, ChatCompletionAdapter.Thinking thinking,
                                          CaptionService service, String workspaceId) {
        return new CaptionConnection(new ChatCompletionAdapter.Config(endpoint, model, outputTokens, 262144, 262144,
                ChatCompletionAdapter.TokenField.MAX_TOKENS, thinking, service, workspaceId), new TranslationBatch.Limits(12, 6000, 2000, 12000), 2);
    }
}
