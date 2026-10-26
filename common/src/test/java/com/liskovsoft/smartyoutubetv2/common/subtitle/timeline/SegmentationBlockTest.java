package com.liskovsoft.smartyoutubetv2.common.subtitle.timeline;

import com.liskovsoft.smartyoutubetv2.common.subtitle.session.CaptionSession;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class SegmentationBlockTest {
    private static final SemanticGroupPlanner.Limits GROUPS = new SemanticGroupPlanner.Limits(64, 256, 512);
    private static final SegmentationProposal.Limits PIECES = new SegmentationProposal.Limits(256, 512, 4096, 64);

    @Test
    public void onlyTheMiddleGroupChangesAndBothOutsideGroupsKeepObjectsAndBoundaries() {
        Fixture f = new Fixture();
        SegmentationProposal p = f.block(1, 2);
        assertSame(f.base.groups, p.basePlan());
        assertEquals(1, p.firstGroup());
        assertEquals(2, p.endGroup());
        assertTrue(p.isFor(f.transcript));
        assertEquals(" red blue.", text(p));
        assertEquals("p0", p.getPieces().get(0).id);
        assertEquals(6, p.getPieces().get(0).slice.start);
        assertEquals(16, p.getPieces().get(p.getPieces().size() - 1).slice.end);
        SemanticGroupPlanner.Plan next = plan(f, p, exact(p, unit(p, 0, 3), unit(p, 3, 4)));
        assertTexts(next, "First.", " red ", "blue.", " Tail.");
        assertSame(f.base.groups.getGroups().get(0), next.getGroups().get(0));
        assertSame(f.base.groups.getGroups().get(2), next.getGroups().get(3));
        assertEquals(SemanticGroupPlanner.Boundary.PUNCTUATION, next.getGroups().get(0).boundary);
        assertEquals(SemanticGroupPlanner.Boundary.PUNCTUATION, next.getGroups().get(3).boundary);
        assertEquals(SemanticGroupPlanner.Boundary.TYPED, next.getGroups().get(1).boundary);
        assertEquals(1, next.getGroups().get(1).slices.size()); // Adjacent same-atom pieces coalesce.
        assertComplete(f.transcript, next);
        assertEquals(Arrays.asList(0, 1, 2, 3), next.getGroupsAt(1_000_000));
        assertTrue(next.getGroupsAt(5_000_000).isEmpty());
        assertTrue(f.session.publishSegmentation(f.session.beginAnalysis(f.base), p,
                exact(p, unit(p, 0, 3), unit(p, 3, 4)), GROUPS));
        CaptionSession.Snapshot current = f.session.getSnapshot();
        assertTrue(current.sameDocumentAs(f.base));
        assertSame(f.base.timeline, current.timeline);
        assertSame(f.base.transcript, current.transcript);
        assertSame(f.base.clock, current.clock);
        assertArrayEquals(f.raw.copySource(), current.timeline.copySource());
    }

    @Test
    public void theFutureTwoGroupsCanMergeWithoutDuplicatingThePrefix() {
        Fixture f = new Fixture();
        SegmentationProposal p = f.block(1, 3);
        assertEquals(" red blue. Tail.", text(p));
        SemanticGroupPlanner.Plan next = plan(f, p, exact(p, unit(p, 0, p.getPieces().size())));
        assertTexts(next, "First.", " red blue. Tail.");
        assertSame(f.base.groups.getGroups().get(0), next.getGroups().get(0));
        assertComplete(f.transcript, next);
        assertEquals(1, next.getGroups().get(1).slices.size());
    }

    @Test
    public void aBlockMaySpanMultipleOriginalAtomsButNeverCreatesReplacementAtoms() {
        RawTimeline raw = RawWebvttParser.parse(("WEBVTT\n\n00:01.000 --> 00:05.000\n" +
                "First. <00:02.000>red <00:03.000>blue. Tail.\n").getBytes(StandardCharsets.UTF_8),
                0, new RawTimeline.Limits(65536, 256, 2048));
        MappedTranscript transcript = MappedTranscript.fromRaw(raw, 2048);
        SemanticGroupPlanner.Plan base = SemanticGroupPlanner.candidates(transcript, 500, GROUPS);
        SegmentationProposal p = SegmentationProposal.prepareBlock(transcript, base, 1, 2, 2, PIECES);
        assertTrue(p.isFor(transcript));
        assertTrue(p.getPieces().get(0).slice.atomIndex < p.getPieces().get(p.getPieces().size() - 1).slice.atomIndex);
        SemanticGroupPlanner.Plan next = SemanticGroupPlanner.fromDecision(transcript, p,
                exact(p, unit(p, 0, 1), unit(p, 1, p.getPieces().size())), GROUPS);
        assertSame(base.getGroups().get(0), next.getGroups().get(0));
        assertSame(base.getGroups().get(2), next.getGroups().get(next.getGroups().size() - 1));
        assertComplete(transcript, next);
    }

    @Test
    public void noOpReturnsTheWholeOriginalPlanAndKeepsPreviouslyAcceptedGroupIdentity() {
        Fixture f = new Fixture();
        SegmentationProposal p = f.block(1, 2);
        SegmentationProposal.Decision same = exact(p, unit(p, 0, p.getPieces().size()));
        assertSame(f.base.groups, plan(f, p, same));
        SegmentationProposal whole = f.block(0, 3);
        assertSame(f.base.groups, plan(f, whole, exact(whole,
                unit(whole, 0, 1), unit(whole, 1, 5), unit(whole, 5, 7))));
        assertTrue(f.session.publishSegmentation(f.session.beginAnalysis(f.base), p, same, GROUPS));
        assertSame(f.base, f.session.getSnapshot());
        assertTrue(f.session.publishSegmentation(f.session.beginAnalysis(f.base), p,
                exact(p, unit(p, 0, 3), unit(p, 3, 4)), GROUPS));
        CaptionSession.Snapshot accepted = f.session.getSnapshot();
        SemanticGroupPlanner.Group priorSemantic = accepted.groups.getGroups().get(1);
        assertEquals(SemanticGroupPlanner.Boundary.TYPED, priorSemantic.boundary);
        SegmentationProposal future = SegmentationProposal.prepareBlock(f.transcript, accepted.groups, 3, 4, 100, PIECES);
        assertTrue(f.session.publishSegmentation(f.session.beginAnalysis(accepted), future,
                exact(future, unit(future, 0, 1), unit(future, 1, future.getPieces().size())), GROUPS));
        CaptionSession.Snapshot changed = f.session.getSnapshot();
        assertSame(priorSemantic, changed.groups.getGroups().get(1));
        SegmentationProposal noop = SegmentationProposal.prepareBlock(f.transcript, changed.groups, 1, 2, 1, PIECES);
        SegmentationProposal.Decision unchanged = exact(noop, unit(noop, 0, noop.getPieces().size()));
        for (int attempt = 0; attempt < 3; attempt++) {
            assertTrue(f.session.publishSegmentation(f.session.beginAnalysis(changed), noop, unchanged, GROUPS));
            assertSame(changed, f.session.getSnapshot());
            assertSame(priorSemantic, changed.groups.getGroups().get(1));
        }
    }

    @Test
    public void budgetsIncludeAllPrefixAndSuffixGroupsSlicesAndReferences() {
        Fixture f = new Fixture();
        SegmentationProposal p = f.block(1, 2);
        SegmentationProposal.Decision same = exact(p, unit(p, 0, p.getPieces().size()));
        // The block needs only one group/slice/reference, but the complete plan needs three of each.
        for (SemanticGroupPlanner.Limits limits : Arrays.asList(
                new SemanticGroupPlanner.Limits(2, 256, 512),
                new SemanticGroupPlanner.Limits(64, 2, 512),
                new SemanticGroupPlanner.Limits(64, 256, 2))) {
            invalid(() -> SemanticGroupPlanner.fromDecision(f.transcript, p, same, limits));
            assertFalse(f.session.publishSegmentation(f.session.beginAnalysis(f.base), p, same, limits));
            assertSame(f.base, f.session.getSnapshot());
        }
        assertSame(f.base.groups, SemanticGroupPlanner.fromDecision(f.transcript, p, same,
                new SemanticGroupPlanner.Limits(3, 3, 3)));
        invalid(() -> SegmentationProposal.prepareBlock(f.transcript, f.base.groups, 1, 2, 100,
                new SegmentationProposal.Limits(3, 512, 4096, 64)));
        invalid(() -> SegmentationProposal.prepareBlock(f.transcript, f.base.groups, 1, 2, 100,
                new SegmentationProposal.Limits(256, 3, 4096, 64)));
    }

    @Test
    public void fullFactoriesStillRequireCompleteOrderedDocumentCoverage() {
        Fixture f = new Fixture();
        SegmentationProposal block = f.block(1, 2);
        List<MappedTranscript.Slice> partial = new ArrayList<>();
        for (SegmentationProposal.Piece piece : block.getPieces()) partial.add(piece.slice);
        invalid(() -> SegmentationProposal.fromAnchors(f.transcript, partial, PIECES));
        invalid(() -> new SegmentationProposal(f.transcript, partial, 4096, 64));
        SegmentationProposal full = SegmentationProposal.prepare(f.transcript, 2, PIECES);
        assertNull(full.basePlan());
        assertEquals("First. red blue. Tail.", text(full));
        assertEquals(0, full.getPieces().get(0).slice.start);
        assertEquals(22, full.getPieces().get(full.getPieces().size() - 1).slice.end);
        SemanticGroupPlanner.Plan merged = plan(f, full, exact(full, unit(full, 0, full.getPieces().size())));
        assertTexts(merged, "First. red blue. Tail.");
        assertComplete(f.transcript, merged);
    }

    @Test
    public void rangesMustContainRealCompleteGroupsFromTheExactTranscript() {
        Fixture f = new Fixture(), other = new Fixture();
        for (int[] range : new int[][]{{-1, 1}, {1, 1}, {2, 1}, {0, 4}, {3, 4}}) {
            invalid(() -> SegmentationProposal.prepareBlock(f.transcript, f.base.groups, range[0], range[1], 100, PIECES));
        }
        invalid(() -> SegmentationProposal.prepareBlock(f.transcript, other.base.groups, 1, 2, 100, PIECES));
        invalid(() -> SegmentationProposal.prepareBlock(f.transcript, null, 1, 2, 100, PIECES));
        invalid(() -> SegmentationProposal.prepareBlock(null, f.base.groups, 1, 2, 100, PIECES));
        invalid(() -> SegmentationProposal.prepareBlock(f.transcript, f.base.groups, 1, 2, 0, PIECES));
        invalid(() -> SegmentationProposal.prepareBlock(f.transcript, f.base.groups, 1, 2, 1048577, PIECES));
        invalid(() -> SegmentationProposal.prepareBlock(f.transcript, f.base.groups, 1, 2, 100, null));
        RawTimeline emptyRaw = RawWebvttParser.parse("WEBVTT\n\n".getBytes(StandardCharsets.UTF_8),
                0, new RawTimeline.Limits(65536, 256, 2048));
        MappedTranscript empty = MappedTranscript.fromRaw(emptyRaw, 16);
        SemanticGroupPlanner.Plan emptyPlan = SemanticGroupPlanner.preserve(empty, GROUPS);
        invalid(() -> SegmentationProposal.prepareBlock(empty, emptyPlan, 0, 1, 100, PIECES));
    }

    @Test
    public void unicodeWhitespaceAndMidAtomBlockEdgesStayInsideTheSelectedGroups() {
        Fixture f = new Fixture("First. 🙂 café é 中. Tail.");
        SegmentationProposal p = SegmentationProposal.prepareBlock(f.transcript, f.base.groups, 1, 2, 1, PIECES);
        MappedTranscript.Slice bounds = f.base.groups.getGroups().get(1).slices.get(0);
        int cursor = bounds.start;
        boolean emoji = false, nbsp = false;
        for (int index = 0; index < p.getPieces().size(); index++) {
            SegmentationProposal.Piece piece = p.getPieces().get(index);
            assertEquals("p" + index, piece.id);
            assertEquals(0, piece.slice.atomIndex);
            assertEquals(cursor, piece.slice.start);
            assertTrue(piece.slice.end <= bounds.end);
            assertEquals(1, piece.text.codePointCount(0, piece.text.length()));
            assertTrue(MappedTranscript.boundary(f.transcript.getAtoms().get(0).text, piece.slice.start));
            assertTrue(MappedTranscript.boundary(f.transcript.getAtoms().get(0).text, piece.slice.end));
            assertEquals(piece.slice.start, p.getReferences(index).get(0).textStart);
            assertEquals(piece.slice.end, p.getReferences(index).get(0).textEnd);
            emoji |= piece.text.equals("🙂");
            nbsp |= piece.text.equals(" ");
            cursor = piece.slice.end;
        }
        assertEquals(bounds.end, cursor);
        assertTrue(emoji);
        assertTrue(nbsp);
        assertSame(f.base.groups, plan(f, p, exact(p, unit(p, 0, p.getPieces().size()))));
        invalid(() -> SegmentationProposal.fromAnchors(f.transcript,
                Collections.singletonList(new MappedTranscript.Slice(0, bounds.start + 2, bounds.end)), PIECES));
    }

    @Test
    public void aBlockEdgeInsideAWordDoesNotBecomeAPunctuationInsertionBoundary() {
        for (Fixture f : Arrays.asList(new Fixture("abcdefghi", 3),
                new Fixture("WEBVTT\n\n00:01.000 --> 00:05.000\n" +
                        "abc<00:02.000>def<00:03.000>ghi\n", 3, null))) {
            SegmentationProposal p = f.block(1, 2);
            assertEquals("def", text(p));
            assertFalse(p.evaluate(Collections.singletonList(new SegmentationProposal.Unit(
                    Collections.singletonList("p0"), ".def")), SegmentationProposal.TextPolicy.ENGLISH_PUNCTUATION_AND_CASE,
                    SegmentationProposal.Completion.COMPLETE).accepted());
            assertFalse(p.evaluate(Collections.singletonList(new SegmentationProposal.Unit(
                    Collections.singletonList("p0"), "def.")), SegmentationProposal.TextPolicy.ENGLISH_PUNCTUATION_AND_CASE,
                    SegmentationProposal.Completion.COMPLETE).accepted());
            assertTrue(exact(p, unit(p, 0, 1)).accepted());
        }
    }

    @Test
    public void missingReorderedDuplicateUnknownChangedAndTruncatedOutputRejectTheWholeBlock() {
        Fixture f = new Fixture();
        SegmentationProposal p = f.block(1, 2);
        List<List<SegmentationProposal.Unit>> outputs = Arrays.asList(
                Collections.singletonList(unit(p, 0, 3)),
                Collections.singletonList(new SegmentationProposal.Unit(Arrays.asList("p0", "p2", "p1", "p3"), " red blue.")),
                Collections.singletonList(new SegmentationProposal.Unit(Arrays.asList("p0", "p1", "p1", "p3"), " red blue.")),
                Collections.singletonList(new SegmentationProposal.Unit(Arrays.asList("p0", "p1", "p2", "p99"), " red blue.")),
                Collections.singletonList(new SegmentationProposal.Unit(Arrays.asList("p0", "p1", "p2", "p3"), " reed blue.")),
                Arrays.asList(unit(p, 0, 3), new SegmentationProposal.Unit(Collections.singletonList("p3"), "blue")));
        for (List<SegmentationProposal.Unit> output : outputs) {
            SegmentationProposal.Decision rejected = p.evaluate(output, SegmentationProposal.TextPolicy.EXACT,
                    SegmentationProposal.Completion.COMPLETE);
            assertRejected(f, p, rejected);
        }
        for (SegmentationProposal.Completion completion : Arrays.asList(null, SegmentationProposal.Completion.TRUNCATED,
                SegmentationProposal.Completion.FAILED, SegmentationProposal.Completion.UNKNOWN)) {
            assertRejected(f, p, p.evaluate(Collections.singletonList(unit(p, 0, 4)),
                    SegmentationProposal.TextPolicy.EXACT, completion));
        }
        SegmentationProposal another = f.block(1, 2);
        SegmentationProposal.Decision accepted = exact(p, unit(p, 0, 4));
        assertFalse(another.owns(accepted));
        invalid(() -> plan(f, another, accepted));
        assertFalse(f.session.publishSegmentation(f.session.beginAnalysis(f.base), another, accepted, GROUPS));
    }

    @Test
    public void crossFlowUnitsRejectButSeparateUnitsKeepTheFullDocument() {
        Fixture f = new Fixture("WEBVTT\n\n00:01.000 --> 00:05.000\nFirst. red blue.\n\n" +
                "00:06.000 --> 00:09.000\nTail.\n", 500, null);
        SegmentationProposal p = f.block(1, 3);
        SegmentationProposal.Decision bad = exact(p, unit(p, 0, p.getPieces().size()));
        assertEquals(SegmentationProposal.Problem.CROSS_FLOW, bad.problem);
        assertFalse(f.session.publishSegmentation(f.session.beginAnalysis(f.base), p, bad, GROUPS));
        SemanticGroupPlanner.Plan next = plan(f, p, exact(p, unit(p, 0, 3), unit(p, 3, 4), unit(p, 4, 5)));
        assertComplete(f.transcript, next);
        assertSame(f.base.groups.getGroups().get(0), next.getGroups().get(0));
        assertSame(f.base.groups.getGroups().get(2), next.getGroups().get(3));
        assertEquals(Collections.singletonList(3), next.getGroupsAt(6_000_000));
    }

    @Test
    public void equalLookingButForeignBasePlanCannotBeCommittedAgainstAnAnalysisTicket() {
        Fixture f = new Fixture();
        SemanticGroupPlanner.Plan foreign = SemanticGroupPlanner.candidates(f.transcript, 500, GROUPS);
        assertNotSame(f.base.groups, foreign);
        SegmentationProposal p = SegmentationProposal.prepareBlock(f.transcript, foreign, 1, 2, 100, PIECES);
        SegmentationProposal.Decision decision = exact(p, unit(p, 0, 3), unit(p, 3, 4));
        assertTrue(p.canApplyTo(foreign));
        assertFalse(p.canApplyTo(f.base.groups));
        assertComplete(f.transcript, plan(f, p, decision)); // Pure planner has no session snapshot.
        assertFalse(f.session.publishSegmentation(f.session.beginAnalysis(f.base), p, decision, GROUPS));
        assertSame(f.base, f.session.getSnapshot());
        Fixture other = new Fixture();
        assertFalse(f.session.publishSegmentation(other.session.beginAnalysis(other.base), p, decision, GROUPS));
    }

    @Test
    public void replacementSampleSeekSourceActivationAndCloseCannotReviveAnOldBlockTicket() {
        for (int action = 0; action < 7; action++) {
            Fixture f = new Fixture();
            SegmentationProposal p = f.block(1, 2);
            SegmentationProposal.Decision decision = exact(p, unit(p, 0, 3), unit(p, 3, 4));
            CaptionSession.AnalysisTicket ticket = f.session.beginAnalysis(f.base);
            if (action == 0) {
                assertTrue(f.session.accept(f.session.beginSample(f.track), f.raw, f.transcript, f.base.groups));
                assertFalse(f.base.sameDocumentAs(f.session.getSnapshot()));
            } else if (action == 1) f.session.flush(f.track);
            else if (action == 2) f.session.selectTrack(0, null);
            else if (action == 3) f.session.close();
            else if (action == 4) { f.session.setActive(false); f.session.setActive(true); }
            else if (action == 5) f.session.release(f.track);
            else f.session.beginAnalysis(f.base);
            CaptionSession.Snapshot current = f.session.getSnapshot();
            assertFalse(f.session.publishSegmentation(ticket, p, decision, GROUPS));
            assertSame(current, f.session.getSnapshot());
        }
        Fixture f = new Fixture();
        SegmentationProposal p = f.block(1, 2);
        SegmentationProposal.Decision decision = exact(p, unit(p, 0, 3), unit(p, 3, 4));
        CaptionSession.AnalysisTicket ticket = f.session.beginAnalysis(f.base);
        assertTrue(f.session.publishSegmentation(ticket, p, decision, GROUPS));
        CaptionSession.Snapshot published = f.session.getSnapshot();
        assertFalse(f.session.publishSegmentation(ticket, p, decision, GROUPS));
        assertSame(published, f.session.getSnapshot());
    }

    @Test
    public void guardsRunDocumentThenExtraThenCommitWithoutHoldingTheSessionLock() {
        List<String> order = new ArrayList<>();
        AtomicBoolean outerAllowed = new AtomicBoolean(true);
        CaptionSession[] owner = {null};
        Fixture f = new Fixture(action -> {
            assertFalse(Thread.holdsLock(owner[0]));
            order.add("document");
            if (!outerAllowed.get()) return false;
            action.run();
            return true;
        });
        owner[0] = f.session;
        SegmentationProposal p = f.block(1, 2);
        SegmentationProposal.Decision decision = exact(p, unit(p, 0, 3), unit(p, 3, 4));
        CaptionSession.AnalysisTicket ticket = f.session.beginAnalysis(f.base);
        assertFalse(f.session.publishSegmentation(ticket, p, decision, GROUPS, action -> {
            assertFalse(Thread.holdsLock(f.session));
            order.add("extra");
            return false;
        }));
        assertEquals(Arrays.asList("document", "extra"), order);
        assertSame(f.base, f.session.getSnapshot());
        order.clear();
        outerAllowed.set(false);
        assertFalse(f.session.publishSegmentation(ticket, p, decision, GROUPS, action -> {
            fail("Extra guard must not bypass document admission");
            return true;
        }));
        assertEquals(Collections.singletonList("document"), order);
        outerAllowed.set(true);
        order.clear();
        assertTrue(f.session.publishSegmentation(ticket, p, decision, GROUPS, action -> {
            assertFalse(Thread.holdsLock(f.session));
            order.add("extra");
            assertSame(f.base, f.session.getSnapshot());
            action.run();
            assertNotSame(f.base, f.session.getSnapshot());
            order.add("committed");
            return true;
        }));
        assertEquals(Arrays.asList("document", "extra", "committed"), order);
    }

    @Test
    public void extraGuardCannotCommitAfterChangingTheSourceOrInvalidatingAnalysis() {
        for (int action = 0; action < 3; action++) {
            Fixture f = new Fixture();
            SegmentationProposal p = f.block(1, 2);
            int change = action;
            assertFalse(f.session.publishSegmentation(f.session.beginAnalysis(f.base), p,
                    exact(p, unit(p, 0, 3), unit(p, 3, 4)), GROUPS, commit -> {
                        assertFalse(Thread.holdsLock(f.session));
                        if (change == 0) f.session.flush(f.track);
                        else if (change == 1) f.session.beginSample(f.track);
                        else f.session.close();
                        commit.run();
                        return true;
                    }));
            if (action == 0) assertSame(f.base, f.session.getSnapshot());
            else assertNull(f.session.getSnapshot());
        }
    }

    private static void assertRejected(Fixture f, SegmentationProposal p, SegmentationProposal.Decision rejected) {
        assertFalse(rejected.accepted());
        StringBuilder fallback = new StringBuilder();
        for (SegmentationProposal.AcceptedUnit unit : rejected.units) fallback.append(unit.originalText);
        assertEquals(text(p), fallback.toString());
        assertFalse(f.session.publishSegmentation(f.session.beginAnalysis(f.base), p, rejected, GROUPS));
        assertSame(f.base, f.session.getSnapshot());
    }

    private static SemanticGroupPlanner.Plan plan(Fixture f, SegmentationProposal p, SegmentationProposal.Decision d) {
        return SemanticGroupPlanner.fromDecision(f.transcript, p, d, GROUPS);
    }

    private static SegmentationProposal.Decision exact(SegmentationProposal p, SegmentationProposal.Unit... units) {
        return p.evaluate(Arrays.asList(units), SegmentationProposal.TextPolicy.EXACT, SegmentationProposal.Completion.COMPLETE);
    }

    private static SegmentationProposal.Unit unit(SegmentationProposal p, int first, int end) {
        List<String> refs = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (int i = first; i < end; i++) {
            refs.add(p.getPieces().get(i).id);
            text.append(p.getPieces().get(i).text);
        }
        return new SegmentationProposal.Unit(refs, text.toString());
    }

    private static String text(SegmentationProposal p) {
        return unit(p, 0, p.getPieces().size()).suggestedSource;
    }

    private static void assertTexts(SemanticGroupPlanner.Plan plan, String... expected) {
        List<String> actual = new ArrayList<>();
        for (int i = 0; i < plan.getGroups().size(); i++) actual.add(plan.getText(i));
        assertEquals(Arrays.asList(expected), actual);
    }

    private static void assertComplete(MappedTranscript transcript, SemanticGroupPlanner.Plan plan) {
        assertTrue(plan.isFor(transcript));
        int atom = 0, offset = 0;
        for (int index = 0; index < plan.getGroups().size(); index++) {
            SemanticGroupPlanner.Group group = plan.getGroups().get(index);
            int flow = plan.getFlowIndex(index);
            List<MappedTranscript.Reference> references = plan.getReferences(index);
            int refIndex = 0;
            for (MappedTranscript.Slice slice : group.slices) {
                assertEquals(atom, slice.atomIndex);
                assertEquals(offset, slice.start);
                assertEquals(flow, transcript.getAtoms().get(atom).flowIndex);
                for (MappedTranscript.Reference expected : transcript.references(slice)) {
                    MappedTranscript.Reference actual = references.get(refIndex++);
                    assertSame(expected.origin, actual.origin);
                    assertEquals(expected.textStart, actual.textStart);
                    assertEquals(expected.textEnd, actual.textEnd);
                    assertEquals(expected.wholeRun, actual.wholeRun);
                    assertEquals(expected.origin.activeStartUs, actual.origin.activeStartUs);
                    assertEquals(expected.origin.activeEndUs, actual.origin.activeEndUs);
                    assertEquals(expected.origin.timingStartUs, actual.origin.timingStartUs);
                    assertEquals(expected.origin.timingEndUs, actual.origin.timingEndUs);
                    assertEquals(expected.origin.timingKind, actual.origin.timingKind);
                }
                offset = slice.end;
                if (offset == transcript.getAtoms().get(atom).text.length()) { atom++; offset = 0; }
            }
            assertEquals(references.size(), refIndex);
        }
        assertEquals(transcript.getAtoms().size(), atom);
        assertEquals(0, offset);
    }

    private static void invalid(Runnable action) {
        try { action.run(); fail("Expected invalid block"); }
        catch (IllegalArgumentException expected) {
            assertEquals("Unsupported or invalid subtitle document", expected.getMessage());
        }
    }

    private static final class Fixture {
        final CaptionSession session = new CaptionSession();
        final CaptionSession.Track track = session.selectTrack(0, null);
        final RawTimeline raw;
        final MappedTranscript transcript;
        final CaptionSession.Snapshot base;

        Fixture() { this("First. red blue. Tail."); }
        Fixture(String text) { this(text, 500); }
        Fixture(String text, int budget) { this("WEBVTT\n\n00:01.000 --> 00:05.000\n" + text + "\n", budget, null); }
        Fixture(CaptionSession.Guard guard) {
            this("WEBVTT\n\n00:01.000 --> 00:05.000\nFirst. red blue. Tail.\n", 500, guard);
        }
        Fixture(String document, int budget, CaptionSession.Guard guard) {
            raw = RawWebvttParser.parse(document.getBytes(StandardCharsets.UTF_8), 0,
                    new RawTimeline.Limits(65536, 256, 2048));
            transcript = MappedTranscript.fromRaw(raw, 2048);
            SemanticGroupPlanner.Plan groups = SemanticGroupPlanner.candidates(transcript, budget, GROUPS);
            assertTrue(session.accept(session.beginSample(track), raw, transcript, groups,
                    new CaptionSession.InputClock("text/vtt", 0, 0), guard));
            base = session.getSnapshot();
            assertEquals(3, base.groups.getGroups().size());
        }
        SegmentationProposal block(int first, int end) {
            return SegmentationProposal.prepareBlock(transcript, base.groups, first, end, 100, PIECES);
        }
    }
}
