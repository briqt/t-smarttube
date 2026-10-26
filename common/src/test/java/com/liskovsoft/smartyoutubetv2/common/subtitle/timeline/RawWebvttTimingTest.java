package com.liskovsoft.smartyoutubetv2.common.subtitle.timeline;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

public class RawWebvttTimingTest {
    @Test
    public void timestampWindowsAreNotVisibilityOrMeasuredWordDurations() throws Exception {
        RawTimeline.Cue cue = parse("<00:00.500>Hello <00:01.500>world");
        assertEquals("Hello world", cue.text);
        assertEquals(2, cue.runs.size());
        run(cue, 0, "Hello ", 500_000L, 1_500_000L);
        run(cue, 1, "world", 1_500_000L, 4_000_000L);
        assertEquals("Hello world", cue.textAt(0));
        assertEquals("Hello world", cue.textAt(1_000_000L));
        assertEquals("Hello world", cue.textAt(3_999_999L));
        assertEquals("", cue.textAt(4_000_000L));
    }

    @Test
    public void cueBoundsSupplyOnlyThePrefixAndSuffixWindowEdges() throws Exception {
        RawTimeline.Cue cue = parse("A<00:01.000> B<00:02.000> C");
        assertEquals("A B C", cue.mappingText);
        run(cue, 0, "A", 0, 1_000_000L);
        run(cue, 1, " B", 1_000_000L, 2_000_000L);
        run(cue, 2, " C", 2_000_000L, 4_000_000L);
    }

    @Test
    public void tagsCanCrossTimestampBoundariesWithoutChangingTheTextOrClock() throws Exception {
        RawTimeline.Cue cue = parse("<v Speaker><c.green><b>One<00:01.000>two</b></c>" +
                "<00:02.000><lang en>three</lang></v>");
        assertEquals("Onetwothree", cue.text);
        run(cue, 0, "One", 0, 1_000_000L);
        run(cue, 1, "two", 1_000_000L, 2_000_000L);
        run(cue, 2, "three", 2_000_000L, 4_000_000L);
    }

    @Test
    public void oneTimestampFragmentCanContainMultipleWordsOrNoSpaces() throws Exception {
        RawTimeline.Cue cue = parse("Mr. Smith said<00:01.000>你好，世界！");
        assertEquals(2, cue.runs.size());
        run(cue, 0, "Mr. Smith said", 0, 1_000_000L);
        run(cue, 1, "你好，世界！", 1_000_000L, 4_000_000L);
    }

    @Test
    public void plainCuesRemainCoarseWithoutInventingPerWordTiming() throws Exception {
        RawTimeline.Cue cue = parse("Several words, no word clock.");
        assertEquals(1, cue.runs.size());
        assertEquals(RawTimeline.TimingKind.CUE_INTERVAL, cue.runs.get(0).timingKind);
        assertEquals(0, cue.runs.get(0).timingStartUs);
        assertEquals(4_000_000L, cue.runs.get(0).timingEndUs);
    }

    @Test
    public void entityProducedTimestampSpellingIsNotAnAnnotation() throws Exception {
        RawTimeline.Cue cue = parse("&lt;00:01.000&gt; &amp;lt;00:02.000&amp;gt;");
        assertEquals("<00:01.000> &lt;00:02.000&gt;", cue.text);
        assertEquals(1, cue.runs.size());
        assertEquals(RawTimeline.TimingKind.CUE_INTERVAL, cue.runs.get(0).timingKind);
    }

    @Test
    public void utf16RangesReferToDecodedMappingTextWithoutSplittingEmoji() throws Exception {
        RawTimeline.Cue cue = parse("🙂&lt;x&gt;<00:01.000>文&amp;字");
        assertEquals("🙂<x>文&字", cue.mappingText);
        assertEquals(0, cue.runs.get(0).textStart);
        assertEquals(5, cue.runs.get(0).textEnd);
        assertEquals(5, cue.runs.get(1).textStart);
        assertEquals(8, cue.runs.get(1).textEnd);
        run(cue, 0, "🙂<x>", 0, 1_000_000L);
        run(cue, 1, "文&字", 1_000_000L, 4_000_000L);
    }

    @Test
    public void whitespaceNewlinesAndRepeatedWordsKeepTheirOwnRanges() throws Exception {
        RawTimeline.Cue cue = parse(" A\nA<00:01.000> A ");
        assertEquals(" A\nA A ", cue.mappingText);
        run(cue, 0, " A\nA", 0, 1_000_000L);
        run(cue, 1, " A ", 1_000_000L, 4_000_000L);
        assertEquals(cue.mappingText, cue.textAt(2_000_000L));
    }

    @Test
    public void emptyAnnotationIntervalsDoNotCreateEmptyWords() throws Exception {
        RawTimeline.Cue cue = parse("<00:00.500><00:01.000>A<00:02.000>");
        assertEquals(1, cue.runs.size());
        run(cue, 0, "A", 1_000_000L, 2_000_000L);
        assertEquals("A", cue.textAt(3_000_000L));
        assertEquals(0, parse("<00:00.500><00:01.000>").runs.size());
    }

    @Test
    public void timestampsUseTheDocumentClockThenApplyEffectiveOffsetExactlyOnce() throws Exception {
        String source = "WEBVTT\n\n00:01.000 --> 00:05.000\nA<00:02.000>B\n";
        RawTimeline.Cue cue = RawWebvttParser.parse(bytes(source), 7_000_001L).getCues().get(0);
        assertEquals(8_000_001L, cue.startUs);
        assertEquals(12_000_001L, cue.endUs);
        assertEquals(8_000_001L, cue.runs.get(0).timingStartUs);
        assertEquals(9_000_001L, cue.runs.get(0).timingEndUs);
        assertEquals(9_000_001L, cue.runs.get(1).timingStartUs);
        RawTimeline.Cue negative = RawWebvttParser.parse(bytes(source), -3_000_000L).getCues().get(0);
        assertEquals(-2_000_000L, negative.runs.get(0).timingStartUs);
        assertEquals(-1_000_000L, negative.runs.get(1).timingStartUs);
    }

    @Test
    public void timestampsMustBeStrictlyIncreasingAndStrictlyInsideTheCue() throws Exception {
        for (String text : new String[]{"A<00:00.000>B", "A<00:04.000>B", "A<00:05.000>B",
                "A<00:02.000>B<00:02.000>C", "A<00:02.000>B<00:01.000>C",
                "A<00:60.000>B", "A<00:01.0001>B", "A<0:01.000>B"}) {
            rejected(text);
        }
    }

    @Test
    public void mappingRunBudgetIsInclusiveAndDocumentWide() throws Exception {
        StringBuilder payload = new StringBuilder("X");
        for (int i = 1; i < RawTimeline.MAX_TEXT_RUNS; i++) {
            payload.append(String.format(java.util.Locale.ROOT, "<00:%02d.%03d>X", i / 1000, i % 1000));
        }
        assertEquals(RawTimeline.MAX_TEXT_RUNS, parse(payload.toString()).runs.size());
        rejected(payload + "<00:02.048>X");
        String document = document(payload.toString()) + "\n00:05.000 --> 00:06.000\nextra\n";
        try {
            RawWebvttParser.parse(bytes(document), 0);
            fail("Expected document-wide run budget rejection");
        } catch (IllegalArgumentException expected) {
            assertEquals(RawTimeline.invalid().getMessage(), expected.getMessage());
        }
    }

    @Test
    public void generatedFragmentsReconstructTextAndNeverCreateZeroLengthTiming() throws Exception {
        Random random = new Random(421);
        for (int attempt = 0; attempt < 100; attempt++) {
            StringBuilder payload = new StringBuilder();
            StringBuilder expected = new StringBuilder();
            int count = 1 + random.nextInt(12);
            for (int i = 0; i < count; i++) {
                if (i > 0) {
                    payload.append(String.format(java.util.Locale.ROOT, "<00:00.%03d>", i * 50));
                }
                String text = i % 2 == 0 ? "🙂&lt;字&gt;" : "repeat ";
                payload.append("<b>").append(text).append("</b>");
                expected.append(i % 2 == 0 ? "🙂<字>" : "repeat ");
            }
            RawTimeline.Cue cue = parse(payload.toString());
            assertEquals(expected.toString(), cue.mappingText);
            assertEquals(count, cue.runs.size());
            int cursor = 0;
            for (RawTimeline.TextRun run : cue.runs) {
                assertEquals(cursor, run.textStart);
                assertFalse(run.timingEndUs <= run.timingStartUs);
                cursor = run.textEnd;
            }
            assertEquals(expected.length(), cursor);
        }
    }

    private static void run(RawTimeline.Cue cue, int index, String text, long startUs, long endUs) {
        RawTimeline.TextRun run = cue.runs.get(index);
        assertEquals(text, cue.mappingText.substring(run.textStart, run.textEnd));
        assertEquals(startUs, run.timingStartUs);
        assertEquals(endUs, run.timingEndUs);
        assertEquals(cue.startUs, run.activeStartUs);
        assertEquals(cue.endUs, run.activeEndUs);
        assertEquals(RawTimeline.TimingKind.VTT_TIMESTAMP_WINDOW, run.timingKind);
    }

    private static RawTimeline.Cue parse(String payload) throws Exception {
        return RawWebvttParser.parse(bytes(document(payload)), 0).getCues().get(0);
    }

    private static String document(String payload) {
        return "WEBVTT\n\n00:00.000 --> 00:04.000\n" + payload + "\n";
    }

    private static byte[] bytes(String value) throws Exception {
        return value.getBytes("UTF-8");
    }

    private static void rejected(String payload) throws Exception {
        try {
            parse(payload);
            fail("Expected invalid timestamp fixture rejection");
        } catch (IllegalArgumentException expected) {
            assertEquals(RawTimeline.invalid().getMessage(), expected.getMessage());
            assertNull(expected.getCause());
        }
    }
}
