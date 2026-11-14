package com.liskovsoft.smartyoutubetv2.common.subtitle.translation;

import java.util.List;

/** Collapses internal hard line breaks cleanly for CJK and Latin subtitle text. */
public final class CaptionLineNormalizer {
    /** Normalize a single text block to one line while preserving single-line text unchanged. */
    public static String normalize(String text) {
        if (text == null || text.isEmpty()) return "";
        if (text.indexOf('\n') < 0 && text.indexOf('\r') < 0) return text;
        String[] rawLines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < rawLines.length; i++) {
            String line = rawLines[i];
            int start = i > 0 ? skipHorizontalWhitespace(line, 0, line.length()) : 0;
            int end = i < rawLines.length - 1 ? trimHorizontalEnd(line, start, line.length()) : line.length();
            if (isHorizontalBlank(line, start, end)) continue;
            appendSegment(out, line, start, end);
        }
        return out.toString();
    }

    /** Append a normalized segment onto one display line, trimming whitespace across segment joins. */
    public static void appendInline(StringBuilder out, String segment) {
        String clean = normalize(segment);
        if (blank(clean)) return;
        if (out.length() == 0) {
            out.append(clean);
            return;
        }
        int trimOut = trimHorizontalEnd(out, 0, out.length());
        out.setLength(trimOut);
        int start = skipHorizontalWhitespace(clean, 0, clean.length());
        appendSegment(out, clean, start, clean.length());
    }

    /** Trim outer blank lines and collapse multi-line output items into a single line. */
    public static String joinLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) return "";
        int start = 0;
        int end = lines.size() - 1;
        while (start <= end && blank(lines.get(start))) start++;
        while (end >= start && blank(lines.get(end))) end--;
        if (start > end) return "";
        if (start == end) return normalize(lines.get(start));
        StringBuilder out = new StringBuilder();
        for (int i = start; i <= end; i++) {
            String line = normalize(lines.get(i));
            int segStart = i > start ? skipHorizontalWhitespace(line, 0, line.length()) : 0;
            int segEnd = i < end ? trimHorizontalEnd(line, segStart, line.length()) : line.length();
            if (isHorizontalBlank(line, segStart, segEnd)) continue;
            appendSegment(out, line, segStart, segEnd);
        }
        return out.toString();
    }

    static String separatorBetween(int prevCp, int nextCp) {
        if ((isNoSpaceCjkScript(prevCp) && isNoSpaceCjkScript(nextCp))
                || isFullwidthPunctuation(prevCp)
                || isFullwidthPunctuation(nextCp)) {
            return "";
        }
        return " ";
    }

    private static void appendSegment(StringBuilder out, String line, int start, int end) {
        if (start >= end) return;
        if (out.length() > 0) {
            int prevCp = Character.codePointBefore(out, out.length());
            int nextCp = line.codePointAt(start);
            out.append(separatorBetween(prevCp, nextCp));
        }
        out.append(line, start, end);
    }

    private static boolean isNoSpaceCjkScript(int cp) {
        // Han, Hiragana, Katakana, and Bopomofo write without inter-word spaces; Hangul uses spaces.
        return (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0x20000 && cp <= 0x2FA1F)
                || (cp >= 0x3040 && cp <= 0x30FF)
                || (cp >= 0x31F0 && cp <= 0x31FF)
                || (cp >= 0x3100 && cp <= 0x312F);
    }

    private static boolean isFullwidthPunctuation(int cp) {
        return (cp >= 0x3000 && cp <= 0x303F)
                || (cp >= 0xFE10 && cp <= 0xFE6F)
                || (cp >= 0xFF01 && cp <= 0xFF60)
                || (cp >= 0xFFE0 && cp <= 0xFFEE);
    }

    private static int skipHorizontalWhitespace(CharSequence text, int start, int end) {
        int i = start;
        while (i < end && isHorizontalWhitespace(text.charAt(i))) i++;
        return i;
    }

    private static int trimHorizontalEnd(CharSequence text, int start, int end) {
        int i = end;
        while (i > start && isHorizontalWhitespace(text.charAt(i - 1))) i--;
        return i;
    }

    private static boolean isHorizontalBlank(CharSequence text, int start, int end) {
        return skipHorizontalWhitespace(text, start, end) >= end;
    }

    private static boolean isHorizontalWhitespace(char c) {
        return c == ' ' || c == '\t';
    }

    private static boolean blank(String text) {
        if (text == null || text.isEmpty()) return true;
        return text.trim().isEmpty();
    }

    private CaptionLineNormalizer() { }
}
