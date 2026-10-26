package com.liskovsoft.smartyoutubetv2.common.subtitle.timeline;

import org.junit.Test;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

/** Offline JDK SAX contract tests, not a claim of full TTML conformance or Android integration. */
public class RawTtmlParserTest {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String TT = "http://www.w3.org/ns/ttml";

    @Test
    public void ancestorStartCrossesUntimedDivAndExplicitSampleOffsetIsAppliedOnce() {
        String source = document("begin='1s'", "", "<p begin='2s' end='4s'>text</p>");
        cue(only(parse(source, 0)), 0, null, 3_000_000L, 5_000_000L, "text");
        cue(only(parse(source, 11_000_000L)), 0, null, 14_000_000L, 16_000_000L, "text");
    }

    @Test
    public void nestedUntimedContainersInheritStartAndExplicitParHasTheSameMeaning() {
        String source = document("begin='1s' timeContainer='par'", "begin='2s' timeContainer='par'",
                "<div><div><p begin='2s' dur='3s' timeContainer='par'>text</p></div></div>");
        cue(only(parse(source, 0)), 0, null, 5_000_000L, 8_000_000L, "text");
    }

    @Test
    public void explicitEndUsesParentSyncbaseRatherThanChildBegin() {
        String source = document("begin='1s'", "begin='2s'",
                "<p begin='2s' end='4s' dur='3s'>text</p>");
        cue(only(parse(source, 0)), 0, null, 5_000_000L, 7_000_000L, "text");
    }

    @Test
    public void paragraphCanInheritBodyEndAcrossUntimedDiv() {
        String source = document("begin='1s' end='9s'", "", "<p begin='2s'>text</p>");
        cue(only(parse(source, 0)), 0, null, 3_000_000L, 9_000_000L, "text");
    }

    @Test
    public void paragraphCanInheritDivEndRelativeToBodyStart() {
        String source = document("begin='1s'", "begin='2s' end='8s'",
                "<div><p begin='1s'>text</p></div>");
        cue(only(parse(source, 0)), 0, null, 4_000_000L, 9_000_000L, "text");
    }

    @Test
    public void ancestorDurationClipsDescendantEndAndDuration() {
        String source = document("begin='1s' dur='4s'", "begin='1s' dur='10s'",
                "<p begin='1s' end='30s' dur='20s'>text</p>");
        cue(only(parse(source, 0)), 0, null, 3_000_000L, 5_000_000L, "text");
    }

    @Test
    public void ownEndAndDurationChooseEarlierEndpointInBothDirections() {
        List<RawTimeline.Cue> cues = parse(ttml("<p begin='2s' end='9s' dur='3s'>a</p>"
                + "<p begin='3s' end='4s' dur='8s'>b</p>"), 0).getCues();
        assertEquals(2, cues.size());
        cue(cues.get(0), 0, null, 2_000_000L, 5_000_000L, "a");
        cue(cues.get(1), 1, null, 3_000_000L, 4_000_000L, "b");
    }

    @Test
    public void ancestorEndAndDurationAlsoChooseEarlierEndpoint() {
        cue(only(parse(document("begin='1s' end='20s' dur='4s'", "", "<p>a</p>"), 0)),
                0, null, 1_000_000L, 5_000_000L, "a");
        cue(only(parse(document("begin='1s' end='4s' dur='20s'", "", "<p>a</p>"), 0)),
                0, null, 1_000_000L, 4_000_000L, "a");
        cue(only(parse(document("begin='1s' end='20s'", "begin='2s' end='8s' dur='3s'",
                "<p>a</p>"), 0)), 0, null, 3_000_000L, 6_000_000L, "a");
    }

    @Test
    public void clippedParagraphsStillConsumeDocumentOrdinals() {
        String source = document("dur='5s'", "", "<p begin='6s' dur='1s'>hidden</p>"
                + "<p begin='1s' dur='1s' xml:id='same'>visible</p>"
                + "<p begin='5s' dur='1s'>also hidden</p>"
                + "<p begin='2s' dur='1s' xml:id='same'>visible</p>");
        List<RawTimeline.Cue> cues = parse(source, 0).getCues();
        assertEquals(2, cues.size());
        cue(cues.get(0), 1, "same", 1_000_000L, 2_000_000L, "visible");
        cue(cues.get(1), 3, "same", 2_000_000L, 3_000_000L, "visible");
    }

    @Test
    public void whollyClippedSubtreeIsNotAnIntrinsicallyMalformedInterval() {
        String source = document("dur='1s'", "begin='2s' dur='4s'", "<div><p>a</p></div>");
        assertEquals(0, parse(source, 0).getCues().size());
    }

    @Test
    public void overlapsLongCuesRepeatedTextAndRepeatedIdsRemainInDocumentOrder() {
        List<RawTimeline.Cue> cues = parse(ttml("<p begin='10s' dur='20s' xml:id='same'>same</p>"
                + "<p begin='1s' dur='20s' xml:id='same'>same</p>"
                + "<p begin='5s' dur='30s' xml:id='same'>same</p>"), 0).getCues();
        assertEquals(3, cues.size());
        cue(cues.get(0), 0, "same", 10_000_000L, 30_000_000L, "same");
        cue(cues.get(1), 1, "same", 1_000_000L, 21_000_000L, "same");
        cue(cues.get(2), 2, "same", 5_000_000L, 35_000_000L, "same");
    }

    @Test
    public void xmlEntitiesAreDecodedExactlyOnceAndNeverReparsedAsMarkup() {
        String source = ttml("<p dur='1s'>&lt;b&gt;literal&lt;/b&gt; &amp;lt; &amp;amp; "
                + "&#160; &#xA0; &quot; &apos; &#x1D11E;</p>");
        assertEquals("<b>literal</b> &lt; &amp;     \" ' 𝄞",
                only(parse(source, 0)).text);
    }

    @Test
    public void untimedSpansDoNotStripEntityProducedTagsOrInventWordBoundaries() {
        assertEquals("A<i>B</i>C", only(parse(ttml(
                "<p dur='1s'>A<span>&lt;i&gt;B&lt;/i&gt;</span>C</p>"), 0)).text);
        assertEquals("ABC", only(parse(ttml("<p dur='1s'>A<span>B</span>C</p>"), 0)).text);
    }

    @Test
    public void cdataIsTextNotMarkupAndItsEntitySpellingIsNotDecoded() {
        assertEquals("<b>&amp;", only(parse(ttml("<p dur='1s'><![CDATA[<b>&amp;]]></p>"), 0)).text);
    }

    @Test
    public void defaultXmlWhitespaceCollapsesAcrossSpansAndTrimsAroundExplicitBreaks() {
        String source = ttml("<p dur='1s'> \t A<span> \nB </span> <br/> \r\n C\tD <br/><br/> E </p>");
        assertEquals("A B\nC D\n\nE", only(parse(source, 0)).text);
        assertEquals("\nA\n", only(parse(ttml("<p dur='1s'><br/> A <br/></p>"), 0)).text);
        assertEquals("  A ", only(parse(ttml("<p dur='1s'> &#160; A&#160; </p>"), 0)).text);
    }

    @Test
    public void explicitDefaultSpaceIsSupportedButPreserveIsRejectedEvenWhenOverridden() {
        String source = "<tt xmlns='" + TT + "' xml:space='default'><body><div>"
                + "<p dur='1s' xml:space='default'> A<span xml:space='default'> B </span></p>"
                + "</div></body></tt>";
        assertEquals("A B", only(parse(source, 0)).text);
        invalid("<tt xml:space='preserve'><body><p dur='1s' xml:space='default'>a</p></body></tt>");
        invalid(ttml("<p dur='1s' xml:space='preserve'>a</p>"));
        invalid(ttml("<p dur='1s'><span xml:space='preserve'>a</span></p>"));
        invalid(ttml("<p dur='1s'><br xml:space='preserve'/></p>"));
        invalid(ttml("<p dur='1s' xml:space='invalid'>a</p>"));
    }

    @Test
    public void decimalClockAndOffsetUnitsHaveExactMicrosecondPrecision() {
        String[] values = {"01:02:03.000004", "1.5h", "2.25m", "1.000001s", "0.001ms", "1.0000000s"};
        long[] micros = {3_723_000_004L, 5_400_000_000L, 135_000_000L, 1_000_001L, 1L, 1_000_000L};
        for (int i = 0; i < values.length; i++) {
            cue(only(parse(ttml("<p begin='" + values[i] + "' dur='0.000001s'>a</p>"), 0)),
                    0, null, micros[i], micros[i] + 1, "a");
        }
    }

    @Test
    public void maximumLongIsFiniteNotAnIndefiniteSentinel() {
        cue(only(parse(ttml("<p begin='9223372036854.775806s' end='9223372036854.775807s'>a</p>"), 0)),
                0, null, Long.MAX_VALUE - 1, Long.MAX_VALUE, "a");
    }

    @Test
    public void explicitOffsetCanBeNegativeWithoutClampingOrLosingSourceTimeDifference() {
        cue(only(parse(ttml("<p begin='1s' dur='2s'>a</p>"), -2_000_000L)),
                0, null, -1_000_000L, 1_000_000L, "a");
    }

    @Test
    public void malformedNegativeNonFiniteUnsupportedAndLossyTimesAreRejected() {
        String[] bad = {"-1s", "+1s", "NaN", "NaNs", "Infinitys", "1e3s", " 1s", "1s ",
                ".5s", "1.s", "1", "1S", "1f", "1t", "00:00:01:01", "00:00", "0:00:01",
                "00:60:00", "00:00:60", "00:00:-1", "00:00:01.", "0.0000001s", "0.0001ms",
                "00:00:00.0000001", "１s", "9223372036854.775808s"};
        for (String time : bad) {
            invalid(ttml("<p begin='" + time + "' dur='1s'>a</p>"));
        }
    }

    @Test
    public void emptyReversedAndUnboundedParagraphTimingIsRejected() {
        invalid(ttml("<p>unbounded</p>"));
        invalid(ttml("<p begin='1s'>unbounded</p>"));
        invalid(ttml("<p begin='1s' end='1s'>empty</p>"));
        invalid(ttml("<p begin='2s' end='1s' dur='3s'>reversed</p>"));
        invalid(ttml("<p dur='0s' end='1s'>empty</p>"));
        invalid(document("begin='2s' end='1s'", "", "<p dur='1s'>a</p>"));
        invalid(document("", "dur='0s'", "<p dur='1s'>a</p>"));
    }

    @Test
    public void arithmeticOverflowIsRejectedEvenIfAnAncestorWouldClipIt() {
        invalid(document("begin='9223372036854.775807s'", "begin='0.000001s'", "<p dur='1s'>a</p>"));
        invalid(ttml("<p begin='9223372036854.775807s' dur='0.000001s'>a</p>"));
        invalid(document("dur='1s'", "", "<p begin='9223372036854.775807s' dur='1s'>a</p>"));
        invalid(ttml("<p dur='1s'>a</p>"), Long.MAX_VALUE);
        invalid(ttml("<p begin='9223372036854.775806s' end='9223372036854.775807s'>a</p>"), 1);
    }

    @Test
    public void timedSpansAndBreaksProduceExplicitIntervalsRatherThanFlattenedTiming() {
        String[] attributes = {"begin='1s'", "end='1s'", "dur='1s'"};
        for (String name : new String[]{"span", "br"}) {
            for (int i = 0; i < attributes.length; i++) {
                String content = "span".equals(name) ? "text" : "";
                RawTimeline.Cue cue = only(parse(ttml("<p dur='2s'><" + name + " "
                        + attributes[i] + ">" + content + "</" + name + "></p>"), 0));
                assertEquals(1, cue.runs.size());
                RawTimeline.TextRun run = cue.runs.get(0);
                long start = i == 0 ? 1_000_000L : 0;
                long end = i == 0 ? 2_000_000L : 1_000_000L;
                assertEquals(start, run.activeStartUs);
                assertEquals(end, run.activeEndUs);
                assertEquals(start, run.timingStartUs);
                assertEquals(end, run.timingEndUs);
                assertEquals(RawTimeline.TimingKind.TTML_INTERVAL, run.timingKind);
                assertEquals("br".equals(name), run.lineBreak);
                assertEquals("br".equals(name) ? "\n" : "text", cue.mappingText);
                assertEquals(cue.text, cue.textAt(start));
                assertEquals("", cue.textAt(end));
            }
        }
        RawTimeline.Cue par = only(parse(ttml(
                "<p dur='2s'><span timeContainer='par'>text</span></p>"), 0));
        assertEquals(RawTimeline.TimingKind.CUE_INTERVAL, par.runs.get(0).timingKind);
        invalid(ttml("<p dur='2s'><br timeContainer='par'/></p>"));
    }

    @Test
    public void unknownSemanticElementsAttributesAndNonParContainersAreRejected() {
        for (String element : new String[]{"set", "image", "unknown", "metadata", "head"}) {
            invalid(ttml("<p dur='1s'><" + element + "/></p>"));
        }
        invalid("<tt><head/><body><p dur='1s'>a</p></body></tt>");
        for (String attribute : new String[]{"style='x'", "region='x'", "unknown='x'", "xml:base='urn:ttml:test'",
                "timeContainer='seq'", "xml:unknown='x'", "x:begin='1s' xmlns:x='urn:ttml:test'"}) {
            invalid(ttml("<p dur='1s' " + attribute + ">a</p>"));
        }
        invalid(document("timeContainer='seq'", "", "<p dur='1s'>a</p>"));
        invalid(document("", "timeContainer='seq'", "<p dur='1s'>a</p>"));
        invalid(ttml("<p dur='1s'><x:span xmlns:x='urn:ttml:test'>a</x:span></p>"));
        invalid(ttml("<p dur='1s' xmlns:tts='" + TT + "#styling' tts:color='red'>a</p>"));
        invalid(ttml("<p dur='1s'><br>text</br></p>"));
        invalid(ttml("<p dur='1s'><br><span/></br></p>"));
    }

    @Test
    public void mediaTimeBaseIsAllowedButClockSmpteAndFrameTickParametersAreRejected() {
        String prefix = "<tt xmlns='" + TT + "' xmlns:ttp='" + TT + "#parameter' ";
        String content = "><body><div><p dur='1s'>a</p></div></body></tt>";
        assertEquals("a", only(parse(prefix + "ttp:timeBase='media'" + content, 0)).text);
        for (String attr : new String[]{"ttp:timeBase='clock'", "ttp:timeBase='smpte'", "ttp:frameRate='30'",
                "ttp:frameRateMultiplier='1000 1001'", "ttp:tickRate='1000'", "ttp:unknown='x'"}) {
            invalid(prefix + attr + content);
        }
    }

    @Test
    public void namespacesAreCheckedAndUnnamespacedSyntheticFixturesAreAlsoSupported() {
        assertEquals("a", only(parse("<tt><body><p dur='1s'>a</p></body></tt>", 0)).text);
        assertEquals("a", only(parse("<t:tt xmlns:t='" + TT + "' xml:lang='en'><t:body><t:div>"
                + "<t:p dur='1s'>a</t:p></t:div></t:body></t:tt>", 0)).text);
        invalid("<tt xmlns='urn:ttml:not-supported'><body><p dur='1s'>a</p></body></tt>");
        invalid("<tt xmlns='" + TT + "'><body><div xmlns=''><p dur='1s'>a</p></div></body></tt>");
        invalid("<tt><body/><body/></tt>");
        invalid("<tt/>");
        invalid("<not-tt><body><p dur='1s'>a</p></body></not-tt>");
        invalid("<?xml-stylesheet href='urn:ttml:test'?><tt><body/></tt>");
    }

    @Test
    public void aLateInvalidParagraphOrMalformedXmlNeverReturnsAPartialTimeline() {
        invalid(ttml("<p dur='1s'>valid</p><p>unbounded</p>"));
        invalid(ttml("<p dur='1s'>valid</p><p dur='1s'><span begin='0s' dur='0s'>invalid</span></p>"));
        invalid(ttml("<p dur='1s'>valid</p><p dur='1s'>private-marker&unknown;</p>"));
        invalid(ttml("<p dur='1s'>private-marker</div>"));
        invalid(ttml("<p dur='1s'>private-marker & bad</p>"));
        invalid(ttml("<p dur='1s'>\u0000</p>"));
        invalid(ttml("<p dur='1s' dur='2s'>duplicate attribute</p>"));
        invalid(ttml("<p dur='1s'>a</p>") + "trailing private-marker");
    }

    @Test
    public void allDtdFormsAreRejectedUsingOnlyInertUrnsNotFileOrNetworkTargets() {
        String body = "<tt><body><p dur='1s'>a</p></body></tt>";
        invalid("<!DOCTYPE tt>" + body);
        invalid("<!DOCTYPE tt [<!ENTITY local 'private-marker'>]>" + body.replace(">a</p>", ">&local;</p>"));
        invalid("<!DOCTYPE tt SYSTEM 'urn:ttml:test:never-resolve'>" + body);
        invalid("<!DOCTYPE tt [<!ENTITY external SYSTEM 'urn:ttml:test:never-resolve'>]>"
                + body.replace(">a</p>", ">&external;</p>"));
        invalid("<!DOCTYPE tt [<!ENTITY % external SYSTEM 'urn:ttml:test:never-resolve'>%external;]>" + body);
    }

    @Test
    public void depthLimitIsInclusiveAndRejectsOneMoreLevel() {
        assertEquals("a", only(parse(nested(RawTtmlParser.MAX_DEPTH - 3), 0)).text);
        invalid(nested(RawTtmlParser.MAX_DEPTH - 2));
    }

    @Test
    public void structuralNodeLimitBoundsSpansCommentsAndCdata() {
        String spans = repeat("<span/>", RawTtmlParser.MAX_NODES - 4);
        assertEquals("a", only(parse(ttml("<p dur='1s'>a" + spans + "</p>"), 0)).text);
        invalid(ttml("<p dur='1s'>a" + spans + "<span/></p>"));
        invalid(ttml("<p dur='1s'>" + repeat("<!---->", RawTtmlParser.MAX_NODES) + "</p>"));
        invalid(ttml("<p dur='1s'>" + repeat("<![CDATA[a]]>", RawTtmlParser.MAX_NODES) + "</p>"));
    }

    @Test
    public void cueLimitCountsAllParagraphsIncludingThoseClippedAway() {
        String paragraphs = repeat("<p dur='1s'>a</p>", RawTimeline.MAX_CUES);
        assertEquals(RawTimeline.MAX_CUES, parse(ttml(paragraphs), 0).getCues().size());
        invalid(ttml(paragraphs + "<p dur='1s'>extra</p>"));
        invalid(document("dur='1s'", "begin='2s'", paragraphs + "<p dur='1s'>extra</p>"));
    }

    @Test
    public void documentByteLimitIsInclusiveAndInvalidInputsAreSanitized() {
        String shell = ttml("<p dur='1s'></p>");
        int padding = RawTimeline.MAX_DOCUMENT_BYTES - shell.getBytes(UTF8).length;
        String exact = ttml("<p dur='1s'>" + repeat("x", padding) + "</p>");
        assertEquals(RawTimeline.MAX_DOCUMENT_BYTES, exact.getBytes(UTF8).length);
        assertEquals(padding, only(parse(exact, 0)).text.length());
        invalid(exact + " ");
        invalid((byte[]) null, 0);
        invalid(new byte[0], 0);
    }

    @Test
    public void malformedUtf8IsRejectedInsteadOfSubstitutingReplacementCharacters() {
        byte[] prefix = "<tt><body><p dur='1s'>".getBytes(UTF8);
        byte[] suffix = "</p></body></tt>".getBytes(UTF8);
        byte[][] bad = {{(byte) 0xC3}, {(byte) 0x80}, {(byte) 0xC0, (byte) 0xAF},
                {(byte) 0xED, (byte) 0xA0, (byte) 0x80}, {(byte) 0xF5, (byte) 0x80, (byte) 0x80, (byte) 0x80}};
        for (byte[] middle : bad) {
            byte[] bytes = Arrays.copyOf(prefix, prefix.length + middle.length + suffix.length);
            System.arraycopy(middle, 0, bytes, prefix.length, middle.length);
            System.arraycopy(suffix, 0, bytes, prefix.length + middle.length, suffix.length);
            invalid(bytes, 0);
        }
    }

    @Test
    public void utf8BomUnicodeAndOriginalSourceBytesSurviveDefensiveCopies() {
        byte[] source = ("﻿<?xml version='1.0' encoding='UTF-8'?>"
                + ttml("<p dur='1s'>中文 café &#160;</p>")).getBytes(UTF8);
        byte[] expected = source.clone();
        RawTimeline timeline = RawTtmlParser.parse(source, 0);
        assertEquals("中文 café  ", only(timeline).text);
        source[0] = 0;
        assertArrayEquals(expected, timeline.copySource());
        byte[] exposed = timeline.copySource();
        exposed[exposed.length - 1] = 0;
        assertArrayEquals(expected, timeline.copySource());
        try {
            timeline.getCues().clear();
            fail("Cue list must be immutable");
        } catch (UnsupportedOperationException expectedException) {
            // The model owns the output list, not the parser's mutable accumulator.
        }
    }

    @Test
    public void anXmlDeclarationCannotOverrideTheUtf8InputContract() {
        invalid("<?xml version='1.0' encoding='UTF-16'?>" + ttml("<p dur='1s'>a</p>"));
        invalid("<?xml version='1.0' encoding='ISO-8859-1'?>" + ttml("<p dur='1s'>a</p>"));
        assertEquals("a", only(parse("<?xml version='1.0' encoding='utf-8'?>"
                + ttml("<p dur='1s'>a</p>"), 0)).text);
    }

    private static String document(String bodyAttributes, String divAttributes, String content) {
        return "<tt xmlns='" + TT + "'><body " + bodyAttributes + "><div " + divAttributes + ">"
                + content + "</div></body></tt>";
    }

    private static String ttml(String content) {
        return document("", "", content);
    }

    private static String nested(int divs) {
        return "<tt><body>" + repeat("<div>", divs) + "<p dur='1s'>a</p>"
                + repeat("</div>", divs) + "</body></tt>";
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) {
            result.append(value);
        }
        return result.toString();
    }

    private static RawTimeline parse(String xml, long offset) {
        return RawTtmlParser.parse(xml.getBytes(UTF8), offset);
    }

    private static RawTimeline.Cue only(RawTimeline timeline) {
        assertEquals(1, timeline.getCues().size());
        return timeline.getCues().get(0);
    }

    private static void cue(RawTimeline.Cue cue, int index, String id, long start, long end, String text) {
        assertEquals(index, cue.sourceIndex);
        assertEquals(id, cue.sourceId);
        assertEquals(start, cue.startUs);
        assertEquals(end, cue.endUs);
        assertEquals(text, cue.text);
    }

    private static void invalid(String xml) {
        invalid(xml, 0);
    }

    private static void invalid(String xml, long offset) {
        invalid(xml.getBytes(UTF8), offset);
    }

    private static void invalid(byte[] bytes, long offset) {
        try {
            RawTtmlParser.parse(bytes, offset);
            fail("Invalid or unsupported XML must not return a partial timeline");
        } catch (IllegalArgumentException e) {
            assertEquals(RawTimeline.invalid().getMessage(), e.getMessage());
            assertNull(e.getCause());
            assertEquals(0, e.getSuppressed().length);
        }
    }
}
