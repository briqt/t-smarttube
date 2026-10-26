package com.liskovsoft.smartyoutubetv2.common.subtitle.translation;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.source.dash.manifest.DashManifest;
import com.google.android.exoplayer2.source.dash.manifest.DashManifestParser2;
import com.google.android.exoplayer2.text.Cue;
import com.google.android.exoplayer2.text.SubtitleDecoder;
import com.google.android.exoplayer2.text.SubtitleInputBuffer;
import com.google.android.exoplayer2.text.SubtitleOutputBuffer;
import com.google.android.exoplayer2.text.TextOutput;
import com.google.android.exoplayer2.upstream.DataSource;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.mediaserviceinterfaces.data.MediaSubtitle;
import com.liskovsoft.smartyoutubetv2.common.subtitle.observation.CaptionCueDispatcher;
import com.liskovsoft.smartyoutubetv2.common.subtitle.observation.CaptionObservation;
import com.liskovsoft.smartyoutubetv2.common.subtitle.presentation.CaptionOutputBridge;
import com.liskovsoft.smartyoutubetv2.common.subtitle.presentation.NativeCueComposer;
import com.liskovsoft.smartyoutubetv2.common.subtitle.session.CaptionSession;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.lang.reflect.Proxy;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

import static org.junit.Assert.*;

/**
 * Synthetic production-entry contracts, not model-quality tests. Only the worker/UI scheduling and
 * transport are controlled. Raw/Plan/InputSession, wire encoding/decoding and final Cue composition
 * are production code. The intercepted service sees only the final request body, never a document.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 27)
public class CaptionNeighborContextProductionTest {
    private static final String BROKEN = "BROKEN_CONTEXT";
    private static final String PERMIT = "MARA_REFUSED_PERMIT_SENTINEL";
    private static final String RIVER = "RIVER_BANK_SENTINEL";
    private static final String FINANCIAL = "FINANCIAL_BANK_SENTINEL";
    private static final String MIXED = "MIXED_SOURCE_CONTEXT";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    @Test
    public void previousCueNegationAndPronounMustReachTheActualRequestAndNativeSink() throws Exception {
        // Negative oracle: neither the target phrase nor the test's document can supply its antecedent.
        assertEquals(BROKEN, answer("She refused it.", ""));
        String document = "WEBVTT\n\n00:01.000 --> 00:02.000\nMara did not sign the permit.\n\n" +
                "00:03.000 --> 00:04.000\nShe refused it.\n";
        try (Playback h = new Playback(document)) {
            assertEquals("The production Store's template context starts empty", "", h.template.context);
            h.emitAt(3500);
            h.complete();

            // Current preserve + empty-context production must fail here with BROKEN_CONTEXT,
            // rather than accidentally passing because the fake service remembers document above.
            h.assertNativeSentinel(PERMIT);
            Receipt target = h.lastTarget("She refused it.");
            assertEquals(PERMIT, answer(target.source, target.context));
            assertEquals(0, h.extraSourceOpens);
        }
    }

    @Test
    public void nextCueOutsideSmallPrefetchMustDisambiguateBankInTheActualRequest() throws Exception {
        assertEquals(BROKEN, answer("It was on the bank.", ""));
        String document = "WEBVTT\n\n00:03.000 --> 00:04.000\nIt was on the bank.\n\n" +
                "00:04.250 --> 00:05.250\nThe river flooded the footpath.\n";
        try (Playback h = new Playback(document)) {
            h.emitAt(3500); // Neighbor begins outside this fixture's intentionally small prefetch.
            h.complete();

            h.assertNativeSentinel(RIVER);
            Receipt target = h.lastTarget("It was on the bank.");
            // Information may arrive in source OR effective context; no prescribed future grouping.
            assertEquals(RIVER, answer(target.source, target.context));
            assertEquals(0, h.extraSourceOpens);
        }
    }

    @Test
    public void sameBindingResampleTicksAndSeekReusePaidTextButNeverOldFrames() throws Exception {
        String document = "WEBVTT\n\n00:01.000 --> 00:02.000\nThe river flooded the footpath.\n\n" +
                "00:03.000 --> 00:04.000\nThe river bank was muddy.\n\n" +
                "00:05.000 --> 00:06.000\nWe stayed away.\n";
        try (Playback h = new Playback(document)) {
            h.emitAt(3500);
            h.complete();
            // This target is self-sufficient, so this guard is valid before neighbor context exists.
            h.assertNativeSentinel(RIVER);
            int paid = h.requests;
            assertTrue(paid > 0);
            TranslationCoordinator.Frame oldFrame = h.coordinator.prepareFrame();
            assertNotNull(oldFrame);
            CaptionSession.Snapshot oldRaw = h.observation.getSession().getSnapshot();

            for (int i = 0; i < 12; i++) {
                h.emitAt(3200 + i * 20);
                h.complete();
            }
            assertEquals("Playback ticks must not create new paid context namespaces", paid, h.requests);

            h.capture(document); // Native queue -> a genuinely new Raw/Mapped/Plan, same actual binding.
            CaptionSession.Snapshot replacement = h.observation.getSession().getSnapshot();
            assertNotSame(oldRaw, replacement);
            assertSame(oldRaw.track, replacement.track);
            assertNotSame(oldRaw.timeline, replacement.timeline);
            assertNotSame(oldRaw.groups, replacement.groups);
            h.emitAt(3500);
            h.complete();
            assertFalse(h.coordinator.deliver(oldFrame, ignored -> fail("An old Frame cannot be reused")));
            h.assertNativeSentinel(RIVER);
            assertEquals("Equivalent replacement Plan/context must reuse completed Strings", paid, h.requests);

            TranslationCoordinator.Frame beforeSeek = h.coordinator.prepareFrame();
            assertNotNull(beforeSeek);
            h.bridge.seek();
            h.positionMs = 3300;
            h.bridge.seekProcessed(); // Actual supported no-op-seek seam; no manual Plan reconstruction.
            h.complete();
            assertFalse(h.coordinator.deliver(beforeSeek, ignored -> fail("A seek must invalidate old Frames")));
            h.assertNativeSentinel(RIVER);
            assertEquals("Seeking within the same completed semantic input must not repay", paid, h.requests);
            assertEquals(0, h.extraSourceOpens);
        }
    }

    @Test
    public void anotherActualFormatAndNewSourceCannotSupplyOrRetainForeignNeighbors() throws Exception {
        String finance = "WEBVTT\n\n00:01.000 --> 00:02.000\nThe loan officer approved the credit.\n\n" +
                "00:03.000 --> 00:04.000\nIt was on the bank.\n";
        String river = "WEBVTT\n\n00:01.000 --> 00:02.000\nThe river flooded the footpath.\n\n" +
                "00:03.000 --> 00:04.000\nIt was on the bank.\n";
        try (Playback h = new Playback(finance)) {
            h.emitAt(3500);
            h.complete();
            CaptionSession.CacheScope oldScope = h.observation.getSession().getSnapshot().track.cacheScope;
            int firstPaid = h.requests;
            int cut = h.receipts.size();

            h.selectFormat(1, river); // Real second Format in the SAME manifest binding, not a fake scope.
            assertNotSame(oldScope, h.observation.getSession().getSnapshot().track.cacheScope);
            h.emitAt(3500);
            h.complete();
            assertTrue("Another actual format must not reuse the first format's completed answer", h.requests > firstPaid);
            Receipt current = h.lastTargetSince(cut, "It was on the bank.");
            assertFalse(current.source.toLowerCase(Locale.US).contains("loan officer"));
            assertFalse(current.context.toLowerCase(Locale.US).contains("loan officer"));
            assertNotEquals(FINANCIAL, current.translation);
            assertNotEquals(MIXED, current.translation);
            h.assertNativeSentinel(current.translation);

            // Same target/time/Format values, but a new real source binding without either neighbor.
            // A context service must not fill the gap from its previous document or extra source.
            int secondPaid = h.requests;
            cut = h.receipts.size();
            h.bindSource();
            h.selectFormat(0, "WEBVTT\n\n00:03.000 --> 00:04.000\nIt was on the bank.\n");
            h.emitAt(3500);
            h.complete();
            assertTrue(h.requests > secondPaid);
            Receipt isolated = h.lastTargetSince(cut, "It was on the bank.");
            assertEquals(BROKEN, answer(isolated.source, isolated.context));
            h.assertNativeSentinel(BROKEN);
            assertEquals("Neighbor context must not initiate an unrelated catalog read", 0, h.extraSourceOpens);
        }
    }

    /** No fixture/document argument: decisions can depend only on the item's final wire text/context. */
    private static String answer(String source, String context) {
        String text = (source + "\n" + context).toLowerCase(Locale.US).replaceAll("\\s+", " ");
        if (source.contains("She refused it.")) {
            return text.contains("mara did not sign the permit") ? PERMIT : BROKEN;
        }
        if (source.contains("It was on the bank.") || source.contains("The river bank was muddy.")) {
            boolean river = text.contains("river flooded the footpath") || text.contains("river bank was muddy");
            boolean finance = text.contains("loan officer approved the credit");
            return river && finance ? MIXED : river ? RIVER : finance ? FINANCIAL : BROKEN;
        }
        return "OBSERVED:" + source;
    }

    private static final class Receipt {
        final String source;
        final String context;
        final String translation;
        Receipt(String source, String context, String translation) {
            this.source = source;
            this.context = context;
            this.translation = translation;
        }
    }

    private static final class Worker implements Executor {
        final List<Runnable> tasks = new ArrayList<>();
        public void execute(Runnable task) { tasks.add(task); }
        void drain() {
            int count = 0;
            while (!tasks.isEmpty()) {
                if (++count > 100) throw new AssertionError("Unbounded caption work");
                tasks.remove(0).run();
            }
        }
    }

    private static final class Playback implements AutoCloseable {
        final CaptionObservation observation;
        final TranslationCoordinator coordinator;
        final CaptionOutputBridge bridge;
        final TranslationCoordinator.Profile template;
        final Worker worker = new Worker();
        final List<Receipt> receipts = new ArrayList<>();
        final MediaItemFormatInfo metadata;
        final DashManifest manifest;
        final DataSource.Factory extraSource;
        SubtitleDecoder decoder;
        SubtitleOutputBuffer nativeOutput;
        Runnable pulse;
        long positionMs = 3500;
        int requests;
        int extraSourceOpens;
        List<Cue> last = Collections.emptyList();

        Playback(String document) throws Exception {
            observation = CaptionObservation.create(RuntimeEnvironment.getApplication());
            // Same existing test seam as controlled workers: do not replace Raw/Plan or input choice.
            ReflectionHelpers.setField(observation, "mExecutor", (Executor) Runnable::run);
            metadata = info(Arrays.asList(caption("Track one", "a.en.one"), caption("Track two", "a.en.two")));
            manifest = new DashManifestParser2().parse(metadata);
            extraSource = () -> {
                extraSourceOpens++;
                throw new AssertionError("This direct-caption fixture never needs an extra source GET");
            };
            bindSource();
            selectFormat(0, document);

            ChatTranslationClient client = new ChatTranslationClient(new OkHttpClient.Builder()
                    .dns(hostname -> { throw new AssertionError("No DNS/network is allowed in this fixture"); })
                    .addInterceptor(chain -> {
                        requests++;
                        try {
                            Buffer bytes = new Buffer();
                            assertNotNull(chain.request().body());
                            chain.request().body().writeTo(bytes);
                            JSONObject body = BoundedJson.object(bytes.readUtf8());
                            JSONObject input = userInput(body);
                            String context = input.optString("context", "");
                            JSONArray items = input.getJSONArray("items");
                            JSONArray output = new JSONArray();
                            for (int i = 0; i < items.length(); i++) {
                                JSONObject item = items.getJSONObject(i);
                                String source = item.getString("source");
                                Object itemContext = item.has("context") ? item.get("context") : "";
                                assertTrue("Optional item.context must be a string", itemContext instanceof String);
                                String effectiveContext = context;
                                if (!((String) itemContext).isEmpty()) effectiveContext += "\n" + (String) itemContext;
                                String translated = answer(source, effectiveContext);
                                receipts.add(new Receipt(source, effectiveContext, translated));
                                output.put(new JSONObject().put("id", item.getString("id")).put("text", translated));
                            }
                            String content = new JSONObject().put("translations", output).toString();
                            String response = new JSONObject().put("choices", new JSONArray().put(new JSONObject()
                                    .put("finish_reason", "stop")
                                    .put("message", new JSONObject().put("content", content)))).toString();
                            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                                    .code(200).message("synthetic-neighbor-context")
                                    .body(ResponseBody.create(MediaType.parse("application/json"), response)).build();
                        } catch (Exception failure) {
                            throw new AssertionError("Invalid actual Chat request/fixture response", failure);
                        }
                    }).build());
            coordinator = new TranslationCoordinator(observation.getInputSession().getSession(), () -> 0,
                    worker, (batch, profile) -> {
                ChatTranslationClient.Job job = client.newJob(batch, profile.wire, profile.credentials, profile.maxCalls);
                return new TranslationCoordinator.Work() {
                    public ChatTranslationClient.Result execute() { return job.execute(); }
                    public void cancel() { job.cancel(); }
                };
            }, new TranslationCoordinator.Limits(100_000, 16, 8192, 60_000, 1, 10, 128));
            bridge = createBridge();
            TranslationCoordinator.ScopedCredentials credentials = new TranslationCoordinator.ScopedCredentials() {
                private final TranslationCoordinator.CredentialScope scope = new TranslationCoordinator.CredentialScope();
                public String getKey() { return "synthetic-neighbor-fixture-key"; }
                public TranslationCoordinator.CredentialScope getCaptionCacheScope() { return scope; }
            };
            template = new TranslationCoordinator.Profile(new ChatCompletionAdapter.Config(
                    "https://example.invalid/chat/completions", "synthetic-neighbor-model", 256, 65536, 65536,
                    ChatCompletionAdapter.TokenField.MAX_TOKENS, ChatCompletionAdapter.Thinking.OMIT),
                    credentials, 0, null, "zh", "", new TranslationBatch.Limits(1, 4096, 4096, 4096), 1);
            assertTrue(bridge.configure(bridge.beginConfiguration(), template, true,
                    NativeCueComposer.Mode.BILINGUAL, NativeCueComposer.Order.ORIGINAL_FIRST));
        }

        // Only bridge's existing package-local UI/constructor seam is reflected, not its algorithm.
        @SuppressWarnings({"rawtypes", "unchecked"})
        private CaptionOutputBridge createBridge() throws Exception {
            Class<?> uiClass = Class.forName(CaptionOutputBridge.class.getName() + "$Ui");
            Object ui = Proxy.newProxyInstance(uiClass.getClassLoader(), new Class<?>[]{uiClass}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "currentThread": return true;
                    case "later": pulse = (Runnable) args[0]; return null;
                    case "remove": if (pulse == args[0]) pulse = null; return null;
                    case "toString": return "SyntheticCaptionUi";
                    case "hashCode": return System.identityHashCode(proxy);
                    case "equals": return proxy == args[0];
                    default: throw new AssertionError("Unexpected UI seam: " + method.getName());
                }
            });
            return ReflectionHelpers.callConstructor(CaptionOutputBridge.class,
                    ReflectionHelpers.ClassParameter.from(CaptionObservation.class, observation),
                    ReflectionHelpers.ClassParameter.from(TranslationCoordinator.class, coordinator),
                    ReflectionHelpers.ClassParameter.from(TextOutput.class, (TextOutput) cues -> last = cues),
                    ReflectionHelpers.ClassParameter.from(CaptionOutputBridge.Position.class,
                            (CaptionOutputBridge.Position) () -> positionMs),
                    ReflectionHelpers.ClassParameter.from((Class) uiClass, ui));
        }

        void bindSource() {
            if (nativeOutput != null) { nativeOutput.release(); nativeOutput = null; }
            if (decoder != null) { decoder.release(); decoder = null; }
            observation.bindSource(metadata, manifest, extraSource);
        }

        void selectFormat(int index, String document) throws Exception {
            if (nativeOutput != null) { nativeOutput.release(); nativeOutput = null; }
            if (decoder != null) decoder.release();
            Format selected = manifest.getPeriod(0).adaptationSets.get(index).representations.get(0).format;
            decoder = observation.createDecoder(selected);
            capture(document);
        }

        void capture(String document) throws Exception {
            if (nativeOutput != null) { nativeOutput.release(); nativeOutput = null; }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            SubtitleInputBuffer input;
            while ((input = decoder.dequeueInputBuffer()) == null && System.nanoTime() < deadline) Thread.sleep(1);
            assertNotNull("Native decoder input pool", input);
            byte[] bytes = document.getBytes(UTF8);
            input.ensureSpaceForWrite(bytes.length);
            input.data.put(bytes);
            input.timeUs = 0;
            input.subsampleOffsetUs = 0;
            input.addFlag(C.BUFFER_FLAG_KEY_FRAME);
            input.flip();
            decoder.queueInputBuffer(input);
            assertNotNull("Actual observed queue must publish the production Raw/Plan", observation.getSession().getSnapshot());
            while ((nativeOutput = decoder.dequeueOutputBuffer()) == null && System.nanoTime() < deadline) Thread.sleep(1);
            assertNotNull("Actual native decoded sample", nativeOutput);
        }

        void emitAt(long milliseconds) {
            positionMs = milliseconds;
            List<Cue> cues = nativeOutput.getCues(milliseconds * 1000);
            assertFalse("Fixture's real native output must be visible", cues.isEmpty());
            // Native formatter output is the existing bridge seam; no hand-built translation or Plan.
            new CaptionCueDispatcher(observation, stamped -> bridge.onNativeCues(stamped, cues), null).onCues(cues);
        }

        void complete() {
            for (int i = 0; i < 100; i++) {
                worker.drain();
                Runnable current = pulse;
                pulse = null;
                if (current != null) current.run();
                if (worker.tasks.isEmpty()) return;
            }
            fail("Unbounded fixture completion");
        }

        Receipt lastTarget(String phrase) { return lastTargetSince(0, phrase); }

        Receipt lastTargetSince(int start, String phrase) {
            for (int i = receipts.size() - 1; i >= start; i--) {
                if (receipts.get(i).source.contains(phrase)) return receipts.get(i);
            }
            throw new AssertionError("Target did not reach the actual request body: " + phrase);
        }

        void assertNativeSentinel(String sentinel) {
            assertEquals("One native composed text Cue", 1, last.size());
            assertNotNull(last.get(0).text);
            String visible = last.get(0).text.toString();
            assertTrue("Native sink must end in the body-derived sentinel, got: " + visible,
                    visible.endsWith("\n" + sentinel));
        }

        public void close() {
            bridge.close();
            if (nativeOutput != null) nativeOutput.release();
            if (decoder != null) decoder.release();
            observation.close();
        }
    }

    private static JSONObject userInput(JSONObject body) throws Exception {
        JSONArray messages = body.getJSONArray("messages");
        JSONObject found = null;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.getJSONObject(i);
            if ("user".equals(message.getString("role"))) {
                assertNull("One actual caption data message", found);
                found = BoundedJson.object(message.getString("content"));
            }
        }
        assertNotNull("The final request must contain caption data", found);
        return found;
    }

    private static MediaSubtitle caption(String name, String id) {
        return new MediaSubtitle() {
            public String getBaseUrl() {
                return "https://www.youtube.com/api/timedtext?v=synthetic-neighbor-video&lang=en&fmt=vtt&name=" + id;
            }
            public boolean isTranslatable() { return true; }
            public String getLanguageCode() { return "en"; }
            public String getVssId() { return id; }
            public String getName() { return name; }
            public String getMimeType() { return "text/vtt"; }
            public String getCodecs() { return null; }
            public String getType() { return null; }
        };
    }

    private static MediaItemFormatInfo info(List<MediaSubtitle> subtitles) {
        return (MediaItemFormatInfo) Proxy.newProxyInstance(MediaItemFormatInfo.class.getClassLoader(),
                new Class<?>[]{MediaItemFormatInfo.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getSubtitles": return subtitles;
                        case "getAdaptiveFormats": return Collections.emptyList();
                        case "getLengthSeconds": return "30";
                        case "getVideoId": return "synthetic-neighbor-video";
                        case "getClientInfo": return new MediaItemFormatInfo.ClientInfo() {
                            public String getClientName() { return "WEB"; }
                            public String getClientVersion() { return "synthetic"; }
                            public String getOsName() { return "synthetic"; }
                            public String getOsVersion() { return "synthetic"; }
                            public String getUserAgent() { return "synthetic"; }
                        };
                    }
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == long.class) return 0L;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == float.class) return 0f;
                    return null;
                });
    }
}
