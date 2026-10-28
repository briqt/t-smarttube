package com.liskovsoft.smartyoutubetv2.common.subtitle.presentation;

import android.content.pm.ApplicationInfo;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.text.Cue;
import com.google.android.exoplayer2.text.SubtitleDecoder;
import com.google.android.exoplayer2.text.SubtitleInputBuffer;
import com.google.android.exoplayer2.text.SubtitleOutputBuffer;
import com.google.android.exoplayer2.ui.SubtitleView;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.other.SubtitleManager;
import com.liskovsoft.smartyoutubetv2.common.subtitle.observation.CaptionObservation;
import com.liskovsoft.smartyoutubetv2.common.subtitle.observation.CaptionCueDispatcher;
import com.liskovsoft.smartyoutubetv2.common.subtitle.session.CaptionSession;
import com.liskovsoft.smartyoutubetv2.common.subtitle.acquisition.CaptionInputSession;
import com.liskovsoft.smartyoutubetv2.common.subtitle.acquisition.NativeCaptionLoader;
import com.liskovsoft.smartyoutubetv2.common.subtitle.acquisition.NativeCaptionRequest;
import com.liskovsoft.smartyoutubetv2.common.subtitle.source.CaptionSource;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.RawTimeline;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.SegmentationProposal;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.SemanticGroupPlanner;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.mediaserviceinterfaces.data.MediaSubtitle;
import com.google.android.exoplayer2.source.dash.manifest.DashManifest;
import com.google.android.exoplayer2.source.dash.manifest.DashManifestParser2;
import com.google.android.exoplayer2.upstream.ByteArrayDataSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.TransferListener;
import org.robolectric.util.ReflectionHelpers;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.ChatCompletionAdapter;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.ChatTranslationClient;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.TranslationBatch;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.TranslationCoordinator;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.TranslationCoordinatorTest;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 27)
public class CaptionOutputBridgeTest {
    @Test
    public void settingsKeepOriginalLongClockButCannotMixCroppedNativeWindowsOrReviveAClear() throws Exception {
        Harness h = new Harness("WEBVTT\n\n00:01.000 --> 00:20.000 align:start position:0%\nsource\n\n" +
                "00:12.000 --> 00:14.000 align:end position:100%\nnext\n");
        try {
            CaptionSession.Snapshot raw = h.observation.getSession().getSnapshot();
            assertEquals(RawTimeline.Origin.DOCUMENT, raw.timeline.getOrigin());
            assertEquals(20_000_000, raw.timeline.getCues().get(0).endUs);
            SubtitleOutputBuffer output = nativeOutput(h);
            try {
                h.enable();
                emitNative(h, output.getCues(1_000_000));
                h.worker.drain(); h.ui.pulse();
                assertEquals("source\n译:source", h.last.get(0).text.toString());
                assertEquals(1, h.calls.get());
                h.positionMs = 12500;
                List<Cue> next = output.getCues(12_500_000);
                assertEquals(1, next.size()); // First native cue was cropped after ten seconds.
                assertEquals("next", next.get(0).text.toString());
                assertEquals(2, raw.groups.getGroups().size());
                emitNative(h, next);
                h.ui.pulse();
                assertSame("Two active source groups cannot be guessed onto one native cue", next, h.last);
                h.positionMs = 15000;
                assertTrue(output.getCues(15_000_000).isEmpty());
                emitNative(h, Collections.emptyList());
                h.worker.drain(); h.ui.pulse();
                assertTrue(h.last.isEmpty());
                assertEquals(1, h.calls.get());
            } finally { output.release(); }
        } finally { h.close(); }
    }

    @Test
    public void nativeCueGapKeepsInflightPrefetchButNeverRendersTheLateResponse() throws Exception {
        Harness h = new Harness("WEBVTT\n\n00:01.000 --> 00:03.000\nfirst\n\n" +
                "00:04.000 --> 00:06.000\nsecond\n");
        try {
            assertEquals(2, h.observation.getSession().getSnapshot().groups.getGroups().size());
            h.enable();
            emitNative(h, Collections.singletonList(new Cue("first")));
            h.beforeResponse.set(() -> {
                h.positionMs = 3500;
                emitNative(h, Collections.emptyList());
                assertTrue(h.last.isEmpty());
            });
            h.worker.drain();
            h.ui.pulse();
            assertTrue("A late result must not revive captions during a native gap", h.last.isEmpty());
            h.positionMs = 4000;
            emitNative(h, Collections.singletonList(new Cue("second")));
            assertTrue("The prefetched second cue must not need another request", h.worker.tasks.isEmpty());
            assertEquals("second\n译:second", h.last.get(0).text.toString());
            assertEquals(1, h.calls.get());
        } finally { h.close(); }
    }

    @Test
    public void overlappingSettingsDoNotAssignTwoSourceGroupsToOneNativeRollupOrMultipleCues() throws Exception {
        for (boolean positioned : new boolean[]{false, true}) {
            String settings = positioned ? "align:start position:0%" : "align:start";
            Harness h = new Harness("WEBVTT\n\n00:01.000 --> 00:04.000 " + settings + "\nsource\n\n" +
                    "00:02.000 --> 00:05.000 " + settings + "\nother\n");
            try {
                SubtitleOutputBuffer output = nativeOutput(h);
                try {
                    h.positionMs = 2500;
                    h.enable();
                    List<Cue> nativeCues = output.getCues(2_500_000);
                    assertEquals(positioned ? 2 : 1, nativeCues.size());
                    emitNative(h, nativeCues);
                    h.worker.drain(); h.ui.pulse();
                    assertSame(nativeCues, h.last);
                    assertEquals(1, h.calls.get());
                } finally { output.release(); }
            } finally { h.close(); }
        }
    }

    @Test
    public void disabledSettingsInputKeepsTheRealNativeFormatterAndRollingSuppressionUnchanged() throws Exception {
        Harness h = new Harness("WEBVTT\n\n00:01.000 --> 00:04.000 align:start position:0%\nsource\n");
        SubtitleView enhancedView = new SubtitleView(RuntimeEnvironment.getApplication());
        SubtitleView controlView = new SubtitleView(RuntimeEnvironment.getApplication());
        SubtitleManager enhanced = new SubtitleManager(enhancedView), control = new SubtitleManager(controlView);
        enhanced.attachCaptionBridge(h.observation, () -> h.positionMs);
        try {
            assertEquals("0%", h.observation.getSession().getSnapshot().timeline.getCues().get(0).webvttSettings.get("position"));
            for (String text : new String[]{"source", "source", "source ", "source next", "next\nlast", "last\nnew"}) {
                List<Cue> cues = Collections.singletonList(new Cue(text));
                control.onCues(cues);
                new CaptionCueDispatcher(h.observation, enhanced, null).onCues(cues);
                List<Cue> actual = ReflectionHelpers.getField(enhancedView, "cues");
                List<Cue> expected = ReflectionHelpers.getField(controlView, "cues");
                assertEquals(expected.size(), actual.size());
                for (int i = 0; i < actual.size(); i++) {
                    assertEquals(expected.get(i).text.toString(), actual.get(i).text.toString());
                    assertEquals(expected.get(i).textAlignment, actual.get(i).textAlignment);
                    assertEquals(expected.get(i).position, actual.get(i).position, 0);
                    assertEquals(expected.get(i).size, actual.get(i).size, 0);
                }
            }
            new CaptionCueDispatcher(h.observation, enhanced, null).onCues(Collections.emptyList());
            assertTrue(((List<?>) ReflectionHelpers.getField(enhancedView, "cues")).isEmpty());
            assertEquals(0, h.calls.get());
            assertTrue(h.worker.tasks.isEmpty());
        } finally { enhanced.release(); control.release(); h.close(); }
    }

    private static SubtitleOutputBuffer nativeOutput(Harness h) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        SubtitleOutputBuffer output;
        while ((output = h.decoder.dequeueOutputBuffer()) == null && System.nanoTime() < until) Thread.sleep(1);
        assertNotNull(output);
        return output;
    }

    private static void emitNative(Harness h, List<Cue> nativeCues) {
        new CaptionCueDispatcher(h.observation, cues -> h.bridge.onNativeCues(cues, nativeCues), null).onCues(nativeCues);
    }

    @Test
    public void paintOnGoldTravelsThroughProductionInputStrictWireRealFormatterAndVisibleView() throws Exception {
        String a = "[token1]\n";
        String b = "token2 token3 token4 token5 token6 token7 token8\n";
        String c = "token9 token10 token11 token12 token13 token14";
        String fixture;
        try (java.io.InputStream input = CaptionOutputBridgeTest.class.getResourceAsStream("/subtitle/rolling_settings_synthetic.vtt");
             java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
            assertNotNull(input);
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            fixture = new String(bytes.toByteArray(), Charset.forName("UTF-8"));
        }
        List<String> sent = new ArrayList<>();
        Harness h = new Harness(false, fixture, (target, source) -> {
            assertEquals("zh-Hans", target);
            assertTrue("A complete source unit must match the prewritten gold table", a.equals(source) || b.equals(source) || c.equals(source));
            sent.add(source);
            return a.equals(source) ? "提示一" : b.equals(source) ? "第二至八项" : "第九至十四项";
        }, true);
        h.view = new SubtitleView(RuntimeEnvironment.getApplication());
        SubtitleManager manager = new SubtitleManager(h.view);
        ReflectionHelpers.setField(manager, "mCaptionBridge", h.bridge); // Inject transport/UI dependencies, never a source Plan.
        try {
            CaptionSession session = h.observation.getSession();
            CaptionSession.Snapshot base = session.getSnapshot();
            assertEquals(1, base.transcript.normalizationVersion());
            assertEquals(a + b + c, base.groups.getText(0));
            SegmentationProposal proposal = session.prepareSegmentation(base, 100,
                    new SegmentationProposal.Limits(128, 256, 1024, 16));
            List<SegmentationProposal.Unit> units = new ArrayList<>();
            List<String> refs = new ArrayList<>();
            int[] ends = {9, 58, 104};
            String[] originals = {a, b, c};
            int offset = 0, unit = 0;
            for (SegmentationProposal.Piece piece : proposal.getPieces()) {
                refs.add(piece.id);
                offset += piece.text.length();
                assertTrue(offset <= ends[unit]);
                if (offset == ends[unit]) { units.add(new SegmentationProposal.Unit(refs, originals[unit++])); refs = new ArrayList<>(); }
            }
            assertEquals(3, units.size());
            SegmentationProposal.Decision decision = proposal.evaluate(units, SegmentationProposal.TextPolicy.EXACT,
                    SegmentationProposal.Completion.COMPLETE);
            assertTrue(session.publishSegmentation(session.beginAnalysis(base), proposal, decision,
                    new SemanticGroupPlanner.Limits(16, 128, 256)));
            SubtitleOutputBuffer output = nativeOutput(h);
            try {
                h.positionMs = 6040;
                h.enable();
                long[] positions = {6040, 6049, 6050, 7480, 7489, 7490, 10400, 10409, 10410};
                boolean[] events = {true, false, true, true, false, true, true, false, true};
                String lineB = "token2 token3 token4 token5 token6 token7 token8";
                String lineC = "token9 token10 token11 token12 token13 token14";
                String[] visible = {"[token1]\n提示一", "[token1]\n提示一", "[token1]\n" + lineB + "\n提示一\n第二至八项",
                        lineB + "\n第二至八项", lineB + "\n第二至八项", lineB + "\n" + lineC + "\n第二至八项\n第九至十四项",
                        lineC + "\n第九至十四项", lineC + "\n第九至十四项", ""};
                for (int i = 0; i < positions.length; i++) {
                    h.positionMs = positions[i];
                    if (events[i]) new CaptionCueDispatcher(h.observation, manager, null).onCues(output.getCues(positions[i] * 1000));
                    h.worker.drain(); h.ui.pulse();
                    if (visible[i].isEmpty()) assertTrue(h.last.isEmpty());
                    else assertOneVisibleCue(h, visible[i]);
                }
                assertEquals(Arrays.asList(a, b, c), sent);
                assertEquals("Display holds and frame refreshes must not repay the three canonical source units", 1, h.calls.get());
            } finally { output.release(); }
        } finally { manager.release(); h.close(); }
    }

    @Test
    public void publishedCoarseGroupsBothReachTheWireAndOneVisibleNativeCueWithoutRepayment() throws Exception {
        List<String> sent = new ArrayList<>();
        Harness h = new Harness("WEBVTT\n\n00:01.000 --> 00:05.000\nred blue\n", (target, source) -> {
            assertEquals("zh-Hans", target);
            assertTrue("Wire source must match the independent gold table", "red ".equals(source) || "blue".equals(source));
            sent.add(source);
            return "red ".equals(source) ? "红" : "蓝";
        });
        try {
            h.view = new SubtitleView(RuntimeEnvironment.getApplication());
            publishCoarseSplit(h);
            SubtitleOutputBuffer output = nativeOutput(h);
            try {
                List<Cue> nativeCues = output.getCues(1_000_000);
                h.enable();
                emitNative(h, nativeCues);
                h.worker.drain(); h.ui.pulse();
                assertEquals(Arrays.asList("red ", "blue"), sent);
                assertEquals(1, h.calls.get());
                assertEquals(2, h.coordinator.prepareFrame().items.size());
                assertEquals("red ", h.coordinator.prepareFrame().items.get(0).original);
                assertEquals("blue", h.coordinator.prepareFrame().items.get(1).original);
                assertOneVisibleCue(h, "red \nblue\n红\n蓝");
                h.bridge.setDisplay(NativeCueComposer.Mode.BILINGUAL, NativeCueComposer.Order.TRANSLATION_FIRST);
                assertOneVisibleCue(h, "红\n蓝\nred \nblue");
                h.bridge.setDisplay(NativeCueComposer.Mode.TRANSLATION, NativeCueComposer.Order.TRANSLATION_FIRST);
                assertOneVisibleCue(h, "红\n蓝");
                h.bridge.setDisplay(NativeCueComposer.Mode.ORIGINAL, NativeCueComposer.Order.ORIGINAL_FIRST);
                assertOneVisibleCue(h, "red blue");
                h.bridge.setDisplay(NativeCueComposer.Mode.BILINGUAL, NativeCueComposer.Order.ORIGINAL_FIRST);
                h.bridge.setEnabled(false);
                assertOneVisibleCue(h, "red blue");
                h.bridge.setEnabled(true);
                assertOneVisibleCue(h, "red \nblue\n红\n蓝");
                assertEquals(1, h.calls.get());
                h.positionMs = 5000;
                emitNative(h, output.getCues(5_000_000));
                h.ui.pulse();
                assertTrue(h.last.isEmpty());
                assertTrue(((List<?>) ReflectionHelpers.getField(h.view, "cues")).isEmpty());
            } finally { output.release(); }
        } finally { h.close(); }
    }

    @Test
    public void emptyOrWhitespaceRawTextCannotReviveACompletedMappedBundle() throws Exception {
        Harness h = new Harness("WEBVTT\n\n00:01.000 --> 00:05.000\nred blue\n", (target, source) ->
                "red ".equals(source) ? "红" : "蓝");
        try {
            publishCoarseSplit(h);
            SubtitleOutputBuffer output = nativeOutput(h);
            try {
                h.enable();
                emitNative(h, output.getCues(1_000_000));
                h.worker.drain(); h.ui.pulse();
                assertEquals(1, h.calls.get());
                for (String text : new String[]{"", " ", "\n", " "}) {
                    List<Cue> raw = Collections.singletonList(new Cue(text));
                    emitNative(h, raw);
                    h.ui.pulse();
                    assertSame("A blank native input is not a visible source window", raw, h.last);
                    assertEquals(text, h.last.get(0).text.toString());
                }
                assertEquals(1, h.calls.get());
            } finally { output.release(); }
        } finally { h.close(); }
    }

    @Test
    public void incompleteCoarseBundleDoesNotShowOnlyTheTranslatedPrefix() throws Exception {
        Harness h = new Harness("WEBVTT\n\n00:01.000 --> 00:05.000\nred blue\n", (target, source) -> {
            assertTrue("red ".equals(source) || "blue".equals(source));
            return "red ".equals(source) ? "红" : "";
        });
        try {
            h.view = new SubtitleView(RuntimeEnvironment.getApplication());
            publishCoarseSplit(h);
            SubtitleOutputBuffer output = nativeOutput(h);
            try {
                h.enable();
                List<Cue> nativeCues = output.getCues(1_000_000);
                emitNative(h, nativeCues);
                h.worker.drain(); h.ui.pulse();
                assertEquals(2, h.coordinator.prepareFrame().items.size());
                assertNotNull(h.coordinator.prepareFrame().items.get(0).translation);
                assertNull(h.coordinator.prepareFrame().items.get(1).translation);
                assertOneVisibleCue(h, "red blue");
            } finally { output.release(); }
        } finally { h.close(); }
    }

    private static void publishCoarseSplit(Harness h) {
        CaptionSession session = h.observation.getSession();
        CaptionSession.Snapshot base = session.getSnapshot();
        SegmentationProposal proposal = session.prepareSegmentation(base, 100,
                new SegmentationProposal.Limits(16, 64, 1024, 16));
        SegmentationProposal.Decision decision = proposal.evaluate(Arrays.asList(
                new SegmentationProposal.Unit(Arrays.asList("p0", "p1"), "red "),
                new SegmentationProposal.Unit(Collections.singletonList("p2"), "blue")),
                SegmentationProposal.TextPolicy.EXACT, SegmentationProposal.Completion.COMPLETE);
        assertTrue(session.publishSegmentation(session.beginAnalysis(base), proposal, decision,
                new SemanticGroupPlanner.Limits(16, 64, 128)));
        assertEquals(2, session.getSnapshot().groups.getGroups().size());
    }

    private static void assertOneVisibleCue(Harness h, String expected) {
        assertEquals(1, h.last.size());
        assertEquals(expected, h.last.get(0).text.toString());
        List<Cue> actual = ReflectionHelpers.getField(h.view, "cues");
        assertEquals(1, actual.size());
        assertEquals(expected, actual.get(0).text.toString());
    }

    @Test
    public void noConfigurationPreservesExactNormalizedNativeListWithoutRequestsOrUiLoop() throws Exception {
        Harness h = new Harness();
        try {
            h.emit();
            assertSame(h.normalized, h.last);
            assertEquals(0, h.calls.get());
            assertTrue(h.worker.tasks.isEmpty());
            assertNull(h.ui.pending);
        } finally { h.close(); }
    }

    @Test
    public void realCaptureAndStampedOutputReachOneBilingualCueThroughFakeTransport() throws Exception {
        Harness h = new Harness();
        try {
            h.enable();
            h.emit();
            assertSame(h.normalized, h.last);
            h.worker.drain();
            h.ui.pulse();
            assertEquals(1, h.last.size());
            assertEquals("source\n译:source", h.last.get(0).text.toString());
            assertEquals(1, h.calls.get());
            assertNull(h.last.get(0).textAlignment); // Native formatter/global layout, no new screen placement.
        } finally { h.close(); }
    }

    @Test
    public void displaySwitchAndDisableReenableAreImmediateAndDoNotRefetchCompletedText() throws Exception {
        Harness h = new Harness();
        try {
            h.enable(); h.emit(); h.worker.drain(); h.ui.pulse();
            h.bridge.setDisplay(NativeCueComposer.Mode.BILINGUAL, NativeCueComposer.Order.TRANSLATION_FIRST);
            assertEquals("译:source\nsource", h.last.get(0).text.toString());
            h.bridge.setDisplay(NativeCueComposer.Mode.TRANSLATION, NativeCueComposer.Order.TRANSLATION_FIRST);
            assertEquals("译:source", h.last.get(0).text.toString());
            h.bridge.setEnabled(false);
            assertSame(h.normalized, h.last);
            assertNull(h.ui.pending);
            h.bridge.setEnabled(true);
            assertEquals("译:source", h.last.get(0).text.toString());
            assertEquals(1, h.calls.get());
        } finally { h.close(); }
    }

    @Test
    public void sourceResetRejectsLateConfigurationAndNativeNoneNeverRevivesBilingualText() throws Exception {
        Harness h = new Harness();
        try {
            h.enable(); h.emit(); h.worker.drain(); h.ui.pulse();
            long pending = h.bridge.beginConfiguration();
            h.bridge.resetSource();
            assertTrue(h.last.isEmpty());
            assertFalse(h.bridge.configure(pending, h.profile(), true, NativeCueComposer.Mode.BILINGUAL, NativeCueComposer.Order.ORIGINAL_FIRST));
            h.emit();
            assertSame(h.normalized, h.last);
            h.enable(); h.emit(); h.worker.drain(); h.ui.pulse();
            h.observation.clearTrack();
            h.bridge.onNativeCues(Collections.emptyList(), Collections.emptyList());
            h.ui.pulse();
            assertTrue(h.last.isEmpty());
        } finally { h.close(); }
    }

    @Test
    public void queuedOldNativeFrameCannotEnhanceAnotherVideoEvenWithIdenticalText() throws Exception {
        Harness h = new Harness();
        try {
            AtomicReference<List<Cue>> old = new AtomicReference<>();
            new CaptionCueDispatcher(h.observation, old::set, null).onCues(h.raw);
            h.bridge.resetSource();
            h.bind();
            h.enable();
            h.bridge.onNativeCues(old.get(), h.normalized);
            assertTrue(h.last.isEmpty());
            assertTrue(h.worker.tasks.isEmpty());
            h.emit(); h.worker.drain(); h.ui.pulse();
            assertEquals("source\n译:source", h.last.get(0).text.toString());
        } finally { h.close(); }
    }

    @Test
    public void hiddenViewAndSeekDropOldPresentationWithoutClearingStillValidResults() throws Exception {
        Harness h = new Harness();
        try {
            h.enable(); h.emit(); h.worker.drain(); h.ui.pulse();
            h.bridge.setVisible(false);
            assertSame(h.normalized, h.last);
            h.bridge.setVisible(true);
            assertEquals("source\n译:source", h.last.get(0).text.toString());
            h.bridge.seek();
            assertTrue(h.last.isEmpty());
            h.ui.pulse();
            assertTrue(h.last.isEmpty());
            h.decoder.flush();
            h.emit();
            assertEquals("source\n译:source", h.last.get(0).text.toString());
            assertEquals(1, h.calls.get());
        } finally { h.close(); }
    }

    @Test
    public void nativeNoOpSeekAckRestoresStillCurrentOutputAndDoesNotLeaveRendererBlocked() throws Exception {
        Harness h = new Harness();
        try {
            h.enable(); h.emit(); h.worker.drain(); h.ui.pulse();
            h.bridge.seek();
            h.bridge.seek(); // Native coalesces pending seek acknowledgements.
            AtomicReference<List<Cue>> inFlight = new AtomicReference<>();
            new CaptionCueDispatcher(h.observation, inFlight::set, null).onCues(h.raw);
            assertFalse(h.bridge.acceptsNativeCues(inFlight.get()));
            h.bridge.seekProcessed(); // No decoder.flush: native optimized the seek to the same position.
            assertEquals("source\n译:source", h.last.get(0).text.toString());
            assertFalse(h.bridge.acceptsNativeCues(inFlight.get()));
            h.emit();
            assertEquals("source\n译:source", h.last.get(0).text.toString());
            assertEquals(1, h.calls.get());
        } finally { h.close(); }
    }

    @Test
    public void seekAckAfterRealFlushOrSourceResetCannotRestoreTheSavedPreSeekCue() throws Exception {
        for (boolean reset : new boolean[]{false, true}) {
            Harness h = new Harness();
            try {
                h.enable(); h.emit(); h.worker.drain(); h.ui.pulse();
                h.bridge.seek();
                if (reset) { h.bridge.resetSource(); h.bind(); h.enable(); }
                else h.decoder.flush();
                h.bridge.seekProcessed();
                assertTrue(h.last.isEmpty());
                h.emit(); h.worker.drain(); h.ui.pulse();
                assertEquals("source\n译:source", h.last.get(0).text.toString());
            } finally { h.close(); }
        }
    }

    @Test
    public void actualUiQueueCannotRestorePreSeekCueAndNewOutputStillReusesTranslation() throws Exception {
        org.robolectric.shadows.ShadowLooper looper = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper());
        looper.pause();
        Harness h = new Harness();
        try {
            h.enable(); h.emit(); h.worker.drain(); h.ui.pulse();
            CaptionCueDispatcher dispatcher = new CaptionCueDispatcher(h.observation,
                    cues -> h.bridge.onNativeCues(cues, h.normalized), android.os.Looper.getMainLooper());
            Thread renderer = new Thread(() -> dispatcher.onCues(h.raw));
            renderer.start(); renderer.join();
            h.bridge.seek();
            assertTrue(h.last.isEmpty());
            looper.idle();
            h.ui.pulse();
            assertTrue(h.last.isEmpty());
            h.decoder.flush();
            h.emit();
            assertEquals("source\n译:source", h.last.get(0).text.toString());
            assertEquals(1, h.calls.get());
        } finally { h.close(); }
    }

    @Test
    public void rendererOutputAfterUiSeekButBeforeDecoderFlushCannotAcquireNewPositionIdentity() throws Exception {
        org.robolectric.shadows.ShadowLooper looper = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper());
        looper.pause();
        Harness h = new Harness();
        try {
            h.enable(); h.emit(); h.worker.drain(); h.ui.pulse();
            h.bridge.seek();
            CaptionCueDispatcher dispatcher = new CaptionCueDispatcher(h.observation,
                    cues -> h.bridge.onNativeCues(cues, h.normalized), android.os.Looper.getMainLooper());
            Thread renderer = new Thread(() -> dispatcher.onCues(h.raw));
            renderer.start(); renderer.join();
            h.decoder.flush(); // The renderer has now processed the native seek, after posting old output.
            looper.idle();
            h.ui.pulse();
            assertTrue(h.last.isEmpty());
            h.emit();
            assertEquals("source\n译:source", h.last.get(0).text.toString());
            assertEquals(1, h.calls.get());
        } finally { h.close(); }
    }

    @Test
    public void actualUiQueueDropsOldSourceTextAndClearEvenWhenAiIsNotConfigured() throws Exception {
        org.robolectric.shadows.ShadowLooper looper = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper());
        looper.pause();
        Harness h = new Harness();
        try {
            CaptionCueDispatcher dispatcher = new CaptionCueDispatcher(h.observation,
                    cues -> h.bridge.onNativeCues(cues, cues.isEmpty() ? Collections.emptyList() : h.normalized), android.os.Looper.getMainLooper());
            Thread renderer = new Thread(() -> { dispatcher.onCues(h.raw); dispatcher.onCues(Collections.emptyList()); });
            renderer.start(); renderer.join();
            h.bridge.resetSource();
            h.bind();
            h.emit();
            assertSame(h.normalized, h.last);
            looper.idle();
            assertSame(h.normalized, h.last);
            assertEquals(0, h.calls.get());
        } finally { h.close(); }
    }

    @Test
    public void nativeClearPostedBeforeFlushOrReleaseStillClearsInsteadOfLeavingOldText() throws Exception {
        org.robolectric.shadows.ShadowLooper looper = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper());
        looper.pause();
        for (boolean release : new boolean[]{false, true}) {
            Harness h = new Harness();
            try {
                h.enable(); h.emit(); h.worker.drain(); h.ui.pulse();
                CaptionCueDispatcher dispatcher = new CaptionCueDispatcher(h.observation,
                        cues -> h.bridge.onNativeCues(cues, Collections.emptyList()), android.os.Looper.getMainLooper());
                Thread renderer = new Thread(() -> dispatcher.onCues(Collections.emptyList()));
                renderer.start(); renderer.join();
                if (release) { h.decoder.release(); h.decoder = null; }
                else h.decoder.flush();
                looper.idle();
                h.ui.pulse();
                assertTrue(h.last.isEmpty());
            } finally { h.close(); }
        }
    }

    @Test
    public void closeDropsQueuedProviderWorkAndRemovesOnlyItsOwnPulse() throws Exception {
        Harness h = new Harness();
        try {
            h.enable(); h.emit();
            h.bridge.close();
            h.worker.drain();
            assertNull(h.ui.pending);
            assertEquals(0, h.calls.get());
            assertFalse(h.bridge.configure(h.bridge.beginConfiguration(), h.profile(), true,
                    NativeCueComposer.Mode.BILINGUAL, NativeCueComposer.Order.ORIGINAL_FIRST));
        } finally { h.close(); }
    }

    @Test
    public void inFlightResponseCannotReviveDisabledBilingualOutputWhenTransportIgnoresCancellation() throws Exception {
        Harness h = new Harness(true);
        try {
            h.enable(); h.emit();
            h.beforeResponse.set(() -> h.bridge.setEnabled(false));
            h.worker.drain(); h.ui.pulse();
            assertEquals(1, h.calls.get());
            assertSame(h.normalized, h.last);
            assertNull(h.ui.pending);
            h.bridge.setEnabled(true);
            assertSame(h.normalized, h.last);
            h.worker.drain(); h.ui.pulse();
            assertEquals(2, h.calls.get());
            assertEquals("source\n译:source", h.last.get(0).text.toString());
        } finally { h.close(); }
    }

    @Test
    public void inFlightResponseCannotRefillNativeNoneAndReselectionNeedsANewResult() throws Exception {
        Harness h = new Harness(true);
        try {
            h.enable(); h.emit();
            h.beforeResponse.set(() -> {
                h.observation.clearTrack();
                h.bridge.onNativeCues(Collections.emptyList(), Collections.emptyList());
            });
            h.worker.drain(); h.ui.pulse();
            assertTrue(h.last.isEmpty());
            assertEquals(1, h.calls.get());
            h.bind(); h.enable(); h.emit();
            assertSame(h.normalized, h.last);
            h.worker.drain(); h.ui.pulse();
            assertEquals(2, h.calls.get());
            assertEquals("source\n译:source", h.last.get(0).text.toString());
        } finally { h.close(); }
    }

    @Test
    public void inFlightProfileChangeCannotDisplayOldResultBeforeReplacementCompletes() throws Exception {
        Harness h = new Harness(true);
        try {
            h.enable(); h.emit();
            h.beforeResponse.set(() -> assertTrue(h.bridge.configure(h.bridge.beginConfiguration(), h.profile("ja"), true,
                    NativeCueComposer.Mode.BILINGUAL, NativeCueComposer.Order.ORIGINAL_FIRST)));
            h.worker.one(); h.ui.pulse();
            assertEquals(1, h.calls.get());
            assertSame(h.normalized, h.last);
            h.worker.drain(); h.ui.pulse();
            assertEquals(2, h.calls.get());
            assertEquals("source\n译:source", h.last.get(0).text.toString());
        } finally { h.close(); }
    }

    @Test
    public void closedBridgeRejectsInFlightResponseEvenIfTransportCompletesSuccessfully() throws Exception {
        Harness h = new Harness(true);
        try {
            h.enable(); h.emit();
            AtomicInteger writesAtClose = new AtomicInteger();
            h.beforeResponse.set(() -> { h.bridge.close(); writesAtClose.set(h.sinkWrites); });
            h.worker.drain(); h.ui.pulse();
            assertEquals(1, h.calls.get());
            assertEquals(writesAtClose.get(), h.sinkWrites);
            assertNull(h.ui.pending);
            assertTrue(h.worker.tasks.isEmpty());
        } finally { h.close(); }
    }

    @Test
    public void completedStringSurvivesNativeNoneAndSameBindingDecoderWithoutReusingOldFrames() throws Exception {
        Harness h = new Harness();
        try {
            h.enable(); h.emit(); h.worker.drain(); h.ui.pulse();
            assertEquals(1, h.calls.get());
            CaptionSession.Snapshot oldNative = h.observation.getSession().getSnapshot();
            CaptionSession.Snapshot oldInput = h.observation.getInputSession().getSession().getSnapshot();
            TranslationCoordinator.Frame oldFrame = h.coordinator.prepareFrame();
            Cue oldBilingual = h.last.get(0);
            int firstWrites = h.sinkWrites;
            assertEquals(0, oldNative.track.candidates); // Unknown provenance is not a cache-disable policy.
            assertNull(oldNative.track.source);
            AtomicReference<List<Cue>> oldOutput = new AtomicReference<>();
            new CaptionCueDispatcher(h.observation, oldOutput::set, null).onCues(h.raw);
            Format boundFormat = h.format;
            int bindings = h.formats;

            h.observation.clearTrack();
            h.bridge.onNativeCues(Collections.emptyList(), Collections.emptyList());
            h.ui.pulse();
            assertTrue(h.last.isEmpty());
            assertFalse(h.observation.getSession().isCurrent(oldNative));
            assertFalse(h.observation.getInputSession().getSession().isCurrent(oldInput));
            assertFalse(h.coordinator.deliver(oldFrame, ignored -> fail()));
            h.reselect(boundFormat, 0); // No bindSource, bridge.configure or replacement connection template.
            assertEquals(bindings, h.formats);
            assertSame(boundFormat, h.format);
            CaptionSession.Snapshot newNative = h.observation.getSession().getSnapshot();
            assertNotSame(oldNative, newNative);
            assertNotSame(oldNative.track, newNative.track);
            assertNotSame(oldNative.timeline, newNative.timeline);
            assertTrue(newNative.transcript.isFor(newNative.timeline));
            assertTrue(newNative.groups.isFor(newNative.transcript));
            assertFalse(h.bridge.acceptsNativeCues(oldOutput.get()));
            h.bridge.onNativeCues(oldOutput.get(), h.normalized);
            assertTrue(h.last.isEmpty());

            h.emit(); h.worker.drain(); h.ui.pulse();
            assertEquals("source\n译:source", h.last.get(0).text.toString());
            assertNotSame(oldBilingual, h.last.get(0));
            assertTrue(h.sinkWrites > firstWrites);
            assertNotSame(oldInput, h.observation.getInputSession().getSession().getSnapshot());
            TranslationCoordinator.Frame fresh = h.coordinator.prepareFrame();
            assertNotSame(oldFrame, fresh);
            assertFalse(h.coordinator.deliver(oldFrame, ignored -> fail("A cache hit cannot revive the old Frame")));
            assertTrue(h.coordinator.deliver(fresh, ignored -> { }));
            assertEquals("A previously completed String must require zero new calls", 1, h.calls.get());
            assertTrue(h.worker.tasks.isEmpty());
        } finally { h.close(); }
    }

    @Test
    public void completedUnknownSourceStringsCannotCrossActualVideoBindingsWithEqualTextAndClock() throws Exception {
        Harness h = new Harness();
        try {
            TranslationCoordinator.Profile connection = h.profile();
            TranslationCoordinator.Frame previous = null;
            for (int video = 0; video < 3; video++) {
                if (video > 0) { h.bridge.resetSource(); h.bind(); }
                h.enable(connection); // Intentionally the same configured connection for every video.
                assertEquals(0, h.observation.getSession().getSnapshot().track.candidates);
                assertNull(h.observation.getSession().getSnapshot().track.source);
                if (previous != null) assertFalse(h.coordinator.deliver(previous, ignored -> fail()));
                h.emit();
                assertSame("Same text/time in another bind must not display the previous translation", h.normalized, h.last);
                h.worker.drain(); h.ui.pulse();
                assertEquals(video + 1, h.calls.get());
                assertEquals("source\n译:source", h.last.get(0).text.toString());
                previous = h.coordinator.prepareFrame();
            }
        } finally { h.close(); }
    }

    @Test
    public void equalTextAndClockFromAnotherActualFormatInTheSameBindingNeedsItsOwnResult() throws Exception {
        Harness h = new Harness();
        try {
            h.bind(true); // Register both formats before either one is translated.
            h.enable(); h.emit(); h.worker.drain(); h.ui.pulse();
            assertEquals(1, h.calls.get());
            TranslationCoordinator.Frame old = h.coordinator.prepareFrame();
            int bindings = h.formats;
            h.reselect(h.alternateFormat, 0);
            assertEquals(bindings, h.formats);
            assertFalse(h.coordinator.deliver(old, ignored -> fail()));
            h.emit();
            assertSame(h.normalized, h.last);
            h.worker.drain(); h.ui.pulse();
            assertEquals(2, h.calls.get());
            assertEquals("source\n译:source", h.last.get(0).text.toString());
        } finally { h.close(); }
    }

    @Test
    public void sameBindingAndFormatWithDifferentEffectiveOffsetCannotReuseCompletedString() throws Exception {
        Harness h = new Harness();
        try {
            h.enable(); h.emit(); h.worker.drain(); h.ui.pulse();
            assertEquals(1, h.calls.get());
            CaptionSession.Snapshot old = h.observation.getSession().getSnapshot();
            h.reselect(h.format, 1_000_000);
            h.positionMs = 2000;
            CaptionSession.Snapshot shifted = h.observation.getSession().getSnapshot();
            assertEquals(old.timeline.getCues().get(0).startUs + 1_000_000, shifted.timeline.getCues().get(0).startUs);
            h.emit();
            assertSame(h.normalized, h.last);
            h.worker.drain(); h.ui.pulse();
            assertEquals(2, h.calls.get());
            assertEquals("source\n译:source", h.last.get(0).text.toString());
        } finally { h.close(); }
    }

    @Test
    public void provenSameLanguageLateOriginalReusesBothCompletedFallbackStringsWithoutTakingCurrentWindow() throws Exception {
        for (OriginalDifference difference : new OriginalDifference[]{OriginalDifference.NONE, OriginalDifference.SETTINGS}) {
            try (LateOriginalHarness h = new LateOriginalHarness(difference)) {
                CaptionInputSession.Selection fallback = h.completeFallbackAndInspectPrefetchedSecond();
                TranslationCoordinator.Frame old = h.coordinator.prepareFrame();
                h.deliverOriginalWithoutChangingWindow(fallback);
                h.emit(12500, "second");
                CaptionInputSession.Selection original = h.selection();
                assertTrue(original.usingFetchedOriginal);
                assertEquals("en", original.textLanguage);
                assertNotSame(fallback.input, original.input);
                assertSame(fallback.nativeSnapshot, original.nativeSnapshot);
                assertFalse(h.inputs.deliver(fallback, ignored -> fail("An old Selection cannot be revived by String reuse")));
                assertFalse(h.coordinator.deliver(old, ignored -> fail("An old Frame cannot become current")));
                if (difference == OriginalDifference.SETTINGS) {
                    assertTrue(fallback.input.timeline.getCues().get(1).webvttSettings.isEmpty());
                    assertEquals("100%", original.input.timeline.getCues().get(1).webvttSettings.get("position"));
                    assertEquals(RawTimeline.Origin.DOCUMENT, original.input.timeline.getOrigin());
                }
                h.worker.drain(); h.ui.pulse();
                assertEquals("second\n译:second", h.last.get(0).text.toString());
                assertEquals("The proved parent has equal text/clock/language/wire; role or layout alone cannot require payment", 1, h.calls.get());
                assertTrue(h.worker.tasks.isEmpty());
            }
        }
    }

    @Test
    public void lateOriginalChangedTextStillTranslatesInsteadOfBorrowingFallbackString() throws Exception {
        assertDifferentOriginalRequests(OriginalDifference.TEXT, "changed second", "en");
    }

    @Test
    public void lateOriginalChangedClockStillTranslatesInsteadOfBorrowingFallbackString() throws Exception {
        assertDifferentOriginalRequests(OriginalDifference.CLOCK, "second", "en");
    }

    @Test
    public void lateOriginalChangedDeclaredLanguageStillTranslatesInsteadOfBorrowingFallbackString() throws Exception {
        assertDifferentOriginalRequests(OriginalDifference.LANGUAGE, "second", "fr");
    }

    @Test
    public void lateOriginalNativeProjectionDoesNotBorrowTheEqualLookingDocumentMapping() throws Exception {
        assertDifferentOriginalRequests(OriginalDifference.PROJECTION, "second", "en");
    }

    @Test
    public void lateOriginalSettingsWithConflictingHeaderStayTranslatableButDoNotBorrowAnEnglishNamespace() throws Exception {
        assertDifferentOriginalRequests(OriginalDifference.HEADER_CONFLICT, "second", null);
    }

    private void assertDifferentOriginalRequests(OriginalDifference difference, String text, String language) throws Exception {
        try (LateOriginalHarness h = new LateOriginalHarness(difference)) {
            CaptionInputSession.Selection fallback = h.completeFallbackAndInspectPrefetchedSecond();
            h.deliverOriginalWithoutChangingWindow(fallback);
            h.emit(12500, "second");
            CaptionInputSession.Selection original = h.selection();
            assertTrue(original.usingFetchedOriginal);
            assertEquals(language, original.textLanguage);
            assertFalse(original.alreadyTargetLanguage("zh"));
            TranslationCoordinator.Frame missing = h.coordinator.prepareFrame();
            assertEquals(text, missing.items.get(0).original);
            assertNull("A changed semantic input must miss the completed cache", missing.items.get(0).translation);
            if (difference == OriginalDifference.CLOCK) {
                assertNotEquals(fallback.input.timeline.getCues().get(1).startUs, original.input.timeline.getCues().get(1).startUs);
            }
            if (difference == OriginalDifference.PROJECTION) {
                assertEquals(RawTimeline.Origin.DOCUMENT, fallback.input.timeline.getOrigin());
                assertEquals(RawTimeline.Origin.NATIVE_DISPLAY, original.input.timeline.getOrigin());
            }
            h.worker.drain(); h.ui.pulse();
            assertEquals("A cache miss cannot disable translation", 2, h.calls.get());
            assertEquals("译:" + text, h.coordinator.prepareFrame().items.get(0).translation);
        }
    }

    @Test
    public void ambiguousMotherAssociationKeepsAiOnButDoesNotMergeAnActualOtherFormatNamespace() throws Exception {
        try (LateOriginalHarness h = new LateOriginalHarness(OriginalDifference.WEAK)) {
            h.completeFallbackAndInspectPrefetchedSecond();
            assertTrue(h.originalWorker.tasks.isEmpty());
            assertEquals(0, h.originalReads.get());
            h.decoder.release();
            h.decoder = h.observation.createDecoder(h.parentFormat);
            h.captureNativeDocument(); // Actual different Format, still the same manifest binding and connection template.
            h.emit(12500, "second");
            assertFalse(h.selection().usingFetchedOriginal);
            assertEquals("en", h.selection().textLanguage);
            assertNull(h.coordinator.prepareFrame().items.get(0).translation);
            h.worker.drain(); h.ui.pulse();
            assertEquals("Weak same-video/vss/lang resemblance must not merge two native Format domains", 2, h.calls.get());
            assertEquals("second\n译:second", h.last.get(0).text.toString());
            assertEquals(0, h.originalReads.get());
        }
    }

    /** Cross-package controller fixture; actual native sample and bridge, synthetic intercepted translation. */
    public static final class AccountPlayback implements AutoCloseable {
        private final Harness h;
        public final CaptionOutputBridge bridge;
        public final TranslationCoordinator coordinator;
        public AccountPlayback() throws Exception {
            h = new Harness();
            bridge = h.bridge;
            coordinator = h.coordinator;
        }
        public Format format() { return h.format; }
        public void emit() { h.emit(); }
        public void complete() { h.worker.drain(); h.ui.pulse(); }
        public int calls() { return h.calls.get(); }
        public List<Cue> current() { return h.last; }
        public void close() { h.close(); }
    }

    private enum OriginalDifference { NONE, TEXT, CLOCK, LANGUAGE, PROJECTION, SETTINGS, HEADER_CONFLICT, WEAK }

    /** Real Observation.bindSource/find/InputSession, with only the original-reader executor/clock controlled. */
    private static final class LateOriginalHarness implements AutoCloseable {
        private static final String NATIVE_DOCUMENT = "WEBVTT\n\n00:01.000 --> 00:04.000\nfirst\n\n00:11.000 --> 00:14.000\nsecond\n";
        final CaptionObservation observation;
        final CaptionInputSession inputs;
        final TranslationCoordinator coordinator;
        final CaptionOutputBridge bridge;
        final Queue originalWorker = new Queue(), worker = new Queue();
        final Ui ui = new Ui();
        final AtomicInteger calls = new AtomicInteger(), originalReads = new AtomicInteger();
        final Format parentFormat;
        SubtitleDecoder decoder;
        long positionMs = 1000;
        List<Cue> last = Collections.emptyList();

        LateOriginalHarness(OriginalDifference difference) throws Exception {
            observation = CaptionObservation.create(RuntimeEnvironment.getApplication());
            observation.getInputSession().close();
            NativeCaptionLoader loader = ReflectionHelpers.callConstructor(NativeCaptionLoader.class,
                    ReflectionHelpers.ClassParameter.from(Executor.class, originalWorker),
                    ReflectionHelpers.ClassParameter.from(Executor.class, (Executor) Runnable::run));
            inputs = new CaptionInputSession(observation.getSession(), () -> 0, loader,
                    new CaptionInputSession.Limits(0, 1, 2, new RawTimeline.Limits(65536, 128, 512)));
            ReflectionHelpers.setField(observation, "mInputs", inputs);
            String parentLanguage = difference == OriginalDifference.LANGUAGE ? "fr" : "en";
            String base = "https://www.youtube.com/api/timedtext?v=synthetic-video&lang=" + parentLanguage + "&fmt=vtt";
            MediaSubtitle current = caption("Current captions", "en", "a." + parentLanguage, base + "&tlang=en");
            MediaSubtitle parent = caption("Parent one", parentLanguage, "a." + parentLanguage, base + "&candidate=one");
            List<MediaSubtitle> catalog = difference == OriginalDifference.WEAK ? Arrays.asList(current, parent,
                    caption("Parent two", parentLanguage, "a." + parentLanguage, base + "&candidate=two")) : Arrays.asList(current, parent);
            MediaItemFormatInfo metadata = captionInfo(catalog);
            DashManifest manifest = new DashManifestParser2().parse(metadata);
            Format selected = manifest.getPeriod(0).adaptationSets.get(0).representations.get(0).format;
            parentFormat = manifest.getPeriod(0).adaptationSets.get(1).representations.get(0).format;
            String originalDocument = difference == OriginalDifference.TEXT ? NATIVE_DOCUMENT.replace("second", "changed second") :
                    difference == OriginalDifference.CLOCK ? NATIVE_DOCUMENT.replace("00:11.000 --> 00:14.000", "00:12.000 --> 00:15.000") :
                    difference == OriginalDifference.PROJECTION ? NATIVE_DOCUMENT.replace("WEBVTT\n\n",
                            "WEBVTT\n\nSTYLE\n::cue { color: lime; }\n\n") :
                    difference == OriginalDifference.HEADER_CONFLICT ? NATIVE_DOCUMENT.replace("WEBVTT\n\n", "WEBVTT\nLanguage: fr\n\n")
                            .replace("--> 00:04.000", "--> 00:04.000 align:start position:0%")
                            .replace("--> 00:14.000", "--> 00:14.000 align:start position:0%") :
                    difference == OriginalDifference.SETTINGS ? NATIVE_DOCUMENT.replace("--> 00:04.000", "--> 00:04.000 align:start position:100%")
                            .replace("--> 00:14.000", "--> 00:14.000 align:start position:100%") : NATIVE_DOCUMENT;
            DataSource.Factory transport = () -> new DataSource() {
                final ByteArrayDataSource source = new ByteArrayDataSource(originalDocument.getBytes(Charset.forName("UTF-8")));
                public void addTransferListener(TransferListener listener) { source.addTransferListener(listener); }
                public long open(DataSpec spec) throws java.io.IOException { originalReads.incrementAndGet(); return source.open(spec); }
                public int read(byte[] bytes, int start, int length) throws java.io.IOException { return source.read(bytes, start, length); }
                public android.net.Uri getUri() { return source.getUri(); }
                public void close() throws java.io.IOException { source.close(); }
            };
            NativeCaptionRequest proof = NativeCaptionRequest.find("synthetic-video", catalog, selected, transport, null, true);
            if (difference == OriginalDifference.WEAK) assertNull(proof);
            else {
                assertNotNull("Fixture must prove a unique actual parent, not inject an original Selection", proof);
                assertEquals(parentLanguage, proof.source.getSourceLanguage());
                assertEquals(CaptionSource.TranslationKind.DIRECT, proof.source.getTranslationKind());
            }
            observation.bindSource(metadata, manifest, transport);
            decoder = observation.createDecoder(selected);
            captureNativeDocument();
            coordinator = TranslationCoordinatorTest.uiFixture(inputs.getSession(), () -> 0, worker, calls);
            bridge = new CaptionOutputBridge(observation, coordinator, cues -> last = cues, () -> positionMs, ui);
            TranslationCoordinator.Profile template = new TranslationCoordinator.Profile(new ChatCompletionAdapter.Config(
                    "https://example.invalid/chat/completions", "fixture-model", 1000, 65536, 65536,
                    ChatCompletionAdapter.TokenField.MAX_TOKENS, ChatCompletionAdapter.Thinking.OMIT),
                    () -> "synthetic-fixture-key", 0, null, "zh", "", new TranslationBatch.Limits(4, 1000, 0, 1000), 1);
            assertTrue(bridge.configure(bridge.beginConfiguration(), template, true,
                    NativeCueComposer.Mode.BILINGUAL, NativeCueComposer.Order.ORIGINAL_FIRST));
        }

        CaptionInputSession.Selection completeFallbackAndInspectPrefetchedSecond() {
            emit(1000, "first");
            CaptionInputSession.Selection fallback = selection();
            assertNotNull(fallback);
            assertFalse(fallback.usingFetchedOriginal);
            assertEquals("en", fallback.textLanguage);
            worker.drain(); ui.pulse();
            assertEquals(1, calls.get());
            assertEquals("first\n译:first", last.get(0).text.toString());
            // Inspect the prefetched value without creating another native window or releasing the delayed GET.
            coordinator.tick(12_500_000, false);
            assertEquals("second", coordinator.prepareFrame().items.get(0).original);
            assertEquals("译:second", coordinator.prepareFrame().items.get(0).translation);
            coordinator.tick(1_000_000, false);
            return fallback;
        }

        void deliverOriginalWithoutChangingWindow(CaptionInputSession.Selection fallback) {
            assertEquals(1, originalWorker.tasks.size());
            originalWorker.drain();
            assertEquals(1, originalReads.get());
            ui.pulse();
            CaptionInputSession.Selection stillCurrent = selection();
            assertFalse(stillCurrent.usingFetchedOriginal);
            assertSame(fallback.input, stillCurrent.input);
            assertTrue(inputs.isCurrent(fallback));
            assertEquals("first\n译:first", last.get(0).text.toString());
            assertEquals(1, calls.get());
        }

        CaptionInputSession.Selection selection() { return inputs.tick(ReflectionHelpers.getField(bridge, "mWindow")); }
        void emit(long position, String text) {
            positionMs = position;
            List<Cue> nativeCues = Collections.singletonList(new Cue(text));
            new CaptionCueDispatcher(observation, cues -> bridge.onNativeCues(cues, nativeCues), null).onCues(nativeCues);
        }
        void captureNativeDocument() throws Exception {
            SubtitleInputBuffer input = decoder.dequeueInputBuffer();
            assertNotNull(input);
            byte[] bytes = NATIVE_DOCUMENT.getBytes(Charset.forName("UTF-8"));
            input.ensureSpaceForWrite(bytes.length);
            input.data.put(bytes);
            input.subsampleOffsetUs = 0;
            input.timeUs = 0;
            input.addFlag(C.BUFFER_FLAG_KEY_FRAME);
            input.flip();
            decoder.queueInputBuffer(input);
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (observation.getSession().getSnapshot() == null && System.nanoTime() < until) Thread.sleep(1);
            assertNotNull(observation.getSession().getSnapshot());
        }
        public void close() { bridge.close(); decoder.release(); observation.close(); }
    }

    private static MediaSubtitle caption(String name, String language, String id, String url) {
        return new MediaSubtitle() {
            public String getBaseUrl() { return url; }
            public boolean isTranslatable() { return true; }
            public String getLanguageCode() { return language; }
            public String getVssId() { return id; }
            public String getName() { return name; }
            public String getMimeType() { return "text/vtt"; }
            public String getCodecs() { return null; }
            public String getType() { return null; }
        };
    }
    private static MediaItemFormatInfo captionInfo(List<MediaSubtitle> subtitles) {
        return (MediaItemFormatInfo) Proxy.newProxyInstance(MediaItemFormatInfo.class.getClassLoader(),
                new Class<?>[]{MediaItemFormatInfo.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getSubtitles": return subtitles;
                        case "getAdaptiveFormats": return Collections.emptyList();
                        case "getLengthSeconds": return "90";
                        case "getVideoId": return "synthetic-video";
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

    private static final class Queue implements Executor {
        final List<Runnable> tasks = new ArrayList<>();
        public void execute(Runnable runnable) { tasks.add(runnable); }
        void one() { tasks.remove(0).run(); }
        void drain() { int n = 0; while (!tasks.isEmpty()) { if (++n > 30) throw new AssertionError("Unbounded work"); tasks.remove(0).run(); } }
    }
    private static final class Ui implements CaptionOutputBridge.Ui {
        Runnable pending;
        public boolean currentThread() { return true; }
        public void later(Runnable task, long delayMs) { assertEquals(250, delayMs); pending = task; }
        public void remove(Runnable task) { if (pending == task) pending = null; }
        void pulse() { Runnable task = pending; pending = null; if (task != null) task.run(); }
    }
    private static final class Harness {
        final CaptionObservation observation;
        final CaptionOutputBridge bridge;
        final TranslationCoordinator coordinator;
        final Queue worker = new Queue();
        final Ui ui = new Ui();
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<Runnable> beforeResponse = new AtomicReference<>();
        final String document;
        final boolean asrCatalog;
        final List<Cue> raw = Collections.singletonList(new Cue("source"));
        final List<Cue> normalized = Collections.singletonList(new Cue("source"));
        List<Cue> last = Collections.emptyList();
        SubtitleView view;
        SubtitleDecoder decoder;
        Format format, alternateFormat;
        long positionMs = 1000;
        int formats, sinkWrites;
        Harness() throws Exception { this(false); }
        Harness(boolean ignoreCancellation) throws Exception {
            this(ignoreCancellation, "WEBVTT\n\n00:01.000 --> 00:04.000\nsource\n");
        }
        Harness(String document) throws Exception { this(false, document); }
        Harness(boolean ignoreCancellation, String document) throws Exception {
            this(ignoreCancellation, document, (target, source) -> "译:" + source);
        }
        Harness(String document, java.util.function.BiFunction<String, String, String> translation) throws Exception {
            this(false, document, translation);
        }
        Harness(boolean ignoreCancellation, String document, java.util.function.BiFunction<String, String, String> translation) throws Exception {
            this(ignoreCancellation, document, translation, false);
        }
        Harness(boolean ignoreCancellation, String document, java.util.function.BiFunction<String, String, String> translation,
                boolean asrCatalog) throws Exception {
            this.document = document;
            this.asrCatalog = asrCatalog;
            RuntimeEnvironment.getApplication().getApplicationInfo().flags |= ApplicationInfo.FLAG_DEBUGGABLE;
            observation = CaptionObservation.create(RuntimeEnvironment.getApplication());
            assertNotNull(observation);
            bind();
            coordinator = TranslationCoordinatorTest.uiFixture(observation.getInputSession().getSession(), () -> 0, worker, calls,
                    beforeResponse, ignoreCancellation, translation);
            bridge = new CaptionOutputBridge(observation, coordinator, cues -> {
                sinkWrites++;
                last = cues;
                if (view != null) view.setCues(cues);
            }, () -> positionMs, ui);
        }
        void bind() throws Exception { bind(false); }
        void bind(boolean includeAlternate) throws Exception {
            if (decoder != null) decoder.release();
            if (asrCatalog) {
                MediaItemFormatInfo metadata = captionInfo(Collections.singletonList(caption("English", "en", "a.en",
                        "https://www.youtube.com/api/timedtext?v=synthetic-video&lang=en&kind=asr&fmt=vtt")));
                DashManifest manifest = new DashManifestParser2().parse(metadata);
                format = manifest.getPeriod(0).adaptationSets.get(0).representations.get(0).format;
                alternateFormat = null;
                observation.bindSource(metadata, manifest);
            } else {
                format = Format.createTextSampleFormat("format-" + ++formats, "text/vtt", 0, "Unlabelled");
                alternateFormat = includeAlternate ? Format.createTextSampleFormat("alternate-" + formats, "text/vtt", 0, "Unlabelled") : null;
                List<Format> selected = includeAlternate ? java.util.Arrays.asList(format, alternateFormat) : Collections.singletonList(format);
                observation.bindSource(null, CaptionObservation.Route.DASH_CATALOG, selected, 10000);
            }
            decoder = observation.createDecoder(format);
            capture(0);
        }
        void reselect(Format selected, long offsetUs) throws Exception {
            if (decoder != null) decoder.release();
            decoder = observation.createDecoder(selected);
            capture(offsetUs);
        }
        void capture(long offsetUs) throws Exception {
            SubtitleInputBuffer input = decoder.dequeueInputBuffer();
            assertNotNull(input);
            byte[] bytes = document.getBytes(Charset.forName("UTF-8"));
            input.ensureSpaceForWrite(bytes.length);
            input.data.put(bytes);
            input.subsampleOffsetUs = offsetUs;
            input.timeUs = 0;
            input.addFlag(C.BUFFER_FLAG_KEY_FRAME);
            input.flip();
            decoder.queueInputBuffer(input);
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (observation.getSession().getSnapshot() == null && System.nanoTime() < until) Thread.sleep(1);
            assertNotNull(observation.getSession().getSnapshot());
        }
        void enable() { enable(profile()); }
        void enable(TranslationCoordinator.Profile configured) { assertTrue(bridge.configure(bridge.beginConfiguration(), configured, true,
                NativeCueComposer.Mode.BILINGUAL, NativeCueComposer.Order.ORIGINAL_FIRST)); }
        TranslationCoordinator.Profile profile() { return profile("zh-Hans"); }
        TranslationCoordinator.Profile profile(String target) {
            ChatCompletionAdapter.Config wire = new ChatCompletionAdapter.Config("https://example.invalid/chat/completions", "fixture-model", 1000,
                    65536, 65536, ChatCompletionAdapter.TokenField.MAX_TOKENS, ChatCompletionAdapter.Thinking.OMIT);
            return new TranslationCoordinator.Profile(wire, () -> "synthetic-fixture-key", 0, null, target, "",
                    new TranslationBatch.Limits(4, 1000, 0, 1000), 1);
        }
        void emit() { new CaptionCueDispatcher(observation, cues -> bridge.onNativeCues(cues, normalized), null).onCues(raw); }
        void close() { bridge.close(); if (decoder != null) decoder.release(); observation.close(); }
    }
}
