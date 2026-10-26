package com.liskovsoft.smartyoutubetv2.common.subtitle.presentation;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.text.style.AlignmentSpan;

import com.google.android.exoplayer2.text.CaptionStyleCompat;
import com.google.android.exoplayer2.text.Cue;
import com.google.android.exoplayer2.ui.SubtitleView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 27)
public class NativeCueComposerTest {
    @Test
    public void originalAndNonePathsAreObjectIdenticalAndNeverReviveCaptions() {
        List<Cue> original = Collections.singletonList(new Cue("source"));
        assertSame(original, compose(original, "source", "译文", NativeCueComposer.Mode.ORIGINAL));
        assertSame(original, compose(original, "source", null, NativeCueComposer.Mode.BILINGUAL));
        assertSame(original, compose(original, "source", " \n ", NativeCueComposer.Mode.TRANSLATION));
        List<Cue> none = Collections.emptyList();
        assertSame(none, compose(none, "source", "译文", NativeCueComposer.Mode.BILINGUAL));
        assertNull(compose(null, "source", "译文", NativeCueComposer.Mode.BILINGUAL));
    }

    @Test
    public void oneBilingualCuePreservesOriginalPositionWindowAndAlignment() {
        Cue source = new Cue("source", Layout.Alignment.ALIGN_OPPOSITE, .8f, Cue.LINE_TYPE_FRACTION,
                Cue.ANCHOR_TYPE_END, .2f, Cue.ANCHOR_TYPE_START, .7f, true, Color.RED);
        List<Cue> result = compose(Collections.singletonList(source), "source", "译文", NativeCueComposer.Mode.BILINGUAL);
        assertEquals(1, result.size());
        Cue combined = result.get(0);
        assertEquals("source\n译文", combined.text.toString());
        assertMetadata(source, combined);
        assertEquals("source", source.text.toString());
    }

    @Test
    public void translationOrderChangesTextOnlyNotScreenPlacement() {
        Cue source = new Cue("source", Layout.Alignment.ALIGN_NORMAL, -1, Cue.LINE_TYPE_NUMBER,
                Cue.ANCHOR_TYPE_END, .4f, Cue.ANCHOR_TYPE_MIDDLE, .8f);
        Cue first = NativeCueComposer.compose(Collections.singletonList(source), "source", "译文",
                NativeCueComposer.Mode.BILINGUAL, NativeCueComposer.Order.TRANSLATION_FIRST, 100).get(0);
        assertEquals("译文\nsource", first.text.toString());
        assertMetadata(source, first);
    }

    @Test
    public void explicitTextSizeIsPreservedWithoutSettingViewStyles() {
        Cue source = new Cue("source", Layout.Alignment.ALIGN_CENTER, .8f, Cue.LINE_TYPE_FRACTION,
                Cue.ANCHOR_TYPE_END, .5f, Cue.ANCHOR_TYPE_MIDDLE, .9f, Cue.TEXT_SIZE_TYPE_ABSOLUTE, 28);
        Cue translated = compose(Collections.singletonList(source), "source", "译文", NativeCueComposer.Mode.TRANSLATION).get(0);
        assertEquals("译文", translated.text.toString());
        assertMetadata(source, translated);
    }

    @Test
    public void inclusiveOriginalSpansDoNotExpandIntoTranslationAndInputsAreNotMutated() {
        SpannableString source = new SpannableString("source");
        StyleSpan bold = new StyleSpan(Typeface.BOLD);
        source.setSpan(bold, 0, source.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        List<Cue> cues = Collections.singletonList(new Cue(source));
        Spanned result = (Spanned) compose(cues, "source", "译文", NativeCueComposer.Mode.BILINGUAL).get(0).text;
        assertEquals(0, result.getSpanStart(bold));
        assertEquals(6, result.getSpanEnd(bold));
        assertEquals(6, source.getSpanEnd(bold));
        Spanned reversed = (Spanned) NativeCueComposer.compose(cues, "source", "译文", NativeCueComposer.Mode.BILINGUAL,
                NativeCueComposer.Order.TRANSLATION_FIRST, 100).get(0).text;
        assertEquals(3, reversed.getSpanStart(bold));
        assertEquals(9, reversed.getSpanEnd(bold));
        assertEquals(0, source.getSpanStart(bold));
    }

    @Test
    public void validParagraphSpanCannotBecomeAPlaybackExceptionWhenAppendingTranslation() {
        SpannableString source = new SpannableString("hello");
        source.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), 0, source.length(), Spanned.SPAN_PARAGRAPH);
        List<Cue> cues = Collections.singletonList(new Cue(source));
        assertSame(cues, compose(cues, "hello", "你好", NativeCueComposer.Mode.BILINGUAL));
        assertEquals("hello", source.toString());
    }

    @Test
    public void multipleCuesBitmapsMismatchesAndUncopyableMetadataFallBackUnchanged() throws Exception {
        List<Cue> overlapping = Arrays.asList(new Cue("one"), new Cue("two"));
        assertSame(overlapping, compose(overlapping, "two", "译文", NativeCueComposer.Mode.BILINGUAL));
        Bitmap bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        List<Cue> image = Collections.singletonList(new Cue(bitmap, .5f, Cue.ANCHOR_TYPE_MIDDLE, .8f, Cue.ANCHOR_TYPE_END, .5f, .2f));
        assertSame(image, compose(image, "source", "译文", NativeCueComposer.Mode.BILINGUAL));
        List<Cue> original = Collections.singletonList(new Cue("different source"));
        assertSame(original, compose(original, "source", "译文", NativeCueComposer.Mode.TRANSLATION));
        Cue impossibleToCopy = new Cue("source", Layout.Alignment.ALIGN_CENTER, .8f, Cue.LINE_TYPE_FRACTION,
                Cue.ANCHOR_TYPE_END, .5f, Cue.ANCHOR_TYPE_MIDDLE, .9f, true, Color.BLUE);
        // A synthetic metadata combination this old Exo has no public copy constructor for.
        Field size = Cue.class.getDeclaredField("textSizeType");
        size.setAccessible(true);
        size.setInt(impossibleToCopy, Cue.TEXT_SIZE_TYPE_ABSOLUTE);
        List<Cue> unsupported = Collections.singletonList(impossibleToCopy);
        assertSame(unsupported, compose(unsupported, "source", "译文", NativeCueComposer.Mode.BILINGUAL));
    }

    @Test
    public void noWhitespaceNormalizationFactTruncationOrSurrogateSplittingForBudgets() {
        Cue cue = new Cue(" original \nline ");
        List<Cue> cues = Collections.singletonList(cue);
        List<Cue> result = compose(cues, cue.text.toString(), " 译文 😀 ", NativeCueComposer.Mode.BILINGUAL);
        assertEquals(" original \nline \n 译文 😀 ", result.get(0).text.toString());
        assertSame(cues, NativeCueComposer.compose(cues, cue.text.toString(), "译文😀", NativeCueComposer.Mode.BILINGUAL,
                NativeCueComposer.Order.ORIGINAL_FIRST, 2));
        assertSame(cues, compose(cues, cue.text.toString(), "x\ud800", NativeCueComposer.Mode.BILINGUAL));
    }

    @Test
    public void nativeViewRetainsOneComposedCueAndExistingStyleSettings() throws Exception {
        SubtitleView view = new SubtitleView(RuntimeEnvironment.getApplication());
        CaptionStyleCompat style = new CaptionStyleCompat(Color.YELLOW, Color.BLACK, Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLUE, Typeface.DEFAULT_BOLD);
        view.setStyle(style);
        view.setApplyEmbeddedStyles(false);
        view.setFractionalTextSize(.07f);
        view.setBottomPaddingFraction(.12f);
        List<Cue> cues = compose(Collections.singletonList(new Cue("source")), "source", "译文", NativeCueComposer.Mode.BILINGUAL);
        view.setCues(cues);
        assertSame(cues, field(view, "cues"));
        assertSame(style, field(view, "style"));
        assertEquals(false, field(view, "applyEmbeddedStyles"));
        assertEquals(.07f, (Float) field(view, "textSize"), 0);
        assertEquals(.12f, (Float) field(view, "bottomPaddingFraction"), 0);
        assertEquals("source\n译文", cues.get(0).text.toString());
        view.setCues(Arrays.asList(new Cue("source"), new Cue("译文")));
        assertEquals(1, ((List<?>) field(view, "cues")).size()); // Existing fork's last-cue baseline.
        assertEquals("译文", ((List<Cue>) field(view, "cues")).get(0).text.toString());
    }

    private static List<Cue> compose(List<Cue> cues, String original, String translation, NativeCueComposer.Mode mode) {
        return NativeCueComposer.compose(cues, original, translation, mode, NativeCueComposer.Order.ORIGINAL_FIRST, 100);
    }

    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static void assertMetadata(Cue expected, Cue actual) {
        assertSame(expected.textAlignment, actual.textAlignment);
        assertSame(expected.bitmap, actual.bitmap);
        assertEquals(expected.line, actual.line, 0);
        assertEquals(expected.lineType, actual.lineType);
        assertEquals(expected.lineAnchor, actual.lineAnchor);
        assertEquals(expected.position, actual.position, 0);
        assertEquals(expected.positionAnchor, actual.positionAnchor);
        assertEquals(expected.size, actual.size, 0);
        assertEquals(expected.bitmapHeight, actual.bitmapHeight, 0);
        assertEquals(expected.windowColorSet, actual.windowColorSet);
        assertEquals(expected.windowColor, actual.windowColor);
        assertEquals(expected.textSizeType, actual.textSizeType);
        assertEquals(expected.textSize, actual.textSize, 0);
    }
}
