package com.liskovsoft.smartyoutubetv2.common.subtitle.session;

import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.MappedTranscript;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.RawTimeline;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.RawWebvttParser;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.SemanticGroupPlanner;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.SegmentationProposal;

import org.junit.Test;

import java.nio.charset.Charset;
import java.util.List;

import static org.junit.Assert.*;

public class CaptionSessionTest {
    private final CaptionSession session = new CaptionSession();
    private final CaptionSession.Track track = session.selectTrack(0, null);

    @Test
    public void retainsWholeDocumentAndQueriesOverlapsInDocumentOrderWithHalfOpenEnds() {
        RawTimeline raw = parse("WEBVTT\n\n00:05.000 --> 00:30.000\nlate\n\n" +
                "00:01.000 --> 00:10.000\nearly\n\n00:01.000 --> 00:10.000\nearly\n");
        assertTrue(accept(session.beginSample(track), raw));
        CaptionSession.Snapshot snapshot = session.getSnapshot();
        assertSame(raw, snapshot.timeline);
        assertSame(track, snapshot.track);
        assertTrue(session.getCuesAt(snapshot, 999_999).isEmpty());
        assertEquals(2, session.getCuesAt(snapshot, 1_000_000).size());
        List<RawTimeline.Cue> overlap = session.getCuesAt(snapshot, 5_000_000);
        assertEquals(3, overlap.size());
        assertEquals("late", overlap.get(0).text);
        assertEquals("early", overlap.get(1).text);
        assertEquals("early", overlap.get(2).text);
        assertEquals(1, session.getCuesAt(snapshot, 10_000_000).size());
        assertTrue(session.getCuesAt(snapshot, 30_000_000).isEmpty());
        try { overlap.clear(); fail(); } catch (UnsupportedOperationException expected) { }
    }

    @Test
    public void lateOldResultCannotOverrideNewSampleRegardlessOfCompletionOrder() {
        CaptionSession.Sample old = session.beginSample(track);
        CaptionSession.Sample next = session.beginSample(track);
        assertFalse(accept(old, raw("old")));
        assertTrue(accept(next, raw("next")));
        CaptionSession.Snapshot snapshot = session.getSnapshot();
        assertFalse(accept(old, raw("old")));
        session.reject(old);
        assertSame(snapshot, session.getSnapshot());
        assertEquals("next", snapshot.timeline.getCues().get(0).text);
        assertFalse(accept(next, raw("duplicate completion")));
    }

    @Test
    public void failedOrDroppedReplacementNeverLeavesPreviousDocumentCurrent() {
        accept(session.beginSample(track), raw("first"));
        CaptionSession.Snapshot old = session.getSnapshot();
        CaptionSession.Sample next = session.beginSample(track);
        assertNull(session.getSnapshot());
        assertFalse(session.isCurrent(old));
        session.reject(next);
        assertFalse(accept(next, raw("late after reject")));
        assertTrue(session.getCuesAt(old, 1_000_000).isEmpty());
        assertTrue(session.getGroupsAt(old, 1_000_000).isEmpty());
    }

    @Test
    public void flushRetainsAcceptedDocumentButRejectsWorkInFlight() {
        accept(session.beginSample(track), raw("accepted"));
        CaptionSession.Snapshot accepted = session.getSnapshot();
        session.flush(track);
        assertTrue(session.isCurrent(accepted));
        assertEquals(1, session.getCuesAt(accepted, 1_000_000).size());
        assertEquals(1, session.getGroupsAt(accepted, 1_000_000).size());
        CaptionSession.Sample pending = session.beginSample(track);
        session.flush(track);
        assertFalse(accept(pending, raw("before seek")));
        assertNull(session.getSnapshot());
        assertTrue(accept(session.beginSample(track), raw("after seek")));
    }

    @Test
    public void sourceOrTrackReplacementInvalidatesHeldSnapshotsAndLateDecoderCleanup() {
        accept(session.beginSample(track), raw("old"));
        CaptionSession.Snapshot old = session.getSnapshot();
        CaptionSession.Sample pending = session.beginSample(track);
        CaptionSession.Track next = session.selectTrack(2, null);
        assertEquals(2, next.candidates);
        assertNull(next.source);
        assertFalse(accept(pending, raw("late")));
        assertNull(session.beginSample(track));
        assertTrue(accept(session.beginSample(next), raw("new")));
        CaptionSession.Snapshot current = session.getSnapshot();
        session.release(track);
        session.flush(track);
        assertSame(current, session.getSnapshot());
        assertFalse(session.isCurrent(old));
        assertTrue(session.getCuesAt(old, 1_000_000).isEmpty());
        assertTrue(session.getGroupsAt(old, 1_000_000).isEmpty());
        session.release(next);
        assertNull(session.getSnapshot());
        assertFalse(session.isCurrent(current));
    }

    @Test
    public void foreignSessionTicketsAndSnapshotsCannotPublishOrQuery() {
        CaptionSession other = new CaptionSession();
        CaptionSession.Track foreignTrack = other.selectTrack(0, null);
        CaptionSession.Sample foreignSample = other.beginSample(foreignTrack);
        CaptionSession.Sample local = session.beginSample(track);
        assertFalse(accept(foreignSample, raw("foreign")));
        assertNull(session.beginSample(foreignTrack));
        assertTrue(accept(local, raw("local")));
        CaptionSession.Snapshot snapshot = session.getSnapshot();
        assertFalse(other.isCurrent(snapshot));
        assertTrue(other.getCuesAt(snapshot, 1_000_000).isEmpty());
    }

    @Test
    public void clearAndCloseRejectDelayedSamplesAndClosedSessionCannotRestart() {
        CaptionSession.Sample old = session.beginSample(track);
        session.clearTrack();
        assertFalse(accept(old, raw("late")));
        CaptionSession.Track next = session.selectTrack(0, null);
        accept(session.beginSample(next), raw("active"));
        CaptionSession.Snapshot snapshot = session.getSnapshot();
        session.close();
        session.close();
        assertNull(session.getSnapshot());
        assertFalse(session.isCurrent(snapshot));
        assertNull(session.beginSample(next));
        assertNull(session.selectTrack(0, null));
    }

    @Test
    public void equalLookingDifferentDocumentCannotSupplyTheMapping() {
        RawTimeline raw = raw("same text");
        RawTimeline other = raw("same text");
        CaptionSession.Sample sample = session.beginSample(track);
        MappedTranscript mapped = MappedTranscript.fromRaw(raw, 32);
        MappedTranscript foreign = MappedTranscript.fromRaw(other, 32);
        SemanticGroupPlanner.Plan plan = preserve(mapped);
        assertFalse(session.accept(sample, raw, foreign, preserve(foreign)));
        assertFalse(session.accept(sample, raw, null, plan));
        assertFalse(session.accept(sample, raw, mapped, preserve(foreign)));
        assertFalse(session.accept(sample, raw, mapped, null));
        assertNull(session.getSnapshot());
        assertTrue(session.accept(sample, raw, mapped, plan));
        CaptionSession.Snapshot snapshot = session.getSnapshot();
        assertTrue(snapshot.transcript.isFor(snapshot.timeline));
        assertTrue(snapshot.groups.isFor(snapshot.transcript));
    }

    @Test
    public void equalMappingFromSameRawStillCannotSupplyAnotherPlansIdentity() {
        RawTimeline raw = raw("same raw");
        MappedTranscript first = MappedTranscript.fromRaw(raw, 32);
        MappedTranscript second = MappedTranscript.fromRaw(raw, 32);
        CaptionSession.Sample sample = session.beginSample(track);
        assertFalse(session.accept(sample, raw, first, preserve(second)));
        assertTrue(session.accept(sample, raw, first, preserve(first)));
        assertEquals("same raw", session.getSnapshot().groups.getText(0));
    }

    @Test
    public void segmentationPreparationAndEvaluationRejectStaleOrForeignSnapshots() {
        accept(session.beginSample(track), raw("hello"));
        CaptionSession.Snapshot snapshot = session.getSnapshot();
        SegmentationProposal.Limits limits = new SegmentationProposal.Limits(32, 32, 100, 32);
        SegmentationProposal proposal = session.prepareSegmentation(snapshot, 10, limits);
        List<SegmentationProposal.Unit> output = java.util.Collections.singletonList(
                new SegmentationProposal.Unit(java.util.Collections.singletonList("p0"), "hello"));
        assertTrue(session.evaluateSegmentation(snapshot, proposal, output, SegmentationProposal.TextPolicy.EXACT,
                SegmentationProposal.Completion.COMPLETE).accepted());
        session.flush(track);
        assertTrue(session.evaluateSegmentation(snapshot, proposal, output, SegmentationProposal.TextPolicy.EXACT,
                SegmentationProposal.Completion.COMPLETE).accepted());
        SegmentationProposal foreign = SegmentationProposal.prepare(MappedTranscript.fromRaw(raw("hello"), 32), 10, limits);
        assertNull(session.evaluateSegmentation(snapshot, foreign, output, SegmentationProposal.TextPolicy.EXACT,
                SegmentationProposal.Completion.COMPLETE));
        session.clearTrack();
        assertNull(session.prepareSegmentation(snapshot, 10, limits));
        assertNull(session.evaluateSegmentation(snapshot, proposal, output, SegmentationProposal.TextPolicy.EXACT,
                SegmentationProposal.Completion.COMPLETE));
    }

    @Test
    public void optionalPreparationBudgetFailurePreservesCurrentDocument() {
        accept(session.beginSample(track), raw("hello world"));
        CaptionSession.Snapshot snapshot = session.getSnapshot();
        try {
            session.prepareSegmentation(snapshot, 10, new SegmentationProposal.Limits(1, 32, 100, 32));
            fail();
        } catch (IllegalArgumentException expected) {
            assertSame(snapshot, session.getSnapshot());
            assertTrue(session.isCurrent(snapshot));
        }
    }

    @Test
    public void invalidationDuringSlowProposalEvaluationCannotReturnACompletedDecision() {
        accept(session.beginSample(track), raw("hello"));
        CaptionSession.Snapshot snapshot = session.getSnapshot();
        SegmentationProposal proposal = session.prepareSegmentation(snapshot, 10,
                new SegmentationProposal.Limits(32, 32, 100, 32));
        List<SegmentationProposal.Unit> delayed = new java.util.AbstractList<SegmentationProposal.Unit>() {
            public int size() { return 1; }
            public SegmentationProposal.Unit get(int index) {
                session.clearTrack();
                return new SegmentationProposal.Unit(java.util.Collections.singletonList("p0"), "hello");
            }
        };
        assertNull(session.evaluateSegmentation(snapshot, proposal, delayed, SegmentationProposal.TextPolicy.EXACT,
                SegmentationProposal.Completion.COMPLETE));
        assertNull(session.getSnapshot());
    }

    private boolean accept(CaptionSession.Sample sample, RawTimeline raw) {
        MappedTranscript mapped = MappedTranscript.fromRaw(raw, 32);
        return session.accept(sample, raw, mapped, preserve(mapped));
    }

    private static SemanticGroupPlanner.Plan preserve(MappedTranscript mapped) {
        return SemanticGroupPlanner.preserve(mapped, new SemanticGroupPlanner.Limits(32, 32, 32));
    }

    private static RawTimeline raw(String text) {
        return parse("WEBVTT\n\n00:01.000 --> 00:02.000\n" + text + "\n");
    }

    private static RawTimeline parse(String text) {
        return RawWebvttParser.parse(text.getBytes(Charset.forName("UTF-8")), 0,
                new RawTimeline.Limits(4096, 16, 32));
    }
}
