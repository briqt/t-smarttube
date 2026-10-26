package com.liskovsoft.smartyoutubetv2.common.subtitle.settings;

import android.content.Context;
import android.os.Build;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.CaptionConnection;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.CaptionConnectionStore;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.ChatCompletionAdapter;

/** Native, write-only credential editor. No network test, Key echo, saved view state or LAN listener. */
public final class CaptionConnectionDialog {
    public interface Current { boolean current(); }
    public interface Completion { void complete(boolean saved); }
    public interface Save {
        void save(CaptionConnection connection, CaptionConnectionStore.KeyEdit edit, String key, Completion completion);
    }

    public static AlertDialog show(Context context, CaptionConnectionStore.Snapshot snapshot, Current current, Save save) {
        CaptionConnection previous = snapshot == null ? null : snapshot.connection;
        LinearLayout fields = new LinearLayout(context);
        fields.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * context.getResources().getDisplayMetrics().density);
        fields.setPadding(padding, padding, padding, padding);
        EditText endpoint = field(context, fields, R.string.caption_ai_endpoint, R.id.caption_ai_endpoint,
                previous == null ? "" : previous.wire.endpoint.toString(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText model = field(context, fields, R.string.caption_ai_model, R.id.caption_ai_model,
                previous == null ? "" : previous.wire.model, InputType.TYPE_CLASS_TEXT);
        EditText key = field(context, fields, R.string.caption_ai_key_hint, R.id.caption_ai_key, "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        key.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
        key.setSaveEnabled(false);
        if (Build.VERSION.SDK_INT >= 26) key.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        EditText tokens = field(context, fields, R.string.caption_ai_output_tokens, R.id.caption_ai_output_tokens,
                Integer.toString(previous == null ? 1024 : previous.wire.maxOutputTokens), InputType.TYPE_CLASS_NUMBER);
        CheckBox thinking = new CheckBox(context);
        thinking.setId(R.id.caption_ai_thinking);
        thinking.setText(R.string.caption_ai_deepseek_thinking);
        thinking.setChecked(previous != null && previous.wire.thinking == ChatCompletionAdapter.Thinking.DEEPSEEK_DISABLED);
        fields.addView(thinking);
        TextView error = new TextView(context);
        error.setId(R.id.caption_ai_error);
        error.setText(R.string.caption_ai_connection_notice);
        fields.addView(error);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(fields);
        AlertDialog dialog = new AlertDialog.Builder(context, R.style.AppDialog).setTitle(R.string.caption_ai_connection)
                .setView(scroll).setPositiveButton(android.R.string.ok, null).setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.caption_ai_clear_key, null).create();
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.setOnDismissListener(ignored -> key.getText().clear());
        Runnable expired = () -> {
            key.getText().clear();
            error.setText(R.string.caption_ai_editor_expired);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(false);
        };
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
                if (!current.current()) { expired.run(); return; }
                CaptionConnection connection;
                try {
                    ChatCompletionAdapter.Config wire = new ChatCompletionAdapter.Config(endpoint.getText().toString().trim(), model.getText().toString().trim(),
                            Integer.parseInt(tokens.getText().toString()), previous == null ? 262144 : previous.wire.requestBytes,
                            previous == null ? 262144 : previous.wire.responseBytes,
                            previous == null ? ChatCompletionAdapter.TokenField.MAX_TOKENS : previous.wire.tokenField,
                            thinking.isChecked() ? ChatCompletionAdapter.Thinking.DEEPSEEK_DISABLED : ChatCompletionAdapter.Thinking.OMIT);
                    CaptionConnection basic = CaptionConnection.basic(wire.endpoint.toString(), wire.model, wire.maxOutputTokens, wire.thinking);
                    connection = new CaptionConnection(wire, previous == null ? basic.batch : previous.batch, previous == null ? basic.maxCalls : previous.maxCalls);
                } catch (RuntimeException invalid) { error.setText(R.string.caption_ai_invalid_connection); return; }
                String replacement = key.getText().toString();
                if (!replacement.isEmpty() && !validKey(replacement)) { error.setText(R.string.caption_ai_invalid_connection); return; }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(false);
                error.setText(R.string.caption_ai_saving);
                save.save(connection, replacement.isEmpty() ? CaptionConnectionStore.KeyEdit.KEEP : CaptionConnectionStore.KeyEdit.REPLACE,
                        replacement.isEmpty() ? null : replacement, saved -> {
                            if (!dialog.isShowing()) return;
                            if (!current.current()) { dialog.dismiss(); return; }
                            if (saved) dialog.dismiss();
                            else {
                                error.setText(R.string.caption_ai_save_failed);
                                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(previous != null);
                            }
                        });
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(previous != null);
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(button -> {
                if (!current.current()) { expired.run(); return; }
                new AlertDialog.Builder(context, R.style.AppDialog).setTitle(R.string.caption_ai_clear_key)
                        .setMessage(R.string.caption_ai_clear_key_confirm).setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok, (confirmation, which) -> {
                            if (!current.current()) { expired.run(); return; }
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(false);
                            save.save(previous, CaptionConnectionStore.KeyEdit.CLEAR, null, saved -> {
                                if (!dialog.isShowing()) return;
                                if (saved || !current.current()) dialog.dismiss();
                                else {
                                    error.setText(R.string.caption_ai_save_failed);
                                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(true);
                                }
                            });
                        }).show();
            });
        });
        dialog.show();
        return dialog;
    }

    private static EditText field(Context context, LinearLayout fields, int title, int id, String value, int type) {
        TextView label = new TextView(context);
        label.setText(title);
        fields.addView(label);
        EditText input = new EditText(context);
        input.setId(id);
        input.setInputType(type);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(
                id == R.id.caption_ai_key ? 8192 : id == R.id.caption_ai_model ? 256 : id == R.id.caption_ai_output_tokens ? 7 : 4096)});
        input.setSingleLine(true);
        input.setSaveEnabled(false);
        input.setHint(title);
        input.setText(value);
        fields.addView(input);
        return input;
    }
    private static boolean validKey(String key) {
        if (key.length() > 8192) return false;
        for (int i = 0; i < key.length(); i++) if (key.charAt(i) < 33 || key.charAt(i) > 126) return false;
        return true;
    }
    private CaptionConnectionDialog() { }
}
