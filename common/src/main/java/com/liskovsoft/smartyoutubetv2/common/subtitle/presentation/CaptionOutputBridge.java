package com.liskovsoft.smartyoutubetv2.common.subtitle.presentation;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.text.Cue;
import com.liskovsoft.smartyoutubetv2.common.subtitle.source.CaptionSource;
import com.google.android.exoplayer2.text.TextOutput;
import com.liskovsoft.smartyoutubetv2.common.subtitle.acquisition.CaptionInputSession;
import com.liskovsoft.smartyoutubetv2.common.subtitle.observation.CaptionCueDispatcher;
import com.liskovsoft.smartyoutubetv2.common.subtitle.observation.CaptionObservation;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.TranslationCoordinator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** UI-owned native output adapter. No track selection, preferences I/O, Key storage, or implicit API setup. */
public final class CaptionOutputBridge {
    public interface Position { long getPositionMs(); }
    interface Ui {
        boolean currentThread();
        void later(Runnable task, long delayMs);
        void remove(Runnable task);
    }

    private final CaptionObservation mObservation;
    private final CaptionInputSession mInputs;
    private final TranslationCoordinator mCoordinator;
    private final TextOutput mOutput;
    private final Position mPosition;
    private final Ui mUi;
    private final Runnable mPulse = this::pulse;
    private List<Cue> mRaw = Collections.emptyList();
    private List<Cue> mNative = Collections.emptyList();
    private CaptionObservation.OutputStamp mStamp;
    private Object mWindow;
    private List<Cue> mSeekRaw;
    private List<Cue> mSeekNative;
    private CaptionObservation.OutputStamp mSeekStamp;
    private Object mAccountOwner;
    private TranslationCoordinator.Profile mTemplate;
    private TranslationCoordinator.Profile mDerived;
    private String mDerivedLanguage;
    private NativeCueComposer.Mode mMode = NativeCueComposer.Mode.BILINGUAL;
    private NativeCueComposer.Order mOrder = NativeCueComposer.Order.ORIGINAL_FIRST;
    private long mConfigurationRevision;
    private boolean mEnabled;
    private boolean mVisible = true;
    private boolean mClosed;

    public static CaptionOutputBridge create(CaptionObservation observation, TextOutput output, Position position) {
        Handler handler = new Handler(Looper.getMainLooper());
        Ui ui = new Ui() {
            public boolean currentThread() { return Looper.myLooper() == handler.getLooper(); }
            public void later(Runnable task, long delayMs) { handler.postDelayed(task, delayMs); }
            public void remove(Runnable task) { handler.removeCallbacks(task); }
        };
        // Development budgets until real UI/provider performance is accepted; no persisted product defaults.
        TranslationCoordinator coordinator = TranslationCoordinator.create(observation.getInputSession().getSession(),
                SystemClock::elapsedRealtime, new TranslationCoordinator.Limits(30_000_000, 256, 65536, 3_600_000, 2, 1000, 4096));
        return new CaptionOutputBridge(observation, coordinator, output, position, ui);
    }

    CaptionOutputBridge(CaptionObservation observation, TranslationCoordinator coordinator, TextOutput output,
                        Position position, Ui ui) {
        if (observation == null || coordinator == null || output == null || position == null || ui == null) {
            throw new IllegalArgumentException("Invalid caption output bridge");
        }
        mObservation = observation;
        mInputs = observation.getInputSession();
        mCoordinator = coordinator;
        mOutput = output;
        mPosition = position;
        mUi = ui;
    }

    public CaptionSource sourceFor(Format format) {
        checkThread();
        return mClosed ? null : mObservation.sourceFor(format);
    }

    /** Bind the actual settings/account owner, not a Profile or the native shared preference bucket. */
    public void bindAccountOwner(Object owner) {
        checkThread();
        if (mClosed || mAccountOwner == owner) return;
        if (owner == null) throw new IllegalArgumentException("Invalid caption account owner");
        mAccountOwner = owner;
        mConfigurationRevision++;
        mEnabled = false;
        mTemplate = null;
        mDerived = null;
        mDerivedLanguage = null;
        mCoordinator.resetAccountOwner();
        refresh();
    }

    /** Call before asynchronously resolving current global/channel settings, and apply only that handle. */
    public long beginConfiguration() {
        checkThread();
        return ++mConfigurationRevision;
    }

    /** profile is an explicit configured connection/target/context template, never inferred from subtitles. */
    public boolean configure(long handle, TranslationCoordinator.Profile profile, boolean enabled,
                             NativeCueComposer.Mode mode, NativeCueComposer.Order order) {
        checkThread();
        if (mClosed || handle != mConfigurationRevision) return false;
        if (mode == null || order == null) throw new IllegalArgumentException("Invalid caption display mode");
        if (mTemplate != profile) {
            mTemplate = profile;
            mDerived = null;
            mDerivedLanguage = null;
            mCoordinator.configure(null);
        }
        mEnabled = enabled;
        mMode = mode;
        mOrder = order;
        mCoordinator.invalidatePresentation();
        refresh();
        return true;
    }

    /** Check before the stateful native formatter too, so old messages cannot poison its text buffer. */
    public boolean acceptsNativeCues(List<Cue> raw) {
        checkThread();
        CaptionObservation.OutputStamp stamp = CaptionCueDispatcher.stampOf(raw);
        return !mClosed && (stamp == null || mObservation.isOutputValid(stamp));
    }

    /** raw is the stamped renderer list; normalized is produced once by the unchanged native formatter. */
    public void onNativeCues(List<Cue> raw, List<Cue> normalized) {
        checkThread();
        if (!acceptsNativeCues(raw)) return;
        clearSeekOutput();
        mStamp = CaptionCueDispatcher.stampOf(raw);
        mRaw = raw == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(raw));
        mNative = normalized == null ? Collections.emptyList() : normalized;
        // A native cue gap clears presentation, not the admitted same-track input or its prefetch.
        if (!mRaw.isEmpty()) mWindow = new Object();
        refresh();
    }

    public void setVisible(boolean visible) {
        checkThread();
        if (mClosed) return;
        mVisible = visible;
        mCoordinator.setVisible(visible);
        refresh();
    }

    /** Cost policy is explicit, independent of view visibility; no hidden/PIP preference is invented here. */
    public void setEnabled(boolean enabled) {
        checkThread();
        if (mClosed) return;
        mEnabled = enabled;
        refresh();
    }

    public void setDisplay(NativeCueComposer.Mode mode, NativeCueComposer.Order order) {
        checkThread();
        if (mClosed) return;
        if (mode == null || order == null) throw new IllegalArgumentException("Invalid caption display mode");
        mMode = mode;
        mOrder = order;
        mCoordinator.invalidatePresentation();
        refresh();
    }

    public void invalidateStyle() {
        checkThread();
        if (mClosed) return;
        mCoordinator.invalidatePresentation();
        refresh();
    }

    public void seek() {
        checkThread();
        if (mClosed) return;
        if (mSeekStamp == null && mObservation.isOutputCurrent(mStamp)) {
            mSeekRaw = mRaw;
            mSeekNative = mNative;
            mSeekStamp = mStamp;
        }
        mObservation.invalidateOutput(true);
        mWindow = null;
        mStamp = null;
        mRaw = Collections.emptyList();
        mNative = Collections.emptyList();
        mCoordinator.setRequesting(false);
        mCoordinator.tick(positionUs(), true);
        mOutput.onCues(mNative);
        arm();
    }

    public void seekProcessed() {
        checkThread();
        if (mClosed) return;
        CaptionObservation.OutputStamp resumed = mObservation.completeOutputSeek(mSeekStamp);
        if (resumed != null) {
            mStamp = resumed;
            mRaw = mSeekRaw;
            mNative = mSeekNative;
            mWindow = mRaw.isEmpty() ? null : new Object();
        }
        clearSeekOutput();
        refresh();
    }

    private void clearSeekOutput() {
        mSeekRaw = null;
        mSeekNative = null;
        mSeekStamp = null;
    }

    /** Prevent previous-channel settings and queued configuration callbacks from being reused on a new source. */
    public void resetSource() {
        checkThread();
        if (mClosed) return;
        mObservation.invalidateOutput(false);
        clearSeekOutput();
        mConfigurationRevision++;
        mEnabled = false;
        mTemplate = null;
        mDerived = null;
        mDerivedLanguage = null;
        mWindow = null;
        mStamp = null;
        mRaw = Collections.emptyList();
        mNative = Collections.emptyList();
        mCoordinator.setRequesting(false);
        mCoordinator.configure(null);
        mObservation.setEnhancementEnabled(false);
        mUi.remove(mPulse);
        mOutput.onCues(mNative);
    }

    private void pulse() {
        if (!mClosed) refresh();
    }

    private void refresh() {
        if (mStamp != null && !mObservation.isOutputValid(mStamp)) {
            mStamp = null;
            mWindow = null;
            mRaw = Collections.emptyList();
            mNative = Collections.emptyList();
        }
        boolean active = mEnabled && mTemplate != null && mMode != NativeCueComposer.Mode.ORIGINAL;
        if (!active) mCoordinator.setRequesting(false);
        mObservation.setEnhancementEnabled(active);
        mCoordinator.setVisible(mVisible);
        if (!active || !mObservation.isOutputCurrent(mStamp)) {
            mCoordinator.setRequesting(false);
            mOutput.onCues(mNative);
            arm();
            return;
        }
        mObservation.ensureEnhancementInput();
        CaptionInputSession.Selection selection = mInputs.tick(mWindow);
        if (selection == null) {
            mCoordinator.setRequesting(false);
            mOutput.onCues(mNative);
            arm();
            return;
        }
        long positionUs = positionUs();
        if (mDerived == null || !equal(mDerivedLanguage, selection.textLanguage)) {
            mCoordinator.setRequesting(false);
            mCoordinator.tick(positionUs, false);
            mDerivedLanguage = selection.textLanguage;
            mDerived = mTemplate.withSourceLanguage(selection.textLanguage);
            mCoordinator.configure(mDerived);
        }
        // Already-target current text can be displayed without a second model translation.
        boolean request = !selection.alreadyTargetLanguage(mTemplate.targetLanguage);
        if (!request) mCoordinator.setRequesting(false);
        mCoordinator.tick(positionUs, false);
        mCoordinator.setRequesting(request);
        TranslationCoordinator.Frame frame = mCoordinator.prepareFrame();
        boolean[] composed = {false};
        if (frame != null && mVisible && mRaw.size() == 1 && mRaw.get(0).bitmap == null && mRaw.get(0).text != null &&
                !NativeCueComposer.blank(mRaw.get(0).text.toString())) {
            mObservation.deliverOutput(mStamp, () -> mInputs.deliver(selection, current -> {
                if (current.input != selection.input) return;
                mCoordinator.deliver(frame, latest -> {
                    CaptionDisplayBundle bundle = CaptionDisplayBundle.create(current.input, latest, 64, 131072);
                    if (bundle == null) return;
                    // Use the native formatter's actual layout, but never feed our two lines back into that formatter.
                    List<Cue> base = mNative.size() == 1 ? mNative :
                            mNative.isEmpty() ? Collections.singletonList(new Cue(mRaw.get(0).text)) : mNative;
                    if (base.size() != 1 || base.get(0).text == null) return;
                    List<Cue> result = NativeCueComposer.composeMapped(base, base.get(0).text.toString(), bundle.original,
                            bundle.translation, mMode, mOrder, 131072);
                    if (result == base) return;
                    mOutput.onCues(result);
                    composed[0] = true;
                });
            }));
        }
        if (!composed[0]) mOutput.onCues(mNative);
        arm();
    }

    private void arm() {
        mUi.remove(mPulse);
        if (!mClosed && mEnabled && mTemplate != null && mMode != NativeCueComposer.Mode.ORIGINAL) mUi.later(mPulse, 250);
    }

    public void close() {
        checkThread();
        if (mClosed) return;
        mClosed = true;
        clearSeekOutput();
        mConfigurationRevision++;
        mUi.remove(mPulse);
        mCoordinator.close();
        mObservation.setEnhancementEnabled(false);
        mAccountOwner = null;
        mTemplate = null;
        mDerived = null;
        mRaw = Collections.emptyList();
        mNative = Collections.emptyList();
        mStamp = null;
        mWindow = null;
    }

    private long positionUs() {
        long value = mPosition.getPositionMs();
        return value < 0 ? 0 : value > Long.MAX_VALUE / 1000 ? Long.MAX_VALUE : value * 1000;
    }
    private void checkThread() { if (!mUi.currentThread()) throw new IllegalStateException("Caption bridge requires its UI thread"); }
    private static boolean equal(String first, String second) { return first == null ? second == null : first.equals(second); }
}
