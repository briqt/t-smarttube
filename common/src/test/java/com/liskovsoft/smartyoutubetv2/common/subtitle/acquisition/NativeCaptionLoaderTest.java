package com.liskovsoft.smartyoutubetv2.common.subtitle.acquisition;

import android.net.Uri;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.TransferListener;
import com.liskovsoft.mediaserviceinterfaces.data.MediaSubtitle;
import com.liskovsoft.smartyoutubetv2.common.subtitle.source.CaptionSource;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.RawTimeline;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 27)
public class NativeCaptionLoaderTest {
    static final String BASE = "https://www.youtube.com/api/timedtext?v=fixture-video&lang=en&fmt=vtt";
    static final RawTimeline.Limits LIMITS = new RawTimeline.Limits(4096, 32, 128);

    @Test
    public void actualOriginalReadMustPrepareTheSamePaintOnCanonicalRanges() throws Exception {
        Transport transport = new Transport();
        try (java.io.InputStream input = NativeCaptionLoaderTest.class.getResourceAsStream("/subtitle/rolling_settings_synthetic.vtt");
             java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
            assertNotNull(input);
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            transport.body = bytes.toByteArray();
        }
        String base = BASE + "&kind=asr";
        NativeCaptionRequest request = find(Arrays.asList(
                subtitle("Chinese", "zh-Hans", base + "&tlang=zh-Hans", null), original(base)), transport, true);
        assertNotNull(request);
        assertEquals(CaptionSource.TextKind.ASR_CANDIDATE, request.source.getTextKind());
        List<NativeCaptionLoader.Result> result = new ArrayList<>();
        NativeCaptionLoader loader = new NativeCaptionLoader(Runnable::run, Runnable::run);
        try {
            loader.start(request, 20_000_000, LIMITS, result::add);
            assertEquals(1, result.size());
            assertEquals(NativeCaptionLoader.Status.ACCEPTED, result.get(0).status);
            assertEquals(1, result.get(0).transcript.normalizationVersion());
            assertEquals("[token1]\ntoken2 token3 token4 token5 token6 token7 token8\n" +
                    "token9 token10 token11 token12 token13 token14", result.get(0).groups.getText(0));
            assertEquals(1, result.get(0).groups.getGroups().size());
            assertEquals(6, result.get(0).timeline.getCues().size());
            assertArrayEquals(transport.body, result.get(0).timeline.copySource());
            assertEquals(1, transport.created);
            assertEquals(1, transport.closed);
        } finally { loader.close(); }
    }

    @Test
    public void uniqueActualCatalogParentUsesExactUrlAndNativeGetCookieContext() {
        Transport transport = new Transport();
        NativeCaptionRequest request = request(transport);
        assertNotNull(request);
        assertEquals(CaptionSource.TranslationKind.DIRECT, request.source.getTranslationKind());
        Queue worker = new Queue();
        NativeCaptionLoader loader = new NativeCaptionLoader(worker, Runnable::run);
        List<NativeCaptionLoader.Result> results = new ArrayList<>();
        loader.start(request, 10_000_000, LIMITS, results::add);
        assertEquals(0, transport.created);
        worker.drain();
        assertEquals(1, results.size());
        assertEquals(NativeCaptionLoader.Status.ACCEPTED, results.get(0).status);
        assertTrue(results.get(0).groups.isFor(results.get(0).transcript));
        DataSpec spec = transport.specs.get(0);
        assertEquals(BASE + "&signature=fixture-original", spec.uri.toString());
        assertEquals(DataSpec.HTTP_METHOD_GET, spec.httpMethod);
        assertEquals(0, spec.position);
        assertEquals(-1, spec.length);
        assertEquals("VISITOR=fixture", spec.httpRequestHeaders.get("Cookie"));
        assertEquals(1, transport.closed);
        loader.close();
    }

    @Test
    public void ambiguousParentsWrongVideoWrongOriginOrUnknownClockOnlySkipExtraRequest() {
        Transport transport = new Transport();
        MediaSubtitle current = translated();
        List<MediaSubtitle> valid = Arrays.asList(current, original(BASE + "&signature=one"));
        assertNotNull(find(valid, transport, true));
        assertNull(find(Arrays.asList(current, original(BASE + "&signature=one"), original(BASE + "&signature=two")), transport, true));
        assertNull(find(valid, transport, false));
        assertNull(find(Arrays.asList(current, original(BASE.replace("fixture-video", "another-video"))), transport, true));
        assertNull(find(Arrays.asList(current, original(BASE.replace("www.youtube.com", "example.invalid"))), transport, true));
        assertNull(find(Arrays.asList(current, original(BASE + "&v=fixture-video")), transport, true));
        assertNull(find(Arrays.asList(current, original(BASE + "&%76=fixture-video")), transport, true));
        assertNull(find(Arrays.asList(current, original(BASE + "&variant=timing-optimized")), transport, true));
        assertEquals(0, transport.created);
    }

    @Test
    public void directAsrIsNotRetranslatedAsAMachineTrackAndMetadataFailuresDoNotThrow() {
        Transport transport = new Transport();
        Format direct = Format.createTextSampleFormat("a.en", "text/vtt", 0, "English");
        assertNull(NativeCaptionRequest.find("fixture-video", Arrays.asList(original(BASE)), direct, transport, null, true));
        assertNull(NativeCaptionRequest.find("fixture-video", null, direct, transport, null, true));
        assertEquals(0, transport.created);
    }

    @Test
    public void oversizedReportedOrStreamedBodyAndReadFailureNeverReturnAnAcceptedPrefix() {
        for (int mode = 0; mode < 3; mode++) {
            Transport transport = new Transport();
            transport.reportedLength = mode == 0 ? 5000 : -1;
            transport.body = mode == 1 ? new byte[5000] : vtt("original");
            transport.failRead = mode == 2;
            List<NativeCaptionLoader.Result> results = new ArrayList<>();
            NativeCaptionLoader loader = new NativeCaptionLoader(Runnable::run, Runnable::run);
            loader.start(request(transport), 10_000_000, LIMITS, results::add);
            assertNotEquals(NativeCaptionLoader.Status.ACCEPTED, results.get(0).status);
            assertNull(results.get(0).timeline);
            assertTrue(transport.closed > 0);
            loader.close();
        }
    }

    @Test
    public void supportedSettingsKeepDocumentTimingStylesStillFallbackAndTimestampMapCannotInventClock() {
        Transport transport = new Transport();
        transport.body = bytes("WEBVTT\n\n00:01.000 --> 00:04.000 align:start\noriginal\n");
        List<NativeCaptionLoader.Result> result = new ArrayList<>();
        NativeCaptionLoader loader = new NativeCaptionLoader(Runnable::run, Runnable::run);
        loader.start(request(transport), 10_000_000, LIMITS, result::add);
        assertEquals(NativeCaptionLoader.Status.ACCEPTED, result.get(0).status);
        assertEquals(RawTimeline.Origin.DOCUMENT, result.get(0).timeline.getOrigin());
        assertEquals("start", result.get(0).timeline.getCues().get(0).webvttSettings.get("align"));
        transport.body = bytes("WEBVTT\n\nSTYLE\n::cue { color: lime; }\n\n00:01.000 --> 00:04.000\noriginal\n");
        loader.start(request(transport), 10_000_000, LIMITS, result::add);
        assertEquals(NativeCaptionLoader.Status.ACCEPTED, result.get(1).status);
        assertEquals(RawTimeline.Origin.NATIVE_DISPLAY, result.get(1).timeline.getOrigin());
        transport.body = bytes("WEBVTT\nX-TIMESTAMP-MAP=MPEGTS:900000,LOCAL:00:00.000\n\n00:01.000 --> 00:04.000 align:start position:0%\noriginal\n");
        loader.start(request(transport), 10_000_000, LIMITS, result::add);
        assertEquals(NativeCaptionLoader.Status.REJECTED, result.get(2).status);
        loader.close();
    }

    @Test
    public void cancellationBeforeExecutionAndCloseCancelQueuedWorkExactlyOnce() {
        Transport transport = new Transport();
        Queue worker = new Queue();
        NativeCaptionLoader loader = new NativeCaptionLoader(worker, Runnable::run);
        List<NativeCaptionLoader.Result> results = new ArrayList<>();
        NativeCaptionLoader.Load load = loader.start(request(transport), 10_000_000, LIMITS, results::add);
        load.cancel();
        worker.drain();
        assertEquals(1, results.size());
        assertEquals(NativeCaptionLoader.Status.CANCELLED, results.get(0).status);
        assertEquals(0, transport.created);
        loader.start(request(transport), 10_000_000, LIMITS, results::add);
        loader.close();
        worker.drain();
        assertEquals(2, results.size());
        assertEquals(0, transport.created);
    }

    @Test
    public void rejectedExecutorAndBrokenSinkDoNotRunIoOnCallerOrEscape() {
        Transport transport = new Transport();
        NativeCaptionLoader loader = new NativeCaptionLoader(task -> { throw new RejectedExecutionException(); }, Runnable::run);
        List<NativeCaptionLoader.Result> results = new ArrayList<>();
        loader.start(request(transport), 10_000_000, LIMITS, results::add);
        assertEquals(NativeCaptionLoader.Status.ERROR, results.get(0).status);
        assertEquals(0, transport.created);
        loader.start(request(transport), 10_000_000, LIMITS, value -> { throw new IllegalStateException("synthetic"); });
        loader.close();
    }

    @Test
    public void ttmlOriginalUsesSameTransportAndSafeNativeWholeDocumentProjection() {
        Transport transport = new Transport();
        String base = BASE.replace("fmt=vtt", "fmt=ttml");
        MediaSubtitle current = subtitle("Chinese", "zh-Hans", base + "&tlang=zh-Hans", null, "application/ttml+xml");
        MediaSubtitle original = subtitle("English", "en", base, null, "application/ttml+xml");
        NativeCaptionRequest request = NativeCaptionRequest.find("fixture-video", Arrays.asList(current, original),
                Format.createTextSampleFormat("a.en", "application/ttml+xml", 0, "Chinese"), transport, null, true);
        assertNotNull(request);
        transport.body = bytes("<tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div><p begin=\"1s\" end=\"4s\">original TTML</p></div></body></tt>");
        List<NativeCaptionLoader.Result> result = new ArrayList<>();
        NativeCaptionLoader loader = new NativeCaptionLoader(Runnable::run, Runnable::run);
        loader.start(request, 10_000_000, LIMITS, result::add);
        assertEquals(NativeCaptionLoader.Status.ACCEPTED, result.get(0).status);
        assertEquals(RawTimeline.Origin.NATIVE_DISPLAY, result.get(0).timeline.getOrigin());
        assertEquals("original TTML", result.get(0).timeline.getCuesAt(1_000_000).get(0).text);
        assertTrue(transport.specs.get(0).httpRequestHeaders.isEmpty());
        loader.close();
    }

    @Test
    public void cancelledQueuedReadMustReleaseItsSlotForTheReplacementSource() throws Exception {
        Transport transport = new Transport();
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch resume = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger opened = new java.util.concurrent.atomic.AtomicInteger();
        DataSource.Factory blocking = () -> {
            DataSource delegate = transport.createDataSource();
            return new DataSource() {
                public void addTransferListener(TransferListener listener) { delegate.addTransferListener(listener); }
                public long open(DataSpec spec) throws IOException {
                    if (opened.getAndIncrement() == 0) {
                        entered.countDown();
                        try { if (!resume.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("Synthetic open timeout"); }
                        catch (InterruptedException e) { throw new IOException("Synthetic interrupted open"); }
                    }
                    return delegate.open(spec);
                }
                public int read(byte[] buffer, int offset, int length) throws IOException { return delegate.read(buffer, offset, length); }
                public Uri getUri() { return delegate.getUri(); }
                public void close() throws IOException { delegate.close(); }
            };
        };
        NativeCaptionRequest request = NativeCaptionRequest.find("fixture-video", Arrays.asList(translated(), original(BASE)),
                Format.createTextSampleFormat("a.en", "text/vtt", 0, "Chinese"), blocking, null, true);
        java.util.concurrent.ThreadPoolExecutor worker = new java.util.concurrent.ThreadPoolExecutor(1, 1, 0,
                java.util.concurrent.TimeUnit.MILLISECONDS, new java.util.concurrent.ArrayBlockingQueue<>(1));
        NativeCaptionLoader loader = new NativeCaptionLoader(worker, Runnable::run);
        try {
            loader.start(request, 10_000_000, LIMITS, ignored -> { });
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            NativeCaptionLoader.Load queued = loader.start(request, 10_000_000, LIMITS, ignored -> { });
            queued.cancel();
            List<NativeCaptionLoader.Result> replacement = new java.util.concurrent.CopyOnWriteArrayList<>();
            loader.start(request, 10_000_000, LIMITS, replacement::add);
            assertTrue(replacement.isEmpty()); // Replacement must be queued, not synchronously rejected as ERROR.
            assertEquals(1, worker.getQueue().size());
        } finally {
            resume.countDown();
            loader.close();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    static NativeCaptionRequest request(Transport transport) {
        return find(Arrays.asList(translated(), original(BASE + "&signature=fixture-original")), transport, true);
    }
    static NativeCaptionRequest find(List<MediaSubtitle> list, Transport transport, boolean clock) {
        return NativeCaptionRequest.find("fixture-video", list, Format.createTextSampleFormat("a.en", "text/vtt", 0, "Chinese"), transport,
                "VISITOR=fixture", clock);
    }
    static MediaSubtitle translated() { return subtitle("Chinese", "zh-Hans", BASE + "&tlang=zh-Hans&signature=fixture-translated", null); }
    static MediaSubtitle original(String url) { return subtitle("English", "en", url, null); }
    static MediaSubtitle subtitle(String name, String language, String url, String type) {
        return subtitle(name, language, url, type, "text/vtt");
    }
    static MediaSubtitle subtitle(String name, String language, String url, String type, String mime) {
        return new MediaSubtitle() {
            public String getBaseUrl() { return url; }
            public boolean isTranslatable() { return true; }
            public String getLanguageCode() { return language; }
            public String getVssId() { return "a.en"; }
            public String getName() { return name; }
            public String getMimeType() { return mime; }
            public String getCodecs() { return null; }
            public String getType() { return type; }
        };
    }
    static byte[] vtt(String text) { return bytes("WEBVTT\n\n00:01.000 --> 00:04.000\n" + text + "\n"); }
    static byte[] bytes(String text) { return text.getBytes(Charset.forName("UTF-8")); }
    static final class Queue implements Executor {
        final List<Runnable> tasks = new ArrayList<>();
        public void execute(Runnable task) { tasks.add(task); }
        void drain() { int n = 0; while (!tasks.isEmpty()) { if (++n > 20) throw new AssertionError("Unbounded requests"); tasks.remove(0).run(); } }
    }
    static final class Transport implements DataSource.Factory {
        final List<DataSpec> specs = new ArrayList<>();
        byte[] body = vtt("original");
        long reportedLength = -1;
        boolean failRead;
        int created, closed;
        public DataSource createDataSource() {
            created++;
            byte[] data = body.clone();
            return new DataSource() {
                int position;
                Uri uri;
                public void addTransferListener(TransferListener listener) { }
                public long open(DataSpec spec) { specs.add(spec); uri = spec.uri; return reportedLength; }
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    if (failRead) throw new IOException("Synthetic source-bearing exception must not escape");
                    if (position == data.length) return -1;
                    int count = Math.min(length, data.length - position);
                    System.arraycopy(data, position, buffer, offset, count);
                    position += count;
                    return count;
                }
                public Uri getUri() { return uri; }
                public void close() { closed++; uri = null; }
            };
        }
    }
}
