package com.liskovsoft.smartyoutubetv2.common.subtitle.timeline;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class RawWebvttSettingsTest {
    private static final RawTimeline.Limits LIMITS = new RawTimeline.Limits(65536, 256, 2048);

    @Test
    public void nativeSupportedSettingsArePreservedAsSeparateImmutableMetadata() {
        RawTimeline raw = parse(document("line:-2,end align:start position:0%,start size:95.5%", "caption"), 0);
        RawTimeline.Cue cue = raw.getCues().get(0);
        assertEquals(RawTimeline.Origin.DOCUMENT, raw.getOrigin());
        assertEquals("caption", cue.text);
        assertEquals("caption", cue.mappingText);
        assertEquals(4, cue.webvttSettings.size());
        assertEquals("-2,end", cue.webvttSettings.get("line"));
        assertEquals("start", cue.webvttSettings.get("align"));
        assertEquals("0%,start", cue.webvttSettings.get("position"));
        assertEquals("95.5%", cue.webvttSettings.get("size"));
        try { cue.webvttSettings.put("align", "end"); fail(); }
        catch (UnsupportedOperationException expected) { }
    }

    @Test
    public void nativeAlignmentAndAnchorAliasesAreNotRewritten() {
        for (String value : new String[]{"start", "center", "middle", "end", "left", "right"}) {
            assertEquals(value, parse(document("align:" + value, "x"), 0).getCues().get(0).webvttSettings.get("align"));
        }
        for (String value : new String[]{"start", "center", "middle", "end"}) {
            RawTimeline.Cue cue = parse(document("line:50%," + value + " position:50%," + value, "x"), 0).getCues().get(0);
            assertEquals("50%," + value, cue.webvttSettings.get("line"));
            assertEquals("50%," + value, cue.webvttSettings.get("position"));
        }
    }

    @Test
    public void fractionsAndSafeSignedIntegerLinesRetainExactSpelling() {
        for (String value : new String[]{"0%", "100%", "0.001%", "99.999%", "050.00%"}) {
            RawTimeline.Cue cue = parse(document("line:" + value + " position:" + value + " size:" + value, "x"), 0).getCues().get(0);
            assertEquals(value, cue.webvttSettings.get("line"));
            assertEquals(value, cue.webvttSettings.get("position"));
            assertEquals(value, cue.webvttSettings.get("size"));
        }
        for (String value : new String[]{"0", "-0", "1", "-1", "2147483647", "-2147483647"}) {
            assertEquals(value, parse(document("line:" + value, "x"), 0).getCues().get(0).webvttSettings.get("line"));
        }
    }

    @Test
    public void settingsOnlyAffectMetadataNotOriginalIntervalsOrInlineWindows() {
        RawTimeline raw = parse(document("align:start position:0%", "first<00:02.000><c> second</c>"), 7_000_001);
        RawTimeline.Cue cue = raw.getCues().get(0);
        assertEquals(8_000_001, cue.startUs);
        assertEquals(22_000_001, cue.endUs);
        assertEquals(2, cue.runs.size());
        assertEquals(9_000_001, cue.runs.get(0).timingEndUs);
        assertEquals(9_000_001, cue.runs.get(1).timingStartUs);
        assertEquals(RawTimeline.TimingKind.VTT_TIMESTAMP_WINDOW, cue.runs.get(1).timingKind);
        assertEquals("first second", cue.textAt(20_000_000));
        assertTrue(raw.getCuesAt(cue.endUs).isEmpty());
    }

    @Test
    public void syntheticRollingSettingsKeepAllSixCuesAndTheirOriginalRuns() throws Exception {
        byte[] source = fixture();
        RawTimeline raw = RawWebvttParser.parse(source, 0, LIMITS);
        assertArrayEquals(source, raw.copySource());
        assertEquals(6, raw.getCues().size());
        int runs = 0;
        for (RawTimeline.Cue cue : raw.getCues()) {
            assertEquals(2, cue.webvttSettings.size());
            assertEquals("start", cue.webvttSettings.get("align"));
            assertEquals("0%", cue.webvttSettings.get("position"));
            runs += cue.runs.size();
        }
        assertEquals(16, runs);
        assertEquals("", raw.getCues().get(0).text);
        assertEquals(10_000, raw.getCues().get(1).endUs - raw.getCues().get(1).startUs);
        assertTrue(raw.getCues().get(2).text.startsWith(raw.getCues().get(1).text + "\n"));
        assertEquals(raw.getCues().get(3).text, raw.getCues().get(2).text.split("\n")[1]);
        assertEquals(10_410_000, raw.getCues().get(5).endUs);
    }

    @Test
    public void mappingMatchesASettingsFreeControlWithoutChangingOrDeduplicatingText() throws Exception {
        String source = new String(fixture(), Charset.forName("UTF-8"));
        RawTimeline with = parse(source, 0);
        RawTimeline without = parse(source.replace(" align:start position:0%", ""), 0);
        MappedTranscript mapped = MappedTranscript.fromRaw(with, 2048);
        MappedTranscript control = MappedTranscript.fromRaw(without, 2048);
        assertEquals(control.getAtoms().size(), mapped.getAtoms().size());
        for (int i = 0; i < mapped.getAtoms().size(); i++) {
            MappedTranscript.Atom actual = mapped.getAtoms().get(i), expected = control.getAtoms().get(i);
            assertEquals(expected.text, actual.text);
            assertEquals(expected.flowIndex, actual.flowIndex);
            assertEquals(expected.origins.get(0).timingStartUs, actual.origins.get(0).timingStartUs);
            assertEquals(expected.origins.get(0).timingEndUs, actual.origins.get(0).timingEndUs);
        }
    }

    @Test
    public void tabsMultipleSpacesAndSettingOrderDoNotChangeValues() {
        RawTimeline.Cue cue = parse(document("\t size:100%  position:0%\talign:start\t", "x"), 0).getCues().get(0);
        assertEquals("start", cue.webvttSettings.get("align"));
        assertEquals("0%", cue.webvttSettings.get("position"));
        assertEquals("100%", cue.webvttSettings.get("size"));
    }

    @Test
    public void mapsAreIndependentAcrossCuesAndCopyCallerOwnedMetadata() {
        RawTimeline raw = parse(document("align:start", "x") + "\n00:16.000 --> 00:17.000 align:end\ny\n", 0);
        assertEquals("start", raw.getCues().get(0).webvttSettings.get("align"));
        assertEquals("end", raw.getCues().get(1).webvttSettings.get("align"));
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("align", "start");
        RawTimeline.Cue cue = new RawTimeline.Cue(0, null, 0, 10, "x", settings);
        settings.put("align", "end");
        settings.clear();
        assertEquals("start", cue.webvttSettings.get("align"));
        assertTrue(new RawTimeline.Cue(0, null, 0, 10, "x").webvttSettings.isEmpty());
    }

    @Test
    public void unknownUnimplementedAndMalformedSettingsStillFailClosed() {
        for (String settings : new String[]{"vertical:rl", "region:r", "foo:secret", "ALIGN:start", "align:",
                ":start", "align:start garbage", "position:0% secret", "align:start:secret", "align=start", "line:auto"}) {
            rejected(settings);
        }
    }

    @Test
    public void duplicateSettingsAreRejectedEvenWhenTheirValuesAreEqual() {
        for (String settings : new String[]{"align:start align:start", "align:start align:end", "line:0 line:0",
                "position:0% position:100%", "size:50% size:50%", "align:start\talign:start"}) {
            rejected(settings);
        }
    }

    @Test
    public void malformedOrOutOfRangePercentagesAreNotParsedByFloatGuessing() {
        for (String value : new String[]{"-1%", "+1%", "100.001%", "101%", "NaN%", "Infinity%", "1e1%",
                "%", ".5%", "5.%", "5..5%", "1,2%", "1%%", "1%junk", "999999999999999999999999999999999%"}) {
            for (String key : new String[]{"line", "position", "size"}) rejected(key + ":" + value);
        }
        rejected("position:50");
        rejected("size:50");
    }

    @Test
    public void invalidAnchorsAndUnsafeLineIntegersRemainRejected() {
        for (String value : new String[]{"0%,left", "0%,right", "0%,auto", "0%,", "0%,start,end", "auto", "-0%"}) {
            rejected("position:" + value);
        }
        for (String value : new String[]{"2147483648", "-2147483648", "+1", "--1", "1.5", "NaN", "1,left", "1,start,end"}) {
            rejected("line:" + value);
        }
        rejected("size:50%,start");
        rejected("align:START");
    }

    @Test
    public void malformedLaterSettingsRejectTheWholeDocumentWithoutReturningAnAcceptedPrefix() {
        try {
            parse(document("align:start", "safe") + "\n00:16.000 --> 00:17.000 position:101%\nsecret\n", 0);
            fail();
        } catch (IllegalArgumentException expected) {
            assertEquals("Unsupported or invalid subtitle document", expected.getMessage());
            assertNull(expected.getCause());
        }
    }

    @Test
    public void settingsCannotHideMissingCueSeparatorsOrAlterDocumentBudgets() throws Exception {
        String zeros = new String(new char[31]).replace('\0', '0');
        assertEquals(zeros + "%", parse(document("position:" + zeros + "%", "x"), 0).getCues().get(0).webvttSettings.get("position"));
        rejected("position:" + zeros + "0%");
        rejected("position:0%" + new String(new char[257]).replace('\0', ' '));
        try { parse(document("align:start", "a") + "00:16.000 --> 00:17.000 position:0%\nb\n", 0); fail(); }
        catch (IllegalArgumentException expected) { assertNull(expected.getCause()); }
        byte[] source = bytes(document("align:start position:0%", "a"));
        try { RawWebvttParser.parse(source, 0, new RawTimeline.Limits(source.length - 1, 2, 16)); fail(); }
        catch (IllegalArgumentException expected) { assertNull(expected.getCause()); }
        assertEquals(1, RawWebvttParser.parse(source, 0, new RawTimeline.Limits(source.length, 1, 1)).getCues().size());
    }

    private static RawTimeline parse(String document, long offset) {
        return RawWebvttParser.parse(bytes(document), offset, LIMITS);
    }
    private static String document(String settings, String text) {
        return "WEBVTT\n\n00:01.000 --> 00:15.000 " + settings + "\n" + text + "\n";
    }
    private static byte[] bytes(String source) { return source.getBytes(Charset.forName("UTF-8")); }
    private static void rejected(String settings) {
        try { parse(document(settings, "secret"), 0); fail("Expected safe settings rejection"); }
        catch (IllegalArgumentException expected) {
            assertEquals("Unsupported or invalid subtitle document", expected.getMessage());
            assertNull(expected.getCause());
        }
    }
    private static byte[] fixture() throws Exception {
        try (InputStream input = RawWebvttSettingsTest.class.getResourceAsStream("/subtitle/rolling_settings_synthetic.vtt");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            assertNotNull(input);
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }
}
