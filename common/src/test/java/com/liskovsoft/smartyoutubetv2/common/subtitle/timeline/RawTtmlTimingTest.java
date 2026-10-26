package com.liskovsoft.smartyoutubetv2.common.subtitle.timeline;

import org.junit.Test;
import org.xml.sax.ext.DefaultHandler2;
import org.xml.sax.helpers.AttributesImpl;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Offline finite media/par interval contracts, not full TTML, word timing, or native playback. */
public class RawTtmlTimingTest {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final long SECOND = 1_000_000L;

    @Test
    public void nestedAncestorsClipInheritedInlineIntervalsAndOffsetIsAppliedOnce() {
        String xml = "<tt><body begin='1s' dur='12s'><div begin='2s' dur='8s'>"
                + "<p begin='2s' dur='20s'>P<span begin='1s' dur='10s'>A"
                + "<span begin='2s' dur='20s'>B<span>C</span></span>Z</span>Q</p>"
                + "</div></body></tt>";
        RawTimeline.Cue original = only(parse(xml, 0));
        RawTimeline.Cue shifted = only(parse(xml, 7 * SECOND));
        assertEquals(5 * SECOND, original.startUs);
        assertEquals(11 * SECOND, original.endUs);
        assertEquals(12 * SECOND, shifted.startUs);
        assertEquals(18 * SECOND, shifted.endUs);
        assertEquals("PABCZQ", shifted.mappingText);
        assertEquals(6, shifted.runs.size());
        run(shifted, 0, "P", 12 * SECOND, 18 * SECOND, false, false);
        run(shifted, 1, "A", 13 * SECOND, 18 * SECOND, true, false);
        run(shifted, 2, "B", 15 * SECOND, 18 * SECOND, true, false);
        run(shifted, 3, "C", 15 * SECOND, 18 * SECOND, true, false);
        run(shifted, 4, "Z", 13 * SECOND, 18 * SECOND, true, false);
        run(shifted, 5, "Q", 12 * SECOND, 18 * SECOND, false, false);
        for (int i = 0; i < original.runs.size(); i++) {
            assertEquals(original.runs.get(i).activeStartUs + 7 * SECOND,
                    shifted.runs.get(i).activeStartUs);
            assertEquals(original.runs.get(i).activeEndUs + 7 * SECOND,
                    shifted.runs.get(i).activeEndUs);
        }
        assertEquals("", shifted.textAt(12 * SECOND - 1));
        assertEquals("PQ", shifted.textAt(12 * SECOND));
        assertEquals("PAZQ", shifted.textAt(13 * SECOND));
        assertEquals("PABCZQ", shifted.textAt(15 * SECOND));
        assertEquals("", shifted.textAt(18 * SECOND));
    }

    @Test
    public void equalOverlappingAndReverseTimeSiblingsRetainDocumentRangesAndOrdinals() {
        RawTimeline.Cue cue = only(parse(ttml("<p dur='10s' xml:id='cue'>"
                + "<span begin='6s' dur='3s' xml:id='same'>same</span>"
                + "<span begin='1s' dur='3s' xml:id='same'>same</span>"
                + "<span begin='1s' dur='3s' xml:id='same'>same</span>"
                + "<span begin='3s' dur='5s' xml:id='same'>same</span></p>"), 0));
        assertEquals(0, cue.sourceIndex);
        assertEquals("cue", cue.sourceId);
        assertEquals("samesamesamesame", cue.mappingText);
        assertEquals(4, cue.runs.size());
        long[] starts = {6, 1, 1, 3};
        long[] ends = {9, 4, 4, 8};
        for (int i = 0; i < 4; i++) {
            run(cue, i, "same", starts[i] * SECOND, ends[i] * SECOND, true, false);
            assertEquals(i * 4, cue.runs.get(i).textStart);
            assertEquals((i + 1) * 4, cue.runs.get(i).textEnd);
        }
        assertEquals("samesame", cue.textAt(SECOND));
        assertEquals("samesamesame", cue.textAt(3 * SECOND));
        assertEquals("samesame", cue.textAt(6 * SECOND));
        assertEquals("same", cue.textAt(8 * SECOND));
        assertEquals("", cue.textAt(9 * SECOND));
    }

    @Test
    public void cueSourceIndexAndRunOrdinalRemainLocalEvenWithDuplicateIds() {
        RawTimeline timeline = parse("<tt><body dur='5s'><div>"
                + "<p begin='6s' dur='1s'>clipped</p>"
                + "<p dur='5s' xml:id='same'><span begin='1s'>x</span><span begin='1s'>x</span></p>"
                + "<p dur='5s' xml:id='same'><span begin='1s'>x</span><span begin='1s'>x</span></p>"
                + "</div></body></tt>", 0);
        assertEquals(2, timeline.getCues().size());
        for (int i = 0; i < 2; i++) {
            RawTimeline.Cue cue = timeline.getCues().get(i);
            assertEquals(i + 1, cue.sourceIndex);
            assertEquals("same", cue.sourceId);
            assertEquals(2, cue.runs.size());
            assertEquals(0, cue.runs.get(0).textStart);
            assertEquals(1, cue.runs.get(1).textStart);
            assertEquals("xx", cue.mappingText);
        }
    }

    @Test
    public void rawWhitespaceIsKeptUntilAllOrVisibleRunsAreProjected() {
        RawTimeline.Cue cue = only(parse(ttml("<p dur='5s'> A \t"
                + "<span begin='1s' end='3s'>  B \n</span>\t C </p>"), 0));
        assertEquals(" A \t  B \n\t C ", cue.mappingText);
        assertEquals("A B C", cue.text);
        assertEquals(3, cue.runs.size());
        run(cue, 0, " A \t", 0, 5 * SECOND, false, false);
        run(cue, 1, "  B \n", SECOND, 3 * SECOND, true, false);
        run(cue, 2, "\t C ", 0, 5 * SECOND, false, false);
        assertEquals("A C", cue.textAt(0));
        assertEquals("A B C", cue.textAt(SECOND));
        assertEquals("A C", cue.textAt(3 * SECOND));
    }

    @Test
    public void hidingASpanDoesNotLeaveItsWhitespaceOrInventWordSeparators() {
        RawTimeline.Cue cue = only(parse(ttml("<p dur='5s'>left"
                + "<span begin='1s' dur='1s'> middle </span>right</p>"), 0));
        assertEquals("left middle right", cue.text);
        assertEquals("leftright", cue.textAt(0));
        assertEquals("left middle right", cue.textAt(SECOND));
        assertEquals("leftright", cue.textAt(2 * SECOND));

        cue = only(parse(ttml("<p dur='5s'>A<span begin='1s' dur='1s'> </span>"
                + "<span begin='3s' dur='1s'>B</span>C</p>"), 0));
        assertEquals("A BC", cue.text);
        assertEquals("AC", cue.textAt(0));
        assertEquals("A C", cue.textAt(SECOND));
        assertEquals("ABC", cue.textAt(3 * SECOND));
    }

    @Test
    public void xmlLineEndingNormalizationDoesNotCollapseTextLeafWhitespace() {
        RawTimeline.Cue cue = only(parse(ttml("<p dur='5s'> \tA\r\n"
                + "<span begin='1s' dur='1s'>\t B &#xD;</span>\nC </p>"), 0));
        // Literal CRLF is normalized by XML; a numeric CR entity remains a text character.
        assertEquals(" \tA\n\t B \r\nC ", cue.mappingText);
        assertEquals("A B C", cue.text);
        assertEquals("A C", cue.textAt(0));
        assertEquals("A B C", cue.textAt(SECOND));
    }

    @Test
    public void timedBreakHasItsOwnRunAndOnlyBreaksLinesWhileVisible() {
        RawTimeline.Cue cue = only(parse(ttml(
                "<p dur='5s'> A <br begin='1s' dur='1s'/> B </p>"), 0));
        assertEquals(" A \n B ", cue.mappingText);
        assertEquals("A\nB", cue.text);
        assertEquals(3, cue.runs.size());
        run(cue, 0, " A ", 0, 5 * SECOND, false, false);
        run(cue, 1, "\n", SECOND, 2 * SECOND, true, true);
        run(cue, 2, " B ", 0, 5 * SECOND, false, false);
        assertEquals(3, cue.runs.get(1).textStart);
        assertEquals(4, cue.runs.get(1).textEnd);
        assertEquals("A B", cue.textAt(0));
        assertEquals("A\nB", cue.textAt(SECOND));
        assertEquals("A B", cue.textAt(2 * SECOND));
    }

    @Test
    public void untimedLeavesAndBreaksInheritInlineTimingButNotInventedWordDurations() {
        RawTimeline.Cue cue = only(parse(ttml("<p dur='5s'>X"
                + "<span begin='1s' dur='2s'>A<span><br/>B</span></span>Y</p>"), 0));
        assertEquals("XA\nBY", cue.mappingText);
        assertEquals(5, cue.runs.size());
        run(cue, 0, "X", 0, 5 * SECOND, false, false);
        run(cue, 1, "A", SECOND, 3 * SECOND, true, false);
        run(cue, 2, "\n", SECOND, 3 * SECOND, true, true);
        run(cue, 3, "B", SECOND, 3 * SECOND, true, false);
        run(cue, 4, "Y", 0, 5 * SECOND, false, false);
        assertEquals("XY", cue.textAt(0));
        assertEquals("XA\nBY", cue.textAt(SECOND));
        assertEquals("XY", cue.textAt(3 * SECOND));

        cue = only(parse(ttml("<p dur='5s'><span timeContainer='par'>A<br/>B</span></p>"), 0));
        assertEquals(3, cue.runs.size());
        run(cue, 1, "\n", 0, 5 * SECOND, false, true);
    }

    @Test
    public void whollyClippedLeavesAndBreaksAreOmittedButOriginalBytesSurvive() {
        String xml = ttml("<p dur='3s'>A<span begin='4s' dur='2s'>hidden"
                + "<span>nested<br/>hidden</span></span>"
                + "<span begin='3s' dur='1s'>at end</span>B<br begin='3s' dur='1s'/></p>");
        RawTimeline timeline = parse(xml, 0);
        RawTimeline.Cue cue = only(timeline);
        assertEquals("AB", cue.mappingText);
        assertEquals("AB", cue.text);
        assertEquals(2, cue.runs.size());
        run(cue, 0, "A", 0, 3 * SECOND, false, false);
        run(cue, 1, "B", 0, 3 * SECOND, false, false);
        assertArrayEquals(xml.getBytes(UTF8), timeline.copySource());
    }

    @Test
    public void finiteParagraphWithNoVisibleLeavesStillHasAnEmptyMapping() {
        RawTimeline.Cue cue = only(parse(ttml("<p dur='3s'><span begin='3s' dur='1s'>"
                + "hidden<br/></span><span/></p>"), 0));
        assertEquals(0, cue.startUs);
        assertEquals(3 * SECOND, cue.endUs);
        assertEquals("", cue.mappingText);
        assertEquals("", cue.text);
        assertEquals("", cue.textAt(0));
        assertTrue(cue.runs.isEmpty());
    }

    @Test
    public void inlineEndAndDurationChooseTheEarlierEndpointInBothDirections() {
        RawTimeline.Cue cue = only(parse(ttml("<p begin='2s' dur='10s'>"
                + "<span begin='2s' end='8s' dur='3s'>A</span>"
                + "<span begin='2s' end='3s' dur='8s'>B</span>"
                + "<br begin='2s' end='8s' dur='3s'/>"
                + "<br begin='2s' end='3s' dur='8s'/></p>"), 0));
        assertEquals(4, cue.runs.size());
        run(cue, 0, "A", 4 * SECOND, 7 * SECOND, true, false);
        run(cue, 1, "B", 4 * SECOND, 5 * SECOND, true, false);
        run(cue, 2, "\n", 4 * SECOND, 7 * SECOND, true, true);
        run(cue, 3, "\n", 4 * SECOND, 5 * SECOND, true, true);
    }

    @Test
    public void inlineEndUsesParentSyncbaseNotTheChildBegin() {
        RawTimeline.Cue cue = only(parse("<tt><body begin='1s'><div begin='2s'>"
                + "<p begin='2s' dur='10s'><span begin='3s' end='8s'>A"
                + "<span begin='1s' end='3s'>B<br begin='1s' end='2s'/></span>C</span></p>"
                + "</div></body></tt>", 0));
        assertEquals(4, cue.runs.size());
        run(cue, 0, "A", 8 * SECOND, 13 * SECOND, true, false);
        run(cue, 1, "B", 9 * SECOND, 11 * SECOND, true, false);
        run(cue, 2, "\n", 10 * SECOND, 11 * SECOND, true, true);
        run(cue, 3, "C", 8 * SECOND, 13 * SECOND, true, false);
    }

    @Test
    public void punctuationEntitiesEmojiAndCjkKeepExactUtf16Ranges() {
        RawTimeline.Cue cue = only(parse(ttml("<p dur='5s'> 你,&amp;😀"
                + "<span begin='1s' dur='1s'>&#x1F642;。&lt;b&gt;&amp;lt;</span>"
                + "文！<br/>&#x1D11E;</p>"), 0));
        assertEquals(" 你,&😀🙂。<b>&lt;文！\n𝄞", cue.mappingText);
        assertEquals("你,&😀🙂。<b>&lt;文！\n𝄞", cue.text);
        assertEquals(5, cue.runs.size());
        run(cue, 0, " 你,&😀", 0, 5 * SECOND, false, false);
        run(cue, 1, "🙂。<b>&lt;", SECOND, 2 * SECOND, true, false);
        run(cue, 2, "文！", 0, 5 * SECOND, false, false);
        run(cue, 3, "\n", 0, 5 * SECOND, false, true);
        run(cue, 4, "𝄞", 0, 5 * SECOND, false, false);
        int[] boundaries = {0, 6, 16, 18, 19, 21};
        for (int i = 0; i < cue.runs.size(); i++) {
            assertEquals(boundaries[i], cue.runs.get(i).textStart);
            assertEquals(boundaries[i + 1], cue.runs.get(i).textEnd);
        }
        assertEquals("你,&😀文！\n𝄞", cue.textAt(0));
    }

    @Test
    public void separateNodesKeepSeparateRangesEvenWithIdenticalTextAndTiming() {
        RawTimeline.Cue cue = only(parse(ttml("<p dur='2s'>A<span/>A<span>A</span>A"
                + "<!---->A<![CDATA[A]]>A</p>"), 0));
        assertEquals("AAAAAAA", cue.mappingText);
        assertEquals(7, cue.runs.size());
        for (int i = 0; i < 7; i++) {
            run(cue, i, "A", 0, 2 * SECOND, false, false);
            assertEquals(i, cue.runs.get(i).textStart);
            assertEquals(i + 1, cue.runs.get(i).textEnd);
        }
    }

    @Test
    public void entityExpansionAndLongSaxBuffersDoNotCreateFalseWordRuns() {
        String text = repeat("x", 24_000) + "&😀中文" + repeat("y", 12_000);
        RawTimeline.Cue cue = only(parse(ttml("<p dur='5s'><span begin='1s' dur='2s'>"
                + repeat("x", 24_000) + "&amp;&#x1F600;中文" + repeat("y", 12_000)
                + "</span></p>"), 0));
        assertEquals(text, cue.mappingText);
        assertEquals(1, cue.runs.size());
        run(cue, 0, text, SECOND, 3 * SECOND, true, false);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void arbitraryCharactersCallbacksMergeEvenWhenASurrogatePairIsSplit() throws Exception {
        // Inject callbacks deterministically; SAX buffer sizes are an XML runtime detail.
        Class<?> type = Class.forName(RawTtmlParser.class.getName() + "$Handler");
        Constructor<?> constructor = type.getDeclaredConstructor(long.class);
        constructor.setAccessible(true);
        DefaultHandler2 handler = (DefaultHandler2) constructor.newInstance(0L);
        AttributesImpl empty = new AttributesImpl();
        AttributesImpl paragraph = new AttributesImpl();
        paragraph.addAttribute("", "dur", "dur", "CDATA", "5s");
        AttributesImpl span = new AttributesImpl();
        span.addAttribute("", "begin", "begin", "CDATA", "1s");
        span.addAttribute("", "dur", "dur", "CDATA", "2s");
        handler.startElement("", "tt", "tt", empty);
        handler.startElement("", "body", "body", empty);
        handler.startElement("", "p", "p", paragraph);
        handler.startElement("", "span", "span", span);
        String prefix = "A&😀中";
        char[] chars = prefix.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            handler.characters(chars, i, 1);
        }
        char[] suffix = {'x'};
        for (int i = 0; i < RawTimeline.MAX_TEXT_RUNS * 2; i++) {
            handler.characters(suffix, 0, 1);
        }
        handler.characters(suffix, 0, 0);
        handler.endElement("", "span", "span");
        handler.endElement("", "p", "p");
        handler.endElement("", "body", "body");
        handler.endElement("", "tt", "tt");
        handler.endDocument();
        Field field = type.getDeclaredField("cues");
        field.setAccessible(true);
        List<RawTimeline.Cue> cues = (List<RawTimeline.Cue>) field.get(handler);
        assertEquals(1, cues.size());
        RawTimeline.Cue cue = cues.get(0);
        assertPartition(cue);
        assertEquals(1, cue.runs.size());
        run(cue, 0, prefix + repeat("x", RawTimeline.MAX_TEXT_RUNS * 2),
                SECOND, 3 * SECOND, true, false);
    }

    @Test
    public void prefixAndSuffixUseCueIntervalsRatherThanFabricatedWordWindows() {
        RawTimeline.Cue cue = only(parse(ttml("<p dur='6s'>First two words "
                + "<span begin='2s' dur='1s'>timed multi word</span> and suffix.</p>"), 0));
        assertEquals(3, cue.runs.size());
        run(cue, 0, "First two words ", 0, 6 * SECOND, false, false);
        run(cue, 1, "timed multi word", 2 * SECOND, 3 * SECOND, true, false);
        run(cue, 2, " and suffix.", 0, 6 * SECOND, false, false);
        assertEquals("First two words and suffix.", cue.textAt(0));
        assertEquals("First two words timed multi word and suffix.", cue.textAt(2 * SECOND));
        assertEquals("First two words and suffix.", cue.textAt(3 * SECOND));
    }

    @Test
    public void negativeEffectiveOffsetIsNotClampedAndMaximumFiniteEndpointSurvives() {
        RawTimeline.Cue cue = only(parse(ttml("<p begin='1s' dur='3s'>P"
                + "<span begin='1s' dur='1s'>T</span>Q</p>"), -2 * SECOND));
        assertEquals(-SECOND, cue.startUs);
        assertEquals(2 * SECOND, cue.endUs);
        run(cue, 1, "T", 0, SECOND, true, false);
        assertEquals("PQ", cue.textAt(-SECOND));
        assertEquals("PTQ", cue.textAt(0));
        assertEquals("PQ", cue.textAt(SECOND));

        cue = only(parse(ttml("<p begin='9223372036854.775805s' end='9223372036854.775807s'>"
                + "<span begin='0.000001s' dur='0.000001s'>x</span></p>"), 0));
        run(cue, 0, "x", Long.MAX_VALUE - 1, Long.MAX_VALUE, true, false);
        assertEquals("x", cue.textAt(Long.MAX_VALUE - 1));
        assertEquals("", cue.textAt(Long.MAX_VALUE));
    }

    @Test
    public void finiteChildTimingDoesNotSupplyAnUnboundedParagraphEndpoint() {
        invalid(ttml("<p><span begin='1s' end='2s'>x</span></p>"));
        invalid(ttml("<p begin='1s'><br dur='1s'/></p>"));
        invalid(ttml("<p><span dur='1s'>x</span><span begin='1s' dur='1s'>y</span></p>"));
    }

    @Test
    public void malformedLossyZeroAndReversedInlineIntervalsFailClosed() {
        String[] badTimes = {"-1s", "+1s", "NaN", "Infinitys", "1e3s", "1f", "1t",
                "00:00:01:01", "00:60:00", "0.0000001s", "0.0001ms", "9223372036854.775808s"};
        String[] badIntervals = {"dur='0s'", "end='0s'", "begin='2s' end='2s'",
                "begin='2s' end='1s' dur='3s'", "begin='4s' dur='0s'"};
        for (String name : new String[]{"span", "br"}) {
            for (String attr : new String[]{"begin", "end", "dur"}) {
                for (String value : badTimes) {
                    invalid(ttml("<p dur='3s'><" + name + " " + attr + "='" + value + "'/></p>"));
                }
            }
            for (String attrs : badIntervals) {
                invalid(ttml("<p dur='3s'><" + name + " " + attrs + "/></p>"));
            }
        }
        invalid(ttml("<p dur='1s'>valid</p><p dur='2s'><span dur='0s'>invalid</span></p>"));
        invalid(ttml("<p dur='1s'><span begin='2s' dur='1s'><span dur='0s'>hidden</span></span></p>"));
    }

    @Test
    public void inlineArithmeticOverflowIsRejectedBeforeAncestorClipping() {
        for (String name : new String[]{"span", "br"}) {
            invalid(ttml("<p dur='1s'><" + name
                    + " begin='9223372036854.775807s' dur='0.000001s'/></p>"));
            invalid(ttml("<p begin='1s' dur='1s'><" + name
                    + " begin='9223372036854.775807s'/></p>"));
            invalid(ttml("<p begin='1s' dur='1s'><" + name
                    + " end='9223372036854.775807s'/></p>"));
        }
        invalid(ttml("<p dur='2s'><span begin='1s' dur='1s'>x</span></p>"), Long.MAX_VALUE);
    }

    @Test
    public void unsupportedInlineSemanticsRemainRejectedEvenOnHiddenLeaves() {
        for (String attr : new String[]{"timeContainer='seq'", "xml:space='preserve'", "style='x'",
                "region='x'", "unknown='x'"}) {
            invalid(ttml("<p dur='1s'><span begin='2s' dur='1s' " + attr + ">hidden</span></p>"));
        }
        invalid(ttml("<p dur='1s'><br timeContainer='par'/></p>"));
        invalid(ttml("<p dur='1s'><br timeContainer='seq'/></p>"));
        invalid(ttml("<p dur='1s'><span timeContainer='par'><br begin='0s'> </br></span></p>"));
    }

    @Test
    public void textRunBudgetIsInclusiveAndIndependentOfStructuralNodeBudget() {
        RawTimeline.Cue cue = only(parse(ttml("<p dur='1s'>"
                + leaves(RawTimeline.MAX_TEXT_RUNS) + "</p>"), 0));
        assertEquals(RawTimeline.MAX_TEXT_RUNS, cue.runs.size());
        assertEquals(repeat("x", RawTimeline.MAX_TEXT_RUNS), cue.mappingText);
        invalid(ttml("<p dur='1s'>" + leaves(RawTimeline.MAX_TEXT_RUNS + 1) + "</p>"));
    }

    @Test
    public void textRunBudgetIsGlobalRatherThanResetForEveryParagraph() {
        String paragraphs = "<p dur='1s'>" + leaves(RawTimeline.MAX_TEXT_RUNS / 2) + "</p>"
                + "<p dur='1s'>" + leaves(RawTimeline.MAX_TEXT_RUNS / 2) + "</p>";
        RawTimeline timeline = parse(ttml(paragraphs), 0);
        assertEquals(2, timeline.getCues().size());
        assertEquals(RawTimeline.MAX_TEXT_RUNS / 2, timeline.getCues().get(0).runs.size());
        assertEquals(RawTimeline.MAX_TEXT_RUNS / 2, timeline.getCues().get(1).runs.size());
        invalid(ttml(paragraphs + "<p dur='1s'>extra</p>"));
    }

    @Test
    public void clippedLeavesDoNotConsumeOutputTextRunBudget() {
        RawTimeline.Cue cue = only(parse(ttml("<p dur='1s'>"
                + repeat("<span begin='2s' dur='1s'>hidden<br/></span>", 100)
                + leaves(RawTimeline.MAX_TEXT_RUNS) + "</p>"), 0));
        assertEquals(RawTimeline.MAX_TEXT_RUNS, cue.runs.size());
        assertEquals(repeat("x", RawTimeline.MAX_TEXT_RUNS), cue.mappingText);
    }

    private static String ttml(String content) {
        return "<tt><body><div>" + content + "</div></body></tt>";
    }

    private static String repeat(String text, int count) {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < count; i++) {
            value.append(text);
        }
        return value.toString();
    }

    private static String leaves(int count) {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < count; i++) {
            value.append(i % 2 == 0 ? "x" : "<span>x</span>");
        }
        return value.toString();
    }

    private static RawTimeline parse(String xml, long offset) {
        byte[] bytes = xml.getBytes(UTF8);
        RawTimeline timeline = RawTtmlParser.parse(bytes, offset);
        assertArrayEquals(bytes, timeline.copySource());
        for (RawTimeline.Cue cue : timeline.getCues()) {
            assertPartition(cue);
        }
        return timeline;
    }

    private static RawTimeline.Cue only(RawTimeline timeline) {
        assertEquals(1, timeline.getCues().size());
        return timeline.getCues().get(0);
    }

    private static void assertPartition(RawTimeline.Cue cue) {
        StringBuilder rebuilt = new StringBuilder();
        int cursor = 0;
        for (RawTimeline.TextRun run : cue.runs) {
            assertEquals(cursor, run.textStart);
            assertTrue(run.textEnd > run.textStart);
            assertTrue(run.textEnd <= cue.mappingText.length());
            assertFalse(splitsSurrogate(cue.mappingText, run.textStart));
            assertFalse(splitsSurrogate(cue.mappingText, run.textEnd));
            rebuilt.append(cue.mappingText, run.textStart, run.textEnd);
            assertTrue(run.activeStartUs >= cue.startUs);
            assertTrue(run.activeEndUs <= cue.endUs);
            assertTrue(run.activeEndUs > run.activeStartUs);
            assertEquals(run.activeStartUs, run.timingStartUs);
            assertEquals(run.activeEndUs, run.timingEndUs);
            assertTrue(run.timingKind == RawTimeline.TimingKind.CUE_INTERVAL
                    || run.timingKind == RawTimeline.TimingKind.TTML_INTERVAL);
            if (run.lineBreak) {
                assertEquals("\n", cue.mappingText.substring(run.textStart, run.textEnd));
            }
            cursor = run.textEnd;
        }
        assertEquals(cue.mappingText.length(), cursor);
        assertEquals(cue.mappingText, rebuilt.toString());
    }

    private static boolean splitsSurrogate(String text, int offset) {
        return offset > 0 && offset < text.length()
                && Character.isHighSurrogate(text.charAt(offset - 1))
                && Character.isLowSurrogate(text.charAt(offset));
    }

    private static void run(RawTimeline.Cue cue, int ordinal, String text, long start, long end,
                            boolean inline, boolean lineBreak) {
        RawTimeline.TextRun run = cue.runs.get(ordinal);
        assertEquals(text, cue.mappingText.substring(run.textStart, run.textEnd));
        assertEquals(start, run.activeStartUs);
        assertEquals(end, run.activeEndUs);
        assertEquals(start, run.timingStartUs);
        assertEquals(end, run.timingEndUs);
        assertEquals(inline ? RawTimeline.TimingKind.TTML_INTERVAL : RawTimeline.TimingKind.CUE_INTERVAL,
                run.timingKind);
        assertEquals(lineBreak, run.lineBreak);
    }

    private static void invalid(String xml) {
        invalid(xml, 0);
    }

    private static void invalid(String xml, long offset) {
        try {
            RawTtmlParser.parse(xml.getBytes(UTF8), offset);
            fail("Invalid or unsupported XML must not return a partial timeline");
        } catch (IllegalArgumentException e) {
            assertEquals(RawTimeline.invalid().getMessage(), e.getMessage());
            assertNull(e.getCause());
            assertEquals(0, e.getSuppressed().length);
        }
    }
}
