package com.liskovsoft.smartyoutubetv2.common.subtitle.session;

import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.MappedTranscript;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.RawTimeline;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.RawWebvttParser;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.SegmentationProposal;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.SemanticGroupPlanner;
import org.junit.Test;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class SegmentationPublicationTest {
    private static final SemanticGroupPlanner.Limits GROUPS = new SemanticGroupPlanner.Limits(32, 128, 256);
    private static final SegmentationProposal.Limits PIECES = new SegmentationProposal.Limits(128, 256, 4096, 32);

    @Test
    public void ownedCompleteDecisionBuildsAnActualPlanWithoutInventingTextOrClocks() {
        Fixture f = new Fixture();
        SegmentationProposal.Decision decision = f.split();
        assertTrue(f.proposal.owns(decision));
        SemanticGroupPlanner.Plan plan = SemanticGroupPlanner.fromDecision(f.base.transcript, f.proposal, decision, GROUPS);
        assertEquals(2, plan.getGroups().size());
        assertEquals("red ", plan.getText(0));
        assertEquals("blue", plan.getText(1));
        assertEquals(Arrays.asList(0, 1), plan.getGroupsAt(1_000_000));
        assertEquals(Arrays.asList(0, 1), plan.getGroupsAt(4_999_999));
        assertTrue(plan.getGroupsAt(5_000_000).isEmpty());
        assertEquals(1_000_000, plan.getReferences(0).get(0).origin.timingStartUs);
        assertEquals(5_000_000, plan.getReferences(1).get(0).origin.timingEndUs);
        assertFalse(plan.getReferences(1).get(0).wholeRun);
    }

    @Test
    public void anotherEqualLookingProposalOrTranscriptCannotOwnTheDecision() {
        Fixture f = new Fixture();
        SegmentationProposal another = SegmentationProposal.prepare(f.base.transcript, 100, PIECES);
        assertFalse(another.owns(f.split()));
        invalid(() -> SemanticGroupPlanner.fromDecision(f.base.transcript, another, f.split(), GROUPS));
        Fixture foreign = new Fixture();
        invalid(() -> SemanticGroupPlanner.fromDecision(foreign.base.transcript, f.proposal, f.split(), GROUPS));
    }

    @Test
    public void publicationCreatesANewSnapshotInTheSameAdmittedDocumentAndKeepsRawImmutable() {
        Fixture f = new Fixture();
        CaptionSession.AnalysisTicket ticket = f.session.beginAnalysis(f.base);
        assertNotNull(ticket);
        assertTrue(f.session.publishSegmentation(ticket, f.proposal, f.split(), GROUPS));
        CaptionSession.Snapshot current = f.session.getSnapshot();
        assertNotSame(f.base, current);
        assertTrue(current.sameDocumentAs(f.base));
        assertSame(f.base.timeline, current.timeline);
        assertSame(f.base.transcript, current.transcript);
        assertSame(f.base.clock, current.clock);
        assertEquals("red ", current.groups.getText(0));
        assertEquals("blue", current.groups.getText(1));
        assertFalse(f.session.isCurrent(f.base));
        assertTrue(f.session.isCurrent(current));
        assertArrayEquals(f.document, current.timeline.copySource());
        assertEquals(Arrays.asList(0, 1), f.session.getGroupsAt(current, 2_000_000));
        assertFalse(f.session.publishSegmentation(ticket, f.proposal, f.split(), GROUPS));
    }

    @Test
    public void equalPartitionIsANoopInsteadOfInvalidatingAStillValidFrameOrSemanticBoundary() {
        Fixture f = new Fixture();
        SegmentationProposal.Decision same = f.proposal.evaluate(Collections.singletonList(
                new SegmentationProposal.Unit(Arrays.asList("p0", "p1", "p2"), "red blue")),
                SegmentationProposal.TextPolicy.EXACT, SegmentationProposal.Completion.COMPLETE);
        for (int i = 0; i < 10; i++) {
            CaptionSession.AnalysisTicket ticket = f.session.beginAnalysis(f.base);
            assertTrue(f.session.publishSegmentation(ticket, f.proposal, same, GROUPS));
            assertSame(f.base, f.session.getSnapshot());
            assertEquals(SemanticGroupPlanner.Boundary.FLOW_END, f.base.groups.getGroups().get(0).boundary);
        }
    }

    @Test
    public void supersededOrForeignTicketCannotPublishEvenWhenItsTextIsIdentical() {
        Fixture f = new Fixture(), foreign = new Fixture();
        CaptionSession.AnalysisTicket old = f.session.beginAnalysis(f.base);
        CaptionSession.AnalysisTicket latest = f.session.beginAnalysis(f.base);
        assertFalse(f.session.publishSegmentation(old, f.proposal, f.split(), GROUPS));
        assertFalse(f.session.publishSegmentation(foreign.session.beginAnalysis(foreign.base), f.proposal, f.split(), GROUPS));
        assertTrue(f.session.publishSegmentation(latest, f.proposal, f.split(), GROUPS));
    }

    @Test
    public void pendingSampleInvalidatesAnOldAnalysisEvenIfTheCallerReusesTheSameRawObject() {
        Fixture f = new Fixture();
        CaptionSession.AnalysisTicket old = f.session.beginAnalysis(f.base);
        CaptionSession.Sample replacement = f.session.beginSample(f.track);
        assertTrue(f.session.accept(replacement, f.base.timeline, f.base.transcript, f.base.groups));
        CaptionSession.Snapshot current = f.session.getSnapshot();
        assertFalse(current.sameDocumentAs(f.base));
        assertFalse(f.session.publishSegmentation(old, f.proposal, f.split(), GROUPS));
        assertSame(current, f.session.getSnapshot());
    }

    @Test
    public void seekInvalidatesAnalysisTicketsWithoutClearingTheAcceptedDocument() {
        Fixture f = new Fixture();
        CaptionSession.AnalysisTicket old = f.session.beginAnalysis(f.base);
        f.session.flush(f.track);
        assertSame(f.base, f.session.getSnapshot());
        assertTrue(f.session.isCurrent(f.base));
        assertFalse(f.session.publishSegmentation(old, f.proposal, f.split(), GROUPS));
        assertTrue(f.session.publishSegmentation(f.session.beginAnalysis(f.base), f.proposal, f.split(), GROUPS));
    }

    @Test
    public void suspendThenResumeDoesNotReviveAnOldAnalysisTicket() {
        Fixture f = new Fixture();
        CaptionSession.AnalysisTicket old = f.session.beginAnalysis(f.base);
        f.session.setActive(false);
        assertNull(f.session.beginAnalysis(f.base));
        f.session.setActive(true);
        assertTrue(f.session.isCurrent(f.base));
        assertFalse(f.session.publishSegmentation(old, f.proposal, f.split(), GROUPS));
        assertTrue(f.session.publishSegmentation(f.session.beginAnalysis(f.base), f.proposal, f.split(), GROUPS));
    }

    @Test
    public void noneSourceReplacementReleaseAndCloseRejectPendingAnalysis() {
        for (int action = 0; action < 4; action++) {
            Fixture f = new Fixture();
            CaptionSession.AnalysisTicket old = f.session.beginAnalysis(f.base);
            if (action == 0) f.session.clearTrack();
            else if (action == 1) f.session.selectTrack(0, null);
            else if (action == 2) f.session.release(f.track);
            else f.session.close();
            assertFalse(f.session.publishSegmentation(old, f.proposal, f.split(), GROUPS));
            assertNull(f.session.getSnapshot());
        }
    }

    @Test
    public void rejectedDecisionsAndInsufficientPlanBudgetsNeverPublishAnAcceptedPrefix() {
        Fixture f = new Fixture();
        for (List<SegmentationProposal.Unit> bad : Arrays.asList(
                Collections.singletonList(new SegmentationProposal.Unit(Collections.singletonList("p0"), "red")),
                Collections.singletonList(new SegmentationProposal.Unit(Arrays.asList("p0", "p1", "p2"), "reed blue")),
                Collections.singletonList(new SegmentationProposal.Unit(Arrays.asList("p0", "p2", "p1"), "redblue ")))) {
            SegmentationProposal.Decision decision = f.proposal.evaluate(bad, SegmentationProposal.TextPolicy.EXACT,
                    SegmentationProposal.Completion.COMPLETE);
            assertFalse(decision.accepted());
            assertFalse(f.session.publishSegmentation(f.session.beginAnalysis(f.base), f.proposal, decision, GROUPS));
            assertSame(f.base, f.session.getSnapshot());
        }
        assertFalse(f.session.publishSegmentation(f.session.beginAnalysis(f.base), f.proposal, f.split(),
                new SemanticGroupPlanner.Limits(1, 128, 256)));
        assertSame(f.base, f.session.getSnapshot());
    }

    @Test
    public void outerAdmissionGuardIsRecheckedAtCommitAndCannotBeBypassedByAValidDecision() {
        AtomicBoolean permitted = new AtomicBoolean(true);
        Fixture f = new Fixture(action -> { if (!permitted.get()) return false; action.run(); return true; });
        CaptionSession.AnalysisTicket ticket = f.session.beginAnalysis(f.base);
        permitted.set(false);
        assertFalse(f.session.publishSegmentation(ticket, f.proposal, f.split(), GROUPS));
        assertSame(f.base, f.session.getSnapshot());
    }

    @Test
    public void anOuterGuardThatChangesTheDocumentBeforeTheCommitCannotPublishToTheReplacement() {
        Fixture f = new Fixture();
        AtomicBoolean reset = new AtomicBoolean(false);
        CaptionSession guarded = new CaptionSession();
        CaptionSession.Track track = guarded.selectTrack(0, null);
        assertTrue(guarded.accept(guarded.beginSample(track), f.base.timeline, f.base.transcript, f.base.groups,
                f.base.clock, action -> { if (reset.get()) guarded.beginSample(track); action.run(); return true; }));
        CaptionSession.Snapshot base = guarded.getSnapshot();
        CaptionSession.AnalysisTicket ticket = guarded.beginAnalysis(base);
        reset.set(true);
        assertFalse(guarded.publishSegmentation(ticket, f.proposal, f.split(), GROUPS));
        assertNull(guarded.getSnapshot());
    }

    private static void invalid(Runnable task) {
        try { task.run(); fail("Expected rejected foreign decision"); }
        catch (IllegalArgumentException expected) { assertEquals("Unsupported or invalid subtitle document", expected.getMessage()); }
    }
    private static final class Fixture {
        final byte[] document = "WEBVTT\n\n00:01.000 --> 00:05.000\nred blue\n".getBytes(Charset.forName("UTF-8"));
        final CaptionSession session = new CaptionSession();
        final CaptionSession.Track track = session.selectTrack(0, null);
        final CaptionSession.Snapshot base;
        final SegmentationProposal proposal;
        Fixture() { this(null); }
        Fixture(CaptionSession.Guard guard) {
            RawTimeline raw = RawWebvttParser.parse(document, 0, new RawTimeline.Limits(65536, 256, 2048));
            MappedTranscript transcript = MappedTranscript.fromRaw(raw, 2048);
            SemanticGroupPlanner.Plan plan = SemanticGroupPlanner.preserve(transcript, GROUPS);
            assertTrue(session.accept(session.beginSample(track), raw, transcript, plan,
                    new CaptionSession.InputClock("text/vtt", 0, 0), guard));
            base = session.getSnapshot();
            proposal = session.prepareSegmentation(base, 100, PIECES);
            assertEquals(3, proposal.getPieces().size());
        }
        SegmentationProposal.Decision split() {
            SegmentationProposal.Decision result = proposal.evaluate(Arrays.asList(
                    new SegmentationProposal.Unit(Arrays.asList("p0", "p1"), "red "),
                    new SegmentationProposal.Unit(Collections.singletonList("p2"), "blue")),
                    SegmentationProposal.TextPolicy.EXACT, SegmentationProposal.Completion.COMPLETE);
            assertTrue(result.accepted());
            return result;
        }
    }
}
