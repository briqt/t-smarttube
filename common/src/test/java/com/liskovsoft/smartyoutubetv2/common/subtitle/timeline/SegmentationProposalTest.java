package com.liskovsoft.smartyoutubetv2.common.subtitle.timeline;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SegmentationProposalTest {
    @Test
    public void adjacentAnchorsCanFormOneUnitWithoutCreatingTiming() {
        SegmentationProposal proposal = proposal("we are", " still talking", " now.");
        SegmentationProposal.Decision decision = exact(proposal,
                unit("p0", "p1", "p2", "we are still talking now."));
        assertTrue(decision.accepted());
        assertEquals(1, decision.units.size());
        assertEquals("we are still talking now.", decision.units.get(0).originalText);
        assertSame(proposal.getPieces().get(0), decision.units.get(0).pieces.get(0));
    }

    @Test
    public void missingDuplicateUnknownAndReorderedRefsRejectTheWholeProposal() {
        SegmentationProposal proposal = proposal("we ", "are ", "now");
        for (SegmentationProposal.Unit unit : Arrays.asList(unit("p0", "we "),
                unit("p0", "p0", "p2", "we we now"), unit("p0", "p2", "we now"),
                unit("p1", "p0", "p2", "are we now"), unit("p0", "p1", "p99", "we are now"))) {
            SegmentationProposal.Decision decision = exact(proposal, unit);
            assertFalse(decision.accepted());
            assertFallback(decision, "we are now");
        }
    }

    @Test
    public void aLateInvalidUnitDoesNotPublishAnAcceptedPrefix() {
        SegmentationProposal proposal = proposal("First. ", "Second.");
        SegmentationProposal.Decision decision = exact(proposal,
                unit("p0", "First. "), unit("unknown", "bad"));
        assertFalse(decision.accepted());
        assertFallback(decision, "First. Second.");
    }

    @Test
    public void evenACompleteLookingProposalRequiresConfirmedSuccessfulCompletion() {
        SegmentationProposal proposal = proposal("x");
        for (SegmentationProposal.Completion completion : Arrays.asList(null,
                SegmentationProposal.Completion.TRUNCATED, SegmentationProposal.Completion.FAILED,
                SegmentationProposal.Completion.UNKNOWN)) {
            SegmentationProposal.Decision decision = proposal.evaluate(Collections.singletonList(unit("p0", "x")),
                    SegmentationProposal.TextPolicy.EXACT, completion);
            assertEquals(SegmentationProposal.Problem.INCOMPLETE_COMPLETION, decision.problem);
            assertFallback(decision, "x");
        }
    }

    @Test
    public void exactPolicyRejectsWordChangesPunctuationChangesAndCaseChanges() {
        SegmentationProposal proposal = proposal("we ", "are ", "now");
        for (String changed : Arrays.asList("we are now!", "We are now", "we were now", "we are now soon", "we are")) {
            SegmentationProposal.Decision decision = exact(proposal, unit("p0", "p1", "p2", changed));
            assertEquals(SegmentationProposal.Problem.TEXT_CHANGED, decision.problem);
            assertFallback(decision, "we are now");
        }
    }

    @Test
    public void crossFlowMergeIsRejectedButSeparateUnitsMayCoverBothFlows() {
        SegmentationProposal proposal = proposal(new String[][]{{"Hello ", "world"}, {"Again"}});
        SegmentationProposal.Decision decision = exact(proposal, unit("p0", "p1", "p2", "Hello worldAgain"));
        assertEquals(SegmentationProposal.Problem.CROSS_FLOW, decision.problem);
        assertFallback(decision, "Hello world", "Again");
        assertTrue(exact(proposal, unit("p0", "p1", "Hello world"), unit("p2", "Again")).accepted());
    }

    @Test
    public void explicitEnglishPolicyCanAddBoundaryPunctuationAndAsciiCase() {
        SegmentationProposal proposal = proposal("hello world", " next");
        SegmentationProposal.Decision decision = english(proposal, unit("p0", "p1", "Hello, world. Next!"));
        assertTrue(decision.accepted());
        assertEquals("hello world next", decision.units.get(0).originalText);
        assertEquals("Hello, world. Next!", decision.units.get(0).suggestedSource);
    }

    @Test
    public void englishPolicyCannotRemoveOrReplaceOriginalPunctuationOrWhitespace() {
        SegmentationProposal proposal = proposal("hello, world. next!");
        for (String changed : Arrays.asList("Hello world. next!", "Hello, world next!", "Hello, world. next",
                "Hello; world. next!", "Hello,  world. next!", "Hello,world. next!")) {
            assertFalse(english(proposal, unit("p0", changed)).accepted());
        }
        assertTrue(english(proposal, unit("p0", "Hello, world. Next!")).accepted());
    }

    @Test
    public void englishPolicyCannotChangeApostrophesHyphensNegationNumbersOrWordOrder() {
        SegmentationProposal proposal = proposal("we can't re-sign 3.14 or go now");
        for (String changed : Arrays.asList("we cant re-sign 3.14 or go now", "we can re-sign 3.14 or go now",
                "we can't resign 3.14 or go now", "we can't re-sign 3,14 or go now",
                "we can't re-sign 3.15 or go now", "we can't re-sign 3.14 or now go")) {
            assertFalse(english(proposal, unit("p0", changed)).accepted());
        }
    }

    @Test
    public void addedPunctuationCannotSplitWordsOrDecimals() {
        for (String changed : Arrays.asList("hel.lo world", "hello wor!ld", "hello wor ld", "hello worlds")) {
            assertFalse(english(proposal("hello world"), unit("p0", changed)).accepted());
        }
        assertFalse(english(proposal("3.14"), unit("p0", "3..14")).accepted());
        assertTrue(english(proposal("hello world"), unit("p0", "\"Hello world.\"")).accepted());
    }

    @Test
    public void unitEdgesCannotInventPunctuationInsideOriginalWordsOrDecimals() {
        MappedTranscript word = transcript(new String[][]{{"hello"}});
        SegmentationProposal splitWord = new SegmentationProposal(word, Arrays.asList(
                new MappedTranscript.Slice(0, 0, 2), new MappedTranscript.Slice(0, 2, 5)), 100, 10);
        assertFalse(english(splitWord, unit("p0", "He."), unit("p1", "llo")).accepted());
        assertFalse(english(splitWord, unit("p0", "He"), unit("p1", ".llo")).accepted());
        assertTrue(english(splitWord, unit("p0", "He"), unit("p1", "llo.")).accepted());
        assertFalse(english(proposal("he", "llo"), unit("p0", "He."), unit("p1", "llo")).accepted());

        MappedTranscript number = transcript(new String[][]{{"3.14"}});
        SegmentationProposal splitNumber = new SegmentationProposal(number, Arrays.asList(
                new MappedTranscript.Slice(0, 0, 1), new MappedTranscript.Slice(0, 1, 4)), 100, 10);
        assertFalse(english(splitNumber, unit("p0", "3."), unit("p1", ".14")).accepted());
        assertTrue(english(splitNumber, unit("p0", "3"), unit("p1", ".14")).accepted());
    }

    @Test
    public void exactUnicodeTextIsNotNormalizedByTheEnglishPolicy() {
        assertTrue(english(proposal("🙂 café é 中文"), unit("p0", "🙂 Café é 中文.")).accepted());
        assertFalse(english(proposal("é"), unit("p0", "é")).accepted());
        assertFalse(english(proposal("ı"), unit("p0", "I")).accepted());
        assertFalse(english(proposal("Straße"), unit("p0", "STRASSE")).accepted());
    }

    @Test
    public void preallocatedPartialAnchorsKeepSourceWindowsAndEveryAlias() {
        String source = "hello world";
        List<RawTimeline.Cue> cues = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            cues.add(new RawTimeline.Cue(i, null, i * 10, 1000 + i * 10, source,
                    Collections.singletonList(new RawTimeline.TextRun(0, source.length(), i * 10, 1000 + i * 10,
                            100, 900, RawTimeline.TimingKind.VTT_TIMESTAMP_WINDOW, false)), false));
        }
        MappedTranscript transcript = RollingTextNormalizer.normalize(new RawTimeline(new byte[]{1}, cues), new int[]{1, 1});
        SegmentationProposal proposal = new SegmentationProposal(transcript, Arrays.asList(
                new MappedTranscript.Slice(0, 0, 5), new MappedTranscript.Slice(0, 5, 11)), 100, 10);
        SegmentationProposal.Decision decision = english(proposal, unit("p0", "Hello."), unit("p1", " world."));
        assertTrue(decision.accepted());
        for (SegmentationProposal.AcceptedUnit accepted : decision.units) {
            for (SegmentationProposal.Piece piece : accepted.pieces) {
                List<MappedTranscript.Reference> refs = transcript.references(piece.slice);
                assertEquals(2, refs.size());
                for (MappedTranscript.Reference ref : refs) {
                    assertFalse(ref.wholeRun);
                    assertEquals(100, ref.origin.timingStartUs);
                    assertEquals(900, ref.origin.timingEndUs);
                }
            }
        }
    }

    @Test
    public void anchorCoverageMustBeCompleteOrderedAndSurrogateSafe() {
        MappedTranscript transcript = transcript(new String[][]{{"🙂hello"}});
        invalid(() -> new SegmentationProposal(transcript, Collections.singletonList(new MappedTranscript.Slice(0, 1, 7)), 100, 2));
        invalid(() -> new SegmentationProposal(transcript, Collections.singletonList(new MappedTranscript.Slice(0, 0, 1)), 100, 2));
        invalid(() -> new SegmentationProposal(transcript, Collections.singletonList(new MappedTranscript.Slice(0, 0, 2)), 100, 2));
        invalid(() -> new SegmentationProposal(transcript, Arrays.asList(new MappedTranscript.Slice(0, 0, 2),
                new MappedTranscript.Slice(0, 0, 7)), 100, 2));
        assertEquals(2, new SegmentationProposal(transcript, Arrays.asList(new MappedTranscript.Slice(0, 0, 2),
                new MappedTranscript.Slice(0, 2, 7)), 100, 2).getPieces().size());
    }

    @Test
    public void outputBudgetsAreInclusiveAndFailuresPreserveAllInput() {
        MappedTranscript transcript = transcript(new String[][]{{"one", " two", " three"}});
        SegmentationProposal proposal = prepare(transcript, 13, 2);
        assertTrue(exact(proposal, unit("p0", "p1", "p2", "one two three")).accepted());
        SegmentationProposal.Decision units = exact(proposal,
                unit("p0", "one"), unit("p1", " two"), unit("p2", " three"));
        assertEquals(SegmentationProposal.Problem.OVER_BUDGET, units.problem);
        assertFallback(units, "one two three");
        SegmentationProposal.Decision text = english(prepare(transcript, 12, 2), unit("p0", "p1", "p2", "one two three"));
        assertEquals(SegmentationProposal.Problem.OVER_BUDGET, text.problem);
        assertFallback(text, "one two three");
        assertTrue(exact(proposal("🙂"), unit("p0", "🙂")).accepted());
    }

    @Test
    public void nullUnitsOrFieldsAndEmptyOutputFallbackWithoutExceptions() {
        SegmentationProposal proposal = proposal("x");
        for (List<SegmentationProposal.Unit> output : Arrays.<List<SegmentationProposal.Unit>>asList(
                null, Collections.singletonList(null), Collections.singletonList(new SegmentationProposal.Unit(null, "x")),
                Collections.singletonList(new SegmentationProposal.Unit(Collections.singletonList("p0"), null)),
                Collections.singletonList(new SegmentationProposal.Unit(Collections.emptyList(), "x")))) {
            assertEquals(SegmentationProposal.Problem.MISSING_OUTPUT,
                    proposal.evaluate(output, SegmentationProposal.TextPolicy.EXACT, SegmentationProposal.Completion.COMPLETE).problem);
        }
        assertEquals(SegmentationProposal.Problem.INCOMPLETE, exact(proposal).problem);
        assertEquals(SegmentationProposal.Problem.MISSING_OUTPUT, proposal.evaluate(Collections.emptyList(), null, SegmentationProposal.Completion.COMPLETE).problem);
        assertTrue(exact(proposal()).accepted());
    }

    @Test
    public void inputAndDecisionListsAreDefensivelyCopiedAndImmutable() {
        SegmentationProposal proposal = proposal("x");
        List<String> refs = new ArrayList<>(Collections.singletonList("p0"));
        SegmentationProposal.Unit unit = new SegmentationProposal.Unit(refs, "x");
        refs.clear();
        assertTrue(exact(proposal, unit).accepted());
        SegmentationProposal.Decision decision = exact(proposal, unit);
        immutable(() -> proposal.getPieces().clear());
        immutable(() -> decision.units.clear());
        immutable(() -> decision.units.get(0).pieces.clear());
        immutable(() -> unit.sourceRefs.clear());
        SegmentationProposal.Decision rejected = exact(proposal);
        immutable(() -> rejected.units.clear());
    }

    @Test
    public void constructionThresholdsAreExplicitAndBounded() {
        MappedTranscript transcript = transcript(new String[][]{{"x"}});
        for (int budget : new int[]{0, -1, RawTimeline.MAX_DOCUMENT_BYTES + 1, Integer.MAX_VALUE}) {
            invalid(() -> prepare(transcript, budget, 1));
        }
        for (int count : new int[]{0, -1, RawTimeline.MAX_TEXT_RUNS + 1, Integer.MAX_VALUE}) {
            invalid(() -> prepare(transcript, 100, count));
        }
        invalid(() -> new SegmentationProposal(null, Collections.emptyList(), 100, 1));
        invalid(() -> new SegmentationProposal(transcript, null, 100, 1));
    }

    @Test
    public void randomContiguousPartitionsReconstructEveryInputCharacterAndRefExactlyOnce() {
        Random random = new Random(90210);
        for (int attempt = 0; attempt < 100; attempt++) {
            int count = 1 + random.nextInt(20);
            String[] atoms = new String[count];
            for (int i = 0; i < count; i++) {
                atoms[i] = i % 2 == 0 ? "🙂w" + i : " repeat";
            }
            SegmentationProposal proposal = proposal(atoms);
            List<SegmentationProposal.Unit> units = new ArrayList<>();
            for (int start = 0; start < count;) {
                int end = Math.min(count, start + 1 + random.nextInt(4));
                List<String> ids = new ArrayList<>();
                StringBuilder text = new StringBuilder();
                for (int i = start; i < end; i++) {
                    ids.add("p" + i);
                    text.append(atoms[i]);
                }
                units.add(new SegmentationProposal.Unit(ids, text.toString()));
                start = end;
            }
            SegmentationProposal.Decision decision = proposal.evaluate(units, SegmentationProposal.TextPolicy.EXACT, SegmentationProposal.Completion.COMPLETE);
            assertTrue(decision.accepted());
            int cursor = 0;
            for (SegmentationProposal.AcceptedUnit accepted : decision.units) {
                for (SegmentationProposal.Piece piece : accepted.pieces) {
                    assertEquals("p" + cursor, piece.id);
                    assertEquals(atoms[cursor++], piece.text);
                }
            }
            assertEquals(count, cursor);
        }
    }

    private static SegmentationProposal.Unit unit(String... values) {
        return new SegmentationProposal.Unit(Arrays.asList(values).subList(0, values.length - 1), values[values.length - 1]);
    }

    private static SegmentationProposal proposal(String... atoms) {
        return proposal(atoms.length == 0 ? new String[0][] : new String[][]{atoms});
    }

    private static SegmentationProposal proposal(String[][] flows) {
        return prepare(transcript(flows), 1000, 100);
    }

    private static SegmentationProposal prepare(MappedTranscript transcript, int characters, int units) {
        List<MappedTranscript.Slice> anchors = new ArrayList<>();
        for (MappedTranscript.Atom atom : transcript.getAtoms()) {
            anchors.add(new MappedTranscript.Slice(atom.index, 0, atom.text.length()));
        }
        return new SegmentationProposal(transcript, anchors, characters, units);
    }

    private static MappedTranscript transcript(String[][] flows) {
        List<RawTimeline.Cue> cues = new ArrayList<>();
        List<MappedTranscript.Atom> mapped = new ArrayList<>();
        for (int flow = 0; flow < flows.length; flow++) {
            StringBuilder text = new StringBuilder();
            List<RawTimeline.TextRun> runs = new ArrayList<>();
            for (String value : flows[flow]) {
                int start = text.length();
                text.append(value);
                runs.add(new RawTimeline.TextRun(start, text.length(), 0, 10_000,
                        1_000, 9_000, RawTimeline.TimingKind.CUE_INTERVAL, false));
            }
            RawTimeline.Cue cue = new RawTimeline.Cue(flow, null, 0, 10_000, text.toString(), runs, false);
            cues.add(cue);
            for (int run = 0; run < runs.size(); run++) {
                mapped.add(new MappedTranscript.Atom(mapped.size(), flow, flows[flow][run],
                        Collections.singletonList(new MappedTranscript.Origin(flow, cue, run))));
            }
        }
        return new MappedTranscript(new RawTimeline("fixture".getBytes(StandardCharsets.UTF_8), cues), mapped);
    }

    private static SegmentationProposal.Decision exact(SegmentationProposal proposal, SegmentationProposal.Unit... units) {
        return proposal.evaluate(Arrays.asList(units), SegmentationProposal.TextPolicy.EXACT, SegmentationProposal.Completion.COMPLETE);
    }

    private static SegmentationProposal.Decision english(SegmentationProposal proposal, SegmentationProposal.Unit... units) {
        return proposal.evaluate(Arrays.asList(units), SegmentationProposal.TextPolicy.ENGLISH_PUNCTUATION_AND_CASE, SegmentationProposal.Completion.COMPLETE);
    }

    private static void assertFallback(SegmentationProposal.Decision decision, String... texts) {
        assertFalse(decision.accepted());
        List<String> original = new ArrayList<>();
        for (SegmentationProposal.AcceptedUnit unit : decision.units) {
            original.add(unit.originalText);
            assertEquals(unit.originalText, unit.suggestedSource);
        }
        assertEquals(Arrays.asList(texts), original);
    }

    private static void invalid(Runnable action) {
        try {
            action.run();
            fail("Expected invalid proposal construction");
        } catch (IllegalArgumentException expected) {
            assertEquals(RawTimeline.invalid().getMessage(), expected.getMessage());
        }
    }

    private static void immutable(Runnable action) {
        try {
            action.run();
            fail("Expected immutable mapping data");
        } catch (UnsupportedOperationException expected) {
            // Both fallback and accepted decisions own their lists.
        }
    }
}
