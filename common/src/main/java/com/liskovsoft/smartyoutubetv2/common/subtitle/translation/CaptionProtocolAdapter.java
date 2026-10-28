package com.liskovsoft.smartyoutubetv2.common.subtitle.translation;

import okhttp3.Request;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Native text-only protocol envelopes. Caption IDs and source validation remain in the caller. */
final class CaptionProtocolAdapter {
    static final class Text {
        final ChatCompletionAdapter.State state;
        final String content;
        Text(ChatCompletionAdapter.State state, String content) { this.state = state; this.content = content; }
    }

    static JSONObject encode(ChatCompletionAdapter.Config config, String system, String input, int tokens) throws JSONException {
        JSONObject body = new JSONObject();
        switch (config.service) {
            case OPENAI_RESPONSES:
                body.put("model", config.model).put("instructions", system).put("input", input)
                        .put("max_output_tokens", tokens).put("stream", false).put("store", false);
                break;
            case CLAUDE:
                body.put("model", config.model).put("system", system).put("max_tokens", tokens).put("stream", false)
                        .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", input)));
                break;
            case GEMINI:
                body.put("systemInstruction", new JSONObject().put("parts", parts(system)))
                        .put("contents", new JSONArray().put(new JSONObject().put("role", "user").put("parts", parts(input))))
                        .put("generationConfig", new JSONObject().put("maxOutputTokens", tokens));
                break;
            default:
                JSONArray messages = new JSONArray()
                        .put(new JSONObject().put("role", CaptionModelPolicy.developerRole(config) ? "developer" : "system").put("content", system))
                        .put(new JSONObject().put("role", "user").put("content", input));
                String field = config.service == CaptionService.OPENAI_CHAT ||
                        config.service == CaptionService.CUSTOM_CHAT && config.tokenField == ChatCompletionAdapter.TokenField.MAX_COMPLETION_TOKENS ?
                        "max_completion_tokens" : "max_tokens";
                body.put("model", config.model).put("messages", messages).put("stream", false).put(field, tokens);
        }
        CaptionModelPolicy.apply(config, body);
        return body;
    }

    static void authorize(ChatCompletionAdapter.Config config, Request.Builder request, String key) {
        if (config.service == CaptionService.GEMINI) request.header("x-goog-api-key", key);
        else request.header("Authorization", "Bearer " + key);
        if (config.service == CaptionService.CLAUDE) {
            request.header("anthropic-version", "2026-10-21");
            if (config.workspaceId != null) request.header("anthropic-workspace-id", config.workspaceId);
        }
    }

    static Text decode(ChatCompletionAdapter.Config config, JSONObject envelope) {
        if (envelope.has("error") && !envelope.isNull("error")) return state(ChatCompletionAdapter.State.FAILED);
        switch (config.service) {
            case OPENAI_RESPONSES: return responses(envelope);
            case CLAUDE: return claude(envelope);
            case GEMINI: return gemini(envelope);
            default: return chat(envelope);
        }
    }

    private static Text chat(JSONObject envelope) {
        JSONArray choices = envelope.optJSONArray("choices");
        if (choices == null || choices.length() != 1) return state(ChatCompletionAdapter.State.INVALID);
        JSONObject choice = choices.optJSONObject(0);
        JSONObject message = choice == null ? null : choice.optJSONObject("message");
        if (message == null) return state(ChatCompletionAdapter.State.INVALID);
        Object refusal = message.opt("refusal");
        if (refusal != null && refusal != JSONObject.NULL && !"".equals(refusal)) return state(ChatCompletionAdapter.State.REFUSED);
        String reason = BoundedJson.string(choice, "finish_reason");
        if (!"stop".equals(reason)) {
            return state("length".equals(reason) ? ChatCompletionAdapter.State.TRUNCATED :
                    "content_filter".equals(reason) ? ChatCompletionAdapter.State.FILTERED :
                    "tool_calls".equals(reason) || "function_call".equals(reason) || "aborted".equals(reason) ||
                            "insufficient_system_resource".equals(reason) ? ChatCompletionAdapter.State.FAILED : ChatCompletionAdapter.State.UNKNOWN);
        }
        if (message.has("tool_calls") && !message.isNull("tool_calls") ||
                message.has("function_call") && !message.isNull("function_call")) return state(ChatCompletionAdapter.State.FAILED);
        return text(BoundedJson.string(message, "content"));
    }

    private static Text responses(JSONObject envelope) {
        String status = BoundedJson.string(envelope, "status");
        if (!"completed".equals(status)) {
            JSONObject details = envelope.optJSONObject("incomplete_details");
            String reason = details == null ? null : BoundedJson.string(details, "reason");
            return state("incomplete".equals(status) && "max_output_tokens".equals(reason) ? ChatCompletionAdapter.State.TRUNCATED :
                    "content_filter".equals(reason) ? ChatCompletionAdapter.State.FILTERED :
                    "failed".equals(status) || "cancelled".equals(status) ? ChatCompletionAdapter.State.FAILED : ChatCompletionAdapter.State.UNKNOWN);
        }
        JSONArray output = envelope.optJSONArray("output");
        if (output == null) return state(ChatCompletionAdapter.State.INVALID);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < output.length(); i++) {
            JSONObject message = output.optJSONObject(i);
            if (message == null) return state(ChatCompletionAdapter.State.INVALID);
            String type = BoundedJson.string(message, "type");
            if ("reasoning".equals(type)) continue;
            if (!"message".equals(type) || !"assistant".equals(BoundedJson.string(message, "role"))) return state(ChatCompletionAdapter.State.FAILED);
            JSONArray parts = message.optJSONArray("content");
            if (parts == null) return state(ChatCompletionAdapter.State.INVALID);
            for (int j = 0; j < parts.length(); j++) {
                JSONObject part = parts.optJSONObject(j);
                if (part == null) return state(ChatCompletionAdapter.State.INVALID);
                if ("refusal".equals(BoundedJson.string(part, "type"))) return state(ChatCompletionAdapter.State.REFUSED);
                if (!"output_text".equals(BoundedJson.string(part, "type"))) return state(ChatCompletionAdapter.State.INVALID);
                String value = BoundedJson.string(part, "text");
                if (value == null) return state(ChatCompletionAdapter.State.INVALID);
                text.append(value);
            }
        }
        return text(text.toString());
    }

    private static Text claude(JSONObject envelope) {
        String stop = BoundedJson.string(envelope, "stop_reason");
        if (!"end_turn".equals(stop)) {
            return state("max_tokens".equals(stop) || "model_context_window_exceeded".equals(stop) ? ChatCompletionAdapter.State.TRUNCATED :
                    "refusal".equals(stop) ? ChatCompletionAdapter.State.REFUSED :
                    "tool_use".equals(stop) || "pause_turn".equals(stop) ? ChatCompletionAdapter.State.FAILED : ChatCompletionAdapter.State.UNKNOWN);
        }
        JSONArray parts = envelope.optJSONArray("content");
        if (parts == null) return state(ChatCompletionAdapter.State.INVALID);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.optJSONObject(i);
            if (part == null) return state(ChatCompletionAdapter.State.INVALID);
            String type = BoundedJson.string(part, "type");
            if ("thinking".equals(type) || "redacted_thinking".equals(type)) continue;
            if ("tool_use".equals(type)) return state(ChatCompletionAdapter.State.FAILED);
            if (!"text".equals(type)) return state(ChatCompletionAdapter.State.INVALID);
            String value = BoundedJson.string(part, "text");
            if (value == null) return state(ChatCompletionAdapter.State.INVALID);
            text.append(value);
        }
        return text(text.toString());
    }

    private static Text gemini(JSONObject envelope) {
        JSONObject feedback = envelope.optJSONObject("promptFeedback");
        String block = feedback == null ? null : BoundedJson.string(feedback, "blockReason");
        if (block != null && !"BLOCK_REASON_UNSPECIFIED".equals(block)) return state(ChatCompletionAdapter.State.FILTERED);
        JSONArray candidates = envelope.optJSONArray("candidates");
        if (candidates == null || candidates.length() != 1) return state(ChatCompletionAdapter.State.INVALID);
        JSONObject candidate = candidates.optJSONObject(0);
        if (candidate == null) return state(ChatCompletionAdapter.State.INVALID);
        String finish = BoundedJson.string(candidate, "finishReason");
        if (!"STOP".equals(finish)) {
            return state("MAX_TOKENS".equals(finish) ? ChatCompletionAdapter.State.TRUNCATED :
                    "SAFETY".equals(finish) || "RECITATION".equals(finish) || "BLOCKLIST".equals(finish) ||
                            "PROHIBITED_CONTENT".equals(finish) || "SPII".equals(finish) ? ChatCompletionAdapter.State.FILTERED :
                    "MALFORMED_FUNCTION_CALL".equals(finish) ? ChatCompletionAdapter.State.FAILED : ChatCompletionAdapter.State.UNKNOWN);
        }
        JSONObject content = candidate.optJSONObject("content");
        JSONArray parts = content == null ? null : content.optJSONArray("parts");
        if (parts == null) return state(ChatCompletionAdapter.State.INVALID);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.optJSONObject(i);
            if (part == null) return state(ChatCompletionAdapter.State.INVALID);
            if (part.has("functionCall")) return state(ChatCompletionAdapter.State.FAILED);
            if (part.has("thought") && !(part.opt("thought") instanceof Boolean)) return state(ChatCompletionAdapter.State.INVALID);
            if (part.optBoolean("thought", false)) continue;
            String value = BoundedJson.string(part, "text");
            if (value == null) return state(ChatCompletionAdapter.State.INVALID);
            text.append(value);
        }
        return text(text.toString());
    }

    private static JSONArray parts(String text) throws JSONException {
        return new JSONArray().put(new JSONObject().put("text", text));
    }
    private static Text state(ChatCompletionAdapter.State state) { return new Text(state, null); }
    private static Text text(String text) {
        return text == null || text.isEmpty() ? state(ChatCompletionAdapter.State.INVALID) : new Text(ChatCompletionAdapter.State.COMPLETE, text);
    }
    private CaptionProtocolAdapter() { }
}
