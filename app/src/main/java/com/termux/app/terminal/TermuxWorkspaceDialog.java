package com.termux.app.terminal;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxService;
import com.termux.app.UbuntuRootfsInstaller;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;

import java.util.ArrayList;
import java.util.List;

/** Project-profile manager with live sessions displayed beneath their workspace group. */
public final class TermuxWorkspaceDialog {

    private TermuxWorkspaceDialog() {
    }

    public static void show(TermuxActivity activity) {
        if (activity == null || activity.isFinishing()) return;
        if (activity.getTermuxService() == null) {
            Toast.makeText(activity, R.string.premium_workspace_service_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        new WorkspaceScreen(activity, new TermuxWorkspaceManager(activity)).show();
    }

    private static final class WorkspaceScreen {
        private final TermuxActivity activity;
        private final TermuxWorkspaceManager manager;
        private AlertDialog dialog;
        private LinearLayout content;

        WorkspaceScreen(TermuxActivity activity, TermuxWorkspaceManager manager) {
            this.activity = activity;
            this.manager = manager;
        }

        void show() {
            ScrollView scroll = new ScrollView(activity);
            scroll.setFillViewport(true);
            content = new LinearLayout(activity);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(activity, 16), dp(activity, 8), dp(activity, 16), dp(activity, 12));
            scroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

            dialog = new AlertDialog.Builder(activity, R.style.Theme_TermuxPremiumDialog)
                .setTitle(R.string.premium_workspaces_title)
                .setView(scroll)
                .setPositiveButton(R.string.premium_workspace_create, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
            dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> showEditor(null)));
            dialog.show();
            refresh();
        }

        private void refresh() {
            if (content == null || activity.isFinishing()) return;
            content.removeAllViews();
            addLabel(content, activity.getString(R.string.premium_workspaces_summary), false);

            List<TermuxWorkspaceManager.Workspace> workspaces = manager.getWorkspaces();
            for (TermuxWorkspaceManager.Workspace workspace : workspaces) {
                List<TermuxSession> sessions = getSessionsForWorkspace(workspace.id);
                addWorkspaceCard(workspace, sessions);
            }

            ArrayList<TermuxSession> unassigned = getSessionsForWorkspace(null);
            if (!unassigned.isEmpty()) addUnassignedCard(unassigned);
            if (workspaces.isEmpty() && unassigned.isEmpty()) {
                TextView empty = addLabel(content, activity.getString(R.string.premium_workspace_empty), false);
                empty.setGravity(Gravity.CENTER);
                empty.setPadding(0, dp(activity, 24), 0, dp(activity, 24));
            }
        }

        private void addWorkspaceCard(TermuxWorkspaceManager.Workspace workspace, List<TermuxSession> sessions) {
            LinearLayout card = createCard();
            content.addView(card, cardLayoutParams());

            LinearLayout top = new LinearLayout(activity);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            card.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView title = new TextView(activity);
            title.setText(workspace.name);
            title.setTextColor(Color.WHITE);
            title.setTextSize(17);
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button edit = smallButton(R.string.premium_workspace_edit);
            edit.setOnClickListener(view -> showEditor(workspace));
            top.addView(edit);
            Button delete = smallButton(R.string.premium_workspace_delete);
            delete.setOnClickListener(view -> confirmDelete(workspace, sessions.size()));
            top.addView(delete);

            TextView details = addLabel(card, activity.getString(R.string.premium_workspace_details,
                workspace.workingDirectory, workspace.environment.size()), false);
            details.setPadding(0, dp(activity, 4), 0, dp(activity, 7));

            Button newSession = smallButton(R.string.premium_workspace_new_session);
            newSession.setOnClickListener(view -> startWorkspaceSession(workspace));
            card.addView(newSession, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

            addSessionGroup(card, sessions);
        }

        private void addUnassignedCard(List<TermuxSession> sessions) {
            LinearLayout card = createCard();
            content.addView(card, cardLayoutParams());
            TextView title = addLabel(card, activity.getString(R.string.premium_workspace_unassigned), true);
            title.setTextSize(16);
            addSessionGroup(card, sessions);
        }

        private void addSessionGroup(LinearLayout card, List<TermuxSession> sessions) {
            TextView heading = addLabel(card, activity.getString(R.string.premium_workspace_sessions_count, sessions.size()), true);
            heading.setPadding(0, dp(activity, 8), 0, dp(activity, 3));
            if (sessions.isEmpty()) {
                addLabel(card, activity.getString(R.string.premium_workspace_no_sessions), false);
                return;
            }
            for (TermuxSession termuxSession : sessions) addSessionRow(card, termuxSession);
        }

        private void addSessionRow(LinearLayout card, TermuxSession termuxSession) {
            TerminalSession session = termuxSession == null ? null : termuxSession.getTerminalSession();
            if (session == null) return;
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(activity, 2), dp(activity, 3), 0, dp(activity, 3));
            card.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

            String label = session.mSessionName;
            if (label == null || label.trim().isEmpty()) label = session.getTitle();
            if (label == null || label.trim().isEmpty()) {
                TermuxService service = activity.getTermuxService();
                int index = service == null ? 0 : service.getIndexOfSession(session);
                label = activity.getString(R.string.premium_session_default_name, Math.max(0, index) + 1);
            }
            TextView name = new TextView(activity);
            name.setText(label);
            name.setTextSize(13);
            name.setTextColor(Color.WHITE);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView state = new TextView(activity);
            state.setText(session.isRunning() ? R.string.premium_workspace_running : R.string.premium_workspace_stopped);
            state.setTextSize(11);
            state.setTextColor(session.isRunning() ? TermuxPremiumTheme.getRunningColor(activity) :
                activity.getResources().getColor(R.color.premium_muted));
            row.addView(state, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

            row.setContentDescription(activity.getString(R.string.premium_workspace_switch_session, label));
            row.setFocusable(true);
            row.setClickable(true);
            row.setOnClickListener(view -> switchToSession(session));
            row.setOnLongClickListener(view -> {
                activity.getTermuxTerminalSessionClient().renameSession(session);
                return true;
            });
        }

        private void switchToSession(TerminalSession session) {
            activity.getTermuxTerminalSessionClient().setCurrentSession(session);
            if (dialog != null) dialog.dismiss();
        }

        private ArrayList<TermuxSession> getSessionsForWorkspace(String workspaceId) {
            ArrayList<TermuxSession> result = new ArrayList<>();
            TermuxService service = activity.getTermuxService();
            if (service == null) return result;
            for (TermuxSession session : service.getTermuxSessions()) {
                if (session == null || session.getTerminalSession() == null) continue;
                String assignedId = manager.getWorkspaceIdForSession(session.getTerminalSession().mHandle);
                if (workspaceId == null ? assignedId == null : workspaceId.equals(assignedId)) result.add(session);
            }
            return result;
        }

        private void startWorkspaceSession(TermuxWorkspaceManager.Workspace workspace) {
            if (!UbuntuRootfsInstaller.isReady()) {
                new AlertDialog.Builder(activity, R.style.Theme_TermuxPremiumDialog)
                    .setTitle(R.string.premium_workspace_ubuntu_required_title)
                    .setMessage(R.string.premium_workspace_ubuntu_required_message)
                    .setPositiveButton(R.string.ubuntu_setup_retry,
                        (dialogInterface, which) -> UbuntuRootfsInstaller.installIfNeeded(activity, () -> { }))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
                return;
            }
            activity.getTermuxTerminalSessionClient().addWorkspaceSession(workspace);
            if (dialog != null) dialog.dismiss();
        }

        private void showEditor(TermuxWorkspaceManager.Workspace workspace) {
            boolean editing = workspace != null;
            LinearLayout form = new LinearLayout(activity);
            form.setOrientation(LinearLayout.VERTICAL);
            form.setPadding(dp(activity, 18), dp(activity, 8), dp(activity, 18), dp(activity, 4));

            EditText name = addInput(form, R.string.premium_workspace_name, R.string.premium_workspace_name_hint,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, 1);
            EditText directory = addInput(form, R.string.premium_workspace_directory,
                R.string.premium_workspace_directory_hint,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, 1);
            EditText environment = addInput(form, R.string.premium_workspace_environment,
                R.string.premium_workspace_environment_hint,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, 5);
            if (editing) {
                name.setText(workspace.name);
                directory.setText(workspace.workingDirectory);
                environment.setText(TermuxWorkspaceManager.formatEnvironmentText(workspace.environment));
            } else {
                directory.setText("/root");
            }
            ScrollView formScroll = new ScrollView(activity);
            formScroll.setFillViewport(false);
            formScroll.addView(form, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

            AlertDialog editor = new AlertDialog.Builder(activity, R.style.Theme_TermuxPremiumDialog)
                .setTitle(editing ? R.string.premium_workspace_edit : R.string.premium_workspace_create)
                .setView(formScroll)
                .setPositiveButton(R.string.premium_workspace_save, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
            editor.setOnShowListener(ignored -> editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                try {
                    manager.saveWorkspace(editing ? workspace.id : null, name.getText().toString(),
                        directory.getText().toString(), environment.getText().toString());
                    activity.termuxSessionListNotifyUpdated();
                    editor.dismiss();
                    refresh();
                } catch (TermuxWorkspaceManager.WorkspaceValidationException e) {
                    Toast.makeText(activity, validationMessage(e.code), Toast.LENGTH_LONG).show();
                } catch (IllegalArgumentException e) {
                    Toast.makeText(activity, R.string.premium_workspace_error_invalid, Toast.LENGTH_LONG).show();
                }
            }));
            editor.show();
        }

        private void confirmDelete(TermuxWorkspaceManager.Workspace workspace, int sessionCount) {
            String message = activity.getString(R.string.premium_workspace_delete_message,
                workspace.name, sessionCount);
            new AlertDialog.Builder(activity, R.style.Theme_TermuxPremiumDialog)
                .setTitle(R.string.premium_workspace_delete)
                .setMessage(message)
                .setPositiveButton(R.string.premium_workspace_delete, (dialogInterface, which) -> {
                    manager.deleteWorkspace(workspace.id);
                    activity.termuxSessionListNotifyUpdated();
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        }

        private int validationMessage(String code) {
            if (TermuxWorkspaceManager.ERROR_NAME_REQUIRED.equals(code)) return R.string.premium_workspace_error_name_required;
            if (TermuxWorkspaceManager.ERROR_NAME_TOO_LONG.equals(code)) return R.string.premium_workspace_error_name_too_long;
            if (TermuxWorkspaceManager.ERROR_DUPLICATE_NAME.equals(code)) return R.string.premium_workspace_error_duplicate;
            if (TermuxWorkspaceManager.ERROR_DIRECTORY_INVALID.equals(code)) return R.string.premium_workspace_error_directory;
            if (TermuxWorkspaceManager.ERROR_ENVIRONMENT_TOO_LARGE.equals(code)) return R.string.premium_workspace_error_environment_size;
            if (TermuxWorkspaceManager.ERROR_TOO_MANY_WORKSPACES.equals(code)) return R.string.premium_workspace_error_limit;
            return R.string.premium_workspace_error_environment;
        }

        private EditText addInput(LinearLayout form, int labelResource, int hintResource, int inputType, int minLines) {
            TextView label = addLabel(form, activity.getString(labelResource), true);
            label.setPadding(0, dp(activity, 8), 0, dp(activity, 3));
            EditText input = new EditText(activity);
            input.setHint(hintResource);
            input.setInputType(inputType);
            input.setMinLines(minLines);
            if (minLines == 1) input.setSingleLine(true);
            if (minLines > 1) {
                input.setGravity(Gravity.TOP | Gravity.START);
                input.setMaxLines(8);
                input.setPadding(dp(activity, 10), dp(activity, 8), dp(activity, 10), dp(activity, 8));
            }
            form.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                minLines > 1 ? dp(activity, 132) : ViewGroup.LayoutParams.WRAP_CONTENT));
            return input;
        }

        private Button smallButton(int textResource) {
            Button button = new Button(activity);
            button.setText(textResource);
            button.setTextSize(12);
            button.setMinHeight(dp(activity, 36));
            button.setMinimumHeight(dp(activity, 36));
            button.setPadding(dp(activity, 8), 0, dp(activity, 8), 0);
            return button;
        }

        private LinearLayout createCard() {
            LinearLayout card = new LinearLayout(activity);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10));
            GradientDrawable background = new GradientDrawable();
            background.setColor(Color.rgb(24, 24, 36));
            background.setCornerRadius(dp(activity, 14));
            background.setStroke(dp(activity, 1), Color.rgb(58, 58, 76));
            card.setBackground(background);
            return card;
        }

        private LinearLayout.LayoutParams cardLayoutParams() {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(activity, 10);
            return params;
        }

        private TextView addLabel(LinearLayout parent, String text, boolean bold) {
            TextView label = new TextView(activity);
            label.setText(text);
            label.setTextColor(bold ? Color.WHITE : activity.getResources().getColor(R.color.premium_muted));
            label.setTextSize(bold ? 13 : 12);
            if (bold) label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            parent.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
            return label;
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
