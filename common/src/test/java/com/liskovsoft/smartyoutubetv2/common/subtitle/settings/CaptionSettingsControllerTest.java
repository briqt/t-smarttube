package com.liskovsoft.smartyoutubetv2.common.subtitle.settings;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import androidx.appcompat.app.AlertDialog;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.CaptionConnection;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.CaptionConnectionStore;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.CaptionPreferenceStore;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.ChatCompletionAdapter;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.CaptionPreferences;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.TranslationCoordinator;
import com.liskovsoft.smartyoutubetv2.common.subtitle.presentation.CaptionOutputBridge;
import com.liskovsoft.smartyoutubetv2.common.subtitle.presentation.CaptionOutputBridgeTest;
import com.liskovsoft.smartyoutubetv2.common.subtitle.presentation.NativeCueComposer;
import com.liskovsoft.smartyoutubetv2.common.subtitle.source.CaptionSource;
import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs;
import com.liskovsoft.mediaserviceinterfaces.oauth.Account;
import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import com.google.android.exoplayer2.Format;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.ExoFormatItem;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLog;
import java.util.ArrayList;
import java.util.List;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.Collections;
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
        h.onNewVideo(a); // The same Video instance is allowed to be opened again; the token must still change.
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
    @Test public void sourceChangeDoesNotClosePairingButEngineViewAndFinishDoWithoutOpeningSockets() throws Exception {
        Harness h = new Harness();
        try {
            CaptionPairingDialog first = unshownPairing(h.context);
            ReflectionHelpers.setField(h, "mPairing", first);
            CaptionPairingDialog.Session session = ReflectionHelpers.getField(first, "mSession");
            h.onNewVideo(video("synthetic-b"));
            assertTrue(session.current());
            h.onEngineReleased();
            assertFalse(session.current());
            CaptionPairingDialog second = unshownPairing(h.context);
            ReflectionHelpers.setField(h, "mPairing", second);
            session = ReflectionHelpers.getField(second, "mSession");
            h.onViewDestroyed(); assertFalse(session.current());
            CaptionPairingDialog third = unshownPairing(h.context);
            ReflectionHelpers.setField(h, "mPairing", third);
            session = ReflectionHelpers.getField(third, "mSession");
            h.onFinish(); assertFalse(session.current());
        } finally { h.onFinish(); }
    }
    private static CaptionPairingDialog unshownPairing(Context context) throws Exception {
        Constructor<CaptionPairingDialog> constructor = CaptionPairingDialog.class.getDeclaredConstructor(
                Context.class, CaptionPairingDialog.Active.class, CaptionPairingDialog.Host.class);
        constructor.setAccessible(true);
        return constructor.newInstance(context, (CaptionPairingDialog.Active) () -> true, new CaptionPairingDialog.Host() {
            @Override public void read(CaptionPairingDialog.Read completion) { fail("Unshown windows do not read"); }
            @Override public void save(com.liskovsoft.smartyoutubetv2.common.subtitle.translation.CaptionConnectionEdit edit,
                    CaptionPairingDialog.Session session, CaptionPairingDialog.Saved completion) { fail("Unshown windows do not save"); }
            @Override public com.liskovsoft.smartyoutubetv2.common.subtitle.translation.ChatTranslationClient.Job probe(
                    com.liskovsoft.smartyoutubetv2.common.subtitle.translation.CaptionConnectionEdit edit, CaptionConnectionStore.Snapshot snapshot) {
                throw new AssertionError("Unshown windows do not probe");
            }
        });
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
    @Test public void connectionEditorUsesTheNativeMenuActivityNotTheCoveredPlaybackActivity() throws Exception {
        assertEditorOwner(R.string.caption_ai_connection);
    }
    @Test public void connectionKeyStateIsVisibleInTheNativeButtonTitleWithoutExposingTheKey() throws Exception {
        ActivityController<Activity> playback = activity(), menu = activity();
        Harness h = new Harness();
        h.context = playback.get();
        MenuPresenter presenter = new MenuPresenter(menu.get());
        h.dialogPresenter = presenter;
        CaptionPreferenceStore preferences = new CaptionPreferenceStore(playback.get(), "synthetic-visible-status", new CaptionPreferenceStore.Limits(32, 65536));
        CaptionConnectionStore connection = new CaptionConnectionStore(playback.get(), "synthetic-visible-status");
        try {
            ReflectionHelpers.setField(h, "mPreferences", preferences.load());
            ReflectionHelpers.setField(h, "mConnection", connection.load());
            h.onNewVideo(video("synthetic-a"));
            ReflectionHelpers.callInstanceMethod(h, "showDetails");
            OptionItem row = presenter.buttons.stream().filter(item -> item.getTitle().toString().startsWith(menu.get().getString(R.string.caption_ai_connection))).findFirst().get();
            assertTrue("Native singleButton only renders its title", row.getTitle().toString().contains(menu.get().getString(R.string.caption_ai_key_missing)));
            String key = "synthetic-visible-status-not-a-real-key";
            CaptionConnectionStore.Snapshot saved = connection.save(connection.load(), CaptionConnection.basic(
                    "https://synthetic.invalid/v1/chat/completions", "synthetic-model", 1024, ChatCompletionAdapter.Thinking.OMIT), CaptionConnectionStore.KeyEdit.REPLACE, key);
            assertNotNull(saved);
            ReflectionHelpers.setField(h, "mConnection", saved);
            presenter.buttons.clear();
            ReflectionHelpers.callInstanceMethod(h, "showDetails");
            row = presenter.buttons.stream().filter(item -> item.getTitle().toString().startsWith(menu.get().getString(R.string.caption_ai_connection))).findFirst().get();
            int status = saved.persistentKey ? R.string.caption_ai_key_saved : R.string.caption_ai_key_memory;
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
        MenuPresenter presenter = new MenuPresenter(menu.get());
        h.dialogPresenter = presenter;
        CaptionPreferenceStore preferences = new CaptionPreferenceStore(playback.get(), "synthetic-menu-window", new CaptionPreferenceStore.Limits(32, 65536));
        CaptionConnectionStore connection = new CaptionConnectionStore(playback.get(), "synthetic-menu-window");
        AlertDialog editor = null;
        try {
            ReflectionHelpers.setField(h, "mPreferences", preferences.load());
            ReflectionHelpers.setField(h, "mConnection", connection.load());
            h.onNewVideo(video("synthetic-a"));
            ReflectionHelpers.callInstanceMethod(h, "showDetails");
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
    private static ActivityController<Activity> activity() {
        ActivityController<Activity> owner = Robolectric.buildActivity(Activity.class);
        owner.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat);
        owner.setup();
        return owner;
    }
    private static final class MenuPresenter extends AppDialogPresenter {
        final Context context;
        final List<OptionItem> buttons = new ArrayList<>();
        MenuPresenter(Context context) { super(context); this.context = context; }
        @Override public Context getContext() { return context; }
        @Override public void appendSingleButton(OptionItem item) { buttons.add(item); }
        @Override public void appendRadioCategory(CharSequence title, List<OptionItem> items) { }
        @Override public void showDialog(CharSequence title) { }
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

            // Actual AppPrefs callback and actual controller listener, not swapping two unrelated Store fixtures.
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
            previous.close(); // A blanket close()->revoke workaround must not break same-account reload.
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

    /** Account notification keeps this host/bridge alive, as the controller's actual profile callback permits. */
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
            ThreadPoolExecutor io = ReflectionHelpers.getField(controller, "mIo");
            CountDownLatch ready = new CountDownLatch(1);
            io.execute(ready::countDown);
            assertTrue("Settings work must finish before its UI completion is drained", ready.await(5, TimeUnit.SECONDS));
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
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
