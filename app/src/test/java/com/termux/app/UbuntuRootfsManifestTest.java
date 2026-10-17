package com.termux.app;

import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

public class UbuntuRootfsManifestTest {

    private static final String VALID_MANIFEST = "FORMAT=1\nARCH=arm64\nCOMPRESSED_BYTES=931521564\n"
        + "EXPANDED_BYTES=3738142720\nSHA256=dc9a0d1b6dd1e03defd737c50606e5cf7eebd4a874ce04189b246fbf60c7e0c8\n";

    private UbuntuRootfsManifest parse(String text) throws Exception {
        return UbuntuRootfsManifest.parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void parsesArm64SizesAndChecksum() throws Exception {
        UbuntuRootfsManifest manifest = parse(VALID_MANIFEST);
        Assert.assertEquals(931521564L, manifest.compressedBytes);
        Assert.assertEquals(3738142720L, manifest.expandedBytes);
        Assert.assertEquals("dc9a0d1b6dd1e03defd737c50606e5cf7eebd4a874ce04189b246fbf60c7e0c8", manifest.sha256);
    }

    @Test
    public void recommendedSpaceIncludesArchiveAndSafetyMargin() throws Exception {
        Assert.assertEquals(5206535196L, parse(VALID_MANIFEST).getRecommendedFreeBytes());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnsupportedArchitecture() throws Exception {
        parse(VALID_MANIFEST.replace("ARCH=arm64", "ARCH=x86_64"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMalformedChecksum() throws Exception {
        parse(VALID_MANIFEST.replace("dc9a0d1b6dd1e03defd737c50606e5cf7eebd4a874ce04189b246fbf60c7e0c8", "not-a-checksum"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsDuplicateKeys() throws Exception {
        parse(VALID_MANIFEST + "ARCH=arm64\n");
    }
}
