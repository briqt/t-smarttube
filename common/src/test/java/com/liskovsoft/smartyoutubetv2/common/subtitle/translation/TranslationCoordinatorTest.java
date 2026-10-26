package com.liskovsoft.smartyoutubetv2.common.subtitle.translation;

import com.liskovsoft.smartyoutubetv2.common.subtitle.session.CaptionSession;
import com.liskovsoft.smartyoutubetv2.common.subtitle.presentation.NativeCueComposer;
import com.google.android.exoplayer2.text.Cue;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.MappedTranscript;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.RawTimeline;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.RawWebvttParser;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.SemanticGroupPlanner;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

import static com.liskovsoft.smartyoutubetv2.common.subtitle.translation.ChatCompletionAdapterTest.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 27)
public class TranslationCoordinatorTest {
    @Test
    public void defaultDoesNotRequestAndExplicitProfileStillNeedsRequestEnable() {
        Harness h = new Harness("first");
        h.engine.tick(1_000_000, false);
        h.engine.configure(h.profile("zh", 0, 1));
        h.engine.setVisible(true);
        assertTrue(h.queue.tasks.isEmpty());
        assertNull(h.engine.prepareFrame());
        h.engine.setRequesting(true);
        assertEquals(1, h.queue.tasks.size());
        h.queue.drain();
        assertEquals("zh-g0", h.engine.prepareFrame().items.get(0).translation);
        assertEquals(1, h.calls);
    }

    @Test
    public void currentGroupsPrecedeDocumentOrderAndFutureGroupsUseTimeOrder() {
        Harness h = new Harness("first");
        h.replace("00:30.000 --> 00:35.000\nlate\n\n00:01.000 --> 00:05.000\ncurrent\n\n" +
                "00:10.000 --> 00:15.000\nnext\n\n");
        h.enable("zh", 2, 1_000_000);
        h.queue.drain();
        assertEquals(Arrays.asList("g1", "g2"), h.ids.get(0));
        assertEquals("current", h.engine.prepareFrame().items.get(0).original);
        h.engine.tick(1_000_000, false);
        h.queue.drain();
        assertEquals(Collections.singletonList("g0"), h.ids.get(1));
    }

    @Test
    public void queuedSourceChangeDoesNotEvenCreateAProviderJob() {
        Harness h = new Harness("first");
        h.enable("zh", 1, 1_000_000);
        h.session.clearTrack();
        h.queue.drain();
        assertEquals(0, h.created);
        assertEquals(0, h.calls);
        assertNull(h.engine.prepareFrame());
    }

    @Test
    public void lateProfileResultCannotPopulateNewLanguageEvenIfTransportIgnoresCancellation() {
        Harness h = new Harness("first");
        h.ignoreCancellation = true;
        h.enable("zh", 1, 1_000_000);
        h.onHttp = () -> h.engine.configure(h.profile("fr", 1, 1));
        h.queue.one();
        assertEquals(1, h.cancellations);
        assertNull(h.engine.prepareFrame().items.get(0).translation);
        h.queue.drain();
        assertEquals(Arrays.asList("zh", "fr"), h.languages);
        assertEquals("fr-g0", h.engine.prepareFrame().items.get(0).translation);
    }

    @Test
    public void sameLanguageModelOrCredentialNamespaceChangeStillInvalidatesOldWork() {
        Harness h = new Harness("first");
        h.ignoreCancellation = true;
        h.enable("zh", 1, 1_000_000);
        h.onHttp = () -> h.engine.configure(h.profile("zh", 2, 1));
        h.queue.one();
        assertNull(h.engine.prepareFrame().items.get(0).translation);
        h.queue.drain();
        assertEquals(2, h.calls);
        assertEquals("zh-g0", h.engine.prepareFrame().items.get(0).translation);
    }

    @Test
    public void sourceChangesDuringResponseRejectOldResultWithoutNeedingAnotherTick() {
        Harness h = new Harness("first");
        h.ignoreCancellation = true;
        h.enable("zh", 1, 1_000_000);
        TranslationCoordinator.Frame old = h.engine.prepareFrame();
        h.onHttp = () -> h.replace("00:01.000 --> 00:05.000\nnew source\n\n");
        h.queue.one();
        assertFalse(h.engine.deliver(old, frame -> fail("Old source was delivered")));
        assertNull(h.engine.prepareFrame().items.get(0).translation);
        h.engine.tick(1_000_000, false);
        h.queue.drain();
        assertEquals("new source", h.engine.prepareFrame().items.get(0).original);
        assertEquals("zh-g0", h.engine.prepareFrame().items.get(0).translation);
    }

    @Test
    public void seekCancelsOldWindowButKeepsCompletedSameDocumentCache() {
        Harness h = new Harness("first", "second");
        h.enable("zh", 1, 1_000_000);
        h.queue.drain();
        assertEquals("zh-g0", h.engine.prepareFrame().items.get(0).translation);
        h.engine.tick(11_000_000, true);
        h.queue.drain();
        assertEquals("zh-g1", h.engine.prepareFrame().items.get(0).translation);
        h.engine.tick(1_000_000, true);
        assertTrue(h.queue.tasks.isEmpty());
        assertEquals("zh-g0", h.engine.prepareFrame().items.get(0).translation);
        assertEquals(2, h.calls);
    }

    @Test
    public void seekBeforeExecutionReplacesQueuedBatchAndDoesNotPayForOldWindow() {
        Harness h = new Harness("first", "second");
        h.enable("zh", 1, 1_000_000);
        h.engine.tick(11_000_000, true);
        h.queue.drain();
        assertEquals(1, h.calls);
        assertEquals(Collections.singletonList("g1"), h.ids.get(0));
    }

    @Test
    public void requestOffCancelsWorkButPreservesCompletedTextForExplicitReenable() {
        Harness h = new Harness("first");
        h.enable("zh", 1, 1_000_000);
        h.queue.drain();
        TranslationCoordinator.Frame old = h.engine.prepareFrame();
        h.engine.setRequesting(false);
        assertNull(h.engine.prepareFrame());
        assertFalse(h.engine.deliver(old, frame -> fail()));
        h.engine.setRequesting(true);
        assertTrue(h.queue.tasks.isEmpty());
        assertEquals("zh-g0", h.engine.prepareFrame().items.get(0).translation);
        assertEquals(1, h.calls);
    }

    @Test
    public void visibilityIsOnlyAPresentationGateAndDoesNotSilentlyChooseCostPolicy() {
        Harness h = new Harness("first");
        h.enable("zh", 1, 1_000_000);
        TranslationCoordinator.Frame old = h.engine.prepareFrame();
        h.engine.setVisible(false);
        h.queue.drain();
        assertEquals(1, h.calls); // Requesting was explicitly left enabled by the caller.
        assertNull(h.engine.prepareFrame());
        h.engine.setVisible(true);
        assertFalse(h.engine.deliver(old, frame -> fail()));
        assertEquals("zh-g0", h.engine.prepareFrame().items.get(0).translation);
    }

    @Test
    public void presentationChecksRunAtDeliveryNotJustWhenFrameWasPrepared() {
        Harness h = new Harness("first", "second");
        h.enable("zh", 1, 1_000_000);
        h.queue.drain();
        TranslationCoordinator.Frame frame = h.engine.prepareFrame();
        int[] applied = {0};
        Runnable ui = () -> { if (h.engine.deliver(frame, ignored -> applied[0]++)) fail("Stale UI runnable accepted"); };
        h.engine.tick(11_000_000, false);
        ui.run();
        assertEquals(0, applied[0]);
        h.queue.drain();
        assertTrue(h.engine.deliver(h.engine.prepareFrame(), ignored -> applied[0]++));
        assertEquals(1, applied[0]);
    }

    @Test
    public void foreignCoordinatorFrameCannotMatchCoincidentallyEqualRevisions() {
        Harness h = new Harness("first");
        TranslationCoordinator.Profile profile = h.profile("zh", 0, 1);
        h.engine.tick(1_000_000, false);
        h.engine.configure(profile);
        h.engine.setVisible(true);
        h.engine.setRequesting(true);
        TranslationCoordinator.Frame frame = h.engine.prepareFrame();
        TranslationCoordinator other = new TranslationCoordinator(h.session, () -> h.now, new Queue(), h.factory(), limits());
        other.tick(1_000_000, false);
        other.configure(profile);
        other.setVisible(true);
        other.setRequesting(true);
        assertFalse(other.deliver(frame, ignored -> fail()));
    }

    @Test
    public void displayModeOrOrderInvalidationDoesNotRefetchOrDiscardTranslations() {
        Harness h = new Harness("first");
        h.enable("zh", 1, 1_000_000);
        h.queue.drain();
        TranslationCoordinator.Frame old = h.engine.prepareFrame();
        h.engine.invalidatePresentation();
        assertFalse(h.engine.deliver(old, ignored -> fail()));
        h.engine.tick(1_000_000, false);
        assertTrue(h.queue.tasks.isEmpty());
        assertEquals("zh-g0", h.engine.prepareFrame().items.get(0).translation);
        assertEquals(1, h.calls);
    }

    @Test
    public void cachedFrameExpiresAtExecutionEvenWithoutAPlayerTick() {
        Harness h = new Harness("first");
        h.enable("zh", 1, 1_000_000);
        h.queue.drain();
        TranslationCoordinator.Frame frame = h.engine.prepareFrame();
        h.now = 1000;
        assertFalse(h.engine.deliver(frame, ignored -> fail()));
        assertNull(h.engine.prepareFrame().items.get(0).translation);
        h.engine.tick(1_000_000, false);
        h.queue.drain();
        assertEquals(2, h.calls);
    }

    @Test
    public void partialResultsStayCachedAndOnlyMissingGroupsRetryAfterClockDeadline() {
        Harness h = new Harness("first", "second");
        h.omitLast = true;
        h.enable("zh", 2, 1_000_000);
        h.queue.drain();
        assertEquals("zh-g0", h.engine.prepareFrame().items.get(0).translation);
        h.engine.tick(1_000_000, false);
        assertTrue(h.queue.tasks.isEmpty());
        h.omitLast = false;
        h.now = 50;
        h.engine.tick(1_000_000, false);
        h.queue.drain();
        assertEquals(Collections.singletonList("g1"), h.ids.get(1));
        assertEquals(2, h.calls);
    }

    @Test
    public void retryAfterSurvivesSeekSourceAndProfileChangesWithoutImmediateRetry() {
        Harness h = new Harness("first");
        h.httpCode = 429;
        h.retryAfter = "2";
        h.enable("zh", 1, 1_000_000);
        h.queue.drain();
        h.now = 100;
        h.replace("00:01.000 --> 00:05.000\nnew source\n\n");
        h.engine.tick(1_000_000, true);
        h.engine.configure(h.profile("fr", 2, 1));
        assertTrue(h.queue.tasks.isEmpty());
        h.now = 1999;
        h.engine.tick(1_000_000, false);
        assertTrue(h.queue.tasks.isEmpty());
        h.httpCode = 200;
        h.now = 2000;
        h.engine.tick(1_000_000, false);
        h.queue.drain();
        assertEquals(2, h.calls);
        assertEquals("fr-g0", h.engine.prepareFrame().items.get(0).translation);
    }

    @Test
    public void late429FromCancelledWindowStillThrottlesQueuedReplacementBeforeExecution() {
        Harness h = new Harness("first");
        h.ignoreCancellation = true;
        h.httpCode = 429;
        h.retryAfter = "2";
        h.enable("zh", 1, 1_000_000);
        h.onHttp = () -> h.engine.tick(1_000_000, true);
        h.queue.one();
        h.httpCode = 200;
        h.queue.drain();
        assertEquals(1, h.calls);
        assertNull(h.engine.prepareFrame().items.get(0).translation);
        h.now = 1999;
        h.engine.tick(1_000_000, false);
        assertTrue(h.queue.tasks.isEmpty());
        h.now = 2000;
        h.engine.tick(1_000_000, false);
        h.queue.drain();
        assertEquals(2, h.calls);
        assertEquals("zh-g0", h.engine.prepareFrame().items.get(0).translation);
    }

    @Test
    public void authAndPermanentHttpErrorsBlockUntilExplicitNewProfileNotNewSource() {
        for (int code : new int[]{401, 400, 302}) {
            Harness h = new Harness("first");
            h.httpCode = code;
            h.enable("zh", 1, 1_000_000);
            h.queue.drain();
            h.now = 200;
            h.replace("00:01.000 --> 00:05.000\nnew\n\n");
            h.engine.tick(1_000_000, true);
            assertTrue(h.queue.tasks.isEmpty());
            h.httpCode = 200;
            h.engine.configure(h.profile("zh", 3, 1));
            h.queue.drain();
            assertEquals(2, h.calls);
        }
    }

    @Test
    public void temporaryFailuresHaveClockBackoffAndPerGroupAttemptLimit() {
        Harness h = new Harness("first");
        h.httpCode = 503;
        h.enable("zh", 1, 1_000_000);
        h.queue.drain();
        h.engine.tick(1_000_000, false);
        assertTrue(h.queue.tasks.isEmpty());
        h.now = 50;
        h.engine.tick(1_000_000, false);
        h.queue.drain();
        h.now = 200;
        h.engine.tick(1_000_000, false);
        assertTrue(h.queue.tasks.isEmpty());
        assertEquals(2, h.calls);
        assertNull(h.engine.prepareFrame().items.get(0).translation);
    }

    @Test
    public void refusalAndUnknownCompletionDoNotBecomeAutomaticSchedulerRetries() {
        for (String reason : new String[]{"content_filter", "aborted", "novel"}) {
            Harness h = new Harness("first");
            h.reason = reason;
            h.enable("zh", 1, 1_000_000);
            h.queue.drain();
            h.now = 5000;
            h.engine.tick(1_000_000, false);
            assertTrue(h.queue.tasks.isEmpty());
            assertEquals(1, h.calls);
            assertNull(h.engine.prepareFrame().items.get(0).translation);
        }
    }

    @Test
    public void cachePressureCannotCauseRepeatedRequestsCompetingForOneWindow() {
        TranslationCoordinator.Limits tight = new TranslationCoordinator.Limits(30_000_000, 2, 3, 1000, 2, 50, 20);
        Harness h = new Harness(tight, "first", "second");
        h.translation = "xyz";
        h.enable("zh", 2, 1_000_000);
        h.queue.drain();
        for (int i = 0; i < 5; i++) { h.engine.tick(1_000_000, false); h.queue.drain(); }
        assertEquals(1, h.calls);
        assertEquals("xyz", h.engine.prepareFrame().items.get(0).translation);
        h.engine.tick(11_000_000, true);
        h.queue.drain();
        assertEquals(2, h.calls);
        assertEquals("xyz", h.engine.prepareFrame().items.get(0).translation);
    }

    @Test
    public void originalOnlyContextGroupsAndOversizeSourcesSpendNoProviderCall() {
        Harness h = new Harness("...", "   ");
        h.enable("zh", 1, 1_000_000);
        h.queue.drain();
        assertEquals(0, h.calls);
        Harness large = new Harness("long source");
        TranslationCoordinator.Profile profile = new TranslationCoordinator.Profile(config(), () -> "synthetic", 0,
                "en", "zh", "", new TranslationBatch.Limits(1, 1, 0, 100), 1);
        large.engine.tick(1_000_000, false);
        large.engine.configure(profile);
        large.engine.setRequesting(true);
        large.queue.drain();
        assertEquals(0, large.calls);
    }

    @Test
    public void executorRejectionAndCloseCannotFallBackToExecutingOnCaller() {
        Harness h = new Harness("first");
        TranslationCoordinator rejected = new TranslationCoordinator(h.session, () -> h.now,
                task -> { throw new RejectedExecutionException(); }, h.factory(), limits());
        rejected.tick(1_000_000, false);
        rejected.configure(h.profile("zh", 0, 1));
        rejected.setRequesting(true);
        assertEquals(0, h.created);
        h.enable("zh", 1, 1_000_000);
        h.engine.close();
        h.queue.drain();
        assertEquals(0, h.calls);
        assertNull(h.engine.prepareFrame());
        h.engine.configure(h.profile("fr", 1, 1));
        h.engine.setRequesting(true);
        h.engine.tick(1_000_000, false);
        assertTrue(h.queue.tasks.isEmpty());
    }

    @Test
    public void jobBudgetAndCacheExpiryDoNotCreateAnUnboundedRetryMode() {
        Harness h = new Harness(new TranslationCoordinator.Limits(0, 1, 1000, 1, 8, 1, 2), "first");
        h.enable("zh", 1, 1_000_000);
        h.queue.drain();
        h.now = 2;
        h.engine.tick(1_000_000, false);
        h.queue.drain();
        h.now = 4;
        h.engine.tick(1_000_000, false);
        assertTrue(h.queue.tasks.isEmpty());
        assertEquals(2, h.calls);
    }

    @Test
    public void framesAreImmutableAndCarryNullTranslationForNativeFallback() {
        Harness h = new Harness("first");
        h.enable("zh", 1, 1_000_000);
        TranslationCoordinator.Frame frame = h.engine.prepareFrame();
        assertEquals("first", frame.items.get(0).original);
        assertNull(frame.items.get(0).translation);
        try { frame.items.clear(); fail(); } catch (UnsupportedOperationException expected) { }
        h.session.clearTrack();
        assertFalse(h.engine.deliver(frame, ignored -> fail()));
    }

    @Test
    public void fakeBatchResultReachesOneNativeCueOnlyInsideCurrentExecutionGuard() {
        Harness h = new Harness("first");
        h.enable("zh", 1, 1_000_000);
        h.queue.drain();
        TranslationCoordinator.Frame frame = h.engine.prepareFrame();
        List<Cue> nativeCues = Collections.singletonList(new Cue("first"));
        java.util.concurrent.atomic.AtomicReference<List<Cue>> output = new java.util.concurrent.atomic.AtomicReference<>();
        assertTrue(h.engine.deliver(frame, current -> {
            assertEquals(1, current.items.size());
            TranslationCoordinator.Item item = current.items.get(0);
            output.set(NativeCueComposer.compose(nativeCues, item.original, item.translation,
                    NativeCueComposer.Mode.BILINGUAL, NativeCueComposer.Order.ORIGINAL_FIRST, 100));
        }));
        assertEquals(1, output.get().size());
        assertEquals("first\nzh-g0", output.get().get(0).text.toString());
        h.engine.setRequesting(false);
        assertFalse(h.engine.deliver(frame, ignored -> fail("Old bilingual frame executed after off")));
        assertSame(nativeCues, NativeCueComposer.compose(nativeCues, "first", null,
                NativeCueComposer.Mode.ORIGINAL, NativeCueComposer.Order.ORIGINAL_FIRST, 100));
    }

    @Test
    public void temporaryInputSuspensionDropsQueuedWorkWithoutLosingCompletedCacheOrRevivingOldFrames() {
        Harness h = new Harness("first", "second");
        h.enable("zh", 1, 1_000_000);
        h.queue.drain();
        TranslationCoordinator.Frame old = h.engine.prepareFrame();
        h.engine.tick(11_000_000, false);
        TranslationCoordinator.Frame beforeSuspend = h.engine.prepareFrame();
        h.session.setActive(false);
        h.queue.drain();
        assertEquals(1, h.calls);
        assertFalse(h.engine.deliver(old, ignored -> fail()));
        h.session.setActive(true);
        assertFalse(h.engine.deliver(old, ignored -> fail()));
        assertFalse(h.engine.deliver(beforeSuspend, ignored -> fail()));
        h.engine.tick(11_000_000, false);
        h.queue.drain();
        assertEquals(2, h.calls);
        h.engine.tick(1_000_000, true);
        assertTrue(h.queue.tasks.isEmpty());
        assertEquals("zh-g0", h.engine.prepareFrame().items.get(0).translation);
    }

    @Test
    public void completedStringSurvivesSameTrackResampleButOldDocumentAndFrameStayInvalid() {
        Harness h = new Harness("first");
        h.enable("zh", 1, 1_000_000);
        h.queue.drain();
        CaptionSession.Snapshot old = h.session.getSnapshot();
        TranslationCoordinator.Frame oldFrame = h.engine.prepareFrame();
        assertEquals(0, old.track.candidates); // Candidate count, not a source ID.
        assertNull(old.track.source);
        assertEquals(1, h.calls);

        h.resample("00:01.000 --> 00:05.000\nfirst\n\n", 0);
        CaptionSession.Snapshot next = h.session.getSnapshot();
        assertSame(old.track, next.track);
        assertNotSame(old, next);
        assertNotSame(old.timeline, next.timeline);
        assertNotSame(old.transcript, next.transcript);
        assertNotSame(old.groups, next.groups);
        assertTrue(next.transcript.isFor(next.timeline));
        assertTrue(next.groups.isFor(next.transcript));
        assertFalse(h.session.isCurrent(old));
        assertFalse(h.engine.deliver(oldFrame, ignored -> fail("Old Frame must not become current again")));
        h.engine.tick(1_000_000, false);
        h.queue.drain();
        TranslationCoordinator.Frame current = h.engine.prepareFrame();
        assertNotSame(oldFrame, current);
        assertEquals("first", current.items.get(0).original);
        assertEquals("zh-g0", current.items.get(0).translation);
        assertTrue(h.engine.deliver(current, ignored -> { }));
        assertEquals("A completed String, not the old document, must be reused", 1, h.calls);
        assertEquals(1, h.created);
    }

    @Test
    public void completedStringFollowsCurrentMappingRatherThanAnEqualGroupOrdinal() {
        Harness h = new Harness(new TranslationCoordinator.Limits(0, 3, 1000, 1000, 2, 50, 20), "first");
        h.enable("zh", 1, 1_000_000);
        h.queue.drain();
        h.resample("00:20.000 --> 00:25.000\nunrelated\n\n00:01.000 --> 00:05.000\nfirst\n\n", 0);
        h.engine.tick(1_000_000, false);
        h.queue.drain();
        TranslationCoordinator.Item current = h.engine.prepareFrame().items.get(0);
        assertEquals(1, current.groupIndex);
        assertEquals("first", current.original);
        assertEquals("zh-g0", current.translation); // The completed value was returned for the old local g0.
        assertEquals("Changing a local ordinal must not change otherwise equal request semantics", 1, h.calls);
    }

    @Test
    public void changedTextTimingOrEffectiveOffsetCannotBorrowSameTrackCompletedString() {
        for (int mutation = 0; mutation < 3; mutation++) {
            Harness h = new Harness("first");
            h.translation = "completed-before-change";
            h.enable("zh", 1, 2_000_000);
            h.queue.drain();
            TranslationCoordinator.Frame old = h.engine.prepareFrame();
            String cues = mutation == 0 ? "00:01.000 --> 00:05.000\nchanged text\n\n" :
                    mutation == 1 ? "00:01.000 --> 00:06.000\nfirst\n\n" :
                    "00:01.000 --> 00:05.000\nfirst\n\n";
            h.resample(cues, mutation == 2 ? 1_000_000 : 0);
            h.engine.tick(2_000_000, false);
            assertFalse(h.engine.deliver(old, ignored -> fail()));
            assertNull("Changed source semantics must not expose the old String", h.engine.prepareFrame().items.get(0).translation);
            h.translation = "completed-after-change";
            h.queue.drain();
            assertEquals(2, h.calls);
            assertEquals("completed-after-change", h.engine.prepareFrame().items.get(0).translation);
        }
    }

    @Test
    public void sourceLanguageTargetAndExplicitContextRemainSeparateCompletedNamespaces() {
        for (int mutation = 0; mutation < 3; mutation++) {
            Harness h = new Harness("first");
            ChatTranslationClient.Credentials credential = () -> "synthetic-shared-credential";
            TranslationBatch.Limits batch = new TranslationBatch.Limits(1, 1000, 100, 1000);
            TranslationCoordinator.Profile before = new TranslationCoordinator.Profile(config(), credential, 0,
                    "en", "zh", "context before", batch, 1);
            h.engine.tick(1_000_000, false);
            h.engine.configure(before);
            h.engine.setVisible(true);
            h.engine.setRequesting(true);
            h.translation = "completed-before-change";
            h.queue.drain();
            TranslationCoordinator.Frame old = h.engine.prepareFrame();
            TranslationCoordinator.Profile after = new TranslationCoordinator.Profile(before.wire, credential, 0,
                    mutation == 0 ? "fr" : "en", mutation == 1 ? "ja" : "zh",
                    mutation == 2 ? "context after" : "context before", batch, 1);
            h.engine.configure(after);
            assertFalse(h.engine.deliver(old, ignored -> fail()));
            assertNull(h.engine.prepareFrame().items.get(0).translation);
            h.translation = "completed-after-change";
            h.queue.drain();
            assertEquals(2, h.calls);
            assertEquals("completed-after-change", h.engine.prepareFrame().items.get(0).translation);
        }
    }

    @Test
    public void cancelledEqualContentResponseCannotSeedTheReplacementCompletedCache() {
        Harness h = new Harness("first");
        h.ignoreCancellation = true;
        h.translation = "cancelled-result";
        h.enable("zh", 1, 1_000_000);
        h.onHttp = () -> {
            h.resample("00:01.000 --> 00:05.000\nfirst\n\n", 0);
            h.engine.tick(1_000_000, false);
        };
        h.queue.one();
        assertEquals(1, h.calls);
        assertEquals(1, h.cancellations);
        assertNull("Semantic equality cannot legalize a cancelled ticket", h.engine.prepareFrame().items.get(0).translation);
        h.translation = "current-result";
        h.queue.drain();
        assertEquals(2, h.calls);
        assertEquals("current-result", h.engine.prepareFrame().items.get(0).translation);
        h.resample("00:01.000 --> 00:05.000\nfirst\n\n", 0);
        h.engine.tick(1_000_000, false);
        h.queue.drain();
        assertEquals("Only the subsequently valid completion may be reused", 2, h.calls);
        assertEquals("current-result", h.engine.prepareFrame().items.get(0).translation);
    }

    @Test
    public void resamplingCompletedTextDoesNotRenewItsOriginalExpiry() {
        Harness h = new Harness("first");
        h.enable("zh", 1, 1_000_000);
        h.queue.drain();
        h.now = 500;
        h.resample("00:01.000 --> 00:05.000\nfirst\n\n", 0);
        h.engine.tick(1_000_000, false);
        h.queue.drain();
        assertEquals(1, h.calls);
        TranslationCoordinator.Frame reused = h.engine.prepareFrame();
        h.now = 1000;
        assertFalse(h.engine.deliver(reused, ignored -> fail("Reuse cannot extend TTL")));
        assertNull(h.engine.prepareFrame().items.get(0).translation);
        h.engine.tick(1_000_000, false);
        h.queue.drain();
        assertEquals(2, h.calls);
    }

    @Test
    public void repeatedSameTrackSamplesDoNotRepayForPressureSuppressedItemsInTheSameWindow() {
        Harness h = new Harness(new TranslationCoordinator.Limits(30_000_000, 2, 3, 1000, 2, 50, 20), "first", "second");
        String cues = "00:01.000 --> 00:05.000\nfirst\n\n00:11.000 --> 00:15.000\nsecond\n\n";
        h.translation = "xyz";
        h.enable("zh", 2, 1_000_000);
        h.queue.drain();
        for (int i = 0; i < 4; i++) {
            h.resample(cues, 0);
            h.engine.tick(1_000_000, false);
            h.queue.drain();
        }
        assertEquals("Neither cache hits nor pressure suppression may restart spending per Snapshot", 1, h.calls);
        assertEquals("xyz", h.engine.prepareFrame().items.get(0).translation);
        h.engine.tick(11_000_000, true);
        h.queue.drain();
        assertEquals("A newly prioritized uncached group can still request", 2, h.calls);
        assertEquals("xyz", h.engine.prepareFrame().items.get(0).translation);
    }

    @Test
    public void unchangedSeekWindowDoesNotRepayForCachePressure() {
        Harness h = new Harness(new TranslationCoordinator.Limits(30_000_000, 2, 3, 1000, 2, 50, 20), "first", "second");
        h.translation = "xyz";
        h.enable("zh", 2, 1_000_000);
        h.queue.drain();
        assertEquals(1, h.calls);
        for (int i = 0; i < 4; i++) {
            h.engine.tick(1_000_000, true);
            h.queue.drain();
        }
        assertEquals("A discontinuity alone does not change cache pressure or item priority", 1, h.calls);
        assertEquals("xyz", h.engine.prepareFrame().items.get(0).translation);
        h.engine.tick(11_000_000, true);
        h.queue.drain();
        assertEquals("A genuinely changed priority window can still request", 2, h.calls);
        assertEquals("xyz", h.engine.prepareFrame().items.get(0).translation);
    }

    @Test
    public void oversizedCompletedTextStaysSuppressedAcrossSeekUntilExpiry() {
        Harness h = new Harness(new TranslationCoordinator.Limits(0, 1, 3, 1000, 2, 50, 20), "first");
        h.translation = "abcd";
        h.enable("zh", 1, 1_000_000);
        h.queue.drain();
        assertEquals(1, h.calls);
        for (int i = 0; i < 4; i++) {
            h.engine.tick(1_000_000, true);
            h.queue.drain();
        }
        assertEquals("Seeking cannot make an oversized paid result fit the same cache", 1, h.calls);
        assertNull(h.engine.prepareFrame().items.get(0).translation);
        h.now = 1000;
        h.engine.tick(1_000_000, false);
        h.queue.drain();
        assertEquals("Suppression is bounded by its original TTL, not permanent", 2, h.calls);
    }

    /** Cross-package UI fixture: real protocol/transport classes, intercepted before any DNS/network call. */
    public static TranslationCoordinator uiFixture(CaptionSession session, TranslationCoordinator.Clock clock,
            Executor executor, java.util.concurrent.atomic.AtomicInteger calls) {
        return uiFixture(session, clock, executor, calls, new java.util.concurrent.atomic.AtomicReference<>(), false);
    }

    public static TranslationCoordinator uiFixture(CaptionSession session, TranslationCoordinator.Clock clock,
            Executor executor, java.util.concurrent.atomic.AtomicInteger calls,
            java.util.concurrent.atomic.AtomicReference<Runnable> beforeResponse, boolean ignoreCancellation) {
        return uiFixture(session, clock, executor, calls, beforeResponse, ignoreCancellation,
                (target, source) -> "译:" + source);
    }

    public static TranslationCoordinator uiFixture(CaptionSession session, TranslationCoordinator.Clock clock,
            Executor executor, java.util.concurrent.atomic.AtomicInteger calls,
            java.util.concurrent.atomic.AtomicReference<Runnable> beforeResponse, boolean ignoreCancellation,
            java.util.function.BiFunction<String, String, String> translation) {
        ChatTranslationClient client = new ChatTranslationClient(new OkHttpClient.Builder().addInterceptor(chain -> {
            calls.incrementAndGet();
            try {
                Buffer bytes = new Buffer();
                chain.request().body().writeTo(bytes);
                JSONObject body = BoundedJson.object(bytes.readUtf8());
                JSONObject input = BoundedJson.object(body.getJSONArray("messages").getJSONObject(1).getString("content"));
                JSONArray translated = new JSONArray();
                JSONArray items = input.getJSONArray("items");
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.getJSONObject(i);
                    translated.put(new JSONObject().put("id", item.getString("id")).put("text",
                            translation.apply(input.getString("target_language"), item.getString("source"))));
                }
                byte[] response = envelope("stop", new JSONObject().put("translations", translated).toString(), null);
                Runnable action = beforeResponse.getAndSet(null);
                if (action != null) action.run();
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                        .body(ResponseBody.create(MediaType.parse("application/json"), response)).build();
            } catch (Exception error) { throw new AssertionError(error); }
        }).build());
        return new TranslationCoordinator(session, clock, executor, (batch, profile) -> {
            ChatTranslationClient.Job job = client.newJob(batch, profile.wire, profile.credentials, profile.maxCalls);
            return new TranslationCoordinator.Work() {
                public ChatTranslationClient.Result execute() { return job.execute(); }
                public void cancel() { if (!ignoreCancellation) job.cancel(); }
            };
        }, limits());
    }

    private static TranslationCoordinator.Limits limits() {
        return new TranslationCoordinator.Limits(30_000_000, 3, 1000, 1000, 2, 50, 20);
    }

    private static final class Queue implements Executor {
        final List<Runnable> tasks = new ArrayList<>();
        public void execute(Runnable task) { tasks.add(task); }
        void one() { tasks.remove(0).run(); }
        void drain() { int count = 0; while (!tasks.isEmpty()) { if (++count > 50) throw new AssertionError("Unbounded work"); one(); } }
    }

    private static final class Harness {
        final CaptionSession session = new CaptionSession();
        final Queue queue = new Queue();
        final List<List<String>> ids = new ArrayList<>();
        final List<String> languages = new ArrayList<>();
        final TranslationCoordinator engine;
        long now;
        int calls, created, cancellations;
        int httpCode = 200;
        String reason = "stop", retryAfter, translation;
        boolean omitLast, ignoreCancellation;
        Runnable onHttp;

        Harness(String... sources) { this(limits(), sources); }
        Harness(TranslationCoordinator.Limits limits, String... sources) {
            StringBuilder cues = new StringBuilder();
            for (int i = 0; i < sources.length; i++) cues.append(String.format(java.util.Locale.US,
                    "00:%02d.000 --> 00:%02d.000\n%s\n\n", 1 + 10 * i, 5 + 10 * i, sources[i]));
            replace(cues.toString());
            engine = new TranslationCoordinator(session, () -> now, queue, factory(), limits);
        }

        void replace(String cues) {
            RawTimeline raw = RawWebvttParser.parse(("WEBVTT\n\n" + cues).getBytes(utf8()), 0,
                    new RawTimeline.Limits(65536, 64, 128));
            MappedTranscript mapped = MappedTranscript.fromRaw(raw, 128);
            SemanticGroupPlanner.Plan plan = SemanticGroupPlanner.preserve(mapped, new SemanticGroupPlanner.Limits(64, 128, 128));
            CaptionSession.Track track = session.selectTrack(0, null);
            assertTrue(session.accept(session.beginSample(track), raw, mapped, plan));
        }

        void resample(String cues, long offsetUs) {
            CaptionSession.Track track = session.getSnapshot().track;
            RawTimeline raw = RawWebvttParser.parse(("WEBVTT\n\n" + cues).getBytes(utf8()), offsetUs,
                    new RawTimeline.Limits(65536, 64, 128));
            MappedTranscript mapped = MappedTranscript.fromRaw(raw, 128);
            SemanticGroupPlanner.Plan plan = SemanticGroupPlanner.preserve(mapped, new SemanticGroupPlanner.Limits(64, 128, 128));
            assertTrue(session.accept(session.beginSample(track), raw, mapped, plan));
        }

        TranslationCoordinator.Profile profile(String target, long revision, int items) {
            return new TranslationCoordinator.Profile(config(), () -> "synthetic-fixture-key", revision,
                    "en", target, "", new TranslationBatch.Limits(items, 1000, 0, 1000), 1);
        }

        void enable(String target, int items, long position) {
            engine.tick(position, false);
            engine.configure(profile(target, 0, items));
            engine.setVisible(true);
            engine.setRequesting(true);
        }

        TranslationCoordinator.WorkFactory factory() {
            return (batch, profile) -> {
                created++;
                ChatTranslationClient client = new ChatTranslationClient(new OkHttpClient.Builder().addInterceptor(chain -> {
                    calls++;
                    try {
                        Buffer buffer = new Buffer();
                        chain.request().body().writeTo(buffer);
                        JSONObject body = BoundedJson.object(buffer.readUtf8());
                        JSONObject input = BoundedJson.object(body.getJSONArray("messages").getJSONObject(1).getString("content"));
                        String target = input.getString("target_language");
                        languages.add(target);
                        JSONArray items = input.getJSONArray("items");
                        List<String> requested = new ArrayList<>();
                        for (int i = 0; i < items.length(); i++) requested.add(items.getJSONObject(i).getString("id"));
                        ids.add(requested);
                        Runnable action = onHttp; onHttp = null;
                        if (action != null) action.run();
                        JSONArray output = new JSONArray();
                        for (int i = 0; i < requested.size() - (omitLast ? 1 : 0); i++) {
                            output.put(new JSONObject().put("id", requested.get(i)).put("text", translation == null ? target + "-" + requested.get(i) : translation));
                        }
                        byte[] data = envelope(reason, new JSONObject().put("translations", output).toString(), null);
                        Response.Builder response = new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                                .code(httpCode).message("synthetic").body(ResponseBody.create(MediaType.parse("application/json"), data));
                        if (retryAfter != null) response.header("Retry-After", retryAfter);
                        return response.build();
                    } catch (Exception e) { throw new AssertionError(e); }
                }).build());
                ChatTranslationClient.Job job = client.newJob(batch, profile.wire, profile.credentials, profile.maxCalls);
                return new TranslationCoordinator.Work() {
                    public ChatTranslationClient.Result execute() { return job.execute(); }
                    public void cancel() { cancellations++; if (!ignoreCancellation) job.cancel(); }
                };
            };
        }
    }
}
