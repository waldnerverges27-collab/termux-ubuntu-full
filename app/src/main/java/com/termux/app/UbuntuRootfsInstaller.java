package com.termux.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.StatFs;
import android.util.Log;
import android.widget.Toast;

import com.termux.R;
import com.termux.shared.errors.Error;
import com.termux.shared.file.FileUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.UUID;

/** Installs the bundled native ARM64 Ubuntu rootfs and its PRoot login command. */
public final class UbuntuRootfsInstaller {

    private static final String LOG_TAG = "UbuntuRootfsInstaller";
    private static final String ROOTFS_ASSET = "ubuntu-rootfs.tar.xz";
    private static final String ROOTFS_INFO_ASSET = "ubuntu-rootfs.info";
    private static final String ROOTFS_DIR = TermuxConstants.TERMUX_PREFIX_DIR_PATH
        + "/var/lib/proot-distro/installed-rootfs/ubuntu";
    private static final String EXTRACTED_ROOTFS_DIR = TermuxConstants.TERMUX_PREFIX_DIR_PATH
        + "/var/lib/proot-distro/installed-rootfs/ubuntu-rootfs";
    private static final String PROOT_DEST = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/proot";
    private static final String LOGIN_SCRIPT = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/login";
    private static final String LOGIN_SCRIPT_VERSION_MARKER = "# TERMUX_UBUNTU_LOGIN_VERSION=2";
    private static final String TERMUX_BIN = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH;
    private static final String TERMUX_LIB = TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH;
    private static final String TERMUX_SHARED_STORAGE_DIR = TermuxConstants.TERMUX_HOME_DIR_PATH + "/storage/shared";
    private static final String UBUNTU_SHARED_STORAGE_DIR = "/mnt/shared";
    private static final int DIAGNOSTIC_TAIL_BYTES = 4096;

    private UbuntuRootfsInstaller() {
    }

    public static File getRootfsDirectory() {
        return new File(ROOTFS_DIR);
    }

    public static String getLoginScriptPath() {
        return LOGIN_SCRIPT;
    }

    public static void installIfNeeded(final Activity activity, final Runnable whenDone) {
        if (activity == null || whenDone == null || activity.isFinishing()) return;
        if (!supportsArm64()) {
            showFailure(activity, whenDone, new InstallFailure(R.string.ubuntu_setup_error_arch,
                "UNSUPPORTED_ABI", "Device ABIs: " + java.util.Arrays.toString(Build.SUPPORTED_ABIS)));
            return;
        }
        if (isReady()) {
            activity.runOnUiThread(whenDone);
            return;
        }
        // A valid existing image only needs its small PRoot/login launcher repaired, not a full re-extract.
        if (isValidArm64Rootfs(new File(ROOTFS_DIR))) {
            startInstall(activity, null, whenDone);
            return;
        }

        final UbuntuRootfsManifest manifest;
        try {
            manifest = loadManifest(activity);
        } catch (Exception e) {
            showFailure(activity, whenDone, new InstallFailure(R.string.ubuntu_setup_error_manifest,
                "MANIFEST", "Could not read " + ROOTFS_INFO_ASSET + ": " + e));
            return;
        }

        try {
            ensureInstallDirectoriesWritable(activity);
        } catch (InstallFailure failure) {
            showFailure(activity, whenDone, failure);
            return;
        }

        long availableBytes = getMinimumAvailableBytes(new File(ROOTFS_DIR).getParentFile(), activity.getCacheDir());
        if (availableBytes >= 0 && availableBytes < manifest.getRecommendedFreeBytes()) {
            showLowStorageWarning(activity, manifest, availableBytes, whenDone);
            return;
        }
        startInstall(activity, manifest, whenDone);
    }

    public static boolean isReady() {
        return supportsArm64()
            && isValidArm64Rootfs(new File(ROOTFS_DIR))
            && isArm64Elf(new File(PROOT_DEST))
            && new File(PROOT_DEST).canExecute()
            && hasCurrentLoginScript(new File(LOGIN_SCRIPT));
    }

    private static void showLowStorageWarning(Activity activity, UbuntuRootfsManifest manifest,
                                              long availableBytes, Runnable whenDone) {
        if (activity.isFinishing()) return;
        String message = activity.getString(R.string.ubuntu_setup_space_message,
            formatBytes(manifest.getRecommendedFreeBytes()), formatBytes(availableBytes),
            formatBytes(manifest.compressedBytes), formatBytes(manifest.expandedBytes));
        new AlertDialog.Builder(activity)
            .setTitle(R.string.ubuntu_setup_space_title)
            .setMessage(message)
            .setPositiveButton(R.string.ubuntu_setup_continue_anyway,
                (dialog, which) -> startInstall(activity, manifest, whenDone))
            .setNegativeButton(R.string.ubuntu_setup_open_termux,
                (dialog, which) -> whenDone.run())
            .setNeutralButton(R.string.ubuntu_setup_retry,
                (dialog, which) -> installIfNeeded(activity, whenDone))
            .show();
    }

    private static void startInstall(final Activity activity, final UbuntuRootfsManifest manifest,
                                     final Runnable whenDone) {
        if (activity.isFinishing()) return;
        final ProgressDialog progress = new ProgressDialog(activity);
        progress.setTitle(R.string.ubuntu_setup_title);
        progress.setMessage(activity.getString(R.string.ubuntu_setup_stage_checking));
        progress.setIndeterminate(true);
        progress.setCancelable(false);
        try {
            progress.show();
        } catch (RuntimeException e) {
            showFailure(activity, whenDone, new InstallFailure(R.string.ubuntu_setup_error_generic,
                "PROGRESS_DIALOG", "Could not display setup progress: " + Log.getStackTraceString(e)));
            return;
        }

        new Thread(() -> {
            try {
                installRootfs(activity, manifest, progress);
                activity.runOnUiThread(() -> {
                    dismissProgress(progress);
                    if (!activity.isFinishing()) whenDone.run();
                });
            } catch (InstallFailure failure) {
                Logger.logErrorExtended(LOG_TAG, "Ubuntu setup failed (" + failure.code + "):\n" + failure.detail);
                activity.runOnUiThread(() -> {
                    dismissProgress(progress);
                    showFailure(activity, whenDone, failure);
                });
            } catch (Exception e) {
                Logger.logErrorExtended(LOG_TAG, "Unexpected Ubuntu setup failure:\n" + Log.getStackTraceString(e));
                InstallFailure failure = new InstallFailure(R.string.ubuntu_setup_error_generic,
                    "UNEXPECTED", Log.getStackTraceString(e));
                activity.runOnUiThread(() -> {
                    dismissProgress(progress);
                    showFailure(activity, whenDone, failure);
                });
            }
        }, "ubuntu-rootfs-installer").start();
    }

    private static void installRootfs(Activity activity, UbuntuRootfsManifest manifest,
                                      ProgressDialog progress) throws Exception {
        File rootfsDir = new File(ROOTFS_DIR);
        if (isValidArm64Rootfs(rootfsDir)) {
            updateProgress(activity, progress, R.string.ubuntu_setup_stage_verifying);
            try {
                installProotAndLoginScript(activity);
            } catch (Exception e) {
                throw new InstallFailure(R.string.ubuntu_setup_error_permission, "LAUNCHER",
                    "Ubuntu rootfs is present but its PRoot/login launcher could not be prepared: " + e);
            }
            return;
        }

        File rootfsParent = rootfsDir.getParentFile();
        File extractedRootfsDir = new File(EXTRACTED_ROOTFS_DIR);
        try {
            ensureWritableDirectory(rootfsParent, "Ubuntu installation directory");
        } catch (IOException e) {
            throw new InstallFailure(R.string.ubuntu_setup_error_permission, "PREPARE_DESTINATION", e.toString());
        }

        try {
            installProot(activity);
        } catch (Exception e) {
            throw new InstallFailure(R.string.ubuntu_setup_error_permission, "PROOT",
                "Could not install the bundled PRoot executable: " + e);
        }

        File tarball = new File(activity.getCacheDir(), ROOTFS_ASSET);
        if (tarball.exists() && !tarball.delete())
            throw new InstallFailure(R.string.ubuntu_setup_error_permission, "CACHE_CLEANUP",
                "Could not clear the temporary rootfs archive at " + tarball.getAbsolutePath());

        String preservedRootfsPath = null;
        try {
            updateProgress(activity, progress, R.string.ubuntu_setup_stage_copying);
            copyAndVerifyArchive(activity, tarball, manifest, progress);

            // Do not move aside an existing rootfs until the bundled archive has passed its hash check.
            String stagingCleanupError = deleteTemporaryRootfs(extractedRootfsDir);
            if (stagingCleanupError != null)
                throw new InstallFailure(R.string.ubuntu_setup_error_permission, "STAGING_CLEANUP",
                    "Could not clear previous installer staging data: " + stagingCleanupError);
            try {
                preservedRootfsPath = preserveIncompleteRootfs(rootfsDir);
            } catch (IOException e) {
                throw new InstallFailure(R.string.ubuntu_setup_error_permission, "PRESERVE_OLD_ROOTFS", e.toString());
            }

            updateProgress(activity, progress, R.string.ubuntu_setup_stage_extracting,
                formatBytes(manifest.expandedBytes));
            extractRootfs(tarball, rootfsParent);

            if (!extractedRootfsDir.isDirectory())
                throw new InstallFailure(R.string.ubuntu_setup_error_invalid_rootfs, "ARCHIVE_LAYOUT",
                    "Archive did not contain the expected ubuntu-rootfs/ directory.");
            if (!extractedRootfsDir.renameTo(rootfsDir))
                throw new InstallFailure(R.string.ubuntu_setup_error_permission, "ROOTFS_RENAME",
                    "Could not atomically move " + extractedRootfsDir + " to " + rootfsDir);

            updateProgress(activity, progress, R.string.ubuntu_setup_stage_verifying);
            if (!isValidArm64Rootfs(rootfsDir)) {
                String cleanupError = deleteTemporaryRootfs(rootfsDir);
                String detail = "Extracted rootfs did not pass ARM64 marker/bash checks.";
                if (cleanupError == null) detail += " The invalid extraction was removed.";
                else detail += " Could not remove the invalid extraction: " + cleanupError;
                throw new InstallFailure(R.string.ubuntu_setup_error_invalid_rootfs, "ROOTFS_VALIDATION", detail);
            }
            try {
                createLoginScript();
            } catch (IOException e) {
                throw new InstallFailure(R.string.ubuntu_setup_error_permission, "LOGIN_SCRIPT",
                    "Could not create the Ubuntu login command: " + e);
            }
        } catch (InstallFailure failure) {
            if (preservedRootfsPath != null) failure.recoveryPath = preservedRootfsPath;
            if (extractedRootfsDir.exists()) {
                String cleanupError = deleteTemporaryRootfs(extractedRootfsDir);
                if (cleanupError != null)
                    failure.detail += " Staging cleanup also failed: " + cleanupError;
            }
            if ("ROOTFS_VALIDATION".equals(failure.code) && rootfsDir.exists()) {
                String cleanupError = deleteTemporaryRootfs(rootfsDir);
                if (cleanupError != null)
                    failure.detail += " Cleanup retry also failed: " + cleanupError;
            }
            long freeBytes = getMinimumAvailableBytes(rootfsParent, activity.getCacheDir());
            if (freeBytes >= 0 && freeBytes < UbuntuRootfsManifest.SAFETY_MARGIN_BYTES
                && !"LOW_STORAGE".equals(failure.code)) {
                failure.messageResource = R.string.ubuntu_setup_error_storage;
                failure.code = "LOW_STORAGE";
            }
            throw failure;
        } finally {
            if (tarball.exists() && !tarball.delete())
                Logger.logWarn(LOG_TAG, "Could not remove cached Ubuntu archive: " + tarball.getAbsolutePath());
        }
    }

    private static void copyAndVerifyArchive(Activity activity, File destination,
                                             UbuntuRootfsManifest manifest, ProgressDialog progress) throws Exception {
        MessageDigest digest = newSha256Digest();
        long copiedBytes = 0;
        long lastReportedBucket = -1;
        InputStream assetInput;
        try {
            assetInput = activity.getAssets().open(ROOTFS_ASSET);
        } catch (IOException e) {
            throw new InstallFailure(R.string.ubuntu_setup_error_asset, "ROOTFS_ASSET_MISSING",
                "Unable to read bundled " + ROOTFS_ASSET + ": " + e);
        }
        try (InputStream input = assetInput; FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[256 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                digest.update(buffer, 0, count);
                copiedBytes += count;
                long bucket = manifest.compressedBytes > 0
                    ? (copiedBytes * 100 / manifest.compressedBytes) / 5 : 0;
                if (bucket != lastReportedBucket) {
                    lastReportedBucket = bucket;
                    updateProgress(activity, progress, R.string.ubuntu_setup_detail_copying,
                        formatBytes(copiedBytes), Math.min(100, (int) (copiedBytes * 100 / manifest.compressedBytes)));
                }
            }
            output.getFD().sync();
        } catch (IOException e) {
            throw new InstallFailure(R.string.ubuntu_setup_error_permission, "ARCHIVE_COPY",
                "Could not copy the bundled rootfs to app cache: " + e);
        }

        if (copiedBytes != manifest.compressedBytes)
            throw new InstallFailure(R.string.ubuntu_setup_error_manifest, "SIZE_MISMATCH",
                "Expected " + manifest.compressedBytes + " archive bytes but copied " + copiedBytes + ".");
        String actualHash = toHex(digest.digest());
        if (!manifest.sha256.equals(actualHash))
            throw new InstallFailure(R.string.ubuntu_setup_error_integrity, "SHA256_MISMATCH",
                "Expected SHA-256 " + manifest.sha256 + " but read " + actualHash + ".");
    }

    private static void extractRootfs(File tarball, File rootfsParent) throws InstallFailure {
        Process process = null;
        ByteArrayOutputStream outputTail = new ByteArrayOutputStream();
        try {
            ProcessBuilder builder = new ProcessBuilder(
                TERMUX_BIN + "/tar", "-xJf", tarball.getAbsolutePath(), "-C", rootfsParent.getAbsolutePath());
            builder.redirectErrorStream(true);
            builder.environment().put("LD_LIBRARY_PATH", TERMUX_LIB);
            builder.environment().put("PATH", TERMUX_BIN + ":/system/bin");
            builder.environment().put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
            process = builder.start();

            byte[] buffer = new byte[4096];
            int count;
            try (InputStream output = process.getInputStream()) {
                while ((count = output.read(buffer)) != -1) {
                    appendTail(outputTail, buffer, count);
                }
            }
            int exitCode = process.waitFor();
            if (exitCode != 0)
                throw new InstallFailure(R.string.ubuntu_setup_error_generic, "TAR_EXIT_" + exitCode,
                    "Rootfs extraction failed with tar exit code " + exitCode + ". Output: " + outputTail.toString("UTF-8"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroy();
            throw new InstallFailure(R.string.ubuntu_setup_error_generic, "EXTRACTION_INTERRUPTED",
                "Rootfs extraction was interrupted: " + e);
        } catch (IOException e) {
            throw new InstallFailure(R.string.ubuntu_setup_error_permission, "EXTRACTION_IO",
                "Could not run tar or write the rootfs to " + rootfsParent + ": " + e);
        }
    }

    private static void appendTail(ByteArrayOutputStream tail, byte[] bytes, int count) throws IOException {
        if (count >= DIAGNOSTIC_TAIL_BYTES) {
            tail.reset();
            tail.write(bytes, count - DIAGNOSTIC_TAIL_BYTES, DIAGNOSTIC_TAIL_BYTES);
            return;
        }
        byte[] old = tail.toByteArray();
        int keep = Math.min(old.length, DIAGNOSTIC_TAIL_BYTES - count);
        tail.reset();
        if (keep > 0) tail.write(old, old.length - keep, keep);
        tail.write(bytes, 0, count);
    }

    private static void ensureInstallDirectoriesWritable(Activity activity) throws InstallFailure {
        try {
            ensureWritableDirectory(new File(ROOTFS_DIR).getParentFile(), "Ubuntu installation directory");
            ensureWritableDirectory(activity.getCacheDir(), "Termux temporary directory");
        } catch (IOException e) {
            throw new InstallFailure(R.string.ubuntu_setup_error_permission, "WRITE_PERMISSION", e.toString());
        }
    }

    private static void ensureWritableDirectory(File directory, String label) throws IOException {
        if (directory == null || (!directory.isDirectory() && !directory.mkdirs()) || !directory.canWrite())
            throw new IOException("Android cannot write the " + label + " at " + directory + ".");
        File probe = new File(directory, ".ubuntu-write-check-" + UUID.randomUUID());
        try (FileOutputStream output = new FileOutputStream(probe)) {
            output.write(0);
            output.getFD().sync();
        } finally {
            if (probe.exists() && !probe.delete())
                Logger.logWarn(LOG_TAG, "Could not remove write-check file: " + probe);
        }
    }

    private static String preserveIncompleteRootfs(File existing) throws IOException {
        if (existing == null || !existing.exists()) return null;
        File parent = existing.getParentFile();
        if (parent == null) throw new IOException("Cannot preserve rootfs without a parent directory: " + existing);
        File recovery;
        do {
            recovery = new File(parent, "ubuntu-recovery-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8));
        } while (recovery.exists());
        if (!existing.renameTo(recovery))
            throw new IOException("Could not preserve existing rootfs at " + existing + ". Check storage permissions.");
        Logger.logWarn(LOG_TAG, "Preserved incomplete Ubuntu rootfs at " + recovery.getAbsolutePath());
        return recovery.getAbsolutePath();
    }

    /** Removes only installer-owned temporary extraction data; existing invalid user data is renamed above. */
    private static String deleteTemporaryRootfs(File directory) {
        if (directory == null) return null;
        Error error = FileUtils.deleteFile("incomplete Ubuntu extraction", directory.getAbsolutePath(), true);
        return error == null ? null : Error.getMinimalErrorString(error);
    }

    private static long getMinimumAvailableBytes(File first, File second) {
        long firstAvailable = getAvailableBytes(first);
        long secondAvailable = getAvailableBytes(second);
        if (firstAvailable < 0) return secondAvailable;
        if (secondAvailable < 0) return firstAvailable;
        return Math.min(firstAvailable, secondAvailable);
    }

    private static long getAvailableBytes(File directory) {
        if (directory == null) return -1;
        try {
            StatFs statFs = new StatFs(directory.getAbsolutePath());
            return statFs.getAvailableBytes();
        } catch (RuntimeException e) {
            Logger.logWarn(LOG_TAG, "Could not query available storage at " + directory + ": " + e);
            return -1;
        }
    }

    private static UbuntuRootfsManifest loadManifest(Context context) throws IOException {
        try (InputStream input = context.getAssets().open(ROOTFS_INFO_ASSET)) {
            return UbuntuRootfsManifest.parse(input);
        }
    }

    private static void showFailure(Activity activity, Runnable whenDone, InstallFailure failure) {
        if (activity == null || activity.isFinishing()) return;
        String message = activity.getString(failure.messageResource);
        StringBuilder fullMessage = new StringBuilder(message);
        if (failure.recoveryPath != null)
            fullMessage.append("\n\n").append(activity.getString(R.string.ubuntu_setup_recovery_preserved, failure.recoveryPath));

        String diagnostics = buildDiagnostics(activity, failure, fullMessage.toString());
        new AlertDialog.Builder(activity)
            .setTitle(R.string.ubuntu_setup_error_title)
            .setMessage(fullMessage.toString())
            .setPositiveButton(R.string.ubuntu_setup_retry,
                (dialog, which) -> installIfNeeded(activity, whenDone))
            .setNegativeButton(R.string.ubuntu_setup_open_termux,
                (dialog, which) -> whenDone.run())
            .setNeutralButton(R.string.ubuntu_setup_copy_diagnostics, (dialog, which) -> {
                copyDiagnostics(activity, diagnostics);
                activity.getWindow().getDecorView().post(() -> showFailure(activity, whenDone, failure));
            })
            .show();
    }

    private static String buildDiagnostics(Activity activity, InstallFailure failure, String message) {
        StringBuilder report = new StringBuilder();
        report.append(activity.getString(R.string.ubuntu_setup_diagnostics_label)).append('\n')
            .append("Code: ").append(failure.code).append('\n')
            .append("Message: ").append(message).append('\n')
            .append("Android API: ").append(Build.VERSION.SDK_INT).append('\n')
            .append("Device ABIs: ").append(java.util.Arrays.toString(Build.SUPPORTED_ABIS)).append('\n')
            .append("Rootfs: ").append(ROOTFS_DIR).append('\n')
            .append("Detail: ").append(failure.detail);
        return report.toString();
    }

    private static void copyDiagnostics(Activity activity, String diagnostics) {
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(activity.getString(R.string.ubuntu_setup_diagnostics_label), diagnostics));
            Toast.makeText(activity, R.string.ubuntu_setup_diagnostics_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private static void dismissProgress(ProgressDialog progress) {
        try {
            if (progress != null && progress.isShowing()) progress.dismiss();
        } catch (RuntimeException ignored) {
            // Activity may already have been destroyed.
        }
    }

    private static void updateProgress(Activity activity, ProgressDialog progress, int resource, Object... arguments) {
        activity.runOnUiThread(() -> {
            try {
                if (progress != null && progress.isShowing()) {
                    progress.setMessage(arguments.length == 0
                        ? activity.getString(resource)
                        : activity.getString(resource, arguments));
                }
            } catch (RuntimeException ignored) {
                // Activity may already be finishing.
            }
        });
    }

    private static void installProotAndLoginScript(Activity activity) throws Exception {
        if (!supportsArm64())
            throw new IOException("The bundled PRoot executable requires arm64-v8a.");
        installProot(activity);
        createLoginScript();
    }

    private static void installProot(Context context) throws Exception {
        File proot = new File(PROOT_DEST);
        if (!isArm64Elf(proot))
            copyAssetToFile(context, "proot-aarch64", proot);
        if (!proot.setExecutable(true, false))
            throw new IOException("Could not make the ARM64 PRoot binary executable.");
        if (!isArm64Elf(proot))
            throw new IOException("The bundled PRoot asset is not a valid AArch64 ELF binary.");
    }

    private static void copyAssetToFile(Context context, String assetName, File destination) throws Exception {
        File parent = destination.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs())
            throw new IOException("Could not create asset destination directory: " + parent.getAbsolutePath());
        try (InputStream input = context.getAssets().open(assetName);
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.getFD().sync();
        }
    }

    private static void createLoginScript() throws IOException {
        File script = new File(LOGIN_SCRIPT);
        File parent = script.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs())
            throw new IOException("Could not create the Termux bin directory.");

        File sharedMountPoint = new File(ROOTFS_DIR, "mnt/shared");
        if (!sharedMountPoint.isDirectory() && !sharedMountPoint.mkdirs())
            throw new IOException("Could not create the Ubuntu shared-storage mount point.");

        String content = "#!/data/data/com.termux/files/usr/bin/sh\n"
            + LOGIN_SCRIPT_VERSION_MARKER + "\n"
            + "unset LD_PRELOAD\n"
            + "set -- --kill-on-exit --link2symlink -0 -E LD_LIBRARY_PATH= "
            + "-r " + ROOTFS_DIR + " -b /dev -b /proc -b /sys -w /\n"
            + "if [ -d \"" + TERMUX_SHARED_STORAGE_DIR + "\" ]; then\n"
            + "  set -- \"$@\" -b \"" + TERMUX_SHARED_STORAGE_DIR + ":" + UBUNTU_SHARED_STORAGE_DIR + "\"\n"
            + "fi\n"
            + "WORKDIR=\"${TERMUX_UBUNTU_WORKDIR:-/root}\"\n"
            + "WORKSPACE_ENV=\"\"\n"
            + "if [ -n \"${TERMUX_UBUNTU_ENV_FILE:-}\" ] && [ -f \"$TERMUX_UBUNTU_ENV_FILE\" ]; then\n"
            + "  set -- \"$@\" -b \"$TERMUX_UBUNTU_ENV_FILE:/tmp/termux-workspace-env\"\n"
            + "  WORKSPACE_ENV=/tmp/termux-workspace-env\n"
            + "fi\n"
            + "exec /data/data/com.termux/files/usr/bin/proot \"$@\" /usr/bin/env -i "
            + "HOME=/root "
            + "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin "
            + "TERM=\"${TERM:-xterm-256color}\" "
            + "LANG=C.UTF-8 TMPDIR=/tmp "
            + "TERMUX_UBUNTU_WORKDIR=\"$WORKDIR\" TERMUX_UBUNTU_ENV_FILE=\"$WORKSPACE_ENV\" "
            + "/bin/bash -l -c '"
            + "if [ -n \"$TERMUX_UBUNTU_ENV_FILE\" ] && [ -r \"$TERMUX_UBUNTU_ENV_FILE\" ]; then . \"$TERMUX_UBUNTU_ENV_FILE\"; fi; "
            + "if [ -n \"$TERMUX_UBUNTU_WORKDIR\" ] && [ -d \"$TERMUX_UBUNTU_WORKDIR\" ]; then cd -- \"$TERMUX_UBUNTU_WORKDIR\"; else cd \"$HOME\"; fi; "
            + "exec /bin/bash -i'\n";
        File temporary = new File(parent, "login.tmp-" + UUID.randomUUID());
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(content.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        if (!temporary.setExecutable(true, false)) {
            temporary.delete();
            throw new IOException("Could not make the Ubuntu login command executable.");
        }
        if (!temporary.renameTo(script)) {
            temporary.delete();
            throw new IOException("Could not atomically replace the Ubuntu login command.");
        }
    }

    private static boolean hasCurrentLoginScript(File script) {
        if (script == null || !script.isFile() || !script.canExecute()) return false;
        try (InputStream input = new FileInputStream(script)) {
            byte[] bytes = new byte[8192];
            int count = input.read(bytes);
            if (count <= 0) return false;
            return new String(bytes, 0, count, StandardCharsets.UTF_8).contains(LOGIN_SCRIPT_VERSION_MARKER);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isValidArm64Rootfs(File rootfsDir) {
        File bash = new File(rootfsDir, "bin/bash");
        File marker = new File(rootfsDir, "etc/termux-ubuntu-rootfs");
        if (!rootfsDir.isDirectory() || !bash.isFile() || !marker.isFile() || !isArm64Elf(bash)) return false;
        try (InputStream input = new FileInputStream(marker)) {
            byte[] bytes = new byte[4096];
            int count = input.read(bytes);
            if (count <= 0) return false;
            String markerText = new String(bytes, 0, count, StandardCharsets.UTF_8);
            boolean hasArm64 = false;
            for (String line : markerText.split("\\r?\\n")) {
                if ("ARCH=arm64".equals(line.trim())) hasArm64 = true;
            }
            return hasArm64;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean supportsArm64() {
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) return true;
        }
        return false;
    }

    private static boolean isArm64Elf(File file) {
        if (!file.isFile()) return false;
        try (InputStream input = new FileInputStream(file)) {
            byte[] header = new byte[20];
            int offset = 0;
            while (offset < header.length) {
                int count = input.read(header, offset, header.length - offset);
                if (count < 0) return false;
                offset += count;
            }
            return header[0] == 0x7f && header[1] == 'E' && header[2] == 'L' && header[3] == 'F'
                && header[4] == 2 && header[5] == 1
                && (header[18] & 0xff) == 183 && header[19] == 0;
        } catch (IOException e) {
            return false;
        }
    }

    private static MessageDigest newSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable on this Android device.", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 0) return "unknown";
        double gib = bytes / (1024d * 1024d * 1024d);
        if (gib >= 1d) return String.format(Locale.getDefault(), "%.2f GiB", gib);
        double mib = bytes / (1024d * 1024d);
        return String.format(Locale.getDefault(), "%.0f MiB", mib);
    }

    private static final class InstallFailure extends Exception {
        int messageResource;
        String code;
        String detail;
        String recoveryPath;

        InstallFailure(int messageResource, String code, String detail) {
            this(messageResource, code, detail, null);
        }

        InstallFailure(int messageResource, String code, String detail, String recoveryPath) {
            super(detail);
            this.messageResource = messageResource;
            this.code = code;
            this.detail = detail;
            this.recoveryPath = recoveryPath;
        }
    }
}
