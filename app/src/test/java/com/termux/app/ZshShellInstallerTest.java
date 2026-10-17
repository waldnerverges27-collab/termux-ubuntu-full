package com.termux.app;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class ZshShellInstallerTest {

    @Test
    public void installerScriptParsesAsBash() throws Exception {
        Assume.assumeTrue("Bash is needed for this syntax check.", new File("/bin/bash").canExecute());
        Process process = new ProcessBuilder("/bin/bash", "-n", "src/main/assets/zsh-shell-module.sh")
            .redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream stream = process.getInputStream()) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = stream.read(buffer)) != -1) output.write(buffer, 0, count);
        }
        Assert.assertEquals(output.toString("UTF-8"), 0, process.waitFor());
    }

    @Test
    public void requestedPluginListAndUpstreamSourcesAreComplete() throws Exception {
        String[] expected = {
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
        Assert.assertArrayEquals(expected, ZshShellInstaller.getRequestedPlugins());

        String script = readInstallerScript();
        Assert.assertTrue(script.contains("ensure_plugin powerlevel10k https://github.com/romkatv/powerlevel10k.git"));
        for (String plugin : expected) {
            Assert.assertTrue("Missing install step for " + plugin,
                script.contains("ensure_plugin " + plugin + " https://github.com/"));
        }
    }

    @Test
    public void moduleKeepsBashAndFailsafeSeparateAndProtectsUserStartupFiles() throws Exception {
        String script = readInstallerScript();
        Assert.assertTrue(script.contains("write_text_atomically \"$HOME/.termux/shell\" zsh"));
        Assert.assertTrue(script.contains("write_text_atomically /etc/termux-zsh/default-shell zsh"));
        Assert.assertTrue(script.contains("restore-bash"));
        Assert.assertTrue(script.contains("The existing $startup is a symbolic link; it was left untouched."));
        Assert.assertTrue(script.contains("START_MARKER='# >>> TERMUX ZSH MODULE >>>'"));
        Assert.assertTrue(script.contains("END_MARKER='# <<< TERMUX ZSH MODULE <<<'"));
    }

    private static String readInstallerScript() throws Exception {
        File asset = new File("src/main/assets/zsh-shell-module.sh");
        try (InputStream input = new FileInputStream(asset);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
