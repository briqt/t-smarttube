package com.liskovsoft.smartyoutubetv2.common.subtitle.settings;

import android.app.Activity;
import android.text.method.PasswordTransformationMethod;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.CaptionConnection;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.CaptionConnectionStore;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.ChatCompletionAdapter;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 27)
public class CaptionConnectionDialogTest {
    private static final String ENDPOINT = "https://synthetic.invalid/v1/chat/completions";
    private static final String KEY = "synthetic-fixture-key-not-a-real-credential";

    @Test public void nativePasswordIsWriteOnlySecureAndDoesNotSaveViewState() {
        Harness h = new Harness(true);
        try {
            assertEquals("", h.key().getText().toString());
            assertTrue(h.key().getTransformationMethod() instanceof PasswordTransformationMethod);
            assertFalse(h.key().isSaveEnabled());
            assertTrue((h.dialog.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
            h.key().setText(KEY);
            h.dismiss();
            assertEquals("", h.key().getText().toString());
            assertEquals(0, h.calls);
        } finally { h.close(); }
    }
    @Test public void blankKeyMeansKeepAndDialogWaitsForActualSaveAck() {
        Harness h = new Harness(true);
        try {
            h.validFields(); h.ok();
            assertEquals(1, h.calls);
            assertEquals(CaptionConnectionStore.KeyEdit.KEEP, h.edit);
            assertNull(h.replacement);
            assertEquals(ENDPOINT, h.connection.wire.endpoint.toString());
            assertTrue(h.dialog.isShowing());
            assertFalse(h.dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
            h.completion.complete(false);
            assertTrue(h.dialog.isShowing());
            assertTrue(h.dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
            assertEquals(h.activity.getString(R.string.caption_ai_save_failed), ((TextView) h.dialog.findViewById(R.id.caption_ai_error)).getText().toString());
            h.ok(); h.completion.complete(true);
            assertFalse(h.dialog.isShowing());
        } finally { h.close(); }
    }
    @Test public void replacementIsExplicitAndAckOrCancelClearsEditorWithoutEchoingPreviousKey() {
        Harness h = new Harness(true);
        try {
            h.validFields(); h.key().setText(KEY); h.ok();
            assertEquals(CaptionConnectionStore.KeyEdit.REPLACE, h.edit);
            assertEquals(KEY, h.replacement);
            h.dismiss();
            assertEquals("", h.key().getText().toString());
            h.completion.complete(true); // A late ack may not reopen a cancelled dialog.
            assertFalse(h.dialog.isShowing());
        } finally { h.close(); }
    }
    @Test public void invalidEndpointModelTokenOrKeyNeverReachesPersistence() {
        Harness h = new Harness(false);
        try {
            h.validFields(); h.field(R.id.caption_ai_endpoint).setText("http://synthetic.invalid/insecure"); h.ok();
            assertEquals(0, h.calls);
            h.validFields(); h.field(R.id.caption_ai_model).setText(""); h.ok();
            assertEquals(0, h.calls);
            h.validFields(); h.field(R.id.caption_ai_output_tokens).setText("0"); h.ok();
            assertEquals(0, h.calls);
            h.validFields(); h.key().setText("synthetic key with spaces"); h.ok();
            assertEquals(0, h.calls);
            assertTrue(h.dialog.isShowing());
        } finally { h.close(); }
    }
    @Test public void obsoleteVideoOrProfileShowsThatTheDraftWasNotSubmitted() {
        Harness h = new Harness(false);
        try {
            h.validFields(); h.key().setText(KEY);
            h.current = false; h.ok();
            assertEquals(0, h.calls);
            assertTrue("An expired draft must not close as though it was saved", h.dialog.isShowing());
            assertEquals("", h.key().getText().toString());
            assertFalse(h.dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
            assertFalse(h.dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled());
            assertEquals(h.activity.getString(R.string.caption_ai_editor_expired), ((TextView) h.dialog.findViewById(R.id.caption_ai_error)).getText().toString());
            h.dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); h.drain();
            assertFalse(h.dialog.isShowing());
        } finally { h.close(); }
    }
    @Test public void profileChangeWhileConfirmingClearLeavesTheKeyUntouchedAndExplainsTheExpiredPage() {
        Harness h = new Harness(true);
        try {
            h.key().setText(KEY);
            h.dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick(); h.drain();
            AlertDialog confirmation = (AlertDialog) ShadowDialog.getLatestDialog();
            h.current = false;
            confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); h.drain();
            assertEquals(0, h.calls);
            assertTrue(h.dialog.isShowing());
            assertEquals("", h.key().getText().toString());
            assertFalse(h.dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
            assertFalse(h.dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled());
            assertEquals(h.activity.getString(R.string.caption_ai_editor_expired), ((TextView) h.dialog.findViewById(R.id.caption_ai_error)).getText().toString());
        } finally { h.close(); }
    }
    @Test public void clearKeyNeedsSeparateConfirmationAndDoesNotSaveUnrelatedDraftFields() {
        Harness h = new Harness(true);
        try {
            h.field(R.id.caption_ai_endpoint).setText("https://other.synthetic.invalid/completions");
            h.dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick(); h.drain();
            AlertDialog confirmation = (AlertDialog) ShadowDialog.getLatestDialog();
            assertNotSame(h.dialog, confirmation);
            confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); h.drain();
            assertEquals(0, h.calls);
            h.dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick(); h.drain();
            confirmation = (AlertDialog) ShadowDialog.getLatestDialog();
            confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); h.drain();
            assertEquals(1, h.calls);
            assertEquals(CaptionConnectionStore.KeyEdit.CLEAR, h.edit);
            assertNull(h.replacement);
            assertEquals(ENDPOINT, h.connection.wire.endpoint.toString());
            h.completion.complete(true);
            assertFalse(h.dialog.isShowing());
        } finally { h.close(); }
    }

    private static final class Harness {
        final ActivityController<Activity> owner;
        final Activity activity;
        final AlertDialog dialog;
        boolean current = true;
        int calls;
        CaptionConnection connection;
        CaptionConnectionStore.KeyEdit edit;
        String replacement;
        CaptionConnectionDialog.Completion completion;
        Harness(boolean previous) {
            owner = Robolectric.buildActivity(Activity.class);
            activity = owner.get();
            activity.setTheme(androidx.appcompat.R.style.Theme_AppCompat);
            owner.setup();
            CaptionConnectionStore.Snapshot snapshot = null;
            if (previous) {
                CaptionConnectionStore store = new CaptionConnectionStore(activity, "synthetic-native-dialog-" + System.nanoTime());
                snapshot = store.save(store.load(), CaptionConnection.basic(ENDPOINT, "synthetic-model", 1024,
                        ChatCompletionAdapter.Thinking.OMIT), CaptionConnectionStore.KeyEdit.REPLACE, KEY);
                assertNotNull(snapshot);
            }
            dialog = CaptionConnectionDialog.show(activity, snapshot, () -> current, (value, action, key, result) -> {
                calls++; connection = value; edit = action; replacement = key; completion = result;
            });
            drain();
        }
        EditText field(int id) { return dialog.findViewById(id); }
        EditText key() { return field(R.id.caption_ai_key); }
        void validFields() {
            field(R.id.caption_ai_endpoint).setText(ENDPOINT);
            field(R.id.caption_ai_model).setText("synthetic-model");
            field(R.id.caption_ai_output_tokens).setText("1024");
            key().setText("");
        }
        void drain() { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); }
        void ok() { dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); drain(); }
        void dismiss() { dialog.dismiss(); drain(); }
        void close() { dismiss(); owner.pause().stop().destroy(); }
    }
}
