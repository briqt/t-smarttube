package com.liskovsoft.smartyoutubetv2.common.subtitle.presentation;

import android.text.SpannableString;
import android.text.Spanned;
import android.text.SpannedString;
import android.text.TextUtils;

import com.google.android.exoplayer2.text.Cue;

import java.util.Collections;
import java.util.List;

/** A single text Cue for the existing native view; no view styles, placement, or native selection changes. */
public final class NativeCueComposer {
    public enum Mode { ORIGINAL, BILINGUAL, TRANSLATION }
    public enum Order { ORIGINAL_FIRST, TRANSLATION_FIRST }

    /**
     * Invoke inside the coordinator's guarded synchronous Sink, for one exact current source group.
     * A matching string alone is not a source/session identity. Original/NONE/fallback retain list identity.
     */
    public static List<Cue> compose(List<Cue> nativeCues, String expectedOriginal, String translation,
                                    Mode mode, Order order, int maxCodePoints) {
        return composeInternal(nativeCues, expectedOriginal, null, translation, mode, order, maxCodePoints);
    }

    /** Only for an independently validated same-video/native-window binding, never a text/language guess. */
    public static List<Cue> composeMapped(List<Cue> nativeCues, String expectedNative, String sourceOriginal,
                                          String translation, Mode mode, Order order, int maxCodePoints) {
        if (sourceOriginal == null || sourceOriginal.isEmpty()) return nativeCues;
        return composeInternal(nativeCues, expectedNative, sourceOriginal, translation, mode, order, maxCodePoints);
    }

    private static List<Cue> composeInternal(List<Cue> nativeCues, String expectedOriginal, String replacement,
                                             String translation, Mode mode, Order order, int maxCodePoints) {
        if (mode == null || order == null || maxCodePoints <= 0 || maxCodePoints > 131072) {
            throw new IllegalArgumentException("Invalid caption presentation profile");
        }
        if (mode == Mode.ORIGINAL || nativeCues == null || nativeCues.size() != 1 || translation == null || blank(translation)) {
            return nativeCues;
        }
        Cue cue = nativeCues.get(0);
        if (cue == null || cue.bitmap != null || cue.text == null || cue.text.length() == 0 ||
                !TextUtils.equals(cue.text, expectedOriginal) || cue.bitmapHeight != Cue.DIMEN_UNSET) return nativeCues;
        // This Exo version has no public constructor for simultaneous text size and window color.
        boolean sized = cue.textSizeType != Cue.TYPE_UNSET || cue.textSize != Cue.DIMEN_UNSET;
        if (sized && cue.windowColorSet) return nativeCues;
        CharSequence originalStyled = replacement == null || TextUtils.equals(cue.text, replacement) ? cue.text : replacement;
        String original = originalStyled.toString();
        if (!validUnicode(original)) return nativeCues;
        long characters = translation.codePointCount(0, translation.length());
        if (mode == Mode.BILINGUAL) characters += 1L + original.codePointCount(0, original.length());
        if (characters > maxCodePoints || !validUnicode(translation)) return nativeCues;
        CharSequence text;
        if (mode == Mode.TRANSLATION) {
            text = translation;
        } else {
            int offset = order == Order.ORIGINAL_FIRST ? 0 : translation.length() + 1;
            String joined = order == Order.ORIGINAL_FIRST ? original + "\n" + translation : translation + "\n" + original;
            if (originalStyled instanceof Spanned) {
                try {
                    SpannableString styled = new SpannableString(joined);
                    // Copy onto complete text so inclusive-end spans do not grow into the translation.
                    TextUtils.copySpansFrom((Spanned) originalStyled, 0, originalStyled.length(), Object.class, styled, offset);
                    text = new SpannedString(styled);
                } catch (RuntimeException ignored) {
                    // Paragraph spans may no longer have legal boundaries after text is appended.
                    return nativeCues;
                }
            } else {
                text = joined;
            }
        }
        Cue combined = sized ? new Cue(text, cue.textAlignment, cue.line, cue.lineType, cue.lineAnchor,
                cue.position, cue.positionAnchor, cue.size, cue.textSizeType, cue.textSize) :
                new Cue(text, cue.textAlignment, cue.line, cue.lineType, cue.lineAnchor,
                        cue.position, cue.positionAnchor, cue.size, cue.windowColorSet, cue.windowColor);
        return Collections.singletonList(combined);
    }

    static boolean blank(String text) {
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            if (!Character.isWhitespace(cp) && !Character.isSpaceChar(cp)) return false;
            i += Character.charCount(cp);
        }
        return true;
    }

    private static boolean validUnicode(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) return false;
            } else if (Character.isLowSurrogate(c)) return false;
        }
        return true;
    }

    private NativeCueComposer() { }
}
