package com.liskovsoft.smartyoutubetv2.common.subtitle.timeline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

public class TtmlTimelineParserTest {
    private static final RawTimeline.Limits LIMITS = new RawTimeline.Limits(1024 * 1024, 8192, 65536);

    @Test
    public void parsesInnertubeHeadStylingRegionsLangAndOffset() {
        String xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<tt xmlns=\"http://www.w3.org/ns/ttml\" "
                + "xmlns:tts=\"http://www.w3.org/ns/ttml#styling\" "
                + "xmlns:ttp=\"http://www.w3.org/ns/ttml#parameter\" "
                + "ttp:timeBase=\"media\" xml:lang=\"en-US\">\n"
                + "  <head>\n"
                + "    <metadata><unknownTag>ignored</unknownTag></metadata>\n"
                + "    <styling>\n"
                + "      <style xml:id=\"s1\" tts:color=\"#FFFFFF\" tts:fontSize=\"100%\"/>\n"
                + "    </styling>\n"
                + "    <layout>\n"
                + "      <region xml:id=\"r1\" tts:origin=\"10% 80%\" tts:extent=\"80% 15%\"/>\n"
                + "    </layout>\n"
                + "  </head>\n"
                + "  <body style=\"s1\" timeContainer=\"par\">\n"
                + "    <div region=\"r1\">\n"
                + "      <p xml:id=\"p1\" begin=\"00:00:01.000\" end=\"00:00:04.500\">\n"
                + "        <span>Hello</span>\n"
                + "        <span>world</span><br/>\n"
                + "        <span>line two</span>\n"
                + "      </p>\n"
                + "    </div>\n"
                + "  </body>\n"
                + "</tt>\n";

        RawTimeline timeline = parse(xml, 500_000L, LIMITS);
        assertEquals(RawTimeline.Origin.DOCUMENT, timeline.getOrigin());
        assertEquals("en-US", timeline.getMetadata().get("Language"));
        assertNull(timeline.getMetadata().get("AgentDeclared"));
        assertEquals(1, timeline.getCues().size());

        RawTimeline.Cue cue = timeline.getCues().get(0);
        assertEquals("p1", cue.sourceId);
        assertEquals(1_500_000L, cue.startUs);
        assertEquals(5_000_000L, cue.endUs);
        assertEquals("Hello world\nline two", cue.text);
        assertTrue(cue.webvttSettings.isEmpty());
    }

    @Test
    public void tightensUntimedEndWordRunsMonotonicallyWhilePreservingExplicitSpanEnd() {
        String xml = "<tt xmlns=\"http://www.w3.org/ns/ttml\">\n"
                + "  <body>\n"
                + "    <div>\n"
                + "      <p begin=\"1s\" end=\"4s\">\n"
                + "        <span begin=\"0ms\">one</span>\n"
                + "        <span begin=\"500ms\">two</span>\n"
                + "        <span begin=\"1000ms\" end=\"2200ms\">three</span>\n"
                + "        <span begin=\"2500ms\">four</span>\n"
                + "      </p>\n"
                + "    </div>\n"
                + "  </body>\n"
                + "</tt>";

        RawTimeline timeline = parse(xml, 0L, LIMITS);
        RawTimeline.Cue cue = timeline.getCues().get(0);
        assertEquals("one two three four", cue.text);

        List<RawTimeline.TextRun> wordRuns = new java.util.ArrayList<>();
        for (RawTimeline.TextRun run : cue.runs) {
            if (run.timingKind == RawTimeline.TimingKind.TTML_INTERVAL) {
                wordRuns.add(run);
            }
        }
        assertEquals(4, wordRuns.size());

        assertEquals("one", cue.text.substring(wordRuns.get(0).textStart, wordRuns.get(0).textEnd));
        assertEquals(1_000_000L, wordRuns.get(0).timingStartUs);
        assertEquals(1_500_000L, wordRuns.get(0).timingEndUs);
        assertEquals(4_000_000L, wordRuns.get(0).activeEndUs);

        assertEquals("two", cue.text.substring(wordRuns.get(1).textStart, wordRuns.get(1).textEnd));
        assertEquals(1_500_000L, wordRuns.get(1).timingStartUs);
        assertEquals(2_000_000L, wordRuns.get(1).timingEndUs);

        assertEquals("three", cue.text.substring(wordRuns.get(2).textStart, wordRuns.get(2).textEnd));
        assertEquals(2_000_000L, wordRuns.get(2).timingStartUs);
        assertEquals(3_200_000L, wordRuns.get(2).timingEndUs);

        assertEquals("four", cue.text.substring(wordRuns.get(3).textStart, wordRuns.get(3).textEnd));
        assertEquals(3_500_000L, wordRuns.get(3).timingStartUs);
        assertEquals(4_000_000L, wordRuns.get(3).timingEndUs);
    }

    @Test
    public void foldsXmlWhitespaceByDefaultAndPreservesWhenDeclared() {
        String xml = "<tt xmlns=\"http://www.w3.org/ns/ttml\">\n"
                + "  <body><div>\n"
                + "    <p begin=\"0s\" end=\"2s\">\n"
                + "      hello   \n\t  world\n"
                + "      <br/>\n"
                + "        next   line  \n"
                + "    </p>\n"
                + "    <p xml:space=\"preserve\" begin=\"2s\" end=\"4s\">  kept   spaces  </p>\n"
                + "  </div></body>\n"
                + "</tt>";

        RawTimeline timeline = parse(xml, 0L, LIMITS);
        assertEquals(2, timeline.getCues().size());
        assertEquals("hello world\nnext line", timeline.getCues().get(0).text);
        assertEquals("  kept   spaces  ", timeline.getCues().get(1).text);
    }

    @Test
    public void corruptedOrOutOfOrderInlineTimingDegradesOnlyOwningParagraph() {
        String xml = "<tt xmlns=\"http://www.w3.org/ns/ttml\">\n"
                + "  <body><div>\n"
                + "    <p begin=\"0s\" end=\"3s\">\n"
                + "      <span begin=\"2000ms\">late</span>\n"
                + "      <span begin=\"500ms\">early</span>\n"
                + "    </p>\n"
                + "    <p begin=\"3s\" end=\"6s\">\n"
                + "      <span begin=\"badtime\">oops</span>\n"
                + "    </p>\n"
                + "    <p begin=\"6s\" end=\"9s\">\n"
                + "      <span begin=\"0ms\">good</span>\n"
                + "      <span begin=\"1000ms\">next</span>\n"
                + "    </p>\n"
                + "  </div></body>\n"
                + "</tt>";

        RawTimeline timeline = parse(xml, 0L, LIMITS);
        assertEquals(3, timeline.getCues().size());

        RawTimeline.Cue first = timeline.getCues().get(0);
        assertEquals("late early", first.text);
        assertEquals(1, first.runs.size());
        assertEquals(RawTimeline.TimingKind.CUE_INTERVAL, first.runs.get(0).timingKind);
        assertEquals(0L, first.runs.get(0).timingStartUs);
        assertEquals(3_000_000L, first.runs.get(0).timingEndUs);

        RawTimeline.Cue second = timeline.getCues().get(1);
        assertEquals("oops", second.text);
        assertEquals(1, second.runs.size());
        assertEquals(RawTimeline.TimingKind.CUE_INTERVAL, second.runs.get(0).timingKind);

        RawTimeline.Cue third = timeline.getCues().get(2);
        assertEquals("good next", third.text);
        assertEquals(3, third.runs.size());
        assertEquals(RawTimeline.TimingKind.TTML_INTERVAL, third.runs.get(0).timingKind);
        assertEquals(RawTimeline.TimingKind.CUE_INTERVAL, third.runs.get(1).timingKind);
        assertEquals(RawTimeline.TimingKind.TTML_INTERVAL, third.runs.get(2).timingKind);

        String nestedXml = "<tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div>"
                + "<p begin=\"0s\" end=\"5s\"><span><span begin=\"1000ms\">nested</span></span></p>"
                + "<p begin=\"5s\" end=\"8s\"><span><span begin=\"bad\">broken</span></span></p>"
                + "<p begin=\"8s\" end=\"10s\"><span begin=\"0ms\"><span begin=\"100ms\" end=\"bad\">deep</span></span></p>"
                + "</div></body></tt>";
        RawTimeline nested = parse(nestedXml, 0L, LIMITS);
        assertEquals(3, nested.getCues().size());
        assertEquals(RawTimeline.TimingKind.TTML_INTERVAL, nested.getCues().get(0).runs.get(0).timingKind);
        assertEquals(1_000_000L, nested.getCues().get(0).runs.get(0).timingStartUs);
        assertEquals(1, nested.getCues().get(1).runs.size());
        assertEquals(RawTimeline.TimingKind.CUE_INTERVAL, nested.getCues().get(1).runs.get(0).timingKind);
        assertEquals(1, nested.getCues().get(2).runs.size());
        assertEquals(RawTimeline.TimingKind.CUE_INTERVAL, nested.getCues().get(2).runs.get(0).timingKind);
    }

    @Test
    public void scalesBeyondTenThousandInlineSpansUnderExpandedProductionBudget() {
        StringBuilder xml = new StringBuilder();
        xml.append("<tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div>\n");
        int cues = 250;
        int spansPerCue = 45; // 11,250 spans total + intervening whitespace runs (~22,250 textRuns)
        for (int i = 0; i < cues; i++) {
            long startMs = i * 2000L;
            long endMs = startMs + 1900L;
            xml.append("<p begin=\"").append(startMs).append("ms\" end=\"").append(endMs).append("ms\">");
            for (int w = 0; w < spansPerCue; w++) {
                if (w > 0) {
                    xml.append(' ');
                }
                xml.append("<span begin=\"").append(w * 30L).append("ms\">w").append(w).append("</span>");
            }
            xml.append("</p>\n");
        }
        xml.append("</div></body></tt>");

        RawTimeline timeline = parse(xml.toString(), 0L, LIMITS);
        assertEquals(cues, timeline.getCues().size());
        assertTrue(timeline.getCues().get(0).runs.size() >= spansPerCue);
    }

    @Test
    public void detectsTtmAgentAndRoleAndSupportsFrameAndTickRates() {
        String xml = "<tt xmlns=\"http://www.w3.org/ns/ttml\" "
                + "xmlns:ttm=\"http://www.w3.org/ns/ttml#metadata\" "
                + "xmlns:ttp=\"http://www.w3.org/ns/ttml#parameter\" "
                + "ttp:frameRate=\"30\" ttp:tickRate=\"1000\">\n"
                + "  <body><div>\n"
                + "    <p ttm:agent=\"speaker1\" begin=\"30f\" dur=\"2000t\">Frame and tick</p>\n"
                + "  </div></body>\n"
                + "</tt>";

        RawTimeline timeline = parse(xml, 0L, LIMITS);
        assertEquals("true", timeline.getMetadata().get("AgentDeclared"));
        assertEquals(1, timeline.getCues().size());
        assertEquals(1_000_000L, timeline.getCues().get(0).startUs);
        assertEquals(3_000_000L, timeline.getCues().get(0).endUs);
    }

    @Test
    public void rejectsUnsafeXmlBitmapSequentialContainersNonMediaTimeBaseAndBudgets() {
        List<String> unsafeDocs = Arrays.asList(
                "<!DOCTYPE tt [<!ENTITY x \"boom\">]><tt xmlns=\"http://www.w3.org/ns/ttml\"><body><p begin=\"0s\" end=\"1s\">&x;</p></body></tt>",
                "<!doctype html><tt xmlns=\"http://www.w3.org/ns/ttml\"><body><p begin=\"0s\" end=\"1s\">hi</p></body></tt>",
                "<?xml version=\"1.0\" encoding=\"UTF-16\"?><tt xmlns=\"http://www.w3.org/ns/ttml\"><body><p begin=\"0s\" end=\"1s\">hi</p></body></tt>",
                "<tt xmlns=\"http://www.w3.org/ns/ttml\"><?pi bad?><body><p begin=\"0s\" end=\"1s\">hi</p></body></tt>",
                "<tt xmlns=\"http://www.w3.org/ns/ttml\" xmlns:smpte=\"http://www.smpte-ra.org/schemas/2052-1/2010/smpte-tt\"><body><smpte:image xml:id=\"i1\">AAAA</smpte:image><p begin=\"0s\" end=\"1s\">hi</p></body></tt>",
                "<tt xmlns=\"http://www.w3.org/ns/ttml\" xmlns:smpte=\"http://www.smpte-ra.org/schemas/2052-1/2010/smpte-tt\"><body><div smpte:backgroundImage=\"#i1\"><p begin=\"0s\" end=\"1s\">hi</p></div></body></tt>",
                "<tt xmlns=\"http://www.w3.org/ns/ttml\"><body timeContainer=\"seq\"><p begin=\"0s\" end=\"1s\">hi</p></body></tt>",
                "<tt xmlns=\"http://www.w3.org/ns/ttml\" xmlns:ttp=\"http://www.w3.org/ns/ttml#parameter\" ttp:timeBase=\"smpte\"><body><p begin=\"0s\" end=\"1s\">hi</p></body></tt>",
                "<tt xmlns=\"http://www.w3.org/ns/ttml\"><body><p begin=\"0s\">no end or dur</p></body></tt>",
                "<tt xmlns=\"http://www.w3.org/ns/ttml\"><head/></tt>");

        for (String doc : unsafeDocs) {
            assertInvalid(doc, LIMITS);
        }

        RawTimeline.Limits tinyCues = new RawTimeline.Limits(1024 * 1024, 1, 64);
        assertInvalid("<tt xmlns=\"http://www.w3.org/ns/ttml\"><body>"
                + "<p begin=\"0s\" end=\"1s\">one</p>"
                + "<p begin=\"1s\" end=\"2s\">two</p>"
                + "</body></tt>", tinyCues);

        RawTimeline.Limits tinyRuns = new RawTimeline.Limits(1024 * 1024, 10, 2);
        assertInvalid("<tt xmlns=\"http://www.w3.org/ns/ttml\"><body>"
                + "<p begin=\"0s\" end=\"1s\"><span begin=\"0s\">a</span><span begin=\"300ms\">b</span><span begin=\"600ms\">c</span></p>"
                + "</body></tt>", tinyRuns);
    }

    private static RawTimeline parse(String xml, long offsetUs, RawTimeline.Limits limits) {
        return TtmlTimelineParser.parseForNativePlayback(xml.getBytes(StandardCharsets.UTF_8), offsetUs, limits);
    }

    private static void assertInvalid(String xml, RawTimeline.Limits limits) {
        try {
            parse(xml, 0L, limits);
            fail("Expected invalid TTML rejection for: " + xml);
        } catch (RuntimeException expected) {
            // RawTimeline.invalid() is intentionally thrown on fail-closed rejection.
        }
    }
}
