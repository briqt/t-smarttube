package com.liskovsoft.smartyoutubetv2.common.subtitle.translation;

/** Explicit wire selection. A custom URL never silently changes the protocol. */
public enum CaptionService {
    CUSTOM_CHAT(""),
    OPENAI_CHAT("https://api.openai.com/v1/chat/completions"),
    OPENAI_RESPONSES("https://api.openai.com/v1/responses"),
    DEEPSEEK("https://api.deepseek.com/chat/completions"),
    CLAUDE("https://api.anthropic.com/v1/messages"),
    GEMINI("https://generativelanguage.googleapis.com/v1beta/models");

    public final String defaultEndpoint;

    CaptionService(String endpoint) { defaultEndpoint = endpoint; }

    boolean chat() { return this == CUSTOM_CHAT || this == OPENAI_CHAT || this == DEEPSEEK; }
}
