package com.liskovsoft.smartyoutubetv2.common.subtitle.timeline;

import org.junit.Test;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class RawTimelineProductionTest {
    private static final RawTimeline.Limits LIMITS = new RawTimeline.Limits(1024 * 1024, 4096, 32768);

    @Test
    public void explicitProfileDoesNotInheritTheOld256CueFixtureBudget() {
        StringBuilder input = new StringBuilder("WEBVTT\n\n");
        for (int i = 0; i < 300; i++) input.append("same\n00:01.000 --> 00:02.000\nrepeat\n\n");
        RawTimeline timeline = parse(input.toString(), 0);
        assertEquals(300, timeline.getCues().size());
        assertEquals(300, timeline.getCuesAt(1_500_000).size());
        assertEquals("same", timeline.getCues().get(299).sourceId);
        rejected(() -> RawWebvttParser.parse(bytes(input.toString()), 0));
    }

    @Test
    public void byteCueAndRunBudgetsAreIndependentAndFailureNeverDeliversAPrefix() {
        String input = "WEBVTT\n\n00:00.000 --> 00:02.000\na<00:01.000>b\n\n" +
                "00:03.000 --> 00:05.000\nc<00:04.000>d\n";
        byte[] source = bytes(input);
        assertEquals(2, RawWebvttParser.parse(source, 0,
                new RawTimeline.Limits(source.length, 2, 4)).getCues().size());
        rejected(() -> RawWebvttParser.parse(source, 0, new RawTimeline.Limits(source.length - 1, 2, 4)));
        rejected(() -> RawWebvttParser.parse(source, 0, new RawTimeline.Limits(source.length, 1, 4)));
        rejected(() -> RawWebvttParser.parse(source, 0, new RawTimeline.Limits(source.length, 2, 3)));
    }

    @Test
    public void longAndOverlappingCuesKeepHalfOpenIntervalsAndDocumentOrder() {
        RawTimeline timeline = parse("WEBVTT\n\nB\n00:02.000 --> 00:04.000\nrepeat\n\n" +
                "A\n00:00.000 --> 00:30.000\nrepeat\n\nC\n00:05.000 --> 00:06.000\nlast\n", 0);
        List<RawTimeline.Cue> active = timeline.getCuesAt(3_000_000);
        assertEquals(2, active.size());
        assertEquals("B", active.get(0).sourceId);
        assertEquals("A", active.get(1).sourceId);
        assertEquals(1, timeline.getCuesAt(4_000_000).size());
        assertEquals(1, timeline.getCuesAt(15_000_000).size()); // No native ten-second crop.
        assertEquals("repeat", timeline.getCues().get(1).textAt(29_999_999));
        assertTrue(timeline.getCuesAt(30_000_000).isEmpty());
        assertEquals("", timeline.getCues().get(1).textAt(30_000_000));
    }

    @Test
    public void timestampWindowsPreserveVisibleTextAndDoNotInventWordTiming() {
        RawTimeline timeline = parse("WEBVTT\n\n00:00.000 --> 00:04.000\n" +
                "one two<00:02.000> three four\n", 7);
        RawTimeline.Cue cue = timeline.getCues().get(0);
        assertEquals(2, cue.runs.size());
        assertEquals("one two three four", cue.textAt(1_000_007));
        assertEquals("one two three four", cue.textAt(3_000_007));
        assertEquals(RawTimeline.TimingKind.VTT_TIMESTAMP_WINDOW, cue.runs.get(0).timingKind);
        assertEquals(7, cue.runs.get(0).timingStartUs);
        assertEquals(2_000_007, cue.runs.get(0).timingEndUs);
        assertEquals(cue.endUs, cue.runs.get(0).activeEndUs);
        assertEquals("one two", cue.mappingText.substring(cue.runs.get(0).textStart, cue.runs.get(0).textEnd));
    }

    @Test
    public void immutableDocumentAndListsDoNotRetainTheCallersMutableArrays() {
        byte[] source = bytes("WEBVTT\n\n00:00.000 --> 00:01.000\nKeep &lt;text&gt; 😀\n");
        byte[] original = source.clone();
        RawTimeline timeline = RawWebvttParser.parse(source, 0, LIMITS);
        Arrays.fill(source, (byte) '!');
        assertArrayEquals(original, timeline.copySource());
        byte[] secondCopy = timeline.copySource();
        Arrays.fill(secondCopy, (byte) '?');
        assertArrayEquals(original, timeline.copySource());
        assertEquals("Keep <text> 😀", timeline.getCues().get(0).text);
        try { timeline.getCues().clear(); fail(); } catch (UnsupportedOperationException expected) { }
        try { timeline.getCuesAt(1).clear(); fail(); } catch (UnsupportedOperationException expected) { }
        try { timeline.getCues().get(0).runs.clear(); fail(); } catch (UnsupportedOperationException expected) { }
    }

    @Test
    public void wholeCueSourceStaysCoarseAndDoesNotDeduplicateRepeatedSpeech() {
        RawTimeline timeline = parse("WEBVTT\n\n00:00.000 --> 00:02.000\nNo, no.\n\n" +
                "00:02.000 --> 00:04.000\nNo, no.\n", 0);
        assertEquals(2, timeline.getCues().size());
        for (RawTimeline.Cue cue : timeline.getCues()) {
            assertEquals(1, cue.runs.size());
            assertEquals(RawTimeline.TimingKind.CUE_INTERVAL, cue.runs.get(0).timingKind);
            assertEquals(cue.startUs, cue.runs.get(0).timingStartUs);
            assertEquals(cue.endUs, cue.runs.get(0).timingEndUs);
        }
        assertEquals(1, timeline.getCuesAt(2_000_000).size());
        assertEquals(1, timeline.getCuesAt(2_000_000).get(0).sourceIndex);
    }

    @Test
    public void negativeOffsetsStayValidAndApi17SafeAdditionChecksBothOverflowDirections() {
        RawTimeline timeline = parse("WEBVTT\n\n00:01.000 --> 00:02.000\nX\n", -3_000_000);
        assertEquals(-2_000_000, timeline.getCues().get(0).startUs);
        assertEquals(1, timeline.getCuesAt(-1_500_000).size());
        assertEquals(Long.MIN_VALUE, RawTimeline.add(Long.MIN_VALUE, 0));
        assertEquals(Long.MAX_VALUE, RawTimeline.add(Long.MAX_VALUE, 0));
        rejected(() -> RawTimeline.add(Long.MAX_VALUE, 1));
        rejected(() -> RawTimeline.add(Long.MIN_VALUE, -1));
        rejected(() -> parse("WEBVTT\n\n00:01.000 --> 00:02.000\nX\n", Long.MAX_VALUE));
    }

    @Test
    public void timestampConversionChecksMicrosWithoutBigIntegerWorkOnHugeInputs() {
        RawTimeline timeline = parse("WEBVTT\n\n2562047788:00:54.774 --> 2562047788:00:54.775\nX\n", 0);
        assertEquals(9_223_372_036_854_775_000L, timeline.getCues().get(0).endUs);
        rejected(() -> parse("WEBVTT\n\n00:00.000 --> 2562047788:00:54.776\nX\n", 0));
        char[] digits = new char[32768];
        Arrays.fill(digits, '9');
        rejected(() -> parse("WEBVTT\n\n" + new String(digits) + ":00:00.000 --> 00:01.000\nX\n", 0));
    }

    @Test
    public void unsupportedSettingsAndClockMetadataFailWithoutLeakingSource() {
        for (String input : new String[]{"WEBVTT\n\n00:00.000 --> 00:01.000\nsecret\0\n",
                "WEBVTT\nX-TIMESTAMP-MAP=secret\n\n",
                "WEBVTT\n\n00:00.000 --> 00:01.000 align:secret-invalid\nsecret\n",
                "WEBVTT\n\nSTYLE\nsecret\n", "WEBVTT\n\nREGION\nid:secret\n",
                "WEBVTT\n\n00:00.000 --> 00:01.000\n<ruby>secret</ruby>\n"}) {
            rejected(() -> parse(input, 0));
        }
    }

    @Test
    public void informationalKindAndLanguageArePreservedWithoutBecomingSourcePolicy() {
        RawTimeline raw = parse("WEBVTT\nKind: captions\nLanguage: en-US\n\n00:00.000 --> 00:01.000\nX\n", 0);
        assertEquals("captions", raw.getMetadata().get("Kind"));
        assertEquals("en-US", raw.getMetadata().get("Language"));
        assertEquals(2, raw.getMetadata().size());
        assertEquals("X", raw.getCues().get(0).text);
        try { raw.getMetadata().clear(); fail(); } catch (UnsupportedOperationException expected) { }
        for (String headers : new String[]{"Kind: captions\nKind: captions", "Language: en\nLanguage: fr",
                "Language: en-", "Language: en--US", "Language: secret/value", "Kind: unknown",
                "Kind: captions\nX-TIMESTAMP-MAP=LOCAL:00:00.000,MPEGTS:1", "Other: secret"}) {
            rejected(() -> parse("WEBVTT\n" + headers + "\n\n00:00.000 --> 00:01.000\nX\n", 0));
        }
    }

    @Test
    public void explicitLimitsRejectInvalidOrUnboundedProfiles() {
        for (int[] budget : new int[][]{{0, 1, 1}, {1048577, 1, 1}, {1, 0, 1}, {1, 8193, 1},
                {1, 1, 0}, {1, 1, 65537}}) {
            rejected(() -> new RawTimeline.Limits(budget[0], budget[1], budget[2]));
        }
        rejected(() -> RawWebvttParser.parse(bytes("WEBVTT\n\n"), 0, null));
        rejected(() -> RawWebvttParser.parse(null, 0, LIMITS));
    }

    private static RawTimeline parse(String input, long offsetUs) {
        return RawWebvttParser.parse(bytes(input), offsetUs, LIMITS);
    }
    private static byte[] bytes(String input) { return input.getBytes(Charset.forName("UTF-8")); }
    private static void rejected(Runnable action) {
        try { action.run(); fail("Expected safe rejection"); }
        catch (IllegalArgumentException expected) {
            assertEquals("Unsupported or invalid subtitle document", expected.getMessage());
            assertNull(expected.getCause());
        }
    }
}
