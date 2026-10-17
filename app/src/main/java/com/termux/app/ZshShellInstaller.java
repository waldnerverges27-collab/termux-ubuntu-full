package com.termux.app;

import android.app.Activity;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Installs and reports the optional Zsh module in the Termux host and Ubuntu rootfs. */
public final class ZshShellInstaller {

    private static final String LOG_TAG = "ZshShellInstaller";
    private static final String SCRIPT_ASSET = "zsh-shell-module.sh";
    private static final String MODULE_DIRECTORY = ".local/share/termux-zsh";
    private static final String PLUGIN_DIRECTORY = MODULE_DIRECTORY + "/plugins";
    private static final String TERMUX_HOME = TermuxConstants.TERMUX_HOME_DIR_PATH;
    private static final String TERMUX_BIN = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH;
    private static final int DIAGNOSTIC_TAIL_LINES = 24;

    private static final String[] REQUESTED_PLUGINS = new String[]{
        "zsh-defer",
        "zsh-autosuggestions",
        "zsh-syntax-highlighting",
        "zsh-history-substring-search",
        "zsh-completions",
        "fzf-tab",
        "zsh-you-should-use",
        "zsh-autopair",
        "zsh-better-npm-completion"
    };

    public interface Listener {
        void onProgress(@NonNull String message);
        void onComplete(boolean success, @NonNull String details);
    }

    private ZshShellInstaller() {
    }

    @NonNull
    public static String[] getRequestedPlugins() {
        return REQUESTED_PLUGINS.clone();
    }

    /** Snapshot of installation/default status for both shell environments. */
    @NonNull
    public static Status getStatus() {
        File ubuntuRoot = UbuntuRootfsInstaller.getRootfsDirectory();
        File termuxHome = new File(TERMUX_HOME);
        File ubuntuHome = new File(ubuntuRoot, "root");
        File termuxZsh = new File(TERMUX_BIN, "zsh");
        File ubuntuZsh = firstExistingExecutable(new File(ubuntuRoot, "usr/bin/zsh"),
            new File(ubuntuRoot, "bin/zsh"));

        Map<String, Boolean> termuxPlugins = getPluginStatus(new File(termuxHome, PLUGIN_DIRECTORY));
        Map<String, Boolean> ubuntuPlugins = getPluginStatus(new File(ubuntuHome, PLUGIN_DIRECTORY));
        boolean termuxTheme = hasTheme(new File(termuxHome, PLUGIN_DIRECTORY));
        boolean ubuntuTheme = hasTheme(new File(ubuntuHome, PLUGIN_DIRECTORY));
        String termuxDefault = readFirstLine(new File(termuxHome, ".termux/shell"));
        String ubuntuDefault = readFirstLine(new File(ubuntuRoot, "etc/termux-zsh/default-shell"));

        return new Status(termuxZsh.canExecute(), termuxZsh.canExecute() && "zsh".equals(termuxDefault),
            ubuntuZsh != null, ubuntuZsh != null && "zsh".equals(ubuntuDefault), termuxTheme, ubuntuTheme,
            termuxPlugins, ubuntuPlugins, UbuntuRootfsInstaller.isReady());
    }

    /** Install the module in both environments. Ubuntu must already be prepared. */
    public static void installBoth(@NonNull Activity activity, @Nullable Listener listener) {
        if (!UbuntuRootfsInstaller.isReady()) {
            completeOnUiThread(activity, listener, false,
                "Ubuntu is not ready. Prepare the bundled rootfs, then retry the Zsh module installation.");
            return;
        }
        runOperations(activity, listener, true);
    }

    /** Restore Bash as the default without uninstalling Zsh or deleting plugin/configuration files. */
    public static void restoreBashDefaults(@NonNull Activity activity, @Nullable Listener listener) {
        runOperations(activity, listener, false);
    }

    private static void runOperations(@NonNull Activity activity, @Nullable Listener listener,
                                      boolean installZsh) {
        Context context = activity.getApplicationContext();
        new Thread(() -> {
            List<String> errors = new ArrayList<>();
            File script = null;
            try {
                script = copyInstallerScript(context);
                if (installZsh) {
                    errors.addAll(runTarget(activity, context, script, "termux", "install", listener));
                    errors.addAll(runTarget(activity, context, script, "ubuntu", "install", listener));
                } else {
                    errors.addAll(runTarget(activity, context, script, "termux", "restore-bash", listener));
                    if (UbuntuRootfsInstaller.isReady())
                        errors.addAll(runTarget(activity, context, script, "ubuntu", "restore-bash", listener));
                }
            } catch (Exception e) {
                Logger.logErrorExtended(LOG_TAG, "Zsh module operation failed: " + e);
                errors.add(e.getMessage() == null ? e.toString() : e.getMessage());
            } finally {
                if (script != null && script.exists() && !script.delete())
                    Logger.logWarn(LOG_TAG, "Could not remove temporary Zsh module script: " + script);
            }

            StringBuilder details = new StringBuilder();
            for (String error : errors) {
                if (details.length() > 0) details.append('\n');
                details.append(error);
            }
            boolean success = errors.isEmpty();
            String result = success ? "" : details.toString();
            activity.runOnUiThread(() -> {
                if (!activity.isFinishing() && listener != null) listener.onComplete(success, result);
            });
        }, installZsh ? "termux-zsh-install" : "termux-zsh-restore-bash").start();
    }

    private static List<String> runTarget(Activity activity, Context context, File script,
                                          String target, String action, @Nullable Listener listener) {
        List<String> errors = new ArrayList<>();
        String label = "termux".equals(target) ? "Termux host" : "Ubuntu";
        reportProgress(activity, listener,
            "install".equals(action) ? "Preparing Zsh in " + label + "…" : "Restoring Bash in " + label + "…");
        try {
            ProcessResult result = executeScript(context, script, target, action,
                message -> reportProgress(activity, listener, message));
            if (result.exitCode != 0) {
                String detail = label + " returned exit code " + result.exitCode;
                if (!result.outputTail.isEmpty()) detail += ":\n" + result.outputTail;
                errors.add(detail);
                Logger.logErrorExtended(LOG_TAG, detail);
            }
        } catch (Exception e) {
            String detail = label + ": " + (e.getMessage() == null ? e.toString() : e.getMessage());
            errors.add(detail);
            Logger.logErrorExtended(LOG_TAG, detail);
        }
        return errors;
    }

    private static ProcessResult executeScript(Context context, File script, String target, String action,
                                               ProgressReporter progressReporter) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        if ("termux".equals(target)) {
            command.add(TERMUX_BIN + "/bash");
            command.add(script.getAbsolutePath());
            command.add(target);
            command.add(action);
        } else {
            command.add(TERMUX_BIN + "/proot");
            Collections.addAll(command, "--kill-on-exit", "--link2symlink", "-0", "-r",
                UbuntuRootfsInstaller.getRootfsDirectory().getAbsolutePath(),
                "-b", "/dev", "-b", "/proc", "-b", "/sys",
                "-b", script.getAbsolutePath() + ":/tmp/termux-zsh-module.sh",
                "-w", "/", "/bin/bash", "/tmp/termux-zsh-module.sh", target, action);
        }

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        File homeDirectory = new File(TERMUX_HOME);
        if (homeDirectory.isDirectory()) processBuilder.directory(homeDirectory);
        Map<String, String> environment = processBuilder.environment();
        environment.clear();
        HashMap<String, String> termuxEnvironment = new TermuxShellEnvironment().getEnvironment(context, false);
        for (Map.Entry<String, String> entry : termuxEnvironment.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null)
                environment.put(entry.getKey(), entry.getValue());
        }
        environment.put("HOME", TERMUX_HOME);
        environment.put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
        environment.put("PATH", TERMUX_BIN);
        environment.put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
        environment.put("TERM", "xterm-256color");
        environment.put("LANG", "C.UTF-8");
        environment.remove("LD_PRELOAD");
        environment.remove("LD_LIBRARY_PATH");

        Process process = processBuilder.start();
        try (OutputStream input = process.getOutputStream()) {
            // The installer is passed as a private, read-only-by-convention file, not through stdin.
        }

        ArrayDeque<String> tail = new ArrayDeque<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("TERMUX_ZSH_PROGRESS:")) {
                    progressReporter.onProgress(line.substring("TERMUX_ZSH_PROGRESS:".length()).trim());
                } else if (!line.trim().isEmpty()) {
                    if (tail.size() == DIAGNOSTIC_TAIL_LINES) tail.removeFirst();
                    tail.addLast(line);
                }
            }
        } catch (IOException e) {
            process.destroy();
            throw e;
        }

        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            process.destroy();
            Thread.currentThread().interrupt();
            throw e;
        }
        StringBuilder outputTail = new StringBuilder();
        for (String line : tail) {
            if (outputTail.length() > 0) outputTail.append('\n');
            outputTail.append(line);
        }
        return new ProcessResult(exitCode, outputTail.toString());
    }

    private static File copyInstallerScript(Context context) throws IOException {
        File script = File.createTempFile("termux-zsh-module-", ".sh", context.getCacheDir());
        try (InputStream input = context.getAssets().open(SCRIPT_ASSET);
             FileOutputStream output = new FileOutputStream(script)) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.getFD().sync();
        } catch (IOException e) {
            if (script.exists()) script.delete();
            throw e;
        }
        return script;
    }

    private static void reportProgress(Activity activity, @Nullable Listener listener, String message) {
        activity.runOnUiThread(() -> {
            if (!activity.isFinishing() && listener != null) listener.onProgress(message);
        });
    }

    private static void completeOnUiThread(Activity activity, @Nullable Listener listener,
                                           boolean success, String details) {
        activity.runOnUiThread(() -> {
            if (!activity.isFinishing() && listener != null) listener.onComplete(success, details);
        });
    }

    private static File firstExistingExecutable(File first, File second) {
        if (first.isFile() && first.canExecute()) return first;
        if (second.isFile() && second.canExecute()) return second;
        return null;
    }

    private static Map<String, Boolean> getPluginStatus(File pluginRoot) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (String plugin : REQUESTED_PLUGINS) {
            String entry = requiredPluginEntry(plugin);
            File requiredPath = new File(new File(pluginRoot, plugin), entry);
            result.put(plugin, "src".equals(entry) ? requiredPath.isDirectory() : requiredPath.isFile());
        }
        return result;
    }

    private static String requiredPluginEntry(String plugin) {
        switch (plugin) {
            case "zsh-defer": return "zsh-defer.plugin.zsh";
            case "zsh-autosuggestions": return "zsh-autosuggestions.zsh";
            case "zsh-syntax-highlighting": return "zsh-syntax-highlighting.zsh";
            case "zsh-history-substring-search": return "zsh-history-substring-search.zsh";
            case "zsh-completions": return "src";
            case "fzf-tab": return "fzf-tab.plugin.zsh";
            case "zsh-you-should-use": return "you-should-use.plugin.zsh";
            case "zsh-autopair": return "autopair.zsh";
            case "zsh-better-npm-completion": return "zsh-better-npm-completion.plugin.zsh";
            default: return "";
        }
    }

    private static boolean hasTheme(File pluginRoot) {
        return new File(new File(pluginRoot, "powerlevel10k"), "powerlevel10k.zsh-theme").isFile();
    }

    @Nullable
    private static String readFirstLine(File file) {
        if (file == null || !file.isFile()) return null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            return line == null ? null : line.trim();
        } catch (IOException e) {
            return null;
        }
    }

    public static final class Status {
        public final boolean termuxZshInstalled;
        public final boolean termuxDefaultZsh;
        public final boolean ubuntuZshInstalled;
        public final boolean ubuntuDefaultZsh;
        public final boolean termuxThemeInstalled;
        public final boolean ubuntuThemeInstalled;
        public final Map<String, Boolean> termuxPlugins;
        public final Map<String, Boolean> ubuntuPlugins;
        public final boolean ubuntuReady;

        Status(boolean termuxZshInstalled, boolean termuxDefaultZsh,
               boolean ubuntuZshInstalled, boolean ubuntuDefaultZsh,
               boolean termuxThemeInstalled, boolean ubuntuThemeInstalled,
               Map<String, Boolean> termuxPlugins, Map<String, Boolean> ubuntuPlugins,
               boolean ubuntuReady) {
            this.termuxZshInstalled = termuxZshInstalled;
            this.termuxDefaultZsh = termuxDefaultZsh;
            this.ubuntuZshInstalled = ubuntuZshInstalled;
            this.ubuntuDefaultZsh = ubuntuDefaultZsh;
            this.termuxThemeInstalled = termuxThemeInstalled;
            this.ubuntuThemeInstalled = ubuntuThemeInstalled;
            this.termuxPlugins = Collections.unmodifiableMap(new LinkedHashMap<>(termuxPlugins));
            this.ubuntuPlugins = Collections.unmodifiableMap(new LinkedHashMap<>(ubuntuPlugins));
            this.ubuntuReady = ubuntuReady;
        }

        public boolean isPluginInstalled(boolean inUbuntu, String pluginName) {
            Boolean installed = (inUbuntu ? ubuntuPlugins : termuxPlugins).get(pluginName);
            return installed != null && installed;
        }
    }

    private interface ProgressReporter {
        void onProgress(String message);
    }

    private static final class ProcessResult {
        final int exitCode;
        final String outputTail;

        ProcessResult(int exitCode, String outputTail) {
            this.exitCode = exitCode;
            this.outputTail = outputTail;
        }
    }
}
