package com.liskovsoft.smartyoutubetv2.common.subtitle.timeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Limited standalone VTT text/timestamp mapping. Unsupported display/clock semantics fail closed. */
public final class RawWebvttParser {
    private static final Pattern TIMING = Pattern.compile("([^ \\t]+)[ \\t]+-->[ \\t]+([^ \\t]+)(?:[ \\t]+(.*))?");
    private static final Pattern TIMESTAMP = Pattern.compile("(?:([0-9]{2,}):)?([0-9]{2}):([0-9]{2})\\.([0-9]{3})");

    private RawWebvttParser() {
    }

    static RawTimeline parse(byte[] source, long effectiveOffsetUs) {
        return parse(source, effectiveOffsetUs, RawTimeline.OFFLINE_LIMITS);
    }

    /** The caller supplies an already-resolved sample offset; X-TIMESTAMP-MAP is not supported. */
    public static RawTimeline parse(byte[] source, long effectiveOffsetUs, RawTimeline.Limits limits) {
        if (source == null || limits == null || source.length == 0 || source.length > limits.documentBytes) {
            throw RawTimeline.invalid();
        }
        byte[] snapshot = source.clone();
        String document = RawTimeline.decodeUtf8(snapshot, limits);
        if (document.indexOf('\0') >= 0) throw RawTimeline.invalid();
        if (document.startsWith("﻿")) {
            document = document.substring(1);
        }
        String[] lines = document.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        if (!(lines[0].equals("WEBVTT") || lines[0].startsWith("WEBVTT ") || lines[0].startsWith("WEBVTT\t")) ||
                lines[0].contains("-->")) {
            throw RawTimeline.invalid();
        }
        int line = 1;
        Map<String, String> metadata = new LinkedHashMap<>();
        while (line < lines.length && !lines[line].isEmpty()) {
            String header = lines[line++];
            int colon = header.indexOf(':');
            String key = colon < 0 ? "" : header.substring(0, colon);
            String value = colon < 0 ? "" : header.substring(colon + 1).trim();
            if (metadata.containsKey(key) || !("Kind".equals(key) &&
                    ("captions".equals(value) || "subtitles".equals(value)) ||
                    "Language".equals(key) && languageHeader(value))) {
                // Never ignore unknown headers, clock mapping, or ambiguous repeated declarations.
                throw RawTimeline.invalid();
            }
            metadata.put(key, value);
        }
        if (line >= lines.length) throw RawTimeline.invalid();
        List<RawTimeline.Cue> cues = new ArrayList<>();
        int totalRuns = 0;
        while (line < lines.length) {
            if (lines[line].isEmpty()) {
                line++;
                continue;
            }
            if (lines[line].equals("NOTE") || lines[line].startsWith("NOTE ") || lines[line].startsWith("NOTE\t")) {
                while (line < lines.length && !lines[line].isEmpty()) {
                    line++;
                }
                continue;
            }
            if (lines[line].equals("STYLE") || lines[line].equals("REGION")) {
                throw RawTimeline.invalid();
            }
            String id = null;
            if (!lines[line].contains("-->")) {
                id = lines[line++];
            }
            if (line >= lines.length) {
                throw RawTimeline.invalid();
            }
            Matcher timing = TIMING.matcher(lines[line++]);
            if (!timing.matches()) {
                throw RawTimeline.invalid();
            }
            Map<String, String> settings = settings(timing.group(3));
            long startUs = timestamp(timing.group(1));
            long endUs = timestamp(timing.group(2));
            if (endUs <= startUs || cues.size() == limits.cues) {
                throw RawTimeline.invalid();
            }
            StringBuilder payload = new StringBuilder();
            boolean first = true;
            while (line < lines.length && !lines[line].isEmpty()) {
                if (lines[line].contains("-->")) {
                    // Missing cue separators must not swallow another timing line as spoken text.
                    throw RawTimeline.invalid();
                }
                if (!first) {
                    payload.append('\n');
                }
                payload.append(lines[line++]);
                first = false;
            }
            RawTimeline.Cue cue = text(cues.size(), id, startUs, endUs, effectiveOffsetUs,
                    payload.toString(), limits.textRuns - totalRuns, settings);
            totalRuns += cue.runs.size();
            if (totalRuns > limits.textRuns) throw RawTimeline.invalid();
            cues.add(cue);
        }
        return new RawTimeline(snapshot, cues, limits, metadata);
    }

    /** The four scalar settings understood by the host's native VTT parser; layout remains native. */
    private static Map<String, String> settings(String value) {
        Map<String, String> result = new LinkedHashMap<>();
        if (value == null) return result;
        if (value.length() > 256) throw RawTimeline.invalid();
        int start = 0;
        while (start < value.length()) {
            if (value.charAt(start) == ' ' || value.charAt(start) == '\t') { start++; continue; }
            int end = start;
            while (end < value.length() && value.charAt(end) != ' ' && value.charAt(end) != '\t') end++;
            String token = value.substring(start, end);
            int colon = token.indexOf(':');
            if (colon <= 0 || token.indexOf(':', colon + 1) >= 0) throw RawTimeline.invalid();
            String name = token.substring(0, colon), setting = token.substring(colon + 1);
            if (setting.isEmpty() || setting.length() > 32 || result.containsKey(name)) throw RawTimeline.invalid();
            boolean valid;
            if ("align".equals(name)) {
                valid = anchor(setting) || "left".equals(setting) || "right".equals(setting);
            } else if ("size".equals(name)) {
                valid = percentage(setting);
            } else if ("line".equals(name) || "position".equals(name)) {
                int comma = setting.indexOf(',');
                String number = comma < 0 ? setting : setting.substring(0, comma);
                valid = (comma < 0 || anchor(setting.substring(comma + 1))) &&
                        (percentage(number) || "line".equals(name) && lineNumber(number));
            } else {
                // Region/vertical/clock extensions require another contract, not ignored tokens.
                valid = false;
            }
            if (!valid || result.size() == 4) throw RawTimeline.invalid();
            result.put(name, setting);
            start = end;
        }
        return result;
    }

    private static boolean anchor(String value) {
        return "start".equals(value) || "center".equals(value) || "middle".equals(value) || "end".equals(value);
    }

    private static boolean percentage(String value) {
        if (value.length() < 2 || value.charAt(value.length() - 1) != '%') return false;
        int whole = 0, digits = 0;
        boolean dot = false, fractionNonzero = false;
        for (int i = 0; i < value.length() - 1; i++) {
            char c = value.charAt(i);
            if (c == '.' && !dot && digits > 0) { dot = true; digits = 0; continue; }
            if (c < '0' || c > '9') return false;
            digits++;
            if (dot) fractionNonzero |= c != '0';
            else {
                whole = whole * 10 + c - '0';
                if (whole > 100) return false;
            }
        }
        return digits > 0 && (whole < 100 || !fractionNonzero);
    }

    private static boolean lineNumber(String value) {
        int start = value.startsWith("-") ? 1 : 0;
        if (start == value.length()) return false;
        long number = 0;
        for (int i = start; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') return false;
            number = number * 10 + c - '0';
            // Native decrements negative line values; Integer.MIN_VALUE would wrap around.
            if (number > Integer.MAX_VALUE) return false;
        }
        return true;
    }

    private static boolean languageHeader(String value) {
        if (value.length() < 2 || value.length() > 128) return false;
        int start = 0;
        while (start < value.length()) {
            int end = value.indexOf('-', start);
            if (end < 0) end = value.length();
            int length = end - start;
            if (length < (start == 0 ? 2 : 1) || length > 8) return false;
            for (int i = start; i < end; i++) {
                char c = value.charAt(i);
                if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
                        (start > 0 && c >= '0' && c <= '9'))) return false;
            }
            if (end == value.length()) return true;
            start = end + 1;
        }
        return false;
    }

    private static long timestamp(String value) {
        if (value.length() > 32) throw RawTimeline.invalid();
        Matcher match = TIMESTAMP.matcher(value);
        if (!match.matches()) {
            throw RawTimeline.invalid();
        }
        int minutes = Integer.parseInt(match.group(2));
        int seconds = Integer.parseInt(match.group(3));
        if (minutes >= 60 || seconds >= 60) {
            throw RawTimeline.invalid();
        }
        try {
            long hours = match.group(1) == null ? 0 : Long.parseLong(match.group(1));
            long remainder = minutes * 60_000_000L + seconds * 1_000_000L +
                    Integer.parseInt(match.group(4)) * 1000L;
            if (hours > (Long.MAX_VALUE - remainder) / 3_600_000_000L) throw RawTimeline.invalid();
            return hours * 3_600_000_000L + remainder;
        } catch (ArithmeticException | NumberFormatException e) {
            throw RawTimeline.invalid();
        }
    }

    private static RawTimeline.Cue text(int sourceIndex, String id, long startUs, long endUs,
                                         long offsetUs, String payload, int remainingRuns, Map<String, String> settings) {
        StringBuilder text = new StringBuilder();
        List<String> tags = new ArrayList<>();
        List<RawTimeline.TextRun> runs = new ArrayList<>();
        long activeStartUs = RawTimeline.add(startUs, offsetUs);
        long activeEndUs = RawTimeline.add(endUs, offsetUs);
        long windowStartUs = startUs;
        int runStart = 0;
        boolean annotated = false;
        for (int i = 0; i < payload.length();) {
            char value = payload.charAt(i);
            if (value == '<') {
                int end = payload.indexOf('>', i + 1);
                if (end == -1) {
                    throw RawTimeline.invalid();
                }
                String tag = payload.substring(i + 1, end);
                if (!tag.isEmpty() && tag.charAt(0) >= '0' && tag.charAt(0) <= '9') {
                    long nextUs = timestamp(tag);
                    if (nextUs <= windowStartUs || nextUs >= endUs) {
                        throw RawTimeline.invalid();
                    }
                    window(runs, runStart, text.length(), activeStartUs, activeEndUs,
                            RawTimeline.add(windowStartUs, offsetUs), RawTimeline.add(nextUs, offsetUs), remainingRuns);
                    annotated = true;
                    windowStartUs = nextUs;
                    runStart = text.length();
                } else if (tag.startsWith("/")) {
                    if (tags.isEmpty() || !tags.remove(tags.size() - 1).equals(tag.substring(1))) {
                        throw RawTimeline.invalid();
                    }
                } else {
                    if (tags.size() >= 32) {
                        throw RawTimeline.invalid();
                    }
                    tags.add(tagName(tag));
                }
                i = end + 1;
            } else if (value == '&') {
                String replacement = null;
                String[] names = {"amp;", "lt;", "gt;", "nbsp;", "lrm;", "rlm;"};
                String[] values = {"&", "<", ">", " ", "‎", "‏"};
                for (int j = 0; j < names.length; j++) {
                    if (payload.startsWith(names[j], i + 1)) {
                        replacement = values[j];
                        i += names[j].length() + 1;
                        break;
                    }
                }
                if (replacement == null) {
                    text.append(value);
                    i++;
                } else {
                    // Newly decoded '<' and '&' are text; never feed them through this scanner again.
                    text.append(replacement);
                }
            } else {
                text.append(value);
                i++;
            }
        }
        if (!tags.isEmpty()) {
            throw RawTimeline.invalid();
        }
        if (!annotated) {
            return new RawTimeline.Cue(sourceIndex, id, activeStartUs, activeEndUs, text.toString(), settings);
        }
        window(runs, runStart, text.length(), activeStartUs, activeEndUs,
                RawTimeline.add(windowStartUs, offsetUs), activeEndUs, remainingRuns);
        return new RawTimeline.Cue(sourceIndex, id, activeStartUs, activeEndUs, text.toString(), runs, false, settings);
    }

    private static void window(List<RawTimeline.TextRun> runs, int start, int end,
                               long activeStartUs, long activeEndUs, long timingStartUs, long timingEndUs,
                               int remainingRuns) {
        if (end == start) {
            return;
        }
        if (runs.size() >= remainingRuns) {
            throw RawTimeline.invalid();
        }
        runs.add(new RawTimeline.TextRun(start, end, activeStartUs, activeEndUs, timingStartUs, timingEndUs,
                RawTimeline.TimingKind.VTT_TIMESTAMP_WINDOW, false));
    }

    private static String tagName(String tag) {
        if (tag.equals("b") || tag.equals("i") || tag.equals("u") || tag.equals("c")) {
            return tag;
        }
        if (tag.startsWith("c.")) {
            boolean component = false;
            for (int i = 2; i < tag.length(); i++) {
                char value = tag.charAt(i);
                if (value == '.') {
                    if (!component) {
                        throw RawTimeline.invalid();
                    }
                    component = false;
                } else if ((value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z') ||
                        (value >= '0' && value <= '9') || value == '_' || value == '-') {
                    component = true;
                } else {
                    throw RawTimeline.invalid();
                }
            }
            if (component) {
                return "c";
            }
        }
        for (String name : new String[]{"v", "lang"}) {
            if ((tag.startsWith(name + " ") || tag.startsWith(name + "\t")) &&
                    !tag.substring(name.length()).trim().isEmpty() && tag.indexOf('<') == -1 &&
                    tag.indexOf('\n') == -1) {
                return name;
            }
        }
        // Ruby and unknown tags need a separate text/metadata contract.
        throw RawTimeline.invalid();
    }
}
