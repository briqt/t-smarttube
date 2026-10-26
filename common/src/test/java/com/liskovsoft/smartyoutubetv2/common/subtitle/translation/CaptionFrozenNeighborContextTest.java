package com.liskovsoft.smartyoutubetv2.common.subtitle.translation;

import com.liskovsoft.smartyoutubetv2.common.subtitle.session.CaptionSession;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.MappedTranscript;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.RawTimeline;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.RawWebvttParser;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.SemanticGroupPlanner;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

import static org.junit.Assert.*;

/** Independent synthetic contracts; the separate production-entry oracle remains untouched. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 27)
public class CaptionFrozenNeighborContextTest {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String PRIOR = "00:01.000 --> 00:02.000\ndid not approve\n\n";
    private static final String CHANGED = "00:01.000 --> 00:02.000\napproved\n\n";
    private static final String TARGET = "00:03.000 --> 00:04.000\ntarget\n\n";
    private static final String FUTURE = "00:05.000 --> 00:06.000\nnew future\n\n";

    @Test
    public void batchCountsGlobalAndEveryRepeatedLocalCopyIncludingUnicodeAndLabels() throws Exception {
        try (Harness h = new Harness(12, 2000, "", 16, 8192, 1000, 0, PRIOR + TARGET)) {
            String global = "explicit🙂";
            String local = "[before]\nno🙂\n";
            Map<Integer, String> contexts = new HashMap<>();
            contexts.put(0, local);
            contexts.put(1, local);
            int total = cp(global) + 2 * cp(local);
            TranslationBatch batch = batch(h, Arrays.asList(0, 1), global, total, contexts);
            JSONObject input = input(ChatCompletionAdapter.encode(batch, wire()));
            assertEquals(total, sentContextPoints(input));
            assertEquals(local, input.getJSONArray("items").getJSONObject(0).getString("context"));
            assertEquals(local, input.getJSONArray("items").getJSONObject(1).getString("context"));
            invalid(() -> batch(h, Arrays.asList(0, 1), global, total - 1, contexts));
            contexts.put(1, "\ud800");
            invalid(() -> batch(h, Arrays.asList(0, 1), global, total, contexts));
        }
    }

    @Test
    public void twelveSmallLocalsAndRecoverySubsetsPreserveTheExactSameItems() throws Exception {
        StringBuilder cues = new StringBuilder();
        for (int i = 0; i < 12; i++) cues.append(cue(i * 2 + 1, "item" + i));
        try (Harness h = new Harness(12, 2000, "", 16, 8192, 1000, 0, cues.toString())) {
            Map<Integer, String> contexts = new HashMap<>();
            List<Integer> indices = new ArrayList<>();
            for (int i = 0; i < 12; i++) { indices.add(i); contexts.put(i, "[n]🙂"); }
            String global = "g";
            int total = cp(global) + 12 * cp("[n]🙂");
            TranslationBatch original = batch(h, indices, global, total, contexts);
            assertEquals(12, original.getItems().size());
            assertEquals(total, sentContextPoints(input(ChatCompletionAdapter.encode(original, wire()))));
            TranslationBatch.Item item = original.getItems().get(5);
            contexts.put(5, "later mutation must not upgrade the frozen item");
            h.replace(cues + cue(26, "newly observed tail"));
            TranslationBatch subset = original.subset(Collections.singletonList(item));
            assertSame(item, subset.getItems().get(0));
            assertEquals("[n]🙂", item.context);
            JSONObject subsetInput = input(ChatCompletionAdapter.encode(subset, wire()));
            assertEquals(cp(global) + cp(item.context), sentContextPoints(subsetInput));
            assertEquals(item.context, subsetInput.getJSONArray("items").getJSONObject(0).getString("context"));
            assertTrue(subset.isFor(originalPlan(original, h))); // Parent plan, not h's newly installed Plan.
        }
    }

    @Test
    public void realClientTruncationRecoveryKeepsLocalContextsAndTheOriginalTotalBudget() throws Exception {
        try (Harness h = new Harness(2, 2000, "", 16, 8192, 1000, 0, PRIOR + TARGET)) {
            Map<Integer, String> contexts = new HashMap<>();
            contexts.put(0, "[after]\ntarget\n");
            contexts.put(1, "[before]\ndid not approve\n");
            int total = cp("global") + cp(contexts.get(0)) + cp(contexts.get(1));
            TranslationBatch original = batch(h, Arrays.asList(0, 1), "global", total, contexts);
            List<JSONObject> actual = new ArrayList<>();
            ChatTranslationClient client = new ChatTranslationClient(new OkHttpClient.Builder()
                    .dns(host -> { throw new AssertionError("No DNS"); })
                    .addInterceptor(chain -> {
                        try {
                            Buffer buffer = new Buffer();
                            chain.request().body().writeTo(buffer);
                            JSONObject data = input(buffer.readByteArray());
                            actual.add(data);
                            assertTrue(sentContextPoints(data) <= total);
                            if (actual.size() == 1) {
                                contexts.put(1, "later context is not available to recovery");
                                h.replace(TARGET + FUTURE);
                                return response(chain.request(), "length", new JSONArray());
                            }
                            return response(chain.request(), "stop", translations(data));
                        } catch (Exception failure) { throw new AssertionError(failure); }
                    }).build());
            ChatTranslationClient.Result result = client.newJob(original, wire(), () -> "synthetic-context-key", 3).execute();
            assertEquals(ChatTranslationClient.Status.COMPLETE, result.status);
            assertEquals(3, actual.size());
            for (int i = 1; i < actual.size(); i++) {
                JSONArray items = actual.get(i).getJSONArray("items");
                for (int j = 0; j < items.length(); j++) {
                    JSONObject recovered = items.getJSONObject(j);
                    String expected = recovered.getString("id").equals("g0") ? "[after]\ntarget\n" :
                            "[before]\ndid not approve\n";
                    assertEquals(expected, recovered.getString("context"));
                }
            }
        }
    }

    @Test
    public void frameQueriesCannotFreezeContextBeforeWorkerAdmission() throws Exception {
        try (Harness h = new Harness(1, 2000, "", 16, 8192, 1000, 0, PRIOR + TARGET)) {
            h.enableWithoutDraining(3_500_000);
            TranslationCoordinator.Frame frame = h.engine.prepareFrame();
            assertNotNull(frame);
            assertNull(frame.items.get(0).translation);
            SemanticTranslationCache cache = ReflectionHelpers.getField(h.engine, "mCompleted");
            assertTrue(cache.records().isEmpty());
            h.queue.drain();
            assertFalse(cache.records().isEmpty());
            assertTrue(h.lastInputFor("target").getString("context").contains("did not approve"));
        }
    }

    @Test
    public void appendDoesNotUpgradePaidContextUntilItsOriginalAbsoluteExpiry() throws Exception {
        try (Harness h = new Harness(1, 2000, "", 16, 8192, 1000, 0, TARGET)) {
            h.start(3_500_000);
            int paid = h.inputs.size();
            assertEquals("", h.lastInputFor("target").getString("context"));
            TranslationCoordinator.Frame old = h.engine.prepareFrame();
            h.replace(TARGET + FUTURE);
            h.tick(3_500_000, false);
            assertEquals(paid, h.inputs.size());
            assertEquals("ok:target", h.engine.prepareFrame().items.get(0).translation);
            assertFalse(h.engine.deliver(old, ignored -> fail("Old snapshot Frame")));
            h.now = 900;
            h.tick(3_500_000, true);
            assertEquals(paid, h.inputs.size());
            TranslationCoordinator.Frame nearExpiry = h.engine.prepareFrame();
            h.now = 1000;
            assertFalse(h.engine.deliver(nearExpiry, ignored -> fail("Hit must not extend expiry")));
            h.tick(3_500_000, false);
            assertEquals(paid + 1, h.inputs.size());
            assertTrue(h.lastInputFor("target").getString("context").contains("new future"));
        }
    }

    @Test
    public void ordinalChangesAndOrdinaryLeftSlidingLossRetainTheFrozenEvidence() throws Exception {
        try (Harness h = new Harness(1, 2000, "", 16, 8192, 1000, 0, PRIOR + TARGET)) {
            h.start(3_500_000);
            int paid = h.inputs.size();
            String frozen = h.lastInputFor("target").getString("context");
            h.replace("00:00.000 --> 00:00.500\nunrelated prefix\n\n" + PRIOR + TARGET);
            h.tick(3_500_000, false);
            assertEquals(paid, h.inputs.size());
            h.replace(TARGET);
            h.tick(3_500_000, false);
            assertEquals(paid, h.inputs.size());
            assertEquals("ok:target", h.engine.prepareFrame().items.get(0).translation);
            SemanticTranslationCache cache = ReflectionHelpers.getField(h.engine, "mCompleted");
            assertTrue(cache.records().values().stream().anyMatch(record -> record.frozen != null && record.frozen.text.equals(frozen)));
        }
    }

    @Test
    public void sameSlotRevisionChangesOnlyRequestsThatActuallySentTheChangedEvidence() throws Exception {
        String far = "00:30.000 --> 00:31.000\nfar\n\n";
        try (Harness h = new Harness(1, 2000, "", 16, 8192, 1000, 0, PRIOR + TARGET + far)) {
            h.start(3_500_000);
            assertTrue(h.lastInputFor("target").getString("context").contains("did not approve"));
            h.tick(30_500_000, true);
            int paid = h.inputs.size();
            TranslationCoordinator.Frame old = h.engine.prepareFrame();
            h.replace(CHANGED + TARGET + far);
            h.tick(30_500_000, false);
            assertEquals("Unrelated far item remains paid", paid, h.inputs.size());
            assertFalse(h.engine.deliver(old, ignored -> fail("Old Frame")));
            h.tick(3_500_000, true);
            assertEquals(paid + 1, h.inputs.size());
            String revised = h.lastInputFor("target").getString("context");
            assertTrue(revised.contains("approved"));
            assertFalse(revised.contains("did not approve"));
        }
    }

    @Test
    public void omittedDependencyRevisionRepreparesButEqualActualBodyDoesNotRepay() throws Exception {
        try (Harness h = new Harness(1, 1, "", 16, 8192, 1000, 0, PRIOR + TARGET)) {
            h.start(3_500_000);
            int paid = h.inputs.size();
            assertEquals("", h.lastInputFor("target").getString("context"));
            TranslationCoordinator.Frame old = h.engine.prepareFrame();
            SemanticTranslationCache cache = ReflectionHelpers.getField(h.engine, "mCompleted");
            SemanticTranslationCache.Record before = cache.records().values().iterator().next();
            assertFalse(before.frozen.dependencies.isEmpty());
            h.replace(CHANGED + TARGET);
            h.tick(3_500_000, false);
            SemanticTranslationCache.Record after = cache.records().values().iterator().next();
            assertNotSame(before.frozen, after.frozen);
            assertEquals(before.key, after.key);
            assertEquals(before.expiresAtMs, after.expiresAtMs);
            assertEquals(paid, h.inputs.size());
            assertEquals("ok:target", h.engine.prepareFrame().items.get(0).translation);
            assertFalse(h.engine.deliver(old, ignored -> fail("Association update cannot revive old Frame")));
        }
    }

    @Test
    public void zeroLocalBudgetAndExplicitContextUsingAllBudgetStillTranslateUnknownSource() throws Exception {
        for (String global : Arrays.asList("", "explicit")) {
            try (Harness h = new Harness(1, cp(global), global, 16, 8192, 1000, 0, PRIOR + TARGET)) {
                h.start(3_500_000);
                int paid = h.inputs.size();
                assertEquals(global, h.inputs.get(0).getString("context"));
                assertEquals("", h.lastInputFor("target").getString("context"));
                SemanticTranslationCache cache = ReflectionHelpers.getField(h.engine, "mCompleted");
                assertTrue(cache.records().values().iterator().next().frozen.dependencies.isEmpty());
                h.replace(CHANGED + TARGET);
                h.tick(3_500_000, false);
                assertEquals(paid, h.inputs.size());
                assertEquals("ok:target", h.engine.prepareFrame().items.get(0).translation);
            }
        }
    }

    @Test
    public void workerPackingReducesTargetsRatherThanTrimmingTheirFrozenContexts() throws Exception {
        StringBuilder cues = new StringBuilder();
        for (int i = 0; i < 12; i++) cues.append(cue(i * 2 + 1, "item" + i + repeat('x', 120)));
        try (Harness h = new Harness(12, 2000, "", 16, 8192, 1000, 30_000_000, cues.toString())) {
            h.start(1_500_000);
            assertFalse(h.inputs.isEmpty());
            int firstItems = h.inputs.get(0).getJSONArray("items").length();
            assertTrue(firstItems > 0 && firstItems < 12);
            SemanticTranslationCache cache = ReflectionHelpers.getField(h.engine, "mCompleted");
            Map<String, String> frozen = new HashMap<>();
            for (int i = 0; i < 12; i++) {
                SemanticTranslationCache.Key base = SemanticTranslationCache.key(h.session.getSnapshot(), h.profile, i);
                SemanticTranslationCache.Record record = cache.records().get(base);
                assertNotNull(record);
                assertNotNull(record.frozen);
                frozen.put(h.session.getSnapshot().groups.getText(i), record.frozen.text);
            }
            for (int i = 0; i < 12; i++) h.tick(1_500_000, false);
            Map<String, String> observed = new HashMap<>();
            for (JSONObject request : h.inputs) {
                assertTrue(sentContextPoints(request) <= 2000);
                JSONArray items = request.getJSONArray("items");
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.getJSONObject(i);
                    String source = item.getString("source");
                    assertEquals(frozen.get(source), item.getString("context"));
                    observed.put(source, item.getString("context"));
                }
            }
            assertEquals(frozen, observed);
        }
    }

    @Test
    public void completedValuePressureKeepsFrozenContextAndSuppressionAcrossResampleAndSeek() throws Exception {
        String cues = cue(1, "a" + repeat('x', 69)) + cue(3, "b" + repeat('y', 69));
        try (Harness h = new Harness(2, 2000, "", 2, 128, 1000, 10_000_000, cues)) {
            h.start(1_500_000);
            int paid = h.inputs.size();
            assertTrue(paid > 0);
            assertNotNull(h.engine.prepareFrame().items.get(0).translation);
            for (int i = 0; i < 6; i++) {
                h.replace(cues);
                h.tick(1_500_000, true);
            }
            assertEquals(paid, h.inputs.size());
            SemanticTranslationCache cache = ReflectionHelpers.getField(h.engine, "mCompleted");
            assertTrue(cache.records().values().stream().allMatch(record -> record.frozen != null && !record.frozen.text.isEmpty()));
        }
    }

    @Test
    public void contextCapacityRejectionCannotRetryUsingANewEmptyContext() throws Exception {
        try (Harness h = new Harness(1, 2000, "", 2, 1, 1000, 0, PRIOR + TARGET)) {
            h.start(3_500_000);
            SemanticTranslationCache cache = ReflectionHelpers.getField(h.engine, "mCompleted");
            assertFalse(cache.records().isEmpty());
            assertNull(cache.records().values().iterator().next().frozen);
            for (int i = 0; i < 6; i++) {
                h.replace(PRIOR + TARGET);
                h.tick(3_500_000, true);
            }
            assertEquals(0, h.inputs.size());
            assertNull(h.engine.prepareFrame().items.get(0).translation);
            assertTrue(cache.records().size() <= 4);
            assertTrue(((Integer) ReflectionHelpers.getField(cache, "mContextCharacters")) <= 2);
        }
    }

    @Test
    public void completionNearDeadlineDoesNotRenewTheFirstAdmissionTtl() throws Exception {
        try (Harness h = new Harness(1, 0, "", 16, 8192, 100, 0, TARGET)) {
            h.beforeResponse = () -> h.now = 90;
            h.start(3_500_000);
            TranslationCoordinator.Frame frame = h.engine.prepareFrame();
            assertEquals("ok:target", frame.items.get(0).translation);
            int paid = h.inputs.size();
            h.now = 99;
            h.tick(3_500_000, false);
            assertEquals(paid, h.inputs.size());
            h.now = 100;
            assertFalse(h.engine.deliver(frame, ignored -> fail("Completion cannot restart admission TTL")));
            h.tick(3_500_000, false);
            assertEquals(paid + 1, h.inputs.size());
        }
    }

    @Test
    public void cancelledOldSnapshotResponseCannotSeedTheRevisedContext() throws Exception {
        try (Harness h = new Harness(1, 2000, "", 16, 8192, 1000, 0, PRIOR + TARGET)) {
            h.beforeResponse = () -> {
                h.replace(CHANGED + TARGET);
                h.engine.tick(3_500_000, false);
            };
            h.start(3_500_000);
            h.tick(3_500_000, false);
            assertEquals(2, h.inputs.size());
            assertTrue(h.lastInputFor("target").getString("context").contains("approved"));
            assertFalse(h.lastInputFor("target").getString("context").contains("did not approve"));
            assertEquals("ok:target", h.engine.prepareFrame().items.get(0).translation);
        }
    }

    private static TranslationBatch batch(Harness h, List<Integer> indices, String global, int budget, Map<Integer, String> contexts) {
        return new TranslationBatch(h.session.getSnapshot().groups, indices, null, "zh", global,
                new TranslationBatch.Limits(12, 10000, budget, 10000), contexts);
    }

    private static SemanticGroupPlanner.Plan originalPlan(TranslationBatch batch, Harness ignored) {
        return ReflectionHelpers.getField(batch, "mPlan");
    }

    private static JSONObject input(byte[] bytes) throws Exception {
        JSONObject body = BoundedJson.object(new String(bytes, UTF8));
        return BoundedJson.object(body.getJSONArray("messages").getJSONObject(1).getString("content"));
    }

    private static int sentContextPoints(JSONObject input) throws Exception {
        int sum = cp(input.getString("context"));
        JSONArray items = input.getJSONArray("items");
        for (int i = 0; i < items.length(); i++) sum += cp(items.getJSONObject(i).getString("context"));
        return sum;
    }

    private static JSONArray translations(JSONObject input) throws Exception {
        JSONArray result = new JSONArray();
        JSONArray items = input.getJSONArray("items");
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            result.put(new JSONObject().put("id", item.getString("id")).put("text", "ok:" + item.getString("source")));
        }
        return result;
    }

    private static Response response(okhttp3.Request request, String finish, JSONArray items) throws Exception {
        String content = new JSONObject().put("translations", items).toString();
        String body = new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("finish_reason", finish)
                .put("message", new JSONObject().put("content", content)))).toString();
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("synthetic")
                .body(ResponseBody.create(MediaType.parse("application/json"), body)).build();
    }

    private static ChatCompletionAdapter.Config wire() {
        return new ChatCompletionAdapter.Config("https://context-fixture.invalid/chat/completions", "fixture", 256, 65536, 65536,
                ChatCompletionAdapter.TokenField.MAX_TOKENS, ChatCompletionAdapter.Thinking.OMIT);
    }

    private static String repeat(char value, int count) {
        char[] text = new char[count];
        Arrays.fill(text, value);
        return new String(text);
    }
    private static int cp(String text) { return text.codePointCount(0, text.length()); }
    private static String cue(int second, String text) {
        return String.format(java.util.Locale.US, "00:%02d.000 --> 00:%02d.000\n%s\n\n", second, second + 1, text);
    }
    private static void invalid(Runnable action) {
        try { action.run(); fail("Expected context-budget/Unicode rejection"); }
        catch (IllegalArgumentException expected) { assertEquals("Invalid caption translation data", expected.getMessage()); }
    }

    private static final class Queue implements Executor {
        final List<Runnable> tasks = new ArrayList<>();
        public void execute(Runnable task) { tasks.add(task); }
        void drain() {
            int count = 0;
            while (!tasks.isEmpty()) { assertTrue(++count <= 64); tasks.remove(0).run(); }
        }
    }

    private static final class Harness implements AutoCloseable {
        final CaptionSession session = new CaptionSession();
        final CaptionSession.Track track = session.selectTrack(0, null);
        final Queue queue = new Queue();
        final List<JSONObject> inputs = new ArrayList<>();
        final TranslationCoordinator engine;
        final TranslationCoordinator.Profile profile;
        long now;
        Runnable beforeResponse;

        Harness(int items, int contextBudget, String global, int entries, int characters, long ttl, long lookAhead, String cues) {
            replace(cues);
            ChatTranslationClient client = new ChatTranslationClient(new OkHttpClient.Builder()
                    .dns(host -> { throw new AssertionError("No network"); })
                    .addInterceptor(chain -> {
                        try {
                            Buffer buffer = new Buffer();
                            chain.request().body().writeTo(buffer);
                            JSONObject data = input(buffer.readByteArray());
                            inputs.add(data);
                            Runnable action = beforeResponse;
                            beforeResponse = null;
                            if (action != null) action.run();
                            return response(chain.request(), "stop", translations(data));
                        } catch (Exception failure) { throw new AssertionError(failure); }
                    }).build());
            TranslationCoordinator.ScopedCredentials owner = new TranslationCoordinator.ScopedCredentials() {
                final TranslationCoordinator.CredentialScope scope = new TranslationCoordinator.CredentialScope();
                public TranslationCoordinator.CredentialScope getCaptionCacheScope() { return scope; }
                public String getKey() { return "synthetic-context-key"; }
            };
            profile = new TranslationCoordinator.Profile(wire(), owner, 0, null, "zh", global,
                    new TranslationBatch.Limits(items, 10000, contextBudget, 10000), 1);
            engine = new TranslationCoordinator(session, () -> now, queue, (batch, configuration) -> {
                assertFalse(Thread.holdsLock(session));
                ChatTranslationClient.Job job = client.newJob(batch, configuration.wire, configuration.credentials, configuration.maxCalls);
                return new TranslationCoordinator.Work() {
                    public ChatTranslationClient.Result execute() { return job.execute(); }
                    public void cancel() { job.cancel(); }
                };
            }, new TranslationCoordinator.Limits(lookAhead, entries, characters, ttl, 2, 10, 128));
        }

        void replace(String cues) {
            RawTimeline raw = RawWebvttParser.parse(("WEBVTT\n\n" + cues).getBytes(UTF8), 0,
                    new RawTimeline.Limits(65536, 128, 512));
            MappedTranscript mapped = MappedTranscript.fromRaw(raw, 512);
            SemanticGroupPlanner.Plan plan = SemanticGroupPlanner.preserve(mapped, new SemanticGroupPlanner.Limits(128, 512, 512));
            assertTrue(session.accept(session.beginSample(track), raw, mapped, plan));
        }

        void enableWithoutDraining(long position) {
            engine.configure(profile);
            engine.setVisible(true);
            engine.tick(position, false);
            engine.setRequesting(true);
        }
        void start(long position) { enableWithoutDraining(position); queue.drain(); }
        void tick(long position, boolean seek) { engine.tick(position, seek); queue.drain(); }
        JSONObject lastInputFor(String source) throws Exception {
            for (int i = inputs.size() - 1; i >= 0; i--) {
                JSONArray items = inputs.get(i).getJSONArray("items");
                for (int j = 0; j < items.length(); j++) {
                    if (source.equals(items.getJSONObject(j).getString("source"))) return items.getJSONObject(j);
                }
            }
            throw new AssertionError("No encoded source: " + source);
        }
        public void close() { engine.close(); session.close(); }
    }
}
