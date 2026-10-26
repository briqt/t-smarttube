package com.liskovsoft.smartyoutubetv2.common.subtitle.translation;

import android.net.Uri;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.source.dash.manifest.DashManifest;
import com.google.android.exoplayer2.source.dash.manifest.DashManifestParser2;
import com.google.android.exoplayer2.text.Cue;
import com.google.android.exoplayer2.text.SubtitleDecoder;
import com.google.android.exoplayer2.text.SubtitleInputBuffer;
import com.google.android.exoplayer2.text.TextOutput;
import com.google.android.exoplayer2.upstream.ByteArrayDataSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.TransferListener;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.mediaserviceinterfaces.data.MediaSubtitle;
import com.liskovsoft.smartyoutubetv2.common.subtitle.acquisition.CaptionInputSession;
import com.liskovsoft.smartyoutubetv2.common.subtitle.acquisition.NativeCaptionLoader;
import com.liskovsoft.smartyoutubetv2.common.subtitle.acquisition.NativeCaptionRequest;
import com.liskovsoft.smartyoutubetv2.common.subtitle.observation.CaptionCueDispatcher;
import com.liskovsoft.smartyoutubetv2.common.subtitle.observation.CaptionObservation;
import com.liskovsoft.smartyoutubetv2.common.subtitle.presentation.CaptionOutputBridge;
import com.liskovsoft.smartyoutubetv2.common.subtitle.presentation.NativeCueComposer;
import com.liskovsoft.smartyoutubetv2.common.subtitle.session.CaptionSession;
import com.liskovsoft.smartyoutubetv2.common.subtitle.source.CaptionSource;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.RawTimeline;

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

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

import static org.junit.Assert.*;

/**
 * Local original/fallback composition contracts. The acquisition seam is copied minimally from the
 * frozen 615-input CaptionOutputBridgeTest.LateOriginalHarness: real bindSource/find/native queue,
 * InputSession and Bridge, controlled original DataSource/executor and intercepted Chat transport.
 * No original flag, cache scope, Plan, neighbor result, or translation is injected into production.
 * This is not actual Provider, phone, TV or live-stream evidence.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 27)
public class CaptionNeighborOriginalContextTest {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String VIDEO = "synthetic-original-context-video";
    private static final String RIVER_NEIGHBOR = "The river flooded the footpath.";
    private static final String FINANCE_NEIGHBOR = "The loan officer approved the credit.";
    private static final String TARGET = "It was on the bank.";
    private static final String RIVER = "RIVER_BANK_SENTINEL";
    private static final String FINANCE = "FINANCIAL_BANK_SENTINEL";
    private static final String BROKEN = "BROKEN_CONTEXT";
    private static final String MIXED = "MIXED_SOURCE_CONTEXT";
    private static final String NATIVE_DOCUMENT = "WEBVTT\n\n00:01.000 --> 00:04.000\n" +
            RIVER_NEIGHBOR + "\n\n00:05.000 --> 00:08.000\n" + TARGET + "\n";

    @Test
    public void equalLateOriginalKeepsTheCurrentWindowThenReusesPaidNeighborAwareStrings() throws Exception {
        try (Playback h = new Playback(false)) {
            CaptionInputSession.Selection fallback = h.completeFallbackAndVerifyPrefetch();
            TranslationCoordinator.Frame oldFrame = h.coordinator.prepareFrame();
            assertNotNull(oldFrame);
            assertTrue("The old Frame must be valid before the source-choice boundary",
                    h.coordinator.deliver(oldFrame, ignored -> { }));
            int paid = h.requests;
            h.releaseOriginalWithoutTakingTheCurrentWindow(fallback);

            h.emit(5500, TARGET);
            CaptionInputSession.Selection original = h.assertRealOriginalSwitch(fallback, oldFrame);
            assertArrayEquals(fallback.input.timeline.copySource(), original.input.timeline.copySource());
            h.complete();
            h.assertVisible(TARGET + "\n" + RIVER);
            assertEquals("Same target, clock, language, wire AND neighbors must reuse legal completed Strings",
                    paid, h.requests);
            h.assertFreshFrameAndRejectedOldObjects(fallback, oldFrame, RIVER);
        }
    }

    @Test
    public void lateOriginalChangingOnlyTheNeighborMustSendNewContextAndDisplayItsOwnAnswer() throws Exception {
        try (Playback h = new Playback(true)) {
            CaptionInputSession.Selection fallback = h.completeFallbackAndVerifyPrefetch();
            TranslationCoordinator.Frame oldFrame = h.coordinator.prepareFrame();
            assertNotNull(oldFrame);
            assertTrue("The old Frame must be valid before the source-choice boundary",
                    h.coordinator.deliver(oldFrame, ignored -> { }));
            int paid = h.requests;
            int receiptCut = h.receipts.size();
            h.releaseOriginalWithoutTakingTheCurrentWindow(fallback);

            h.emit(5500, TARGET);
            CaptionInputSession.Selection original = h.assertRealOriginalSwitch(fallback, oldFrame);
            assertFalse(Arrays.equals(fallback.input.timeline.copySource(), original.input.timeline.copySource()));
            assertEquals(RIVER_NEIGHBOR, fallback.input.timeline.getCues().get(0).text);
            assertEquals(FINANCE_NEIGHBOR, original.input.timeline.getCues().get(0).text);
            h.complete();

            assertEquals("One genuinely changed target-context admission, not a role-wide retranslation",
                    paid + 1, h.requests);
            Receipt current = h.lastTargetSince(receiptCut);
            assertEquals("The target itself was not rewritten", TARGET, current.source);
            assertEquals(FINANCE, answer(current.source, current.effectiveContext));
            assertEquals(FINANCE, current.translation);
            assertFalse("The fallback neighbor must not leak through a shared base/parent domain",
                    current.effectiveContext.toLowerCase(Locale.US).contains("river flooded the footpath"));
            h.assertVisible(TARGET + "\n" + FINANCE);
            h.assertFreshFrameAndRejectedOldObjects(fallback, oldFrame, FINANCE);
        }
    }

    /** No fixture/document/role argument: only this item's final wire evidence determines its answer. */
    private static String answer(String source, String effectiveContext) {
        if (!TARGET.equals(source)) return "OBSERVED:" + source;
        String evidence = (source + "\n" + effectiveContext).toLowerCase(Locale.US).replaceAll("\\s+", " ");
        boolean river = evidence.contains("river flooded the footpath");
        boolean finance = evidence.contains("loan officer approved the credit");
        return river && finance ? MIXED : river ? RIVER : finance ? FINANCE : BROKEN;
    }

    private static final class Receipt {
        final String source;
        final String effectiveContext;
        final String translation;
        Receipt(String source, String effectiveContext) {
            this.source = source;
            this.effectiveContext = effectiveContext;
            translation = answer(source, effectiveContext);
        }
    }

    private static final class Owner implements TranslationCoordinator.ScopedCredentials {
        final TranslationCoordinator.CredentialScope scope = new TranslationCoordinator.CredentialScope();
        @Override public TranslationCoordinator.CredentialScope getCaptionCacheScope() { return scope; }
        @Override public String getKey() { return "synthetic-original-context-credential"; }
    }

    private static final class Queue implements Executor {
        final List<Runnable> tasks = new ArrayList<>();
        @Override public void execute(Runnable task) { tasks.add(task); }
        void drain() {
            int count = 0;
            while (!tasks.isEmpty()) {
                assertTrue("Unbounded local fixture work", ++count <= 100);
                tasks.remove(0).run();
            }
        }
    }

    private static final class Playback implements AutoCloseable {
        final CaptionObservation observation;
        final CaptionInputSession inputs;
        final TranslationCoordinator coordinator;
        final CaptionOutputBridge bridge;
        final TranslationCoordinator.Profile template;
        final Owner owner = new Owner();
        final Queue originalWorker = new Queue();
        final Queue worker = new Queue();
        final List<Receipt> receipts = new ArrayList<>();
        final List<String> sinkWrites = new ArrayList<>();
        final SubtitleDecoder decoder;
        Runnable pulse;
        List<Cue> last = Collections.emptyList();
        long positionMs = 1000;
        int originalOpens;
        int requests;
        int dnsCalls;

        Playback(boolean changedNeighbor) throws Exception {
            observation = CaptionObservation.create(RuntimeEnvironment.getApplication());
            // Existing frozen scheduling seams only. No production analysis/mapping is replaced.
            ReflectionHelpers.setField(observation, "mExecutor", (Executor) Runnable::run);
            observation.getInputSession().close();
            NativeCaptionLoader loader = ReflectionHelpers.callConstructor(NativeCaptionLoader.class,
                    ReflectionHelpers.ClassParameter.from(Executor.class, originalWorker),
                    ReflectionHelpers.ClassParameter.from(Executor.class, (Executor) Runnable::run));
            inputs = new CaptionInputSession(observation.getSession(), () -> 0L, loader,
                    new CaptionInputSession.Limits(0, 1, 2, new RawTimeline.Limits(65536, 128, 512)));
            ReflectionHelpers.setField(observation, "mInputs", inputs);

            String base = "https://www.youtube.com/api/timedtext?v=" + VIDEO + "&lang=en&fmt=vtt";
            String parentUrl = base + "&candidate=one";
            MediaSubtitle current = caption("Current captions", base + "&tlang=en");
            MediaSubtitle parent = caption("Parent captions", parentUrl);
            List<MediaSubtitle> catalog = Arrays.asList(current, parent);
            MediaItemFormatInfo metadata = captionInfo(catalog);
            DashManifest manifest = new DashManifestParser2().parse(metadata);
            assertFalse("An actual non-dynamic manifest is required", manifest.dynamic);
            assertEquals(1, manifest.getPeriodCount());
            assertEquals(0, manifest.getPeriod(0).startMs);
            assertTrue(manifest.durationMs > 0);
            assertEquals(0, manifest.getPeriod(0).adaptationSets.get(0).representations.get(0).presentationTimeOffsetUs);
            Format selected = manifest.getPeriod(0).adaptationSets.get(0).representations.get(0).format;
            assertEquals(parent.getVssId(), selected.id);
            assertEquals(parent.getMimeType(), selected.sampleMimeType);
            assertEquals("en", current.getLanguageCode());
            assertEquals("en", parent.getLanguageCode());
            boolean vodClock = !manifest.dynamic && manifest.getPeriodCount() == 1 &&
                    manifest.getPeriod(0).startMs == 0 && manifest.durationMs > 0 &&
                    manifest.getPeriod(0).adaptationSets.get(0).representations.get(0).presentationTimeOffsetUs == 0;
            String originalDocument = changedNeighbor ? NATIVE_DOCUMENT.replace(RIVER_NEIGHBOR, FINANCE_NEIGHBOR) : NATIVE_DOCUMENT;
            DataSource.Factory transport = () -> new DataSource() {
                final ByteArrayDataSource bytes = new ByteArrayDataSource(originalDocument.getBytes(UTF8));
                @Override public void addTransferListener(TransferListener listener) { bytes.addTransferListener(listener); }
                @Override public long open(DataSpec spec) throws java.io.IOException {
                    originalOpens++;
                    assertEquals("Only the proved original URL may be opened", Uri.parse(parentUrl), spec.uri);
                    return bytes.open(spec);
                }
                @Override public int read(byte[] buffer, int offset, int length) throws java.io.IOException {
                    return bytes.read(buffer, offset, length);
                }
                @Override public Uri getUri() { return bytes.getUri(); }
                @Override public void close() throws java.io.IOException { bytes.close(); }
            };
            NativeCaptionRequest proof = NativeCaptionRequest.find(VIDEO, catalog, selected, transport, null, vodClock);
            assertNotNull("Real find must prove the unique same-video/vss/language/MIME parent", proof);
            assertEquals("en", proof.source.getSourceLanguage());
            assertEquals(CaptionSource.TranslationKind.DIRECT, proof.source.getTranslationKind());
            assertNull(NativeCaptionRequest.find(VIDEO, catalog, selected, transport, null, false));
            assertNull(NativeCaptionRequest.find("another-video", catalog, selected, transport, null, vodClock));
            assertNull("A second actual parent URL must make the association ambiguous",
                    NativeCaptionRequest.find(VIDEO, Arrays.asList(current, parent,
                            caption("Other parent", base + "&candidate=two")), selected, transport, null, vodClock));
            observation.bindSource(metadata, manifest, transport);
            decoder = observation.createDecoder(selected);
            captureNativeDocument();

            ChatTranslationClient client = new ChatTranslationClient(new OkHttpClient.Builder()
                    .proxy(java.net.Proxy.NO_PROXY)
                    .dns(host -> { dnsCalls++; throw new AssertionError("No DNS/network is allowed"); })
                    .addInterceptor(chain -> {
                        requests++;
                        try {
                            Buffer buffer = new Buffer();
                            assertNotNull(chain.request().body());
                            chain.request().body().writeTo(buffer);
                            JSONObject body = BoundedJson.object(buffer.readUtf8());
                            JSONObject input = userInput(body);
                            assertEquals("en", input.getString("source_language"));
                            assertEquals("zh", input.getString("target_language"));
                            String global = stringOrEmpty(input, "context");
                            assertEquals("The explicit template context remains empty", "", global);
                            JSONArray items = input.getJSONArray("items");
                            assertTrue(items.length() > 0);
                            JSONArray output = new JSONArray();
                            for (int i = 0; i < items.length(); i++) {
                                JSONObject item = items.getJSONObject(i);
                                Receipt receipt = new Receipt(item.getString("source"), global + "\n" + stringOrEmpty(item, "context"));
                                receipts.add(receipt);
                                output.put(new JSONObject().put("id", item.getString("id")).put("text", receipt.translation));
                            }
                            String content = new JSONObject().put("translations", output).toString();
                            String response = new JSONObject().put("choices", new JSONArray().put(new JSONObject()
                                    .put("finish_reason", "stop").put("message", new JSONObject().put("content", content)))).toString();
                            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                                    .code(200).message("offline-original-neighbor-context")
                                    .body(ResponseBody.create(MediaType.parse("application/json"), response)).build();
                        } catch (Exception error) {
                            throw new AssertionError("Invalid actual Chat body or fixture response", error);
                        }
                    }).build());
            template = new TranslationCoordinator.Profile(new ChatCompletionAdapter.Config(
                    "https://original-neighbor.invalid/chat/completions", "synthetic-original-neighbor-model",
                    512, 65536, 65536, ChatCompletionAdapter.TokenField.MAX_TOKENS, ChatCompletionAdapter.Thinking.OMIT),
                    owner, 7, null, "zh", "", new TranslationBatch.Limits(4, 6000, 4096, 12000), 2);
            coordinator = new TranslationCoordinator(inputs.getSession(), () -> 0L, worker, (batch, profile) -> {
                assertSame(owner, profile.credentials);
                assertSame(owner.scope, profile.cacheScope);
                assertSame(template.wire, profile.wire);
                assertEquals(template.credentialRevision, profile.credentialRevision);
                assertEquals("", profile.context);
                ChatTranslationClient.Job job = client.newJob(batch, profile.wire, profile.credentials, profile.maxCalls);
                return new TranslationCoordinator.Work() {
                    @Override public ChatTranslationClient.Result execute() { return job.execute(); }
                    @Override public void cancel() { job.cancel(); }
                };
            }, new TranslationCoordinator.Limits(30_000_000, 32, 16384, 60000, 1, 10, 128));
            bridge = createBridge();
            assertTrue(bridge.configure(bridge.beginConfiguration(), template, true,
                    NativeCueComposer.Mode.BILINGUAL, NativeCueComposer.Order.ORIGINAL_FIRST));
        }

        CaptionInputSession.Selection completeFallbackAndVerifyPrefetch() {
            emit(1000, RIVER_NEIGHBOR);
            CaptionInputSession.Selection fallback = selection();
            assertNotNull(fallback);
            assertFalse(fallback.usingFetchedOriginal);
            assertEquals("en", fallback.textLanguage);
            assertSame(observation.getSession().getSnapshot(), fallback.nativeSnapshot);
            assertSame(fallback.nativeSnapshot.timeline, fallback.input.timeline);
            complete();
            assertVisible(RIVER_NEIGHBOR + "\nOBSERVED:" + RIVER_NEIGHBOR);
            assertEquals("The original GET must remain queued until explicitly released", 0, originalOpens);
            assertEquals(1, originalWorker.tasks.size());
            assertTrue(requests > 0);
            Receipt prefetched = lastTargetSince(0);
            assertEquals("The second target must already have its own neighbor evidence", RIVER, prefetched.translation);
            int paid = requests;
            // Inspect completion, without emitting another native window or executing the original GET.
            coordinator.tick(5_500_000, false);
            TranslationCoordinator.Frame second = coordinator.prepareFrame();
            assertNotNull(second);
            assertEquals(1, second.items.size());
            assertEquals(TARGET, second.items.get(0).original);
            assertEquals(RIVER, second.items.get(0).translation);
            assertTrue("Prefetch must be completed, not merely queued by this inspection", worker.tasks.isEmpty());
            assertEquals(paid, requests);
            coordinator.tick(1_000_000, false);
            return fallback;
        }

        void releaseOriginalWithoutTakingTheCurrentWindow(CaptionInputSession.Selection fallback) {
            int paid = requests;
            String visible = visible();
            int sinkCut = sinkWrites.size();
            originalWorker.drain();
            assertEquals(1, originalOpens);
            complete();
            CaptionInputSession.Selection stillCurrent = selection();
            assertNotNull(stillCurrent);
            assertFalse("Late original must not steal the already chosen native window", stillCurrent.usingFetchedOriginal);
            assertSame(fallback.input, stillCurrent.input);
            assertTrue(inputs.isCurrent(fallback));
            assertTrue("Observe the actual post-arrival output, not only a Selection flag", sinkWrites.size() > sinkCut);
            for (int i = sinkCut; i < sinkWrites.size(); i++) {
                assertEquals("No empty, original-only or changed-text flash when the original arrives", visible, sinkWrites.get(i));
            }
            assertEquals(visible, visible());
            assertEquals("Original arrival alone cannot spend another translation request", paid, requests);
        }

        CaptionInputSession.Selection assertRealOriginalSwitch(CaptionInputSession.Selection fallback,
                                                               TranslationCoordinator.Frame oldFrame) {
            CaptionInputSession.Selection original = selection();
            assertNotNull(original);
            assertTrue("The next native window must really use the fetched original", original.usingFetchedOriginal);
            assertEquals("en", original.textLanguage);
            assertSame(fallback.nativeSnapshot, original.nativeSnapshot);
            assertNotSame(fallback.input, original.input);
            assertNotSame("Different acquisition must really create a different Raw document", fallback.input.timeline, original.input.timeline);
            assertEquals(2, fallback.input.timeline.getCues().size());
            assertEquals(2, original.input.timeline.getCues().size());
            RawTimeline.Cue before = fallback.input.timeline.getCues().get(1);
            RawTimeline.Cue after = original.input.timeline.getCues().get(1);
            assertEquals(TARGET, before.text);
            assertEquals(before.text, after.text);
            assertEquals(before.mappingText, after.mappingText);
            assertEquals(before.startUs, after.startUs);
            assertEquals(before.endUs, after.endUs);
            assertEquals(before.runs.get(0).timingKind, after.runs.get(0).timingKind);
            assertEquals(before.runs.get(0).timingStartUs, after.runs.get(0).timingStartUs);
            assertEquals(before.runs.get(0).timingEndUs, after.runs.get(0).timingEndUs);
            assertEquals(fallback.input.clock.mime, original.input.clock.mime);
            assertEquals(fallback.input.clock.offsetUs, original.input.clock.offsetUs);
            assertEquals(fallback.input.clock.durationUs, original.input.clock.durationUs);
            assertFalse(inputs.deliver(fallback, ignored -> fail("Old fallback Selection reached the sink")));
            assertFalse(coordinator.deliver(oldFrame, ignored -> fail("Old fallback Frame reached the sink")));
            return original;
        }

        void assertFreshFrameAndRejectedOldObjects(CaptionInputSession.Selection fallback,
                                                  TranslationCoordinator.Frame oldFrame, String sentinel) {
            CaptionInputSession.Selection original = selection();
            TranslationCoordinator.Frame current = coordinator.prepareFrame();
            assertNotNull(current);
            assertNotSame(oldFrame, current);
            assertEquals(1, current.items.size());
            assertEquals(TARGET, current.items.get(0).original);
            assertEquals(sentinel, current.items.get(0).translation);
            boolean[] delivered = {false};
            assertTrue(inputs.deliver(original, ignored -> assertTrue(coordinator.deliver(current, frame -> delivered[0] = true))));
            assertTrue(delivered[0]);
            assertFalse(inputs.deliver(fallback, ignored -> fail("Completed String reuse cannot revive a Selection")));
            assertFalse(coordinator.deliver(oldFrame, ignored -> fail("Completed String reuse cannot revive a Frame")));
            assertEquals(1, originalOpens);
            assertEquals(0, dnsCalls);
            assertTrue(worker.tasks.isEmpty());
        }

        CaptionInputSession.Selection selection() {
            return inputs.tick(ReflectionHelpers.getField(bridge, "mWindow"));
        }

        void emit(long position, String source) {
            positionMs = position;
            List<Cue> nativeCues = Collections.singletonList(new Cue(source));
            new CaptionCueDispatcher(observation, stamped -> bridge.onNativeCues(stamped, nativeCues), null).onCues(nativeCues);
        }

        void captureNativeDocument() throws Exception {
            SubtitleInputBuffer input = decoder.dequeueInputBuffer();
            assertNotNull(input);
            byte[] bytes = NATIVE_DOCUMENT.getBytes(UTF8);
            input.ensureSpaceForWrite(bytes.length);
            input.data.put(bytes);
            input.timeUs = 0;
            input.subsampleOffsetUs = 0;
            input.addFlag(C.BUFFER_FLAG_KEY_FRAME);
            input.flip();
            decoder.queueInputBuffer(input);
            assertNotNull("Actual observed native queue must publish the Raw/Plan", observation.getSession().getSnapshot());
        }

        void complete() {
            for (int i = 0; i < 100; i++) {
                worker.drain();
                Runnable current = pulse;
                pulse = null;
                if (current != null) current.run();
                if (worker.tasks.isEmpty()) return;
            }
            fail("Unbounded bridge/translation work");
        }

        Receipt lastTargetSince(int start) {
            for (int i = receipts.size() - 1; i >= start; i--) {
                if (TARGET.equals(receipts.get(i).source)) return receipts.get(i);
            }
            throw new AssertionError("The target never reached its own actual Chat source/context after the boundary");
        }

        String visible() {
            assertEquals("One actual composed native text Cue", 1, last.size());
            assertNotNull(last.get(0).text);
            return last.get(0).text.toString();
        }

        void assertVisible(String expected) { assertEquals(expected, visible()); }

        @SuppressWarnings({"rawtypes", "unchecked"})
        CaptionOutputBridge createBridge() {
            Class<?> uiClass;
            try { uiClass = Class.forName(CaptionOutputBridge.class.getName() + "$Ui"); }
            catch (ClassNotFoundException error) { throw new AssertionError(error); }
            Object ui = Proxy.newProxyInstance(uiClass.getClassLoader(), new Class<?>[]{uiClass}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "currentThread": return true;
                    case "later": pulse = (Runnable) args[0]; return null;
                    case "remove": if (pulse == args[0]) pulse = null; return null;
                    case "toString": return "SyntheticOriginalContextUi";
                    case "hashCode": return System.identityHashCode(proxy);
                    case "equals": return proxy == args[0];
                    default: throw new AssertionError("Unexpected existing UI seam: " + method.getName());
                }
            });
            return ReflectionHelpers.callConstructor(CaptionOutputBridge.class,
                    ReflectionHelpers.ClassParameter.from(CaptionObservation.class, observation),
                    ReflectionHelpers.ClassParameter.from(TranslationCoordinator.class, coordinator),
                    ReflectionHelpers.ClassParameter.from(TextOutput.class, (TextOutput) cues -> {
                        last = cues;
                        StringBuilder write = new StringBuilder();
                        for (Cue cue : cues) {
                            if (write.length() > 0) write.append("\n---\n");
                            write.append(cue.text == null ? "<non-text>" : cue.text);
                        }
                        sinkWrites.add(write.toString());
                    }),
                    ReflectionHelpers.ClassParameter.from(CaptionOutputBridge.Position.class,
                            (CaptionOutputBridge.Position) () -> positionMs),
                    ReflectionHelpers.ClassParameter.from((Class) uiClass, ui));
        }

        @Override public void close() {
            bridge.close();
            decoder.release();
            observation.close();
        }
    }

    private static String stringOrEmpty(JSONObject object, String field) throws Exception {
        Object value = object.has(field) ? object.get(field) : "";
        assertTrue(field + " must be a string when present", value instanceof String);
        return (String) value;
    }

    private static JSONObject userInput(JSONObject body) throws Exception {
        JSONObject input = null;
        JSONArray messages = body.getJSONArray("messages");
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.getJSONObject(i);
            if ("user".equals(message.getString("role"))) {
                assertNull(input);
                input = BoundedJson.object(message.getString("content"));
            }
        }
        assertNotNull(input);
        return input;
    }

    private static MediaSubtitle caption(String name, String url) {
        return new MediaSubtitle() {
            @Override public String getBaseUrl() { return url; }
            @Override public boolean isTranslatable() { return true; }
            @Override public String getLanguageCode() { return "en"; }
            @Override public String getVssId() { return "a.en"; }
            @Override public String getName() { return name; }
            @Override public String getMimeType() { return "text/vtt"; }
            @Override public String getCodecs() { return null; }
            @Override public String getType() { return null; }
        };
    }

    private static MediaItemFormatInfo captionInfo(List<MediaSubtitle> subtitles) {
        return (MediaItemFormatInfo) Proxy.newProxyInstance(MediaItemFormatInfo.class.getClassLoader(),
                new Class<?>[]{MediaItemFormatInfo.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getSubtitles": return subtitles;
                        case "getAdaptiveFormats": return Collections.emptyList();
                        case "getLengthSeconds": return "90";
                        case "getVideoId": return VIDEO;
                        case "getClientInfo": return new MediaItemFormatInfo.ClientInfo() {
                            @Override public String getClientName() { return "WEB"; }
                            @Override public String getClientVersion() { return "synthetic"; }
                            @Override public String getOsName() { return "synthetic"; }
                            @Override public String getOsVersion() { return "synthetic"; }
                            @Override public String getUserAgent() { return "synthetic"; }
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
