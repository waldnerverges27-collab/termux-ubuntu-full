package com.termux.app;

import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

public class UbuntuRootfsManifestTest {

    private static final String VALID_MANIFEST = "FORMAT=1\nARCH=arm64\nCOMPRESSED_BYTES=931685256\n"
        + "EXPANDED_BYTES=3512398070\nSHA256=43eb091e219bb0fb98c8d9aea5b8f23b8e377267ea2e58278bcafa35f64f5ca1\n";

    private UbuntuRootfsManifest parse(String text) throws Exception {
        return UbuntuRootfsManifest.parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void parsesArm64SizesAndChecksum() throws Exception {
        UbuntuRootfsManifest manifest = parse(VALID_MANIFEST);
        Assert.assertEquals(931685256L, manifest.compressedBytes);
        Assert.assertEquals(3512398070L, manifest.expandedBytes);
        Assert.assertEquals("43eb091e219bb0fb98c8d9aea5b8f23b8e377267ea2e58278bcafa35f64f5ca1", manifest.sha256);
    }

    @Test
    public void recommendedSpaceIncludesArchiveAndSafetyMargin() throws Exception {
        Assert.assertEquals(4980954238L, parse(VALID_MANIFEST).getRecommendedFreeBytes());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnsupportedArchitecture() throws Exception {
        parse(VALID_MANIFEST.replace("ARCH=arm64", "ARCH=x86_64"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMalformedChecksum() throws Exception {
        parse(VALID_MANIFEST.replace("43eb091e219bb0fb98c8d9aea5b8f23b8e377267ea2e58278bcafa35f64f5ca1", "not-a-checksum"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsDuplicateKeys() throws Exception {
        parse(VALID_MANIFEST + "ARCH=arm64\n");
    }
}
