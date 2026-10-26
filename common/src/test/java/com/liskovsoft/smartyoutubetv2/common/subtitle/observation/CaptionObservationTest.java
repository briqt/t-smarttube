package com.liskovsoft.smartyoutubetv2.common.subtitle.observation;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.source.dash.manifest.DashManifest;
import com.google.android.exoplayer2.source.dash.manifest.DashManifestParser2;
import com.google.android.exoplayer2.source.sabr.manifest.SabrManifest;
import com.google.android.exoplayer2.source.sabr.manifest.SabrManifestParser;
import com.google.android.exoplayer2.text.SubtitleDecoder;
import com.google.android.exoplayer2.text.SubtitleDecoderException;
import com.google.android.exoplayer2.text.SubtitleDecoderFactory;
import com.google.android.exoplayer2.text.SubtitleInputBuffer;
import com.google.android.exoplayer2.text.SubtitleOutputBuffer;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.mediaserviceinterfaces.data.MediaSubtitle;
import com.liskovsoft.smartyoutubetv2.common.subtitle.session.CaptionSession;
import com.liskovsoft.smartyoutubetv2.common.subtitle.source.CaptionSource;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.MappedTranscript;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.SegmentationProposal;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.SemanticGroupPlanner;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 27)
public class CaptionObservationTest {
    private static final String VTT = "WEBVTT\n\n00:01.000 --> 00:12.000 align:start\nsecret-payload\n";
    // CSS still needs native projection; scalar settings alone no longer force that path.
    private static final String NATIVE_ONLY_VTT = VTT.replace("WEBVTT\n\n", "WEBVTT\n\nSTYLE\n::cue { color: lime; }\n\n");
    private final List<Runnable> tasks = new ArrayList<>();
    private final List<CaptionObservation.Report> reports = new ArrayList<>();
    private final FakeDecoder nativeDecoder = new FakeDecoder();
    private final SubtitleDecoderFactory factory = new SubtitleDecoderFactory() {
        @Override public boolean supportsFormat(Format format) { return true; }
        @Override public SubtitleDecoder createDecoder(Format format) { return nativeDecoder; }
    };
    private final CaptionObservation observation = new CaptionObservation(factory, tasks::add, reports::add);

    @Test
    public void actualDashManifestFormatsBindWithoutRetainingOrLoggingSourceValues() throws Exception {
        MediaItemFormatInfo info = info(subtitle("a.secret", "English private", "text/vtt", "en", null));
        DashManifest manifest = new DashManifestParser2().parse(info);
        Format format = manifest.getPeriod(0).adaptationSets.get(0).representations.get(0).format;
        observation.bindSource(info, manifest);
        SubtitleDecoder decoder = observation.createDecoder(format);
        SubtitleInputBuffer input = input(VTT);
        input.data.position(5);
        input.timeUs = 7_000_000;
        input.subsampleOffsetUs = Format.OFFSET_SAMPLE_RELATIVE;
        input.addFlag(C.BUFFER_FLAG_KEY_FRAME);
        decoder.queueInputBuffer(input);
        assertSame(input, nativeDecoder.lastInput);
        assertEquals(5, input.data.position());
        assertEquals(1, tasks.size());
        Arrays.fill(input.data.array(), (byte) '!');
        drain();
        CaptionObservation.Report report = reports.get(0);
        assertEquals(CaptionObservation.Route.DASH_CATALOG, report.lease.route);
        assertEquals(90_000, report.lease.durationMs);
        assertEquals(1, report.lease.binding.candidates);
        assertEquals(CaptionSource.TranslationKind.DIRECT, report.lease.binding.source.getTranslationKind());
        assertEquals(7_000_000, report.offsetUs);
        assertTrue(report.keyFrame);
        assertEquals(Long.valueOf(12_000_000), report.metrics.maxEndUs);
        assertEquals(CaptionObservation.TimelineState.ACCEPTED, report.timeline.state);
        assertEquals(1, report.timeline.cues);
        assertTrue(report.sessionAccepted);
        assertEquals("start", observation.getSession().getSnapshot().timeline.getCues().get(0).webvttSettings.get("align"));
        assertEquals(19_000_000, observation.getSession().getSnapshot().timeline.getCues().get(0).endUs);
        String line = report.safeLine();
        for (String secret : new String[]{"secret-payload", "a.secret", "English private", "example.invalid", "signature"}) {
            assertFalse(line, line.contains(secret));
        }
    }

    @Test
    public void acceptedTimelineUsesEffectiveOffsetAndOnlyScalarSummaryLeavesWorker() throws Exception {
        SubtitleDecoder decoder = observation.createDecoder(bind(format("a", "English", "text/vtt")));
        SubtitleInputBuffer input = input("WEBVTT\n\n00:01.000 --> 00:15.000\nKeep &lt;text&gt;\n");
        input.subsampleOffsetUs = 4_000_000;
        decoder.queueInputBuffer(input);
        Arrays.fill(input.data.array(), (byte) '!');
        drain();
        CaptionObservation.Report report = reports.get(0);
        assertEquals(CaptionObservation.TimelineState.ACCEPTED, report.timeline.state);
        assertEquals(1, report.timeline.cues);
        assertEquals(1, report.timeline.runs);
        assertEquals(Long.valueOf(5_000_000), report.timeline.minUs);
        assertEquals(Long.valueOf(19_000_000), report.timeline.maxUs);
        assertFalse(report.safeLine().contains("Keep"));
        assertTrue(report.sessionAccepted);
        CaptionSession session = observation.getSession();
        CaptionSession.Snapshot snapshot = session.getSnapshot();
        assertSame(report.lease.binding.source, snapshot.track.source);
        assertEquals(1, snapshot.track.candidates);
        assertTrue(session.getCuesAt(snapshot, 4_999_999).isEmpty());
        assertEquals("Keep <text>", session.getCuesAt(snapshot, 5_000_000).get(0).text);
        assertTrue(session.getCuesAt(snapshot, 19_000_000).isEmpty());
        assertTrue(snapshot.transcript.isFor(snapshot.timeline));
        assertTrue(snapshot.groups.isFor(snapshot.transcript));
        assertEquals(1, snapshot.groups.getGroups().size());
        assertEquals("Keep <text>", snapshot.groups.getText(0));
        assertEquals("Keep <text>", snapshot.transcript.getAtoms().get(0).text);
        MappedTranscript.Reference reference = snapshot.transcript.references(new MappedTranscript.Slice(0, 0, 4)).get(0);
        assertFalse(reference.wholeRun);
        assertEquals(5_000_000, reference.origin.timingStartUs);
        assertEquals(19_000_000, reference.origin.timingEndUs);
        for (java.lang.reflect.Field field : CaptionObservation.TimelineSummary.class.getDeclaredFields()) {
            Class<?> type = field.getType();
            assertTrue(type == int.class || type == Long.class || type == CaptionObservation.TimelineState.class ||
                    type == com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.RawTimeline.Origin.class);
        }
    }

    @Test
    public void malformedLateCueRejectsTheWholeTimelineWithoutChangingNativeQueue() throws Exception {
        SubtitleDecoder decoder = observation.createDecoder(bind(format("a", "English", "text/vtt")));
        SubtitleInputBuffer input = input("WEBVTT\n\n00:01.000 --> 00:02.000\nfirst\n\n" +
                "00:03.000 --> 00:01.000\nlast\n");
        decoder.queueInputBuffer(input);
        assertSame(input, nativeDecoder.lastInput);
        drain();
        assertEquals(CaptionObservation.TimelineState.REJECTED, reports.get(0).timeline.state);
        assertNull(reports.get(0).timeline.maxUs);
    }

    @Test
    public void actualSabrManifestUsesIndependentSourceBinding() throws Exception {
        MediaItemFormatInfo info = info(subtitle("a", "English", "application/ttml+xml", "en", null));
        SabrManifest manifest = new SabrManifestParser().parse(info);
        Format format = manifest.getPeriod(0).adaptationSets.get(0).representations.get(0).format;
        observation.bindSource(info, manifest);
        observation.createDecoder(format).queueInputBuffer(input("<not-parsed/>"));
        drain();
        assertEquals(CaptionObservation.Route.SABR_CATALOG, reports.get(0).lease.route);
        assertEquals(CaptionSampleMetrics.Status.TTML_NOT_ANALYZED, reports.get(0).metrics.status);
        assertNull(reports.get(0).metrics.maxEndUs);
        assertEquals(CaptionObservation.TimelineState.SKIPPED, reports.get(0).timeline.state);
    }

    @Test
    public void reusedVssIdAndDisplayNameRemainAmbiguousEvenWhenClassificationDiffers() throws Exception {
        Format format = format("a", "English", "text/vtt");
        observation.bindSource(info(subtitle("a", "English", "text/vtt", "en", null),
                subtitle("a", "English", "text/vtt", "fr", "asr")), CaptionObservation.Route.DASH_CATALOG,
                Collections.singletonList(format), 90_000);
        observation.createDecoder(format).queueInputBuffer(input(VTT));
        drain();
        assertEquals(2, reports.get(0).lease.binding.candidates);
        assertNull(reports.get(0).lease.binding.source);
        assertTrue(reports.get(0).safeLine().contains("translation=UNKNOWN"));
    }

    @Test
    public void formatWithoutMatchingCatalogIsLinkedToSourceButNotGuessedAsDirect() throws Exception {
        Format format = bind(format("missing", "English", "text/vtt"));
        observation.createDecoder(format).queueInputBuffer(input(VTT));
        drain();
        assertEquals(0, reports.get(0).lease.binding.candidates);
        assertNull(reports.get(0).lease.binding.source);
    }

    @Test
    public void equalLookingOldOrCopiedFormatsNeverAttachToTheNewSource() throws Exception {
        Format old = bind(format("a", "English", "text/vtt"));
        SubtitleDecoder decoder = observation.createDecoder(old);
        decoder.queueInputBuffer(input(VTT));
        bind(format("a", "English", "text/vtt"));
        assertSame(nativeDecoder, observation.createDecoder(old));
        assertSame(nativeDecoder, observation.createDecoder(format("a", "English", "text/vtt")));
        drain();
        assertTrue(reports.isEmpty());
    }

    @Test
    public void flushInvalidatesQueuedWorkButTheSameDecoderCanCaptureAfterSeek() throws Exception {
        SubtitleDecoder decoder = observation.createDecoder(bind(format("a", "English", "text/vtt")));
        decoder.queueInputBuffer(input(VTT));
        decoder.flush();
        decoder.queueInputBuffer(input(VTT));
        drain();
        assertEquals(1, nativeDecoder.flushes);
        assertEquals(1, reports.size());
        assertEquals(1, reports.get(0).revision);
    }

    @Test
    public void trackNoneSourceChangeReleaseAndCloseInvalidateDelayedWork() throws Exception {
        for (int action = 0; action < 4; action++) {
            SubtitleDecoder decoder = observation.createDecoder(bind(format("a", "English", "text/vtt")));
            decoder.queueInputBuffer(input(VTT));
            if (action == 0) observation.clearTrack();
            if (action == 1) observation.clearSource();
            if (action == 2) decoder.release();
            if (action == 3) observation.close();
            drain();
            assertTrue(reports.isEmpty());
        }
        assertSame(nativeDecoder, observation.createDecoder(format("a", "English", "text/vtt")));
    }

    @Test
    public void newerDecoderInvalidatesPriorDecoderWithoutRelyingOnTextOrId() throws Exception {
        Format format = bind(format("a", "English", "text/vtt"));
        SubtitleDecoder old = observation.createDecoder(format);
        old.queueInputBuffer(input(VTT));
        SubtitleDecoder next = observation.createDecoder(format);
        next.queueInputBuffer(input(VTT));
        old.queueInputBuffer(input(VTT));
        drain();
        assertEquals(1, reports.size());
        assertEquals(2, reports.get(0).lease.decoderId);
    }

    @Test
    public void queueAndLaterNativeDecodeExceptionsRemainIdenticalAndNoSuccessIsInvented() throws Exception {
        SubtitleDecoder decoder = observation.createDecoder(bind(format("a", "English", "text/vtt")));
        SubtitleDecoderException failure = new SubtitleDecoderException("synthetic");
        nativeDecoder.queueFailure = failure;
        try { decoder.queueInputBuffer(input(VTT)); fail(); } catch (SubtitleDecoderException e) { assertSame(failure, e); }
        assertTrue(tasks.isEmpty());
        nativeDecoder.queueFailure = null;
        decoder.queueInputBuffer(input(VTT));
        nativeDecoder.outputFailure = failure;
        try { decoder.dequeueOutputBuffer(); fail(); } catch (SubtitleDecoderException e) { assertSame(failure, e); }
        drain();
        assertEquals(1, reports.size()); // Structural input observation is not native decode success.
    }

    @Test
    public void unsupportedBufferAndLargeInputStillDelegateWhileEosAndEncryptedAreNotCaptured() throws Exception {
        SubtitleDecoder decoder = observation.createDecoder(bind(format("a", "English", "text/vtt")));
        SubtitleInputBuffer direct = input(VTT);
        direct.data = ByteBuffer.allocateDirect(4);
        direct.data.limit(4);
        decoder.queueInputBuffer(direct);
        SubtitleInputBuffer large = input(VTT);
        large.data = ByteBuffer.allocate(CaptionSampleMetrics.MAX_BYTES + 1);
        decoder.queueInputBuffer(large);
        SubtitleInputBuffer readOnly = input(VTT);
        readOnly.data = readOnly.data.asReadOnlyBuffer();
        decoder.queueInputBuffer(readOnly);
        SubtitleInputBuffer sliced = input(VTT);
        sliced.data.position(1);
        sliced.data = sliced.data.slice();
        decoder.queueInputBuffer(sliced);
        SubtitleInputBuffer eos = input(VTT);
        eos.addFlag(C.BUFFER_FLAG_END_OF_STREAM);
        decoder.queueInputBuffer(eos);
        SubtitleInputBuffer encrypted = input(VTT);
        encrypted.addFlag(C.BUFFER_FLAG_ENCRYPTED);
        decoder.queueInputBuffer(encrypted);
        assertSame(encrypted, nativeDecoder.lastInput);
        drain();
        assertEquals(4, reports.size());
        assertEquals(CaptionObservation.Capture.UNSUPPORTED_BUFFER, reports.get(0).capture);
        assertEquals(CaptionObservation.Capture.TOO_LARGE, reports.get(1).capture);
    }

    @Test
    public void failedHandoffAndThrowingSinkDoNotChangeSuccessfulNativeQueue() throws Exception {
        for (Executor executor : Arrays.<Executor>asList(command -> { throw new RejectedExecutionException(); }, Runnable::run)) {
            CaptionObservation local = new CaptionObservation(factory, executor, report -> { throw new IllegalStateException(); });
            Format format = format("a", "English", "text/vtt");
            local.bindSource(info(subtitle("a", "English", "text/vtt", "en", null)),
                    CaptionObservation.Route.DASH_CATALOG, Collections.singletonList(format), 1);
            local.createDecoder(format).queueInputBuffer(input(VTT));
            assertNotNull(nativeDecoder.lastInput);
        }
    }

    @Test
    public void diagnosticLimitStopsReportsButNotBusinessCopiesAndDispatch() throws Exception {
        SubtitleDecoder decoder = observation.createDecoder(bind(format("a", "English", "text/vtt")));
        for (int i = 0; i < 40; i++) decoder.queueInputBuffer(validInput("sample " + i));
        assertEquals(40, tasks.size());
        drain();
        assertEquals(32, reports.size());
        assertEquals("sample 39", observation.getSession().getSnapshot().groups.getText(0));
        assertEquals(40, nativeDecoder.queues);
    }

    @Test
    public void absentDiagnosticSinkStillPublishesDocumentsPastThirtyTwoAndRejectsOldLease() throws Exception {
        CaptionObservation local = new CaptionObservation(factory, tasks::add, null);
        try {
            Format format = format("a", "English", "text/vtt");
            local.bindSource(info(subtitle("a", "English", "text/vtt", "en", null)),
                    CaptionObservation.Route.DASH_CATALOG, Collections.singletonList(format), 90_000);
            SubtitleDecoder old = local.createDecoder(format);
            for (int i = 0; i < 40; i++) {
                old.queueInputBuffer(validInput("without diagnostics " + i));
                drain();
                assertEquals("without diagnostics " + i, local.getSession().getSnapshot().groups.getText(0));
            }
            assertTrue(reports.isEmpty());
            old.queueInputBuffer(validInput("old pending"));
            SubtitleDecoder current = local.createDecoder(format);
            current.queueInputBuffer(validInput("current decoder"));
            old.queueInputBuffer(validInput("old late return"));
            drain();
            assertEquals("current decoder", local.getSession().getSnapshot().groups.getText(0));
            assertNull(local.getInputSession().tick(new Object())); // AI remains disabled without explicit configuration.
        } finally {
            local.close();
        }
    }

    @Test
    public void absentDiagnosticSinkRetainsOnlyOwnedInputForEnablingAfterDownload() throws Exception {
        CaptionObservation local = new CaptionObservation(factory, tasks::add, null);
        try {
            Format format = format("a", "English", "text/vtt");
            local.bindSource(info(subtitle("a", "English", "text/vtt", "en", null)),
                    CaptionObservation.Route.DASH_CATALOG, Collections.singletonList(format), 90_000);
            SubtitleDecoder decoder = local.createDecoder(format);
            SubtitleInputBuffer input = input(NATIVE_ONLY_VTT);
            decoder.queueInputBuffer(input);
            drain();
            assertNull(local.getSession().getSnapshot());
            Arrays.fill(input.data.array(), (byte) '!');
            local.setEnhancementEnabled(true);
            drain();
            assertEquals("secret-payload", local.getSession().getSnapshot().groups.getText(0));
            assertEquals(1, nativeDecoder.queues);
            local.ensureEnhancementInput();
            assertTrue(tasks.isEmpty());
            local.clearTrack();
            local.ensureEnhancementInput();
            drain();
            assertNull(local.getSession().getSnapshot());
        } finally {
            local.close();
        }
    }

    @Test
    public void delegateNamePositionAndBuffersKeepOwnership() throws Exception {
        SubtitleDecoder decoder = observation.createDecoder(bind(format("a", "English", "text/vtt")));
        assertEquals("fake", decoder.getName());
        assertSame(nativeDecoder.input, decoder.dequeueInputBuffer());
        assertNull(decoder.dequeueOutputBuffer());
        decoder.setPositionUs(900);
        assertEquals(900, nativeDecoder.position);
        decoder.release();
        assertEquals(1, nativeDecoder.releases);
    }

    @Test
    public void sessionKeepsAcceptedDocumentAcrossFlushAndEosUntilTrackEnds() throws Exception {
        SubtitleDecoder decoder = observation.createDecoder(bind(format("a", "English", "text/vtt")));
        decoder.queueInputBuffer(validInput("whole document"));
        drain();
        CaptionSession session = observation.getSession();
        CaptionSession.Snapshot snapshot = session.getSnapshot();
        decoder.flush();
        SubtitleInputBuffer eos = validInput("ignored");
        eos.addFlag(C.BUFFER_FLAG_END_OF_STREAM);
        decoder.queueInputBuffer(eos);
        assertSame(snapshot, session.getSnapshot());
        assertEquals("whole document", session.getCuesAt(snapshot, 1_000_000).get(0).text);
        decoder.release();
        assertNull(session.getSnapshot());
        assertFalse(session.isCurrent(snapshot));
    }

    @Test
    public void outOfOrderSameDecoderCompletionsOnlyAdmitLatestSample() throws Exception {
        SubtitleDecoder decoder = observation.createDecoder(bind(format("a", "English", "text/vtt")));
        decoder.queueInputBuffer(validInput("old"));
        decoder.queueInputBuffer(validInput("new"));
        tasks.get(1).run();
        CaptionSession.Snapshot latest = observation.getSession().getSnapshot();
        tasks.get(0).run();
        tasks.clear();
        assertSame(latest, observation.getSession().getSnapshot());
        assertEquals("new", latest.timeline.getCues().get(0).text);
        assertTrue(reports.get(0).sessionAccepted);
        assertFalse(reports.get(1).sessionAccepted);
    }

    @Test
    public void rejectedOrUncapturedReplacementClearsOldRawAndNativeQueueStillSucceeds() throws Exception {
        SubtitleDecoder decoder = observation.createDecoder(bind(format("a", "English", "text/vtt")));
        decoder.queueInputBuffer(validInput("accepted"));
        drain();
        CaptionSession.Snapshot old = observation.getSession().getSnapshot();
        assertNotNull(old);
        SubtitleInputBuffer malformed = input(VTT.replace("align:start", "align:invalid"));
        decoder.queueInputBuffer(malformed);
        assertSame(malformed, nativeDecoder.lastInput);
        assertNull(observation.getSession().getSnapshot());
        drain();
        assertFalse(observation.getSession().isCurrent(old));
        assertFalse(reports.get(1).sessionAccepted);
        decoder.queueInputBuffer(validInput("accepted again"));
        drain();
        assertNotNull(observation.getSession().getSnapshot());
        SubtitleInputBuffer direct = validInput("uncaptured");
        direct.data = ByteBuffer.allocateDirect(4);
        decoder.queueInputBuffer(direct);
        drain();
        assertNull(observation.getSession().getSnapshot());
        assertSame(direct, nativeDecoder.lastInput);
    }

    @Test
    public void lateOldDecoderFlushAndReleaseCannotEraseNewSourceDocument() throws Exception {
        SubtitleDecoder old = observation.createDecoder(bind(format("a", "English", "text/vtt")));
        old.queueInputBuffer(validInput("old source"));
        Format nextFormat = bind(format("a", "English", "text/vtt"));
        SubtitleDecoder next = observation.createDecoder(nextFormat);
        next.queueInputBuffer(validInput("new source"));
        drain();
        CaptionSession.Snapshot snapshot = observation.getSession().getSnapshot();
        old.flush();
        old.release();
        assertSame(snapshot, observation.getSession().getSnapshot());
        assertEquals("new source", snapshot.timeline.getCues().get(0).text);
        assertEquals(1, reports.size());
    }

    @Test
    public void trackNoneSourceChangeAndCloseClearRetainedRawAndPreventQueuedRepopulation() throws Exception {
        for (int action = 0; action < 3; action++) {
            SubtitleDecoder decoder = observation.createDecoder(bind(format("a", "English", "text/vtt")));
            decoder.queueInputBuffer(validInput("accepted"));
            drain();
            CaptionSession.Snapshot snapshot = observation.getSession().getSnapshot();
            assertNotNull(snapshot);
            decoder.queueInputBuffer(validInput("pending"));
            if (action == 0) observation.clearTrack();
            if (action == 1) observation.clearSource();
            if (action == 2) observation.close();
            drain();
            assertNull(observation.getSession().getSnapshot());
            assertFalse(observation.getSession().isCurrent(snapshot));
        }
    }

    @Test
    public void ambiguousCatalogCanRetainRawWithoutInventingProvenance() throws Exception {
        Format format = format("a", "English", "text/vtt");
        observation.bindSource(info(subtitle("a", "English", "text/vtt", "en", null),
                subtitle("a", "English", "text/vtt", "fr", "asr")), CaptionObservation.Route.DASH_CATALOG,
                Collections.singletonList(format), 90_000);
        observation.createDecoder(format).queueInputBuffer(validInput("unclassified"));
        drain();
        CaptionSession.Snapshot snapshot = observation.getSession().getSnapshot();
        assertNotNull(snapshot);
        assertEquals(2, snapshot.track.candidates);
        assertNull(snapshot.track.source);
    }

    @Test
    public void rejectedExecutorHandoffDoesNotRestoreOldDocumentOrBlockNativeQueue() throws Exception {
        int[] dispatches = {0};
        CaptionObservation local = new CaptionObservation(factory, task -> {
            if (dispatches[0]++ > 0) throw new RejectedExecutionException();
            task.run();
        }, report -> { throw new IllegalStateException("synthetic sink failure"); });
        Format format = format("a", "English", "text/vtt");
        local.bindSource(info(subtitle("a", "English", "text/vtt", "en", null)),
                CaptionObservation.Route.DASH_CATALOG, Collections.singletonList(format), 90_000);
        SubtitleDecoder decoder = local.createDecoder(format);
        decoder.queueInputBuffer(validInput("accepted despite diagnostic sink failure"));
        assertNotNull(local.getSession().getSnapshot());
        SubtitleInputBuffer dropped = validInput("dropped");
        decoder.queueInputBuffer(dropped);
        assertSame(dropped, nativeDecoder.lastInput);
        assertNull(local.getSession().getSnapshot());
    }

    @Test
    public void diagnosticLimitStillAdmitsReplacementWhileEosPreservesAcceptedDocument() throws Exception {
        SubtitleDecoder decoder = observation.createDecoder(bind(format("a", "English", "text/vtt")));
        for (int i = 0; i < 32; i++) {
            decoder.queueInputBuffer(validInput("sample " + i));
            drain();
        }
        CaptionSession.Snapshot snapshot = observation.getSession().getSnapshot();
        assertNotNull(snapshot);
        SubtitleInputBuffer eos = validInput("ignored");
        eos.addFlag(C.BUFFER_FLAG_END_OF_STREAM);
        nativeDecoder.queueAction = eos::clear; // Delegate owns and may reset flags before returning.
        decoder.queueInputBuffer(eos);
        assertSame(snapshot, observation.getSession().getSnapshot());
        decoder.queueInputBuffer(validInput("beyond diagnostic budget"));
        assertNull(observation.getSession().getSnapshot());
        assertEquals(1, tasks.size());
        drain();
        assertEquals("beyond diagnostic budget", observation.getSession().getSnapshot().groups.getText(0));
        decoder.queueInputBuffer(input("malformed replacement after diagnostic budget"));
        assertNull(observation.getSession().getSnapshot());
        drain();
        assertNull(observation.getSession().getSnapshot());
        assertEquals(32, reports.size());
    }

    @Test
    public void encryptedInputInvalidatesPendingRawAndNextPlainInputCanRecover() throws Exception {
        SubtitleDecoder decoder = observation.createDecoder(bind(format("a", "English", "text/vtt")));
        decoder.queueInputBuffer(validInput("pending"));
        SubtitleInputBuffer encrypted = validInput("uncaptured");
        encrypted.addFlag(C.BUFFER_FLAG_ENCRYPTED);
        decoder.queueInputBuffer(encrypted);
        drain();
        assertNull(observation.getSession().getSnapshot());
        assertFalse(reports.get(0).sessionAccepted);
        decoder.queueInputBuffer(validInput("recovered"));
        drain();
        assertEquals("recovered", observation.getSession().getSnapshot().timeline.getCues().get(0).text);
    }

    @Test
    public void lateUncapturedQueueReturnCannotEraseDocumentAdmittedAfterFlush() throws Exception {
        SubtitleDecoder decoder = observation.createDecoder(bind(format("a", "English", "text/vtt")));
        SubtitleInputBuffer encrypted = validInput("old uncaptured input");
        encrypted.addFlag(C.BUFFER_FLAG_ENCRYPTED);
        nativeDecoder.queueAction = () -> {
            decoder.flush();
            try {
                decoder.queueInputBuffer(validInput("after flush"));
            } catch (SubtitleDecoderException e) {
                throw new AssertionError(e);
            }
            drain();
        };
        decoder.queueInputBuffer(encrypted);
        assertEquals("after flush", observation.getSession().getSnapshot().timeline.getCues().get(0).text);
    }

    @Test
    public void nativeSampleBuildsBoundPlanWithoutResplittingOrdinaryPunctuatedCues() throws Exception {
        SubtitleDecoder decoder = observation.createDecoder(bind(format("a", "English", "text/vtt")));
        decoder.queueInputBuffer(input("WEBVTT\n\n00:01.000 --> 00:20.000\nFirst. Second!\n\n" +
                "00:03.000 --> 00:04.000\nFirst. Second!\n"));
        drain();
        CaptionSession.Snapshot snapshot = observation.getSession().getSnapshot();
        assertEquals(2, snapshot.groups.getGroups().size());
        assertEquals("First. Second!", snapshot.groups.getText(0));
        assertEquals("First. Second!", snapshot.groups.getText(1));
        assertEquals(1_000_000, snapshot.groups.getReferences(0).get(0).origin.timingStartUs);
        assertEquals(20_000_000, snapshot.groups.getReferences(0).get(0).origin.timingEndUs);
        assertEquals(1, snapshot.groups.getReferences(1).get(0).origin.sourceIndex);
        assertEquals(Arrays.asList(0, 1), observation.getSession().getGroupsAt(snapshot, 3_000_000));
        assertEquals(Collections.singletonList(0), observation.getSession().getGroupsAt(snapshot, 4_000_000));
        decoder.flush();
        assertSame(snapshot, observation.getSession().getSnapshot());
        observation.clearTrack();
        assertFalse(observation.getSession().isCurrent(snapshot));
        assertNull(observation.getSession().getSnapshot());
    }

    @Test
    public void missingCatalogLabelsStillUseActualManifestFormatAndCurrentWholeDocument() throws Exception {
        Format format = format("unlabelled", "Unknown display label", "text/vtt");
        observation.bindSource(null, CaptionObservation.Route.DASH_CATALOG, Collections.singletonList(format), 90_000);
        observation.setEnhancementEnabled(true);
        observation.createDecoder(format).queueInputBuffer(validInput("usable current document"));
        drain();
        com.liskovsoft.smartyoutubetv2.common.subtitle.acquisition.CaptionInputSession.Selection selection =
                observation.getInputSession().tick(new Object());
        assertNotNull(selection);
        assertNull(selection.textLanguage);
        assertEquals("usable current document", selection.input.timeline.getCues().get(0).text);
        assertEquals(0, reports.get(0).lease.binding.candidates);
        observation.close();
    }

    @Test
    public void actualDashAndSabrQueuesMustPublishThePaintOnCanonicalRangesWithoutConfirmedHints() throws Exception {
        String fixture = rollingFixture();
        String gold = "[token1]\ntoken2 token3 token4 token5 token6 token7 token8\n" +
                "token9 token10 token11 token12 token13 token14";
        for (boolean sabr : new boolean[]{false, true}) {
            CaptionObservation local = new CaptionObservation(factory, tasks::add, reports::add);
            MediaItemFormatInfo metadata = info(originalLookupSubtitle("English", "en",
                    "https://www.youtube.com/api/timedtext?v=synthetic-video&lang=en&kind=asr&fmt=vtt"));
            Format selected;
            if (sabr) {
                SabrManifest manifest = new SabrManifestParser().parse(metadata);
                selected = manifest.periods.get(0).adaptationSets.get(0).representations.get(0).format;
                local.bindSource(metadata, manifest);
            } else {
                DashManifest manifest = new DashManifestParser2().parse(metadata);
                selected = manifest.getPeriod(0).adaptationSets.get(0).representations.get(0).format;
                local.bindSource(metadata, manifest);
            }
            local.setEnhancementEnabled(true);
            SubtitleDecoder decoder = local.createDecoder(selected);
            decoder.queueInputBuffer(input(fixture));
            drain();
            CaptionSession.Snapshot snapshot = local.getSession().getSnapshot();
            assertNotNull(snapshot);
            assertEquals(1, snapshot.transcript.normalizationVersion());
            assertEquals(gold, snapshot.groups.getText(0));
            assertEquals(1, snapshot.groups.getGroups().size());
            assertEquals(6, snapshot.timeline.getCues().size());
            assertEquals(16, snapshot.timeline.getCues().stream().mapToInt(cue -> cue.runs.size()).sum());
            assertArrayEquals(fixture.getBytes(Charset.forName("UTF-8")), snapshot.timeline.copySource());
            com.liskovsoft.smartyoutubetv2.common.subtitle.acquisition.CaptionInputSession.Selection effective =
                    local.getInputSession().tick(new Object());
            assertNotNull(effective);
            assertEquals(gold, effective.input.groups.getText(0));
            assertEquals(1, effective.input.transcript.normalizationVersion());
            CaptionObservation.Report report = reports.get(reports.size() - 1);
            assertEquals(1, report.timeline.normalization);
            assertEquals(16, report.timeline.semanticAtoms);
            assertEquals(37, report.timeline.semanticReferences);
            assertEquals(21, report.timeline.semanticEchoes);
            assertEquals(254, report.timeline.sourceCodePoints);
            assertEquals(104, report.timeline.semanticCodePoints);
            assertFalse(report.safeLine().contains("token2"));
            local.close();
        }
    }

    private static String rollingFixture() throws Exception {
        try (java.io.InputStream source = CaptionObservationTest.class.getResourceAsStream("/subtitle/rolling_settings_synthetic.vtt");
             java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
            assertNotNull(source);
            byte[] buffer = new byte[1024];
            int count;
            while ((count = source.read(buffer)) != -1) bytes.write(buffer, 0, count);
            return new String(bytes.toByteArray(), Charset.forName("UTF-8"));
        }
    }

    @Test
    public void aPublishedPlanOnTheSameRawMustReachEffectiveInputWithoutAnotherNativeQueue() throws Exception {
        SubtitleDecoder decoder = observation.createDecoder(bind(format("a", "English", "text/vtt")));
        observation.setEnhancementEnabled(true);
        decoder.queueInputBuffer(validInput("red blue"));
        drain();
        CaptionSession.Snapshot base = observation.getSession().getSnapshot();
        Object window = new Object();
        com.liskovsoft.smartyoutubetv2.common.subtitle.acquisition.CaptionInputSession.Selection before =
                observation.getInputSession().tick(window);
        assertNotNull(before);
        assertEquals("red blue", before.input.groups.getText(0));
        SegmentationProposal proposal = observation.getSession().prepareSegmentation(base, 100,
                new SegmentationProposal.Limits(16, 64, 1024, 16));
        SegmentationProposal.Decision decision = proposal.evaluate(Arrays.asList(
                new SegmentationProposal.Unit(Arrays.asList("p0", "p1"), "red "),
                new SegmentationProposal.Unit(Collections.singletonList("p2"), "blue")),
                SegmentationProposal.TextPolicy.EXACT, SegmentationProposal.Completion.COMPLETE);
        assertTrue(observation.getSession().publishSegmentation(observation.getSession().beginAnalysis(base),
                proposal, decision, new SemanticGroupPlanner.Limits(16, 64, 128)));
        com.liskovsoft.smartyoutubetv2.common.subtitle.acquisition.CaptionInputSession.Selection after =
                observation.getInputSession().tick(window);
        assertNotNull("Raw identity alone cannot leave effective input stuck on the old analysis", after);
        assertSame(base.timeline, after.input.timeline);
        assertEquals("red ", after.input.groups.getText(0));
        assertEquals("blue", after.input.groups.getText(1));
        assertSame(before.input.track.cacheScope, after.input.track.cacheScope);
        assertFalse(observation.getInputSession().isCurrent(before));
        assertFalse(observation.getInputSession().deliver(before, ignored -> fail("Old Selection must not revive")));
        assertTrue(observation.getInputSession().isCurrent(after));
        assertEquals(1, nativeDecoder.queues);
        assertEquals(1, reports.size());
        observation.close();
    }

    @Test
    public void downloadedScalarSettingsKeepTheOriginalSnapshotWhenEnhancementIsEnabledLater() throws Exception {
        SubtitleDecoder decoder = observation.createDecoder(bind(format("a", "English", "text/vtt")));
        SubtitleInputBuffer input = input(VTT);
        decoder.queueInputBuffer(input);
        drain();
        CaptionSession.Snapshot original = observation.getSession().getSnapshot();
        assertNotNull(original);
        assertEquals(com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.RawTimeline.Origin.DOCUMENT, original.timeline.getOrigin());
        assertEquals("start", original.timeline.getCues().get(0).webvttSettings.get("align"));
        assertNull(observation.getInputSession().tick(new Object()));
        Arrays.fill(input.data.array(), (byte) '!');
        observation.setEnhancementEnabled(true);
        drain();
        observation.ensureEnhancementInput();
        assertSame(original, observation.getSession().getSnapshot());
        assertEquals("secret-payload", original.groups.getText(0));
        assertEquals(1, nativeDecoder.queues);
        assertEquals(1, reports.size());
        assertFalse(reports.get(0).replayed);
        assertTrue(tasks.isEmpty());
        observation.close();
    }

    @Test
    public void enableAfterDownloadReprojectsOwnedBytesWithoutAnotherNativeQueueOrSampleCount() throws Exception {
        SubtitleDecoder decoder = observation.createDecoder(bind(format("a", "English", "text/vtt")));
        SubtitleInputBuffer input = input(NATIVE_ONLY_VTT);
        decoder.queueInputBuffer(input);
        drain();
        assertNull(observation.getSession().getSnapshot());
        Arrays.fill(input.data.array(), (byte) '!');
        observation.setEnhancementEnabled(true);
        drain();
        assertEquals(1, nativeDecoder.queues);
        assertSame(input, nativeDecoder.lastInput);
        assertEquals(com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.RawTimeline.Origin.NATIVE_DISPLAY,
                observation.getSession().getSnapshot().timeline.getOrigin());
        assertEquals("secret-payload", observation.getSession().getSnapshot().timeline.getCues().get(0).text);
        assertTrue(reports.get(1).replayed);
        assertEquals(reports.get(0).sample, reports.get(1).sample);
        assertFalse(reports.get(1).safeLine().contains("secret-payload"));
        observation.ensureEnhancementInput();
        assertTrue(tasks.isEmpty());
        observation.close();
    }

    @Test
    public void deferredProjectionCannotRepopulateAfterNoneAndMalformedInputIsNotRetriedPerTick() throws Exception {
        SubtitleDecoder decoder = observation.createDecoder(bind(format("a", "English", "text/vtt")));
        decoder.queueInputBuffer(input(NATIVE_ONLY_VTT));
        drain();
        observation.setEnhancementEnabled(true);
        observation.clearTrack();
        drain();
        assertNull(observation.getSession().getSnapshot());
        decoder = observation.createDecoder(bind(format("a", "English", "text/vtt")));
        decoder.queueInputBuffer(input("bad document secret"));
        drain();
        assertNull(observation.getSession().getSnapshot());
        for (int i = 0; i < 5; i++) observation.ensureEnhancementInput();
        assertTrue(tasks.isEmpty());
        observation.close();
    }

    @Test
    public void actualDashAndSabrManifestBindingsCarryNativeFactoryAndCookieToOriginalRead() throws Exception {
        for (boolean sabr : new boolean[]{false, true}) {
            CaptionObservation local = new CaptionObservation(factory, tasks::add, reports::add);
            String base = "https://www.youtube.com/api/timedtext?v=synthetic-video&lang=en&fmt=vtt";
            MediaSubtitle translated = originalLookupSubtitle("Chinese", "zh-Hans", base + "&tlang=zh-Hans");
            MediaSubtitle original = originalLookupSubtitle("English", "en", base + "&signature=fixture");
            MediaItemFormatInfo delegate = info(translated, original);
            MediaItemFormatInfo metadata = (MediaItemFormatInfo) Proxy.newProxyInstance(MediaItemFormatInfo.class.getClassLoader(),
                    new Class<?>[]{MediaItemFormatInfo.class}, (proxy, method, args) -> method.getName().equals("getVisitorCookie") ?
                            "VISITOR=fixture" : method.invoke(delegate, args));
            java.util.concurrent.atomic.AtomicReference<com.google.android.exoplayer2.upstream.DataSpec> opened = new java.util.concurrent.atomic.AtomicReference<>();
            com.google.android.exoplayer2.upstream.DataSource.Factory transport = () -> new com.google.android.exoplayer2.upstream.DataSource() {
                final com.google.android.exoplayer2.upstream.ByteArrayDataSource source = new com.google.android.exoplayer2.upstream.ByteArrayDataSource(
                        "WEBVTT\n\n00:01.000 --> 00:04.000\nsource original\n".getBytes(Charset.forName("UTF-8")));
                public void addTransferListener(com.google.android.exoplayer2.upstream.TransferListener listener) { source.addTransferListener(listener); }
                public long open(com.google.android.exoplayer2.upstream.DataSpec spec) throws java.io.IOException { opened.set(spec); return source.open(spec); }
                public int read(byte[] bytes, int start, int length) throws java.io.IOException { return source.read(bytes, start, length); }
                public android.net.Uri getUri() { return source.getUri(); }
                public void close() throws java.io.IOException { source.close(); }
            };
            Format selected;
            if (sabr) {
                SabrManifest manifest = new SabrManifestParser().parse(metadata);
                selected = manifest.periods.get(0).adaptationSets.get(0).representations.get(0).format;
                local.bindSource(metadata, manifest, transport);
            } else {
                DashManifest manifest = new DashManifestParser2().parse(metadata);
                selected = manifest.getPeriod(0).adaptationSets.get(0).representations.get(0).format;
                local.bindSource(metadata, manifest, transport);
            }
            local.setEnhancementEnabled(true);
            SubtitleDecoder decoder = local.createDecoder(selected);
            decoder.queueInputBuffer(validInput("current translation"));
            drain();
            Object window = new Object();
            com.liskovsoft.smartyoutubetv2.common.subtitle.acquisition.CaptionInputSession.Selection selection;
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            do {
                selection = local.getInputSession().tick(window);
                if (selection != null && selection.usingFetchedOriginal) break;
                Thread.sleep(1);
            } while (System.nanoTime() < deadline);
            assertNotNull(selection);
            assertTrue(selection.usingFetchedOriginal);
            assertEquals("source original", selection.input.timeline.getCuesAt(1_000_000).get(0).text);
            assertEquals("VISITOR=fixture", opened.get().httpRequestHeaders.get("Cookie"));
            assertEquals(base + "&signature=fixture", opened.get().uri.toString());
            local.close();
        }
    }

    private static MediaSubtitle originalLookupSubtitle(String name, String language, String url) {
        return new MediaSubtitle() {
            public String getBaseUrl() { return url; }
            public boolean isTranslatable() { return true; }
            public String getLanguageCode() { return language; }
            public String getVssId() { return "a.en"; }
            public String getName() { return name; }
            public String getMimeType() { return "text/vtt"; }
            public String getCodecs() { return null; }
            public String getType() { return null; }
        };
    }

    @Test
    public void safeUiDescriptorRequiresExactCurrentManifestFormatIdentity() {
        Format actual = bind(format("a", "English", "text/vtt"));
        assertNotNull(observation.sourceFor(actual));
        assertEquals("en", observation.sourceFor(actual).getCatalogLanguage());
        assertNull(observation.sourceFor(format("a", "English", "text/vtt")));
        observation.clearSource();
        assertNull(observation.sourceFor(actual));
    }

    private static SubtitleInputBuffer validInput(String text) {
        return input("WEBVTT\n\n00:01.000 --> 00:20.000\n" + text + "\n");
    }

    private Format bind(Format format) {
        observation.bindSource(info(subtitle("a", "English", "text/vtt", "en", null)),
                CaptionObservation.Route.DASH_CATALOG, Collections.singletonList(format), 90_000);
        return format;
    }

    private void drain() {
        for (Runnable task : new ArrayList<>(tasks)) task.run();
        tasks.clear();
    }

    static Format format(String id, String name, String mime) {
        return Format.createTextSampleFormat(id, mime, 0, name);
    }

    static SubtitleInputBuffer input(String text) {
        byte[] data = text.getBytes(Charset.forName("UTF-8"));
        SubtitleInputBuffer input = new SubtitleInputBuffer();
        input.ensureSpaceForWrite(data.length);
        input.data.put(data);
        input.flip();
        return input;
    }

    static MediaItemFormatInfo info(MediaSubtitle... subtitles) {
        return (MediaItemFormatInfo) Proxy.newProxyInstance(MediaItemFormatInfo.class.getClassLoader(),
                new Class<?>[]{MediaItemFormatInfo.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getSubtitles": return Arrays.asList(subtitles);
                        case "getAdaptiveFormats": return Collections.emptyList();
                        case "getLengthSeconds": return "90";
                        case "getVideoId": return "synthetic-video";
                        case "getServerAbrStreamingUrl": return "https://example.invalid/synthetic";
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

    static MediaSubtitle subtitle(String id, String name, String mime, String language, String type) {
        return new MediaSubtitle() {
            public String getBaseUrl() { return "https://example.invalid/caption?lang=" + language + "&signature=synthetic"; }
            public boolean isTranslatable() { return false; }
            public String getLanguageCode() { return language; }
            public String getVssId() { return id; }
            public String getName() { return name; }
            public String getMimeType() { return mime; }
            public String getCodecs() { return null; }
            public String getType() { return type; }
        };
    }

    private static final class FakeDecoder implements SubtitleDecoder {
        final SubtitleInputBuffer input = new SubtitleInputBuffer();
        SubtitleInputBuffer lastInput;
        SubtitleDecoderException queueFailure;
        SubtitleDecoderException outputFailure;
        Runnable queueAction;
        int queues, flushes, releases;
        long position;
        public String getName() { return "fake"; }
        public SubtitleInputBuffer dequeueInputBuffer() { return input; }
        public SubtitleOutputBuffer dequeueOutputBuffer() throws SubtitleDecoderException {
            if (outputFailure != null) throw outputFailure;
            return null;
        }
        public void queueInputBuffer(SubtitleInputBuffer input) throws SubtitleDecoderException {
            if (queueFailure != null) throw queueFailure;
            lastInput = input;
            queues++;
            if (queueAction != null) {
                Runnable action = queueAction;
                queueAction = null;
                action.run();
            }
        }
        public void setPositionUs(long positionUs) { position = positionUs; }
        public void flush() { flushes++; }
        public void release() { releases++; }
    }
}
