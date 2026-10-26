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

import java.net.Proxy;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

import static org.junit.Assert.*;

/** Actual-wire progress/cost contracts; no reflection, cache-record assumptions, or model calls. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 27)
public class CaptionContextAmbiguousSlotTest {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final long POSITION_US = 3_500_000;
    private static final String SAME_BASE = "WEBVTT\n\n" +
            "00:01.000 --> 00:02.000\nA\n\n" +
            "00:03.000 --> 00:04.000\ntarget\n\n" +
            "00:03.000 --> 00:04.000\ntarget\n\n" +
            "00:05.000 --> 00:06.000\nB\n\n";
    private static final String DIFFERENT_TEXT = "WEBVTT\n\n" +
            "00:03.000 --> 00:04.000\nfirst\n\n" +
            "00:03.000 --> 00:04.000\nsecond\n\n";

    @Test
    public void simultaneousSameBaseOccurrencesKeepBothTranslationsWithoutRepeatedPayment() throws Exception {
        try (Harness h = new Harness(SAME_BASE, 2, 2000)) {
            assertEquals(4, h.session.getSnapshot().timeline.getCues().size());
            TranslationCoordinator.Frame beforeAdmission = h.begin();
            h.tickRepeatedly(8);

            // Count occurrences, not distinct source strings: both real "target" cues must survive.
            h.assertEachActiveOccurrenceSentOnce();
            TranslationCoordinator.Frame paid = h.assertActiveTranslations(Arrays.asList("target", "target"));
            h.assertRejected(beforeAdmission);
            int paidCalls = h.packets.size();

            h.tickRepeatedly(8);
            assertEquals("Completed overlapping occurrences must not keep consuming calls", paidCalls, h.packets.size());
            h.resample();
            h.tickRepeatedly(8);
            assertEquals("An equivalent same-source sample must not restart payment", paidCalls, h.packets.size());
            h.assertEachActiveOccurrenceSentOnce();
            h.assertActiveTranslations(Arrays.asList("target", "target"));
            h.assertRejected(paid);
            assertEquals(0, h.dnsCalls);
        }
    }

    @Test
    public void simultaneousDifferentTextWithOnePreparationSlotAndZeroContextDoesNotStarveSecondItem() throws Exception {
        try (Harness h = new Harness(DIFFERENT_TEXT, 1, 0)) {
            assertEquals(2, h.session.getSnapshot().timeline.getCues().size());
            TranslationCoordinator.Frame beforeAdmission = h.begin();
            h.tickRepeatedly(8);

            h.assertEachActiveOccurrenceSentOnce();
            TranslationCoordinator.Frame paid = h.assertActiveTranslations(Arrays.asList("first", "second"));
            h.assertRejected(beforeAdmission);
            int paidCalls = h.packets.size();

            h.tickRepeatedly(8);
            assertEquals("A completed first item must not block progress or cause more calls", paidCalls, h.packets.size());
            h.resample();
            h.tickRepeatedly(8);
            assertEquals("Resampling cannot restart the same successful zero-context work", paidCalls, h.packets.size());
            h.assertEachActiveOccurrenceSentOnce();
            h.assertActiveTranslations(Arrays.asList("first", "second"));
            h.assertRejected(paid);
            assertEquals(0, h.dnsCalls);
        }
    }

    private static final class Receipt {
        final int groupIndex;
        final String translation;
        Receipt(int groupIndex, String translation) {
            this.groupIndex = groupIndex;
            this.translation = translation;
        }
    }

    private static final class Queue implements Executor {
        final List<Runnable> tasks = new ArrayList<>();
        @Override public void execute(Runnable task) { tasks.add(task); }
        void drain() {
            int count = 0;
            while (!tasks.isEmpty()) {
                assertTrue("Unexpected unbounded fixture work", ++count <= 64);
                tasks.remove(0).run();
            }
        }
    }

    private static final class Harness implements AutoCloseable {
        final CaptionSession session = new CaptionSession();
        final CaptionSession.Track track = session.selectTrack(0, null);
        final Queue queue = new Queue();
        final List<List<Receipt>> packets = new ArrayList<>();
        final Map<Integer, String> replies = new HashMap<>();
        final String document;
        final TranslationCoordinator engine;
        final TranslationCoordinator.Profile profile;
        TranslationBatch executing;
        int dnsCalls;

        Harness(String document, int maxItems, int contextBudget) {
            this.document = document;
            resample();
            ChatTranslationClient client = new ChatTranslationClient(new OkHttpClient.Builder()
                    .proxy(Proxy.NO_PROXY)
                    .dns(host -> { dnsCalls++; throw new AssertionError("No DNS/network in this fixture"); })
                    .addInterceptor(chain -> {
                        try {
                            assertNotNull(executing);
                            Map<String, Integer> submitted = new HashMap<>();
                            for (TranslationBatch.Item item : executing.getItems()) submitted.put(item.id, item.groupIndex);
                            Buffer bytes = new Buffer();
                            chain.request().body().writeTo(bytes);
                            JSONObject body = BoundedJson.object(bytes.readUtf8());
                            JSONObject input = BoundedJson.object(body.getJSONArray("messages").getJSONObject(1).getString("content"));
                            JSONArray items = input.getJSONArray("items");
                            assertTrue(items.length() > 0 && items.length() <= maxItems);
                            assertEquals(submitted.size(), items.length());
                            String global = input.getString("context");
                            long contextPoints = global.codePointCount(0, global.length());
                            Set<String> seen = new HashSet<>();
                            List<Receipt> packet = new ArrayList<>();
                            JSONArray output = new JSONArray();
                            for (int i = 0; i < items.length(); i++) {
                                JSONObject item = items.getJSONObject(i);
                                String id = item.getString("id");
                                assertTrue("Only actual submitted IDs may be answered", submitted.containsKey(id));
                                assertTrue("Independent occurrences must not share a wire ID", seen.add(id));
                                String local = item.getString("context");
                                contextPoints += local.codePointCount(0, local.length());
                                // The fake service has only this actual item's wire data, not the document
                                // or peers. The suffix makes a wrong-context completed value observable.
                                String translation = "translated " + item.getString("source") + " [" +
                                        Integer.toHexString((global + "\n" + local).hashCode()) + "]";
                                int group = submitted.get(id);
                                packet.add(new Receipt(group, translation));
                                replies.put(group, translation);
                                output.put(new JSONObject().put("id", id).put("text", translation));
                            }
                            assertTrue("Global plus every local context must fit the real request budget", contextPoints <= contextBudget);
                            packets.add(packet);
                            String content = new JSONObject().put("translations", output).toString();
                            String envelope = new JSONObject().put("choices", new JSONArray().put(new JSONObject()
                                    .put("finish_reason", "stop").put("message", new JSONObject().put("content", content)))).toString();
                            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                                    .code(200).message("offline-ambiguous-slot")
                                    .body(ResponseBody.create(MediaType.parse("application/json"), envelope)).build();
                        } catch (Exception error) {
                            throw new AssertionError("Invalid actual body or synthetic response", error);
                        }
                    }).build());
            TranslationCoordinator.ScopedCredentials owner = new TranslationCoordinator.ScopedCredentials() {
                final TranslationCoordinator.CredentialScope scope = new TranslationCoordinator.CredentialScope();
                @Override public TranslationCoordinator.CredentialScope getCaptionCacheScope() { return scope; }
                @Override public String getKey() { return "synthetic-ambiguous-slot-credential"; }
            };
            ChatCompletionAdapter.Config wire = new ChatCompletionAdapter.Config(
                    "https://ambiguous-slot.invalid/chat/completions", "synthetic-slot-model", 512, 65536, 65536,
                    ChatCompletionAdapter.TokenField.MAX_TOKENS, ChatCompletionAdapter.Thinking.OMIT);
            profile = new TranslationCoordinator.Profile(wire, owner, 0, "en", "zh", "",
                    new TranslationBatch.Limits(maxItems, 10000, contextBudget, 10000), 1);
            engine = new TranslationCoordinator(session, () -> 0L, queue, (batch, configuration) -> {
                ChatTranslationClient.Job job = client.newJob(batch, configuration.wire, configuration.credentials, configuration.maxCalls);
                return new TranslationCoordinator.Work() {
                    @Override public ChatTranslationClient.Result execute() {
                        assertNull(executing);
                        executing = batch;
                        try {
                            ChatTranslationClient.Result result = job.execute();
                            assertEquals(ChatTranslationClient.Status.COMPLETE, result.status);
                            return result;
                        } finally {
                            executing = null;
                        }
                    }
                    @Override public void cancel() { job.cancel(); }
                };
            }, new TranslationCoordinator.Limits(0, 16, 8192, 60000, 2, 10, 64));
        }

        void resample() {
            RawTimeline raw = RawWebvttParser.parse(document.getBytes(UTF8), 0, new RawTimeline.Limits(65536, 128, 512));
            MappedTranscript mapped = MappedTranscript.fromRaw(raw, 512);
            SemanticGroupPlanner.Plan plan = SemanticGroupPlanner.preserve(mapped, new SemanticGroupPlanner.Limits(128, 512, 512));
            assertTrue(session.accept(session.beginSample(track), raw, mapped, plan));
            assertEquals("Keep both independent active source occurrences", 2, plan.getGroupsAt(POSITION_US).size());
        }

        TranslationCoordinator.Frame begin() {
            engine.tick(POSITION_US, false);
            engine.configure(profile);
            engine.setVisible(true);
            engine.setRequesting(true);
            TranslationCoordinator.Frame frame = engine.prepareFrame();
            assertNotNull(frame);
            assertEquals(2, frame.items.size());
            for (TranslationCoordinator.Item item : frame.items) assertNull(item.translation);
            return frame;
        }

        void tickRepeatedly(int count) {
            queue.drain();
            for (int i = 0; i < count; i++) {
                engine.tick(POSITION_US, false);
                queue.drain();
            }
        }

        void assertEachActiveOccurrenceSentOnce() {
            Map<Integer, Integer> counts = new HashMap<>();
            for (List<Receipt> packet : packets) {
                for (Receipt receipt : packet) {
                    Integer old = counts.get(receipt.groupIndex);
                    counts.put(receipt.groupIndex, old == null ? 1 : old + 1);
                }
            }
            Set<Integer> active = new HashSet<>(session.getSnapshot().groups.getGroupsAt(POSITION_US));
            assertEquals("Every independent active occurrence must make progress to a real request", active, counts.keySet());
            for (Integer group : active) {
                assertEquals("Successful unchanged occurrences must not be charged repeatedly", Integer.valueOf(1), counts.get(group));
            }
        }

        TranslationCoordinator.Frame assertActiveTranslations(List<String> originals) {
            TranslationCoordinator.Frame frame = engine.prepareFrame();
            assertNotNull(frame);
            assertEquals(originals.size(), frame.items.size());
            assertTrue(engine.deliver(frame, applied -> {
                for (int i = 0; i < originals.size(); i++) {
                    TranslationCoordinator.Item item = applied.items.get(i);
                    assertEquals(originals.get(i), item.original);
                    assertNotNull("Each independent active occurrence needs a legal translation", item.translation);
                    assertEquals("Delivered text must match this occurrence's actual wire response", replies.get(item.groupIndex), item.translation);
                }
            }));
            return frame;
        }

        void assertRejected(TranslationCoordinator.Frame old) {
            assertFalse("Newly projected keys must never revive an old Frame", engine.deliver(old, ignored -> fail("Old Frame reached the sink")));
        }

        @Override public void close() { engine.close(); session.close(); }
    }
}
