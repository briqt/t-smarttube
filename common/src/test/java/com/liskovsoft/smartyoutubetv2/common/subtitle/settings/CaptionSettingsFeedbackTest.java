package com.liskovsoft.smartyoutubetv2.common.subtitle.settings;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.widget.EditText;
import androidx.appcompat.app.AlertDialog;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.CaptionConnection;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.CaptionConnectionStore;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.CaptionPreferenceStore;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.ChatCompletionAdapter;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.util.ReflectionHelpers;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Real native editors and stores, with explicit worker/ack ordering and synthetic credentials only. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 27)
public class CaptionSettingsFeedbackTest {
    private static final String ENDPOINT = "https://synthetic.invalid/v1/chat/completions";
    private static final String KEY = "synthetic-feedback-not-a-real-key";

    @Test public void targetAndSaveStateUpdateTheExistingRowsOnlyAfterTheActualAck() throws Exception {
        try (Harness h = new Harness()) {
            h.target("fr");
            assertTrue(h.targetTitle().endsWith(": fr"));
            assertEquals(h.text(R.string.caption_ai_saving), h.stateTitle());
            assertNull(h.preferences.load().preferences.global.targetLanguage);
            assertEquals(1, h.menu.shows);
            h.work();
            assertEquals("fr", h.preferences.load().preferences.global.targetLanguage);
            assertEquals(h.text(R.string.caption_ai_saved), h.stateTitle());
            assertEquals(1, h.menu.shows);
        }
    }
    @Test public void failedDiskCommitStaysVisibleAndExplicitRetryRestoresThenSavesThePendingTarget() throws Exception {
        try (Harness h = new Harness()) {
            h.failCommit = true;
            h.target("fr"); h.work();
            assertEquals(h.text(R.string.caption_ai_save_failed), h.stateTitle());
            assertTrue(h.targetTitle().endsWith(": fr"));
            assertFalse(h.preferences.load().valid);
            h.failCommit = false;
            h.button(R.string.caption_ai_retry).onSelect(true);
            assertEquals(h.text(R.string.caption_ai_saving), h.stateTitle());
            h.work(); // Recover last-good, then enqueue the pending target write.
            assertEquals(h.text(R.string.caption_ai_saving), h.stateTitle());
            h.work();
            assertEquals(h.text(R.string.caption_ai_saved), h.stateTitle());
            assertEquals("fr", h.preferences.load().preferences.global.targetLanguage);
            assertEquals(1, h.menu.shows);
        }
    }
    @Test public void aRejectedWorkerDoesNotLeaveTheDetailRowsStuckAtSaving() throws Exception {
        try (Harness h = new Harness()) {
            h.worker.reject = true;
            h.target("fr");
            assertEquals(h.text(R.string.caption_ai_save_failed), h.stateTitle());
            assertEquals(0, (int) ReflectionHelpers.getField(h.controller, "mGlobalSaves"));
            assertEquals(1, h.menu.shows);
        }
    }
    @Test public void globalAckUpdatesItsExistingRowsAcrossSourceChangesWithoutReopeningAMenu() throws Exception {
        try (Harness h = new Harness()) {
            h.target("fr");
            Video next = new Video(); next.videoId = "synthetic-next"; next.channelId = "synthetic-next-channel";
            h.controller.onNewVideo(next);
            h.work();
            assertEquals(h.text(R.string.caption_ai_saved), h.stateTitle());
            assertEquals(1, h.menu.shows);
            assertEquals("fr", h.preferences.load().preferences.global.targetLanguage);
        }
    }
    @Test public void globalConnectionCanOpenAndSaveAcrossSourceChangesWhileChannelTokensExpire() throws Exception {
        try (Harness h = new Harness()) {
            Object channelToken = h.controller.menuToken();
            Video reload = new Video(); reload.videoId = "synthetic-video"; reload.channelId = "synthetic-channel";
            h.controller.onNewVideo(reload);
            assertFalse(h.controller.menuCurrent(channelToken));
            AlertDialog editor = h.connectionEditor();
            assertNotNull("Global connection must remain editable after source replacement", editor);
            assertTrue(editor.isShowing());
            h.fill(editor, R.id.caption_ai_endpoint, ENDPOINT);
            h.fill(editor, R.id.caption_ai_model, "synthetic-model");
            h.controller.onNewVideo(reload);
            h.ok(editor);
            assertEquals(1, h.worker.pending.size());
            h.work();
            assertFalse(editor.isShowing());
            assertNotNull(h.connection.load().connection);
            assertFalse(h.connection.load().hasKey);
            assertEquals(h.text(R.string.caption_ai_saved), h.stateTitle());
            assertEquals(1, h.menu.shows);
        }
    }
    @Test public void aChangedOwnerStillRejectsTheOldGlobalEditorBeforeSubmitting() throws Exception {
        try (Harness h = new Harness()) {
            AlertDialog editor = h.connectionEditor();
            h.fill(editor, R.id.caption_ai_endpoint, ENDPOINT);
            h.fill(editor, R.id.caption_ai_model, "synthetic-model");
            Object oldOwner = ReflectionHelpers.getField(h.controller, "mOwner");
            ReflectionHelpers.setField(oldOwner, "active", false);
            Constructor<?> constructor = oldOwner.getClass().getDeclaredConstructor();
            constructor.setAccessible(true);
            ReflectionHelpers.setField(h.controller, "mOwner", constructor.newInstance());
            h.ok(editor);
            assertTrue(editor.isShowing());
            assertEquals(h.text(R.string.caption_ai_editor_expired), ((android.widget.TextView) editor.findViewById(R.id.caption_ai_error)).getText().toString());
            assertTrue(h.worker.pending.isEmpty());
            assertNull(h.connection.load().connection);
        }
    }
    @Test public void engineRebuildBeforeSubmitKeepsTheExpiredDraftVisibleWithoutSaving() throws Exception {
        try (Harness h = new Harness()) {
            CaptionConnectionStore.Snapshot before = h.connection.load();
            AlertDialog editor = h.connectionEditor();
            h.fill(editor, R.id.caption_ai_endpoint, ENDPOINT);
            h.fill(editor, R.id.caption_ai_model, "synthetic-model");
            h.fill(editor, R.id.caption_ai_key, KEY);
            h.controller.onEngineReleased();
            h.controller.onEngineInitialized();
            h.ok(editor);
            assertTrue("Closing an unsubmitted draft looks like a successful save", editor.isShowing());
            assertEquals(h.text(R.string.caption_ai_editor_expired), ((android.widget.TextView) editor.findViewById(R.id.caption_ai_error)).getText().toString());
            assertEquals("", ((EditText) editor.findViewById(R.id.caption_ai_key)).getText().toString());
            assertFalse(editor.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
            assertTrue(h.worker.pending.isEmpty());
            assertEquals(before.revision, h.connection.load().revision);
            assertNull(h.connection.load().connection);
            assertEquals(1, h.menu.shows);
        }
    }
    @Test public void cancellingTheConnectionEditorDoesNotLetItsLateAckReopenIt() throws Exception {
        try (Harness h = new Harness()) {
            AlertDialog editor = h.connectionEditor();
            h.fill(editor, R.id.caption_ai_endpoint, ENDPOINT);
            h.fill(editor, R.id.caption_ai_model, "synthetic-model");
            h.fill(editor, R.id.caption_ai_key, KEY);
            h.ok(editor);
            assertEquals(h.text(R.string.caption_ai_saving), h.stateTitle());
            assertTrue(h.connectionTitle().contains(h.text(R.string.caption_ai_saving)));
            editor.dismiss(); h.drain();
            h.work();
            assertFalse(editor.isShowing());
            assertEquals(h.text(R.string.caption_ai_saved), h.stateTitle());
            CaptionConnectionStore.Snapshot saved = h.connection.load();
            assertTrue(saved.hasKey);
            assertTrue(h.connectionTitle().contains(h.text(saved.persistentKey ? R.string.caption_ai_key_saved : R.string.caption_ai_key_memory)));
            assertFalse(h.connectionTitle().contains(KEY));
            assertEquals(1, h.menu.shows);
        }
    }
    @Test public void connectionRejectionAndSameEditorRetryUpdateTheBoundStatusWithoutRebuildingThePage() throws Exception {
        try (Harness h = new Harness(true)) {
            CaptionConnectionStore.Snapshot original = h.connection.load();
            AlertDialog editor = h.connectionEditor();
            h.fill(editor, R.id.caption_ai_endpoint, "https://other.synthetic.invalid/v1/chat/completions");
            h.ok(editor); h.work();
            assertTrue(editor.isShowing());
            assertEquals(h.text(R.string.caption_ai_save_failed), h.stateTitle());
            assertTrue(h.connectionTitle().contains(h.text(R.string.caption_ai_save_failed)));
            assertEquals(original.revision, h.connection.load().revision);
            h.fill(editor, R.id.caption_ai_key, KEY + "-replacement");
            h.ok(editor); h.work();
            assertFalse(editor.isShowing());
            assertEquals(h.text(R.string.caption_ai_saved), h.stateTitle());
            assertFalse(h.connectionTitle().contains(h.text(R.string.caption_ai_save_failed)));
            assertEquals(1, h.menu.shows);
        }
    }

    private static final class Worker extends ThreadPoolExecutor {
        final ArrayDeque<Runnable> pending = new ArrayDeque<>();
        boolean reject;
        Worker() { super(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32)); }
        @Override public void execute(Runnable task) {
            if (reject) throw new RejectedExecutionException("Synthetic full worker");
            pending.add(task);
        }
        void next() { assertFalse("Expected one queued settings operation", pending.isEmpty()); pending.remove().run(); }
    }
    private static final class Menu extends AppDialogPresenter {
        final Context context;
        final List<OptionItem> buttons = new ArrayList<>();
        final Map<OptionItem, String> titles = new IdentityHashMap<>();
        int shows, changes;
        Menu(Context context) { super(context); this.context = context; }
        @Override public Context getContext() { return context; }
        @Override public void appendSingleButton(OptionItem item) {
            buttons.add(item); titles.put(item, item.getTitle().toString());
            item.setOnTitleChanged(() -> { changes++; titles.put(item, item.getTitle().toString()); });
        }
        @Override public void appendRadioCategory(CharSequence title, List<OptionItem> items) { }
        @Override public void showDialog(CharSequence title) { shows++; }
    }
    private static final class Controller extends CaptionSettingsController {
        final Context context;
        final Menu menu;
        final PlaybackView player = (PlaybackView) Proxy.newProxyInstance(PlaybackView.class.getClassLoader(), new Class<?>[]{PlaybackView.class},
                (proxy, method, args) -> {
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == long.class) return 0L;
                    return null;
                });
        Controller(Context context, Menu menu) { this.context = context; this.menu = menu; }
        @Override public Context getContext() { return context; }
        @Override public PlaybackView getPlayer() { return player; }
        @Override protected AppDialogPresenter getAppDialogPresenter() { return menu; }
    }
    private static final class Harness implements AutoCloseable {
        final ActivityController<Activity> playback = activity(), menuActivity = activity();
        final Menu menu = new Menu(menuActivity.get());
        final Controller controller = new Controller(playback.get(), menu);
        final Worker worker = new Worker();
        final CaptionPreferenceStore preferences;
        final CaptionConnectionStore connection;
        boolean failCommit;
        Harness() throws Exception { this(false); }
        Harness(boolean existingConnection) throws Exception {
            String scope = "synthetic-feedback-" + System.nanoTime();
            SharedPreferences backing = playback.get().getSharedPreferences(scope, Context.MODE_PRIVATE);
            SharedPreferences faulting = (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                    (proxy, method, args) -> {
                        if (!method.getName().equals("edit")) return invoke(method, backing, args);
                        SharedPreferences.Editor editor = backing.edit();
                        return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class},
                                (editProxy, editMethod, editArgs) -> {
                                    if (editMethod.getName().equals("commit") && failCommit) { editor.commit(); return false; }
                                    Object result = invoke(editMethod, editor, editArgs);
                                    return result instanceof SharedPreferences.Editor ? editProxy : result;
                                });
                    });
            Constructor<CaptionPreferenceStore> store = CaptionPreferenceStore.class.getDeclaredConstructor(SharedPreferences.class, CaptionPreferenceStore.Limits.class);
            store.setAccessible(true);
            preferences = store.newInstance(faulting, new CaptionPreferenceStore.Limits(32, 65536));
            connection = new CaptionConnectionStore(playback.get(), scope);
            if (existingConnection) assertNotNull(connection.save(connection.load(), CaptionConnection.basic(
                    ENDPOINT, "synthetic-model", 1024, ChatCompletionAdapter.Thinking.OMIT), CaptionConnectionStore.KeyEdit.REPLACE, KEY));
            Class<?> type = Class.forName(CaptionSettingsController.class.getName() + "$Owner");
            Constructor<?> ownerConstructor = type.getDeclaredConstructor(); ownerConstructor.setAccessible(true);
            Object owner = ownerConstructor.newInstance();
            ReflectionHelpers.setField(owner, "preferences", preferences);
            ReflectionHelpers.setField(owner, "connection", connection);
            ReflectionHelpers.setField(controller, "mOwner", owner);
            ReflectionHelpers.setField(controller, "mIo", worker);
            ReflectionHelpers.setField(controller, "mPreferences", preferences.load());
            ReflectionHelpers.setField(controller, "mConnection", connection.load());
            controller.onViewCreated();
            Video video = new Video(); video.videoId = "synthetic-video"; video.channelId = "synthetic-channel";
            controller.onNewVideo(video);
            ReflectionHelpers.callInstanceMethod(controller, "showDetails");
        }
        String text(int id) { return menu.context.getString(id); }
        OptionItem button(int title) { return menu.buttons.stream().filter(item -> item.getTitle().toString().startsWith(text(title))).findFirst().get(); }
        String stateTitle() { return menu.titles.get(menu.buttons.get(0)); }
        String targetTitle() { return menu.titles.get(button(R.string.caption_ai_target)); }
        String connectionTitle() { return menu.titles.get(button(R.string.caption_ai_connection)); }
        void drain() { org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle(); }
        void work() { worker.next(); drain(); }
        void target(String value) {
            button(R.string.caption_ai_target).onSelect(true); drain();
            AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            fill(dialog, R.id.simple_edit_value, value); ok(dialog);
        }
        AlertDialog connectionEditor() {
            button(R.string.caption_ai_connection).onSelect(true); drain();
            return (AlertDialog) ShadowDialog.getLatestDialog();
        }
        void fill(AlertDialog dialog, int id, String value) { ((EditText) dialog.findViewById(id)).setText(value); }
        void ok(AlertDialog dialog) { dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); drain(); }
        @Override public void close() {
            android.app.Dialog dialog = ShadowDialog.getLatestDialog(); if (dialog != null) dialog.dismiss();
            controller.onFinish(); preferences.close(); connection.close();
            menuActivity.pause().stop().destroy(); playback.pause().stop().destroy();
        }
    }
    private static ActivityController<Activity> activity() {
        ActivityController<Activity> owner = Robolectric.buildActivity(Activity.class);
        owner.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat); owner.setup(); return owner;
    }
    private static Object invoke(Method method, Object receiver, Object[] args) throws Throwable {
        try { return method.invoke(receiver, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
}
