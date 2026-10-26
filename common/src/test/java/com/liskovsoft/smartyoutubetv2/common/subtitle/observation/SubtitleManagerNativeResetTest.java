package com.liskovsoft.smartyoutubetv2.common.subtitle.observation;

import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.text.Cue;
import com.google.android.exoplayer2.text.SubtitleDecoder;
import com.google.android.exoplayer2.text.SubtitleDecoderFactory;
import com.google.android.exoplayer2.ui.SubtitleView;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.other.SubtitleManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
