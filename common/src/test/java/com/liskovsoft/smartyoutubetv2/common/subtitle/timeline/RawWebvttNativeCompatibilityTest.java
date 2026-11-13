package com.liskovsoft.smartyoutubetv2.common.subtitle.timeline;

import java.nio.charset.Charset;
import org.junit.Test;
import static org.junit.Assert.*;

/** 合成复现：词标记比cue结束晚10ms，只退化该cue的词时钟，不丢全文。 */
public class RawWebvttNativeCompatibilityTest {
    private static final RawTimeline.Limits LIMITS = new RawTimeline.Limits(65536, 32, 64);

    @Test public void overshootingWordMarkerUsesOriginalCueIntervalAndKeepsSourceBytes() {
        byte[] source = bytes("WEBVTT\n\n01:40.200 --> 01:41.690 align:start position:0%\naa<01:41.500>bbbb<01:41.700>cc\n");
        try { RawWebvttParser.parse(source, 1_000_000, LIMITS); fail("严格入口仍拒绝越界词时钟"); }
        catch (RawWebvttParser.TimestampRejection expected) { }
        RawTimeline raw = RawWebvttParser.parseForNativePlayback(source, 1_000_000, LIMITS);
        assertArrayEquals(source, raw.copySource());
        RawTimeline.Cue cue = raw.getCues().get(0);
        assertEquals("aabbbbcc", cue.mappingText);
        assertEquals(101_200_000, cue.startUs);
        assertEquals(102_690_000, cue.endUs);
        assertEquals("start", cue.webvttSettings.get("align"));
        assertEquals(1, cue.runs.size());
        assertEquals(RawTimeline.TimingKind.CUE_INTERVAL, cue.runs.get(0).timingKind);
        assertEquals(cue.startUs, cue.runs.get(0).timingStartUs);
        assertEquals(cue.endUs, cue.runs.get(0).timingEndUs);
    }

    @Test public void onlyAffectedCueLosesWordClockWhileNeighboursRemainExact() {
        RawTimeline raw = RawWebvttParser.parseForNativePlayback(bytes(mixed()), 0, LIMITS);
        assertEquals(3, raw.getCues().size());
        assertEquals(2, raw.getCues().get(0).runs.size());
        assertEquals(RawTimeline.TimingKind.VTT_TIMESTAMP_WINDOW, raw.getCues().get(0).runs.get(0).timingKind);
        assertEquals("badend", raw.getCues().get(1).mappingText);
        assertEquals(1, raw.getCues().get(1).runs.size());
        assertEquals(RawTimeline.TimingKind.CUE_INTERVAL, raw.getCues().get(1).runs.get(0).timingKind);
        assertEquals(2, raw.getCues().get(2).runs.size());
        assertEquals(RawTimeline.TimingKind.VTT_TIMESTAMP_WINDOW, raw.getCues().get(2).runs.get(0).timingKind);
        assertEquals(5_000_000, raw.getCues().get(2).runs.get(1).timingStartUs);
    }

    @Test public void compatibilityNeverBypassesMarkupClockDeclarationsOrTimestampSyntax() {
        for (String source : new String[] {
                "WEBVTT\nX-TIMESTAMP-MAP=LOCAL:00:00.000,MPEGTS:900000\n\n",
                "WEBVTT\n\n00:01.000 --> 00:02.000\naa<00:02.010><ruby>x</ruby>\n",
                "WEBVTT\n\n00:01.000 --> 00:02.000\naa<00:02.010><b>x\n",
                "WEBVTT\n\n00:01.000 --> 00:02.000\naa<00:02.010><00:01.0000>x\n"}) {
            reject(bytes(source), LIMITS);
        }
    }

    @Test public void originalDocumentCueAndRunBudgetsStillApply() {
        byte[] source = bytes(mixed());
        reject(source, new RawTimeline.Limits(32, 32, 64));
        reject(source, new RawTimeline.Limits(65536, 2, 64));
        reject(source, new RawTimeline.Limits(65536, 32, 4));
    }

    private static String mixed() {
        return "WEBVTT\n\n00:01.000 --> 00:03.000\nhead<00:02.000>tail\n\n" +
                "00:03.100 --> 00:04.000\nbad<00:04.010>end\n\n" +
                "00:04.100 --> 00:06.000\nleft<00:05.000>right\n";
    }
    private static byte[] bytes(String value) { return value.getBytes(Charset.forName("UTF-8")); }
    private static void reject(byte[] source, RawTimeline.Limits limits) {
        try { RawWebvttParser.parseForNativePlayback(source, 0, limits); fail("安全边界仍应拒收"); }
        catch (IllegalArgumentException expected) { }
    }
}
