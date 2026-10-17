package com.termux.app.terminal;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.view.ViewGroup;

import com.termux.R;
import com.termux.app.UbuntuRootfsInstaller;
import com.termux.app.ZshShellInstaller;

/** Settings dialog for the optional Zsh shell module and its per-environment status. */
public final class ZshShellDialog {

    private ZshShellDialog() {
    }

    public static void show(Activity activity) {
        if (activity == null || activity.isFinishing()) return;

        ScrollView scrollView = new ScrollView(activity);
        scrollView.setFillViewport(true);
        TextView message = new TextView(activity);
        message.setText(buildStatusMessage(activity, ZshShellInstaller.getStatus()));
        message.setTextSize(14);
        message.setTextIsSelectable(true);
        int padding = dp(activity, 16);
        message.setPadding(padding, padding, padding, padding);
        scrollView.addView(message, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int maxHeight = Math.min(dp(activity, 520), Math.round(activity.getResources()
            .getDisplayMetrics().heightPixels * 0.68f));
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, maxHeight));

        AlertDialog dialog = new AlertDialog.Builder(activity)
            .setTitle(R.string.zsh_module_dialog_title)
            .setView(scrollView)
            .setPositiveButton(R.string.zsh_module_install_both, null)
            .setNeutralButton(R.string.zsh_module_restore_bash, null)
            .setNegativeButton(R.string.zsh_module_close, null)
            .create();
        dialog.setOnShowListener(ignored -> {
            Button install = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (install != null) install.setOnClickListener(view -> {
                dialog.dismiss();
                beginInstall(activity);
            });
            Button restore = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            if (restore != null) restore.setOnClickListener(view -> {
                dialog.dismiss();
                beginRestoreBash(activity);
            });
        });
        dialog.show();
    }

    private static String buildStatusMessage(Activity activity, ZshShellInstaller.Status status) {
        StringBuilder result = new StringBuilder();
        result.append(activity.getString(R.string.zsh_module_dialog_intro));
        result.append("\n\n");
        result.append(activity.getString(R.string.zsh_module_termux_status,
            getDefaultStatus(activity, status.termuxZshInstalled, status.termuxDefaultZsh)));
        result.append('\n');
        result.append(activity.getString(R.string.zsh_module_ubuntu_status,
            status.ubuntuReady
                ? getDefaultStatus(activity, status.ubuntuZshInstalled, status.ubuntuDefaultZsh)
                : activity.getString(R.string.zsh_module_ubuntu_not_ready)));
        result.append("\n\n");
        result.append(activity.getString(R.string.zsh_module_theme_row,
            status.termuxThemeInstalled ? activity.getString(R.string.zsh_module_installed)
                : activity.getString(R.string.zsh_module_missing),
            status.ubuntuThemeInstalled ? activity.getString(R.string.zsh_module_installed)
                : activity.getString(R.string.zsh_module_missing)));
        result.append('\n');
        for (String plugin : ZshShellInstaller.getRequestedPlugins()) {
            result.append(activity.getString(R.string.zsh_module_plugin_row,
                plugin,
                status.isPluginInstalled(false, plugin) ? activity.getString(R.string.zsh_module_installed)
                    : activity.getString(R.string.zsh_module_missing),
                status.isPluginInstalled(true, plugin) ? activity.getString(R.string.zsh_module_installed)
                    : activity.getString(R.string.zsh_module_missing)));
            result.append('\n');
        }
        result.append("\n");
        result.append(activity.getString(R.string.zsh_module_safety_note));
        return result.toString();
    }

    private static String getDefaultStatus(Activity activity, boolean zshInstalled, boolean defaultZsh) {
        if (defaultZsh) return activity.getString(R.string.zsh_module_state_zsh_default);
        if (zshInstalled) return activity.getString(R.string.zsh_module_state_installed_not_default);
        return activity.getString(R.string.zsh_module_state_not_installed);
    }

    private static void beginInstall(Activity activity) {
        if (activity.isFinishing()) return;
        if (!UbuntuRootfsInstaller.isReady()) {
            new AlertDialog.Builder(activity)
                .setTitle(R.string.zsh_module_ubuntu_required_title)
                .setMessage(R.string.zsh_module_ubuntu_required_message)
                .setPositiveButton(R.string.zsh_module_prepare_ubuntu, (dialog, which) ->
                    UbuntuRootfsInstaller.installIfNeeded(activity, () -> {
                        if (activity.isFinishing()) return;
                        if (UbuntuRootfsInstaller.isReady()) beginInstall(activity);
                        else {
                            Toast.makeText(activity, R.string.zsh_module_ubuntu_not_ready,
                                Toast.LENGTH_LONG).show();
                            show(activity);
                        }
                    }))
                .setNegativeButton(R.string.zsh_module_close, null)
                .show();
            return;
        }

        ProgressDialog progress = createProgressDialog(activity, R.string.zsh_module_install_progress);
        ZshShellInstaller.installBoth(activity, new ZshShellInstaller.Listener() {
            @Override
            public void onProgress(String message) {
                if (!activity.isFinishing()) progress.setMessage(message);
            }

            @Override
            public void onComplete(boolean success, String details) {
                if (progress.isShowing()) progress.dismiss();
                if (activity.isFinishing()) return;
                if (success) {
                    Toast.makeText(activity, R.string.zsh_module_install_success, Toast.LENGTH_LONG).show();
                    show(activity);
                } else {
                    showFailure(activity, details, true);
                }
            }
        });
    }

    private static void beginRestoreBash(Activity activity) {
        if (activity.isFinishing()) return;
        ProgressDialog progress = createProgressDialog(activity, R.string.zsh_module_restore_progress);
        ZshShellInstaller.restoreBashDefaults(activity, new ZshShellInstaller.Listener() {
            @Override
            public void onProgress(String message) {
                if (!activity.isFinishing()) progress.setMessage(message);
            }

            @Override
            public void onComplete(boolean success, String details) {
                if (progress.isShowing()) progress.dismiss();
                if (activity.isFinishing()) return;
                if (success) {
                    Toast.makeText(activity, R.string.zsh_module_restore_success, Toast.LENGTH_LONG).show();
                    show(activity);
                } else {
                    showFailure(activity, details, false);
                }
            }
        });
    }

    private static ProgressDialog createProgressDialog(Activity activity, int messageResource) {
        ProgressDialog progress = new ProgressDialog(activity);
        progress.setTitle(R.string.zsh_module_dialog_title);
        progress.setMessage(activity.getString(messageResource));
        progress.setIndeterminate(true);
        progress.setCancelable(false);
        progress.show();
        return progress;
    }

    private static void showFailure(Activity activity, String details, boolean installFailure) {
        if (activity.isFinishing()) return;
        int title = installFailure ? R.string.zsh_module_install_error_title
            : R.string.zsh_module_restore_error_title;
        int okLabel = R.string.zsh_module_close;
        String message = details == null || details.trim().isEmpty()
            ? activity.getString(R.string.zsh_module_operation_failed)
            : details;
        new AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(okLabel, (dialog, which) -> show(activity))
            .setNegativeButton(R.string.zsh_module_close, null)
            .show();
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
