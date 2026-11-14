package com.liskovsoft.smartyoutubetv2.common.subtitle.settings;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.widget.EditText;
import androidx.appcompat.app.AlertDialog;
import com.google.android.exoplayer2.Format;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata;
import com.liskovsoft.mediaserviceinterfaces.oauth.Account;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.ExoFormatItem;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs;
import com.liskovsoft.smartyoutubetv2.common.subtitle.presentation.CaptionOutputBridge;
import com.liskovsoft.smartyoutubetv2.common.subtitle.presentation.CaptionOutputBridgeTest;
import com.liskovsoft.smartyoutubetv2.common.subtitle.presentation.NativeCueComposer;
import com.liskovsoft.smartyoutubetv2.common.subtitle.source.CaptionSource;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.CaptionConnection;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.CaptionConnectionStore;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.CaptionPreferenceStore;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.CaptionPreferences;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.ChatCompletionAdapter;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.TranslationCoordinator;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Native event adapter with no account service, network, real connection, disk writes or device. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 27)
public class CaptionSettingsControllerTest {
    @Test public void lifecycleMarkersUseTheActualCapturedTokenStayBoundedAndExcludeContent() throws Exception {
        Harness h = new Harness();
        ShadowLog.clear();
        ReflectionHelpers.setField(h, "mLifecycleTraceEnabled", true);
        try {
            Video video = video("synthetic-private-video");
            video.channelId = "synthetic-private-channel";
            h.onNewVideo(video);
            Object captured = h.menuToken();
            h.onEngineReleased();
            h.onEngineInitialized();
            assertFalse(h.menuCurrent(captured));
            assertTrue(h.menuCurrent(h.menuToken()));
            for (int i = 0; i < 300; i++) h.menuCurrent(captured);
            List<ShadowLog.LogItem> logs = ShadowLog.getLogsForTag("CaptionSettingsLifecycle");
            assertEquals(256, logs.size());
            String expired = logs.stream().map(item -> item.msg).filter(value -> value.contains("event=menu_expired")).findFirst().get();
            assertTrue(expired.contains("view=1"));
            assertTrue(expired.contains("capturedView=0"));
            for (ShadowLog.LogItem item : logs) {
                assertFalse(item.msg.contains(video.videoId));
                assertFalse(item.msg.contains(video.channelId));
                assertTrue(item.msg.matches("[a-zA-Z0-9_= ]+"));
            }
        } finally { h.onFinish(); }
    }
    @Test public void manualTrackAndNoneSurviveLateChannelMetadataWithoutReclassifyingDisplayLanguage() throws Exception {
        Harness h = new Harness();
        Video video = video("synthetic-a");
        h.onNewVideo(video);
        h.userSelected(h.english);
        video.channelId = "synthetic-channel";
        h.metadataUpdated(video, metadata(video.videoId));
        assertSame(h.english, h.restoreSubtitle(FormatItem.SUBTITLE_NONE));
        h.userSelected(FormatItem.SUBTITLE_NONE);
        h.metadataUpdated(video, metadata(video.videoId));
        assertSame(FormatItem.SUBTITLE_NONE, h.restoreSubtitle(h.english));
        h.onFinish();
    }
    @Test public void menuTokensRejectSourceAbaProfileOwnerChangeAndFinishedPlayback() throws Exception {
        Harness h = new Harness();
        Video a = video("synthetic-a"), b = video("synthetic-b");
        h.onNewVideo(a);
        Object first = h.menuToken();
        assertTrue(h.menuCurrent(first));
        h.onNewVideo(b);
        assertFalse(h.menuCurrent(first));
        h.onNewVideo(a);
        assertFalse(h.menuCurrent(first));
        Object reopened = h.menuToken();
        assertTrue(h.menuCurrent(reopened));
        h.installOwner();
        assertFalse(h.menuCurrent(reopened));
        Object current = h.menuToken();
        h.onFinish();
        assertFalse(h.menuCurrent(current));
    }
    @Test public void globalPairingTokenSurvivesSourceChangesButNotProfileOrEngineChanges() throws Exception {
        Harness h = new Harness();
        try {
            h.onNewVideo(video("synthetic-a"));
            Object token = ReflectionHelpers.callInstanceMethod(h, "globalMenuToken");
            h.onNewVideo(video("synthetic-b"));
            assertTrue((Boolean) ReflectionHelpers.callInstanceMethod(h, "globalMenuCurrent", ReflectionHelpers.ClassParameter.from(Object.class, token)));
            h.onEngineReleased();
            assertFalse((Boolean) ReflectionHelpers.callInstanceMethod(h, "globalMenuCurrent", ReflectionHelpers.ClassParameter.from(Object.class, token)));
            Object next = ReflectionHelpers.callInstanceMethod(h, "globalMenuToken");
            h.installOwner();
            assertFalse((Boolean) ReflectionHelpers.callInstanceMethod(h, "globalMenuCurrent", ReflectionHelpers.ClassParameter.from(Object.class, next)));
        } finally { h.onFinish(); }
    }
    @Test public void standaloneSettingsTokenWorksWithoutActivePlayerAndExpiresOnProfileChange() throws Exception {
        ActivityController<Activity> home = activity();
        Harness h = new Harness(false);
        h.context = home.get();
        MenuPresenter presenter = new MenuPresenter(home.get());
        try {
            boolean[] called = {false};
            h.whenReady(() -> {
                called[0] = true;
                h.appendGlobalSettings(presenter);
            });
            awaitControllerIo(h);
            assertTrue(called[0]);
            assertEquals("Standalone global settings expose target + batch + connection buttons and display radio category", 3, presenter.buttons.size());
            assertEquals(1, presenter.radioCategories.size());
            assertEquals(0, presenter.switches.size());

            OptionItem targetButton = presenter.buttons.get(0);
            targetButton.onSelect(true);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            AlertDialog picker = (AlertDialog) ShadowDialog.getLatestDialog();
            assertNotNull(picker);
            assertTrue(picker.isShowing());
            picker.getListView().performItemClick(picker.getListView().getAdapter().getView(0, null, picker.getListView()), 0, 0);
            awaitControllerIo(h);
            assertFalse(picker.isShowing());
            assertTrue(targetButton.getTitle().toString().contains(home.get().getString(R.string.caption_ai_lang_zh_hans)));

            Object token = ReflectionHelpers.getField(h, "mMenuToken");
            assertTrue((Boolean) ReflectionHelpers.callInstanceMethod(h, "globalMenuCurrent", ReflectionHelpers.ClassParameter.from(Object.class, token)));

            // Player teardown events (engine release, view destroy, playback finish) must NOT break open standalone Settings -> Subtitles
            h.onEngineReleased();
            h.onViewDestroyed();
            h.onFinish();
            assertTrue((Boolean) ReflectionHelpers.callInstanceMethod(h, "globalMenuCurrent", ReflectionHelpers.ClassParameter.from(Object.class, token)));
            targetButton.onSelect(true);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            AlertDialog pickerAfterFinish = (AlertDialog) ShadowDialog.getLatestDialog();
            assertNotNull(pickerAfterFinish);
            pickerAfterFinish.getListView().performItemClick(pickerAfterFinish.getListView().getAdapter().getView(3, null, pickerAfterFinish.getListView()), 3, 3);
            awaitControllerIo(h);
            assertTrue("Standalone row title must still update after background player teardown",
                    targetButton.getTitle().toString().contains(home.get().getString(R.string.caption_ai_lang_ja)));

            h.onProfileChanged();
            assertFalse((Boolean) ReflectionHelpers.callInstanceMethod(h, "globalMenuCurrent", ReflectionHelpers.ClassParameter.from(Object.class, token)));
        } finally {
            h.onStandaloneClosed();
            h.onFinish();
            home.pause().stop().destroy();
        }
    }
    @Test public void whenReadyWaitsForLatestProfileLoadWhenProfileChangesMidHydration() throws Exception {
        ActivityController<Activity> home = activity();
        Harness h = new Harness(false);
        h.context = home.get();
        try {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).pause();
            boolean[] readyFired = {false};
            boolean[] storesReadyAtFire = {false};
            h.whenReady(() -> {
                readyFired[0] = true;
                storesReadyAtFire[0] = h.isReady();
            });
            // Trigger a second profile reload before the first load's UI post runs
            h.onProfileChanged();
            assertFalse("whenReady must not fire before the active profile finishes hydrating", readyFired[0]);
            ThreadPoolExecutor io = ReflectionHelpers.getField(h, "mIo");
            CountDownLatch drained = new CountDownLatch(1);
            io.execute(drained::countDown);
            assertTrue(drained.await(5, TimeUnit.SECONDS));
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertTrue(readyFired[0]);
            assertTrue("Stores must be fully hydrated when ready callback runs", storesReadyAtFire[0]);
        } finally {
            h.onFinish();
            home.pause().stop().destroy();
        }
    }
    @Test public void customLanguageCodeOptionValidatesTrimsAndPreselectsOnReopen() throws Exception {
        ActivityController<Activity> menu = activity();
        Harness h = new Harness(false);
        h.context = menu.get();
        MenuPresenter presenter = new MenuPresenter(menu.get());
        try {
            h.whenReady(() -> h.appendGlobalSettings(presenter));
            awaitControllerIo(h);
            OptionItem targetButton = presenter.buttons.get(0);
            targetButton.onSelect(true);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            AlertDialog picker = (AlertDialog) ShadowDialog.getLatestDialog();
            int customIndex = picker.getListView().getCount() - 1;
            picker.getListView().performItemClick(picker.getListView().getAdapter().getView(customIndex, null, picker.getListView()), customIndex, customIndex);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

            AlertDialog customDialog = (AlertDialog) ShadowDialog.getLatestDialog();
            assertNotSame(picker, customDialog);
            EditText input = customDialog.findViewById(R.id.simple_edit_value);
            input.setText("  pt-BR  ");
            customDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            awaitControllerIo(h);
            assertFalse(customDialog.isShowing());
            assertTrue(targetButton.getTitle().toString().endsWith(": pt-BR"));

            targetButton.onSelect(true);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            AlertDialog reopened = (AlertDialog) ShadowDialog.getLatestDialog();
            assertEquals(customIndex, reopened.getListView().getCheckedItemPosition());
            reopened.dismiss();
        } finally {
            h.onFinish();
            menu.pause().stop().destroy();
        }
    }
    @Test public void engineOrViewRecreationCannotReviveOldDialogActionsForTheSameVideo() throws Exception {
        Harness h = new Harness();
        h.onNewVideo(video("synthetic-a"));
        Object original = h.menuToken();
        h.onEngineReleased();
        h.onEngineInitialized();
        assertFalse(h.menuCurrent(original));
        Object beforeDestroy = h.menuToken();
        h.onViewDestroyed();
        assertFalse(h.menuCurrent(beforeDestroy));
        h.onViewCreated(); h.onViewResumed();
        assertFalse(h.menuCurrent(beforeDestroy));
        assertTrue(h.menuCurrent(h.menuToken()));
        h.onFinish();
    }
    @Test public void metadataRequiresKnownCurrentVideoIdentityButUnknownEvidenceKeepsNativeFallback() throws Exception {
        Harness h = new Harness();
        Video current = video("synthetic-a");
        h.onNewVideo(current);
        assertTrue(h.metadataCurrent(current, metadata("synthetic-a")));
        assertFalse(h.metadataCurrent(video("synthetic-a"), metadata("synthetic-a")));
        assertFalse(h.metadataCurrent(current, metadata("synthetic-b")));
        assertTrue(h.metadataCurrent(current, metadata(null)));
        h.onFinish();
    }
    @Test public void nonDebugSettingsRemainAvailableWithoutDiagnosticsOrChangingNativeFallback() throws Exception {
        Harness h = new Harness(false);
        int flags = h.getContext().getApplicationInfo().flags;
        try {
            h.getContext().getApplicationInfo().flags &= ~ApplicationInfo.FLAG_DEBUGGABLE;
            ShadowLog.clear();
            h.onInit();
            assertNotNull(h.menuToken());
            assertFalse(h.menuCurrent(null));
            assertNotNull(ReflectionHelpers.getField(h, "mIo"));
            assertNotNull(ReflectionHelpers.getField(h, "mAppPrefs"));
            assertFalse((Boolean) ReflectionHelpers.getField(h, "mLifecycleTraceEnabled"));
            h.onNewVideo(video("synthetic-a"));
            assertSame(h.english, h.restoreSubtitle(h.english));
            h.userSelected(FormatItem.SUBTITLE_NONE);
            assertSame(FormatItem.SUBTITLE_NONE, h.restoreSubtitle(h.english));
            assertTrue(ShadowLog.getLogsForTag("CaptionSettingsLifecycle").isEmpty());
        } finally { h.getContext().getApplicationInfo().flags = flags; h.onFinish(); }
    }

    @Test public void targetEditorUsesTheNativeMenuActivityNotTheCoveredPlaybackActivity() throws Exception {
        assertEditorOwner(R.string.caption_ai_target);
    }
    @Test public void batchSizeEditorUsesTheNativeMenuActivityNotTheCoveredPlaybackActivity() throws Exception {
        assertEditorOwner(R.string.caption_ai_batch_size);
    }
    @Test public void connectionEditorUsesTheNativeMenuActivityNotTheCoveredPlaybackActivity() throws Exception {
        assertEditorOwner(R.string.caption_ai_connection);
    }
    @Test public void customBatchSizeOptionValidatesTrimsAndPreselectsOnReopen() throws Exception {
        ActivityController<Activity> menu = activity();
        Harness h = new Harness(false);
        h.context = menu.get();
        MenuPresenter presenter = new MenuPresenter(menu.get());
        try {
            h.whenReady(() -> h.appendGlobalSettings(presenter));
            awaitControllerIo(h);
            OptionItem batchButton = presenter.buttons.stream()
                    .filter(item -> item.getTitle().toString().startsWith(menu.get().getString(R.string.caption_ai_batch_size)))
                    .findFirst().get();
            String beforeTitle = batchButton.getTitle().toString();
            for (String invalid : new String[]{"0", "33", "abc"}) {
                batchButton.onSelect(true);
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
                AlertDialog picker = (AlertDialog) ShadowDialog.getLatestDialog();
                int customIndex = picker.getListView().getCount() - 1;
                picker.getListView().performItemClick(picker.getListView().getAdapter().getView(customIndex, null, picker.getListView()), customIndex, customIndex);
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

                AlertDialog customDialog = (AlertDialog) ShadowDialog.getLatestDialog();
                EditText input = customDialog.findViewById(R.id.simple_edit_value);
                input.setText(invalid);
                customDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
                assertTrue("Invalid batch size " + invalid + " keeps custom dialog open", customDialog.isShowing());
                assertEquals(beforeTitle, batchButton.getTitle().toString());
                customDialog.dismiss();
            }

            batchButton.onSelect(true);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            AlertDialog picker = (AlertDialog) ShadowDialog.getLatestDialog();
            int customIndex = picker.getListView().getCount() - 1;
            picker.getListView().performItemClick(picker.getListView().getAdapter().getView(customIndex, null, picker.getListView()), customIndex, customIndex);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

            AlertDialog customDialog = (AlertDialog) ShadowDialog.getLatestDialog();
            EditText input = customDialog.findViewById(R.id.simple_edit_value);
            input.setText("  14  ");
            customDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            awaitControllerIo(h);
            assertFalse(customDialog.isShowing());
            assertTrue(batchButton.getTitle().toString().contains("14"));

            batchButton.onSelect(true);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            AlertDialog reopened = (AlertDialog) ShadowDialog.getLatestDialog();
            assertEquals(customIndex, reopened.getListView().getCheckedItemPosition());
            reopened.dismiss();
        } finally {
            h.onFinish();
            menu.pause().stop().destroy();
        }
    }
    @Test public void resolveVideoContextPrefersMetadataTitleWithVideoFallbackAndLatchesOncePerSource() throws Exception {
        Video v = video("synthetic-ctx");
        v.deArrowTitle = "DeArrow\t Title\nHere";
        v.title = "Video Title";
        v.author = "Video\u0007Author";
        MediaItemMetadata meta = metadataWith("synthetic-ctx", "Meta Title", "Meta Author");
        assertEquals("Title: DeArrow Title Here\nChannel: Video Author", CaptionSettingsController.resolveVideoContext(v, meta));
        v.deArrowTitle = null;
        assertEquals("Title: Meta Title\nChannel: Video Author", CaptionSettingsController.resolveVideoContext(v, meta));
        v.author = null;
        assertEquals("Title: Meta Title\nChannel: Meta Author", CaptionSettingsController.resolveVideoContext(v, meta));
        assertEquals("Title: Video Title", CaptionSettingsController.resolveVideoContext(v, null));
        v.title = null;
        v.author = "Only Chan";
        assertEquals("Channel: Only Chan", CaptionSettingsController.resolveVideoContext(v, null));
        assertEquals("", CaptionSettingsController.resolveVideoContext(null, null));

        ActivityController<Activity> playback = activity();
        Harness h = new Harness();
        h.context = playback.get();
        CaptionOutputBridgeTest.AccountPlayback bridgeOwner = new CaptionOutputBridgeTest.AccountPlayback();
        h.captionBridge = bridgeOwner.bridge;
        CaptionPreferenceStore preferences = new CaptionPreferenceStore(playback.get(), "synthetic-ctx-latch", new CaptionPreferenceStore.Limits(32, 65536));
        CaptionConnectionStore connection = new CaptionConnectionStore(playback.get(), "synthetic-ctx-latch");
        try {
            CaptionPreferenceStore.Snapshot prefSnap = preferences.updateGlobal(preferences.load(),
                    new com.liskovsoft.smartyoutubetv2.common.subtitle.translation.CaptionPreferences.Global(
                            "zh", com.liskovsoft.smartyoutubetv2.common.subtitle.presentation.NativeCueComposer.Mode.BILINGUAL,
                            com.liskovsoft.smartyoutubetv2.common.subtitle.presentation.NativeCueComposer.Order.ORIGINAL_FIRST,
                            CaptionConnection.PRIMARY, 8));
            prefSnap = preferences.updateChannel(prefSnap, "synthetic-ch",
                    new com.liskovsoft.smartyoutubetv2.common.subtitle.translation.CaptionPreferences.Channel(
                            true, null, com.liskovsoft.smartyoutubetv2.common.subtitle.source.CaptionSource.TextKind.UNKNOWN));
            CaptionConnectionStore.Snapshot connSnap = connection.save(connection.load(), CaptionConnection.basic(
                    "https://synthetic.invalid/v1/chat/completions", "synthetic-model", 1024, ChatCompletionAdapter.Thinking.OMIT),
                    CaptionConnectionStore.KeyEdit.REPLACE, "synthetic-ctx-key");
            ReflectionHelpers.setField(h, "mPreferences", prefSnap);
            ReflectionHelpers.setField(h, "mGlobal", prefSnap.preferences.global);
            ReflectionHelpers.setField(h, "mConnection", connSnap);
            h.onViewCreated();

            Video first = video("synthetic-v1");
            first.channelId = "synthetic-ch";
            first.title = "First Title";
            first.author = "Chan One";
            h.onNewVideo(first);
            h.onSourceChanged(first);
            com.liskovsoft.smartyoutubetv2.common.subtitle.translation.TranslationCoordinator.Profile firstProfile =
                    ReflectionHelpers.getField(bridgeOwner.bridge, "mTemplate");
            assertNotNull(firstProfile);
            assertEquals("Title: First Title\nChannel: Chan One", firstProfile.context);

            // Same source receives later metadata update: latched context remains unchanged.
            h.metadataUpdated(first, metadataWith("synthetic-v1", "Later Mutated Title", "Later Chan"));
            assertSame(firstProfile, ReflectionHelpers.getField(bridgeOwner.bridge, "mTemplate"));

            // New source resets latched video context.
            Video second = video("synthetic-v2");
            second.channelId = "synthetic-ch";
            second.title = "Second Title";
            second.author = "Chan Two";
            h.onNewVideo(second);
            h.onSourceChanged(second);
            com.liskovsoft.smartyoutubetv2.common.subtitle.translation.TranslationCoordinator.Profile secondProfile =
                    ReflectionHelpers.getField(bridgeOwner.bridge, "mTemplate");
            assertNotNull(secondProfile);
            assertEquals("Title: Second Title\nChannel: Chan Two", secondProfile.context);
        } finally {
            h.onFinish(); bridgeOwner.close(); preferences.close(); connection.close();
            playback.pause().stop().destroy();
        }
    }
    private static MediaItemMetadata metadataWith(String id, String title, String author) {
        return (MediaItemMetadata) Proxy.newProxyInstance(MediaItemMetadata.class.getClassLoader(), new Class<?>[]{MediaItemMetadata.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getVideoId")) return id;
                    if (method.getName().equals("getTitle")) return title;
                    if (method.getName().equals("getAuthor")) return author;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == long.class) return 0L;
                    return null;
                });
    }
    @Test public void flattenedPlayerMenuShowsFourCleanControlsAndModelSummaryWithoutExposingTheKey() throws Exception {
        ActivityController<Activity> playback = activity(), menu = activity();
        Harness h = new Harness();
        h.context = playback.get();
        h.captionBridge = new CaptionOutputBridgeTest.AccountPlayback().bridge;
        MenuPresenter presenter = new MenuPresenter(menu.get());
        h.dialogPresenter = presenter;
        CaptionPreferenceStore preferences = new CaptionPreferenceStore(playback.get(), "synthetic-visible-status", new CaptionPreferenceStore.Limits(32, 65536));
        CaptionConnectionStore connection = new CaptionConnectionStore(playback.get(), "synthetic-visible-status");
        try {
            ReflectionHelpers.setField(h, "mPreferences", preferences.load());
            ReflectionHelpers.setField(h, "mConnection", connection.load());
            h.onNewVideo(video("synthetic-a"));
            h.appendMenu(presenter);
            assertEquals("Exactly 1 channel switch", 1, presenter.switches.size());
            assertEquals("Exactly 1 display radio category", 1, presenter.radioCategories.size());
            assertEquals("Exactly 3 global action rows (target language + batch size + model settings), no status/retry clutter", 3, presenter.buttons.size());

            OptionItem row = presenter.buttons.stream().filter(item -> item.getTitle().toString().startsWith(menu.get().getString(R.string.caption_ai_connection))).findFirst().get();
            assertTrue("Native singleButton only renders its title", row.getTitle().toString().contains(menu.get().getString(R.string.caption_ai_key_missing)));
            String key = "synthetic-visible-status-not-a-real-key";
            CaptionConnectionStore.Snapshot saved = connection.save(connection.load(), CaptionConnection.basic(
                    "https://synthetic.invalid/v1/chat/completions", "synthetic-model", 1024, ChatCompletionAdapter.Thinking.OMIT), CaptionConnectionStore.KeyEdit.REPLACE, key);
            assertNotNull(saved);
            ReflectionHelpers.setField(h, "mConnection", saved);
            presenter.clearCaptured();
            h.appendMenu(presenter);
            row = presenter.buttons.stream().filter(item -> item.getTitle().toString().startsWith(menu.get().getString(R.string.caption_ai_connection))).findFirst().get();
            int status = saved.persistentKey ? R.string.caption_ai_key_saved : R.string.caption_ai_key_memory;
            assertTrue(row.getTitle().toString().contains("synthetic-model"));
            assertTrue(row.getTitle().toString().contains(menu.get().getString(status)));
            assertFalse(row.getTitle().toString().contains(key));
        } finally {
            h.onFinish(); preferences.close(); connection.close();
            menu.pause().stop().destroy(); playback.pause().stop().destroy();
        }
    }
    private void assertEditorOwner(int title) throws Exception {
        ActivityController<Activity> playback = activity(), menu = activity();
        Harness h = new Harness();
        h.context = playback.get();
        h.captionBridge = new CaptionOutputBridgeTest.AccountPlayback().bridge;
        MenuPresenter presenter = new MenuPresenter(menu.get());
        h.dialogPresenter = presenter;
        CaptionPreferenceStore preferences = new CaptionPreferenceStore(playback.get(), "synthetic-menu-window", new CaptionPreferenceStore.Limits(32, 65536));
        CaptionConnectionStore connection = new CaptionConnectionStore(playback.get(), "synthetic-menu-window");
        AlertDialog editor = null;
        try {
            ReflectionHelpers.setField(h, "mPreferences", preferences.load());
            ReflectionHelpers.setField(h, "mConnection", connection.load());
            h.onNewVideo(video("synthetic-a"));
            h.appendMenu(presenter);
            OptionItem option = presenter.buttons.stream().filter(item -> item.getTitle().toString().startsWith(menu.get().getString(title))).findFirst().get();
            option.onSelect(true);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            editor = (AlertDialog) ShadowDialog.getLatestDialog();
            assertNotNull(editor);
            assertTrue(editor.isShowing());
            Context context = editor.getContext();
            while (!(context instanceof Activity) && context instanceof ContextWrapper) context = ((ContextWrapper) context).getBaseContext();
            assertSame("Editor must be above the native menu, not behind its Activity", menu.get(), context);
        } finally {
            if (editor != null) editor.dismiss();
            h.onFinish(); preferences.close(); connection.close();
            menu.pause().stop().destroy(); playback.pause().stop().destroy();
        }
    }
    private static void awaitControllerIo(CaptionSettingsController controller) throws Exception {
        ThreadPoolExecutor io = ReflectionHelpers.getField(controller, "mIo");
        CountDownLatch ready = new CountDownLatch(1);
        io.execute(ready::countDown);
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }
    private static ActivityController<Activity> activity() {
        ActivityController<Activity> owner = Robolectric.buildActivity(Activity.class);
        owner.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat);
        owner.setup();
        return owner;
    }
    private static final class MenuPresenter extends AppDialogPresenter {
        final Context context;
        final List<OptionItem> switches = new ArrayList<>();
        final List<OptionItem> buttons = new ArrayList<>();
        final List<CharSequence> radioCategories = new ArrayList<>();
        MenuPresenter(Context context) { super(context); this.context = context; }
        @Override public Context getContext() { return context; }
        @Override public void appendSingleSwitch(OptionItem item) { switches.add(item); }
        @Override public void appendSingleButton(OptionItem item) { buttons.add(item); }
        @Override public void appendRadioCategory(CharSequence title, List<OptionItem> items) { radioCategories.add(title); }
        @Override public void showDialog(CharSequence title) { }
        void clearCaptured() { switches.clear(); buttons.clear(); radioCategories.clear(); }
    }

    @Test
    public void realAccountNotificationCannotReuseCompletedStringsWhenNativeSettingsBucketAndBridgeStayTheSame() throws Exception {
        try (SharedBucketScenario s = new SharedBucketScenario()) {
            CaptionOutputBridge sameBridge = s.controller.captionBridge;
            Object oldOwner = ReflectionHelpers.getField(s.controller, "mOwner");
            TranslationCoordinator.Frame oldFrame = s.playback.coordinator.prepareFrame();
            SharedPreferences bucket = ReflectionHelpers.getField(s.ownedConnection(), "mPreferences");
            Map<String, ?> connectionBefore = new HashMap<>(bucket.getAll());
            String scopeBefore = s.appPrefs.getProfileScope();
            assertFalse(s.appPrefs.isMultiProfilesEnabled());
            assertEquals(1, s.playback.calls());

            s.appPrefs.onAccountChanged(account("synthetic-cache-account-b", 2));
            assertEquals("Default native settings are still in the shared bucket", scopeBefore, s.appPrefs.getProfileScope());
            assertNotSame(oldOwner, ReflectionHelpers.getField(s.controller, "mOwner"));
            assertSame(sameBridge, s.controller.getPlayer().getCaptionOutputBridge());
            assertFalse(s.playback.coordinator.deliver(oldFrame, ignored -> fail("Old account Frame must remain invalid")));
            s.awaitSettings();
            SharedPreferences reloadedBucket = ReflectionHelpers.getField(s.ownedConnection(), "mPreferences");
            assertSame("This must exercise the same SharedPreferences identity and its State cache", bucket, reloadedBucket);
            assertEquals("Account isolation must not erase or rewrite shared connection configuration", connectionBefore, bucket.getAll());
            CaptionConnectionStore.Snapshot loaded = ReflectionHelpers.getField(s.controller, "mConnection");
            assertTrue(loaded.valid);
            assertTrue(loaded.hasKey);
            assertEquals("zh", ((CaptionPreferences.Global) ReflectionHelpers.getField(s.controller, "mGlobal")).targetLanguage);
            s.playback.emit(); s.playback.complete();
            assertEquals("source\n译:source", s.playback.current().get(0).text.toString());
            assertEquals("A changed account owner must not hydrate a completed String from the previous owner", 2, s.playback.calls());
            assertFalse(s.playback.coordinator.deliver(oldFrame, ignored -> fail()));
        }
    }

    @Test
    public void sameAccountStoreCloseReloadAndRealTargetRoundTripKeepCompletedStrings() throws Exception {
        try (SharedBucketScenario s = new SharedBucketScenario()) {
            Object accountOwner = ReflectionHelpers.getField(s.controller, "mOwner");
            CaptionConnectionStore previous = s.ownedConnection();
            SharedPreferences bucket = ReflectionHelpers.getField(previous, "mPreferences");
            previous.close();
            CaptionConnectionStore reopened = new CaptionConnectionStore(s.controller.context, s.appPrefs.getProfileScope());
            CaptionConnectionStore.Snapshot reloaded = reopened.load();
            assertSame(bucket, ReflectionHelpers.getField(reopened, "mPreferences"));
            assertTrue(reloaded.hasKey);
            ReflectionHelpers.setField(accountOwner, "connection", reopened);
            ReflectionHelpers.setField(s.controller, "mConnection", reloaded);
            ReflectionHelpers.callInstanceMethod(s.controller, "applyBridge");
            s.playback.complete();
            assertEquals(1, s.playback.calls());
            assertEquals("source\n译:source", s.playback.current().get(0).text.toString());

            s.changeTarget("ja");
            assertEquals(2, s.playback.calls());
            s.changeTarget("zh");
            assertSame(accountOwner, ReflectionHelpers.getField(s.controller, "mOwner"));
            assertEquals("Same account zh -> ja -> zh is still a completed-cache hit", 2, s.playback.calls());
            assertEquals("source\n译:source", s.playback.current().get(0).text.toString());
        }
    }

    private static final class SharedBucketScenario implements AutoCloseable {
        final Harness controller;
        final CaptionOutputBridgeTest.AccountPlayback playback;
        final AppPrefs appPrefs;
        final CaptionPreferenceStore seedPreferences;
        final CaptionConnectionStore seedConnection;
        final boolean previousMultiProfiles;
        final String previousProfileName;

        SharedBucketScenario() throws Exception {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).pause();
            controller = new Harness(false);
            playback = new CaptionOutputBridgeTest.AccountPlayback();
            controller.captionBridge = playback.bridge;
            controller.captionFormat = ExoFormatItem.from(playback.format());
            appPrefs = AppPrefs.instance(controller.context);
            previousMultiProfiles = appPrefs.isMultiProfilesEnabled();
            previousProfileName = ReflectionHelpers.callInstanceMethod(appPrefs, "getProfileName");
            appPrefs.enableMultiProfiles(false);
            appPrefs.onAccountChanged(account("synthetic-cache-account-a", 1));
            String scope = appPrefs.getProfileScope();
            seedPreferences = new CaptionPreferenceStore(controller.context, scope, new CaptionPreferenceStore.Limits(8192, 1024 * 1024));
            CaptionPreferenceStore.Snapshot preferences = seedPreferences.updateGlobal(seedPreferences.load(),
                    new CaptionPreferences.Global("zh", NativeCueComposer.Mode.BILINGUAL,
                            NativeCueComposer.Order.ORIGINAL_FIRST, CaptionConnection.PRIMARY));
            assertNotNull(preferences);
            assertNotNull(seedPreferences.updateChannel(preferences, "synthetic-owner-channel",
                    new CaptionPreferences.Channel(true, null, CaptionSource.TextKind.UNKNOWN)));
            seedConnection = new CaptionConnectionStore(controller.context, scope);
            assertNotNull(seedConnection.save(seedConnection.load(), CaptionConnection.basic(
                    "https://example.invalid/chat/completions", "synthetic-owner-model", 1000,
                    ChatCompletionAdapter.Thinking.OMIT), CaptionConnectionStore.KeyEdit.REPLACE, "synthetic-account-boundary-key"));
            controller.onInit();
            awaitSettings();
            Video video = video("synthetic-owner-video");
            video.channelId = "synthetic-owner-channel";
            controller.onNewVideo(video);
            controller.onSourceChanged(video);
            controller.onVideoLoaded(video);
            playback.emit(); playback.complete();
            assertEquals(1, playback.calls());
            assertEquals("source\n译:source", playback.current().get(0).text.toString());
        }

        CaptionConnectionStore ownedConnection() {
            return ReflectionHelpers.getField(ReflectionHelpers.getField(controller, "mOwner"), "connection");
        }
        void awaitSettings() throws Exception {
            awaitControllerIo(controller);
            assertNotNull(ReflectionHelpers.getField(controller, "mConnection"));
        }
        void changeTarget(String target) throws Exception {
            Class<?> changeType = Class.forName(CaptionSettingsController.class.getName() + "$GlobalChange");
            Object change = Proxy.newProxyInstance(changeType.getClassLoader(), new Class<?>[]{changeType}, (proxy, method, args) -> {
                if (!method.getName().equals("apply")) throw new AssertionError("Unexpected synthetic target callback");
                CaptionPreferences.Global before = (CaptionPreferences.Global) args[0];
                return new CaptionPreferences.Global(target, before.mode, before.order, before.connectionId);
            });
            java.lang.reflect.Method update = CaptionSettingsController.class.getDeclaredMethod("changeGlobal", changeType);
            update.setAccessible(true);
            update.invoke(controller, change);
            awaitSettings();
            playback.complete();
        }
        public void close() {
            controller.onFinish();
            playback.close();
            seedPreferences.close();
            seedConnection.close();
            ReflectionHelpers.callInstanceMethod(appPrefs, "setProfileName", ReflectionHelpers.ClassParameter.from(String.class, previousProfileName));
            appPrefs.enableMultiProfiles(previousMultiProfiles);
        }
    }

    private static Account account(String name, int id) {
        return new Account() {
            public int getId() { return id; }
            public String getName() { return name; }
            public String getEmail() { return "synthetic@example.invalid"; }
            public String getAvatarImageUrl() { return null; }
            public boolean isSelected() { return true; }
            public boolean isEmpty() { return false; }
        };
    }

    private static Video video(String id) { Video video = new Video(); video.videoId = id; return video; }
    private static MediaItemMetadata metadata(String id) {
        return (MediaItemMetadata) Proxy.newProxyInstance(MediaItemMetadata.class.getClassLoader(), new Class<?>[]{MediaItemMetadata.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getVideoId")) return id;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == long.class) return 0L;
                    return null;
                });
    }
    private static final class Harness extends CaptionSettingsController {
        Context context = RuntimeEnvironment.getApplication();
        AppDialogPresenter dialogPresenter;
        CaptionOutputBridge captionBridge;
        FormatItem captionFormat;
        final FormatItem english = ExoFormatItem.from(Format.createTextSampleFormat("synthetic-en", "text/vtt", 0, "English display name"));
        final PlaybackView player = (PlaybackView) Proxy.newProxyInstance(PlaybackView.class.getClassLoader(), new Class<?>[]{PlaybackView.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getCaptionOutputBridge")) return captionBridge;
                    if (method.getName().equals("getSubtitleFormats")) return Collections.singletonList(captionFormat == null ? english : captionFormat);
                    if (method.getName().equals("getSubtitleFormat")) return captionFormat == null ? FormatItem.SUBTITLE_NONE : captionFormat;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == long.class) return 0L;
                    return null;
                });
        Harness() throws Exception { this(true); }
        Harness(boolean owner) throws Exception { if (owner) installOwner(); onViewCreated(); }
        void installOwner() throws Exception {
            Class<?> type = Class.forName(CaptionSettingsController.class.getName() + "$Owner");
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            ReflectionHelpers.setField(this, "mOwner", constructor.newInstance());
        }
        @Override public Context getContext() { return context; }
        @Override protected AppDialogPresenter getAppDialogPresenter() { return dialogPresenter; }
        @Override public PlaybackView getPlayer() { return player; }
    }
}
