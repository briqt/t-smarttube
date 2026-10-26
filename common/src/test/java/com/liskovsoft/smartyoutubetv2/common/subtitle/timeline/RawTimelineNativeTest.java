package com.liskovsoft.smartyoutubetv2.common.subtitle.timeline;

import android.app.Application;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.text.Cue;
import com.google.android.exoplayer2.text.SubtitleDecoder;
import com.google.android.exoplayer2.text.SubtitleDecoderFactory;
import com.google.android.exoplayer2.text.SubtitleInputBuffer;
import com.google.android.exoplayer2.text.SubtitleOutputBuffer;
import com.google.android.exoplayer2.util.MimeTypes;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 27, manifest = Config.NONE, application = Application.class)
public class RawTimelineNativeTest {
    @Test
    public void nativeLongCueClippingAndOverlapAreNotRawEntries() throws Exception {
        byte[] document = bytes("WEBVTT\n\na\n00:01.000 --> 00:15.000\nA\n\n" +
                "b\n00:03.000 --> 00:05.000\nB\n");
        RawTimeline raw = RawWebvttParser.parse(document, 0);
        NativeProjection nativeResult = decode(MimeTypes.TEXT_VTT, document, 0, 0,
                4_000_000L, 12_000_000L);
        assertArrayEquals(new long[]{1_000_000L, 3_000_000L, 5_000_000L, 11_000_000L}, nativeResult.times);
        assertEquals(Arrays.asList("A\nB"), nativeResult.texts.get(0));
        assertTrue(nativeResult.texts.get(1).isEmpty());
        assertEquals(Arrays.asList("A", "B"), active(raw, 4_000_000L));
        assertEquals(Arrays.asList("A"), active(raw, 12_000_000L));
        assertEquals(15_000_000L, raw.getCues().get(0).endUs);
    }

    @Test
    public void nativeTextProjectionIsNotTheEntityPreservingSource() throws Exception {
        byte[] document = bytes("WEBVTT\n\n00:00.000 --> 00:01.000\n" +
                "This is the &lt;fourth&gt; &amp;subtitle.\n");
        RawTimeline raw = RawWebvttParser.parse(document, 0);
        NativeProjection nativeResult = decode(MimeTypes.TEXT_VTT, document, 0, 0, 500_000L);
        assertEquals("This is the <fourth> &subtitle.", raw.getCues().get(0).text);
        // Characterizes this fork; it does not remove or prove the cause of its HTML compatibility patch.
        assertEquals(Arrays.asList("This is the  &subtitle."), nativeResult.texts.get(0));
        assertArrayEquals(document, raw.copySource());
    }

    @Test
    public void untimedDivDoesNotLoseAncestorStartInRawTimeline() throws Exception {
        byte[] document = bytes("<tt xmlns=\"http://www.w3.org/ns/ttml\">" +
                "<body begin=\"1s\"><div><p begin=\"2s\" dur=\"1s\">X</p></div></body></tt>");
        RawTimeline raw = RawTtmlParser.parse(document, 11_000_000L);
        NativeProjection nativeResult = decode(MimeTypes.APPLICATION_TTML, document,
                7_000_000L, 11_000_000L, 13_500_000L, 14_500_000L);
        assertArrayEquals(new long[]{13_000_000L, 14_000_000L}, nativeResult.times);
        assertEquals(Arrays.asList("X"), nativeResult.texts.get(0));
        assertTrue(active(raw, 13_500_000L).isEmpty());
        assertTrue(nativeResult.texts.get(1).isEmpty());
        assertEquals(Arrays.asList("X"), active(raw, 14_500_000L));
        assertEquals(14_000_000L, raw.getCues().get(0).startUs);
        assertEquals(15_000_000L, raw.getCues().get(0).endUs);
    }

    @Test
    public void rawIntervalsIncludeTheParentClippingBoundaryMissingFromNativeEvents() throws Exception {
        byte[] document = ttml("<p begin=\"2s\" dur=\"10s\">X</p>");
        RawTimeline raw = RawTtmlParser.parse(document, 0);
        NativeProjection nativeResult = decode(MimeTypes.APPLICATION_TTML, document, 0, 0,
                5_000_000L, 6_000_000L);
        assertArrayEquals(new long[]{3_000_000L, 13_000_000L}, nativeResult.times);
        assertEquals(Arrays.asList("X"), nativeResult.texts.get(0));
        assertTrue(nativeResult.texts.get(1).isEmpty());
        assertEquals(3_000_000L, raw.getCues().get(0).startUs);
        assertEquals(6_000_000L, raw.getCues().get(0).endUs);
        assertTrue(active(raw, 6_000_000L).isEmpty());
    }

    @Test
    public void rawEndAndDurationUseTheEarlierLimitRatherThanNativeEndPriority() throws Exception {
        byte[] document = ttml("<p begin=\"2s\" end=\"10s\" dur=\"1s\">X</p>");
        RawTimeline raw = RawTtmlParser.parse(document, 0);
        NativeProjection nativeResult = decode(MimeTypes.APPLICATION_TTML, document, 0, 0, 4_500_000L);
        assertArrayEquals(new long[]{3_000_000L, 11_000_000L}, nativeResult.times);
        assertEquals(Arrays.asList("X"), nativeResult.texts.get(0));
        assertEquals(4_000_000L, raw.getCues().get(0).endUs);
        assertTrue(active(raw, 4_500_000L).isEmpty());
    }

    @Test
    public void vttTimestampWindowsDoNotBecomeNativeVisibilityEvents() throws Exception {
        byte[] document = bytes("WEBVTT\n\n00:00.000 --> 00:04.000\nA<00:01.000> B<00:02.000> C\n");
        RawTimeline.Cue raw = RawWebvttParser.parse(document, 0).getCues().get(0);
        NativeProjection nativeResult = decode(MimeTypes.TEXT_VTT, document, 0, 0,
                500_000L, 1_500_000L, 3_000_000L);
        assertArrayEquals(new long[]{0, 4_000_000L}, nativeResult.times);
        assertEquals(3, raw.runs.size());
        for (int i = 0; i < 3; i++) {
            assertEquals(Arrays.asList("A B C"), nativeResult.texts.get(i));
        }
        assertEquals("A B C", raw.textAt(500_000L));
        assertEquals(1_000_000L, raw.runs.get(1).timingStartUs);
        assertEquals(2_000_000L, raw.runs.get(2).timingStartUs);
    }

    @Test
    public void timedTtmlSpanMatchesNativeVisibilityWithoutFlatteningItsClock() throws Exception {
        byte[] document = timedInline("A<span begin=\"1s\" dur=\"1s\">B</span>C");
        RawTimeline.Cue raw = RawTtmlParser.parse(document, 0).getCues().get(0);
        NativeProjection nativeResult = decode(MimeTypes.APPLICATION_TTML, document, 0, 0,
                1_500_000L, 2_500_000L, 3_500_000L);
        assertArrayEquals(new long[]{1_000_000L, 2_000_000L, 3_000_000L, 5_000_000L}, nativeResult.times);
        assertEquals(Arrays.asList("AC"), nativeResult.texts.get(0));
        assertEquals(Arrays.asList("ABC"), nativeResult.texts.get(1));
        assertEquals(Arrays.asList("AC"), nativeResult.texts.get(2));
        assertEquals("AC", raw.textAt(1_500_000L));
        assertEquals("ABC", raw.textAt(2_500_000L));
        assertEquals("AC", raw.textAt(3_500_000L));
    }

    @Test
    public void timedTtmlBreakRetainsItsOwnVisibilityDespiteNativeUnconditionalBreak() throws Exception {
        byte[] document = timedInline("A<br begin=\"1s\" dur=\"1s\"/>C");
        RawTimeline.Cue raw = RawTtmlParser.parse(document, 0).getCues().get(0);
        NativeProjection nativeResult = decode(MimeTypes.APPLICATION_TTML, document, 0, 0,
                1_500_000L, 2_500_000L, 3_500_000L);
        assertEquals("AC", raw.textAt(1_500_000L));
        assertEquals("A\nC", raw.textAt(2_500_000L));
        assertEquals("AC", raw.textAt(3_500_000L));
        for (int i = 0; i < 3; i++) {
            // Existing TtmlNode handles br before checking the br node's own active interval.
            assertEquals(Arrays.asList("A\nC"), nativeResult.texts.get(i));
        }
    }

    private static byte[] timedInline(String text) throws Exception {
        return bytes("<tt xmlns=\"http://www.w3.org/ns/ttml\"><body begin=\"0s\"><div begin=\"0s\">" +
                "<p begin=\"1s\" dur=\"4s\">" + text + "</p></div></body></tt>");
    }

    private static byte[] ttml(String paragraph) throws Exception {
        return bytes("<tt xmlns=\"http://www.w3.org/ns/ttml\"><body begin=\"1s\" end=\"6s\">" +
                "<div begin=\"0s\">" + paragraph + "</div></body></tt>");
    }

    private static NativeProjection decode(String mime, byte[] document, long sampleUs,
                                           long offsetUs, long... probes) throws Exception {
        SubtitleDecoder decoder = SubtitleDecoderFactory.DEFAULT.createDecoder(
                Format.createTextSampleFormat(null, mime, 0, null));
        SubtitleOutputBuffer output = null;
        try {
            SubtitleInputBuffer input = decoder.dequeueInputBuffer();
            assertNotNull(input);
            input.ensureSpaceForWrite(document.length);
            input.data.put(document);
            input.timeUs = sampleUs;
            input.subsampleOffsetUs = offsetUs;
            input.setFlags(C.BUFFER_FLAG_KEY_FRAME);
            input.flip();
            decoder.queueInputBuffer(input);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (output == null && System.nanoTime() < deadline) {
                decoder.setPositionUs(sampleUs);
                output = decoder.dequeueOutputBuffer();
                if (output == null) {
                    Thread.sleep(1);
                }
            }
            assertNotNull("Native subtitle output timed out", output);
            assertFalse(output.isEndOfStream());
            long[] times = new long[output.getEventTimeCount()];
            for (int i = 0; i < times.length; i++) {
                times[i] = output.getEventTime(i);
            }
            List<List<String>> texts = new ArrayList<>();
            for (long probe : probes) {
                List<String> atTime = new ArrayList<>();
                for (Cue cue : output.getCues(probe)) {
                    assertNotNull(cue.text);
                    atTime.add(cue.text.toString());
                }
                texts.add(atTime);
            }
            return new NativeProjection(times, texts);
        } finally {
            if (output != null) {
                output.release();
            }
            decoder.release();
        }
    }

    private static List<String> active(RawTimeline raw, long timeUs) {
        List<String> result = new ArrayList<>();
        for (RawTimeline.Cue cue : raw.getCues()) {
            if (cue.startUs <= timeUs && timeUs < cue.endUs) {
                result.add(cue.text);
            }
        }
        return result;
    }

    private static byte[] bytes(String text) throws Exception {
        return text.getBytes("UTF-8");
    }

    private static final class NativeProjection {
        final long[] times;
        final List<List<String>> texts;

        NativeProjection(long[] times, List<List<String>> texts) {
            this.times = times;
            this.texts = texts;
        }
    }
}
