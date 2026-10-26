package com.liskovsoft.smartyoutubetv2.common.subtitle.timeline;

import org.junit.Test;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class SemanticGroupProductionTest {
    private static final SemanticGroupPlanner.Limits LIMITS = new SemanticGroupPlanner.Limits(4096, 8192, 8192);

    @Test
    public void preserveKeepsOriginalCuesWithoutSentenceOrLengthCutsAndRetainsDuplicates() {
        MappedTranscript mapped = mapped("WEBVTT\n\n00:01.000 --> 00:20.000\nFirst. Second!\n\n" +
                "00:01.000 --> 00:20.000\nFirst. Second!\n");
        SemanticGroupPlanner.Plan plan = SemanticGroupPlanner.preserve(mapped, LIMITS);
        assertEquals(2, plan.getGroups().size());
        assertTrue(plan.isFor(mapped));
        for (int i = 0; i < 2; i++) {
            assertEquals("First. Second!", plan.getText(i));
            assertEquals(i, plan.getFlowIndex(i));
            assertEquals(SemanticGroupPlanner.Boundary.FLOW_END, plan.getGroups().get(i).boundary);
            assertEquals(i, plan.getReferences(i).get(0).origin.sourceIndex);
            assertTrue(plan.getReferences(i).get(0).wholeRun);
        }
    }

    @Test
    public void explicitCandidatesKeepFullCoverageAndCoarseClocksAfterTextCuts() {
        MappedTranscript mapped = mapped("WEBVTT\n\n00:01.000 --> 00:20.000\nDr. Lee paid 3.14. Next!\n");
        SemanticGroupPlanner.Plan plan = SemanticGroupPlanner.candidates(mapped, 100, LIMITS);
        assertEquals(2, plan.getGroups().size());
        assertEquals("Dr. Lee paid 3.14.", plan.getText(0));
        assertEquals(" Next!", plan.getText(1));
        int cursor = 0;
        for (int i = 0; i < 2; i++) {
            MappedTranscript.Reference ref = plan.getReferences(i).get(0);
            assertEquals(cursor, ref.textStart);
            cursor += plan.getText(i).length();
            assertEquals(cursor, ref.textEnd);
            assertFalse(ref.wholeRun);
            assertEquals(1_000_000, ref.origin.timingStartUs);
            assertEquals(20_000_000, ref.origin.timingEndUs);
        }
        assertEquals(mapped.getAtoms().get(0).text.length(), cursor);
    }

    @Test
    public void referencesFollowTimestampWindowsAndWhitespaceIsNeverDropped() {
        MappedTranscript mapped = mapped("WEBVTT\n\n00:01.000 --> 00:05.000\nA<00:02.000>B\nC.  \n");
        SemanticGroupPlanner.Plan plan = SemanticGroupPlanner.preserve(mapped, LIMITS);
        assertEquals("AB\nC.  ", plan.getText(0));
        List<MappedTranscript.Reference> refs = plan.getReferences(0);
        assertEquals(2, refs.size());
        assertEquals(1_000_000, refs.get(0).origin.timingStartUs);
        assertEquals(2_000_000, refs.get(1).origin.timingStartUs);
        assertEquals(1_000_000, refs.get(1).origin.activeStartUs);
        assertEquals(5_000_000, refs.get(1).origin.activeEndUs);
    }

    @Test
    public void groupSliceAndAliasedReferenceLimitsAreIndependentAndRejectWholePlan() {
        MappedTranscript twoCues = mapped("WEBVTT\n\n00:01.000 --> 00:02.000\nx\n\n00:03.000 --> 00:04.000\ny\n");
        invalid(() -> SemanticGroupPlanner.preserve(twoCues, new SemanticGroupPlanner.Limits(1, 2, 2)));
        MappedTranscript twoRuns = mapped("WEBVTT\n\n00:01.000 --> 00:05.000\na<00:02.000>b\n");
        invalid(() -> SemanticGroupPlanner.preserve(twoRuns, new SemanticGroupPlanner.Limits(1, 1, 2)));
        invalid(() -> SemanticGroupPlanner.candidates(twoRuns, 100, new SemanticGroupPlanner.Limits(1, 1, 2)));
        MappedTranscript aliases = aliased();
        invalid(() -> SemanticGroupPlanner.preserve(aliases, new SemanticGroupPlanner.Limits(1, 1, 1)));
        SemanticGroupPlanner.Plan plan = SemanticGroupPlanner.preserve(aliases, new SemanticGroupPlanner.Limits(1, 1, 2));
        assertEquals(2, plan.getReferences(0).size());
        assertEquals(0, plan.getReferences(0).get(0).origin.sourceIndex);
        assertEquals(1, plan.getReferences(0).get(1).origin.sourceIndex);
        invalid(() -> SemanticGroupPlanner.candidates(aliases, 1, new SemanticGroupPlanner.Limits(10, 10, 2)));
    }

    @Test
    public void explicitProfilesExceedHistoricalCueAndTextBudgetsWithoutChangingOfflineContract() {
        StringBuilder text = new StringBuilder("WEBVTT\n\n");
        for (int i = 0; i < 2050; i++) text.append("00:01.000 --> 00:02.000\nx\n\n");
        SemanticGroupPlanner.Plan plan = SemanticGroupPlanner.preserve(mapped(text.toString()), LIMITS);
        assertEquals(2050, plan.getGroups().size());
        StringBuilder longCue = new StringBuilder("WEBVTT\n\n00:01.000 --> 00:02.000\n");
        for (int i = 0; i < 70000; i++) longCue.append('x');
        plan = SemanticGroupPlanner.candidates(mapped(longCue.toString()), 70000, new SemanticGroupPlanner.Limits(1, 1, 1));
        assertEquals(70000, plan.getText(0).length());
    }

    @Test
    public void tinyBudgetRetainsEmojiAndTrailingWhitespaceAndHasNoPartialSuccessOnOverflow() {
        MappedTranscript mapped = mapped("WEBVTT\n\n00:01.000 --> 00:02.000\n😀。  \n");
        SemanticGroupPlanner.Plan plan = SemanticGroupPlanner.candidates(mapped, 1, LIMITS);
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < plan.getGroups().size(); i++) {
            String part = plan.getText(i);
            assertEquals(1, part.codePointCount(0, part.length()));
            all.append(part);
        }
        assertEquals("😀。  ", all.toString());
        invalid(() -> SemanticGroupPlanner.candidates(mapped, 1, new SemanticGroupPlanner.Limits(3, 10, 10)));
    }

    @Test
    public void planIdentityAndReturnedViewsCannotBeMixedOrMutated() {
        String source = "WEBVTT\n\n00:01.000 --> 00:02.000\nx\n";
        MappedTranscript mapped = mapped(source);
        SemanticGroupPlanner.Plan plan = SemanticGroupPlanner.preserve(mapped, LIMITS);
        assertFalse(plan.isFor(mapped(source)));
        try { plan.getGroups().clear(); fail(); } catch (UnsupportedOperationException expected) { }
        try { plan.getGroups().get(0).slices.clear(); fail(); } catch (UnsupportedOperationException expected) { }
        try { plan.getReferences(0).clear(); fail(); } catch (UnsupportedOperationException expected) { }
        invalid(() -> plan.getText(-1));
        invalid(() -> plan.getReferences(1));
        invalid(() -> plan.getFlowIndex(1));
    }

    @Test
    public void invalidLimitsAndEmptyInputHaveExplicitContracts() {
        MappedTranscript empty = mapped("WEBVTT\n\n");
        assertTrue(SemanticGroupPlanner.preserve(empty, LIMITS).getGroups().isEmpty());
        assertTrue(SemanticGroupPlanner.candidates(empty, 1, LIMITS).getGroups().isEmpty());
        invalid(() -> new SemanticGroupPlanner.Limits(0, 1, 1));
        invalid(() -> new SemanticGroupPlanner.Limits(1, 65537, 1));
        invalid(() -> new SemanticGroupPlanner.Limits(1, 1, Integer.MAX_VALUE));
        invalid(() -> SemanticGroupPlanner.preserve(null, LIMITS));
        invalid(() -> SemanticGroupPlanner.preserve(empty, null));
        invalid(() -> SemanticGroupPlanner.candidates(empty, 0, LIMITS));
        invalid(() -> SemanticGroupPlanner.candidates(empty, 1024 * 1024 + 1, LIMITS));
    }

    @Test
    public void timeQueryUsesHalfOpenActiveIntervalsInDocumentOrderAndKeepsCoarseCandidatesSimultaneous() {
        MappedTranscript mapped = mapped("WEBVTT\n\n00:05.000 --> 00:30.000\nlate\n\n" +
                "00:01.000 --> 00:10.000\nFirst. Second!\n");
        SemanticGroupPlanner.Plan preserved = SemanticGroupPlanner.preserve(mapped, LIMITS);
        assertTrue(preserved.getGroupsAt(999_999).isEmpty());
        assertEquals(Collections.singletonList(1), preserved.getGroupsAt(1_000_000));
        assertEquals(Arrays.asList(0, 1), preserved.getGroupsAt(5_000_000));
        assertEquals(Collections.singletonList(0), preserved.getGroupsAt(10_000_000));
        assertTrue(preserved.getGroupsAt(30_000_000).isEmpty());
        SemanticGroupPlanner.Plan candidates = SemanticGroupPlanner.candidates(mapped, 100, LIMITS);
        assertEquals(Arrays.asList(1, 2), candidates.getGroupsAt(1_000_000));
        try { candidates.getGroupsAt(1_000_000).clear(); fail(); } catch (UnsupportedOperationException expected) { }
    }

    @Test
    public void timestampWindowDoesNotHideFutureTextAndInactiveGapsAreNotFilled() {
        MappedTranscript mapped = mapped("WEBVTT\n\n00:01.000 --> 00:05.000\nFirst.<00:03.000> Second!\n");
        SemanticGroupPlanner.Plan candidates = SemanticGroupPlanner.candidates(mapped, 100, LIMITS);
        assertEquals(Arrays.asList(0, 1), candidates.getGroupsAt(1_000_000));
        assertEquals(Arrays.asList(0, 1), candidates.getGroupsAt(4_000_000));
        assertTrue(candidates.getGroupsAt(5_000_000).isEmpty());
        // Synthetic disjoint origin intervals verify that an interval hull would be incorrect.
        RawTimeline raw = new RawTimeline(new byte[]{1}, Arrays.asList(
                new RawTimeline.Cue(0, null, 0, 2, "xy"), new RawTimeline.Cue(1, null, 4, 6, "xy")));
        MappedTranscript.Atom atom = new MappedTranscript.Atom(0, 0, "xy", Arrays.asList(
                new MappedTranscript.Origin(0, raw.getCues().get(0), 0),
                new MappedTranscript.Origin(1, raw.getCues().get(1), 0)));
        SemanticGroupPlanner.Plan aliases = SemanticGroupPlanner.preserve(
                new MappedTranscript(raw, Collections.singletonList(atom)), LIMITS);
        assertEquals(Collections.singletonList(0), aliases.getGroupsAt(0));
        assertTrue(aliases.getGroupsAt(3).isEmpty());
        assertEquals(Collections.singletonList(0), aliases.getGroupsAt(4));
        assertTrue(aliases.getGroupsAt(6).isEmpty());
    }

    private static MappedTranscript mapped(String document) {
        RawTimeline raw = RawWebvttParser.parse(document.getBytes(Charset.forName("UTF-8")), 0,
                new RawTimeline.Limits(1024 * 1024, 4096, 8192));
        return MappedTranscript.fromRaw(raw, 8192);
    }

    /** A synthetic alias contract only; no production source currently asserts rolling groups. */
    private static MappedTranscript aliased() {
        RawTimeline raw = new RawTimeline(new byte[]{1}, Arrays.asList(
                new RawTimeline.Cue(0, null, 0, 2, "xy"), new RawTimeline.Cue(1, null, 1, 3, "xy")));
        MappedTranscript.Atom atom = new MappedTranscript.Atom(0, 0, "xy", Arrays.asList(
                new MappedTranscript.Origin(0, raw.getCues().get(0), 0),
                new MappedTranscript.Origin(1, raw.getCues().get(1), 0)));
        return new MappedTranscript(raw, Collections.singletonList(atom));
    }

    private static void invalid(Runnable action) {
        try {
            action.run();
            fail("Expected complete plan rejection");
        } catch (IllegalArgumentException expected) {
            assertEquals("Unsupported or invalid subtitle document", expected.getMessage());
        }
    }
}
