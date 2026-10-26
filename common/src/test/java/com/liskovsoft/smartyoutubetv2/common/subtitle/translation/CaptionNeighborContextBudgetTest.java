package com.liskovsoft.smartyoutubetv2.common.subtitle.translation;

import com.google.android.exoplayer2.text.Cue;
import com.liskovsoft.smartyoutubetv2.common.subtitle.presentation.NativeCueComposer;
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

import java.net.Proxy;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

import static org.junit.Assert.*;

/**
 * Black-box wire contracts using the frozen Coordinator/Batch API, not a neighbor helper or a new API.
 * Every receipt comes from actual Chat encoding intercepted before DNS. The service's answer can use
 * only global context and the CURRENT item's source/context, never another item's source/context.
 * These are synthetic local contracts, not actual Provider quality or device/production-entry tests.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 27)
public class CaptionNeighborContextBudgetTest {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String ANTECEDENT = "Mara did not sign the permit.";
    private static final String TARGET = "She refused it.";
    private static final String PERMIT = "MARA_REFUSED_PERMIT_SENTINEL";
    private static final String BROKEN = "BROKEN_CONTEXT";
    private static final String EXPLICIT = "Neutral glossary only.";
    private static final int BUDGET = 2000;

    @Test
    public void sameItemKeepsItsOwnEvidenceWithOneTwoAndTwelveActualTargets() throws Exception {
        Owner owner = new Owner();
        try (Input input = new Input(false)) {
            Receipt baseline = null;
            for (int items : new int[]{1, 2, 12}) {
                try (Harness h = new Harness(input, owner, EXPLICIT, BUDGET, items, false)) {
                    h.completeAll();
                    Receipt current = h.firstTarget("coordinator");
                    assertOwnEvidence(current);
                    if (baseline != null) assertSameContext(baseline, current);
                    baseline = current;
                    assertEquals("Short neighbors must allow actual multi-item packing, not singleton fallback",
                            items, h.largestCoordinatorBatch());
                    h.assertCurrentSink(PERMIT);
                }
            }
        }
    }

    @Test
    public void reorderedPeersRemovedPeersAndActualMissingItemRecoveryKeepFrozenContext() throws Exception {
        Owner owner = new Owner();
        try (Input input = new Input(false);
             Harness h = new Harness(input, owner, EXPLICIT, BUDGET, 12, true)) {
            h.completeAll();
            List<Packet> root = h.packets("coordinator");
            assertEquals("The real Job must execute its original batch and missing-only recovery", 2, root.size());
            assertEquals(12, root.get(0).receipts.size());
            assertEquals(Collections.singletonList(TARGET), root.get(1).sources());
            assertTrue("The target must actually have been omitted from the first response", h.omissionUsed);

            Packet reversed = h.onlyPacket("reordered");
            List<String> expectedReverse = new ArrayList<>(root.get(0).sources());
            Collections.reverse(expectedReverse);
            assertEquals(expectedReverse, reversed.sources());
            assertFalse("The peer-order probe must really change order", root.get(0).sources().equals(reversed.sources()));
            Packet alone = h.onlyPacket("without-peers");
            assertEquals(Collections.singletonList(TARGET), alone.sources());

            Receipt original = root.get(0).target();
            for (Receipt receipt : Arrays.asList(root.get(1).target(), reversed.target(), alone.target())) {
                assertSameContext(original, receipt);
                assertOwnEvidence(receipt);
            }
            assertOwnEvidence(original);
            h.assertCurrentSink(PERMIT);
        }
    }

    @Test
    public void longNeighborPackingKeepsSingletonContextsAndUsesFewerTargetsNotShorterContext() throws Exception {
        Owner owner = new Owner();
        try (Input input = new Input(true)) {
            Map<String, Receipt> singleton;
            try (Harness h = new Harness(input, owner, EXPLICIT, BUDGET, 1, false)) {
                h.completeAll();
                singleton = h.firstReceiptsBySource();
                assertEquals(12, singleton.size());
                assertOwnEvidence(singleton.get(TARGET));
            }
            long allContext = cp(EXPLICIT);
            long allSource = 0;
            for (Receipt receipt : singleton.values()) {
                allContext += cp(receipt.localContext);
                allSource += cp(receipt.source);
            }
            assertTrue("Source budget must not be the reason twelve targets cannot fit", allSource <= 6000);
            // No assertion fixes the producer's quote limit, labels, radius or a 512-CP policy.
            // A fixture that does not actually create pressure is NOT budget-reduction evidence.
            assertTrue("UNEXERCISED_CONTEXT_PRESSURE: observed singleton contexts fit in one batch; " +
                    "this fixture cannot establish long-neighbor pressure reduction", allContext > BUDGET);

            try (Harness h = new Harness(input, owner, EXPLICIT, BUDGET, 12, false)) {
                h.completeAll();
                Map<String, Receipt> packed = h.firstReceiptsBySource();
                assertEquals(singleton.keySet(), packed.keySet());
                for (String source : singleton.keySet()) assertSameContext(singleton.get(source), packed.get(source));
                assertTrue("Long contexts require fewer than twelve targets, not an oversized request",
                        h.largestCoordinatorBatch() < 12);
                boolean pairFits = false;
                List<Receipt> baselineItems = new ArrayList<>(singleton.values());
                for (int i = 0; i < baselineItems.size(); i++) {
                    for (int j = i + 1; j < baselineItems.size(); j++) {
                        Receipt a = baselineItems.get(i), b = baselineItems.get(j);
                        if ((long) cp(EXPLICIT) + cp(a.localContext) + cp(b.localContext) <= BUDGET &&
                                (long) cp(a.source) + cp(b.source) <= 6000) pairFits = true;
                    }
                }
                if (pairFits) {
                    assertTrue("A demonstrably fitting pair must not be needlessly degraded to singleton requests",
                            h.largestCoordinatorBatch() > 1);
                }
                for (Packet packet : h.packets("coordinator")) {
                    assertTrue("Fixture source data must leave room for a pair", packet.sourceCodePoints <= 6000);
                }
                h.assertCurrentSink(PERMIT);
            }
        }
    }

    @Test
    public void zeroContextBudgetStillRequestsAndDeliversTheCurrentOriginal() throws Exception {
        Owner owner = new Owner();
        try (Input input = new Input(false);
             Harness h = new Harness(input, owner, "", 0, 12, false)) {
            h.completeAll();
            assertTrue(h.httpCalls > 0);
            Receipt target = h.firstTarget("coordinator");
            assertEquals("", target.globalContext);
            assertEquals("", target.localContext);
            // Missing context is allowed here. The contract is continued translation, not a fake
            // correct antecedent borrowed from the fixture document or another item.
            assertEquals(BROKEN, target.translation);
            h.assertCurrentSink(BROKEN);
        }
    }

    @Test
    public void explicitContextFillingTheWholeBudgetStillTranslatesWithoutExtraLocalAllowance() throws Exception {
        Owner owner = new Owner();
        String explicit = ANTECEDENT;
        int budget = cp(explicit);
        try (Input input = new Input(false);
             Harness h = new Harness(input, owner, explicit, budget, 12, false)) {
            h.completeAll();
            assertTrue(h.httpCalls > 0);
            Receipt target = h.firstTarget("coordinator");
            assertEquals(explicit, target.globalContext);
            assertEquals("No per-item allowance remains after explicit global context", "", target.localContext);
            assertOwnEvidence(target);
            h.assertCurrentSink(PERMIT);
        }
    }

    @Test
    public void accountingOracleItselfRejectsTwelveTimesFourHundredWithoutAnyProductRequest() throws Exception {
        // Supplementary characters ensure the ledger uses code points, not UTF-16 char length.
        JSONObject exact = data("", Collections.singletonList("🙂🙂"));
        assertEquals(2, assertContextBudget(exact, 2));
        String fourHundred = repeat("🙂", 400);
        assertEquals(400, cp(fourHundred));
        JSONObject bad = data(EXPLICIT, Collections.nCopies(12, fourHundred));
        try {
            assertContextBudget(bad, BUDGET);
        } catch (ContextBudgetExceeded caught) {
            assertEquals((long) cp(EXPLICIT) + 12L * 400, caught.measured);
            assertEquals(BUDGET, caught.budget);
            return;
        }
        fail("The wire ledger accepted 12 x 400 context CP against a 2000-CP batch budget");
    }

    private static int cp(String text) { return text.codePointCount(0, text.length()); }

    private static String repeat(String text, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) result.append(text);
        return result.toString();
    }

    private static String stringOrEmpty(JSONObject object, String field) throws Exception {
        Object value = object.has(field) ? object.get(field) : "";
        assertTrue(field + " must be a string when present", value instanceof String);
        return (String) value;
    }

    private static long assertContextBudget(JSONObject input, int budget) throws Exception {
        long total = cp(stringOrEmpty(input, "context"));
        JSONArray items = input.getJSONArray("items");
        for (int i = 0; i < items.length(); i++) total += cp(stringOrEmpty(items.getJSONObject(i), "context"));
        if (total > budget) throw new ContextBudgetExceeded(total, budget);
        return total;
    }

    private static final class ContextBudgetExceeded extends AssertionError {
        final long measured;
        final int budget;
        ContextBudgetExceeded(long measured, int budget) {
            super("CONTEXT_BUDGET_EXCEEDED: global + sum(item.context) = " + measured + ", limit = " + budget);
            this.measured = measured;
            this.budget = budget;
        }
    }

    private static JSONObject data(String global, List<String> contexts) throws Exception {
        JSONArray items = new JSONArray();
        for (int i = 0; i < contexts.size(); i++) {
            items.put(new JSONObject().put("id", "synthetic" + i).put("source", "fixture text")
                    .put("context", contexts.get(i)));
        }
        return new JSONObject().put("context", global).put("items", items);
    }

    private static String answer(String source, String effectiveContext) {
        if (!TARGET.equals(source)) return "OBSERVED:" + source;
        String evidence = (source + "\n" + effectiveContext).toLowerCase(Locale.US).replaceAll("\\s+", " ");
        return evidence.contains("mara did not sign the permit") ? PERMIT : BROKEN;
    }

    private static void assertOwnEvidence(Receipt receipt) {
        assertNotNull("Target must actually reach the encoded request", receipt);
        assertEquals(PERMIT, answer(receipt.source, receipt.globalContext + "\n" + receipt.localContext));
        assertEquals(PERMIT, receipt.translation);
    }

    private static void assertSameContext(Receipt expected, Receipt actual) {
        assertNotNull(actual);
        assertEquals(expected.source, actual.source);
        assertEquals("Global evidence must not depend on the selected peers", expected.globalContext, actual.globalContext);
        assertEquals("The item's own frozen context must survive batch size/order/subset changes",
                expected.localContext, actual.localContext);
    }

    private static final class Receipt {
        final String source;
        final String globalContext;
        final String localContext;
        final String translation;
        Receipt(String source, String globalContext, String localContext) {
            this.source = source;
            this.globalContext = globalContext;
            this.localContext = localContext;
            translation = answer(source, globalContext + "\n" + localContext);
        }
    }

    private static final class Packet {
        final String phase;
        final List<Receipt> receipts = new ArrayList<>();
        int sourceCodePoints;
        Packet(String phase) { this.phase = phase; }
        List<String> sources() {
            List<String> sources = new ArrayList<>();
            for (Receipt receipt : receipts) sources.add(receipt.source);
            return sources;
        }
        Receipt target() {
            for (Receipt receipt : receipts) if (TARGET.equals(receipt.source)) return receipt;
            throw new AssertionError("Target absent from actual wire packet");
        }
    }

    private static final class Owner implements TranslationCoordinator.ScopedCredentials {
        final TranslationCoordinator.CredentialScope scope = new TranslationCoordinator.CredentialScope();
        @Override public TranslationCoordinator.CredentialScope getCaptionCacheScope() { return scope; }
        @Override public String getKey() { return "synthetic-neighbor-budget-credential"; }
    }

    private static final class Queue implements Executor {
        final List<Runnable> tasks = new ArrayList<>();
        @Override public void execute(Runnable task) { tasks.add(task); }
        void drain() {
            int count = 0;
            while (!tasks.isEmpty()) {
                assertTrue("Unbounded fixture work", ++count <= 200);
                tasks.remove(0).run();
            }
        }
    }

    /** One immutable accepted document/track/clock reused across batch-size comparisons. */
    private static final class Input implements AutoCloseable {
        final CaptionSession session = new CaptionSession();
        final CaptionSession.Snapshot snapshot;
        final List<String> targets = new ArrayList<>();
        Input(boolean longNeighbors) {
            targets.add(TARGET);
            for (int i = 0; i < 11; i++) {
                targets.add(longNeighbors ? "Account " + i + " did not authorize the transfer. " +
                        repeat("The written record remains available. ", 10) + "End of account " + i + "." : "Note " + i + ".");
            }
            StringBuilder document = new StringBuilder("WEBVTT\n\n00:01.000 --> 00:02.000\n")
                    .append(ANTECEDENT).append("\n\n");
            for (int i = 0; i < targets.size(); i++) {
                int start = 3 + i * 2;
                document.append(String.format(Locale.US, "00:%02d.000 --> 00:%02d.000\n", start, start + 1))
                        .append(targets.get(i)).append("\n\n");
            }
            RawTimeline raw = RawWebvttParser.parse(document.toString().getBytes(UTF8), 0,
                    new RawTimeline.Limits(65536, 64, 128));
            MappedTranscript mapped = MappedTranscript.fromRaw(raw, 128);
            SemanticGroupPlanner.Plan plan = SemanticGroupPlanner.preserve(mapped, new SemanticGroupPlanner.Limits(64, 128, 128));
            CaptionSession.Track track = session.selectTrack(0, null, new CaptionSession.CacheScope());
            assertTrue(session.accept(session.beginSample(track), raw, mapped, plan,
                    new CaptionSession.InputClock("text/vtt", 0, 0), null));
            snapshot = session.getSnapshot();
        }
        @Override public void close() { session.close(); }
    }

    private static final class Harness implements AutoCloseable {
        final Input input;
        final Owner owner;
        final String explicit;
        final int budget;
        final int maxItems;
        final boolean probeAndRecover;
        final Queue queue = new Queue();
        final List<Packet> packets = new ArrayList<>();
        final TranslationCoordinator engine;
        final TranslationCoordinator.Profile profile;
        final ChatTranslationClient client;
        int httpCalls;
        int dnsCalls;
        boolean omissionUsed;
        boolean probesDone;
        String phase = "coordinator";

        Harness(Input input, Owner owner, String explicit, int budget, int maxItems, boolean probeAndRecover) {
            this.input = input;
            this.owner = owner;
            this.explicit = explicit;
            this.budget = budget;
            this.maxItems = maxItems;
            this.probeAndRecover = probeAndRecover;
            OkHttpClient transport = new OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
                    .dns(host -> { dnsCalls++; throw new AssertionError("No DNS/network in this fixture"); })
                    .addInterceptor(chain -> {
                        httpCalls++;
                        try {
                            assertNotNull(chain.request().body());
                            Buffer bytes = new Buffer();
                            chain.request().body().writeTo(bytes);
                            JSONObject body = BoundedJson.object(bytes.readUtf8());
                            JSONObject data = userInput(body);
                            assertContextBudget(data, budget); // Every actual original, probe and recovery request.
                            String global = stringOrEmpty(data, "context");
                            assertEquals("Explicit context must retain its configured meaning", explicit, global);
                            Packet packet = new Packet(phase);
                            JSONArray items = data.getJSONArray("items");
                            assertTrue("A real nonempty batch is required", items.length() > 0);
                            assertTrue(items.length() <= maxItems);
                            JSONArray output = new JSONArray();
                            for (int i = 0; i < items.length(); i++) {
                                JSONObject item = items.getJSONObject(i);
                                Receipt receipt = new Receipt(item.getString("source"), global, stringOrEmpty(item, "context"));
                                packet.receipts.add(receipt);
                                packet.sourceCodePoints += cp(receipt.source);
                                if (probeAndRecover && "coordinator".equals(phase) && !omissionUsed && TARGET.equals(receipt.source)) {
                                    omissionUsed = true; // Only one missing item; real Job.subset performs recovery.
                                } else {
                                    output.put(new JSONObject().put("id", item.getString("id")).put("text", receipt.translation));
                                }
                            }
                            packets.add(packet);
                            String content = new JSONObject().put("translations", output).toString();
                            String envelope = new JSONObject().put("choices", new JSONArray().put(new JSONObject()
                                    .put("finish_reason", "stop").put("message", new JSONObject().put("content", content)))).toString();
                            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                                    .code(200).message("offline-neighbor-budget")
                                    .body(ResponseBody.create(MediaType.parse("application/json"), envelope)).build();
                        } catch (Exception error) {
                            throw new AssertionError("Invalid actual Chat body or synthetic response", error);
                        }
                    }).build();
            client = new ChatTranslationClient(transport);
            ChatCompletionAdapter.Config wire = new ChatCompletionAdapter.Config(
                    "https://neighbor-budget.invalid/chat/completions", "synthetic-budget-model", 512,
                    262144, 262144, ChatCompletionAdapter.TokenField.MAX_TOKENS, ChatCompletionAdapter.Thinking.OMIT);
            profile = new TranslationCoordinator.Profile(wire, owner, 7, "en", "zh", explicit,
                    new TranslationBatch.Limits(maxItems, 6000, budget, 24000), 2);
            assertSame(owner.scope, profile.cacheScope);
            engine = new TranslationCoordinator(input.session, () -> 0L, queue, (batch, activeProfile) -> {
                assertSame(profile, activeProfile);
                assertTrue(batch.isFor(input.snapshot.groups));
                ChatTranslationClient.Job job = client.newJob(batch, activeProfile.wire, activeProfile.credentials, activeProfile.maxCalls);
                return new TranslationCoordinator.Work() {
                    @Override public ChatTranslationClient.Result execute() {
                        ChatTranslationClient.Result result = job.execute();
                        assertEquals(ChatTranslationClient.Status.COMPLETE, result.status);
                        if (probeAndRecover && !probesDone) probeFrozenSubsets(batch, activeProfile);
                        return result;
                    }
                    @Override public void cancel() { job.cancel(); }
                };
            }, new TranslationCoordinator.Limits(60_000_000, 64, 65536, 60000, 1, 10, 128));
            engine.tick(3_500_000, false);
            engine.configure(profile);
            engine.setVisible(true);
            engine.setRequesting(true);
        }

        void probeFrozenSubsets(TranslationBatch batch, TranslationCoordinator.Profile activeProfile) {
            probesDone = true;
            List<TranslationBatch.Item> reversed = new ArrayList<>(batch.getItems());
            Collections.reverse(reversed);
            List<TranslationBatch.Item> targetOnly = new ArrayList<>();
            for (TranslationBatch.Item item : batch.getItems()) if (TARGET.equals(item.source)) targetOnly.add(item);
            assertEquals(1, targetOnly.size());
            try {
                // Use only the existing subset API on the Coordinator's finalized production batch.
                // These probes never manufacture or reach into an item's new context implementation.
                phase = "reordered";
                assertEquals(ChatTranslationClient.Status.COMPLETE,
                        client.newJob(batch.subset(reversed), activeProfile.wire, activeProfile.credentials, 1).execute().status);
                phase = "without-peers";
                assertEquals(ChatTranslationClient.Status.COMPLETE,
                        client.newJob(batch.subset(targetOnly), activeProfile.wire, activeProfile.credentials, 1).execute().status);
            } finally {
                phase = "coordinator";
            }
        }

        void completeAll() {
            for (int i = 0; i < 128; i++) {
                queue.drain();
                assertSame("Do not replace source/Raw/clock during a batch comparison", input.snapshot, input.session.getSnapshot());
                if (firstReceiptsBySource().keySet().containsAll(input.targets)) {
                    assertTrue(httpCalls > 0);
                    assertEquals(0, dnsCalls);
                    return;
                }
                engine.tick(3_500_000, false);
            }
            fail("Not all target sources reached a real encoded request; a silent no-request fallback is not a pass");
        }

        Map<String, Receipt> firstReceiptsBySource() {
            Map<String, Receipt> first = new LinkedHashMap<>();
            for (Packet packet : packets("coordinator")) {
                for (Receipt receipt : packet.receipts) if (!first.containsKey(receipt.source)) first.put(receipt.source, receipt);
            }
            return first;
        }

        int largestCoordinatorBatch() {
            int largest = 0;
            for (Packet packet : packets("coordinator")) largest = Math.max(largest, packet.receipts.size());
            return largest;
        }

        List<Packet> packets(String requestedPhase) {
            List<Packet> found = new ArrayList<>();
            for (Packet packet : packets) if (requestedPhase.equals(packet.phase)) found.add(packet);
            return found;
        }

        Packet onlyPacket(String requestedPhase) {
            List<Packet> found = packets(requestedPhase);
            assertEquals(1, found.size());
            return found.get(0);
        }

        Receipt firstTarget(String requestedPhase) {
            for (Packet packet : packets(requestedPhase)) {
                for (Receipt receipt : packet.receipts) if (TARGET.equals(receipt.source)) return receipt;
            }
            throw new AssertionError("Target never reached actual Chat encoding");
        }

        void assertCurrentSink(String sentinel) {
            TranslationCoordinator.Frame frame = engine.prepareFrame();
            assertNotNull(frame);
            assertEquals(1, frame.items.size());
            assertEquals(TARGET, frame.items.get(0).original);
            AtomicReference<List<Cue>> sink = new AtomicReference<>();
            assertTrue(engine.deliver(frame, current -> sink.set(NativeCueComposer.compose(
                    Collections.singletonList(new Cue(TARGET)), TARGET, current.items.get(0).translation,
                    NativeCueComposer.Mode.BILINGUAL, NativeCueComposer.Order.ORIGINAL_FIRST, 4096))));
            assertNotNull(sink.get());
            assertEquals(1, sink.get().size());
            assertEquals(TARGET + "\n" + sentinel, sink.get().get(0).text.toString());
        }

        @Override public void close() { engine.close(); }
    }

    private static JSONObject userInput(JSONObject body) throws Exception {
        JSONObject input = null;
        JSONArray messages = body.getJSONArray("messages");
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.getJSONObject(i);
            if ("user".equals(message.getString("role"))) {
                assertNull("One actual caption data message", input);
                input = BoundedJson.object(message.getString("content"));
            }
        }
        assertNotNull(input);
        return input;
    }
}
