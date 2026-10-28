package com.liskovsoft.smartyoutubetv2.common.subtitle.translation;

import org.json.JSONException;
import org.json.JSONObject;

/** Documented request controls, not a claim about server-side reasoning or billing. */
public final class CaptionModelPolicy {
    public static final String REVISION = "2026-10-28.1";
    public enum Decision { PROVIDER_DEFAULT, UNKNOWN, DISABLED, LOW, BUDGET_128 }

    public static Decision decision(CaptionService service, String model, ChatCompletionAdapter.Thinking thinking) {
        if (thinking == ChatCompletionAdapter.Thinking.OMIT) return Decision.PROVIDER_DEFAULT;
        if (thinking == ChatCompletionAdapter.Thinking.DEEPSEEK_DISABLED) return Decision.DISABLED;
        if (service == null || model == null) return Decision.UNKNOWN;
        switch (service) {
            case OPENAI_CHAT:
            case OPENAI_RESPONSES:
                if ("gpt-5.5".equals(model)) return Decision.DISABLED;
                return Decision.UNKNOWN;
            case DEEPSEEK:
                return oneOf(model, "deepseek-flash", "deepseek-v4-pro") ? Decision.DISABLED : Decision.UNKNOWN;
            case CLAUDE:
                if (oneOf(model, "claude-opus-5-5", "claude-fable-5", "claude-fable-5-1",
                        "claude-mythos-5", "claude-mythos-5-1")) return Decision.LOW;
                if (oneOf(model, "claude-sonnet-5-5", "claude-haiku-5-5", "claude-opus-5", "claude-sonnet-5",
                        "claude-opus-4-8", "claude-opus-4-7", "claude-opus-4-6", "claude-sonnet-4-6",
                        "claude-opus-4-5", "claude-sonnet-4-5", "claude-haiku-4-5")) return Decision.DISABLED;
                return Decision.UNKNOWN;
            case GEMINI:
                if (oneOf(model, "gemini-2.5-flash", "gemini-2.5-flash-lite")) return Decision.DISABLED;
                if ("gemini-2.5-pro".equals(model)) return Decision.BUDGET_128;
                if (oneOf(model, "gemini-3.8-flash", "gemini-4-argon")) return Decision.LOW;
                return Decision.UNKNOWN;
            default:
                return Decision.UNKNOWN;
        }
    }

    static void apply(ChatCompletionAdapter.Config config, JSONObject body) throws JSONException {
        Decision decision = decision(config.service, config.model, config.thinking);
        if (decision == Decision.PROVIDER_DEFAULT || decision == Decision.UNKNOWN) return;
        switch (config.service) {
            case OPENAI_CHAT:
                body.put("reasoning_effort", decision == Decision.DISABLED ? "none" : "low");
                break;
            case OPENAI_RESPONSES:
                body.put("reasoning", new JSONObject().put("effort", decision == Decision.DISABLED ? "none" : "low"));
                break;
            case CLAUDE:
                if (decision == Decision.DISABLED) {
                    boolean between = "claude-sonnet-5-5".equals(config.model);
                    body.put("thinking", new JSONObject().put("type", between ? "between_tools" : "disabled"));
                    if (between || oneOf(config.model, "claude-opus-5", "claude-haiku-5-5")) {
                        body.put("output_config", new JSONObject().put("effort", "low"));
                    }
                } else body.put("output_config", new JSONObject().put("effort", "low"));
                break;
            case GEMINI:
                JSONObject thinking = new JSONObject();
                if (decision == Decision.LOW) thinking.put("thinkingLevel", "low");
                else thinking.put("thinkingBudget", decision == Decision.BUDGET_128 ? 128 : 0);
                body.getJSONObject("generationConfig").put("thinkingConfig", thinking);
                break;
            default:
                body.put("thinking", new JSONObject().put("type", "disabled"));
        }
    }

    static boolean developerRole(ChatCompletionAdapter.Config config) {
        return config.service == CaptionService.OPENAI_CHAT && "gpt-5.5".equals(config.model);
    }

    private static boolean oneOf(String value, String... choices) {
        for (String choice : choices) if (choice.equals(value)) return true;
        return false;
    }

    private CaptionModelPolicy() { }
}
