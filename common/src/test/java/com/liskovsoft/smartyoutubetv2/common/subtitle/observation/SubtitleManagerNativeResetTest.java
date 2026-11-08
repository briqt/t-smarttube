package com.liskovsoft.smartyoutubetv2.common.subtitle.observation;

import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.text.Cue;
import com.google.android.exoplayer2.text.SubtitleDecoder;
import com.google.android.exoplayer2.text.SubtitleDecoderFactory;
import com.google.android.exoplayer2.ui.SubtitleView;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.ExoMediaSourceFactory;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.other.SubtitleManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 27)
public class SubtitleManagerNativeResetTest {
    @Test
    public void sameTextAfterNativeClearIsVisibleAgain() throws Exception {
        SubtitleView view = view();
        SubtitleManager manager = new SubtitleManager(view);
        try {
            manager.onCues(cue("alpha"));
            assertEquals("alpha", text(view));
            manager.onCues(Collections.emptyList());
            assertEquals("", text(view));
            manager.onCues(cue("alpha"));
            assertEquals("alpha", text(view));
        } finally { manager.release(); }
    }

    @Test
    public void consecutiveDuplicateStillUsesNativeDeduplication() throws Exception {
        SubtitleView view = view();
        SubtitleManager manager = new SubtitleManager(view);
        try {
            manager.onCues(cue("alpha"));
            manager.onCues(cue("alpha"));
            assertEquals("", text(view));
        } finally { manager.release(); }
    }

    @Test
    public void nonemptyMarkerDoesNotResetRollingText() throws Exception {
        SubtitleView view = view();
        SubtitleManager manager = new SubtitleManager(view);
        try {
            manager.onCues(cue("alpha "));
            assertEquals("", text(view));
            manager.onCues(cue("alpha beta"));
            assertEquals("beta", text(view));
        } finally { manager.release(); }
    }

    @Test
    public void nativeClearEndsMarkerHistory() throws Exception {
        SubtitleView view = view();
        SubtitleManager manager = new SubtitleManager(view);
        try {
            manager.onCues(cue("alpha "));
            manager.onCues(Collections.emptyList());
            manager.onCues(cue("alpha beta"));
            assertEquals("alpha beta", text(view));
        } finally { manager.release(); }
    }

    @Test
    public void ttmlRollingLinesKeepExistingBehavior() throws Exception {
        SubtitleView view = view();
        SubtitleManager manager = new SubtitleManager(view);
        try {
            manager.onCues(cue("alpha\nbeta"));
            assertEquals("alpha\nbeta", text(view));
            manager.onCues(cue("beta\ngamma"));
            assertEquals("gamma", text(view));
        } finally { manager.release(); }
    }

    @Test
    public void rejectedOldClearCannotResetCurrentFormatter() throws Exception {
        SubtitleView view = view();
        SubtitleManager manager = new SubtitleManager(view);
        CaptionObservation observation = observation();
        SubtitleDecoder decoder = bind(observation);
        manager.attachCaptionBridge(observation, () -> 0);
        CaptionCueDispatcher current = new CaptionCueDispatcher(observation, manager, null);
        try {
            current.onCues(cue("alpha "));
            List<List<Cue>> queued = new ArrayList<>();
            new CaptionCueDispatcher(observation, queued::add, null).onCues(Collections.emptyList());
            assertTrue(observation.isOutputValid(CaptionCueDispatcher.stampOf(queued.get(0))));
            observation.invalidateOutput(false);
            assertFalse(observation.isOutputValid(CaptionCueDispatcher.stampOf(queued.get(0))));
            manager.onCues(queued.get(0));
            current.onCues(cue("alpha beta"));
            assertEquals("beta", text(view));
        } finally { manager.release(); decoder.release(); observation.close(); }
    }

    @Test
    public void acceptedStampedClearAllowsSameTextAgain() throws Exception {
        SubtitleView view = view();
        SubtitleManager manager = new SubtitleManager(view);
        CaptionObservation observation = observation();
        SubtitleDecoder decoder = bind(observation);
        manager.attachCaptionBridge(observation, () -> 0);
        CaptionCueDispatcher current = new CaptionCueDispatcher(observation, manager, null);
        try {
            current.onCues(cue("alpha"));
            assertEquals("alpha", text(view));
            current.onCues(Collections.emptyList());
            assertEquals("", text(view));
            current.onCues(cue("alpha"));
            assertEquals("alpha", text(view));
        } finally { manager.release(); decoder.release(); observation.close(); }
    }

    @Test
    public void playerListenerForwardsSeekLifecycleAndDetachesOnRelease() throws Exception {
        SubtitleView view = view();
        SubtitleManager manager = new SubtitleManager(view);
        CaptionObservation observation = observation();
        SubtitleDecoder decoder = bind(observation);
        List<Player.EventListener> listeners = new ArrayList<>();
        Player player = (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> {
                    if ("addListener".equals(method.getName())) {
                        listeners.add((Player.EventListener) args[0]);
                        return null;
                    }
                    if ("removeListener".equals(method.getName())) {
                        listeners.remove(args[0]);
                        return null;
                    }
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == long.class) return 0L;
                    return null;
                });
        manager.attachCaptionBridge(observation, player, () -> 0);
        try {
            assertEquals(1, listeners.size());
            Player.EventListener listener = listeners.get(0);
            List<List<Cue>> queued = new ArrayList<>();
            new CaptionCueDispatcher(observation, queued::add, null).onCues(cue("alpha"));
            CaptionObservation.OutputStamp stampBefore = CaptionCueDispatcher.stampOf(queued.get(0));
            assertTrue(observation.isOutputValid(stampBefore));

            listener.onPositionDiscontinuity(Player.DISCONTINUITY_REASON_PERIOD_TRANSITION);
            assertTrue("Non-seek discontinuity must not trigger seek invalidation", observation.isOutputValid(stampBefore));

            listener.onPositionDiscontinuity(Player.DISCONTINUITY_REASON_SEEK);
            assertFalse("Seek discontinuity must invalidate pre-seek output", observation.isOutputValid(stampBefore));
            listener.onSeekProcessed();
        } finally {
            manager.release();
            assertTrue("Release must detach player listener", listeners.isEmpty());
            decoder.release();
            observation.close();
        }
    }

    @Test
    public void mediaSourceFactoryResetsStandaloneSourcesAndPreservesDashAcrossMergedHls() {
        ExoMediaSourceFactory factory = new ExoMediaSourceFactory(RuntimeEnvironment.getApplication());
        CaptionObservation observation = observation();
        AtomicInteger resets = new AtomicInteger();
        AtomicReference<CaptionObservation> bound = new AtomicReference<>(observation);
        factory.setCaptionObservation(observation, () -> {
            resets.incrementAndGet();
            bound.get().clearSource();
        });
        try {
            factory.fromDashManifestUrl("https://example.invalid/manifest.mpd");
            assertEquals(1, resets.get());

            factory.fromHlsPlaylist("https://example.invalid/live.m3u8");
            assertEquals(2, resets.get());

            factory.fromUrlList(Collections.singletonList("https://example.invalid/video.mp4"));
            assertEquals(3, resets.get());

            String mpd = "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" mediaPresentationDuration=\"PT10S\">"
                    + "<Period duration=\"PT10S\"/></MPD>";
            factory.fromDashManifest(new java.io.ByteArrayInputStream(mpd.getBytes("UTF-8")));
            assertEquals("Dash manifest must reset previous source", 4, resets.get());

            factory.fromHlsPlaylist("https://example.invalid/merged-audio.m3u8");
            assertEquals("Paired HLS playlist in openMerged must preserve Dash caption source", 4, resets.get());

            factory.fromHlsPlaylist("https://example.invalid/standalone-after-merge.m3u8");
            assertEquals("Subsequent standalone HLS playlist must reset caption source", 5, resets.get());

            factory.fromDashManifest(new java.io.ByteArrayInputStream(mpd.getBytes("UTF-8")));
            assertEquals(6, resets.get());
            factory.onMediaSourcePrepared();
            factory.fromHlsPlaylist("https://example.invalid/standalone-after-dash.m3u8");
            assertEquals("onMediaSourcePrepared must clear pending Dash flag so standalone HLS resets", 7, resets.get());
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        } finally {
            factory.setCaptionObservation(null);
            observation.close();
        }
    }

    private static SubtitleView view() { return new SubtitleView(RuntimeEnvironment.getApplication()); }
    private static List<Cue> cue(String text) { return Collections.singletonList(new Cue(text)); }
    private static CaptionObservation observation() {
        return new CaptionObservation(SubtitleDecoderFactory.DEFAULT, Runnable::run, report -> { });
    }
    private static SubtitleDecoder bind(CaptionObservation observation) {
        Format format = Format.createTextSampleFormat("same-text", "text/vtt", 0, "en");
        observation.bindSource(null, CaptionObservation.Route.DASH_CATALOG, Collections.singletonList(format), 90_000);
        return observation.createDecoder(format);
    }
    @SuppressWarnings("unchecked")
    private static String text(SubtitleView view) throws Exception {
        Field field = SubtitleView.class.getDeclaredField("cues");
        field.setAccessible(true);
        List<Cue> cues = (List<Cue>) field.get(view);
        if (cues == null || cues.isEmpty()) return "";
        assertEquals(1, cues.size());
        return String.valueOf(cues.get(0).text);
    }
}
