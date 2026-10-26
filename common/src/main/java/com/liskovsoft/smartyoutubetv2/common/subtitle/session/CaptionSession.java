package com.liskovsoft.smartyoutubetv2.common.subtitle.session;

import com.liskovsoft.smartyoutubetv2.common.subtitle.source.CaptionSource;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.MappedTranscript;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.RawTimeline;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.SemanticGroupPlanner;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.SegmentationProposal;

import java.util.Collections;
import java.util.List;

/** One in-memory document for the current source/track. No acquisition, translation, or UI policy. */
public final class CaptionSession {
    /** Opaque in-memory source/format domain. Contains no provider identity or request data. */
    public static final class CacheScope { }

    /** Short, synchronous outer admission only; never parse, perform I/O, or take locks in reverse order. */
    public interface Guard { boolean run(Runnable action); }

    /** Effective input clock, distinct from playback position and runtime decoder generations. */
    public static final class InputClock {
        public final String mime;
        public final long offsetUs;
        public final long durationUs;
        public InputClock(String mime, long offsetUs, long durationUs) {
            if (mime != null && mime.length() > 128 || durationUs < 0) throw new IllegalArgumentException("Invalid caption clock");
            this.mime = mime;
            this.offsetUs = offsetUs;
            this.durationUs = durationUs;
        }
    }
    private static final InputClock LOCAL_CLOCK = new InputClock(null, 0, 0);

    /** Runtime track identity is separate from the explicitly supplied source/format cache domain. */
    public static final class Track {
        public final int candidates;
        public final CaptionSource source;
        public final CacheScope cacheScope;

        private Track(int candidates, CaptionSource source, CacheScope cacheScope) {
            this.candidates = candidates;
            this.source = source;
            this.cacheScope = cacheScope;
        }
    }

    /** An opaque admission ticket. Only the latest pending sample can publish a document. */
    public static final class Sample {
        private final Track mTrack;

        private Sample(Track track) {
            mTrack = track;
        }
    }

    /** One-shot analysis admission, separate from document ownership and presentation tickets. */
    public static final class AnalysisTicket {
        private final CaptionSession mOwner;
        private final Snapshot mBase;
        private final long mActivation;

        private AnalysisTicket(CaptionSession owner, Snapshot base, long activation) {
            mOwner = owner;
            mBase = base;
            mActivation = activation;
        }
    }

    /** Contains subtitle text/bytes. Recheck isCurrent before using asynchronous derived results. */
    public static final class Snapshot {
        public final Track track;
        public final RawTimeline timeline;
        public final MappedTranscript transcript;
        public final SemanticGroupPlanner.Plan groups;
        public final InputClock clock;
        private final CaptionSession mOwner;
        private final Guard mGuard;
        private final Object mDocument;

        private Snapshot(CaptionSession owner, Track track, RawTimeline timeline, MappedTranscript transcript,
                         SemanticGroupPlanner.Plan groups, InputClock clock, Guard guard, Object document) {
            mOwner = owner;
            this.track = track;
            this.timeline = timeline;
            this.transcript = transcript;
            this.groups = groups;
            this.clock = clock;
            mGuard = guard;
            mDocument = document;
        }

        /** Same admitted document, not merely equal bytes/times or reuse of a Raw object. */
        public boolean sameDocumentAs(Snapshot other) {
            return other != null && mOwner == other.mOwner && mDocument == other.mDocument;
        }
    }

    private Track mTrack;
    private Sample mPending;
    private Snapshot mSnapshot;
    private AnalysisTicket mAnalysis;
    private boolean mClosed;
    private boolean mActive = true;
    private long mActivationRevision;

    /** Caller must also clear the track on a source change, even if format values look identical. */
    public synchronized Track selectTrack(int candidates, CaptionSource source) {
        return selectTrack(candidates, source, new CacheScope());
    }

    /** Only an actual same-source/format owner may reuse scope across replacement runtime tracks. */
    public synchronized Track selectTrack(int candidates, CaptionSource source, CacheScope scope) {
        if (candidates < 0 || (candidates == 1) != (source != null) || scope == null) {
            throw new IllegalArgumentException("Invalid caption binding");
        }
        clearTrack();
        if (mClosed) return null;
        mTrack = new Track(candidates, source, scope);
        return mTrack;
    }

    public synchronized void clearTrack() {
        mTrack = null;
        mPending = null;
        mSnapshot = null;
        mAnalysis = null;
    }

    /** Called after native queue succeeds, before dispatch. Failed/uncaptured replacements stay empty. */
    public synchronized Sample beginSample(Track track) {
        if (mClosed || track == null || track != mTrack) return null;
        mPending = new Sample(track);
        mSnapshot = null;
        mAnalysis = null;
        return mPending;
    }

    public synchronized boolean isPending(Sample sample) {
        return !mClosed && sample != null && sample == mPending && sample.mTrack == mTrack;
    }

    /** Models are prepared off-thread and must belong to the exact same immutable document chain. */
    public synchronized boolean accept(Sample sample, RawTimeline timeline, MappedTranscript transcript,
                                       SemanticGroupPlanner.Plan groups) {
        return accept(sample, timeline, transcript, groups, LOCAL_CLOCK, null);
    }

    public synchronized boolean accept(Sample sample, RawTimeline timeline, MappedTranscript transcript,
                                       SemanticGroupPlanner.Plan groups, InputClock clock, Guard guard) {
        if (timeline == null || transcript == null || groups == null || clock == null || !transcript.isFor(timeline) ||
                !groups.isFor(transcript) || !isPending(sample)) return false;
        mSnapshot = new Snapshot(this, mTrack, timeline, transcript, groups, clock, guard, sample);
        mPending = null;
        return true;
    }

    /** Acquire outer guards BEFORE the consumer lock. Consumer must still check isCurrent under this session lock. */
    public boolean guard(Snapshot snapshot, Runnable action) {
        if (action == null) throw new IllegalArgumentException("Invalid caption admission action");
        if (snapshot == null || snapshot.mOwner != this) return false;
        if (snapshot.mGuard != null) return snapshot.mGuard.run(action);
        action.run();
        return true;
    }

    public synchronized void reject(Sample sample) {
        if (isPending(sample)) mPending = null;
    }

    /** An uncaptured input must not leave an older document looking current. */
    public synchronized void discardDocument(Track track) {
        if (track != null && track == mTrack) {
            mPending = null;
            mSnapshot = null;
            mAnalysis = null;
        }
    }

    /** Seek invalidates work in flight, but does not discard an already accepted same-track document. */
    public synchronized void flush(Track track) {
        if (track != null && track == mTrack) {
            mPending = null;
            mAnalysis = null;
            mActivationRevision++; // Retain accepted content, not pre-flush work or presentation tickets.
        }
    }

    /** A late release from a replaced decoder cannot clear the new track. */
    public synchronized void release(Track track) {
        if (track != null && track == mTrack) clearTrack();
    }

    public synchronized Snapshot getSnapshot() {
        return mSnapshot;
    }

    /** Suspend consumers without discarding immutable data or making another cache namespace. */
    public synchronized void setActive(boolean active) {
        if (!mClosed && mActive != active) { mActive = active; mActivationRevision++; mAnalysis = null; }
    }

    public synchronized long getActivationRevision() { return mActivationRevision; }

    public synchronized boolean isCurrent(Snapshot snapshot) {
        return !mClosed && mActive && snapshot != null && snapshot == mSnapshot && snapshot.track == mTrack;
    }

    /** Half-open time query in document order. Callers must recheck the snapshot after async work. */
    public synchronized List<RawTimeline.Cue> getCuesAt(Snapshot snapshot, long timeUs) {
        return isCurrent(snapshot) ? snapshot.timeline.getCuesAt(timeUs) : Collections.emptyList();
    }

    /** Group ordinals are local to this snapshot, including simultaneous coarse-clock candidates. */
    public synchronized List<Integer> getGroupsAt(Snapshot snapshot, long timeUs) {
        return isCurrent(snapshot) ? snapshot.groups.getGroupsAt(timeUs) : Collections.emptyList();
    }

    public synchronized AnalysisTicket beginAnalysis(Snapshot snapshot) {
        if (!isCurrent(snapshot)) return null;
        mAnalysis = new AnalysisTicket(this, snapshot, mActivationRevision);
        return mAnalysis;
    }

    /** Preparation runs off-lock; a failure or stale ticket must retain the current complete analysis. */
    public boolean publishSegmentation(AnalysisTicket ticket, SegmentationProposal proposal,
                                       SegmentationProposal.Decision decision, SemanticGroupPlanner.Limits limits) {
        return publishSegmentation(ticket, proposal, decision, limits, null);
    }

    /** Lock order is document guard, optional external admission guard, then session commit lock. */
    public boolean publishSegmentation(AnalysisTicket ticket, SegmentationProposal proposal,
            SegmentationProposal.Decision decision, SemanticGroupPlanner.Limits limits, Guard extraGuard) {
        final Snapshot base;
        synchronized (this) {
            if (!currentAnalysis(ticket)) return false;
            base = ticket.mBase;
        }
        final SemanticGroupPlanner.Plan groups;
        try {
            groups = SemanticGroupPlanner.fromDecision(base.transcript, proposal, decision, limits).retainUnchanged(base.groups);
        } catch (IllegalArgumentException rejected) {
            return false;
        }
        boolean[] published = {false};
        Runnable commit = () -> {
            synchronized (CaptionSession.this) {
                if (!currentAnalysis(ticket) || !proposal.canApplyTo(ticket.mBase.groups)) return;
                if (groups != base.groups) {
                    mSnapshot = new Snapshot(CaptionSession.this, mTrack, base.timeline, base.transcript,
                            groups, base.clock, base.mGuard, base.mDocument);
                }
                mAnalysis = null;
                published[0] = true;
            }
        };
        boolean[] extraAdmitted = {extraGuard == null};
        boolean admitted = guard(base, () -> {
            if (extraGuard == null) commit.run();
            else extraAdmitted[0] = extraGuard.run(commit);
        });
        return admitted && extraAdmitted[0] && published[0];
    }

    private boolean currentAnalysis(AnalysisTicket ticket) {
        return ticket != null && ticket.mOwner == this && ticket == mAnalysis && ticket.mActivation == mActivationRevision &&
                isCurrent(ticket.mBase);
    }

    /** Worker-side optional preparation. A rejected budget does not discard the accepted Raw snapshot. */
    public SegmentationProposal prepareSegmentation(Snapshot snapshot, int maxPieceCodePoints,
                                                    SegmentationProposal.Limits limits) {
        synchronized (this) {
            if (!isCurrent(snapshot)) return null;
        }
        SegmentationProposal proposal = SegmentationProposal.prepare(snapshot.transcript, maxPieceCodePoints, limits);
        synchronized (this) {
            return isCurrent(snapshot) ? proposal : null;
        }
    }

    /** Side-effect-free validation; null means stale/foreign. Recheck the snapshot before later delivery. */
    public SegmentationProposal.Decision evaluateSegmentation(Snapshot snapshot, SegmentationProposal proposal,
            List<SegmentationProposal.Unit> units, SegmentationProposal.TextPolicy policy,
            SegmentationProposal.Completion completion) {
        synchronized (this) {
            if (!isCurrent(snapshot) || proposal == null || !proposal.isFor(snapshot.transcript)) return null;
        }
        SegmentationProposal.Decision decision = proposal.evaluate(units, policy, completion);
        synchronized (this) {
            return isCurrent(snapshot) ? decision : null;
        }
    }

    public synchronized void close() {
        clearTrack();
        mClosed = true;
    }
}
