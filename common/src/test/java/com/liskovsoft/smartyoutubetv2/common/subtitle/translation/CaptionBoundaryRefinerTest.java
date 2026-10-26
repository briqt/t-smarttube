package com.liskovsoft.smartyoutubetv2.common.subtitle.translation;

import com.liskovsoft.mediaserviceinterfaces.data.MediaSubtitle;
import com.liskovsoft.smartyoutubetv2.common.subtitle.session.CaptionSession;
import com.liskovsoft.smartyoutubetv2.common.subtitle.source.CaptionSourceClassifier;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.AsrTranscriptPlanner;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.RawTimeline;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.RawWebvttParser;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.SemanticGroupPlanner;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.lang.reflect.Proxy;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 27)
public class CaptionBoundaryRefinerTest {
    private static final String CANONICAL = "old\nnew line\nlast line";

    @Test
    public void anAutomaticBoundaryExchangePublishesBeforeTranslationAndDoesNotRepayOnSeekOrResample() throws Exception {
        try (Harness h = new Harness("stop", 200)) {
            CaptionSession.Snapshot deterministic = h.session.getSnapshot();
            h.engine.tick(1_000_000, false); h.queue.drain();
            assertEquals(1, h.boundaries);
            assertEquals(0, h.translations);
            CaptionSession.Snapshot typed = h.session.getSnapshot();
            assertNotSame(deterministic, typed);
            assertTrue(typed.sameDocumentAs(deterministic));
            assertEquals(Arrays.asList("old\n", "new line\n", "last line"), texts(typed));
            h.engine.tick(1_000_000, false); h.queue.drain();
            assertEquals(1, h.boundaries);
            assertEquals(1, h.translations);
            assertEquals(Arrays.asList("old\n", "new line\n", "last line"), h.sent);
            assertEquals(2, h.engine.prepareFrame().items.size());
            assertEquals("旧行", h.engine.prepareFrame().items.get(0).translation);
            assertEquals("新行", h.engine.prepareFrame().items.get(1).translation);
            h.engine.tick(2_500_000, true); h.queue.drain();
            h.engine.tick(1_000_000, true); h.queue.drain();
            assertEquals(1, h.boundaries);
            assertEquals(1, h.translations);
            assertEquals(1, h.maximumConcurrent);
        }
    }

    @Test
    public void truncatedBoundaryReplyKeepsDeterministicTextAndFallsBackWithinTheSameWorker() throws Exception {
        try (Harness h = new Harness("length", 200)) {
            CaptionSession.Snapshot original = h.session.getSnapshot();
            h.engine.tick(1_000_000, false); h.queue.drain();
            assertSame(original, h.session.getSnapshot());
            assertEquals(1, h.boundaries);
            assertEquals(1, h.translations);
            assertEquals(Arrays.asList(CANONICAL), h.sent);
            assertEquals("整句", h.engine.prepareFrame().items.get(0).translation);
            for (int i = 0; i < 10; i++) { h.engine.tick(1_500_000, i % 2 == 0); h.queue.drain(); }
            assertEquals(1, h.boundaries);
            assertEquals(1, h.translations);
        }
    }

    @Test
    public void boundaryAuthenticationAndRateLimitsDoNotLaunchATranslationBehindTheError() throws Exception {
        for (int code : new int[]{401, 429}) {
            try (Harness h = new Harness("stop", code)) {
                h.engine.tick(1_000_000, false); h.queue.drain();
                assertEquals(1, h.boundaries);
                assertEquals(0, h.translations);
                assertNull(h.engine.prepareFrame().items.get(0).translation);
                for (int i = 0; i < 5; i++) { h.engine.tick(1_000_000, false); h.queue.drain(); }
                assertEquals(1, h.boundaries);
                assertEquals(0, h.translations);
            }
        }
    }

    @Test
    public void aConfigurationChangeDuringTheBoundaryReplyCannotPublishTheOldDecision() throws Exception {
        try (Harness h = new Harness("stop", 200)) {
            CaptionSession.Snapshot original = h.session.getSnapshot();
            h.beforeReply = () -> h.engine.configure(null);
            h.engine.tick(1_000_000, false); h.queue.drain();
            assertSame(original, h.session.getSnapshot());
            assertEquals(1, h.boundaries);
            assertEquals(0, h.translations);
        }
    }

    @Test
    public void modelCannotTurnBoundedGroupsIntoOneScreenFillingParagraph() throws Exception {
        try (Harness h = new Harness("stop", 200, true)) {
            CaptionSession.Snapshot original = h.session.getSnapshot();
            assertTrue(original.groups.getGroups().size() > 1);
            h.engine.tick(1_000_000, false); h.queue.drain();
            assertSame(original, h.session.getSnapshot());
            assertEquals(1, h.boundaries);
            assertEquals(1, h.translations);
            assertEquals(texts(original), h.sent);
            h.engine.tick(1_000_000, false); h.queue.drain();
            assertEquals(1, h.boundaries);
            assertNotNull(h.engine.prepareFrame().items.get(0).translation);
        }
    }

    private static List<String> texts(CaptionSession.Snapshot snapshot) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < snapshot.groups.getGroups().size(); i++) result.add(snapshot.groups.getText(i));
        return result;
    }
    private static final class Queue implements java.util.concurrent.Executor {
        final List<Runnable> tasks = new ArrayList<>();
        public void execute(Runnable action) { tasks.add(action); }
        void drain() { int n = 0; while (!tasks.isEmpty()) { if (++n > 30) throw new AssertionError("Unbounded worker loop"); tasks.remove(0).run(); } }
    }
    private static final class Harness implements AutoCloseable {
        final CaptionSession session = new CaptionSession();
        final Queue queue = new Queue();
        final TranslationCoordinator engine;
        final List<String> sent = new ArrayList<>();
        int boundaries, translations, concurrent, maximumConcurrent;
        Runnable beforeReply;
        Harness(String finish, int boundaryCode) throws Exception { this(finish, boundaryCode, false); }
        Harness(String finish, int boundaryCode, boolean longUnit) throws Exception {
            StringBuilder old = new StringBuilder();
            if (longUnit) for (int i = 0; i < 30; i++) old.append("word ");
            old.append("old");
            String canonical = old + "\nnew line\nlast line";
            RawTimeline raw = RawWebvttParser.parse(bytes("WEBVTT\n\n00:01.000 --> 00:02.000\n" + old + "\nnew<00:01.500> line\n\n" +
                    "00:02.000 --> 00:02.010\nnew line\n\n00:02.010 --> 00:03.000\nnew line\nlast<00:02.500> line\n"), 0,
                    new RawTimeline.Limits(65536, 128, 1024));
            MediaSubtitle source = (MediaSubtitle) Proxy.newProxyInstance(MediaSubtitle.class.getClassLoader(),
                    new Class<?>[]{MediaSubtitle.class}, (proxy, method, args) -> "getBaseUrl".equals(method.getName()) ?
                            "https://example.invalid/timedtext?lang=en&kind=asr" : null);
            AsrTranscriptPlanner.Analysis analysis = AsrTranscriptPlanner.prepare(raw, CaptionSourceClassifier.classify(source),
                    new RawTimeline.Limits(65536, 128, 1024), new SemanticGroupPlanner.Limits(128, 1024, 4096));
            assertTrue(analysis.normalized);
            List<String> deterministicSources = new ArrayList<>();
            StringBuilder joined = new StringBuilder();
            for (int i = 0; i < analysis.groups.getGroups().size(); i++) {
                String text = analysis.groups.getText(i);
                deterministicSources.add(text); joined.append(text);
                assertTrue(text.codePointCount(0, text.length()) <= 120);
            }
            assertEquals(canonical, joined.toString());
            CaptionSession.Track track = session.selectTrack(1, CaptionSourceClassifier.classify(source));
            assertTrue(session.accept(session.beginSample(track), raw, analysis.transcript, analysis.groups));
            ChatTranslationClient client = new ChatTranslationClient(new OkHttpClient.Builder().addInterceptor(chain -> {
                concurrent++; maximumConcurrent = Math.max(maximumConcurrent, concurrent);
                try {
                    Buffer buffer = new Buffer(); chain.request().body().writeTo(buffer);
                    JSONObject body = BoundedJson.object(buffer.readUtf8());
                    JSONObject input = BoundedJson.object(body.getJSONArray("messages").getJSONObject(1).getString("content"));
                    int code = 200;
                    String completion = "stop";
                    JSONObject content;
                    if (input.has("pieces")) {
                        boundaries++;
                        assertEquals(512, body.getInt("max_tokens"));
                        JSONArray pieces = input.getJSONArray("pieces");
                        if (!longUnit) assertEquals(9, pieces.length());
                        StringBuilder sourceText = new StringBuilder();
                        for (int i = 0; i < pieces.length(); i++) sourceText.append(pieces.getJSONObject(i).getString("text"));
                        assertEquals(canonical, sourceText.toString());
                        JSONArray ends = longUnit ? new JSONArray().put(pieces.getJSONObject(pieces.length() - 1).getString("id")) :
                                new JSONArray().put("p1").put("p5").put("p8");
                        content = new JSONObject().put("boundaries", ends);
                        code = boundaryCode; completion = finish;
                        Runnable callback = beforeReply; beforeReply = null;
                        if (callback != null) callback.run();
                    } else {
                        translations++;
                        JSONArray items = input.getJSONArray("items"), out = new JSONArray();
                        for (int i = 0; i < items.length(); i++) {
                            JSONObject item = items.getJSONObject(i);
                            String text = item.getString("source"); sent.add(text);
                            assertTrue("Strict source dictionary", longUnit ? deterministicSources.contains(text) :
                                    "old\n".equals(text) || "new line\n".equals(text) || "last line".equals(text) || CANONICAL.equals(text));
                            String translated = "old\n".equals(text) ? "旧行" : "new line\n".equals(text) ? "新行" :
                                    "last line".equals(text) ? "末行" : "整句";
                            out.put(new JSONObject().put("id", item.getString("id")).put("text", translated));
                        }
                        content = new JSONObject().put("translations", out);
                    }
                    String envelope = new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("finish_reason", completion)
                            .put("message", new JSONObject().put("content", content.toString())))).toString();
                    Response.Builder response = new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                            .code(code).message("synthetic").body(ResponseBody.create(MediaType.parse("application/json"), envelope));
                    if (code == 429) response.header("Retry-After", "7");
                    return response.build();
                } catch (org.json.JSONException invalid) { throw new AssertionError(invalid); }
                finally { concurrent--; }
            }).build());
            engine = new TranslationCoordinator(session, () -> 0, queue, (batch, profile) -> {
                ChatTranslationClient.Job job = client.newJob(batch, profile.wire, profile.credentials, profile.maxCalls);
                return new TranslationCoordinator.Work() {
                    public ChatTranslationClient.Result execute() { return job.execute(); }
                    public void cancel() { job.cancel(); }
                };
            }, new TranslationCoordinator.Limits(30_000_000, 32, 8192, 8192, 2, 50, 50));
            engine.setBoundaryRefiner(new CaptionBoundaryRefiner(client.transport(), () -> 0));
            TranslationCoordinator.Profile profile = new TranslationCoordinator.Profile(new ChatCompletionAdapter.Config(
                    "https://example.invalid/chat/completions", "fixture", 1000, 65536, 65536,
                    ChatCompletionAdapter.TokenField.MAX_TOKENS, ChatCompletionAdapter.Thinking.DEEPSEEK_DISABLED),
                    () -> "synthetic-fixture-key", 0, "en", "zh", "", new TranslationBatch.Limits(4, 4096, 0, 4096), 1);
            engine.configure(profile); engine.setVisible(true); engine.setRequesting(true);
        }
        public void close() { engine.close(); session.close(); }
    }
    private static byte[] bytes(String source) { return source.getBytes(Charset.forName("UTF-8")); }
}
