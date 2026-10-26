package com.liskovsoft.smartyoutubetv2.common.subtitle.observation;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.*;
import static com.liskovsoft.smartyoutubetv2.common.subtitle.observation.CaptionSampleMetrics.Status.*;

public class CaptionSampleMetricsTest {
    @Test
    public void analyzesBlocksAndUnmappedDocumentClock() {
        String fixture = "﻿WEBVTT synthetic header\n"
                + "X-TIMESTAMP-MAP=LOCAL:00:30.000,MPEGTS:900000\n\n"
                + "NOTE synthetic comment <00:00.100>\n00:00.000 --> 00:59.000\n\n"
                + "STYLE \t\n::cue { color: lime; }\n\n"
                + "REGION\nid:synthetic-region\n\n"
                + "synthetic-id\n00:01.250 --> 00:03.500 align:start position:10%\n"
                + "<c.synthetic>one<00:02.000>two</c> &lt;00:02.250&gt;\n"
                + "00:40.000 --&gt; 00:50.000\n\n"
                + "01:02:03.004 --> 01:02:04.005\t \nthree <01:02:03.500>\n";
        CaptionSampleMetrics.Result r = inspect(fixture.replace("\n", "\r\n"));
        assertEquals(VTT_ANALYZED, r.status);
        assertEquals(utf8(fixture.replace("\n", "\r\n")).length, r.bytes);
        assertEquals(2, r.cueCount);
        assertEquals(1, r.cuesWithSettings);
        assertEquals(2, r.inlineTimestamps);
        assertTrue(r.hasTimestampMap);
        assertTrue(r.hasStyleBlock);
        assertTrue(r.hasRegionBlock);
        assertEquals(Long.valueOf(1_250_000L), r.minStartUs);
        assertEquals(Long.valueOf(3_724_005_000L), r.maxEndUs);
    }

    @Test
    public void acceptsLineEndingsEmptyDocumentsAndOrdinaryIds() {
        for (String newline : new String[]{"\n", "\r\n", "\r"}) {
            String text = "WEBVTT\n\nNOTEworthy\n00:01.000 --> 00:02.000\nx\n\n"
                    + "STYLE-id\n00:00.000 --> 00:03.000\ny\n\n"
                    + "REGION-id\n00:04.000 --> 00:05.000\nz";
            CaptionSampleMetrics.Result r = inspect(text.replace("\n", newline));
            assertEquals(VTT_ANALYZED, r.status);
            assertEquals(3, r.cueCount);
            assertEquals(Long.valueOf(0), r.minStartUs);
            assertEquals(Long.valueOf(5_000_000L), r.maxEndUs);
        }
        assertEmpty(inspect("﻿WEBVTT\tcomment\n\n"), VTT_ANALYZED);
    }

    @Test
    public void doesNotFindMetadataOrCuesInsideNotesAndPayload() {
        CaptionSampleMetrics.Result r = inspect("WEBVTT\n\nNOTE\tcomment\n"
                + "X-TIMESTAMP-MAP=synthetic\nSTYLE\nREGION\n<00:00.500>\n"
                + "00:00.000 --> 00:01.000\n\n00:02.000 --> 00:03.000\n"
                + "X-TIMESTAMP-MAP=synthetic\nSTYLE\nREGION\n"
                + "00:40.000 --&gt; 00:50.000\n");
        assertEquals(VTT_ANALYZED, r.status);
        assertEquals(1, r.cueCount);
        assertEquals(0, r.inlineTimestamps);
        assertFalse(r.hasTimestampMap);
        assertFalse(r.hasStyleBlock);
        assertFalse(r.hasRegionBlock);
        assertEquals(Long.valueOf(3_000_000L), r.maxEndUs);
    }

    @Test
    public void inlineCountsAreNumericShapesNotWordTimingEvidence() {
        CaptionSampleMetrics.Result r = inspect("WEBVTT\n\n00:00.000 --> 00:01.000\n"
                + "<00:00.100><01:02:03.004><99:99.999>"
                + " &lt;00:00.200&gt; <0:00.300> <00:00.0000> <c.00:00.400>\n");
        assertEquals(VTT_ANALYZED, r.status);
        assertEquals(3, r.inlineTimestamps);
    }

    @Test
    public void rejectsInvalidHeadersAndMisplacedBlocks() {
        for (String text : new String[]{"", "WEBVTTsuffix\n\n", "webvtt\n\n",
                " WEBVTT\n\n", "WEBVTT -->\n\n", "WEBVTT\0\n\n",
                "WEBVTT\n00:00.000 --> 00:01.000\nx",
                "WEBVTT\n\nnot-a-cue", "WEBVTT\n\nid\npayload\n00:00.000 --> 00:01.000",
                "WEBVTT\n\nSTYLE\n00:00.000 --> 00:01.000",
                "WEBVTT\n\nREGION\n00:00.000 --> 00:01.000",
                "WEBVTT\n\n00:00.000 --> 00:01.000\nx\n\nSTYLE\n::cue {}"}) {
            assertEmpty(inspect(text), INVALID);
        }
    }

    @Test
    public void missingCueSeparatorInvalidatesCoverage() {
        assertEmpty(inspect("WEBVTT\n\n00:00.000 --> 00:01.000\nx\n" +
                "00:02.000 --> 00:30.000\ny\n"), INVALID);
    }

    @Test
    public void rejectsMalformedUtf8RatherThanReplacingIt() {
        byte[] header = utf8("WEBVTT\n\nNOTE ");
        for (byte[] malformed : new byte[][]{{(byte) 0xc3, 0x28}, {(byte) 0xc0, (byte) 0xaf},
                {(byte) 0xed, (byte) 0xa0, (byte) 0x80}, {(byte) 0xf0, (byte) 0x9f}}) {
            byte[] data = Arrays.copyOf(header, header.length + malformed.length);
            System.arraycopy(malformed, 0, data, header.length, malformed.length);
            CaptionSampleMetrics.Result r = CaptionSampleMetrics.inspect(data, "text/vtt");
            assertEmpty(r, INVALID);
            assertEquals(data.length, r.bytes);
        }
    }

    @Test
    public void rejectsBadTimingRangesAndSyntax() {
        for (String timing : new String[]{"00:60.000 --> 01:01.000", "60:00.000 --> 61:00.000",
                "00:60:00.000 --> 01:01:00.000", "00:00:60.000 --> 00:01:01.000",
                "0:00.000 --> 00:01.000", "1:00:00.000 --> 02:00:00.000",
                "-00:01.000 --> 00:02.000", "00:00.0000 --> 00:01.000",
                "00:00,000 --> 00:01.000", "00:00.000-->00:01.000",
                "00:00.000 --> ٠٠:٠١.٠٠٠", "00:02.000 --> 00:01.000",
                "00:01.000 --> 00:01.000", "999999999999999999999:00:00.000 --> 00:01.000"}) {
            assertEmpty(inspect("WEBVTT\n\n" + timing + "\nx"), INVALID);
        }
    }

    @Test
    public void checksMicrosecondOverflowWithoutNumericExceptions() {
        CaptionSampleMetrics.Result r = inspect("WEBVTT\n\n"
                + "2562047788:00:54.774 --> 2562047788:00:54.775\nx");
        assertEquals(VTT_ANALYZED, r.status);
        assertEquals(Long.valueOf(9_223_372_036_854_774_000L), r.minStartUs);
        assertEquals(Long.valueOf(9_223_372_036_854_775_000L), r.maxEndUs);
        assertEmpty(inspect("WEBVTT\n\n00:00.000 --> 2562047788:00:54.776\nx"), INVALID);
        char[] digits = new char[32_768];
        Arrays.fill(digits, '9');
        assertEmpty(inspect("WEBVTT\n\n" + new String(digits) + ":00:00.000 --> 00:01.000"), INVALID);
    }

    @Test
    public void lateFailureDiscardsAllPartialMetricsAndSourceValues() {
        String sentinel = "synthetic-sensitive-marker";
        CaptionSampleMetrics.Result r = inspect("WEBVTT\nX-TIMESTAMP-MAP=" + sentinel
                + "\n\nSTYLE\n::cue { color: lime; }\n\nREGION\nid:" + sentinel
                + "\n\n" + sentinel + "\n00:01.000 --> 00:02.000 region:" + sentinel
                + "\n<c." + sentinel + ">https://example.invalid/synthetic<00:01.500></c>\n\n"
                + sentinel + "\n00:03.000 --> 999999999999999999999:00:00.000\nx");
        assertEmpty(r, INVALID);
        assertFalse(r.toString().contains(sentinel));
        assertFalse(r.toString().contains("example.invalid"));
        CaptionSampleMetrics.Result valid = inspect("WEBVTT\n\n" + sentinel
                + "\n00:00.000 --> 00:01.000 region:" + sentinel + "\n<c." + sentinel + ">x</c>");
        assertEquals(VTT_ANALYZED, valid.status);
        assertFalse(valid.toString().contains(sentinel));
    }

    @Test
    public void boundsBytesAndLeavesTtmlEntirelyOpaque() {
        byte[] opaque = {0, (byte) 0xff, '<'};
        CaptionSampleMetrics.Result ttml = CaptionSampleMetrics.inspect(opaque, "application/ttml+xml");
        assertEmpty(ttml, TTML_NOT_ANALYZED);
        assertEquals(opaque.length, ttml.bytes);
        byte[] maximum = new byte[CaptionSampleMetrics.MAX_BYTES];
        Arrays.fill(maximum, (byte) 'x');
        byte[] prefix = utf8("WEBVTT\n\nNOTE ");
        System.arraycopy(prefix, 0, maximum, 0, prefix.length);
        assertEmpty(CaptionSampleMetrics.inspect(maximum, "text/vtt"), VTT_ANALYZED);
        assertEquals(maximum.length, CaptionSampleMetrics.inspect(maximum, "text/vtt").bytes);
        byte[] oversized = Arrays.copyOf(maximum, maximum.length + 1);
        for (String type : new String[]{"text/vtt", "application/ttml+xml", null}) {
            CaptionSampleMetrics.Result r = CaptionSampleMetrics.inspect(oversized, type);
            assertEmpty(r, TOO_LARGE);
            assertEquals(oversized.length, r.bytes);
        }
    }

    @Test
    public void dispatchesOnlyDeclaredMimeTypesAndHandlesNulls() {
        byte[] bytes = utf8("WEBVTT\n\n");
        assertEmpty(CaptionSampleMetrics.inspect(bytes, " TEXT/VTT ; charset=utf-8"), VTT_ANALYZED);
        assertEmpty(CaptionSampleMetrics.inspect(bytes, "Application/TTML+XML; charset=utf-8"), TTML_NOT_ANALYZED);
        for (String type : new String[]{null, "", "application/octet-stream", "text/plain"}) {
            assertEmpty(CaptionSampleMetrics.inspect(bytes, type), UNSUPPORTED_FORMAT);
        }
        assertEmpty(CaptionSampleMetrics.inspect(null, "text/vtt"), INVALID);
        assertEmpty(CaptionSampleMetrics.inspect(new byte[0], "text/vtt"), INVALID);
    }

    @Test
    public void resultRetainsOnlyFinalSafeScalars() {
        for (Field field : CaptionSampleMetrics.Result.class.getDeclaredFields()) {
            assertTrue(Modifier.isFinal(field.getModifiers()));
            Class<?> type = field.getType();
            assertTrue(type == int.class || type == boolean.class || type == Long.class
                    || type == CaptionSampleMetrics.Status.class);
        }
        byte[] bytes = utf8("WEBVTT\n\n00:00.000 --> 00:01.000\nsynthetic");
        CaptionSampleMetrics.Result r = CaptionSampleMetrics.inspect(bytes, "text/vtt");
        String before = r.toString();
        Arrays.fill(bytes, (byte) 0xff);
        assertEquals(before, r.toString());
    }

    private static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static CaptionSampleMetrics.Result inspect(String text) {
        return CaptionSampleMetrics.inspect(utf8(text), "text/vtt");
    }
    private static void assertEmpty(CaptionSampleMetrics.Result r, CaptionSampleMetrics.Status status) {
        assertEquals(status, r.status);
        assertEquals(0, r.cueCount);
        assertEquals(0, r.cuesWithSettings);
        assertEquals(0, r.inlineTimestamps);
        assertFalse(r.hasTimestampMap);
        assertFalse(r.hasStyleBlock);
        assertFalse(r.hasRegionBlock);
        assertNull(r.minStartUs);
        assertNull(r.maxEndUs);
    }
}
