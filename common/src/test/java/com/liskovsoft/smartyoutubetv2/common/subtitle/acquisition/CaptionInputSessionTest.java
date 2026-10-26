package com.liskovsoft.smartyoutubetv2.common.subtitle.acquisition;

import com.liskovsoft.smartyoutubetv2.common.subtitle.session.CaptionSession;
import com.liskovsoft.smartyoutubetv2.common.subtitle.source.CaptionSource;
import com.liskovsoft.smartyoutubetv2.common.subtitle.source.CaptionSourceClassifier;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.MappedTranscript;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.RawTimeline;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.RawWebvttParser;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.SemanticGroupPlanner;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.SegmentationProposal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static com.liskovsoft.smartyoutubetv2.common.subtitle.acquisition.NativeCaptionLoaderTest.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 27)
public class CaptionInputSessionTest {
    @Test
    public void disabledInputSpendsNoExtraRequestAndUnknownLabelsNeverStopAvailableCurrentText() {
        Harness h = new Harness(false);
        Object window = new Object();
        assertNull(h.input.tick(window));
        assertTrue(h.worker.tasks.isEmpty());
        h.input.setEnabled(true);
        CaptionInputSession.Selection selection = h.input.tick(window);
        assertNotNull(selection);
        assertEquals("current text", selection.input.timeline.getCues().get(0).text);
        assertFalse(selection.usingFetchedOriginal);
        assertNull(selection.textLanguage);
        assertTrue(h.worker.tasks.isEmpty());
        h.input.close();
    }

    @Test
    public void originalArrivingWithinWaitBecomesInputWithoutUsingOrRewritingMachineUrl() {
        Harness h = new Harness(true);
        h.input.setEnabled(true);
        Object window = new Object();
        assertNull(h.input.tick(window));
        assertEquals(1, h.worker.tasks.size());
        h.worker.drain();
        CaptionInputSession.Selection selection = h.input.tick(window);
        assertTrue(selection.usingFetchedOriginal);
        assertEquals("original", selection.input.timeline.getCues().get(0).text);
        assertEquals("en", selection.textLanguage);
        assertTrue(selection.alreadyTargetLanguage("EN"));
        assertFalse(selection.alreadyTargetLanguage("zh-Hans"));
        assertEquals(1, h.transport.created);
        h.input.close();
    }

    @Test
    public void deadlineUsesExistingTrackAndLateOriginalOnlyChangesTheNextWindow() {
        Harness h = new Harness(true);
        h.input.setEnabled(true);
        Object first = new Object();
        assertNull(h.input.tick(first));
        h.now = 1000;
        CaptionInputSession.Selection fallback = h.input.tick(first);
        assertNotNull(fallback);
        assertFalse(fallback.usingFetchedOriginal);
        assertTrue(fallback.alreadyTargetLanguage("zh-Hans"));
        h.worker.drain();
        CaptionInputSession.Selection stillCurrent = h.input.tick(first);
        assertSame(fallback.input, stillCurrent.input);
        assertTrue(h.input.isCurrent(fallback));
        CaptionInputSession.Selection next = h.input.tick(new Object());
        assertTrue(next.usingFetchedOriginal);
        assertFalse(h.input.isCurrent(fallback));
        assertEquals(1, h.transport.created);
        h.input.close();
    }

    @Test
    public void failuresKeepCurrentTextAndBackgroundRetriesAreLimitedWithoutRestartingWait() {
        Harness h = new Harness(true);
        h.transport.failRead = true;
        h.input.setEnabled(true);
        Object window = new Object();
        assertNull(h.input.tick(window));
        h.worker.drain();
        assertNotNull(h.input.tick(window)); // Known failure need not wait out the rest of the first deadline.
        h.now = 499;
        h.input.tick(window);
        assertTrue(h.worker.tasks.isEmpty());
        h.now = 500;
        assertNotNull(h.input.tick(window));
        h.worker.drain();
        h.now = 5000;
        assertNotNull(h.input.tick(new Object()));
        assertTrue(h.worker.tasks.isEmpty());
        assertEquals(2, h.transport.created);
        h.input.close();
    }

    @Test
    public void nativeNoneOrParentInvalidationPreventsSelectionDeliveryAndEffectiveRequests() {
        Harness h = new Harness(false);
        h.input.setEnabled(true);
        Object window = new Object();
        CaptionInputSession.Selection selection = h.input.tick(window);
        assertTrue(h.input.deliver(selection, value -> assertSame(selection.input, value.input)));
        h.nativeSession.clearTrack();
        assertFalse(h.input.deliver(selection, value -> fail()));
        assertNull(h.input.tick(window));
        assertNull(h.input.getSession().getSnapshot());
        h.input.close();
    }

    @Test
    public void lateOldTrackCannotReleaseOrPublishOverNewTrackAndCloseCannotRestart() {
        Harness h = new Harness(true);
        h.input.setEnabled(true);
        h.input.tick(new Object());
        CaptionSession.Track old = h.track;
        h.bind(false);
        h.worker.drain();
        CaptionInputSession.Selection current = h.input.tick(new Object());
        h.input.release(old);
        assertTrue(h.input.isCurrent(current));
        assertFalse(current.usingFetchedOriginal);
        h.input.close();
        h.bind(true);
        h.input.setEnabled(true);
        assertNull(h.input.tick(new Object()));
    }

    @Test
    public void nonzeroClockOffsetUsesCurrentTrackEvenIfAnOriginalWasCached() {
        Harness h = new Harness(true);
        h.input.setEnabled(true);
        h.input.tick(new Object());
        h.worker.drain();
        assertTrue(h.input.tick(new Object()).usingFetchedOriginal);
        CaptionSession.Snapshot nativeSnapshot = h.nativeSession.getSnapshot();
        h.input.observe(h.track, nativeSnapshot, 4_000_000);
        CaptionInputSession.Selection current = h.input.tick(new Object());
        assertFalse(current.usingFetchedOriginal);
        assertSame(nativeSnapshot.timeline, current.input.timeline);
        h.input.close();
    }

    @Test
    public void emptyNativeWindowDoesNotReviveTextAndForeignSelectionsCannotBeDelivered() {
        Harness h = new Harness(false), other = new Harness(false);
        h.input.setEnabled(true);
        other.input.setEnabled(true);
        CaptionInputSession.Selection selection = h.input.tick(new Object());
        assertNull(h.input.tick(null));
        assertFalse(h.input.deliver(selection, ignored -> fail()));
        assertFalse(other.input.deliver(selection, ignored -> fail()));
        h.input.close(); other.input.close();
    }

    @Test
    public void emptyOriginalCannotReplaceUsableCurrentTextAndConflictingLanguageDoesNotSkipTranslation() {
        Harness h = new Harness(true);
        h.transport.body = bytes("WEBVTT\n\n");
        h.input.setEnabled(true);
        Object window = new Object();
        h.input.tick(window);
        h.worker.drain();
        assertFalse(h.input.tick(window).usingFetchedOriginal);
        RawTimeline raw = RawWebvttParser.parse(bytes("WEBVTT\nLanguage: en\n\n00:01.000 --> 00:04.000\ncurrent text\n"), 0, LIMITS);
        MappedTranscript mapped = MappedTranscript.fromRaw(raw, LIMITS.textRuns);
        SemanticGroupPlanner.Plan groups = SemanticGroupPlanner.preserve(mapped, new SemanticGroupPlanner.Limits(32, 128, 128));
        assertTrue(h.nativeSession.accept(h.nativeSession.beginSample(h.track), raw, mapped, groups));
        h.input.observe(h.track, h.nativeSession.getSnapshot(), 0);
        CaptionInputSession.Selection selection = h.input.tick(new Object());
        assertNotNull(selection);
        assertNull(selection.textLanguage);
        assertFalse(selection.alreadyTargetLanguage("zh-Hans"));
        h.input.close();
    }

    @Test
    public void disableSuspendsConsumersButReenableKeepsSameCompletedDocumentIdentity() {
        Harness h = new Harness(false);
        h.input.setEnabled(true);
        CaptionInputSession.Selection first = h.input.tick(new Object());
        CaptionSession.Snapshot document = first.input;
        h.input.setEnabled(false);
        assertNull(h.input.tick(new Object()));
        assertSame(document, h.input.getSession().getSnapshot());
        assertFalse(h.input.getSession().isCurrent(document));
        h.input.setEnabled(true);
        CaptionInputSession.Selection resumed = h.input.tick(new Object());
        assertSame(document, resumed.input);
        assertFalse(h.input.isCurrent(first));
        assertEquals(0, h.transport.created);
        h.input.close();
    }

    @Test
    public void nativeSeekKeepsPublishedEffectiveAnalysisButNeverRevivesOldSelections() {
        Harness h = new Harness(false);
        h.input.setEnabled(true);
        CaptionInputSession.Selection first = h.input.tick(new Object());
        CaptionSession effective = h.input.getSession();
        SegmentationProposal proposal = SegmentationProposal.prepare(first.input.transcript, 7,
                new SegmentationProposal.Limits(32, 128, 128, 32));
        List<SegmentationProposal.Unit> units = new ArrayList<>();
        for (SegmentationProposal.Piece piece : proposal.getPieces()) {
            units.add(new SegmentationProposal.Unit(Collections.singletonList(piece.id), piece.text));
        }
        SegmentationProposal.Decision decision = proposal.evaluate(units,
                SegmentationProposal.TextPolicy.EXACT, SegmentationProposal.Completion.COMPLETE);
        assertTrue(effective.publishSegmentation(effective.beginAnalysis(first.input), proposal, decision,
                new SemanticGroupPlanner.Limits(32, 128, 128)));
        CaptionInputSession.Selection refined = h.input.tick(new Object());
        SemanticGroupPlanner.Plan accepted = refined.input.groups;
        assertTrue(accepted.getGroups().size() > first.input.groups.getGroups().size());
        h.nativeSession.flush(h.track);
        CaptionInputSession.Selection afterSeek = h.input.tick(new Object());
        assertNotNull(afterSeek);
        assertSame(accepted, afterSeek.input.groups);
        assertNotSame(refined.input, afterSeek.input);
        assertFalse(h.input.isCurrent(first));
        assertFalse(h.input.isCurrent(refined));
        assertTrue(h.input.isCurrent(afterSeek));
        // A newly accepted native document, even with the same Raw, must replace old analysis.
        CaptionSession.Snapshot nativeBefore = h.nativeSession.getSnapshot();
        assertTrue(h.nativeSession.accept(h.nativeSession.beginSample(h.track), nativeBefore.timeline,
                nativeBefore.transcript, nativeBefore.groups));
        h.input.observe(h.track, h.nativeSession.getSnapshot(), 0);
        CaptionInputSession.Selection replacement = h.input.tick(new Object());
        assertSame(nativeBefore.groups, replacement.input.groups);
        assertNotSame(accepted, replacement.input.groups);
        h.input.close();
    }

    private static final class Harness {
        final CaptionSession nativeSession = new CaptionSession();
        final Queue worker = new Queue();
        final Transport transport = new Transport();
        final CaptionInputSession input;
        CaptionSession.Track track;
        long now;
        Harness(boolean original) {
            input = new CaptionInputSession(nativeSession, () -> now, new NativeCaptionLoader(worker, Runnable::run),
                    new CaptionInputSession.Limits(1000, 500, 2, LIMITS));
            bind(original);
        }
        void bind(boolean original) {
            CaptionSource source = original ? CaptionSourceClassifier.classify(translated()) : null;
            track = nativeSession.selectTrack(original ? 1 : 0, source);
            RawTimeline raw = RawWebvttParser.parse(vtt("current text"), 0, LIMITS);
            MappedTranscript mapped = MappedTranscript.fromRaw(raw, LIMITS.textRuns);
            SemanticGroupPlanner.Plan groups = SemanticGroupPlanner.preserve(mapped, new SemanticGroupPlanner.Limits(32, 128, 128));
            assertTrue(nativeSession.accept(nativeSession.beginSample(track), raw, mapped, groups));
            input.bind(track, original ? request(transport) : null, 10_000_000);
            input.observe(track, nativeSession.getSnapshot(), 0);
        }
    }
}
