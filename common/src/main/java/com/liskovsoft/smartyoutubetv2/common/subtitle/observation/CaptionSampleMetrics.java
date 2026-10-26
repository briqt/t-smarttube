package com.liskovsoft.smartyoutubetv2.common.subtitle.observation;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.Charset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded, text-free structural observations; not a full WebVTT conformance validator. */
final class CaptionSampleMetrics {
    static final int MAX_BYTES = 1024 * 1024;
    private static final Pattern TIMING = Pattern.compile(
            "[ \\t]*([^ \\t]+)[ \\t]+-->[ \\t]+([^ \\t]+)(?:[ \\t]+(.*))?");
    private static final Pattern TIMESTAMP = Pattern.compile(
            "(?:([0-9]{2,}):)?([0-5][0-9]):([0-5][0-9])\\.([0-9]{3})");
    private static final Pattern INLINE = Pattern.compile(
            "<(?:[0-9]{2,}:)?[0-9]{2}:[0-9]{2}\\.[0-9]{3}>");

    enum Status { VTT_ANALYZED, TTML_NOT_ANALYZED, INVALID, UNSUPPORTED_FORMAT, TOO_LARGE }

    static final class Result {
        final Status status;
        final int bytes;
        final int cueCount;
        final int cuesWithSettings;
        // Literal numeric-shaped tags only: neither validated alignment nor proof of word timing.
        final int inlineTimestamps;
        final boolean hasTimestampMap;
        final boolean hasStyleBlock;
        final boolean hasRegionBlock;
        // VTT document clock only; no sample offset or whole-video completeness inference.
        final Long minStartUs;
        final Long maxEndUs;

        private Result(Status status, int bytes, int cueCount, int cuesWithSettings,
                int inlineTimestamps, boolean hasTimestampMap, boolean hasStyleBlock,
                boolean hasRegionBlock, Long minStartUs, Long maxEndUs) {
            this.status = status;
            this.bytes = bytes;
            this.cueCount = cueCount;
            this.cuesWithSettings = cuesWithSettings;
            this.inlineTimestamps = inlineTimestamps;
            this.hasTimestampMap = hasTimestampMap;
            this.hasStyleBlock = hasStyleBlock;
            this.hasRegionBlock = hasRegionBlock;
            this.minStartUs = minStartUs;
            this.maxEndUs = maxEndUs;
        }

        @Override
        public String toString() {
            return "Result{status=" + status + ", bytes=" + bytes + ", cueCount=" + cueCount
                    + ", cuesWithSettings=" + cuesWithSettings + ", inlineTimestamps="
                    + inlineTimestamps + ", hasTimestampMap=" + hasTimestampMap
                    + ", hasStyleBlock=" + hasStyleBlock + ", hasRegionBlock=" + hasRegionBlock
                    + ", minStartUs=" + minStartUs + ", maxEndUs=" + maxEndUs + '}';
        }
    }

    private CaptionSampleMetrics() { }

    static Result inspect(byte[] bytes, String mimeType) {
        if (bytes == null) {
            return empty(Status.INVALID, 0);
        }
        int size = bytes.length;
        if (size > MAX_BYTES) {
            return empty(Status.TOO_LARGE, size);
        }
        String type = mimeType == null ? "" : mimeType.split(";", 2)[0].trim();
        if ("application/ttml+xml".equalsIgnoreCase(type)) {
            // Intentionally opaque, including encoding and XML validity.
            return empty(Status.TTML_NOT_ANALYZED, size);
        }
        if (!"text/vtt".equalsIgnoreCase(type)) {
            return empty(Status.UNSUPPORTED_FORMAT, size);
        }
        String text;
        try {
            text = Charset.forName("UTF-8").newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException ignored) {
            return empty(Status.INVALID, size);
        }
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        if (text.indexOf('\0') >= 0) {
            return empty(Status.INVALID, size);
        }
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        String header = lines[0];
        if (!(header.equals("WEBVTT") || header.startsWith("WEBVTT ")
                || header.startsWith("WEBVTT\t")) || header.contains("-->")) {
            return empty(Status.INVALID, size);
        }
        boolean map = false;
        int i = 1;
        while (i < lines.length && !lines[i].isEmpty()) {
            if (lines[i].contains("-->")) {
                return empty(Status.INVALID, size); // Missing header/block separator.
            }
            map |= lines[i].startsWith("X-TIMESTAMP-MAP=");
            i++;
        }
        int cues = 0;
        int settings = 0;
        int inline = 0;
        boolean style = false;
        boolean region = false;
        Long min = null;
        Long max = null;
        while (i < lines.length) {
            if (lines[i].isEmpty()) {
                i++;
                continue;
            }
            int start = i;
            while (i < lines.length && !lines[i].isEmpty()) {
                i++;
            }
            String first = lines[start];
            if (first.equals("NOTE") || first.startsWith("NOTE ") || first.startsWith("NOTE\t")) {
                continue; // NOTE contents are never cues or inline timestamps.
            }
            boolean styleBlock = marker(first, "STYLE");
            boolean regionBlock = marker(first, "REGION");
            if (styleBlock || regionBlock) {
                if (cues != 0) {
                    return empty(Status.INVALID, size);
                }
                for (int j = start + 1; j < i; j++) {
                    if (lines[j].contains("-->")) {
                        return empty(Status.INVALID, size);
                    }
                }
                style |= styleBlock;
                region |= regionBlock;
                continue;
            }
            int timingLine = start;
            Matcher timing = TIMING.matcher(first);
            if (!timing.matches()) {
                // At most one ordinary ID line; never search the payload for an arrow.
                if (first.contains("-->") || ++timingLine >= i) {
                    return empty(Status.INVALID, size);
                }
                timing = TIMING.matcher(lines[timingLine]);
                if (!timing.matches()) {
                    return empty(Status.INVALID, size);
                }
            }
            long from = timestampUs(timing.group(1));
            long to = timestampUs(timing.group(2));
            if (from < 0 || to <= from) {
                return empty(Status.INVALID, size); // Discard all partial metrics on failure.
            }
            cues++;
            if (timing.group(3) != null && !timing.group(3).trim().isEmpty()) {
                settings++; // Presence only, not validation or storage of setting values.
            }
            min = min == null ? from : Math.min(min, from);
            max = max == null ? to : Math.max(max, to);
            for (int j = timingLine + 1; j < i; j++) {
                if (lines[j].contains("-->")) {
                    return empty(Status.INVALID, size); // A missing separator must not hide later cue coverage.
                }
                Matcher tags = INLINE.matcher(lines[j]);
                while (tags.find()) {
                    inline++;
                }
            }
        }
        return new Result(Status.VTT_ANALYZED, size, cues, settings, inline,
                map, style, region, min, max);
    }

    private static boolean marker(String line, String name) {
        if (!line.startsWith(name)) {
            return false;
        }
        for (int i = name.length(); i < line.length(); i++) {
            if (line.charAt(i) != ' ' && line.charAt(i) != '\t') {
                return false;
            }
        }
        return true;
    }

    private static long timestampUs(String value) {
        Matcher time = TIMESTAMP.matcher(value);
        if (!time.matches()) {
            return -1;
        }
        long hours = 0;
        String hourDigits = time.group(1);
        if (hourDigits != null) {
            for (int i = 0; i < hourDigits.length(); i++) {
                int digit = hourDigits.charAt(i) - '0';
                if (hours > (Long.MAX_VALUE - digit) / 10) {
                    return -1;
                }
                hours = hours * 10 + digit;
            }
        }
        long remainder = (twoDigits(time.group(2)) * 60L + twoDigits(time.group(3)))
                * 1_000_000L + (twoDigits(time.group(4)) * 10L
                + time.group(4).charAt(2) - '0') * 1_000L;
        if (hours > (Long.MAX_VALUE - remainder) / 3_600_000_000L) {
            return -1;
        }
        return hours * 3_600_000_000L + remainder;
    }

    private static int twoDigits(String value) {
        return (value.charAt(0) - '0') * 10 + value.charAt(1) - '0';
    }

    private static Result empty(Status status, int bytes) {
        return new Result(status, bytes, 0, 0, 0, false, false, false, null, null);
    }
}
