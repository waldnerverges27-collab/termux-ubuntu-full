package com.termux.app;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.termux.shared.errors.Error;
import com.termux.shared.file.FileUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Installs the bundled native ARM64 Ubuntu rootfs and its PRoot login command. */
final class UbuntuRootfsInstaller {

    private static final String LOG_TAG = "UbuntuRootfsInstaller";
    private static final String ROOTFS_ASSET = "ubuntu-rootfs.tar.xz";
    private static final String ROOTFS_DIR = TermuxConstants.TERMUX_PREFIX_DIR_PATH
        + "/var/lib/proot-distro/installed-rootfs/ubuntu";
    private static final String EXTRACTED_ROOTFS_DIR = TermuxConstants.TERMUX_PREFIX_DIR_PATH
        + "/var/lib/proot-distro/installed-rootfs/ubuntu-rootfs";
    private static final String PROOT_DEST = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/proot";
    private static final String LOGIN_SCRIPT = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/login";
    private static final String TERMUX_BIN = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH;
    private static final String TERMUX_LIB = TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH;

    private UbuntuRootfsInstaller() {}

    static void installIfNeeded(final Activity activity, final Runnable whenDone) {
        File rootfsDir = new File(ROOTFS_DIR);
        if (isValidArm64Rootfs(rootfsDir)) {
            try {
                installProotAndLoginScript(activity);
            } catch (Exception e) {
                Logger.logErrorExtended(LOG_TAG, "Failed to prepare Ubuntu login: " + Log.getStackTraceString(e));
            }
            activity.runOnUiThread(whenDone);
            return;
        }

        final ProgressDialog progress = ProgressDialog.show(activity, null,
            "Installing Ubuntu ARM64 rootfs...", true, false);
        new Thread(() -> {
            try {
                installRootfs(activity);
            } catch (Exception e) {
                Logger.logErrorExtended(LOG_TAG, "Ubuntu rootfs install failed:\n" + Log.getStackTraceString(e));
                activity.runOnUiThread(() -> {
                    try {
                        progress.dismiss();
                    } catch (RuntimeException ignored) {
                        // Activity may already have been dismissed.
                    }
                    whenDone.run(); // Fall back to a regular Termux shell.
                });
                return;
            }

            activity.runOnUiThread(() -> {
                try {
                    progress.dismiss();
                } catch (RuntimeException ignored) {
                    // Activity may already have been dismissed.
                }
                whenDone.run();
            });
        }, "ubuntu-rootfs-installer").start();
    }

    private static void installRootfs(Activity activity) throws Exception {
        if (!supportsArm64())
            throw new IOException("This APK contains the native ARM64 Ubuntu rootfs and requires an arm64-v8a device.");

        File rootfsDir = new File(ROOTFS_DIR);
        File extractedRootfsDir = new File(EXTRACTED_ROOTFS_DIR);
        File rootfsParent = rootfsDir.getParentFile();
        if (rootfsParent == null || (!rootfsParent.isDirectory() && !rootfsParent.mkdirs()))
            throw new IOException("Could not create the Ubuntu rootfs installation directory.");

        // Remove an incompatible legacy rootfs before extracting the native ARM64 one.
        deleteIfExists(rootfsDir);
        deleteIfExists(extractedRootfsDir);
        installProot(activity);

        File tarball = new File(activity.getCacheDir(), ROOTFS_ASSET);
        try {
            copyAssetToFile(activity, ROOTFS_ASSET, tarball);
            ProcessBuilder builder = new ProcessBuilder(
                TERMUX_BIN + "/tar", "-xJf", tarball.getAbsolutePath(), "-C", rootfsParent.getAbsolutePath());
            builder.redirectErrorStream(true);
            builder.environment().put("LD_LIBRARY_PATH", TERMUX_LIB);
            builder.environment().put("PATH", TERMUX_BIN + ":/system/bin");
            builder.environment().put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);

            Process process = builder.start();
            byte[] outputBuffer = new byte[4096];
            try (InputStream output = process.getInputStream()) {
                while (output.read(outputBuffer) != -1) {
                    // Drain tar diagnostics so the process cannot block on a full pipe.
                }
            }
            int exitCode = process.waitFor();
            if (exitCode != 0)
                throw new IOException("Rootfs extraction failed with tar exit code " + exitCode + ".");

            if (!extractedRootfsDir.isDirectory())
                throw new IOException("The rootfs archive did not contain ubuntu-rootfs/.");
            if (!extractedRootfsDir.renameTo(rootfsDir))
                throw new IOException("Could not move the extracted Ubuntu rootfs into place.");
            if (!isValidArm64Rootfs(rootfsDir))
                throw new IOException("The extracted Ubuntu rootfs is incomplete or is not marked ARM64.");

            createLoginScript();
        } finally {
            if (tarball.exists() && !tarball.delete())
                Logger.logWarn(LOG_TAG, "Could not remove cached rootfs archive: " + tarball.getAbsolutePath());
        }
    }

    static boolean isReady() {
        return supportsArm64()
            && isValidArm64Rootfs(new File(ROOTFS_DIR))
            && isArm64Elf(new File(PROOT_DEST))
            && new File(PROOT_DEST).canExecute()
            && new File(LOGIN_SCRIPT).canExecute();
    }

    private static boolean supportsArm64() {
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) return true;
        }
        return false;
    }

    private static boolean isArm64Elf(File file) {
        if (!file.isFile()) return false;
        try (InputStream input = new java.io.FileInputStream(file)) {
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

    private static boolean isValidArm64Rootfs(File rootfsDir) {
        File bash = new File(rootfsDir, "bin/bash");
        File marker = new File(rootfsDir, "etc/termux-ubuntu-rootfs");
        if (!rootfsDir.isDirectory() || !bash.isFile() || !marker.isFile()) return false;
        try (InputStream input = new java.io.FileInputStream(marker)) {
            byte[] bytes = new byte[4096];
            int count = input.read(bytes);
            if (count <= 0) return false;
            String markerText = new String(bytes, 0, count, StandardCharsets.UTF_8);
            return markerText.contains("ARCH=arm64");
        } catch (IOException e) {
            return false;
        }
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

    private static void deleteIfExists(File file) throws IOException {
        if (file == null || !file.exists()) return;
        Error error = FileUtils.deleteFile("Ubuntu rootfs", file.getAbsolutePath(), true);
        if (error != null)
            throw new IOException("Could not remove incompatible rootfs: " + Error.getMinimalErrorString(error));
    }

    private static void createLoginScript() throws IOException {
        File script = new File(LOGIN_SCRIPT);
        File parent = script.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs())
            throw new IOException("Could not create the Termux bin directory.");

        String content = "#!/data/data/com.termux/files/usr/bin/sh\n"
            + "unset LD_PRELOAD\n"
            + "exec /data/data/com.termux/files/usr/bin/proot --kill-on-exit --link2symlink -0 "
            + "-E LD_LIBRARY_PATH= "
            + "-r /data/data/com.termux/files/usr/var/lib/proot-distro/installed-rootfs/ubuntu "
            + "-b /dev -b /proc -b /sys -w / "
            + "/usr/bin/env -i "
            + "HOME=/root "
            + "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin "
            + "TERM=\"${TERM:-xterm-256color}\" "
            + "LANG=C.UTF-8 TMPDIR=/tmp /bin/bash -l\n";
        try (FileOutputStream output = new FileOutputStream(script)) {
            output.write(content.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        if (!script.setExecutable(true, false))
            throw new IOException("Could not make the Ubuntu login command executable.");
    }
}
