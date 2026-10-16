package com.termux.app;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.termux.shared.file.FileUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Extracts the bundled Ubuntu rootfs (proot) and proot binary into the Termux prefix
 * so that Termux boots directly into Ubuntu.
 */
final class UbuntuRootfsInstaller {

    private static final String LOG_TAG = "UbuntuRootfsInstaller";

    private static final String ROOTFS_ASSET = "ubuntu-rootfs.tar.xz";
    private static final String ROOTFS_DIR = TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/var/lib/proot-distro/installed-rootfs/ubuntu";
    private static final String PROOT_DEST = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/proot";
    private static final String LOGIN_SCRIPT = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/login";

    static void installIfNeeded(final Activity activity, final Runnable whenDone) {
        File rootfsDir = new File(ROOTFS_DIR);
        if (rootfsDir.exists() && rootfsDir.isDirectory() && new File(rootfsDir, "bin/bash").exists()) {
            ensureProotAndLoginScript(activity);
            activity.runOnUiThread(whenDone);
            return;
        }

        final ProgressDialog progress = ProgressDialog.show(activity, null, "Installing Ubuntu rootfs...", true, false);
        new Thread() {
            @Override
            public void run() {
                try {
                    // 1. Extract proot binary for current ABI
                    String abi = Build.SUPPORTED_ABIS[0];
                    String assetName = abi.startsWith("arm64") ? "proot-aarch64" : "proot-x86_64";
                    copyAssetToFile(activity, assetName, new File(PROOT_DEST));
                    new File(PROOT_DEST).setExecutable(true, false);

                    // 2. Extract rootfs tarball
                    File rootfsParent = new File(ROOTFS_DIR).getParentFile();
                    if (rootfsParent != null) rootfsParent.mkdirs();
                    File tarball = new File(activity.getCacheDir(), ROOTFS_ASSET);
                    copyAssetToFile(activity, ROOTFS_ASSET, tarball);

                    ProcessBuilder pb = new ProcessBuilder(
                        TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/tar",
                        "-xJf", tarball.getAbsolutePath(),
                        "-C", new File(ROOTFS_DIR).getParentFile().getAbsolutePath()
                    );
                    // tarball contains "ubuntu-rootfs/..." ? We built with -C /opt ubuntu-rootfs.
                    // Adjust: extract and rename
                    Process p = pb.start();
                    p.waitFor();
                    tarball.delete();

                    // The extracted dir is named "ubuntu-rootfs"; rename to "ubuntu"
                    File extracted = new File(new File(ROOTFS_DIR).getParentFile(), "ubuntu-rootfs");
                    File target = new File(ROOTFS_DIR);
                    if (extracted.exists() && !target.exists()) {
                        extracted.renameTo(target);
                    }

                    // 3. Create login script for auto-boot
                    createLoginScript();

                    activity.runOnUiThread(whenDone);
                } catch (Exception e) {
                    Logger.logErrorExtended(LOG_TAG, "Ubuntu rootfs install failed:\n" + Log.getStackTraceString(e));
                    activity.runOnUiThread(() -> {
                        try { progress.dismiss(); } catch (RuntimeException ignored) {}
                        whenDone.run();
                    });
                } finally {
                    activity.runOnUiThread(() -> {
                        try { progress.dismiss(); } catch (RuntimeException ignored) {}
                    });
                }
            }
        }.start();
    }

    private static void copyAssetToFile(Context context, String assetName, File dest) throws Exception {
        dest.getParentFile().mkdirs();
        try (InputStream in = context.getAssets().open(assetName);
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    private static void ensureProotAndLoginScript(Activity activity) {
        File proot = new File(PROOT_DEST);
        if (!proot.exists()) {
            try {
                String abi = Build.SUPPORTED_ABIS[0];
                String assetName = abi.startsWith("arm64") ? "proot-aarch64" : "proot-x86_64";
                copyAssetToFile(activity, assetName, proot);
                proot.setExecutable(true, false);
            } catch (Exception e) {
                Logger.logErrorExtended(LOG_TAG, "Failed to extract proot: " + e);
            }
        }
        createLoginScript();
    }

    private static void createLoginScript() {
        try {
            File f = new File(LOGIN_SCRIPT);
            f.getParentFile().mkdirs();
            String content = "#!/data/data/com.termux/files/usr/bin/sh\n" +
                "exec /data/data/com.termux/files/usr/bin/proot " +
                "-r /data/data/com.termux/files/usr/var/lib/proot-distro/installed-rootfs/ubuntu " +
                "-b /dev -b /proc -b /sys -w / /bin/bash -l\n";
            FileUtils.writeTextToFile("login", LOGIN_SCRIPT, java.nio.charset.StandardCharsets.UTF_8, content, false);
            f.setExecutable(true, false);
        } catch (Exception e) {
            Logger.logErrorExtended(LOG_TAG, "Failed to create login script: " + e);
        }
    }
}
