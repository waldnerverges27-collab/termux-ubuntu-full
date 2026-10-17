package com.termux.app;

import org.junit.Assert;
import org.junit.Test;

public class UbuntuRootfsInstallerTest {

    @Test
    public void generatedLoginScriptUsesSupportedProotArguments() {
        String script = UbuntuRootfsInstaller.buildLoginScriptContent();

        Assert.assertTrue(script.contains("# TERMUX_UBUNTU_LOGIN_VERSION=3"));
        Assert.assertTrue(script.contains("unset LD_PRELOAD LD_LIBRARY_PATH\n"));
        Assert.assertTrue(script.contains("--kill-on-exit --link2symlink -0 -r "));
        Assert.assertFalse("Do not pass the unsupported uppercase -E option to PRoot.", script.contains(" -E "));
        Assert.assertTrue("The guest starts with an explicitly clean environment.", script.contains("/usr/bin/env -i "));
        Assert.assertTrue(script.contains("exec /bin/bash -i"));
    }
}
