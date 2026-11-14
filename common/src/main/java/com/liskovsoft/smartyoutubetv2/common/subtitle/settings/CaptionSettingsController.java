package com.liskovsoft.smartyoutubetv2.common.subtitle.settings;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import androidx.appcompat.app.AlertDialog;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.BasePlayerController;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs;
import com.liskovsoft.smartyoutubetv2.common.subtitle.presentation.CaptionOutputBridge;
import com.liskovsoft.smartyoutubetv2.common.subtitle.presentation.NativeCueComposer;
import com.liskovsoft.smartyoutubetv2.common.subtitle.source.CaptionSource;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.CaptionConnection;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.CaptionConnectionStore;
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
    private static final String[] TARGET_CODES = {
            "zh-Hans", "zh-Hant", "en", "ja", "ko", "es", "fr", "de", "ru"
    };
    private static final int[] TARGET_LABELS = {
            R.string.caption_ai_lang_zh_hans, R.string.caption_ai_lang_zh_hant,
            R.string.caption_ai_lang_en, R.string.caption_ai_lang_ja,
            R.string.caption_ai_lang_ko, R.string.caption_ai_lang_es,
            R.string.caption_ai_lang_fr, R.string.caption_ai_lang_de,
            R.string.caption_ai_lang_ru
    };

    private static final class MenuToken {
        final Owner owner;
        final Object source;
        final long viewGeneration;
        final boolean requirePlayer;
        MenuToken(Owner owner, Object source, long viewGeneration, boolean requirePlayer) {
            this.owner = owner;
            this.source = source;
            this.viewGeneration = viewGeneration;
            this.requirePlayer = requirePlayer;
        }
    }
    private static final class Owner {
        volatile boolean active = true;
        CaptionPreferenceStore preferences;
        CaptionConnectionStore connection;
    }

    private final Handler mUi = new Handler(Looper.getMainLooper());
    private final CaptionSelectionMemory mSelection = new CaptionSelectionMemory();
    private final List<Runnable> mPendingReady = new ArrayList<>();
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
    private long mGlobalRevision;
    private int mConnectionSaves;
    private int mGlobalSaves;
    private boolean mConnectionUnsaved;
    private boolean mLifecycleTraceEnabled;
    private int mLifecycleEvents;
    private Object mMenuToken;
    private UiOptionItem mMenuTarget;
    private UiOptionItem mMenuConnection;
    private CaptionPreferenceStore.Snapshot mPreferences;
    private CaptionConnectionStore.Snapshot mConnection;
    private CaptionPreferences.Global mGlobal = CaptionPreferences.Global.defaults();

    @Override public void onInit() {
        mLifecycleTraceEnabled = (getContext().getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        trace("init", null);
        if (mAppPrefs == null) { mAppPrefs = AppPrefs.instance(getContext()); mAppPrefs.addListener(this); }
        if (mIo == null || mIo.isShutdown()) {
            ThreadPoolExecutor io = new ThreadPoolExecutor(1, 1, 1L, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(32), task -> {
                        Thread thread = new Thread(task, "caption-settings");
                        thread.setDaemon(true);
                        return thread;
                    }, new ThreadPoolExecutor.AbortPolicy());
            io.allowCoreThreadTimeOut(true);
            mIo = io;
        }
        if (mOwner == null || !mOwner.active) onProfileChanged();
    }
    private void ensureInitialized() {
        if (mOwner != null && mOwner.active && mAppPrefs != null && mIo != null && !mIo.isShutdown()) return;
        onInit();
    }
    @Override public void onProfileChanged() {
        if (Looper.myLooper() != Looper.getMainLooper()) { mUi.post(this::onProfileChanged); return; }
        if (mAppPrefs == null || mIo == null || mIo.isShutdown()) return;
        Owner previous = mOwner;
        if (previous != null) previous.active = false;
        Owner owner = new Owner();
        mOwner = owner;
        mPreferences = null;
        mConnection = null;
        trace("profile_changed", null);
        mGlobal = CaptionPreferences.Global.defaults();
        mGlobalRevision++;
        mConnectionSaves = mGlobalSaves = 0;
        mGlobalUnsaved = mConnectionUnsaved = false;
        clearMenuRows();
        mChannelWrite = null;
        if (mSource != null) mSelection.begin(mSource, channel(mVideo), defaults());
        applyBridge(); // Stop old credentials before asynchronous profile reads, even in the shared bucket.
        String scope = mAppPrefs.getProfileScope();
        Context app = getContext().getApplicationContext();
        if (!execute(() -> {
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
                drainReadyCallbacks();
                bindChannel();
                restoreAfterLoad();
                updateMenuRows();
                applyBridge();
            });
        })) {
            drainReadyCallbacks();
        }
    }
    @Override public void onNewVideo(Video item) {
        mVideo = item;
        mSource = new Object();
        mSourceReady = false;
        mChannelWrite = null;
        mSelection.begin(mSource, channel(item), preferences());
        trace("new_video", null);
        applyBridge();
        updateMenuRows();
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
    @Override public void onEngineReleased() { mViewGeneration++; trace("engine_released", null); updateMenuRows(); stopBridge(); mSourceReady = false; }
    @Override public void onViewDestroyed() { mViewGeneration++; mViewActive = false; trace("view_destroyed", null); updateMenuRows(); stopBridge(); }
    @Override public void onFinish() {
        mViewGeneration++;
        mViewActive = false;
        mSourceReady = false;
        stopBridge();
        mSelection.clear();
        mSource = null;
        mVideo = null;
        mChannelWrite = null;
        updateMenuRows();
        if (!standaloneMenuActive()) {
            if (mOwner != null) mOwner.active = false;
            mPendingReady.clear();
            if (mAppPrefs != null) mAppPrefs.removeListener(this);
            mAppPrefs = null;
            mPreferences = null;
            mConnection = null;
        }
    }

    public void onStandaloneClosed() {
        if (standaloneMenuActive()) clearMenuRows();
    }

    public boolean metadataCurrent(Video video, MediaItemMetadata metadata) {
        return mOwner == null || mSource == null || mVideo == null || metadata == null ||
                mVideo.videoId == null || metadata.getVideoId() == null || video == mVideo && same(video.videoId, metadata.getVideoId());
    }
    public void metadataUpdated(Video video, MediaItemMetadata metadata) {
        if (video == mVideo && metadata != null && same(video.videoId, metadata.getVideoId())) { bindChannel(); applyBridge(); }
    }
    public Object menuToken() {
        MenuToken menu = mOwner == null ? null : new MenuToken(mOwner, mSource, mViewGeneration, true);
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
        updateMenuRows();
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
        updateMenuRows();
        if (!execute(() -> {
            if (!owner.active) return;
            CaptionPreferenceStore.Snapshot current = owner.preferences.recoverFailedWrite();
            CaptionPreferenceStore.Snapshot saved = current != null && current.valid ?
                    owner.preferences.updateChannel(current, change.channelId, change.preference) : null;
            mUi.post(() -> {
                if (owner != mOwner || !owner.active) return;
                if (saved != null) mPreferences = saved;
                if (mChannelWrite != write) return;
                mChannelWrite = null;
                if (source != mSource) return;
                if (saved != null) { mSelection.saved(change); persistChannel(); }
                else { message(R.string.caption_ai_save_failed); }
                updateMenuRows();
            });
        })) { mChannelWrite = null; updateMenuRows(); }
    }
    private void changeGlobal(GlobalChange change) { changeGlobal(change, true); }
    private void changeGlobal(GlobalChange change, boolean targetRow) {
        Owner owner = mOwner;
        if (owner == null || mPreferences == null) { message(R.string.caption_ai_loading); return; }
        CaptionPreferences.Global desired = change.apply(mGlobal);
        mGlobal = desired;
        long revision = ++mGlobalRevision;
        if (targetRow) mGlobalSaves++;
        applyBridge();
        updateMenuRows();
        if (!execute(() -> {
            if (!owner.active) return;
            CaptionPreferenceStore.Snapshot current = owner.preferences.recoverFailedWrite();
            CaptionPreferenceStore.Snapshot saved = current != null && current.valid ?
                    owner.preferences.updateGlobal(current, new CaptionPreferences.Global(
                            desired.targetLanguage, desired.mode, desired.order, current.preferences.global.connectionId)) : null;
            mUi.post(() -> {
                if (owner != mOwner || !owner.active) return;
                if (targetRow) mGlobalSaves--;
                if (saved != null) mPreferences = saved;
                if (revision == mGlobalRevision) {
                    if (targetRow) mGlobalUnsaved = saved == null;
                    else if (saved != null) mGlobalUnsaved = false;
                    if (saved != null) mGlobal = saved.preferences.global;
                    applyBridge();
                }
                updateMenuRows();
                if (saved == null) message(R.string.caption_ai_save_failed);
            });
        })) {
            if (targetRow) { mGlobalSaves--; mGlobalUnsaved = true; }
            updateMenuRows();
        }
    }

    public boolean isReady() {
        return mOwner != null && mOwner.active && mPreferences != null && mConnection != null;
    }

    /** Ensure global stores are loaded on the serialized settings worker before presenting standalone settings. */
    public void whenReady(Runnable ready) {
        if (ready == null) return;
        if (Looper.myLooper() != Looper.getMainLooper()) { mUi.post(() -> whenReady(ready)); return; }
        ensureInitialized();
        if (isReady() || mOwner == null || !mOwner.active || mIo == null || mIo.isShutdown()) {
            ready.run();
            return;
        }
        mPendingReady.add(ready);
    }

    private void drainReadyCallbacks() {
        if (mPendingReady.isEmpty()) return;
        List<Runnable> callbacks = new ArrayList<>(mPendingReady);
        mPendingReady.clear();
        for (Runnable callback : callbacks) callback.run();
    }

    /** Append global AI subtitle settings (target language, display mode, model settings) to standalone Settings -> Subtitles. */
    public void appendGlobalSettings(AppDialogPresenter dialog) {
        if (dialog == null) return;
        ensureInitialized();
        appendGlobalItems(dialog, standaloneMenuToken());
    }

    /** Append flattened per-channel switch + global AI subtitle settings directly into the player subtitle menu. */
    public void appendMenu(AppDialogPresenter dialog) {
        if (dialog == null || bridge() == null) return;
        Object source = mSource;
        Object token = menuToken();
        dialog.appendSingleSwitch(UiOptionItem.from(getContext().getString(R.string.caption_ai_channel), option -> {
            if (!menuCurrent(token)) return;
            mSelection.translate(source, option.isSelected());
            applyBridge();
            persistChannel();
            if (option.isSelected() && (mConnection == null || !mConnection.hasKey || mGlobal.targetLanguage == null)) {
                message(R.string.caption_ai_not_configured);
            }
        }, mSelection.effective().translate));
        appendGlobalItems(dialog, globalMenuToken());
    }

    private Object globalMenuToken() {
        MenuToken menu = mOwner == null ? null : new MenuToken(mOwner, null, mViewGeneration, true);
        trace("global_menu_captured", menu);
        return menu;
    }
    private Object standaloneMenuToken() {
        MenuToken menu = mOwner == null ? null : new MenuToken(mOwner, null, 0, false);
        trace("standalone_menu_captured", menu);
        return menu;
    }
    private boolean globalMenuCurrent(Object token) {
        if (!(token instanceof MenuToken)) return false;
        MenuToken menu = (MenuToken) token;
        boolean valid = menu.owner != null && menu.owner == mOwner && menu.owner.active &&
                (!menu.requirePlayer || mViewActive && menu.viewGeneration == mViewGeneration && getPlayer() != null);
        trace(valid ? "global_menu_current" : "global_menu_expired", menu);
        return valid;
    }
    private boolean current(Owner owner, Object source) {
        return mViewActive && owner != null && owner == mOwner && owner.active && source != null && source == mSource && getPlayer() != null;
    }

    private void appendGlobalItems(AppDialogPresenter dialog, Object token) {
        Owner owner = mOwner;
        clearMenuRows();
        mMenuToken = token;
        mMenuTarget = (UiOptionItem) UiOptionItem.from(targetTitle(), option -> {
            if (!globalMenuCurrent(token)) return;
            if (mPreferences == null || mConnection == null) { message(R.string.caption_ai_loading); return; }
            showTargetDialog(dialog.getContext(), token);
        });
        dialog.appendSingleButton(mMenuTarget);

        List<OptionItem> modes = new ArrayList<>();
        mode(modes, token, R.string.caption_ai_original_first, NativeCueComposer.Mode.BILINGUAL, NativeCueComposer.Order.ORIGINAL_FIRST);
        mode(modes, token, R.string.caption_ai_translation_first, NativeCueComposer.Mode.BILINGUAL, NativeCueComposer.Order.TRANSLATION_FIRST);
        mode(modes, token, R.string.caption_ai_translation_only, NativeCueComposer.Mode.TRANSLATION, mGlobal.order);
        dialog.appendRadioCategory(getContext().getString(R.string.caption_ai_display), modes);

        mMenuConnection = (UiOptionItem) UiOptionItem.from(connectionTitle(), option -> {
            if (!globalMenuCurrent(token)) return;
            if (mPreferences == null || mConnection == null) { message(R.string.caption_ai_loading); return; }
            CaptionConnectionStore.Snapshot[] expected = {mConnection};
            CaptionConnectionDialog.show(dialog.getContext(), expected[0], () -> globalMenuCurrent(token), (connection, edit, key, completion) -> {
                if (!globalMenuCurrent(token)) { completion.complete(false); return; }
                saveConnection(owner, expected, connection, edit, key, completion);
            });
        });
        dialog.appendSingleButton(mMenuConnection);
    }

    private void showTargetDialog(Context dialogContext, Object token) {
        CharSequence[] items = new CharSequence[TARGET_CODES.length + 1];
        int checked = -1;
        String currentTarget = mGlobal.targetLanguage;
        for (int i = 0; i < TARGET_CODES.length; i++) {
            items[i] = dialogContext.getString(TARGET_LABELS[i]);
            if (TARGET_CODES[i].equals(currentTarget)) checked = i;
        }
        String customLabel = dialogContext.getString(R.string.caption_ai_lang_custom);
        if (currentTarget != null && checked < 0) {
            checked = TARGET_CODES.length;
            customLabel = customLabel + " (" + currentTarget + ")";
        }
        items[TARGET_CODES.length] = customLabel;
        int selectedIndex = checked;
        AlertDialog pickerDialog = new AlertDialog.Builder(dialogContext, R.style.AppDialog)
                .setTitle(R.string.caption_ai_target)
                .setSingleChoiceItems(items, selectedIndex, (picker, which) -> {
                    picker.dismiss();
                    if (!globalMenuCurrent(token)) return;
                    if (which >= 0 && which < TARGET_CODES.length) {
                        String code = TARGET_CODES[which];
                        changeGlobal(before -> new CaptionPreferences.Global(code, before.mode, before.order, before.connectionId));
                    } else {
                        String initial = selectedIndex == TARGET_CODES.length && currentTarget != null ? currentTarget : "";
                        SimpleEditDialog.show(dialogContext, getContext().getString(R.string.caption_ai_target),
                                getContext().getString(R.string.caption_ai_lang_custom_hint), initial, raw -> {
                                    if (!globalMenuCurrent(token)) return true;
                                    String value = raw == null ? "" : raw.trim();
                                    try { new CaptionPreferences.Global(value, mGlobal.mode, mGlobal.order, mGlobal.connectionId); }
                                    catch (IllegalArgumentException ignored) { message(R.string.caption_ai_invalid_language); return false; }
                                    changeGlobal(before -> new CaptionPreferences.Global(value, before.mode, before.order, before.connectionId));
                                    return true;
                                });
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        try {
            pickerDialog.show();
        } catch (RuntimeException e) {
            MessageHelpers.showMessage(dialogContext, e.getMessage());
        }
    }

    private String languageLabel(String code) {
        if (code == null) return getContext().getString(R.string.caption_ai_not_set);
        for (int i = 0; i < TARGET_CODES.length; i++) {
            if (TARGET_CODES[i].equals(code)) return getContext().getString(TARGET_LABELS[i]);
        }
        return code;
    }

    private String targetTitle() {
        String header = getContext().getString(R.string.caption_ai_target) + ": " + languageLabel(mGlobal.targetLanguage);
        if (mGlobalSaves > 0) return header + "\n" + getContext().getString(R.string.caption_ai_saving);
        if (mGlobalUnsaved) return header + "\n" + getContext().getString(R.string.caption_ai_save_failed);
        return header;
    }

    private String connectionTitle() {
        CaptionConnection conn = mConnection == null ? null : mConnection.connection;
        String header = getContext().getString(R.string.caption_ai_connection);
        if (conn != null && conn.wire.model != null && !conn.wire.model.isEmpty()) {
            header = header + ": " + conn.wire.model;
        }
        int status = mConnectionSaves > 0 ? R.string.caption_ai_saving :
                mConnection == null ? R.string.caption_ai_loading :
                mConnectionUnsaved || !mConnection.valid ? R.string.caption_ai_save_failed :
                !mConnection.hasKey ? R.string.caption_ai_key_missing :
                mConnection.persistentKey ? R.string.caption_ai_key_saved : R.string.caption_ai_key_memory;
        return header + "\n" + getContext().getString(status);
    }

    private void updateMenuRows() {
        if (mMenuToken == null || !globalMenuCurrent(mMenuToken)) { clearMenuRows(); return; }
        if (mMenuTarget != null) mMenuTarget.setTitle(targetTitle());
        if (mMenuConnection != null) mMenuConnection.setTitle(connectionTitle());
    }
    private void clearMenuRows() {
        mMenuToken = null;
        mMenuTarget = mMenuConnection = null;
    }
    private boolean standaloneMenuActive() {
        return mMenuToken instanceof MenuToken && !((MenuToken) mMenuToken).requirePlayer && globalMenuCurrent(mMenuToken);
    }
    private void mode(List<OptionItem> modes, Object token,
                      int title, NativeCueComposer.Mode mode, NativeCueComposer.Order order) {
        modes.add(UiOptionItem.from(getContext().getString(title), option -> {
            if (globalMenuCurrent(token)) changeGlobal(before -> new CaptionPreferences.Global(before.targetLanguage, mode, order, before.connectionId), false);
        }, mGlobal.mode == mode && (mode != NativeCueComposer.Mode.BILINGUAL || mGlobal.order == order)));
    }

    interface WriteAllowed { boolean begin(); }
    interface KeySource { String get(); }
    interface Saved { void complete(SaveResult result); }
    static final class SaveResult {
        final boolean saved;
        final boolean partial;
        final boolean cancelled;
        final String code;
        final CaptionConnectionStore.Snapshot snapshot;
        SaveResult(boolean saved, boolean partial, boolean cancelled, String code, CaptionConnectionStore.Snapshot snapshot) {
            this.saved = saved;
            this.partial = partial;
            this.cancelled = cancelled;
            this.code = code;
            this.snapshot = snapshot;
        }
    }
    static final class ConnectionWrite {
        final SaveResult result;
        final CaptionPreferenceStore.Snapshot preferences;
        ConnectionWrite(SaveResult result, CaptionPreferenceStore.Snapshot preferences) {
            this.result = result; this.preferences = preferences;
        }
    }
    static boolean connectionUsable(boolean failed, int pending) { return !failed && pending == 0; }

    /** Only called on the serialized settings worker, using stores captured from the original profile. */
    static ConnectionWrite writeConnection(CaptionConnectionStore store, CaptionPreferenceStore preferences,
            CaptionConnectionStore.Snapshot expected, CaptionConnection connection, CaptionConnectionStore.KeyEdit edit,
            KeySource key, boolean clear, WriteAllowed allowed) {
        if (!allowed.begin()) return new ConnectionWrite(new SaveResult(false, false, true, "CANCELLED", expected), null);
        CaptionConnectionStore.Snapshot saved = null;
        CaptionConnectionStore.Snapshot state = expected;
        CaptionPreferenceStore.Snapshot prefs = null;
        try {
            // Recovery may itself write: the cancellation/owner guard precedes it, not just save().
            CaptionConnectionStore.Snapshot recovered = store.recoverFailedWrite();
            if (recovered == null || !recovered.valid && !clear)
                return new ConnectionWrite(new SaveResult(false, false, false, "SAVE_FAILED", recovered), null);
            state = recovered;
            if (expected == null || recovered.revision != expected.revision)
                return new ConnectionWrite(new SaveResult(false, false, false, "REVISION_CONFLICT", recovered), null);
            // A failed acknowledgement can carry valid=false. After the owner gate and exact original
            // revision check, use this store's recovered snapshot for CAS; never adopt a newer revision.
            saved = clear ? store.clear(recovered) : store.save(recovered, connection, edit, key.get());
            if (saved == null) {
                state = store.load();
                String code = state.revision != expected.revision ? "REVISION_CONFLICT" : "SAVE_FAILED";
                return new ConnectionWrite(new SaveResult(false, false, false, code, state), null);
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
        return new ConnectionWrite(new SaveResult(success, partial, false,
                success ? "SAVED" : partial ? "PARTIAL" : "SAVE_FAILED", state), prefs);
    }
    private void saveConnection(Owner owner, CaptionConnectionStore.Snapshot[] expected, CaptionConnection connection,
                                CaptionConnectionStore.KeyEdit edit, String key, CaptionConnectionDialog.Completion completion) {
        saveConnection(owner, expected, connection, edit, () -> key, false, () -> owner.active,
                result -> completion.complete(result.saved));
    }
    private void saveConnection(Owner owner, CaptionConnectionStore.Snapshot[] expected, CaptionConnection connection,
            CaptionConnectionStore.KeyEdit edit, KeySource key, boolean clear, WriteAllowed allowed, Saved completion) {
        CaptionConnectionStore.Snapshot captured = expected[0];
        mConnectionSaves++;
        applyBridge();
        updateMenuRows();
        if (!execute(() -> {
            ConnectionWrite written = writeConnection(owner.connection, owner.preferences, captured, connection, edit, key, clear,
                    () -> owner.active && allowed.begin());
            mUi.post(() -> {
                if (owner != mOwner || !owner.active) { completion.complete(written.result); return; }
                mConnectionSaves--;
                SaveResult result = written.result;
                if (!result.cancelled) {
                    if (result.snapshot != null) { mConnection = result.snapshot; expected[0] = result.snapshot; }
                    mConnectionUnsaved = !result.saved && (result.partial || result.snapshot == null || !result.snapshot.valid);
                    if (result.saved) {
                        mPreferences = written.preferences;
                        mGlobal = new CaptionPreferences.Global(mGlobal.targetLanguage, mGlobal.mode, mGlobal.order,
                                written.preferences.preferences.global.connectionId);
                        if (result.snapshot.hasKey && !result.snapshot.persistentKey) message(R.string.caption_ai_key_memory);
                    }
                }
                applyBridge();
                updateMenuRows();
                completion.complete(result);
            });
        }, false)) {
            mConnectionSaves--;
            boolean cancelled = !owner.active || !allowed.begin();
            if (!cancelled) { mConnectionUnsaved = true; message(R.string.caption_ai_save_failed); }
            applyBridge(); updateMenuRows();
            completion.complete(new SaveResult(false, false, cancelled, cancelled ? "CANCELLED" : "SAVE_FAILED", captured));
        }
    }
    private boolean execute(Runnable task) { return execute(task, true); }
    private boolean execute(Runnable task, boolean feedback) {
        try { if (mIo == null || mIo.isShutdown()) return false; mIo.execute(task); return true; }
        catch (RuntimeException ignored) { if (feedback) message(R.string.caption_ai_save_failed); return false; }
    }
    private void message(int resource) { MessageHelpers.showMessage(getContext(), resource); }
}
