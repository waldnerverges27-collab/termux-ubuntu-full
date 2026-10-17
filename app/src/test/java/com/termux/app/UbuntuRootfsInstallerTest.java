package com.termux.app;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

public class UbuntuRootfsInstallerTest {

    @Test
    public void generatedLoginScriptUsesSupportedProotArgumentsAndBashBootstrap() {
        String script = UbuntuRootfsInstaller.buildLoginScriptContent();

        Assert.assertTrue(script.contains("# TERMUX_UBUNTU_LOGIN_VERSION=4"));
        Assert.assertTrue(script.contains("unset LD_PRELOAD LD_LIBRARY_PATH\n"));
        Assert.assertTrue(script.contains("--kill-on-exit --link2symlink -0 -r "));
        Assert.assertFalse("Do not pass the unsupported uppercase -E option to PRoot.", script.contains(" -E "));
        Assert.assertFalse("Do not depend on /usr/bin/env being executable in the guest.", script.contains("/usr/bin/env"));
        Assert.assertTrue(script.contains("exec -c /bin/bash --noprofile --norc -c"));
        Assert.assertTrue(script.contains("export HOME=/root\n"));
        Assert.assertTrue(script.contains("TERMUX_UBUNTU_WORKDIR=\"$2\""));
        Assert.assertTrue(script.contains("exec /bin/bash -i"));
    }

    @Test
    public void ubuntuSessionClearsPreloadBeforeShebangInterpreterStarts() {
        HashMap<String, String> additionalEnvironment = new HashMap<>();
        additionalEnvironment.put("TERMUX_UBUNTU_WORKDIR", "/root/project");

        HashMap<String, String> environment = UbuntuRootfsInstaller.getSessionEnvironmentOverrides(
            UbuntuRootfsInstaller.getLoginScriptPath(), additionalEnvironment);

        Assert.assertEquals("", environment.get("LD_PRELOAD"));
        Assert.assertEquals("", environment.get("LD_LIBRARY_PATH"));
        Assert.assertEquals("/root/project", environment.get("TERMUX_UBUNTU_WORKDIR"));
        Assert.assertFalse("Do not mutate workspace-specific values supplied by the caller.",
            additionalEnvironment.containsKey("LD_PRELOAD"));
    }

    @Test
    public void unrelatedSessionDoesNotReceiveUbuntuEnvironmentOverrides() {
        Assert.assertNull(UbuntuRootfsInstaller.getSessionEnvironmentOverrides("/system/bin/sh", null));
    }

    @Test
    public void generatedLoginScriptParsesAsPosixShell() throws Exception {
        Assume.assumeTrue("A POSIX shell is needed for this syntax check.", new File("/bin/sh").canExecute());
        String script = UbuntuRootfsInstaller.buildLoginScriptContent();
        Process process = new ProcessBuilder("/bin/sh", "-n").redirectErrorStream(true).start();
        try (OutputStream input = process.getOutputStream()) {
            input.write(script.getBytes(StandardCharsets.UTF_8));
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream stream = process.getInputStream()) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = stream.read(buffer)) != -1) output.write(buffer, 0, count);
        }
        Assert.assertEquals(output.toString("UTF-8"), 0, process.waitFor());
    }
}
