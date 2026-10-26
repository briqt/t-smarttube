package com.liskovsoft.smartyoutubetv2.common.subtitle.timeline;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.nio.charset.Charset;
import java.util.Arrays;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 27)
public class NativeSubtitleProjectionTest {
    private static final RawTimeline.Limits LIMITS = new RawTimeline.Limits(65536, 256, 2048);

    @Test
    public void originalSettingsIntervalsAndNativeDisplayCropRemainDifferentClocks() {
        byte[] source = bytes("WEBVTT\n\n00:01.000 --> 00:15.000 align:start\nhello\n");
        RawTimeline document = RawWebvttParser.parse(source, 4_000_000, LIMITS);
        assertEquals(RawTimeline.Origin.DOCUMENT, document.getOrigin());
        assertEquals(19_000_000, document.getCues().get(0).endUs);
        assertEquals("start", document.getCues().get(0).webvttSettings.get("align"));
        RawTimeline projection = NativeSubtitleProjection.parse(source, "text/vtt", 4_000_000, 30_000_000, LIMITS);
        assertEquals(RawTimeline.Origin.NATIVE_DISPLAY, projection.getOrigin());
        assertEquals(1, projection.getCues().size());
        RawTimeline.Cue cue = projection.getCues().get(0);
        assertEquals("hello", cue.text);
        assertEquals(5_000_000, cue.startUs);
        assertEquals(15_000_000, cue.endUs); // Native fork's 10-second display crop, not original cue timing.
        assertEquals(RawTimeline.TimingKind.NATIVE_DISPLAY_INTERVAL, cue.runs.get(0).timingKind);
        assertTrue(projection.getCuesAt(15_000_000).isEmpty());
        byte[] original = source.clone();
        Arrays.fill(source, (byte) '!');
        assertArrayEquals(original, projection.copySource());
    }

    @Test
    public void nativeTtmlHeadStylesDoNotBlockUsableTextButNeverClaimOriginalTiming() {
        byte[] source = bytes("<tt xmlns=\"http://www.w3.org/ns/ttml\" xmlns:tts=\"http://www.w3.org/ns/ttml#styling\">" +
                "<head><styling><style xml:id=\"s\" tts:color=\"yellow\"/></styling></head>" +
                "<body><div><p begin=\"1s\" end=\"4s\" style=\"s\">hello styled text</p></div></body></tt>");
        RawTimeline projection = NativeSubtitleProjection.parse(source, "application/ttml+xml", 2_000_000, 10_000_000, LIMITS);
        assertEquals(RawTimeline.Origin.NATIVE_DISPLAY, projection.getOrigin());
        assertEquals("hello styled text", projection.getCuesAt(3_000_000).get(0).text);
        assertTrue(projection.getCuesAt(6_000_000).isEmpty());
    }

    @Test
    public void malformedDocumentsUnsafeXmlAndExcessiveDepthRejectWithoutSourceInException() {
        for (String source : new String[]{"<!DOCTYPE tt [<!ENTITY x SYSTEM 'https://example.invalid/private'>]><tt>&x;</tt>",
                "<tt><body><p>secret-text"}) {
            invalid(() -> NativeSubtitleProjection.parse(bytes(source), "application/ttml+xml", 0, 10_000_000, LIMITS));
        }
        StringBuilder deep = new StringBuilder("<tt>");
        for (int i = 0; i < 40; i++) deep.append("<div>");
        for (int i = 0; i < 40; i++) deep.append("</div>");
        deep.append("</tt>");
        invalid(() -> NativeSubtitleProjection.parse(bytes(deep.toString()), "application/ttml+xml", 0, 10_000_000, LIMITS));
        invalid(() -> NativeSubtitleProjection.parse(bytes("not a vtt secret"), "text/vtt", 0, 10_000_000, LIMITS));
    }

    @Test
    public void overBudgetDoesNotReturnAnAcceptedPrefixOrInventWordClocks() {
        byte[] source = bytes("WEBVTT\n\n00:01.000 --> 00:02.000\nfirst\n\n00:03.000 --> 00:04.000\nsecond\n");
        invalid(() -> NativeSubtitleProjection.parse(source, "text/vtt", 0, 10_000_000, new RawTimeline.Limits(4096, 1, 16)));
        RawTimeline projection = NativeSubtitleProjection.parse(source, "text/vtt", 0, 10_000_000, LIMITS);
        assertEquals(2, projection.getCues().size());
        MappedTranscript mapped = MappedTranscript.fromRaw(projection, 16);
        MappedTranscript.Reference reference = mapped.references(new MappedTranscript.Slice(0, 0, 1)).get(0);
        assertEquals(1_000_000, reference.origin.timingStartUs);
        assertEquals(2_000_000, reference.origin.timingEndUs);
        assertFalse(reference.wholeRun);
    }

    private static byte[] bytes(String value) { return value.getBytes(Charset.forName("UTF-8")); }
    private static void invalid(Runnable task) {
        try { task.run(); fail("Expected bounded native fallback rejection"); }
        catch (IllegalArgumentException expected) { assertEquals("Unsupported or invalid subtitle document", expected.getMessage()); assertNull(expected.getCause()); }
    }
}
