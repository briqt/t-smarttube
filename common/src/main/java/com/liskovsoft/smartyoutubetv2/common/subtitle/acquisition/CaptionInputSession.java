package com.liskovsoft.smartyoutubetv2.common.subtitle.acquisition;

import com.liskovsoft.smartyoutubetv2.common.subtitle.session.CaptionSession;
import com.liskovsoft.smartyoutubetv2.common.subtitle.source.CaptionSource;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.RawTimeline;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.MappedTranscript;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.SemanticGroupPlanner;

/** Original-first input choice with a short wait and current-track fallback, never a user settings layer. */
public final class CaptionInputSession {
    public interface Clock { long nowMs(); }
    public interface Action { void apply(Selection selection); }
    public static final class Limits {
        public final long waitMs;
        public final long retryMs;
        public final int attempts;
        public final RawTimeline.Limits timeline;
        public Limits(long waitMs, long retryMs, int attempts, RawTimeline.Limits timeline) {
            if (waitMs < 0 || waitMs > 5000 || retryMs <= 0 || retryMs > 60000 || attempts <= 0 || attempts > 3 || timeline == null) {
                throw new IllegalArgumentException("Invalid caption input limits");
            }
            this.waitMs = waitMs;
            this.retryMs = retryMs;
            this.attempts = attempts;
            this.timeline = timeline;
        }
    }

    /** Sensitive models; current native track and effective translation input remain distinct. */
    public static final class Selection {
        public final CaptionSession.Snapshot nativeSnapshot;
        public final CaptionSession.Snapshot input;
        public final boolean usingFetchedOriginal;
        public final String textLanguage;
        private final CaptionInputSession mOwner;
        private final Object mWindow;
        private final long mRevision;
        private Selection(CaptionInputSession owner, Object window, long revision, CaptionSession.Snapshot nativeSnapshot,
                          CaptionSession.Snapshot input, boolean original, String textLanguage) {
            mOwner = owner;
            mWindow = window;
            mRevision = revision;
            this.nativeSnapshot = nativeSnapshot;
            this.input = input;
            usingFetchedOriginal = original;
            this.textLanguage = textLanguage;
        }
        /** Consistent declared-language equality, not content-language detection or display-name guessing. */
        public boolean alreadyTargetLanguage(String target) {
            return target != null && textLanguage != null && target.equalsIgnoreCase(textLanguage);
        }
    }

    private enum Choice { WAIT, CURRENT, ORIGINAL }
    private static final class Ticket {
        final CaptionSession.Track track;
        final NativeCaptionRequest request;
        final long durationUs;
        NativeCaptionLoader.Load load;
        boolean done;
        Ticket(CaptionSession.Track track, NativeCaptionRequest request, long durationUs) {
            this.track = track;
            this.request = request;
            this.durationUs = durationUs;
        }
    }

    private final CaptionSession mNative;
    private final CaptionSession mEffective = new CaptionSession();
    private final Clock mClock;
    private final NativeCaptionLoader mLoader;
    private final Limits mLimits;
    private CaptionSession.Track mTrack;
    private CaptionSession.CacheScope mOriginalScope;
    private CaptionSession.Snapshot mNativeSnapshot;
    private NativeCaptionRequest mRequest;
    private NativeCaptionLoader.Result mOriginal;
    private Ticket mTicket;
    private long mDurationUs;
    private long mOffsetUs;
    private long mFirstDeadline;
    private long mRetryAt;
    private int mAttempts;
    private Object mWindow;
    private Choice mChoice;
    private static final class Published {
        final Object analysis;
        final CaptionSession.Snapshot nativeSnapshot;
        Published(Object analysis, CaptionSession.Snapshot nativeSnapshot) {
            this.analysis = analysis;
            this.nativeSnapshot = nativeSnapshot;
        }
    }
    private Published mPublished;
    private long mPublishedActivation = -1;
    private long mRevision;
    private boolean mEnabled;
    private boolean mClosed;

    public CaptionInputSession(CaptionSession nativeSession, Clock clock, NativeCaptionLoader loader, Limits limits) {
        if (nativeSession == null || clock == null || loader == null || limits == null) throw new IllegalArgumentException("Invalid caption input session");
        mNative = nativeSession;
        mClock = clock;
        mLoader = loader;
        mLimits = limits;
        mEffective.setActive(false);
    }

    public CaptionSession getSession() { return mEffective; }

    public void bind(CaptionSession.Track track, NativeCaptionRequest request, long durationUs) {
        bind(track, request, durationUs, new CaptionSession.CacheScope());
    }

    public void bind(CaptionSession.Track track, NativeCaptionRequest request, long durationUs,
                     CaptionSession.CacheScope originalScope) {
        if (originalScope == null) throw new IllegalArgumentException("Invalid original caption scope");
        NativeCaptionLoader.Load cancel;
        synchronized (this) {
            if (mClosed) return;
            cancel = resetLocked();
            mTrack = track;
            mOriginalScope = originalScope;
            mRequest = request;
            mDurationUs = durationUs;
        }
        cancel(cancel);
    }

    public synchronized void observe(CaptionSession.Track track, CaptionSession.Snapshot snapshot, long offsetUs) {
        if (mClosed || track == null || track != mTrack || snapshot == null || snapshot.track != track || !mNative.isCurrent(snapshot)) return;
        if (mNativeSnapshot != snapshot) {
            mNativeSnapshot = snapshot;
            mWindow = null;
            mChoice = null;
            mPublished = null;
            mEffective.clearTrack();
            mRevision++;
        }
        mOffsetUs = offsetUs;
    }

    public void setEnabled(boolean enabled) {
        NativeCaptionLoader.Load cancel = null;
        synchronized (this) {
            if (mClosed || mEnabled == enabled) return;
            mEnabled = enabled;
            mEffective.setActive(enabled);
            mRevision++;
            if (!enabled) {
                cancel = detachLocked();
                mWindow = null;
                mChoice = null;
            }
        }
        cancel(cancel);
    }

    /** window is the native visible-cue event identity; null never creates or revives visible captions. */
    public Selection tick(Object window) {
        Ticket start = null;
        NativeCaptionLoader.Load cancel = null;
        boolean ready;
        synchronized (this) {
            ready = readyLocked();
            if (!ready) {
                cancel = detachLocked();
                if (mClosed || mNativeSnapshot == null || !mNative.isCurrent(mNativeSnapshot)) {
                    mEffective.clearTrack();
                    mPublished = null;
                }
                if (mWindow != null) mRevision++;
                mWindow = null;
                mChoice = null;
            } else {
                if (mWindow != window) { mWindow = window; mChoice = null; mRevision++; }
                long now = now();
                if (window != null && mOriginal == null && mTicket == null && mRequest != null && mOffsetUs == 0 && mDurationUs > 0 &&
                        mAttempts < mLimits.attempts && now >= mRetryAt) {
                    if (mAttempts == 0) mFirstDeadline = add(now, mLimits.waitMs);
                    mAttempts++;
                    start = new Ticket(mTrack, mRequest, mDurationUs);
                    mTicket = start;
                }
            }
        }
        cancel(cancel);
        if (!ready || window == null) return null;
        if (start != null) start(start);
        synchronized (this) {
            synchronized (mNative) { return selectLocked(window); }
        }
    }

    private Selection selectLocked(Object window) {
        if (!readyLocked() || mWindow != window) return null;
        if (mChoice == null || mChoice == Choice.WAIT) {
            mChoice = mOriginal != null && mOffsetUs == 0 ? Choice.ORIGINAL :
                    mTicket != null && now() < mFirstDeadline ? Choice.WAIT : Choice.CURRENT;
        }
        if (mChoice == Choice.WAIT) return null; // Native captions continue while the optional lookup waits.
        boolean original = mChoice == Choice.ORIGINAL && mOriginal != null && mOffsetUs == 0;
        RawTimeline timeline = original ? mOriginal.timeline : mNativeSnapshot.timeline;
        CaptionSource source = original ? mRequest.source : mNativeSnapshot.track.source;
        long activation = mNative.getActivationRevision();
        Object analysis = original ? mOriginal : mNativeSnapshot;
        if (mPublished == null || mPublished.analysis != analysis || mPublished.nativeSnapshot != mNativeSnapshot ||
                mEffective.getSnapshot() == null || mPublishedActivation != activation) {
            CaptionSession.Snapshot previous = mEffective.getSnapshot();
            boolean retainAnalysis = previous != null && previous.timeline == timeline && mPublished != null &&
                    mPublished.analysis == analysis && mPublished.nativeSnapshot == mNativeSnapshot;
            MappedTranscript transcript = retainAnalysis ? previous.transcript :
                    original ? mOriginal.transcript : mNativeSnapshot.transcript;
            SemanticGroupPlanner.Plan groups = retainAnalysis ? previous.groups :
                    original ? mOriginal.groups : mNativeSnapshot.groups;
            CaptionSession.Track track = mEffective.selectTrack(original ? 1 : mNativeSnapshot.track.candidates, source,
                    original ? mOriginalScope : mNativeSnapshot.track.cacheScope);
            CaptionSession.InputClock clock = original ? new CaptionSession.InputClock(mRequest.mime(), 0,
                    timeline.getOrigin() == RawTimeline.Origin.NATIVE_DISPLAY ? mDurationUs : 0) : mNativeSnapshot.clock;
            boolean accepted = mEffective.accept(mEffective.beginSample(track), timeline,
                    transcript, groups, clock, admissionFor(mNativeSnapshot, activation));
            if (!accepted) return null;
            mPublished = new Published(analysis, mNativeSnapshot);
            mPublishedActivation = activation;
            mRevision++;
        }
        return new Selection(this, window, mRevision, mNativeSnapshot, mEffective.getSnapshot(), original, language(source, timeline));
    }

    /** Completion is document-scoped, not tied to a now-obsolete visible-cue window. */
    private CaptionSession.Guard admissionFor(CaptionSession.Snapshot nativeSnapshot, long activation) {
        return action -> {
            boolean[] admitted = {false};
            boolean parent = mNative.guard(nativeSnapshot, () -> {
                synchronized (CaptionInputSession.this) {
                    synchronized (mNative) {
                        if (!readyLocked() || mNativeSnapshot != nativeSnapshot || mNative.getActivationRevision() != activation) return;
                        action.run();
                        admitted[0] = true;
                    }
                }
            });
            return parent && admitted[0];
        };
    }

    private void start(Ticket ticket) {
        synchronized (this) {
            if (mClosed || !mEnabled || mTicket != ticket || mTrack != ticket.track) return;
        }
        NativeCaptionLoader.Load load = mLoader.start(ticket.request, ticket.durationUs, mLimits.timeline, result -> {
            synchronized (CaptionInputSession.this) {
                ticket.done = true;
                if (mClosed || !mEnabled || mTicket != ticket || mTrack != ticket.track) return;
                mTicket = null;
                if (result.status == NativeCaptionLoader.Status.ACCEPTED && usable(result) && mNative.isCurrent(mNativeSnapshot)) {
                    mOriginal = result;
                } else {
                    mRetryAt = add(now(), mLimits.retryMs);
                }
                // Do not replace an already chosen current-text window; the next cue can use the original.
            }
        });
        boolean active;
        synchronized (this) { active = mTicket == ticket && !ticket.done && !mClosed && mEnabled; if (active) ticket.load = load; }
        if (!active) load.cancel();
    }

    private boolean readyLocked() {
        if (!mClosed && mNativeSnapshot != null) {
            CaptionSession.Snapshot latest = mNative.getSnapshot();
            if (latest != null && latest != mNativeSnapshot && latest.sameDocumentAs(mNativeSnapshot) && mNative.isCurrent(latest)) {
                mNativeSnapshot = latest;
                mRevision++;
            }
        }
        return !mClosed && mEnabled && mTrack != null && mNativeSnapshot != null && mNative.isCurrent(mNativeSnapshot);
    }

    public synchronized boolean isCurrent(Selection selection) {
        return selection != null && selection.mOwner == this && readyLocked() && selection.mWindow == mWindow &&
                selection.mRevision == mRevision && selection.nativeSnapshot == mNativeSnapshot &&
                mPublishedActivation == mNative.getActivationRevision() && mEffective.isCurrent(selection.input);
    }

    /** Run the coordinator's execution-time deliver inside this guard; it owns its effective-session lock. */
    public boolean deliver(Selection selection, Action action) {
        if (action == null) throw new IllegalArgumentException("Invalid caption input action");
        synchronized (this) {
            synchronized (mNative) {
                if (!isCurrent(selection)) return false;
                action.apply(selection);
                return true;
            }
        }
    }

    public void invalidate(CaptionSession.Track track) {
        NativeCaptionLoader.Load cancel;
        synchronized (this) {
            if (track == null || track != mTrack) return;
            cancel = detachLocked();
            mNativeSnapshot = null;
            mWindow = null;
            mChoice = null;
            mPublished = null;
            mEffective.clearTrack();
            mRevision++;
        }
        cancel(cancel);
    }

    public void release(CaptionSession.Track track) {
        NativeCaptionLoader.Load cancel;
        synchronized (this) { if (track == null || track != mTrack) return; cancel = resetLocked(); }
        cancel(cancel);
    }

    public void clear() {
        NativeCaptionLoader.Load cancel;
        synchronized (this) { cancel = resetLocked(); }
        cancel(cancel);
    }

    public void close() {
        NativeCaptionLoader.Load cancel;
        synchronized (this) {
            if (mClosed) return;
            mClosed = true;
            mEnabled = false;
            cancel = resetLocked();
            mEffective.close();
        }
        cancel(cancel);
        mLoader.close();
    }

    private NativeCaptionLoader.Load resetLocked() {
        NativeCaptionLoader.Load cancel = detachLocked();
        mTrack = null;
        mOriginalScope = null;
        mNativeSnapshot = null;
        mRequest = null;
        mOriginal = null;
        mWindow = null;
        mChoice = null;
        mPublished = null;
        mPublishedActivation = -1;
        mAttempts = 0;
        mFirstDeadline = 0;
        mRetryAt = 0;
        mEffective.clearTrack();
        mRevision++;
        return cancel;
    }

    private NativeCaptionLoader.Load detachLocked() {
        NativeCaptionLoader.Load load = mTicket == null ? null : mTicket.load;
        mTicket = null;
        return load;
    }

    private static boolean usable(NativeCaptionLoader.Result result) {
        if (result.timeline == null || result.transcript == null || result.groups == null) return false;
        for (MappedTranscript.Atom atom : result.transcript.getAtoms()) {
            for (int i = 0; i < atom.text.length();) {
                int cp = atom.text.codePointAt(i), type = Character.getType(cp);
                if (Character.isLetterOrDigit(cp) || type == Character.OTHER_SYMBOL ||
                        type == Character.MATH_SYMBOL || type == Character.CURRENCY_SYMBOL) return true;
                i += Character.charCount(cp);
            }
        }
        return false;
    }

    private static String language(CaptionSource source, RawTimeline timeline) {
        if (source == null || !source.getProblems().isEmpty()) return null;
        String language = source.getTranslationKind() == CaptionSource.TranslationKind.TRANSLATED ? source.getTranslationLanguage() :
                source.getTranslationKind() == CaptionSource.TranslationKind.DIRECT ? source.getSourceLanguage() : null;
        String header = timeline.getMetadata().get("Language");
        return language != null && header != null && !language.equalsIgnoreCase(header) ? null : language;
    }
    private long now() { long value = mClock.nowMs(); if (value < 0) throw new IllegalArgumentException("Invalid caption input clock"); return value; }
    private static long add(long value, long positive) { return value > Long.MAX_VALUE - positive ? Long.MAX_VALUE : value + positive; }
    private static void cancel(NativeCaptionLoader.Load load) { if (load != null) load.cancel(); }
}
