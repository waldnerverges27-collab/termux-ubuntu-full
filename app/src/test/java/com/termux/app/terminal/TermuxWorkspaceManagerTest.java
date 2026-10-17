package com.termux.app.terminal;

import org.junit.Assert;
import org.junit.Test;

import java.util.LinkedHashMap;

public class TermuxWorkspaceManagerTest {

    @Test
    public void normalizesAbsoluteUbuntuFolder() throws Exception {
        Assert.assertEquals("/root/api", TermuxWorkspaceManager.normalizeUbuntuDirectory("/root/./projects/../api/"));
        Assert.assertEquals("/", TermuxWorkspaceManager.normalizeUbuntuDirectory("/../../"));
    }

    @Test(expected = TermuxWorkspaceManager.WorkspaceValidationException.class)
    public void rejectsRelativeFolder() throws Exception {
        TermuxWorkspaceManager.normalizeUbuntuDirectory("projects/api");
    }

    @Test
    public void parsesEnvironmentWithSpacesAndEquals() throws Exception {
        LinkedHashMap<String, String> variables = TermuxWorkspaceManager.parseEnvironmentText(
            "# project settings\nNODE_ENV=development\nAPI_URL=https://example.test/path?a=b\nAPP_LABEL=local build\n");
        Assert.assertEquals(3, variables.size());
        Assert.assertEquals("https://example.test/path?a=b", variables.get("API_URL"));
        Assert.assertEquals("local build", variables.get("APP_LABEL"));
    }

    @Test
    public void shellQuoteEscapesSingleQuotesWithoutExpansion() {
        Assert.assertEquals("'hello'\\''$(not-run)'", TermuxWorkspaceManager.shellQuote("hello'$(not-run)"));
    }

    @Test(expected = TermuxWorkspaceManager.WorkspaceValidationException.class)
    public void rejectsDuplicateEnvironmentKeys() throws Exception {
        TermuxWorkspaceManager.parseEnvironmentText("PORT=3000\nPORT=4000");
    }

    @Test(expected = TermuxWorkspaceManager.WorkspaceValidationException.class)
    public void rejectsInvalidEnvironmentNames() throws Exception {
        TermuxWorkspaceManager.parseEnvironmentText("NOT-VALID=value");
    }

    @Test(expected = TermuxWorkspaceManager.WorkspaceValidationException.class)
    public void protectsLoginControlVariables() throws Exception {
        TermuxWorkspaceManager.parseEnvironmentText("TERMUX_UBUNTU_WORKDIR=/tmp");
    }
}
