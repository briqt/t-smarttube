package com.liskovsoft.smartyoutubetv2.common.subtitle.translation;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.Arrays;
import static com.liskovsoft.smartyoutubetv2.common.subtitle.translation.ChatCompletionAdapterTest.*;
import static org.junit.Assert.*;

/** Synthetic native envelopes intercepted before DNS; no service credentials or inference calls. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 27)
public class CaptionProviderProtocolTest {
    @Test
    public void nativeRequestsUseTheirOwnInstructionAndTokenSlots() throws Exception {
        for (CaptionService service : CaptionService.values()) {
            ChatCompletionAdapter.Config wire = wire(service, model(service), ChatCompletionAdapter.Thinking.AUTO_LOW);
            JSONObject body = body(wire);
            assertFalse(body.has("api_key"));
            assertFalse(body.has("temperature"));
            switch (service) {
                case OPENAI_RESPONSES:
                    assertTrue(body.getString("instructions").contains("Translate"));
                    assertEquals(1024, body.getInt("max_output_tokens"));
                    assertFalse(body.getBoolean("store"));
                    assertTrue(new JSONObject(body.getString("input")).has("items"));
                    assertFalse(body.has("messages"));
                    assertEquals("none", body.getJSONObject("reasoning").getString("effort"));
                    break;
                case CLAUDE:
                    assertTrue(body.getString("system").contains("Translate"));
                    assertEquals("user", body.getJSONArray("messages").getJSONObject(0).getString("role"));
                    assertEquals(1, body.getJSONArray("messages").length());
                    assertEquals(1024, body.getInt("max_tokens"));
                    assertEquals("disabled", body.getJSONObject("thinking").getString("type"));
                    break;
                case GEMINI:
                    assertTrue(body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text").contains("Translate"));
                    assertEquals("user", body.getJSONArray("contents").getJSONObject(0).getString("role"));
                    assertEquals(1024, body.getJSONObject("generationConfig").getInt("maxOutputTokens"));
                    assertEquals(0, body.getJSONObject("generationConfig").getJSONObject("thinkingConfig").getInt("thinkingBudget"));
                    assertFalse(body.has("messages"));
                    assertFalse(body.has("stream"));
                    break;
                default:
                    assertEquals(service == CaptionService.OPENAI_CHAT ? "developer" : "system",
                            body.getJSONArray("messages").getJSONObject(0).getString("role"));
                    assertEquals(1024, body.getInt(service == CaptionService.OPENAI_CHAT ? "max_completion_tokens" : "max_tokens"));
                    if (service == CaptionService.OPENAI_CHAT) assertEquals("none", body.getString("reasoning_effort"));
                    if (service == CaptionService.DEEPSEEK) assertEquals("disabled", body.getJSONObject("thinking").getString("type"));
            }
        }
    }

    @Test
    public void automaticRulesAreExactAndUnknownOrExplicitDefaultNeverInjectsThinking() throws Exception {
        for (CaptionService service : CaptionService.values()) {
            for (ChatCompletionAdapter.Thinking mode : new ChatCompletionAdapter.Thinking[]{ChatCompletionAdapter.Thinking.AUTO_LOW, ChatCompletionAdapter.Thinking.OMIT}) {
                String selected = mode == ChatCompletionAdapter.Thinking.OMIT ? model(service) : model(service) + "-unverified";
                JSONObject body = body(wire(service, selected, mode));
                assertFalse(body.has("thinking"));
                assertFalse(body.has("reasoning"));
                assertFalse(body.has("reasoning_effort"));
                assertFalse(body.has("output_config"));
                if (body.has("generationConfig")) assertFalse(body.getJSONObject("generationConfig").has("thinkingConfig"));
            }
        }
        assertEquals(CaptionModelPolicy.Decision.UNKNOWN, CaptionModelPolicy.decision(CaptionService.CUSTOM_CHAT, "gpt-5.5", ChatCompletionAdapter.Thinking.AUTO_LOW));
    }

    @Test
    public void modelsThatCannotDisableThinkingUseOnlyTheirSupportedLowControl() throws Exception {
        JSONObject opus = body(wire(CaptionService.CLAUDE, "claude-opus-5-5", ChatCompletionAdapter.Thinking.AUTO_LOW));
        assertFalse(opus.has("thinking"));
        assertEquals("low", opus.getJSONObject("output_config").getString("effort"));
        JSONObject sonnet = body(wire(CaptionService.CLAUDE, "claude-sonnet-5-5", ChatCompletionAdapter.Thinking.AUTO_LOW));
        assertEquals("between_tools", sonnet.getJSONObject("thinking").getString("type"));
        assertEquals(1, sonnet.getJSONObject("thinking").length());
        assertEquals("low", sonnet.getJSONObject("output_config").getString("effort"));
        JSONObject pro = body(wire(CaptionService.GEMINI, "gemini-2.5-pro", ChatCompletionAdapter.Thinking.AUTO_LOW));
        assertEquals(128, pro.getJSONObject("generationConfig").getJSONObject("thinkingConfig").getInt("thinkingBudget"));
        JSONObject flash = body(wire(CaptionService.GEMINI, "gemini-3.8-flash", ChatCompletionAdapter.Thinking.AUTO_LOW));
        JSONObject thinking = flash.getJSONObject("generationConfig").getJSONObject("thinkingConfig");
        assertEquals("low", thinking.getString("thinkingLevel"));
        assertFalse(thinking.has("thinkingBudget"));
    }

    @Test
    public void everyNativeEnvelopeStillPassesTheSameStrictCaptionIdValidation() throws Exception {
        for (CaptionService service : CaptionService.values()) {
            ChatCompletionAdapter.Config wire = wire(service, model(service), ChatCompletionAdapter.Thinking.AUTO_LOW);
            TranslationBatch batch = batch("first", "second");
            ChatCompletionAdapter.Result result = ChatCompletionAdapter.decode(batch, wire, reply(service, output("g1", "第二", "g0", "第一")));
            assertEquals(ChatCompletionAdapter.State.COMPLETE, result.state);
            assertEquals(Arrays.asList("g0", "g1"), new java.util.ArrayList<>(result.translations.keySet()));
            assertEquals("第一", result.translations.get("g0"));
            assertEquals(ChatCompletionAdapter.State.INVALID,
                    ChatCompletionAdapter.decode(batch, wire, reply(service, output("g99", "unknown"))).state);
        }
    }

    @Test
    public void nativeTruncationRefusalAndUnfinishedRepliesCannotBorrowCompleteLookingText() throws Exception {
        String content = output("g0", "not accepted");
        JSONObject claude = new JSONObject(new String(reply(CaptionService.CLAUDE, content), utf8()));
        for (String stop : new String[]{"max_tokens", "refusal", "tool_use", "pause_turn", "novel"}) {
            claude.put("stop_reason", stop);
            assertNotEquals(ChatCompletionAdapter.State.COMPLETE, decode(CaptionService.CLAUDE, claude));
        }
        JSONObject responses = new JSONObject(new String(reply(CaptionService.OPENAI_RESPONSES, content), utf8()));
        for (String status : new String[]{"incomplete", "failed", "in_progress", "queued", "cancelled"}) {
            responses.put("status", status).put("incomplete_details", new JSONObject().put("reason", "max_output_tokens"));
            assertNotEquals(ChatCompletionAdapter.State.COMPLETE, decode(CaptionService.OPENAI_RESPONSES, responses));
        }
        responses.put("status", "completed").put("output", new JSONArray().put(new JSONObject().put("type", "message").put("role", "assistant")
                .put("content", new JSONArray().put(new JSONObject().put("type", "refusal").put("refusal", "no")))));
        assertEquals(ChatCompletionAdapter.State.REFUSED, decode(CaptionService.OPENAI_RESPONSES, responses));
        JSONObject gemini = new JSONObject(new String(reply(CaptionService.GEMINI, content), utf8()));
        for (String finish : new String[]{"MAX_TOKENS", "SAFETY", "CONTINUATION", "novel"}) {
            gemini.getJSONArray("candidates").getJSONObject(0).put("finishReason", finish);
            assertNotEquals(ChatCompletionAdapter.State.COMPLETE, decode(CaptionService.GEMINI, gemini));
        }
        JSONObject blocked = new JSONObject().put("promptFeedback", new JSONObject().put("blockReason", "SAFETY"));
        assertEquals(ChatCompletionAdapter.State.FILTERED, decode(CaptionService.GEMINI, blocked));
    }

    @Test
    public void productionTransportUsesNativeHeadersAndBindsTheGeminiModelToItsPath() {
        for (CaptionService service : CaptionService.values()) {
            ChatCompletionAdapter.Config wire = wire(service, model(service), ChatCompletionAdapter.Thinking.AUTO_LOW);
            CaptionHttpTransport transport = new CaptionHttpTransport(new OkHttpClient.Builder().addInterceptor(chain -> {
                assertNull(chain.request().url().query());
                if (service == CaptionService.GEMINI) {
                    assertEquals("synthetic-provider-key", chain.request().header("x-goog-api-key"));
                    assertNull(chain.request().header("Authorization"));
                    assertEquals("/v1beta/models/" + wire.model + ":generateContent", chain.request().url().encodedPath());
                } else assertEquals("Bearer synthetic-provider-key", chain.request().header("Authorization"));
                if (service == CaptionService.CLAUDE) {
                    assertEquals("2026-10-21", chain.request().header("anthropic-version"));
                    assertEquals("wrkspc_fixture", chain.request().header("anthropic-workspace-id"));
                } else assertNull(chain.request().header("anthropic-workspace-id"));
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                        .body(ResponseBody.create(MediaType.parse("application/json"), "{}")).build();
            }).build());
            assertEquals(CaptionHttpTransport.Status.RECEIVED,
                    transport.exchange(wire, () -> "synthetic-provider-key", new byte[]{1}).execute().status);
        }
    }

    @Test
    public void clientCompletesEachNativeProtocolWithoutProviderFallback() {
        for (CaptionService service : CaptionService.values()) {
            int[] calls = {0};
            ChatTranslationClient client = new ChatTranslationClient(new OkHttpClient.Builder().addInterceptor(chain -> {
                calls[0]++;
                try {
                    return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                            .body(ResponseBody.create(MediaType.parse("application/json"), reply(service, output("g0", "译文")))).build();
                } catch (Exception e) { throw new AssertionError(e); }
            }).build());
            ChatTranslationClient.Result result = client.newJob(batch("first"), wire(service, model(service), ChatCompletionAdapter.Thinking.AUTO_LOW),
                    () -> "synthetic-provider-key", 2).execute();
            assertEquals(ChatTranslationClient.Status.COMPLETE, result.status);
            assertEquals("译文", result.translations.get("g0"));
            assertEquals(1, calls[0]);
        }
    }

    @Test
    public void credentialsAndGeminiFullEndpointsCannotSilentlyChangeTargets() {
        ChatCompletionAdapter.Config base = wire(CaptionService.GEMINI, "gemini-2.5-flash", ChatCompletionAdapter.Thinking.AUTO_LOW);
        ChatCompletionAdapter.Config full = new ChatCompletionAdapter.Config(base.requestEndpoint().toString(), base.model, 1024, 65536, 65536,
                ChatCompletionAdapter.TokenField.MAX_TOKENS, base.thinking, base.service, null);
        assertEquals(base.requestEndpoint(), full.requestEndpoint());
        try {
            new ChatCompletionAdapter.Config(base.requestEndpoint().toString(), "gemini-2.5-pro", 1024, 65536, 65536,
                    ChatCompletionAdapter.TokenField.MAX_TOKENS, base.thinking, base.service, null);
            fail("A full URL must not name a different model");
        } catch (IllegalArgumentException expected) { }
        ChatCompletionAdapter.Config chat = wire(CaptionService.OPENAI_CHAT, "gpt-5.5", ChatCompletionAdapter.Thinking.AUTO_LOW);
        ChatCompletionAdapter.Config custom = new ChatCompletionAdapter.Config(chat.endpoint.toString(), chat.model, 1024, 65536, 65536,
                chat.tokenField, chat.thinking);
        assertFalse(chat.sameCredentialTarget(custom));
    }

    @Test
    public void protocolAndWorkspaceParticipateInTheRequestCacheNamespace() {
        ChatCompletionAdapter.Config nativeChat = wire(CaptionService.OPENAI_CHAT, "gpt-5.5", ChatCompletionAdapter.Thinking.AUTO_LOW);
        ChatCompletionAdapter.Config custom = new ChatCompletionAdapter.Config(nativeChat.endpoint.toString(), nativeChat.model, 1024, 65536, 65536,
                nativeChat.tokenField, nativeChat.thinking);
        assertFalse(Arrays.equals(profile(nativeChat).semantics, profile(custom).semantics));
        ChatCompletionAdapter.Config claude = wire(CaptionService.CLAUDE, "claude-sonnet-4-6", ChatCompletionAdapter.Thinking.AUTO_LOW);
        ChatCompletionAdapter.Config workspace = new ChatCompletionAdapter.Config(claude.endpoint.toString(), claude.model, 1024, 65536, 65536,
                claude.tokenField, claude.thinking, claude.service, "wrkspc_other");
        assertFalse(Arrays.equals(profile(claude).semantics, profile(workspace).semantics));
    }

    private static TranslationCoordinator.Profile profile(ChatCompletionAdapter.Config wire) {
        return new TranslationCoordinator.Profile(wire, () -> "synthetic-key", 0, "en", "zh", "",
                new TranslationBatch.Limits(4, 1000, 0, 1000), 1);
    }

    static ChatCompletionAdapter.Config wire(CaptionService service, String model, ChatCompletionAdapter.Thinking thinking) {
        return new ChatCompletionAdapter.Config(service.defaultEndpoint.isEmpty() ? "https://example.invalid/chat/completions" : service.defaultEndpoint,
                model, 1024, 65536, 65536, ChatCompletionAdapter.TokenField.MAX_TOKENS, thinking, service,
                service == CaptionService.CLAUDE ? "wrkspc_fixture" : null);
    }
    private static String model(CaptionService service) {
        switch (service) {
            case OPENAI_CHAT:
            case OPENAI_RESPONSES: return "gpt-5.5";
            case DEEPSEEK: return "deepseek-flash";
            case CLAUDE: return "claude-sonnet-4-6";
            case GEMINI: return "gemini-2.5-flash";
            default: return "fixture-model";
        }
    }
    private static JSONObject body(ChatCompletionAdapter.Config wire) throws Exception {
        return new JSONObject(new String(ChatCompletionAdapter.encode(batch("first"), wire), utf8()));
    }
    private static ChatCompletionAdapter.State decode(CaptionService service, JSONObject envelope) {
        return ChatCompletionAdapter.decode(batch("first"), wire(service, model(service), ChatCompletionAdapter.Thinking.AUTO_LOW), envelope.toString().getBytes(utf8())).state;
    }
    static byte[] reply(CaptionService service, String content) throws Exception {
        JSONObject envelope;
        int middle = content.length() / 2;
        if (service == CaptionService.OPENAI_RESPONSES) {
            envelope = new JSONObject().put("status", "completed").put("output", new JSONArray()
                    .put(new JSONObject().put("type", "reasoning").put("summary", "not a translation"))
                    .put(new JSONObject().put("type", "message").put("role", "assistant").put("content", new JSONArray()
                            .put(new JSONObject().put("type", "output_text").put("text", content.substring(0, middle)))
                            .put(new JSONObject().put("type", "output_text").put("text", content.substring(middle))))));
        } else if (service == CaptionService.CLAUDE) {
            envelope = new JSONObject().put("stop_reason", "end_turn").put("content", new JSONArray()
                    .put(new JSONObject().put("type", "thinking").put("thinking", "not a translation"))
                    .put(new JSONObject().put("type", "text").put("text", content.substring(0, middle)))
                    .put(new JSONObject().put("type", "text").put("text", content.substring(middle))));
        } else if (service == CaptionService.GEMINI) {
            envelope = new JSONObject().put("candidates", new JSONArray().put(new JSONObject().put("finishReason", "STOP")
                    .put("content", new JSONObject().put("parts", new JSONArray()
                            .put(new JSONObject().put("text", "not a translation").put("thought", true))
                            .put(new JSONObject().put("text", content.substring(0, middle)))
                            .put(new JSONObject().put("text", content.substring(middle)))))));
        } else return envelope("stop", content, "not a translation");
        return envelope.toString().getBytes(utf8());
    }
}
