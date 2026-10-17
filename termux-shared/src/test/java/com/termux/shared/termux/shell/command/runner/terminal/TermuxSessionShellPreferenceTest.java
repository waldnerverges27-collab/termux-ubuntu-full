package com.termux.shared.termux.shell.command.runner.terminal;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public class TermuxSessionShellPreferenceTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void readsSupportedModuleManagedShellNames() throws Exception {
        File home = temporaryFolder.newFolder("home");
        writePreference(home, "zsh\n");
        Assert.assertEquals("zsh", TermuxSession.readConfiguredShellName(home));

        writePreference(home, "bash\n");
        Assert.assertEquals("bash", TermuxSession.readConfiguredShellName(home));
    }

    @Test
    public void ignoresExecutablePathsAndUnsupportedShellNames() throws Exception {
        File home = temporaryFolder.newFolder("home");
        writePreference(home, "/data/data/com.termux/files/usr/bin/zsh\n");
        Assert.assertNull(TermuxSession.readConfiguredShellName(home));

        writePreference(home, "login\n");
        Assert.assertNull(TermuxSession.readConfiguredShellName(home));

        writePreference(home, "not-a-shell\n");
        Assert.assertNull(TermuxSession.readConfiguredShellName(home));
    }

    @Test
    public void absentOrUnreadablePreferenceFallsBackToShellDiscovery() throws Exception {
        File home = temporaryFolder.newFolder("home");
        Assert.assertNull(TermuxSession.readConfiguredShellName(home));
        Assert.assertNull(TermuxSession.readConfiguredShellName(null));

        File termuxDirectory = new File(home, ".termux");
        Assert.assertTrue(termuxDirectory.mkdirs());
        File shellPreference = new File(termuxDirectory, "shell");
        Assert.assertTrue(shellPreference.createNewFile());
        Assert.assertNull(TermuxSession.readConfiguredShellName(home));
    }

    private static void writePreference(File home, String value) throws Exception {
        File termuxDirectory = new File(home, ".termux");
        if (!termuxDirectory.isDirectory()) Assert.assertTrue(termuxDirectory.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(termuxDirectory, "shell"))) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
        }
    }
}
