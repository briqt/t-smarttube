package com.liskovsoft.smartyoutubetv2.common.subtitle.timeline;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class RawWebvttParserTest {
    @Test
    public void longAndOverlappingEntriesKeepTheirOriginalIntervals() throws Exception {
        RawTimeline timeline = parse("WEBVTT\n\na\n00:01.000 --> 00:15.000\nA\n\n" +
                "b\n00:03.000 --> 00:05.000\nB\n");
        assertEquals(2, timeline.getCues().size());
        assertCue(timeline.getCues().get(0), 0, "a", 1_000_000L, 15_000_000L, "A");
        assertCue(timeline.getCues().get(1), 1, "b", 3_000_000L, 5_000_000L, "B");
    }

    @Test
    public void repeatedTextIdsAndOutOfTimeOrderDoNotCollapseEntries() throws Exception {
        RawTimeline timeline = parse("WEBVTT\n\nsame\n00:03.000 --> 00:04.000\nRepeat\n\n" +
                "same\n00:01.000 --> 00:02.000\nRepeat\n");
        assertCue(timeline.getCues().get(0), 0, "same", 3_000_000L, 4_000_000L, "Repeat");
        assertCue(timeline.getCues().get(1), 1, "same", 1_000_000L, 2_000_000L, "Repeat");
    }

    @Test
    public void entityResultsAreNeverReinterpretedAsTagsOrEntities() throws Exception {
        String payload = "This is the &lt;fourth&gt; &amp;subtitle.\n<b>&amp;lt;literal&amp;gt;</b>";
        RawTimeline.Cue cue = parse(cue(payload)).getCues().get(0);
        assertEquals("This is the <fourth> &subtitle.\n&lt;literal&gt;", cue.text);
    }

    @Test
    public void webvttEntitiesAreNotGeneralHtmlUnescaping() throws Exception {
        String payload = "&nbsp;&lrm;&rlm;&unknown; &#65; &amp;amp;";
        assertEquals(" ‎‏&unknown; &#65; &amp;",
                parse(cue(payload)).getCues().get(0).text);
    }

    @Test
    public void textWhitespaceAndNewlinesAreNotHtmlParagraphs() throws Exception {
        assertEquals("  First\nSecond  ", parse(cue("  <i>First</i>\n<u>Second</u>  ")).getCues().get(0).text);
    }

    @Test
    public void classVoiceAndLanguageAnnotationsDoNotBecomeSpokenText() throws Exception {
        assertEquals("Hello 世界", parse(cue("<v Speaker><c.green><b>Hello</b></c> " +
                "<lang zh-Hans>世界</lang></v>")).getCues().get(0).text);
    }

    @Test
    public void sourceBytesAndReturnedCollectionsAreOwnedSnapshots() throws Exception {
        byte[] source = bytes("﻿" + cue("原文🙂").replace("\n", "\r\n"));
        byte[] expected = source.clone();
        RawTimeline timeline = RawWebvttParser.parse(source, 0);
        Arrays.fill(source, (byte) 0);
        byte[] returned = timeline.copySource();
        Arrays.fill(returned, (byte) 1);
        assertArrayEquals(expected, timeline.copySource());
        assertEquals("原文🙂", timeline.getCues().get(0).text);
        try {
            timeline.getCues().clear();
            fail("Expected immutable entries");
        } catch (UnsupportedOperationException expectedFailure) {
            // Source preservation and the derived text view are separate contracts.
        }
    }

    @Test
    public void offsetIsAppliedOnceInMicrosecondsWithoutClamping() throws Exception {
        byte[] document = bytes("WEBVTT\n\n00:00.001 --> 00:00.002\nX\n");
        RawTimeline.Cue positive = RawWebvttParser.parse(document, 7_000_001L).getCues().get(0);
        assertCue(positive, 0, null, 7_001_001L, 7_002_001L, "X");
        RawTimeline.Cue negative = RawWebvttParser.parse(document, -1500).getCues().get(0);
        assertCue(negative, 0, null, -500, 500, "X");
    }

    @Test
    public void hoursAreNotRestrictedToOneDay() throws Exception {
        RawTimeline timeline = parse("WEBVTT\n\n72:01:02.003 --> 72:01:03.004\nX\n");
        assertEquals(259_262_003_000L, timeline.getCues().get(0).startUs);
        assertEquals(259_263_004_000L, timeline.getCues().get(0).endUs);
    }

    @Test
    public void notesAndBlankLinesDoNotConsumeCueOrdinals() throws Exception {
        RawTimeline timeline = parse("WEBVTT label\n\nNOTE ignored\nnot timed\n\n\n" +
                "00:00.000 --> 00:01.000\nX\n\nNOTE\ncomment\n");
        assertCue(timeline.getCues().get(0), 0, null, 0, 1_000_000L, "X");
        assertEquals(1, timeline.getCues().size());
    }

    @Test
    public void emptyDocumentsAndEmptyCuePayloadsStayDistinct() throws Exception {
        assertTrue(parse("WEBVTT\n\n").getCues().isEmpty());
        RawTimeline.Cue empty = parse(cue("")).getCues().get(0);
        assertEquals("", empty.text);
        assertNull(empty.sourceId);
    }

    @Test
    public void invalidHeadersAndTimestampsAreRejected() throws Exception {
        for (String document : new String[]{"", "not VTT", "WEBVTTX\n\n", "WEBVTT -->\n\n",
                "WEBVTT\n00:00.000 --> 00:01.000\nX"}) {
            rejected(document);
        }
        for (String timing : new String[]{"00:01.000 --> 00:01.000", "00:02.000 --> 00:01.000",
                "00:60.000 --> 01:01.000", "60:00.000 --> 61:00.000", "0:00.000 --> 00:01.000",
                "-00:01.000 --> 00:02.000", "00:00.0001 --> 00:01.000", "00:00 --> 00:01",
                "NaN --> 00:01.000", "00:00.000-->00:01.000"}) {
            rejected("WEBVTT\n\n" + timing + "\nX\n");
        }
    }

    @Test
    public void missingCueSeparatorDoesNotTurnTheNextTimingLineIntoText() throws Exception {
        rejected(cue("A") + "00:02.000 --> 00:03.000\nB\n");
    }

    @Test
    public void missingCueSeparatorCannotHideUnsupportedSettingsInText() throws Exception {
        rejected(cue("A") + "00:02.000 --> 00:03.000 position:50%\nB\n");
    }

    @Test
    public void escapedTimingArrowRemainsOrdinaryTextAfterEntityDecoding() throws Exception {
        assertEquals("A --> B", parse(cue("A --&gt; B")).getCues().get(0).text);
    }

    @Test
    public void malformedLaterEntryCannotReturnAPartialTimeline() throws Exception {
        rejected(cue("valid") + "\nbad entry\nnot a timing line\nX\n");
    }

    @Test
    public void unsupportedHeadersSettingsAndRubyAreExplicitlyRejected() throws Exception {
        rejected("WEBVTT\nX-TIMESTAMP-MAP=LOCAL:00:00.000,MPEGTS:900000\n\n");
        rejected("WEBVTT\n\nSTYLE\n::cue { color: red; }\n\n");
        rejected("WEBVTT\n\nREGION\nid:r\n\n");
        rejected("WEBVTT\n\n00:00.000 --> 00:01.000 vertical:rl\nX\n");
        rejected(cue("A<00:01.500>B"));
        rejected(cue("<ruby>A<rt>B</rt></ruby>"));
    }

    @Test
    public void unknownUnbalancedAndMismatchedTagsAreNotGuessed() throws Exception {
        for (String payload : new String[]{"<unknown>X</unknown>", "<b>X", "X</b>",
                "<b>X</i>", "<c..x>X</c>", "<v >X</v>", "X<", "<b attr>X</b>"}) {
            rejected(cue(payload));
        }
    }

    @Test
    public void overflowInTimestampsOrOffsetsIsRejected() throws Exception {
        rejected("WEBVTT\n\n99999999999999999999:00:00.000 --> 99999999999999999999:00:01.000\nX\n");
        try {
            RawWebvttParser.parse(bytes(cue("X")), Long.MAX_VALUE);
            fail("Expected checked offset overflow");
        } catch (IllegalArgumentException expected) {
            assertEquals("Unsupported or invalid subtitle document", expected.getMessage());
        }
    }

    @Test
    public void markupDepthLimitAllowsClosingExactlyThirtyTwoLevels() throws Exception {
        StringBuilder payload = new StringBuilder();
        for (int i = 0; i < 32; i++) {
            payload.append("<b>");
        }
        payload.append('X');
        for (int i = 0; i < 32; i++) {
            payload.append("</b>");
        }
        assertEquals("X", parse(cue(payload.toString())).getCues().get(0).text);
    }

    @Test
    public void inputEntryAndMarkupBoundsAreEnforced() throws Exception {
        char[] large = new char[RawTimeline.MAX_DOCUMENT_BYTES];
        Arrays.fill(large, 'x');
        rejected(cue(new String(large)));
        StringBuilder document = new StringBuilder("WEBVTT\n\n");
        for (int i = 0; i < RawTimeline.MAX_CUES; i++) {
            document.append("00:00.000 --> 00:01.000\nX\n\n");
        }
        assertEquals(RawTimeline.MAX_CUES, parse(document.toString()).getCues().size());
        rejected(document + "00:00.000 --> 00:01.000\nX\n");
        StringBuilder nested = new StringBuilder();
        for (int i = 0; i < 33; i++) {
            nested.append("<b>");
        }
        nested.append('X');
        for (int i = 0; i < 33; i++) {
            nested.append("</b>");
        }
        rejected(cue(nested.toString()));
    }

    @Test
    public void invalidUtf8DoesNotSilentlyReplaceOriginalText() throws Exception {
        byte[] source = bytes(cue("X"));
        source[source.length - 2] = (byte) 0xFF;
        try {
            RawWebvttParser.parse(source, 0);
            fail("Expected malformed UTF-8 rejection");
        } catch (IllegalArgumentException expected) {
            assertEquals("Unsupported or invalid subtitle document", expected.getMessage());
            assertNull(expected.getCause());
        }
    }

    private static RawTimeline parse(String document) throws Exception {
        return RawWebvttParser.parse(bytes(document), 0);
    }

    private static String cue(String payload) {
        return "WEBVTT\n\n00:00.000 --> 00:01.000\n" + payload + "\n";
    }

    private static byte[] bytes(String text) throws Exception {
        return text.getBytes("UTF-8");
    }

    private static void rejected(String document) throws Exception {
        try {
            parse(document);
            fail("Expected unsupported or invalid fixture rejection");
        } catch (IllegalArgumentException expected) {
            assertEquals("Unsupported or invalid subtitle document", expected.getMessage());
            assertNull(expected.getCause());
        }
    }

    private static void assertCue(RawTimeline.Cue cue, int index, String id, long startUs, long endUs, String text) {
        assertEquals(index, cue.sourceIndex);
        assertEquals(id, cue.sourceId);
        assertEquals(startUs, cue.startUs);
        assertEquals(endUs, cue.endUs);
        assertEquals(text, cue.text);
    }
}
