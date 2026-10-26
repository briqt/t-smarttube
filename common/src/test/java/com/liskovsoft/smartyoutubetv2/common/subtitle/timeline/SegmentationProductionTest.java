package com.liskovsoft.smartyoutubetv2.common.subtitle.timeline;

import org.junit.Test;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class SegmentationProductionTest {
    private static final SegmentationProposal.Limits LIMITS = new SegmentationProposal.Limits(8192, 8192, 100000, 8192);

    @Test
    public void preparationKeepsWordsWhitespaceAndPunctuationWithoutClaimingLinguisticSegmentation() {
        MappedTranscript mapped = mapped("we can't re-sign 3.14\n😀 中文");
        SegmentationProposal proposal = SegmentationProposal.prepare(mapped, 100, LIMITS);
        List<String> text = new ArrayList<>();
        for (SegmentationProposal.Piece piece : proposal.getPieces()) text.add(piece.text);
        assertEquals(Arrays.asList("we", " ", "can't", " ", "re-sign", " ", "3.14", "\n", "😀", " ", "中文"), text);
        assertTrue(proposal.isFor(mapped));
        assertEquals("we can't re-sign 3.14\n😀 中文", reconstruct(proposal));
    }

    @Test
    public void boundedPiecesAreUnicodeSafeAndRetainEntireCoarseTimingWindows() {
        MappedTranscript mapped = mapped("😀word");
        SegmentationProposal proposal = SegmentationProposal.prepare(mapped, 1, LIMITS);
        assertEquals(5, proposal.getPieces().size());
        assertEquals("😀", proposal.getPieces().get(0).text);
        assertEquals(2, proposal.getPieces().get(0).slice.end);
        for (int i = 0; i < proposal.getPieces().size(); i++) {
            MappedTranscript.Reference reference = proposal.getReferences(i).get(0);
            assertFalse(reference.wholeRun);
            assertEquals(1_000_000, reference.origin.timingStartUs);
            assertEquals(20_000_000, reference.origin.timingEndUs);
        }
        assertEquals("😀word", reconstruct(proposal));
    }

    @Test
    public void midWordAnchorsDoNotPermitPunctuationAtArtificialEdges() {
        SegmentationProposal proposal = SegmentationProposal.prepare(mapped("hello"), 2, LIMITS);
        assertFalse(proposal.evaluate(Arrays.asList(
                new SegmentationProposal.Unit(Collections.singletonList("p0"), "He."),
                new SegmentationProposal.Unit(Arrays.asList("p1", "p2"), "llo")),
                SegmentationProposal.TextPolicy.ENGLISH_PUNCTUATION_AND_CASE,
                SegmentationProposal.Completion.COMPLETE).accepted());
    }

    @Test
    public void productionBudgetsSupportMoreThanOldFixtureAnchorsAndRejectWholeOverflow() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 1100; i++) text.append("x ");
        MappedTranscript mapped = mapped(text.toString());
        SegmentationProposal proposal = SegmentationProposal.prepare(mapped, 8, LIMITS);
        assertEquals(2200, proposal.getPieces().size());
        invalid(() -> SegmentationProposal.prepare(mapped, 8, new SegmentationProposal.Limits(2199, 8192, 10000, 8192)));
        invalid(() -> SegmentationProposal.prepare(mapped, 8, new SegmentationProposal.Limits(8192, 2199, 10000, 8192)));
        assertEquals(text.toString(), reconstruct(proposal));
    }

    @Test
    public void explicitAnchorsAreFullyValidatedAndAliasAmplificationConsumesReferenceBudget() {
        RawTimeline raw = new RawTimeline(new byte[]{1}, Arrays.asList(
                new RawTimeline.Cue(0, null, 0, 2, "x y"), new RawTimeline.Cue(1, null, 1, 3, "x y")));
        MappedTranscript.Atom atom = new MappedTranscript.Atom(0, 0, "x y", Arrays.asList(
                new MappedTranscript.Origin(0, raw.getCues().get(0), 0),
                new MappedTranscript.Origin(1, raw.getCues().get(1), 0)));
        MappedTranscript mapped = new MappedTranscript(raw, Collections.singletonList(atom));
        invalid(() -> SegmentationProposal.prepare(mapped, 10, new SegmentationProposal.Limits(10, 5, 100, 10)));
        SegmentationProposal proposal = SegmentationProposal.prepare(mapped, 10, new SegmentationProposal.Limits(10, 6, 100, 10));
        for (int i = 0; i < 3; i++) assertEquals(2, proposal.getReferences(i).size());
        invalid(() -> SegmentationProposal.fromAnchors(mapped, Collections.singletonList(new MappedTranscript.Slice(0, 0, 1)), LIMITS));
    }

    @Test
    public void completeTypedOutputIsRequiredAndFailureKeepsOriginalFlow() {
        SegmentationProposal proposal = SegmentationProposal.prepare(mapped("hello world"), 20, LIMITS);
        List<SegmentationProposal.Unit> output = Collections.singletonList(
                new SegmentationProposal.Unit(Arrays.asList("p0", "p1", "p2"), "Hello, world."));
        SegmentationProposal.Decision accepted = proposal.evaluate(output,
                SegmentationProposal.TextPolicy.ENGLISH_PUNCTUATION_AND_CASE, SegmentationProposal.Completion.COMPLETE);
        assertTrue(accepted.accepted());
        assertEquals("hello world", accepted.units.get(0).originalText);
        for (SegmentationProposal.Completion completion : Arrays.asList(null, SegmentationProposal.Completion.UNKNOWN,
                SegmentationProposal.Completion.TRUNCATED, SegmentationProposal.Completion.FAILED)) {
            SegmentationProposal.Decision rejected = proposal.evaluate(output, SegmentationProposal.TextPolicy.EXACT, completion);
            assertFalse(rejected.accepted());
            assertEquals("hello world", rejected.units.get(0).originalText);
            assertEquals("hello world", rejected.units.get(0).suggestedSource);
        }
    }

    @Test
    public void emptyInputIsSupportedAndLimitsAreExplicit() {
        RawTimeline empty = RawWebvttParser.parse("WEBVTT\n\n".getBytes(Charset.forName("UTF-8")), 0,
                new RawTimeline.Limits(100, 1, 1));
        SegmentationProposal proposal = SegmentationProposal.prepare(MappedTranscript.fromRaw(empty, 1), 1, LIMITS);
        assertTrue(proposal.evaluate(Collections.emptyList(), SegmentationProposal.TextPolicy.EXACT,
                SegmentationProposal.Completion.COMPLETE).accepted());
        invalid(() -> SegmentationProposal.prepare(mapped("x"), 0, LIMITS));
        invalid(() -> new SegmentationProposal.Limits(65537, 1, 1, 1));
        invalid(() -> new SegmentationProposal.Limits(1, 0, 1, 1));
        invalid(() -> proposal.getReferences(0));
    }

    private static MappedTranscript mapped(String text) {
        RawTimeline raw = RawWebvttParser.parse(("WEBVTT\n\n00:01.000 --> 00:20.000\n" + text + "\n")
                .getBytes(Charset.forName("UTF-8")), 0, new RawTimeline.Limits(1024 * 1024, 4096, 8192));
        return MappedTranscript.fromRaw(raw, 8192);
    }

    private static String reconstruct(SegmentationProposal proposal) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < proposal.getPieces().size(); i++) {
            SegmentationProposal.Piece piece = proposal.getPieces().get(i);
            assertEquals("p" + i, piece.id);
            result.append(piece.text);
        }
        return result.toString();
    }

    private static void invalid(Runnable action) {
        try {
            action.run();
            fail("Expected complete preparation rejection");
        } catch (IllegalArgumentException expected) {
            assertEquals("Unsupported or invalid subtitle document", expected.getMessage());
        }
    }
}
