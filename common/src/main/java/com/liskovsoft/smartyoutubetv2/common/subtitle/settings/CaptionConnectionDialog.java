package com.liskovsoft.smartyoutubetv2.common.subtitle.settings;

import android.content.Context;
import android.os.Build;
import android.text.InputFilter;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.liskovsoft.sharedutils.helpers.KeyHelpers;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.CaptionConnection;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.CaptionConnectionStore;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.CaptionService;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.ChatCompletionAdapter;
import okhttp3.HttpUrl;

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
        AlertDialog.Builder builder = new AlertDialog.Builder(context, R.style.AppDialog);
        Context dialogContext = builder.getContext();
        CaptionConnection previous = snapshot == null ? null : snapshot.connection;
        boolean hasSavedKey = snapshot != null && snapshot.hasKey;
        float density = dialogContext.getResources().getDisplayMetrics().density;
        int padding = (int) (24 * density);
        int sectionGap = (int) (8 * density);

        ScrollView scroll = new ScrollView(dialogContext);
        LinearLayout fields = new LinearLayout(dialogContext);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(padding, padding, padding, padding);
        scroll.addView(fields);

        CaptionService[] selectedService = {previous == null ? CaptionService.CUSTOM_CHAT : previous.wire.service};
        Spinner service = selector(dialogContext, fields, R.string.caption_ai_service, R.id.caption_ai_service, 0);
        choices(dialogContext, service, new int[]{
                R.string.caption_ai_service_custom, R.string.caption_ai_service_openai_chat,
                R.string.caption_ai_service_openai_responses, R.string.caption_ai_service_deepseek,
                R.string.caption_ai_service_claude, R.string.caption_ai_service_gemini
        });
        service.setSelection(selectedService[0].ordinal());

        EditText endpoint = field(dialogContext, fields, R.string.caption_ai_endpoint, R.string.caption_ai_endpoint_hint,
                R.id.caption_ai_endpoint, previous == null ? "" : previous.wire.endpoint.toString(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, sectionGap);
        TextView endpointHint = note(dialogContext, fields, R.string.caption_ai_gemini_endpoint_hint, 0);
        endpointHint.setVisibility(selectedService[0] == CaptionService.GEMINI ? View.VISIBLE : View.GONE);

        EditText model = field(dialogContext, fields, R.string.caption_ai_model, R.string.caption_ai_model_hint,
                R.id.caption_ai_model, previous == null ? "" : previous.wire.model, InputType.TYPE_CLASS_TEXT, sectionGap);

        EditText key = field(dialogContext, fields,
                hasSavedKey ? R.string.caption_ai_key_saved_label : R.string.caption_ai_key_label,
                hasSavedKey ? R.string.caption_ai_key_saved_hint : R.string.caption_ai_key_hint,
                R.id.caption_ai_key, "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, sectionGap);
        key.setTransformationMethod(PasswordTransformationMethod.getInstance());
        key.setSaveEnabled(false);
        if (Build.VERSION.SDK_INT >= 26) key.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);

        boolean initialExpanded = previous != null && previous.wire.workspaceId != null && !previous.wire.workspaceId.isEmpty();
        Button advancedToggle = new Button(dialogContext, null, android.R.attr.borderlessButtonStyle);
        advancedToggle.setId(R.id.caption_ai_advanced_toggle);
        advancedToggle.setAllCaps(false);
        advancedToggle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        advancedToggle.setBackgroundResource(R.drawable.dialog_item_background);
        advancedToggle.setFocusable(true);
        advancedToggle.setPadding(sectionGap, sectionGap, sectionGap, sectionGap);
        advancedToggle.setText(initialExpanded ? R.string.caption_ai_advanced_expanded : R.string.caption_ai_advanced_collapsed);
        LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        toggleParams.topMargin = sectionGap;
        fields.addView(advancedToggle, toggleParams);

        LinearLayout advancedFields = new LinearLayout(dialogContext);
        advancedFields.setId(R.id.caption_ai_advanced_fields);
        advancedFields.setOrientation(LinearLayout.VERTICAL);
        advancedFields.setVisibility(initialExpanded ? View.VISIBLE : View.GONE);
        fields.addView(advancedFields, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        advancedToggle.setOnClickListener(view -> {
            boolean expand = advancedFields.getVisibility() != View.VISIBLE;
            advancedFields.setVisibility(expand ? View.VISIBLE : View.GONE);
            advancedToggle.setText(expand ? R.string.caption_ai_advanced_expanded : R.string.caption_ai_advanced_collapsed);
            if (expand) scroll.post(() -> scroll.smoothScrollTo(0, advancedToggle.getTop()));
        });

        Spinner thinking = selector(dialogContext, advancedFields, R.string.caption_ai_thinking, R.id.caption_ai_thinking, 0);
        thinkingChoices(dialogContext, thinking, selectedService[0],
                previous == null ? ChatCompletionAdapter.Thinking.AUTO_LOW : previous.wire.thinking);

        EditText tokens = field(dialogContext, advancedFields, R.string.caption_ai_output_tokens, R.string.caption_ai_output_tokens,
                R.id.caption_ai_output_tokens, Integer.toString(previous == null ? 8192 : previous.wire.maxOutputTokens),
                InputType.TYPE_CLASS_NUMBER, sectionGap);

        LinearLayout workspaceFields = new LinearLayout(dialogContext);
        workspaceFields.setId(R.id.caption_ai_workspace_fields);
        workspaceFields.setOrientation(LinearLayout.VERTICAL);
        advancedFields.addView(workspaceFields);
        EditText workspace = field(dialogContext, workspaceFields, R.string.caption_ai_workspace, R.string.caption_ai_workspace,
                R.id.caption_ai_workspace, previous == null || previous.wire.workspaceId == null ? "" : previous.wire.workspaceId,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, sectionGap);
        note(dialogContext, workspaceFields, R.string.caption_ai_workspace_hint, 0);
        workspaceFields.setVisibility(selectedService[0] == CaptionService.CLAUDE ? View.VISIBLE : View.GONE);
        workspace.setEnabled(selectedService[0] == CaptionService.CLAUDE);

        service.setOnItemSelectedListener(selection(() -> {
            CaptionService next = CaptionService.values()[service.getSelectedItemPosition()];
            if (next == selectedService[0]) return;
            String address = endpoint.getText().toString().trim();
            if (address.isEmpty() || address.equals(selectedService[0].defaultEndpoint)) {
                endpoint.setText(next.defaultEndpoint);
            }
            selectedService[0] = next;
            thinkingChoices(dialogContext, thinking, next, ChatCompletionAdapter.Thinking.AUTO_LOW);
            endpointHint.setVisibility(next == CaptionService.GEMINI ? View.VISIBLE : View.GONE);
            workspaceFields.setVisibility(next == CaptionService.CLAUDE ? View.VISIBLE : View.GONE);
            workspace.setEnabled(next == CaptionService.CLAUDE);
        }));

        TextView error = note(dialogContext, fields, R.string.caption_ai_connection_notice, sectionGap);
        error.setId(R.id.caption_ai_error);
        if (!hasSavedKey) {
            error.setText("");
            error.setVisibility(View.GONE);
        }

        AlertDialog dialog = builder.setTitle(R.string.caption_ai_connection)
                .setView(scroll).setPositiveButton(android.R.string.ok, null).setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.caption_ai_clear_key, null).create();
        if (dialog.getWindow() != null) dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.setOnDismissListener(ignored -> key.getText().clear());
        Runnable expired = () -> {
            key.getText().clear();
            showStatus(error, R.string.caption_ai_editor_expired);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(false);
        };
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
                if (!current.current()) { expired.run(); return; }
                CaptionConnection connection;
                try {
                    String normalizedEndpoint = normalizeEndpoint(selectedService[0], endpoint.getText().toString());
                    String workspaceId = selectedService[0] == CaptionService.CLAUDE ? workspace.getText().toString().trim() : "";
                    ChatCompletionAdapter.Config wire = new ChatCompletionAdapter.Config(normalizedEndpoint, model.getText().toString().trim(),
                            Integer.parseInt(tokens.getText().toString().trim()), previous == null ? 262144 : previous.wire.requestBytes,
                            previous == null ? 262144 : previous.wire.responseBytes,
                            previous == null ? ChatCompletionAdapter.TokenField.MAX_TOKENS : previous.wire.tokenField,
                            THINKING[thinking.getSelectedItemPosition()], selectedService[0], workspaceId.isEmpty() ? null : workspaceId);
                    endpoint.setText(wire.endpoint.toString());
                    CaptionConnection basic = CaptionConnection.basic(wire.endpoint.toString(), wire.model, wire.maxOutputTokens, wire.thinking,
                            wire.service, wire.workspaceId);
                    connection = new CaptionConnection(wire, previous == null ? basic.batch : previous.batch, previous == null ? basic.maxCalls : previous.maxCalls);
                } catch (RuntimeException invalid) { showStatus(error, R.string.caption_ai_invalid_connection); return; }
                String replacement = key.getText().toString();
                if (!replacement.isEmpty() && !validKey(replacement)) { showStatus(error, R.string.caption_ai_invalid_connection); return; }
                if (replacement.isEmpty() && hasSavedKey && previous != null && !previous.wire.sameCredentialTarget(connection.wire)) {
                    showStatus(error, R.string.caption_ai_connection_notice);
                    key.requestFocus();
                    return;
                }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(false);
                showStatus(error, R.string.caption_ai_saving);
                save.save(connection, replacement.isEmpty() ? CaptionConnectionStore.KeyEdit.KEEP : CaptionConnectionStore.KeyEdit.REPLACE,
                        replacement.isEmpty() ? null : replacement, saved -> {
                            if (!dialog.isShowing()) return;
                            if (!current.current()) { dialog.dismiss(); return; }
                            if (saved) dialog.dismiss();
                            else {
                                showStatus(error, R.string.caption_ai_save_failed);
                                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(previous != null);
                            }
                        });
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(previous != null);
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(button -> {
                if (!current.current()) { expired.run(); return; }
                AlertDialog confirmationDialog = new AlertDialog.Builder(context, R.style.AppDialog).setTitle(R.string.caption_ai_clear_key)
                        .setMessage(R.string.caption_ai_clear_key_confirm).setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok, (confirmation, which) -> {
                            if (!current.current()) { expired.run(); return; }
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(false);
                            save.save(previous, CaptionConnectionStore.KeyEdit.CLEAR, null, saved -> {
                                if (!dialog.isShowing()) return;
                                if (saved || !current.current()) dialog.dismiss();
                                else {
                                    showStatus(error, R.string.caption_ai_save_failed);
                                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(true);
                                }
                            });
                        }).create();
                showSafely(context, confirmationDialog);
            });
        });
        showSafely(context, dialog);
        return dialog;
    }

    /** Complete common shorthand URLs (omitted https://, bare domain, or /v1 base path) without altering explicit paths or http://. */
    static String normalizeEndpoint(CaptionService service, String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return "";
        String candidate = hasExplicitScheme(trimmed) ? trimmed : "https://" + trimmed;
        if (service == null || !service.chat()) return candidate;
        HttpUrl url = HttpUrl.parse(candidate);
        if (url == null || !"https".equals(url.scheme()) || !url.username().isEmpty() || !url.password().isEmpty() ||
                url.query() != null || url.fragment() != null) {
            return candidate;
        }
        String path = url.encodedPath();
        while (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        if ("/".equals(path)) {
            String suffix = service == CaptionService.DEEPSEEK && "api.deepseek.com".equalsIgnoreCase(url.host()) ?
                    "/chat/completions" : "/v1/chat/completions";
            return url.newBuilder().encodedPath(suffix).build().toString();
        }
        int slash = path.lastIndexOf('/');
        String lastSegment = slash >= 0 ? path.substring(slash + 1) : path;
        if (lastSegment.matches("v[0-9]+(?:beta[0-9]*)?")) {
            return url.newBuilder().encodedPath(path + "/chat/completions").build().toString();
        }
        return candidate;
    }

    private static boolean hasExplicitScheme(String value) {
        if (value.contains("://")) return true;
        int colon = value.indexOf(':');
        if (colon <= 0) return false;
        int slash = value.indexOf('/');
        if (slash >= 0 && colon > slash) return false;
        String afterColon = value.substring(colon + 1, slash >= 0 ? slash : value.length());
        if (afterColon.isEmpty()) return true;
        for (int i = 0; i < afterColon.length(); i++) {
            char c = afterColon.charAt(i);
            if (c < '0' || c > '9') return true;
        }
        return false; // host:port without scheme
    }

    private static void showStatus(TextView error, int text) {
        error.setText(text);
        error.setVisibility(View.VISIBLE);
    }

    private static TextView note(Context context, LinearLayout fields, int text, int topMargin) {
        TextView note = new TextView(context);
        note.setText(text);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = topMargin;
        fields.addView(note, params);
        return note;
    }

    private static Spinner selector(Context context, LinearLayout fields, int title, int id, int topMargin) {
        TextView label = note(context, fields, title, topMargin);
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

    private static EditText field(Context context, LinearLayout fields, int title, int hint, int id, String value, int type, int topMargin) {
        note(context, fields, title, topMargin);
        EditText input = new EditText(context);
        input.setId(id);
        input.setInputType(type);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(
                id == R.id.caption_ai_key ? 8192 : id == R.id.caption_ai_workspace ? 128 :
                        id == R.id.caption_ai_model ? 256 : id == R.id.caption_ai_output_tokens ? 7 : 4096)});
        input.setSingleLine(true);
        input.setSaveEnabled(false);
        input.setHint(hint);
        input.setText(value);
        KeyHelpers.fixEnterKey(input);
        fields.addView(input);
        return input;
    }

    private static void showSafely(Context context, AlertDialog dialog) {
        try {
            dialog.show();
        } catch (RuntimeException e) {
            MessageHelpers.showMessage(context, e.getMessage());
        }
    }

    private static boolean validKey(String key) {
        if (key.length() > 8192) return false;
        for (int i = 0; i < key.length(); i++) if (key.charAt(i) < 33 || key.charAt(i) > 126) return false;
        return true;
    }

    private CaptionConnectionDialog() { }
}
