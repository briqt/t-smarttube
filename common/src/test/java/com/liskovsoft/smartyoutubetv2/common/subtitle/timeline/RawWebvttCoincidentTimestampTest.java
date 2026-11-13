package com.liskovsoft.smartyoutubetv2.common.subtitle.timeline;

import java.nio.charset.Charset;
import org.junit.Test;
import static org.junit.Assert.*;

/** 仅合成文本；重现现场7.4秒相邻时间标记相同、两标记间有2字符的结构。 */
public class RawWebvttCoincidentTimestampTest {
    private static final RawTimeline.Limits LIMITS = new RawTimeline.Limits(65536, 32, 64);

    @Test public void coincidentMarkersShareOneWindowWithoutDiscardingTextOrChangingClock() {
        byte[] source = document("aa<00:07.400><c>bb<00:07.400>cc</c>");
        RawTimeline raw = RawWebvttParser.parse(source, 1_000_000, LIMITS);
        assertArrayEquals(source, raw.copySource());
        RawTimeline.Cue cue = raw.getCues().get(0);
        assertEquals("aabbcc", cue.mappingText);
        assertEquals(8_200_000, cue.startUs);
        assertEquals(10_490_000, cue.endUs);
        assertEquals(2, cue.runs.size());
        RawTimeline.TextRun first = cue.runs.get(0), shared = cue.runs.get(1);
        assertEquals("aa", cue.mappingText.substring(first.textStart, first.textEnd));
        assertEquals("bbcc", cue.mappingText.substring(shared.textStart, shared.textEnd));
        assertEquals(8_200_000, first.timingStartUs);
        assertEquals(8_400_000, first.timingEndUs);
        assertEquals(8_400_000, shared.timingStartUs);
        assertEquals(10_490_000, shared.timingEndUs);
        assertEquals(RawTimeline.TimingKind.VTT_TIMESTAMP_WINDOW, shared.timingKind);
    }

    @Test public void cueStartBoundaryStillFailsClosed() {
        for (String payload : new String[]{"<00:07.200>aa", "aa<00:07.200>bb"}) {
            try { RawWebvttParser.parse(document(payload), 0, LIMITS); fail("不放宽原有cue起点边界"); }
            catch (IllegalArgumentException expected) {
                assertEquals("Unsupported or invalid subtitle document", expected.getMessage());
            }
        }
    }

    @Test public void redundantMarkersDoNotConsumeAdditionalRunBudget() {
        StringBuilder payload = new StringBuilder("aa<00:07.400>");
        for (int i = 0; i < 100; i++) payload.append("b<00:07.400>");
        payload.append('c');
        RawTimeline raw = RawWebvttParser.parse(document(payload.toString()), 0, new RawTimeline.Limits(65536, 1, 2));
        assertEquals(2, raw.getCues().get(0).runs.size());
        assertEquals(103, raw.getCues().get(0).mappingText.length());
    }

    @Test public void backwardsAndOutOfCueMarkersStillFailClosed() {
        for (String payload : new String[] {
                "aa<00:07.400>bb<00:07.300>cc",
                "aa<00:07.100>bb", "aa<00:09.490>bb", "aa<00:09.500>bb"}) {
            try { RawWebvttParser.parse(document(payload), 0, LIMITS); fail("应继续拒绝倒序或越界标记"); }
            catch (IllegalArgumentException expected) {
                assertEquals("Unsupported or invalid subtitle document", expected.getMessage());
            }
        }
    }

    private static byte[] document(String payload) {
        return ("WEBVTT\n\n00:07.200 --> 00:09.490 align:start position:0%\n" + payload + "\n").getBytes(Charset.forName("UTF-8"));
    }
}
