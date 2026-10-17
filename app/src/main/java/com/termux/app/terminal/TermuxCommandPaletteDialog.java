package com.termux.app.terminal;

import android.app.Dialog;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import com.termux.R;
import com.termux.app.TermuxActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Searchable, keyboard-friendly action palette for the terminal activity. */
public final class TermuxCommandPaletteDialog {

    public static final String ACTION_NEW_SESSION = "new_session";
    public static final String ACTION_SESSIONS = "sessions";
    public static final String ACTION_FILES = "files";
    public static final String ACTION_WORKSPACES = "workspaces";
    public static final String ACTION_DESKTOP = "desktop";
    public static final String ACTION_SETTINGS = "settings";
    public static final String ACTION_APPEARANCE = "appearance";
    public static final String ACTION_EXTRA_KEYS = "extra_keys";
    public static final String ACTION_KEYBOARD = "keyboard";
    public static final String ACTION_SHARE = "share";
    public static final String ACTION_RESET = "reset";
    public static final String ACTION_HELP = "help";

    public interface ActionHandler {
        void onAction(String actionId);
    }

    private TermuxCommandPaletteDialog() {
    }

    public static void show(TermuxActivity activity, ActionHandler actionHandler) {
        if (activity == null || activity.isFinishing()) return;

        Dialog dialog = new Dialog(activity, R.style.Theme_TermuxPremiumDialog);
        View content = LayoutInflater.from(activity).inflate(R.layout.dialog_command_palette, null, false);
        EditText search = content.findViewById(R.id.command_palette_search);
        ListView list = content.findViewById(R.id.command_palette_list);
        TextView empty = content.findViewById(R.id.command_palette_empty);
        ActionAdapter adapter = new ActionAdapter(activity);

        list.setAdapter(adapter);
        list.setEmptyView(empty);
        list.setOnItemClickListener((parent, view, position, id) -> {
            PaletteAction action = adapter.getItem(position);
            if (action == null) return;
            dialog.dismiss();
            if (actionHandler != null) actionHandler.onAction(action.id);
        });

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.filter(s);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        search.setOnEditorActionListener((view, actionId, event) -> {
            boolean submit = actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN);
            if (submit && adapter.getCount() > 0) {
                PaletteAction action = adapter.getItem(0);
                dialog.dismiss();
                if (actionHandler != null && action != null) actionHandler.onAction(action.id);
                return true;
            }
            return false;
        });

        dialog.setContentView(content);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.dimAmount = 0.62f;
            window.setAttributes(attributes);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE |
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);

            Resources resources = activity.getResources();
            int width = resources.getDisplayMetrics().widthPixels;
            int height = resources.getDisplayMetrics().heightPixels;
            int margin = dp(activity, 28);
            int maxWidth = dp(activity, 540);
            int maxHeight = dp(activity, 520);
            window.setLayout(Math.min(maxWidth, width - margin * 2), Math.min(maxHeight, Math.round(height * 0.74f)));
        }

        search.requestFocus();
        search.post(() -> {
            InputMethodManager inputMethodManager = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (inputMethodManager != null) inputMethodManager.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static final class PaletteAction {
        final String id;
        final int title;
        final int summary;
        final String shortcut;
        final String keywords;

        PaletteAction(String id, int title, int summary, String shortcut, String keywords) {
            this.id = id;
            this.title = title;
            this.summary = summary;
            this.shortcut = shortcut;
            this.keywords = keywords;
        }
    }

    private static final class ActionAdapter extends BaseAdapter {
        private final TermuxActivity activity;
        private final List<PaletteAction> allActions = new ArrayList<>();
        private final List<PaletteAction> visibleActions = new ArrayList<>();

        ActionAdapter(TermuxActivity activity) {
            this.activity = activity;
            allActions.add(new PaletteAction(ACTION_NEW_SESSION, R.string.premium_palette_new_session,
                R.string.premium_palette_new_session_summary, "+", "tab terminal shell ubuntu add"));
            allActions.add(new PaletteAction(ACTION_SESSIONS, R.string.premium_palette_sessions,
                R.string.premium_palette_sessions_summary, "Tab", "switch tabs terminal"));
            allActions.add(new PaletteAction(ACTION_FILES, R.string.premium_palette_files,
                R.string.premium_palette_files_summary,
                TermuxLaptopMode.formatShortcut(activity, TermuxLaptopMode.ACTION_FILES), "files folder browse explorer"));
            allActions.add(new PaletteAction(ACTION_WORKSPACES, R.string.premium_palette_workspaces,
                R.string.premium_palette_workspaces_summary,
                TermuxLaptopMode.formatShortcut(activity, TermuxLaptopMode.ACTION_WORKSPACES),
                "workspace project profile sessions environment folder"));
            allActions.add(new PaletteAction(ACTION_DESKTOP, R.string.premium_palette_desktop,
                R.string.premium_palette_desktop_summary,
                TermuxLaptopMode.formatShortcut(activity, TermuxLaptopMode.ACTION_GUIDE),
                "laptop tablet desktop dex keyboard mouse shortcut input"));
            allActions.add(new PaletteAction(ACTION_SETTINGS, R.string.premium_palette_settings,
                R.string.premium_palette_settings_summary, "", "preferences configure terminal"));
            allActions.add(new PaletteAction(ACTION_APPEARANCE, R.string.premium_palette_appearance,
                R.string.premium_palette_appearance_summary, "", "theme color palette accent"));
            allActions.add(new PaletteAction(ACTION_EXTRA_KEYS, R.string.premium_palette_extra_keys,
                R.string.premium_palette_extra_keys_summary, "", "esc tab ctrl alt arrows keys toolbar"));
            allActions.add(new PaletteAction(ACTION_KEYBOARD, R.string.premium_palette_keyboard,
                R.string.premium_palette_keyboard_summary, "", "soft input gboard"));
            allActions.add(new PaletteAction(ACTION_SHARE, R.string.premium_palette_share,
                R.string.premium_palette_share_summary, "", "copy transcript output"));
            allActions.add(new PaletteAction(ACTION_RESET, R.string.premium_palette_reset,
                R.string.premium_palette_reset_summary, "", "clear screen terminal"));
            allActions.add(new PaletteAction(ACTION_HELP, R.string.premium_palette_help,
                R.string.premium_palette_help_summary, "", "documentation help"));
            visibleActions.addAll(allActions);
        }

        void filter(CharSequence query) {
            String needle = query == null ? "" : query.toString().trim().toLowerCase(Locale.ROOT);
            visibleActions.clear();
            for (PaletteAction action : allActions) {
                String haystack = (activity.getString(action.title) + " " + activity.getString(action.summary) + " " + action.keywords)
                    .toLowerCase(Locale.ROOT);
                if (needle.isEmpty() || haystack.contains(needle)) visibleActions.add(action);
            }
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return visibleActions.size();
        }

        @Override
        public PaletteAction getItem(int position) {
            return position >= 0 && position < visibleActions.size() ? visibleActions.get(position) : null;
        }

        @Override
        public long getItemId(int position) {
            PaletteAction action = getItem(position);
            return action == null ? position : action.id.hashCode();
        }

        @Override
        public View getView(int position, View convertView, android.view.ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = LayoutInflater.from(activity).inflate(R.layout.item_premium_palette_action, parent, false);
            }
            PaletteAction action = getItem(position);
            if (action == null) return row;

            TextView title = row.findViewById(R.id.palette_action_title);
            TextView summary = row.findViewById(R.id.palette_action_summary);
            TextView shortcut = row.findViewById(R.id.palette_action_shortcut);
            title.setText(action.title);
            summary.setText(action.summary);
            shortcut.setText(action.shortcut);
            shortcut.setVisibility(action.shortcut.isEmpty() ? View.GONE : View.VISIBLE);
            row.setContentDescription(activity.getString(action.title) + ". " + activity.getString(action.summary));
            return row;
        }
    }
}
