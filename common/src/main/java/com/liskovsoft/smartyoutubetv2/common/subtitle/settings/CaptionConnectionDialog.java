package com.liskovsoft.smartyoutubetv2.common.subtitle.settings;

import android.content.Context;
import android.os.Build;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.CaptionConnection;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.CaptionConnectionStore;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.CaptionModelPolicy;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.CaptionService;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.ChatCompletionAdapter;

/** Native, write-only credential editor. No network test, Key echo, saved view state or LAN listener. */
public final class CaptionConnectionDialog {
    public interface Current { boolean current(); }
    public interface Completion { void complete(boolean saved); }
    public interface Save {
        void save(CaptionConnection connection, CaptionConnectionStore.KeyEdit edit, String key, Completion completion);
    }

    private static final ChatCompletionAdapter.Thinking[] THINKING = {
            ChatCompletionAdapter.Thinking.AUTO_LOW, ChatCompletionAdapter.Thinking.OMIT,
            ChatCompletionAdapter.Thinking.DEEPSEEK_DISABLED
    };

    public static AlertDialog show(Context context, CaptionConnectionStore.Snapshot snapshot, Current current, Save save) {
        CaptionConnection previous = snapshot == null ? null : snapshot.connection;
        LinearLayout fields = new LinearLayout(context);
        fields.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * context.getResources().getDisplayMetrics().density);
        fields.setPadding(padding, padding, padding, padding);
        CaptionService[] selectedService = {previous == null ? CaptionService.CUSTOM_CHAT : previous.wire.service};
        Spinner service = selector(context, fields, R.string.caption_ai_service, R.id.caption_ai_service);
        choices(context, service, new int[]{R.string.caption_ai_service_custom, R.string.caption_ai_service_openai_chat,
                R.string.caption_ai_service_openai_responses, R.string.caption_ai_service_deepseek,
                R.string.caption_ai_service_claude, R.string.caption_ai_service_gemini});
        service.setSelection(selectedService[0].ordinal());
        note(context, fields, R.string.caption_ai_service_hint);
        EditText endpoint = field(context, fields, R.string.caption_ai_endpoint, R.id.caption_ai_endpoint,
                previous == null ? "" : previous.wire.endpoint.toString(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        TextView endpointHint = note(context, fields, R.string.caption_ai_gemini_endpoint_hint);
        endpointHint.setVisibility(selectedService[0] == CaptionService.GEMINI ? View.VISIBLE : View.GONE);
        EditText model = field(context, fields, R.string.caption_ai_model, R.id.caption_ai_model,
                previous == null ? "" : previous.wire.model, InputType.TYPE_CLASS_TEXT);
        LinearLayout workspaceFields = new LinearLayout(context);
        workspaceFields.setId(R.id.caption_ai_workspace_fields);
        workspaceFields.setOrientation(LinearLayout.VERTICAL);
        fields.addView(workspaceFields);
        EditText workspace = field(context, workspaceFields, R.string.caption_ai_workspace, R.id.caption_ai_workspace,
                previous == null || previous.wire.workspaceId == null ? "" : previous.wire.workspaceId,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        note(context, workspaceFields, R.string.caption_ai_workspace_hint);
        workspaceFields.setVisibility(selectedService[0] == CaptionService.CLAUDE ? View.VISIBLE : View.GONE);
        workspace.setEnabled(selectedService[0] == CaptionService.CLAUDE);
        EditText key = field(context, fields, R.string.caption_ai_key_hint, R.id.caption_ai_key, "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        key.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
        key.setSaveEnabled(false);
        if (Build.VERSION.SDK_INT >= 26) key.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        EditText tokens = field(context, fields, R.string.caption_ai_output_tokens, R.id.caption_ai_output_tokens,
                Integer.toString(previous == null ? 1024 : previous.wire.maxOutputTokens), InputType.TYPE_CLASS_NUMBER);
        Spinner thinking = selector(context, fields, R.string.caption_ai_thinking, R.id.caption_ai_thinking);
        thinkingChoices(context, thinking, selectedService[0], previous == null ? ChatCompletionAdapter.Thinking.AUTO_LOW : previous.wire.thinking);
        TextView policy = note(context, fields, R.string.caption_ai_policy_unknown);
        policy.setId(R.id.caption_ai_thinking_policy);
        note(context, fields, R.string.caption_ai_thinking_notice);
        Runnable updatePolicy = () -> policy.setText(policyText(CaptionModelPolicy.decision(selectedService[0],
                model.getText().toString().trim(), THINKING[thinking.getSelectedItemPosition()])));
        thinking.setOnItemSelectedListener(selection(updatePolicy));
        model.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) { updatePolicy.run(); }
            @Override public void afterTextChanged(Editable value) { }
        });
        service.setOnItemSelectedListener(selection(() -> {
            CaptionService next = CaptionService.values()[service.getSelectedItemPosition()];
            if (next == selectedService[0]) return;
            String address = endpoint.getText().toString().trim();
            if (address.isEmpty() || address.equals(selectedService[0].defaultEndpoint)) endpoint.setText(next.defaultEndpoint);
            selectedService[0] = next;
            thinkingChoices(context, thinking, next, ChatCompletionAdapter.Thinking.AUTO_LOW);
            endpointHint.setVisibility(next == CaptionService.GEMINI ? View.VISIBLE : View.GONE);
            workspaceFields.setVisibility(next == CaptionService.CLAUDE ? View.VISIBLE : View.GONE);
            workspace.setEnabled(next == CaptionService.CLAUDE);
            updatePolicy.run();
        }));
        updatePolicy.run();
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
                    String workspaceId = selectedService[0] == CaptionService.CLAUDE ? workspace.getText().toString().trim() : "";
                    ChatCompletionAdapter.Config wire = new ChatCompletionAdapter.Config(endpoint.getText().toString().trim(), model.getText().toString().trim(),
                            Integer.parseInt(tokens.getText().toString()), previous == null ? 262144 : previous.wire.requestBytes,
                            previous == null ? 262144 : previous.wire.responseBytes,
                            previous == null ? ChatCompletionAdapter.TokenField.MAX_TOKENS : previous.wire.tokenField,
                            THINKING[thinking.getSelectedItemPosition()], selectedService[0], workspaceId.isEmpty() ? null : workspaceId);
                    CaptionConnection basic = CaptionConnection.basic(wire.endpoint.toString(), wire.model, wire.maxOutputTokens, wire.thinking,
                            wire.service, wire.workspaceId);
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

    private static TextView note(Context context, LinearLayout fields, int text) {
        TextView note = new TextView(context);
        note.setText(text);
        fields.addView(note);
        return note;
    }
    private static Spinner selector(Context context, LinearLayout fields, int title, int id) {
        TextView label = note(context, fields, title);
        label.setLabelFor(id);
        Spinner input = new Spinner(context);
        input.setId(id);
        input.setSaveEnabled(false);
        fields.addView(input, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return input;
    }
    private static void choices(Context context, Spinner input, int[] labels) {
        String[] options = new String[labels.length];
        for (int i = 0; i < labels.length; i++) options[i] = context.getString(labels[i]);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        input.setAdapter(adapter);
    }
    private static void thinkingChoices(Context context, Spinner input, CaptionService service, ChatCompletionAdapter.Thinking value) {
        boolean manual = service == CaptionService.CUSTOM_CHAT || service == CaptionService.DEEPSEEK;
        choices(context, input, manual ? new int[]{R.string.caption_ai_thinking_auto, R.string.caption_ai_thinking_default, R.string.caption_ai_deepseek_thinking} :
                new int[]{R.string.caption_ai_thinking_auto, R.string.caption_ai_thinking_default});
        input.setSelection(value == ChatCompletionAdapter.Thinking.AUTO_LOW ? 0 : value == ChatCompletionAdapter.Thinking.OMIT ? 1 : manual ? 2 : 0);
    }
    private static AdapterView.OnItemSelectedListener selection(Runnable changed) {
        return new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { changed.run(); }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        };
    }
    private static int policyText(CaptionModelPolicy.Decision decision) {
        switch (decision) {
            case DISABLED: return R.string.caption_ai_policy_disabled;
            case LOW: return R.string.caption_ai_policy_low;
            case BUDGET_128: return R.string.caption_ai_policy_budget;
            case PROVIDER_DEFAULT: return R.string.caption_ai_policy_default;
            default: return R.string.caption_ai_policy_unknown;
        }
    }
    private static EditText field(Context context, LinearLayout fields, int title, int id, String value, int type) {
        TextView label = new TextView(context);
        label.setText(title);
        fields.addView(label);
        EditText input = new EditText(context);
        input.setId(id);
        input.setInputType(type);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(
                id == R.id.caption_ai_key ? 8192 : id == R.id.caption_ai_workspace ? 128 :
                        id == R.id.caption_ai_model ? 256 : id == R.id.caption_ai_output_tokens ? 7 : 4096)});
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
