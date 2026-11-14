package com.liskovsoft.smartyoutubetv2.common.subtitle.translation;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class CaptionLineNormalizerTest {
    @Test
    public void preservesSingleLineInputsWithoutLineBreaksVerbatim() {
        String leadingSpaces = "  [[end]]";
        assertSame(leadingSpaces, CaptionLineNormalizer.normalize(leadingSpaces));
        String formFeed = "\f[[end]]";
        assertSame(formFeed, CaptionLineNormalizer.normalize(formFeed));
        String verticalTab = "\u000B[[p0]]";
        assertSame(verticalTab, CaptionLineNormalizer.normalize(verticalTab));
        String trailingSpace = "red ";
        assertSame(trailingSpace, CaptionLineNormalizer.normalize(trailingSpace));
        assertEquals("", CaptionLineNormalizer.normalize(null));
        assertEquals("", CaptionLineNormalizer.normalize(""));
    }

    @Test
    public void collapsesLatinCjkAndFullwidthPunctuationLineBreaksCleanly() {
        assertEquals("Just a few short words", CaptionLineNormalizer.normalize("Just a few\nshort words"));
        assertEquals("Hello world!", CaptionLineNormalizer.normalize("Hello\r\n  world!"));
        assertEquals("Hello world", CaptionLineNormalizer.normalize("Hello\r\n\n  \n  world"));
        assertEquals("第一行第二行", CaptionLineNormalizer.normalize("第一行\n第二行"));
        assertEquals("Hello，World", CaptionLineNormalizer.normalize("Hello，\nWorld"));
        assertEquals("中文（注释）", CaptionLineNormalizer.normalize("中文\n（注释）"));
        assertEquals("Use AI 模型", CaptionLineNormalizer.normalize("Use AI\n模型"));
        assertEquals("模型 AI", CaptionLineNormalizer.normalize("模型\nAI"));
        assertEquals("안녕하세요 반갑습니다", CaptionLineNormalizer.normalize("안녕하세요\n반갑습니다"));
        assertEquals("こんにちは世界", CaptionLineNormalizer.normalize("こんにちは\n世界"));
    }

    @Test
    public void handlesAstralCharactersAndPreservesOuterEdgeSpacing() {
        assertEquals("😀 😎", CaptionLineNormalizer.normalize("😀\n😎"));
        assertEquals("\uD840\uDC00\uD840\uDC01", CaptionLineNormalizer.normalize("\uD840\uDC00\n\uD840\uDC01"));
        assertEquals(" red blue ", CaptionLineNormalizer.normalize(" red \n blue "));
    }

    @Test
    public void appendInlineTrimsInnerSpacesAcrossSegmentBoundaries() {
        StringBuilder en = new StringBuilder();
        CaptionLineNormalizer.appendInline(en, "red ");
        CaptionLineNormalizer.appendInline(en, " \t ");
        CaptionLineNormalizer.appendInline(en, "  blue");
        assertEquals("red blue", en.toString());

        StringBuilder zh = new StringBuilder();
        CaptionLineNormalizer.appendInline(zh, "提示一 ");
        CaptionLineNormalizer.appendInline(zh, " 第二至八项");
        assertEquals("提示一第二至八项", zh.toString());
    }

    @Test
    public void joinLinesTrimsOuterBlankLinesAndMergesInternalLines() {
        assertEquals("", CaptionLineNormalizer.joinLines(null));
        assertEquals("", CaptionLineNormalizer.joinLines(Collections.<String>emptyList()));
        assertEquals("", CaptionLineNormalizer.joinLines(Arrays.asList("", "  \t  ")));
        assertEquals("  [[end]]", CaptionLineNormalizer.joinLines(Collections.singletonList("  [[end]]")));
        assertEquals("line one line two", CaptionLineNormalizer.joinLines(Arrays.asList("", "line one", "  ", "line two", " ")));
        assertEquals("第一行 [[p0]]", CaptionLineNormalizer.joinLines(Arrays.asList("第一行", "[[p0]]")));
    }
}
