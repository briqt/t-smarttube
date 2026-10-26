package com.liskovsoft.smartyoutubetv2.common.subtitle.translation;

import com.liskovsoft.smartyoutubetv2.common.subtitle.session.CaptionSession;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.MappedTranscript;
import com.liskovsoft.smartyoutubetv2.common.subtitle.timeline.SemanticGroupPlanner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One document/configuration's bounded translation window. No native track, display, or settings policy. */
public final class TranslationCoordinator {
    public interface Clock { long nowMs(); }
    public interface Work {
        ChatTranslationClient.Result execute();
        void cancel();
    }
    public interface WorkFactory { Work create(TranslationBatch batch, Profile profile); }
    /** Synchronous, short UI application only. Must not schedule work, block, or mutate session state. */
    public interface Sink { void apply(Frame frame); }

    /** Non-secret identity owned by a live account/credential state, never derived from Key bytes. */
    public static final class CredentialScope {
        private volatile boolean mActive = true;
        boolean active() { return mActive; }
        synchronized void revoke() { mActive = false; }
        synchronized boolean admit(Runnable action) {
            if (!mActive) return false;
            action.run();
            return true;
        }
    }
    public interface ScopedCredentials extends ChatTranslationClient.Credentials {
        CredentialScope getCaptionCacheScope();
    }

    /** A new instance still invalidates old work; completed text has a separate credential/semantic identity. */
    public static final class Profile {
        public final ChatCompletionAdapter.Config wire;
        public final ChatTranslationClient.Credentials credentials;
        public final long credentialRevision;
        public final String sourceLanguage;
        public final String targetLanguage;
        public final String context;
        public final TranslationBatch.Limits batchLimits;
        public final int maxCalls;
        final CredentialScope cacheScope;
        final byte[] semantics;

        public Profile(ChatCompletionAdapter.Config wire, ChatTranslationClient.Credentials credentials,
                       long credentialRevision, String sourceLanguage, String targetLanguage, String context,
                       TranslationBatch.Limits batchLimits, int maxCalls) {
            // Real connection credentials carry their State's scope. Unscoped callers lack that proof
            // and conservatively share only via explicit derivation of this Profile.
            this(wire, credentials, credentialRevision, sourceLanguage, targetLanguage, context, batchLimits, maxCalls,
                    credentials instanceof ScopedCredentials ? ((ScopedCredentials) credentials).getCaptionCacheScope() : new CredentialScope());
        }

        private Profile(ChatCompletionAdapter.Config wire, ChatTranslationClient.Credentials credentials,
                        long credentialRevision, String sourceLanguage, String targetLanguage, String context,
                        TranslationBatch.Limits batchLimits, int maxCalls, CredentialScope cacheScope) {
            if (wire == null || credentials == null || cacheScope == null || credentialRevision < 0 ||
                    sourceLanguage != null && !TranslationBatch.language(sourceLanguage) ||
                    !TranslationBatch.language(targetLanguage) || context == null || !TranslationBatch.validText(context) ||
                    batchLimits == null || context.codePointCount(0, context.length()) > batchLimits.contextCodePoints ||
                    maxCalls <= 0 || maxCalls > 8) throw invalid();
            this.wire = wire;
            this.credentials = credentials;
            this.credentialRevision = credentialRevision;
            this.sourceLanguage = sourceLanguage;
            this.targetLanguage = targetLanguage;
            this.context = context;
            this.batchLimits = batchLimits;
            this.maxCalls = maxCalls;
            this.cacheScope = cacheScope;
            semantics = SemanticTranslationCache.requestSemantics(this);
        }

        /** Derivation preserves the true credential owner, not the identity of this transient Profile. */
        public Profile withSourceLanguage(String language) {
            return new Profile(wire, credentials, credentialRevision, language, targetLanguage, context,
                    batchLimits, maxCalls, cacheScope);
        }
    }

    public static final class Limits {
        public final long lookAheadUs;
        public final int cacheEntries;
        public final int cacheCodePoints;
        public final long cacheTtlMs;
        public final int maxAttempts;
        public final long retryDelayMs;
        public final int maxJobs;

        /** Explicit local budgets, not product defaults, token estimates, or a persistent cache. */
        public Limits(long lookAheadUs, int cacheEntries, int cacheCodePoints, long cacheTtlMs,
                      int maxAttempts, long retryDelayMs, int maxJobs) {
            if (lookAheadUs < 0 || lookAheadUs > 600_000_000L || cacheEntries <= 0 || cacheEntries > 8192 ||
                    cacheCodePoints <= 0 || cacheCodePoints > 1024 * 1024 || cacheTtlMs <= 0 || cacheTtlMs > 86_400_000L ||
                    maxAttempts <= 0 || maxAttempts > 8 || retryDelayMs <= 0 || retryDelayMs > 3_600_000L ||
                    maxJobs <= 0 || maxJobs > 65536) throw invalid();
            this.lookAheadUs = lookAheadUs;
            this.cacheEntries = cacheEntries;
            this.cacheCodePoints = cacheCodePoints;
            this.cacheTtlMs = cacheTtlMs;
            this.maxAttempts = maxAttempts;
            this.retryDelayMs = retryDelayMs;
            this.maxJobs = maxJobs;
        }
    }

    public static final class Item {
        public final int groupIndex;
        public final String original;
        public final String translation;

        private Item(int groupIndex, String original, String translation) {
            this.groupIndex = groupIndex;
            this.original = original;
            this.translation = translation;
        }
    }

    /** Sensitive text and local ordinals. Must pass deliver's execution-time guard, never logged. */
    public static final class Frame {
        public final long positionUs;
        public final List<Item> items;
        private final TranslationCoordinator mOwner;
        private final CaptionSession.Snapshot mSnapshot;
        private final Profile mProfile;
        private final long mRevision;
        private final long mExpiresAtMs;
        private final long mSessionActivation;

        private Frame(TranslationCoordinator owner, long positionUs, List<Item> items, CaptionSession.Snapshot snapshot,
                      Profile profile, long revision, long expiresAtMs, long sessionActivation) {
            mOwner = owner;
            this.positionUs = positionUs;
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
            mSnapshot = snapshot;
            mProfile = profile;
            mRevision = revision;
            mExpiresAtMs = expiresAtMs;
            mSessionActivation = sessionActivation;
        }

        /** Identity only; callers still need deliver's configuration, lifetime and position guard. */
        public boolean isFor(CaptionSession.Snapshot snapshot) { return mSnapshot == snapshot; }
    }

    private static final int MAX_GROUPS = 8192;

    private static final class Ticket {
        final CaptionSession.Snapshot snapshot;
        final Profile profile;
        final List<Integer> window;
        final long activation;
        TranslationBatch batch;
        final Map<Integer, SemanticTranslationCache.Key> keys = new HashMap<>();
        final Map<Integer, String> contexts = new HashMap<>();
        final List<Integer> selected = new ArrayList<>();
        Work work;
        boolean cancelled;
        Ticket(CaptionSession.Snapshot snapshot, Profile profile, List<Integer> window, long activation) {
            this.snapshot = snapshot;
            this.profile = profile;
            this.window = Collections.unmodifiableList(new ArrayList<>(window));
            this.activation = activation;
        }
    }

    private final CaptionSession mSession;
    private final Clock mClock;
    private final Executor mExecutor;
    private final WorkFactory mFactory;
    private final Limits mLimits;
    private final ThreadPoolExecutor mOwnedExecutor;
    private CaptionBoundaryRefiner mBoundaryRefiner;
    private final SemanticTranslationCache mCompleted;
    // Current-document scalar-key projection only; bounded by MAX_GROUPS, never owns completed Strings.
    private final Map<Integer, SemanticTranslationCache.Key> mKeys = new HashMap<>();
    private final Map<Integer, Integer> mFailures = new HashMap<>();
    private List<Integer> mWindow = Collections.emptyList();
    private List<Integer> mResolvedWindow = Collections.emptyList();
    private List<SemanticTranslationCache.Key> mWindowKeys = Collections.emptyList();
    private SemanticTranslationCache.Window mCacheWindow = SemanticTranslationCache.window(Collections.emptyList());
    private CaptionSession.CacheScope mBudgetSource;
    private CaptionSession.Snapshot mSnapshot;
    private Profile mProfile;
    private Profile mProjectionProfile;
    private Ticket mRunning;
    private long mPositionUs;
    private long mRevision;
    private long mNotBeforeMs;
    private long mRateLimitedUntilMs;
    private int mJobs;
    private boolean mRequesting;
    private boolean mVisible;
    private boolean mBlocked;
    private boolean mClosed;

    /** No request is made until the caller supplies a profile and explicitly enables requesting. */
    public static TranslationCoordinator create(CaptionSession session, Clock clock, Limits limits) {
        ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), runnable -> new Thread(runnable, "caption-translation"),
                new ThreadPoolExecutor.AbortPolicy());
        ChatTranslationClient client = new ChatTranslationClient();
        TranslationCoordinator coordinator = new TranslationCoordinator(session, clock, worker, (batch, profile) -> {
            ChatTranslationClient.Job job = client.newJob(batch, profile.wire, profile.credentials, profile.maxCalls);
            return new Work() {
                public ChatTranslationClient.Result execute() { return job.execute(); }
                public void cancel() { job.cancel(); }
            };
        }, limits, worker);
        coordinator.mBoundaryRefiner = new CaptionBoundaryRefiner(client.transport(), clock);
        return coordinator;
    }

    void setBoundaryRefiner(CaptionBoundaryRefiner refiner) { mBoundaryRefiner = refiner; }

    TranslationCoordinator(CaptionSession session, Clock clock, Executor executor, WorkFactory factory, Limits limits) {
        this(session, clock, executor, factory, limits, null);
    }

    private TranslationCoordinator(CaptionSession session, Clock clock, Executor executor, WorkFactory factory,
                                   Limits limits, ThreadPoolExecutor ownedExecutor) {
        if (session == null || clock == null || executor == null || factory == null || limits == null) throw invalid();
        mSession = session;
        mClock = clock;
        mExecutor = executor;
        mFactory = factory;
        mLimits = limits;
        mCompleted = new SemanticTranslationCache(limits);
        mOwnedExecutor = ownedExecutor;
    }

    public void configure(Profile profile) {
        Work cancel;
        synchronized (this) {
            if (mClosed || mProfile == profile) return;
            cancel = detachLocked();
            mProfile = profile;
            resetResultsLocked();
            mJobs = 0;
            mRevision++;
        }
        cancel(cancel);
        pump();
    }

    /** Explicit account-owner boundary, even when configuration and credential State use a shared bucket. */
    public void resetAccountOwner() {
        Work cancel;
        synchronized (this) {
            if (mClosed) return;
            cancel = detachLocked();
            mRequesting = false;
            mProfile = null;
            mSnapshot = null;
            resetResultsLocked();
            mCompleted.clear();
            mBudgetSource = null;
            mJobs = 0;
            mRevision++;
        }
        cancel(cancel);
    }

    /** Caller resolves AI-off/original-only/cost policy; this method does not choose those defaults. */
    public void setRequesting(boolean requesting) {
        Work cancel = null;
        synchronized (this) {
            if (mClosed || mRequesting == requesting) return;
            mRequesting = requesting;
            mRevision++;
            if (!requesting) cancel = detachLocked();
        }
        cancel(cancel);
        pump();
    }

    /** Presentation gate only; hidden/PIP request policy remains the caller's explicit decision. */
    public synchronized void setVisible(boolean visible) {
        if (!mClosed && mVisible != visible) { mVisible = visible; mRevision++; }
    }

    /** Mode/order/layout changes invalidate queued UI work without refetching completed translations. */
    public synchronized void invalidatePresentation() {
        if (!mClosed) mRevision++;
    }

    /** Call on player ticks; discontinuity cancels old-window work, not same-document completed text. */
    public void tick(long positionUs, boolean discontinuity) {
        Work cancel;
        synchronized (this) {
            if (mClosed) return;
            cancel = syncSourceLocked();
            if (discontinuity) {
                Work current = detachLocked();
                if (current != null) cancel = current;
            }
            if (discontinuity || positionUs != mPositionUs) mRevision++;
            mPositionUs = positionUs;
        }
        cancel(cancel);
        pump();
    }

    /** A single preparation/provider job on the existing worker; queries never freeze context. */
    private void pump() {
        Work cancel;
        Ticket ticket = null;
        synchronized (this) {
            cancel = syncSourceLocked();
            long now = now();
            expireLocked(now);
            if (mProfile != null && mSnapshot != null && mSnapshot.groups.getGroups().size() <= MAX_GROUPS) updateWindowLocked();
            if (!mClosed && mRequesting && mProfile != null && mProfile.cacheScope.active() && mSnapshot != null &&
                    mSession.isCurrent(mSnapshot) && mRunning == null && mSnapshot.groups.getGroups().size() <= MAX_GROUPS &&
                    needsPreparationLocked(now)) {
                ticket = new Ticket(mSnapshot, mProfile, mWindow, mSession.getActivationRevision());
                mRunning = ticket;
            }
        }
        cancel(cancel);
        if (ticket != null) {
            final Ticket admitted = ticket;
            try { mExecutor.execute(() -> run(admitted)); }
            catch (RuntimeException ignored) { preparationFailed(admitted); }
        }
    }

    private boolean canRequestLocked(long now) {
        return !mBlocked && now >= mNotBeforeMs && now >= mRateLimitedUntilMs && mJobs < mLimits.maxJobs;
    }

    private boolean needsPreparationLocked(long now) {
        if (mWindow.isEmpty()) return false;
        boolean allPaid = true;
        for (Integer index : mWindow) {
            if (mCompleted.get(mKeys.get(index)) == null) { allPaid = false; break; }
        }
        if (allPaid) return false; // A changed priority window alone cannot delay already reachable text.
        if (!canRequestLocked(now)) {
            // Do not queue blind preparation while authentication/rate/call gates block requests.
            // A potentially reusable completed domain may still get one cache-only reassociation.
            return !mResolvedWindow.equals(mWindow) &&
                    mCompleted.hasCompletedDomain(mSnapshot.track.cacheScope, mProfile.cacheScope, mProfile.semantics);
        }
        if (!mResolvedWindow.equals(mWindow)) return true;
        for (Integer index : mWindow) {
            if (failuresLocked(index) >= mLimits.maxAttempts) continue;
            SemanticTranslationCache.Key key = mKeys.get(index);
            if (!mCompleted.prepared(key) || mCompleted.get(key) == null && !mCompleted.suppressed(key, mCacheWindow)) return true;
        }
        return false;
    }

    private static final class Prepared {
        final int index;
        final CaptionNeighborContext.Frozen frozen;
        final SemanticTranslationCache.Key key;
        final long reuseUntilMs;
        Prepared(int index, CaptionNeighborContext.Frozen frozen, SemanticTranslationCache.Key key, long reuseUntilMs) {
            this.index = index; this.frozen = frozen; this.key = key;
            this.reuseUntilMs = reuseUntilMs;
        }
    }

    private void run(Ticket ticket) {
        try {
            final Map<SemanticTranslationCache.Key, SemanticTranslationCache.Record> retained;
            final Map<Integer, SemanticTranslationCache.Key> projected;
            final long started;
            synchronized (this) {
                if (!validLocked(ticket)) { finishPreparation(ticket); return; }
                retained = mCompleted.variants();
                projected = mProjectionProfile == ticket.profile ? new HashMap<>(mKeys) : Collections.emptyMap();
                started = now();
            }
            Map<SemanticTranslationCache.Key, List<SemanticTranslationCache.Record>> byBase = new HashMap<>();
            for (SemanticTranslationCache.Record record : retained.values()) {
                if (record.frozen == null || !record.frozen.associationCertain) continue;
                List<SemanticTranslationCache.Record> variants = byBase.get(record.base);
                if (variants == null) { variants = new ArrayList<>(); byBase.put(record.base, variants); }
                variants.add(record);
            }
            // Complete document scanning, neighbor text construction and every new hash are worker-side,
            // outside coordinator/session/source guards. Only fixed bounded data is later retained.
            CaptionNeighborContext.Document document = new CaptionNeighborContext.Document(ticket.snapshot, ticket.profile);
            List<SemanticTranslationCache.Key> bases = new ArrayList<>();
            for (Integer index : ticket.window) bases.add(document.groups[index].base);
            SemanticTranslationCache.Window window = SemanticTranslationCache.window(bases);
            List<Prepared> prepared = new ArrayList<>();
            int newContexts = 0;
            int globalPoints = ticket.profile.context.codePointCount(0, ticket.profile.context.length());
            int localBudget = ticket.profile.batchLimits.contextCodePoints - globalPoints;
            for (Integer index : ticket.window) {
                CaptionNeighborContext.Group group = document.groups[index];
                SemanticTranslationCache.Record old = retained.get(projected.get(index));
                if (old != null && (!old.base.equals(group.base) || started >= old.expiresAtMs ||
                        old.frozen == null && !old.pressureWindow.equals(window))) old = null;
                if (old == null && document.uniqueTarget(index)) {
                    List<SemanticTranslationCache.Record> variants = byBase.get(group.base);
                    if (variants != null) {
                        for (SemanticTranslationCache.Record variant : variants) {
                            if (variant.frozen != null && started < variant.expiresAtMs && document.matches(variant.frozen, index)) {
                                old = variant;
                                break;
                            }
                        }
                    }
                }
                CaptionNeighborContext.Frozen frozen = null;
                SemanticTranslationCache.Key key = null;
                long reuseUntil = Long.MAX_VALUE;
                if (old != null) {
                    // Current immutable-document ordinal projections are exact, even when slots collide.
                    // Across a new Raw only the unique-association checks above may skip preparation.
                    frozen = old.frozen;
                    key = old.key;
                    reuseUntil = old.expiresAtMs;
                } else if (newContexts < ticket.profile.batchLimits.items) {
                    frozen = document.prepare(index, localBudget);
                    key = SemanticTranslationCache.contextKey(group.base, frozen);
                    newContexts++;
                }
                prepared.add(new Prepared(index, frozen, key, reuseUntil));
            }
            List<SemanticTranslationCache.Key> priorities = new ArrayList<>();
            for (Prepared item : prepared) priorities.add(item.key == null ? document.groups[item.index].base : item.key);
            if (!guardTicket(ticket, () -> selectPreparedLocked(ticket, document, prepared, priorities, window, globalPoints))) return;
            if (ticket.selected.isEmpty()) { finishPreparation(ticket); return; }
            TranslationBatch batch = new TranslationBatch(ticket.snapshot.groups, ticket.selected, ticket.profile.sourceLanguage,
                    ticket.profile.targetLanguage, ticket.profile.context, ticket.profile.batchLimits, ticket.contexts);
            batch = fitWire(ticket, batch);
            if (batch == null) { finishPreparation(ticket); return; }
            final TranslationBatch admitted = batch;
            if (!guardTicket(ticket, () -> {
                if (canRequestLocked(now())) { ticket.batch = admitted; mJobs++; }
            }) || ticket.batch == null) { finishPreparation(ticket); return; }
        } catch (RuntimeException ignored) { preparationFailed(ticket); return; }
        synchronized (this) { if (!executionReadyLocked(ticket)) return; }
        if (refineBoundaries(ticket)) return;
        synchronized (this) { if (!executionReadyLocked(ticket)) return; }
        Work work;
        try { work = mFactory.create(ticket.batch, ticket.profile); }
        catch (RuntimeException ignored) { finish(ticket, null); return; }
        boolean active;
        synchronized (this) {
            active = executionReadyLocked(ticket);
            if (active) ticket.work = work;
        }
        if (!active) { cancel(work); return; }
        ChatTranslationClient.Result result = null;
        try { if (work != null) result = work.execute(); }
        catch (RuntimeException ignored) { /* Do not expose provider errors or request data. */ }
        finish(ticket, result);
    }

    /** Optional boundary and translation exchanges run serially on this same owned worker. */
    private boolean refineBoundaries(Ticket ticket) {
        if (mBoundaryRefiner == null) return false;
        CaptionBoundaryRefiner.Pending pending;
        try {
            pending = mBoundaryRefiner.prepare(mSession, ticket.snapshot, ticket.profile, ticket.batch.getItems(),
                    action -> guardTicket(ticket, action));
        } catch (RuntimeException unavailable) {
            return false;
        }
        if (pending == null) return false;
        Work cancellation = new Work() {
            public ChatTranslationClient.Result execute() { return null; }
            public void cancel() { pending.cancel(); }
        };
        boolean active;
        synchronized (this) {
            active = executionReadyLocked(ticket);
            if (active) ticket.work = cancellation;
        }
        if (!active) { pending.cancel(); return true; }
        CaptionBoundaryRefiner.Outcome outcome = null;
        try { outcome = pending.execute(); }
        catch (RuntimeException unavailable) { /* Deterministic groups remain usable. */ }
        if (outcome != null && outcome.changed) { finishPreparation(ticket); return true; }
        CaptionBoundaryClient.Result response = outcome == null ? null : outcome.response;
        if (response != null && (response.status == CaptionHttpTransport.Status.AUTH_ERROR || response.httpCode == 429)) {
            ChatTranslationClient.Status status = response.httpCode == 429 ? ChatTranslationClient.Status.RATE_LIMITED :
                    ChatTranslationClient.Status.AUTH_ERROR;
            finish(ticket, ChatTranslationClient.failure(ticket.batch, status, response.calls, response.httpCode, response.retryAfterMs));
            return true;
        }
        synchronized (this) {
            ticket.work = null;
            return !executionReadyLocked(ticket);
        }
    }

    /** This is the same outer-source -> credential -> coordinator -> effective-session admission order. */
    private boolean guardTicket(Ticket ticket, Runnable action) {
        boolean[] reached = {false};
        mSession.guard(ticket.snapshot, () -> ticket.profile.cacheScope.admit(() -> {
            synchronized (TranslationCoordinator.this) {
                synchronized (mSession) {
                    if (!validLocked(ticket)) return;
                    action.run();
                    reached[0] = true;
                }
            }
        }));
        if (!reached[0]) finishPreparation(ticket);
        return reached[0];
    }

    private void selectPreparedLocked(Ticket ticket, CaptionNeighborContext.Document document, List<Prepared> prepared,
                                      List<SemanticTranslationCache.Key> priorities, SemanticTranslationCache.Window window, int globalPoints) {
        long now = now();
        expireLocked(now);
        boolean request = canRequestLocked(now);
        int sourcePoints = 0;
        int contextPoints = globalPoints;
        for (Prepared item : prepared) {
            boolean fresh = now < item.reuseUntilMs;
            SemanticTranslationCache.Record record = mCompleted.resolve(document.groups[item.index].base,
                    fresh ? item.frozen : null, item.key, now, priorities, window, request && fresh && item.frozen != null);
            if (record == null) { mKeys.remove(item.index); continue; }
            mKeys.put(item.index, record.key);
            if (!request || record.frozen == null || mCompleted.get(record.key) != null ||
                    mCompleted.suppressed(record.key, window) || failuresLocked(item.index) >= mLimits.maxAttempts) continue;
            int count = document.groups[item.index].codePoints;
            if (count > ticket.profile.batchLimits.sourceCodePoints) { mCompleted.suppress(record.key, null, now); continue; }
            int local = record.frozen.codePoints;
            if (ticket.selected.size() >= ticket.profile.batchLimits.items ||
                    count > ticket.profile.batchLimits.sourceCodePoints - sourcePoints ||
                    local > ticket.profile.batchLimits.contextCodePoints - contextPoints) continue;
            ticket.selected.add(item.index);
            ticket.contexts.put(item.index, record.frozen.text);
            ticket.keys.put(item.index, record.key);
            sourcePoints += count;
            contextPoints += local;
        }
        mCacheWindow = window;
        mProjectionProfile = ticket.profile;
        mResolvedWindow = ticket.window;
        projectWindowKeysLocked();
        mRevision++;
    }

    /** Shrink target count only. Never truncate or recompute an already frozen local context. */
    private TranslationBatch fitWire(Ticket ticket, TranslationBatch batch) {
        while (true) {
            try { ChatCompletionAdapter.encode(batch, ticket.profile.wire); return batch; }
            catch (IllegalArgumentException overBudget) {
                List<TranslationBatch.Item> items = batch.getItems();
                if (items.size() > 1) batch = batch.subset(items.subList(0, items.size() - 1));
                else {
                    guardTicket(ticket, () -> mCompleted.suppress(ticket.keys.get(ticket.selected.get(0)), null, now()));
                    return null;
                }
            }
        }
    }

    private synchronized void finishPreparation(Ticket ticket) {
        if (mRunning == ticket) mRunning = null;
    }

    private synchronized void preparationFailed(Ticket ticket) {
        if (mRunning != ticket) return;
        for (Integer index : ticket.window) mFailures.put(index, failuresLocked(index) + 1);
        mNotBeforeMs = add(now(), mLimits.retryDelayMs);
        mRunning = null;
    }

    private void finish(Ticket ticket, ChatTranslationClient.Result result) {
        synchronized (this) {
            // Quota feedback survives an obsolete content/window ticket, never its translations.
            if (!mClosed && result != null && result.batch == ticket.batch && result.httpCode == 429) {
                long delay = result.retryAfterMs == null ? mLimits.retryDelayMs : Math.max(mLimits.retryDelayMs, result.retryAfterMs);
                mRateLimitedUntilMs = Math.max(mRateLimitedUntilMs, add(now(), delay));
            }
        }
        boolean[] reached = {false};
        try {
            // Outer source/input guards, then credential state, coordinator, effective session.
            // All keys were prepared at admission. No parsing or transport takes place here.
            mSession.guard(ticket.snapshot, () -> ticket.profile.cacheScope.admit(() -> {
                synchronized (TranslationCoordinator.this) {
                    synchronized (mSession) {
                        reached[0] = true;
                        finishCurrentLocked(ticket, result);
                    }
                }
            }));
        } finally {
            if (!reached[0]) {
                synchronized (this) {
                    if (mRunning == ticket) { ticket.cancelled = true; mRunning = null; }
                }
            }
        }
    }

    /** Caller holds the complete admission chain through the validity check and String write. */
    private void finishCurrentLocked(Ticket ticket, ChatTranslationClient.Result result) {
        if (!validLocked(ticket)) {
            if (mRunning == ticket) { ticket.cancelled = true; mRunning = null; }
            return;
        }
        mRunning = null;
        long now = now();
        expireLocked(now);
        boolean matched = result != null && result.batch == ticket.batch && result.status != ChatTranslationClient.Status.CANCELLED;
        boolean terminal = matched && (result.completionState == ChatCompletionAdapter.State.REFUSED ||
                result.completionState == ChatCompletionAdapter.State.FILTERED ||
                result.completionState == ChatCompletionAdapter.State.FAILED ||
                result.completionState == ChatCompletionAdapter.State.UNKNOWN);
        for (TranslationBatch.Item item : ticket.batch.getItems()) {
            String text = matched ? result.translations.get(item.id) : null;
            if (text != null) {
                mFailures.remove(item.groupIndex);
                mCompleted.store(ticket.keys.get(item.groupIndex), text, now, mWindowKeys, mCacheWindow);
            } else {
                int failures = failuresLocked(item.groupIndex);
                mFailures.put(item.groupIndex, terminal ? mLimits.maxAttempts : failures + 1);
            }
        }
        if (!matched || result.status != ChatTranslationClient.Status.COMPLETE) {
            long delay = mLimits.retryDelayMs;
            if (matched && result.retryAfterMs != null) delay = Math.max(delay, result.retryAfterMs);
            mNotBeforeMs = add(now, delay);
            if (matched && result.status == ChatTranslationClient.Status.RATE_LIMITED) {
                mRateLimitedUntilMs = Math.max(mRateLimitedUntilMs, mNotBeforeMs);
            }
        }
        if (matched && (result.status == ChatTranslationClient.Status.AUTH_ERROR ||
                result.status == ChatTranslationClient.Status.HTTP_ERROR && result.httpCode != 408 && result.httpCode < 500 ||
                result.status == ChatTranslationClient.Status.INVALID && result.httpCode == 0)) mBlocked = true;
        mRevision++;
    }

    private boolean executionReadyLocked(Ticket ticket) {
        if (!validLocked(ticket)) {
            if (mRunning == ticket) { ticket.cancelled = true; mRunning = null; }
            return false;
        }
        long now = now();
        if (now < mNotBeforeMs || now < mRateLimitedUntilMs) {
            ticket.cancelled = true;
            mRunning = null;
            return false;
        }
        return true;
    }

    private boolean validLocked(Ticket ticket) {
        return !mClosed && mRequesting && !ticket.cancelled && mRunning == ticket &&
                mSnapshot == ticket.snapshot && mProfile == ticket.profile && ticket.profile.cacheScope.active() &&
                ticket.activation == mSession.getActivationRevision() && mSession.isCurrent(ticket.snapshot);
    }

    private Work syncSourceLocked() {
        CaptionSession.Snapshot next = mClosed ? null : mSession.getSnapshot();
        if (mSnapshot == next) {
            return mRunning != null && (mRunning.activation != mSession.getActivationRevision() ||
                    !mSession.isCurrent(mRunning.snapshot)) ? detachLocked() : null;
        }
        boolean keepProjection = canRetainProjection(next);
        Work cancel = detachLocked();
        mSnapshot = next;
        if (next != null && next.track.cacheScope != mBudgetSource) {
            mBudgetSource = next.track.cacheScope;
            mJobs = 0;
        }
        boolean blocked = mBlocked;
        long notBefore = mNotBeforeMs;
        resetResultsLocked(keepProjection);
        mBlocked = blocked;
        mNotBeforeMs = notBefore;
        mRevision++;
        return cancel;
    }

    /** Rebind scalar keys only for the exact immutable document and request owner/configuration. */
    private boolean canRetainProjection(CaptionSession.Snapshot next) {
        return mSnapshot != null && next != null && mProfile != null && mProjectionProfile == mProfile &&
                mProfile.cacheScope.active() && mSnapshot.track.cacheScope == next.track.cacheScope &&
                mSnapshot.timeline == next.timeline && mSnapshot.transcript == next.transcript &&
                mSnapshot.groups == next.groups && mSnapshot.clock.offsetUs == next.clock.offsetUs &&
                mSnapshot.clock.durationUs == next.clock.durationUs &&
                (mSnapshot.clock.mime == null ? next.clock.mime == null : mSnapshot.clock.mime.equals(next.clock.mime));
    }

    private int failuresLocked(int index) {
        Integer count = mFailures.get(index);
        return count == null ? 0 : count;
    }

    private void resetResultsLocked() { resetResultsLocked(false); }

    private void resetResultsLocked(boolean keepProjection) {
        if (!keepProjection) {
            mKeys.clear();
            mFailures.clear();
            mProjectionProfile = null;
            mResolvedWindow = Collections.emptyList();
        }
        mWindow = Collections.emptyList();
        mWindowKeys = Collections.emptyList();
        mNotBeforeMs = 0;
        mBlocked = false;
    }

    private Work detachLocked() {
        if (mRunning == null) return null;
        mRunning.cancelled = true;
        Work work = mRunning.work;
        mRunning = null;
        if (mOwnedExecutor != null) mOwnedExecutor.getQueue().clear();
        return work;
    }

    private void updateWindowLocked() {
        List<Integer> priority = new ArrayList<>(mSnapshot.groups.getGroupsAt(mPositionUs));
        Set<Integer> active = new HashSet<>(priority);
        List<Integer> future = new ArrayList<>();
        Map<Integer, Long> starts = new HashMap<>();
        long end = add(mPositionUs, mLimits.lookAheadUs);
        for (int i = 0; i < mSnapshot.groups.getGroups().size(); i++) {
            if (active.contains(i)) continue;
            long start = nextStart(i);
            if (start > mPositionUs && start <= end && start != Long.MAX_VALUE) { future.add(i); starts.put(i, start); }
        }
        Collections.sort(future, (first, second) -> {
            long a = starts.get(first), b = starts.get(second);
            return a < b ? -1 : a > b ? 1 : first < second ? -1 : first.equals(second) ? 0 : 1;
        });
        priority.addAll(future);
        List<Integer> window = new ArrayList<>();
        for (Integer index : priority) {
            if (!TranslationBatch.hasContent(mSnapshot.groups.getText(index))) continue;
            window.add(index);
            if (window.size() == mLimits.cacheEntries) break;
        }
        mWindow = window;
        projectWindowKeysLocked();
    }

    private void projectWindowKeysLocked() {
        List<SemanticTranslationCache.Key> keys = new ArrayList<>();
        for (Integer index : mWindow) {
            SemanticTranslationCache.Key key = mKeys.get(index);
            if (key != null && mCompleted.prepared(key)) keys.add(key);
        }
        mWindowKeys = keys;
    }

    private long nextStart(int index) {
        long start = Long.MAX_VALUE;
        for (MappedTranscript.Slice slice : mSnapshot.groups.getGroups().get(index).slices) {
            for (MappedTranscript.Origin origin : mSnapshot.transcript.getAtoms().get(slice.atomIndex).origins) {
                if (origin.activeStartUs >= mPositionUs) start = Math.min(start, origin.activeStartUs);
            }
        }
        return start;
    }

    private SemanticTranslationCache.Entry cachedLocked(int index) {
        // A Frame can read only an already admitted key; it must never freeze empty/local context.
        SemanticTranslationCache.Key key = mKeys.get(index);
        return key == null || mSnapshot.groups.getGroups().size() > MAX_GROUPS ? null : mCompleted.get(key);
    }

    private void expireLocked(long now) {
        if (mCompleted.expire(now)) mRevision++;
    }

    public Frame prepareFrame() {
        Work cancel;
        Frame frame = null;
        synchronized (this) {
            cancel = syncSourceLocked();
            long now = now();
            expireLocked(now);
            if (!mClosed && mRequesting && mVisible && mSnapshot != null && mProfile != null &&
                    mProfile.cacheScope.active() && mSession.isCurrent(mSnapshot)) {
                List<Item> items = new ArrayList<>();
                long expires = Long.MAX_VALUE;
                for (Integer index : mSnapshot.groups.getGroupsAt(mPositionUs)) {
                    SemanticTranslationCache.Entry value = cachedLocked(index);
                    items.add(new Item(index, mSnapshot.groups.getText(index), value == null ? null : value.text));
                    if (value != null) expires = Math.min(expires, value.expiresAtMs);
                }
                frame = new Frame(this, mPositionUs, items, mSnapshot, mProfile, mRevision, expires, mSession.getActivationRevision());
            }
        }
        cancel(cancel);
        return frame;
    }

    /** Call inside the actual UI runnable. Both guards remain locked through synchronous application. */
    public boolean deliver(Frame frame, Sink sink) {
        if (sink == null) throw invalid();
        synchronized (this) {
            synchronized (mSession) {
                if (frame == null || frame.mOwner != this || mClosed || !mRequesting || !mVisible || frame.mRevision != mRevision ||
                        frame.mProfile != mProfile || frame.mSnapshot != mSnapshot || !mProfile.cacheScope.active() ||
                        !mSession.isCurrent(frame.mSnapshot) || frame.mSessionActivation != mSession.getActivationRevision() ||
                        now() >= frame.mExpiresAtMs) return false;
                sink.apply(frame);
                return true;
            }
        }
    }

    public void close() {
        Work cancel;
        synchronized (this) {
            if (mClosed) return;
            mClosed = true;
            cancel = detachLocked();
            mSnapshot = null;
            mProfile = null;
            mRequesting = false;
            mVisible = false;
            resetResultsLocked();
            mCompleted.clear();
            mBudgetSource = null;
            mJobs = 0;
            mRevision++;
        }
        cancel(cancel);
        if (mOwnedExecutor != null) mOwnedExecutor.shutdownNow();
    }

    private long now() {
        long value = mClock.nowMs();
        if (value < 0) throw invalid();
        return value;
    }

    private static long add(long first, long positive) {
        return first > Long.MAX_VALUE - positive ? Long.MAX_VALUE : first + positive;
    }

    private static void cancel(Work work) {
        if (work != null) try { work.cancel(); } catch (RuntimeException ignored) { }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid caption translation session");
    }
}
