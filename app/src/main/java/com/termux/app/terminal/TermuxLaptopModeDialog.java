package com.termux.app.terminal;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.view.KeyboardUtils;

import java.util.Map;

/** Guide and configuration screen for physical-keyboard, mouse and wide-window use. */
public final class TermuxLaptopModeDialog {

    private TermuxLaptopModeDialog() {
    }

    public static void show(TermuxActivity activity) {
        if (activity == null || activity.isFinishing()) return;

        int padding = dp(activity, 18);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padding, padding, padding, padding);

        TextView inputStatus = bodyText(activity, getInputStatus(activity));
        inputStatus.setTextColor(color(activity, R.color.premium_accent));
        content.addView(inputStatus, matchWrap());

        TextView guideTitle = heading(activity, R.string.desktop_guide_title);
        addWithTopMargin(content, guideTitle, dp(activity, 16));
        TextView guide = bodyText(activity, activity.getString(R.string.desktop_guide_text));
        addWithTopMargin(content, guide, dp(activity, 6));

        TextView inputTitle = heading(activity, R.string.desktop_input_title);
        addWithTopMargin(content, inputTitle, dp(activity, 16));

        Switch softKeyboardEnabled = new Switch(activity);
        softKeyboardEnabled.setText(R.string.desktop_soft_keyboard_enabled);
        softKeyboardEnabled.setTextColor(color(activity, R.color.premium_text));
        softKeyboardEnabled.setChecked(activity.getPreferences().isSoftKeyboardEnabled());
        addWithTopMargin(content, softKeyboardEnabled, dp(activity, 8));

        Switch onlyWithoutHardware = new Switch(activity);
        onlyWithoutHardware.setText(R.string.desktop_keyboard_only_without_hardware);
        onlyWithoutHardware.setTextColor(color(activity, R.color.premium_text));
        onlyWithoutHardware.setChecked(activity.getPreferences().isSoftKeyboardEnabledOnlyIfNoHardware());
        addWithTopMargin(content, onlyWithoutHardware, dp(activity, 4));

        TextView keyboardHint = bodyText(activity, activity.getString(R.string.desktop_keyboard_hint));
        addWithTopMargin(content, keyboardHint, dp(activity, 4));

        TextView shortcutsTitle = heading(activity, R.string.desktop_shortcuts_title);
        addWithTopMargin(content, shortcutsTitle, dp(activity, 16));
        TextView shortcutsHint = bodyText(activity, activity.getString(R.string.desktop_shortcuts_hint));
        addWithTopMargin(content, shortcutsHint, dp(activity, 4));

        LinearLayout shortcutsContainer = new LinearLayout(activity);
        shortcutsContainer.setOrientation(LinearLayout.VERTICAL);
        addWithTopMargin(content, shortcutsContainer, dp(activity, 6));
        rebuildShortcutRows(activity, shortcutsContainer);

        softKeyboardEnabled.setOnCheckedChangeListener((button, checked) -> {
            activity.getPreferences().setSoftKeyboardEnabled(checked);
            refreshKeyboardState(activity);
        });
        onlyWithoutHardware.setOnCheckedChangeListener((button, checked) -> {
            activity.getPreferences().setSoftKeyboardEnabledOnlyIfNoHardware(checked);
            refreshKeyboardState(activity);
        });

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        scroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(activity, R.style.Theme_TermuxPremiumDialog)
            .setTitle(R.string.desktop_title)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.desktop_restore_defaults, null)
            .create();
        dialog.setOnShowListener(ignored -> {
            Button reset = dialog.getButton(DialogInterface.BUTTON_NEUTRAL);
            if (reset != null) {
                reset.setOnClickListener(view -> {
                    TermuxLaptopMode.resetShortcuts(activity);
                    rebuildShortcutRows(activity, shortcutsContainer);
                    Toast.makeText(activity, R.string.desktop_defaults_restored, Toast.LENGTH_SHORT).show();
                });
            }
        });
        dialog.setOnDismissListener(ignored -> {
            if (activity.isFinishing()) return;
            activity.updatePremiumSessionHeader();
        });
        dialog.show();
        setDialogSize(activity, dialog);
    }

    private static void rebuildShortcutRows(TermuxActivity activity, LinearLayout container) {
        container.removeAllViews();
        Map<String, TermuxLaptopMode.Shortcut> shortcuts = TermuxLaptopMode.getShortcuts(activity);
        for (TermuxLaptopMode.Definition definition : TermuxLaptopMode.getDefinitions()) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(dp(activity, 10), dp(activity, 8), dp(activity, 6), dp(activity, 8));
            row.setBackgroundColor(Color.argb(24, 255, 255, 255));

            TextView title = new TextView(activity);
            title.setText(definition.titleResource);
            title.setTextColor(color(activity, R.color.premium_text));
            title.setTextSize(14);
            title.setMaxLines(2);
            row.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            Button change = new Button(activity);
            change.setAllCaps(false);
            change.setMinWidth(dp(activity, 104));
            change.setText(TermuxLaptopMode.formatShortcut(activity, shortcuts.get(definition.id)));
            change.setContentDescription(activity.getString(R.string.desktop_change_shortcut,
                activity.getString(definition.titleResource)));
            change.setOnClickListener(view -> captureShortcut(activity, definition, container));
            row.addView(change, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout.LayoutParams rowParams = matchWrap();
            rowParams.bottomMargin = dp(activity, 6);
            container.addView(row, rowParams);
        }
    }

    private static void captureShortcut(TermuxActivity activity, TermuxLaptopMode.Definition definition,
                                        LinearLayout rowsContainer) {
        ShortcutCaptureView capture = new ShortcutCaptureView(activity);
        capture.setTextColor(color(activity, R.color.premium_muted));
        capture.setTextSize(13);
        capture.setGravity(android.view.Gravity.CENTER);
        capture.setPadding(dp(activity, 16), dp(activity, 28), dp(activity, 16), dp(activity, 28));
        capture.setBackgroundColor(Color.argb(24, 255, 255, 255));
        capture.setText(R.string.desktop_capture_hint);
        capture.setFocusable(true);
        capture.setFocusableInTouchMode(true);
        capture.setOnKeyCapture(event -> {
            int keyCode = event.getKeyCode();
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
                ((AlertDialog) capture.getTag()).dismiss();
                return true;
            }
            if (TermuxLaptopMode.isModifierKey(keyCode)) {
                capture.setText(R.string.desktop_capture_modifier_pressed);
                return true;
            }
            if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0) return true;

            int modifiers = TermuxLaptopMode.modifiersFromEvent(event);
            if (modifiers == 0) {
                capture.setText(R.string.desktop_capture_modifier_required);
                return true;
            }
            TermuxLaptopMode.Shortcut candidate = new TermuxLaptopMode.Shortcut(keyCode, modifiers);
            if (candidate.keyCode == KeyEvent.KEYCODE_UNKNOWN) {
                capture.setText(R.string.desktop_capture_invalid);
                return true;
            }
            String conflictId = TermuxLaptopMode.findConflict(TermuxLaptopMode.getShortcuts(activity), definition.id, candidate);
            if (conflictId != null) {
                int conflictTitle = getTitleResource(conflictId);
                String conflictName = conflictTitle == 0 ? conflictId : activity.getString(conflictTitle);
                capture.setText(activity.getString(R.string.desktop_capture_conflict, conflictName,
                    TermuxLaptopMode.formatShortcut(activity, candidate)));
                return true;
            }

            TermuxLaptopMode.saveShortcut(activity, definition.id, candidate);
            ((AlertDialog) capture.getTag()).dismiss();
            rebuildShortcutRows(activity, rowsContainer);
            return true;
        });

        AlertDialog captureDialog = new AlertDialog.Builder(activity, R.style.Theme_TermuxPremiumDialog)
            .setTitle(activity.getString(R.string.desktop_capture_title, activity.getString(definition.titleResource)))
            .setView(capture)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.desktop_unassign, null)
            .create();
        capture.setTag(captureDialog);
        captureDialog.setOnShowListener(ignored -> {
            Button clear = captureDialog.getButton(DialogInterface.BUTTON_NEUTRAL);
            if (clear != null) {
                clear.setOnClickListener(view -> {
                    TermuxLaptopMode.saveShortcut(activity, definition.id, null);
                    captureDialog.dismiss();
                    rebuildShortcutRows(activity, rowsContainer);
                });
            }
            capture.requestFocus();
        });
        captureDialog.show();
        setDialogSize(activity, captureDialog);
    }

    private static int getTitleResource(String actionId) {
        for (TermuxLaptopMode.Definition definition : TermuxLaptopMode.getDefinitions())
            if (definition.id.equals(actionId)) return definition.titleResource;
        return 0;
    }

    private static String getInputStatus(TermuxActivity activity) {
        boolean keyboard = KeyboardUtils.isHardKeyboardConnected(activity);
        boolean pointer = hasPhysicalPointer();
        float density = activity.getResources().getDisplayMetrics().density;
        int widthDp = Math.round(activity.getResources().getDisplayMetrics().widthPixels / Math.max(0.1f, density));
        boolean multiWindow = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInMultiWindowMode();
        String windowState = multiWindow ? activity.getString(R.string.desktop_window_multiwindow) :
            widthDp >= 600 ? activity.getString(R.string.desktop_window_wide) : activity.getString(R.string.desktop_window_compact);
        return activity.getString(R.string.desktop_input_status,
            activity.getString(keyboard ? R.string.desktop_connected : R.string.desktop_not_connected),
            activity.getString(pointer ? R.string.desktop_connected : R.string.desktop_not_connected),
            widthDp, windowState);
    }

    private static boolean hasPhysicalPointer() {
        int[] ids = InputDevice.getDeviceIds();
        for (int id : ids) {
            InputDevice device = InputDevice.getDevice(id);
            if (device == null || device.isVirtual()) continue;
            int sources = device.getSources();
            if ((sources & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE ||
                (sources & InputDevice.SOURCE_STYLUS) == InputDevice.SOURCE_STYLUS ||
                (sources & InputDevice.SOURCE_TRACKBALL) == InputDevice.SOURCE_TRACKBALL) return true;
        }
        return false;
    }

    private static void refreshKeyboardState(TermuxActivity activity) {
        if (activity.getTermuxTerminalViewClient() != null)
            activity.getTermuxTerminalViewClient().refreshSoftKeyboardState();
    }

    private static TextView heading(Context context, int textResource) {
        TextView view = new TextView(context);
        view.setText(textResource);
        view.setTextColor(color(context, R.color.premium_text));
        view.setTextSize(16);
        view.setTypeface(null, Typeface.BOLD);
        return view;
    }

    private static TextView bodyText(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(color(context, R.color.premium_muted));
        view.setTextSize(13);
        view.setLineSpacing(dp(context, 2), 1.0f);
        return view;
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static void addWithTopMargin(LinearLayout parent, View view, int margin) {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = margin;
        parent.addView(view, params);
    }

    private static int color(Context context, int resource) {
        return context.getResources().getColor(resource);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static void setDialogSize(TermuxActivity activity, AlertDialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) return;
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.dimAmount = 0.58f;
        window.setAttributes(attributes);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        int width = activity.getResources().getDisplayMetrics().widthPixels;
        int height = activity.getResources().getDisplayMetrics().heightPixels;
        int maxWidth = dp(activity, 620);
        int margin = dp(activity, 24);
        window.setLayout(Math.min(maxWidth, width - margin * 2), Math.min(dp(activity, 680), Math.round(height * 0.88f)));
    }

    private interface KeyCaptureListener {
        boolean onKey(KeyEvent event);
    }

    private static final class ShortcutCaptureView extends TextView {
        private KeyCaptureListener listener;

        ShortcutCaptureView(Context context) {
            super(context);
            setFocusable(true);
            setFocusableInTouchMode(true);
        }

        void setOnKeyCapture(KeyCaptureListener listener) {
            this.listener = listener;
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent event) {
            return listener != null ? listener.onKey(event) : super.onKeyDown(keyCode, event);
        }

        @Override
        public boolean onKeyUp(int keyCode, KeyEvent event) {
            return true;
        }
    }
}
