package com.liskovsoft.smartyoutubetv2.common.subtitle.settings;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.BasePlayerController;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs;
import com.liskovsoft.smartyoutubetv2.common.subtitle.presentation.CaptionOutputBridge;
import com.liskovsoft.smartyoutubetv2.common.subtitle.presentation.NativeCueComposer;
import com.liskovsoft.smartyoutubetv2.common.subtitle.source.CaptionSource;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.CaptionConnection;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.CaptionConnectionStore;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.CaptionConnectionEdit;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.CaptionConnectionProbe;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.ChatTranslationClient;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.CaptionPreferenceStore;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.CaptionPreferences;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.ChannelPreferenceEditor;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.TranslationCoordinator;
import com.liskovsoft.smartyoutubetv2.common.utils.SimpleEditDialog;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Native lifecycle/menu adapter; all persistence and Keystore operations stay on one bounded worker. */
public class CaptionSettingsController extends BasePlayerController implements AppPrefs.ProfileChangeListener {
    private interface GlobalChange { CaptionPreferences.Global apply(CaptionPreferences.Global before); }
    private static final class MenuToken {
        final Owner owner;
        final Object source;
        final long viewGeneration;
        MenuToken(Owner owner, Object source, long viewGeneration) { this.owner = owner; this.source = source; this.viewGeneration = viewGeneration; }
    }
    private static final class Owner {
        volatile boolean active = true;
        CaptionPreferenceStore preferences;
        CaptionConnectionStore connection;
    }
    private final Handler mUi = new Handler(Looper.getMainLooper());
    private final CaptionSelectionMemory mSelection = new CaptionSelectionMemory();
    private ThreadPoolExecutor mIo;
    private AppPrefs mAppPrefs;
    private Owner mOwner;
    private Video mVideo;
    private Object mSource;
    private Object mChannelWrite;
    private boolean mSourceReady;
    private boolean mViewActive;
    private long mViewGeneration;
    private boolean mGlobalUnsaved;
    private boolean mChannelUnsaved;
    private long mGlobalRevision;
    private int mConnectionSaves;
    private int mGlobalSaves;
    private boolean mConnectionUnsaved;
    private boolean mRetrying;
    private boolean mSavedFeedback;
    private boolean mLifecycleTraceEnabled;
    private int mLifecycleEvents;
    private Object mDetailsToken;
    private UiOptionItem mDetailsState;
    private UiOptionItem mDetailsTarget;
    private UiOptionItem mDetailsConnection;
    private CaptionPreferenceStore.Snapshot mPreferences;
    private CaptionConnectionStore.Snapshot mConnection;
    private CaptionPairingDialog mPairing;
    private CaptionPreferences.Global mGlobal = CaptionPreferences.Global.defaults();

    @Override public void onInit() {
        mLifecycleTraceEnabled = (getContext().getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        trace("init", null);
        if (mAppPrefs == null) { mAppPrefs = AppPrefs.instance(getContext()); mAppPrefs.addListener(this); }
        if (mIo == null || mIo.isShutdown()) mIo = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(32), task -> new Thread(task, "caption-settings"), new ThreadPoolExecutor.AbortPolicy());
        onProfileChanged();
    }
    @Override public void onProfileChanged() {
        if (Looper.myLooper() != Looper.getMainLooper()) { mUi.post(this::onProfileChanged); return; }
        if (mAppPrefs == null || mIo == null || mIo.isShutdown()) return;
        Owner previous = mOwner;
        if (previous != null) previous.active = false;
        closePairing();
        Owner owner = new Owner();
        mOwner = owner;
        mPreferences = null;
        mConnection = null;
        trace("profile_changed", null);
        mGlobal = CaptionPreferences.Global.defaults();
        mGlobalRevision++;
        mConnectionSaves = mGlobalSaves = 0;
        mRetrying = mSavedFeedback = false;
        mGlobalUnsaved = mChannelUnsaved = mConnectionUnsaved = false;
        clearDetails();
        mChannelWrite = null;
        if (mSource != null) mSelection.begin(mSource, channel(mVideo), defaults());
        applyBridge(); // Stop old credentials before asynchronous profile reads, even in the shared bucket.
        String scope = mAppPrefs.getProfileScope();
        Context app = getContext().getApplicationContext();
        execute(() -> {
            if (previous != null) {
                if (previous.preferences != null) previous.preferences.close();
                if (previous.connection != null) previous.connection.close();
            }
            if (!owner.active) return;
            owner.preferences = new CaptionPreferenceStore(app, scope, new CaptionPreferenceStore.Limits(8192, 1024 * 1024));
            owner.connection = new CaptionConnectionStore(app, scope);
            CaptionPreferenceStore.Snapshot preferences = owner.preferences.load();
            CaptionConnectionStore.Snapshot connection = owner.connection.load();
            mUi.post(() -> {
                if (mOwner != owner || !owner.active) return;
                mPreferences = preferences;
                mConnection = connection;
                mGlobal = preferences.preferences.global;
                trace("profile_loaded", null);
                bindChannel();
                restoreAfterLoad();
                applyBridge();
            });
        });
    }
    @Override public void onNewVideo(Video item) {
        mVideo = item;
        mSource = new Object();
        mSourceReady = false;
        mChannelWrite = null;
        mChannelUnsaved = false;
        mSelection.begin(mSource, channel(item), preferences());
        trace("new_video", null);
        applyBridge();
        updateDetails();
    }
    @Override public void onSourceChanged(Video item) {
        if (mSource == null || item != mVideo) onNewVideo(item);
        mSourceReady = true;
        trace("source_changed", null);
        bindChannel();
        applyBridge();
    }
    @Override public void onVideoLoaded(Video item) { if (item == mVideo) { bindChannel(); restoreAfterLoad(); applyBridge(); } }
    @Override public void onMetadata(MediaItemMetadata metadata) {
        if (mVideo != null && metadata != null && same(mVideo.videoId, metadata.getVideoId())) { bindChannel(); applyBridge(); }
    }
    @Override public void onEngineInitialized() { trace("engine_initialized", null); applyBridge(); }
    @Override public void onViewCreated() { mViewActive = true; trace("view_created", null); }
    @Override public void onViewResumed() { mViewActive = true; trace("view_resumed", null); restoreAfterLoad(); applyBridge(); }
    @Override public void onEngineReleased() { mViewGeneration++; closePairing(); trace("engine_released", null); clearDetails(); stopBridge(); mSourceReady = false; }
    @Override public void onViewDestroyed() { mViewGeneration++; mViewActive = false; closePairing(); trace("view_destroyed", null); clearDetails(); stopBridge(); }
    @Override public void onFinish() {
        closePairing();
        clearDetails();
        mViewGeneration++;
        mViewActive = false;
        stopBridge();
        if (mOwner != null) mOwner.active = false;
        if (mAppPrefs != null) mAppPrefs.removeListener(this);
        mAppPrefs = null;
        if (mIo != null) mIo.shutdown();
        mSelection.clear();
        mSource = null;
        mVideo = null;
        mPreferences = null;
        mConnection = null;
    }

    public boolean metadataCurrent(Video video, MediaItemMetadata metadata) {
        return mOwner == null || mSource == null || mVideo == null || metadata == null ||
                mVideo.videoId == null || metadata.getVideoId() == null || video == mVideo && same(video.videoId, metadata.getVideoId());
    }
    public void metadataUpdated(Video video, MediaItemMetadata metadata) {
        if (video == mVideo && metadata != null && same(video.videoId, metadata.getVideoId())) { bindChannel(); applyBridge(); }
    }
    public Object menuToken() {
        MenuToken menu = mOwner == null ? null : new MenuToken(mOwner, mSource, mViewGeneration);
        trace("menu_captured", menu);
        return menu;
    }
    public boolean menuCurrent(Object token) {
        if (token == null) return mOwner == null;
        if (!(token instanceof MenuToken)) return false;
        MenuToken menu = (MenuToken) token;
        boolean valid = menu.viewGeneration == mViewGeneration && current(menu.owner, menu.source);
        trace(valid ? "menu_current" : "menu_expired", menu);
        return valid;
    }
    private void trace(String event, MenuToken menu) {
        if (!mLifecycleTraceEnabled || mLifecycleEvents >= 256) return;
        android.util.Log.d("CaptionSettingsLifecycle", "seq=" + (++mLifecycleEvents) + " event=" + event +
                " owner=" + System.identityHashCode(mOwner) + " source=" + System.identityHashCode(mSource) +
                " view=" + mViewGeneration + " active=" + mViewActive + " ready=" + mSourceReady +
                " loaded=" + (mPreferences != null && mConnection != null) +
                (menu == null ? "" : " token=" + System.identityHashCode(menu) +
                        " capturedOwner=" + System.identityHashCode(menu.owner) + " capturedSource=" + System.identityHashCode(menu.source) +
                        " capturedView=" + menu.viewGeneration));
    }
    public void userSelected(FormatItem format) {
        if (mOwner == null || mSource == null || format == null) return;
        List<FormatItem> formats = getPlayer() == null ? null : getPlayer().getSubtitleFormats();
        if (!format.isDefault() && formats != null) {
            for (FormatItem candidate : formats) if (format.equals(candidate)) { format = candidate; break; }
        }
        mSelection.selected(mSource, format, sourceFor(format));
        persistChannel();
    }
    public FormatItem restoreSubtitle(FormatItem nativeFallback) {
        if (mOwner == null) return nativeFallback;
        bindChannel();
        return getPlayer() == null ? nativeFallback : mSelection.restore(nativeFallback, getPlayer().getSubtitleFormats(), this::sourceFor);
    }
    private CaptionSource sourceFor(FormatItem format) {
        CaptionOutputBridge bridge = bridge();
        return bridge == null || format == null || format.getTrack() == null ? null : bridge.sourceFor(format.getTrack().format);
    }
    private void bindChannel() {
        String channel = channel(mVideo);
        if (mSource == null || channel == null || mPreferences == null) return;
        if (mSelection.channel() == null) mSelection.bind(mSource, channel, mPreferences.preferences);
        persistChannel();
        updateDetails();
    }
    private void restoreAfterLoad() {
        if (!mViewActive || getPlayer() == null || !mSourceReady) return;
        if (mSource != null && channel(mVideo) != null) mSelection.bind(mSource, channel(mVideo), preferences());
        FormatItem current = getPlayer().getSubtitleFormat();
        FormatItem wanted = restoreSubtitle(current);
        if (wanted != null && !wanted.equals(current)) getPlayer().setFormat(wanted);
    }
    private void applyBridge() {
        if (mOwner == null || !mViewActive) return;
        CaptionOutputBridge bridge = bridge();
        if (bridge == null) return;
        bridge.bindAccountOwner(mOwner);
        TranslationCoordinator.Profile profile = connectionUsable(mConnectionUnsaved, mConnectionSaves) && mConnection != null && CaptionConnection.PRIMARY.equals(mGlobal.connectionId) ?
                mConnection.profile(mGlobal.targetLanguage) : null;
        bridge.configure(bridge.beginConfiguration(), profile, mSourceReady && connectionUsable(mConnectionUnsaved, mConnectionSaves) && mSelection.effective().translate,
                mGlobal.mode, mGlobal.order);
    }
    private void stopBridge() {
        if (mOwner == null) return;
        CaptionOutputBridge bridge = bridge();
        if (bridge != null) bridge.configure(bridge.beginConfiguration(), null, false, mGlobal.mode, mGlobal.order);
    }
    private CaptionOutputBridge bridge() { return getPlayer() == null ? null : getPlayer().getCaptionOutputBridge(); }
    private CaptionPreferences preferences() { return mPreferences == null ? defaults() : mPreferences.preferences; }
    private static CaptionPreferences defaults() { return new CaptionPreferences(CaptionPreferences.Global.defaults(), Collections.emptyMap()); }
    private static String channel(Video video) {
        if (video == null || video.channelId == null) return null;
        try { new CaptionPreferences(CaptionPreferences.Global.defaults(), Collections.singletonMap(video.channelId, CaptionPreferences.Channel.defaults())); return video.channelId; }
        catch (IllegalArgumentException ignored) { return null; }
    }
    private static boolean same(String a, String b) { return a == null ? b == null : a.equals(b); }

    private void persistChannel() {
        Owner owner = mOwner;
        ChannelPreferenceEditor.Change change = mSelection.pending();
        if (owner == null || mPreferences == null || mChannelWrite != null || change == null) return;
        Object write = new Object(), source = mSource;
        mChannelWrite = write;
        mSavedFeedback = false;
        updateDetails();
        if (!execute(() -> {
            if (!owner.active) return;
            CaptionPreferenceStore.Snapshot current = owner.preferences.load();
            CaptionPreferenceStore.Snapshot saved = owner.preferences.updateChannel(current, change.channelId, change.preference);
            mUi.post(() -> {
                if (owner != mOwner || !owner.active) return;
                if (saved != null) mPreferences = saved;
                if (mChannelWrite != write) return;
                mChannelWrite = null;
                if (source != mSource) return;
                if (saved != null) { mSelection.saved(change); mChannelUnsaved = false; mSavedFeedback = true; persistChannel(); }
                else { mChannelUnsaved = true; message(R.string.caption_ai_save_failed); }
                updateDetails();
            });
        })) { mChannelWrite = null; mChannelUnsaved = true; updateDetails(); }
    }
    private void changeGlobal(GlobalChange change) {
        Owner owner = mOwner;
        if (owner == null || mPreferences == null) { message(R.string.caption_ai_loading); return; }
        mGlobal = change.apply(mGlobal);
        long revision = ++mGlobalRevision;
        mGlobalSaves++;
        mSavedFeedback = false;
        applyBridge();
        updateDetails();
        if (!execute(() -> {
            if (!owner.active) return;
            CaptionPreferenceStore.Snapshot current = owner.preferences.load();
            CaptionPreferenceStore.Snapshot saved = owner.preferences.updateGlobal(current, change.apply(current.preferences.global));
            mUi.post(() -> {
                if (owner != mOwner || !owner.active) return;
                mGlobalSaves--;
                if (saved != null) mPreferences = saved;
                if (revision == mGlobalRevision) {
                    mGlobalUnsaved = saved == null;
                    if (saved != null) { mGlobal = saved.preferences.global; mSavedFeedback = true; }
                    applyBridge();
                }
                updateDetails();
                if (saved == null) message(R.string.caption_ai_save_failed);
            });
        })) { mGlobalSaves--; mGlobalUnsaved = true; updateDetails(); }
    }
    private void retry() {
        Owner owner = mOwner;
        if (owner == null || mPreferences == null || mRetrying) return;
        mRetrying = true;
        updateDetails();
        if (!execute(() -> {
            if (!owner.active) return;
            CaptionPreferenceStore.Snapshot recovered = owner.preferences.recoverFailedWrite();
            owner.connection.recoverFailedWrite();
            mUi.post(() -> {
                if (owner != mOwner || !owner.active) return;
                mRetrying = false;
                if (recovered == null || !recovered.valid) { updateDetails(); message(R.string.caption_ai_save_failed); return; }
                mPreferences = recovered;
                if (mGlobalUnsaved) { CaptionPreferences.Global wanted = mGlobal; changeGlobal(before -> wanted); }
                persistChannel();
                updateDetails();
            });
        })) { mRetrying = false; updateDetails(); }
    }

    public void appendMenu(AppDialogPresenter dialog) {
        if (bridge() == null) return;
        Owner owner = mOwner;
        Object source = mSource;
        Object token = menuToken();
        dialog.appendSingleSwitch(UiOptionItem.from(getContext().getString(R.string.caption_ai_channel), option -> {
            if (!menuCurrent(token)) return;
            mSelection.translate(source, option.isSelected());
            applyBridge();
            persistChannel();
            if (mConnection == null || !mConnection.hasKey || mGlobal.targetLanguage == null) message(R.string.caption_ai_not_configured);
        }, mSelection.effective().translate));
        Object globalToken = globalMenuToken();
        dialog.appendSingleButton(UiOptionItem.from(getContext().getString(R.string.caption_ai_details), option -> {
            if (globalMenuCurrent(globalToken)) showDetails();
        }));
    }
    private Object globalMenuToken() {
        MenuToken menu = mOwner == null ? null : new MenuToken(mOwner, null, mViewGeneration);
        trace("global_menu_captured", menu);
        return menu;
    }
    private boolean globalMenuCurrent(Object token) {
        if (!(token instanceof MenuToken)) return false;
        MenuToken menu = (MenuToken) token;
        boolean valid = mViewActive && menu.viewGeneration == mViewGeneration && menu.owner != null &&
                menu.owner == mOwner && menu.owner.active && getPlayer() != null;
        trace(valid ? "global_menu_current" : "global_menu_expired", menu);
        return valid;
    }
    private boolean current(Owner owner, Object source) { return mViewActive && owner != null && owner == mOwner && owner.active && source != null && source == mSource && getPlayer() != null; }
    private void showDetails() {
        if (mPreferences == null || mConnection == null) { message(R.string.caption_ai_loading); return; }
        Owner owner = mOwner;
        Object token = globalMenuToken();
        AppDialogPresenter dialog = getAppDialogPresenter();
        clearDetails();
        mDetailsToken = token;
        mDetailsState = (UiOptionItem) UiOptionItem.from(getContext().getString(detailsStatus()));
        dialog.appendSingleButton(mDetailsState);
        mDetailsTarget = (UiOptionItem) UiOptionItem.from(targetTitle(), option -> {
            if (!globalMenuCurrent(token)) return;
            SimpleEditDialog.show(dialog.getContext(), getContext().getString(R.string.caption_ai_target), mGlobal.targetLanguage, value -> {
                if (!globalMenuCurrent(token)) return true;
                try { new CaptionPreferences.Global(value, mGlobal.mode, mGlobal.order, mGlobal.connectionId); }
                catch (IllegalArgumentException ignored) { message(R.string.caption_ai_invalid_language); return false; }
                changeGlobal(before -> new CaptionPreferences.Global(value, before.mode, before.order, before.connectionId));
                return true;
            });
        });
        dialog.appendSingleButton(mDetailsTarget);
        List<OptionItem> modes = new ArrayList<>();
        mode(modes, token, R.string.caption_ai_original_first, NativeCueComposer.Mode.BILINGUAL, NativeCueComposer.Order.ORIGINAL_FIRST);
        mode(modes, token, R.string.caption_ai_translation_first, NativeCueComposer.Mode.BILINGUAL, NativeCueComposer.Order.TRANSLATION_FIRST);
        mode(modes, token, R.string.caption_ai_translation_only, NativeCueComposer.Mode.TRANSLATION, mGlobal.order);
        dialog.appendRadioCategory(getContext().getString(R.string.caption_ai_display), modes);
        CaptionConnectionStore.Snapshot[] expected = {mConnection};
        mDetailsConnection = (UiOptionItem) UiOptionItem.from(connectionTitle(), option -> {
            if (!globalMenuCurrent(token)) return;
            CaptionConnectionDialog.show(dialog.getContext(), expected[0], () -> globalMenuCurrent(token), (connection, edit, key, completion) -> {
                if (!globalMenuCurrent(token)) { completion.complete(false); return; }
                saveConnection(owner, expected, connection, edit, key, completion);
            });
        });
        dialog.appendSingleButton(mDetailsConnection);
        dialog.appendSingleButton(UiOptionItem.from(getContext().getString(R.string.caption_pairing_title), option -> {
            if (globalMenuCurrent(token)) showPairing(dialog.getContext(), owner, token);
        }));
        dialog.appendSingleButton(UiOptionItem.from(getContext().getString(R.string.caption_ai_retry), option -> { if (globalMenuCurrent(token)) retry(); }));
        dialog.showDialog(getContext().getString(R.string.caption_ai_details));
    }
    private String targetTitle() {
        String target = mGlobal.targetLanguage == null ? getContext().getString(R.string.caption_ai_not_set) : mGlobal.targetLanguage;
        return getContext().getString(R.string.caption_ai_target) + ": " + target;
    }
    private int detailsStatus() {
        if (mGlobalSaves > 0 || mConnectionSaves > 0 || mChannelWrite != null || mRetrying) return R.string.caption_ai_saving;
        if (mGlobalUnsaved || mChannelUnsaved || mConnectionUnsaved) return R.string.caption_ai_save_failed;
        if (mSelection.channel() == null) return R.string.caption_ai_pending_channel;
        return mSavedFeedback ? R.string.caption_ai_saved : R.string.caption_ai_scope_hint;
    }
    private String connectionTitle() {
        int status = mConnectionSaves > 0 ? R.string.caption_ai_saving :
                mConnectionUnsaved || !mConnection.valid ? R.string.caption_ai_save_failed :
                !mConnection.hasKey ? R.string.caption_ai_key_missing :
                mConnection.persistentKey ? R.string.caption_ai_key_saved : R.string.caption_ai_key_memory;
        return getContext().getString(R.string.caption_ai_connection) + "\n" + getContext().getString(status);
    }
    private void updateDetails() {
        if (mDetailsToken == null || !globalMenuCurrent(mDetailsToken)) { clearDetails(); return; }
        mDetailsState.setTitle(getContext().getString(detailsStatus()));
        mDetailsTarget.setTitle(targetTitle());
        mDetailsConnection.setTitle(connectionTitle());
    }
    private void clearDetails() {
        mDetailsToken = null;
        mDetailsState = mDetailsTarget = mDetailsConnection = null;
    }
    private void mode(List<OptionItem> modes, Object token,
                      int title, NativeCueComposer.Mode mode, NativeCueComposer.Order order) {
        modes.add(UiOptionItem.from(getContext().getString(title), option -> {
            if (globalMenuCurrent(token)) changeGlobal(before -> new CaptionPreferences.Global(before.targetLanguage, mode, order, before.connectionId));
        }, mGlobal.mode == mode && (mode != NativeCueComposer.Mode.BILINGUAL || mGlobal.order == order)));
    }
    private void closePairing() {
        if (mPairing != null) { mPairing.close(); mPairing = null; }
    }
    private void showPairing(Context context, Owner owner, Object token) {
        closePairing();
        CaptionConnectionStore.Snapshot[] expected = {mConnection};
        mPairing = CaptionPairingDialog.show(context, () -> owner.active, new CaptionPairingDialog.Host() {
            @Override public void read(CaptionPairingDialog.Read completion) {
                if (!globalMenuCurrent(token)) { completion.complete(null); return; }
                if (!execute(() -> {
                    CaptionConnectionStore.Snapshot snapshot = null;
                    try { if (owner.active) snapshot = owner.connection.load(); }
                    catch (RuntimeException ignored) { }
                    CaptionConnectionStore.Snapshot loaded = snapshot;
                    mUi.post(() -> {
                        if (!globalMenuCurrent(token)) { completion.complete(null); return; }
                        if (loaded != null) { expected[0] = loaded; mConnection = loaded; updateDetails(); }
                        completion.complete(loaded);
                    });
                })) completion.complete(null);
            }
            @Override public void save(CaptionConnectionEdit edit, CaptionPairingDialog.Session session, CaptionPairingDialog.Saved completion) {
                if (!globalMenuCurrent(token) || !session.current()) {
                    completion.complete(new CaptionPairingDialog.SaveResult(false, false, true, "CANCELLED", expected[0])); return;
                }
                if (expected[0] == null || edit.revision != expected[0].revision) {
                    completion.complete(new CaptionPairingDialog.SaveResult(false, false, false, "REVISION_CONFLICT", expected[0])); return;
                }
                saveConnection(owner, expected, edit.connection, edit.keyEdit, edit::replacement,
                        edit.operation == CaptionConnectionEdit.Operation.CLEAR, session::beginCommit, completion);
            }
            @Override public ChatTranslationClient.Job probe(CaptionConnectionEdit edit, CaptionConnectionStore.Snapshot snapshot) {
                if (!globalMenuCurrent(token)) return null;
                return CaptionConnectionProbe.create(edit, snapshot, mGlobal.targetLanguage == null ? "en" : mGlobal.targetLanguage);
            }
        });
    }
    interface WriteAllowed { boolean begin(); }
    interface KeySource { String get(); }
    static final class ConnectionWrite {
        final CaptionPairingDialog.SaveResult result;
        final CaptionPreferenceStore.Snapshot preferences;
        ConnectionWrite(CaptionPairingDialog.SaveResult result, CaptionPreferenceStore.Snapshot preferences) {
            this.result = result; this.preferences = preferences;
        }
    }
    static boolean connectionUsable(boolean failed, int pending) { return !failed && pending == 0; }

    /** Only called on the serialized settings worker, using stores captured from the original profile. */
    static ConnectionWrite writeConnection(CaptionConnectionStore store, CaptionPreferenceStore preferences,
            CaptionConnectionStore.Snapshot expected, CaptionConnection connection, CaptionConnectionStore.KeyEdit edit,
            KeySource key, boolean clear, WriteAllowed allowed) {
        if (!allowed.begin()) return new ConnectionWrite(new CaptionPairingDialog.SaveResult(false, false, true, "CANCELLED", expected), null);
        CaptionConnectionStore.Snapshot saved = null;
        CaptionConnectionStore.Snapshot state = expected;
        CaptionPreferenceStore.Snapshot prefs = null;
        try {
            // Recovery may itself write: the cancellation/owner guard precedes it, not just save().
            CaptionConnectionStore.Snapshot recovered = store.recoverFailedWrite();
            if (recovered == null || !recovered.valid && !clear)
                return new ConnectionWrite(new CaptionPairingDialog.SaveResult(false, false, false, "SAVE_FAILED", recovered), null);
            state = recovered;
            if (expected == null || recovered.revision != expected.revision)
                return new ConnectionWrite(new CaptionPairingDialog.SaveResult(false, false, false, "REVISION_CONFLICT", recovered), null);
            // A failed acknowledgement can carry valid=false. After the owner gate and exact original
            // revision check, use this store's recovered snapshot for CAS; never adopt a newer revision.
            saved = clear ? store.clear(recovered) : store.save(recovered, connection, edit, key.get());
            if (saved == null) {
                state = store.load();
                String code = state.revision != expected.revision ? "REVISION_CONFLICT" : "SAVE_FAILED";
                return new ConnectionWrite(new CaptionPairingDialog.SaveResult(false, false, false, code, state), null);
            }
            state = saved;
            prefs = preferences.recoverFailedWrite();
            if (prefs != null && prefs.valid && saved.connection != null && !CaptionConnection.PRIMARY.equals(prefs.preferences.global.connectionId)) {
                prefs = preferences.updateGlobal(prefs, new CaptionPreferences.Global(prefs.preferences.global.targetLanguage,
                        prefs.preferences.global.mode, prefs.preferences.global.order, CaptionConnection.PRIMARY));
            }
        } catch (RuntimeException ignored) {
            // Never return exceptions that could contain submitted connection fields or credentials.
            if (saved == null) try { state = store.load(); } catch (RuntimeException unavailable) { state = null; }
        }
        boolean success = saved != null && prefs != null && prefs.valid;
        boolean partial = saved != null && !success;
        return new ConnectionWrite(new CaptionPairingDialog.SaveResult(success, partial, false,
                success ? "SAVED" : partial ? "PARTIAL" : "SAVE_FAILED", state), prefs);
    }
    private void saveConnection(Owner owner, CaptionConnectionStore.Snapshot[] expected, CaptionConnection connection,
                                CaptionConnectionStore.KeyEdit edit, String key, CaptionConnectionDialog.Completion completion) {
        saveConnection(owner, expected, connection, edit, () -> key, false, () -> owner.active,
                result -> completion.complete(result.saved));
    }
    private void saveConnection(Owner owner, CaptionConnectionStore.Snapshot[] expected, CaptionConnection connection,
            CaptionConnectionStore.KeyEdit edit, KeySource key, boolean clear, WriteAllowed allowed, CaptionPairingDialog.Saved completion) {
        CaptionConnectionStore.Snapshot captured = expected[0];
        mConnectionSaves++;
        mSavedFeedback = false;
        applyBridge();
        updateDetails();
        if (!execute(() -> {
            ConnectionWrite written = writeConnection(owner.connection, owner.preferences, captured, connection, edit, key, clear,
                    () -> owner.active && allowed.begin());
            mUi.post(() -> {
                if (owner != mOwner || !owner.active) { completion.complete(written.result); return; }
                mConnectionSaves--;
                CaptionPairingDialog.SaveResult result = written.result;
                if (!result.cancelled) {
                    if (result.snapshot != null) { mConnection = result.snapshot; expected[0] = result.snapshot; }
                    mConnectionUnsaved = !result.saved;
                    if (result.saved) {
                        mSavedFeedback = true;
                        mPreferences = written.preferences;
                        mGlobal = new CaptionPreferences.Global(mGlobal.targetLanguage, mGlobal.mode, mGlobal.order,
                                written.preferences.preferences.global.connectionId);
                        if (result.snapshot.hasKey && !result.snapshot.persistentKey) message(R.string.caption_ai_key_memory);
                    }
                }
                applyBridge();
                updateDetails();
                completion.complete(result);
            });
        }, false)) {
            mConnectionSaves--;
            boolean cancelled = !owner.active || !allowed.begin();
            if (!cancelled) { mConnectionUnsaved = true; message(R.string.caption_ai_save_failed); }
            applyBridge(); updateDetails();
            completion.complete(new CaptionPairingDialog.SaveResult(false, false, cancelled, cancelled ? "CANCELLED" : "SAVE_FAILED", captured));
        }
    }
    private boolean execute(Runnable task) { return execute(task, true); }
    private boolean execute(Runnable task, boolean feedback) {
        try { if (mIo == null || mIo.isShutdown()) return false; mIo.execute(task); return true; }
        catch (RuntimeException ignored) { if (feedback) message(R.string.caption_ai_save_failed); return false; }
    }
    private void message(int resource) { MessageHelpers.showMessage(getContext(), resource); }
}
