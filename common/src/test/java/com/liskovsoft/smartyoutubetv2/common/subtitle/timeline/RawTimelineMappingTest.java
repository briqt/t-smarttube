package com.liskovsoft.smartyoutubetv2.common.subtitle.timeline;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class RawTimelineMappingTest {
    @Test
    public void coarseFallbackDoesNotPretendToLocateIndividualWords() {
        RawTimeline.Cue cue = new RawTimeline.Cue(0, null, 1, 10, "Two words");
        assertEquals("Two words", cue.mappingText);
        assertEquals(1, cue.runs.size());
        assertEquals(RawTimeline.TimingKind.CUE_INTERVAL, cue.runs.get(0).timingKind);
        assertEquals("", cue.textAt(0));
        assertEquals("Two words", cue.textAt(1));
        assertEquals("", cue.textAt(10));
    }

    @Test
    public void timedInlineWhitespaceDoesNotLeakIntoAnInactiveTextProjection() {
        RawTimeline.Cue cue = cue("A B C", Arrays.asList(run(0, 1, 0, 4), run(1, 4, 1, 2), run(4, 5, 0, 4)), true);
        assertEquals("A B C", cue.text);
        assertEquals("AC", cue.textAt(0));
        assertEquals("A B C", cue.textAt(1));
        assertEquals("AC", cue.textAt(2));
        assertEquals("", cue.textAt(4));
    }

    @Test
    public void mappingOffsetsAreNotOffsetsIntoTheWhitespaceNormalizedView() {
        RawTimeline.Cue cue = cue("  A\t\n B  ", Arrays.asList(run(0, 9, 0, 4)), true);
        assertEquals("A B", cue.text);
        assertEquals("  A\t\n B  ", cue.mappingText);
        assertEquals(9, cue.runs.get(0).textEnd);
    }

    @Test
    public void explicitTimedBreaksAreDistinctFromXmlWhitespace() {
        List<RawTimeline.TextRun> runs = Arrays.asList(run(0, 1, 0, 4),
                new RawTimeline.TextRun(1, 2, 1, 2, 1, 2, RawTimeline.TimingKind.TTML_INTERVAL, true),
                run(2, 3, 0, 4));
        RawTimeline.Cue cue = cue("A\nB", runs, true);
        assertEquals("A\nB", cue.text);
        assertEquals("AB", cue.textAt(0));
        assertEquals("A\nB", cue.textAt(1));
        assertEquals("AB", cue.textAt(2));
    }

    @Test
    public void cueOwnsItsRunListAndDoesNotExposeMutableMappingState() {
        List<RawTimeline.TextRun> runs = new ArrayList<>();
        runs.add(run(0, 1, 0, 4));
        RawTimeline.Cue cue = cue("X", runs, false);
        runs.clear();
        assertEquals(1, cue.runs.size());
        try {
            cue.runs.clear();
            fail("Expected immutable runs");
        } catch (UnsupportedOperationException expected) {
            // Immutable ranges continue to reference the immutable mapping string.
        }
    }

    @Test
    public void rangesMustPartitionAllTextWithoutGapsDuplicationOrOutOfBounds() {
        for (List<RawTimeline.TextRun> runs : Arrays.asList(
                Arrays.asList(run(1, 2, 0, 4)), Arrays.asList(run(0, 1, 0, 4)),
                Arrays.asList(run(0, 2, 0, 4), run(1, 2, 0, 4)), Arrays.asList(run(0, 3, 0, 4)))) {
            invalid(() -> cue("AB", runs, false));
        }
    }

    @Test
    public void unicodeRangesCannotSplitASurrogatePair() {
        invalid(() -> cue("🙂", Arrays.asList(run(0, 1, 0, 4), run(1, 2, 0, 4)), false));
        RawTimeline.Cue cue = cue("🙂", Arrays.asList(run(0, 2, 0, 4)), false);
        assertEquals("🙂", cue.textAt(1));
    }

    @Test
    public void timingMustBePositiveContainedAndDistinctFromVisibilityIfNeeded() {
        invalid(() -> new RawTimeline.TextRun(0, 1, 1, 1, 1, 1, RawTimeline.TimingKind.CUE_INTERVAL, false));
        invalid(() -> new RawTimeline.TextRun(0, 1, 0, 4, 1, 1, RawTimeline.TimingKind.CUE_INTERVAL, false));
        invalid(() -> new RawTimeline.TextRun(0, 1, 0, 4, -1, 2, RawTimeline.TimingKind.CUE_INTERVAL, false));
        invalid(() -> new RawTimeline.TextRun(0, 1, 0, 4, 1, 5, RawTimeline.TimingKind.CUE_INTERVAL, false));
        invalid(() -> cue("X", Arrays.asList(run(0, 1, -1, 4)), false));
        invalid(() -> cue("X", Arrays.asList(run(0, 1, 0, 5)), false));
        RawTimeline.Cue cue = cue("X", Arrays.asList(new RawTimeline.TextRun(0, 1, 0, 4, 1, 2,
                RawTimeline.TimingKind.VTT_TIMESTAMP_WINDOW, false)), false);
        assertEquals("X", cue.textAt(0));
        assertEquals("X", cue.textAt(3));
    }

    @Test
    public void lineBreakRangesMustReferenceExactlyOneNewline() {
        invalid(() -> cue("X", Arrays.asList(new RawTimeline.TextRun(0, 1, 0, 4, 0, 4,
                RawTimeline.TimingKind.CUE_INTERVAL, true)), true));
        invalid(() -> cue("\n\n", Arrays.asList(new RawTimeline.TextRun(0, 2, 0, 4, 0, 4,
                RawTimeline.TimingKind.CUE_INTERVAL, true)), true));
    }

    private static RawTimeline.Cue cue(String text, List<RawTimeline.TextRun> runs, boolean xmlWhitespace) {
        return new RawTimeline.Cue(0, null, 0, 4, text, runs, xmlWhitespace);
    }

    private static RawTimeline.TextRun run(int start, int end, long activeStart, long activeEnd) {
        return new RawTimeline.TextRun(start, end, activeStart, activeEnd, activeStart, activeEnd,
                RawTimeline.TimingKind.TTML_INTERVAL, false);
    }

    private static void invalid(Runnable action) {
        try {
            action.run();
            fail("Expected invalid mapping rejection");
        } catch (IllegalArgumentException expected) {
            assertEquals(RawTimeline.invalid().getMessage(), expected.getMessage());
        }
    }
}
