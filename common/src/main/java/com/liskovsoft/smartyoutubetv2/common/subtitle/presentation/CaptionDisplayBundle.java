package com.liskovsoft.smartyoutubetv2.common.subtitle.presentation;

import com.liskovsoft.smartyoutubetv2.common.subtitle.session.CaptionSession;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.TranslationCoordinator;

import java.util.List;

/** One bounded, complete same-flow display unit. Contains sensitive text, not new source timing. */
public final class CaptionDisplayBundle {
    public final String original;
    public final String translation;
    public final int groups;

    private CaptionDisplayBundle(String original, String translation, int groups) {
        this.original = original;
        this.translation = translation;
        this.groups = groups;
    }

    /** Invoke only inside the current selection/frame/native-stamp execution guards. */
    public static CaptionDisplayBundle create(CaptionSession.Snapshot snapshot, TranslationCoordinator.Frame frame,
                                              int maxGroups, int maxCodePoints) {
        if (snapshot == null || frame == null || !frame.isFor(snapshot) || maxGroups <= 0 || maxGroups > 64 ||
                maxCodePoints <= 0 || maxCodePoints > 131072 || frame.items.isEmpty() || frame.items.size() > maxGroups) return null;
        List<Integer> active = snapshot.groups.getGroupsAt(frame.positionUs);
        if (active.size() != frame.items.size()) return null;
        long codePoints = 2L * (active.size() - 1);
        int flow = -1;
        for (int i = 0; i < active.size(); i++) {
            TranslationCoordinator.Item item = frame.items.get(i);
            if (item == null || item.groupIndex != active.get(i) || item.original == null || blank(item.translation) ||
                    !item.original.equals(snapshot.groups.getText(item.groupIndex))) return null;
            int currentFlow = snapshot.groups.getFlowIndex(item.groupIndex);
            // Independent overlapping cues may represent different speakers/regions, not one bundle.
            if (flow >= 0 && flow != currentFlow) return null;
            flow = currentFlow;
            codePoints += item.original.codePointCount(0, item.original.length()) +
                    (long) item.translation.codePointCount(0, item.translation.length());
            if (codePoints > maxCodePoints) return null;
        }
        StringBuilder original = new StringBuilder(), translated = new StringBuilder();
        for (TranslationCoordinator.Item item : frame.items) {
            appendDisplayBlock(original, item.original);
            appendDisplayBlock(translated, item.translation);
        }
        return new CaptionDisplayBundle(original.toString(), translated.toString(), frame.items.size());
    }

    /** Display separators only: the Plan, request text and source references retain their real newlines. */
    private static void appendDisplayBlock(StringBuilder output, String text) {
        int start = 0, end = text.length();
        while (start < end && (text.charAt(start) == '\n' || text.charAt(start) == '\r')) start++;
        while (end > start && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == '\r')) end--;
        if (start == end) return;
        if (output.length() > 0) output.append('\n');
        output.append(text, start, end);
    }

    private static boolean blank(String text) {
        if (text == null || text.isEmpty()) return true;
        for (int at = 0; at < text.length();) {
            int cp = text.codePointAt(at);
            if (!Character.isWhitespace(cp) && !Character.isSpaceChar(cp)) return false;
            at += Character.charCount(cp);
        }
        return true;
    }
}
