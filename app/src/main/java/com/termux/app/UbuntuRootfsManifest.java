package com.termux.app;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Validated size and integrity metadata for the Ubuntu rootfs bundled in the APK. */
final class UbuntuRootfsManifest {

    static final long SAFETY_MARGIN_BYTES = 512L * 1024L * 1024L;
    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-fA-F]{64}");

    final long compressedBytes;
    final long expandedBytes;
    final String sha256;

    private UbuntuRootfsManifest(long compressedBytes, long expandedBytes, String sha256) {
        this.compressedBytes = compressedBytes;
        this.expandedBytes = expandedBytes;
        this.sha256 = sha256.toLowerCase(Locale.ROOT);
    }

    static UbuntuRootfsManifest parse(InputStream input) throws IOException {
        if (input == null) throw new IllegalArgumentException("Ubuntu rootfs metadata is missing.");
        Map<String, String> values = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                int separator = trimmed.indexOf('=');
                if (separator < 1 || separator == trimmed.length() - 1)
                    throw new IllegalArgumentException("Invalid Ubuntu metadata at line " + lineNumber + ".");
                String key = trimmed.substring(0, separator).trim();
                String value = trimmed.substring(separator + 1).trim();
                if (values.put(key, value) != null)
                    throw new IllegalArgumentException("Duplicate Ubuntu metadata key: " + key + ".");
            }
        }

        if (!"1".equals(values.get("FORMAT")) || !"arm64".equalsIgnoreCase(values.get("ARCH")))
            throw new IllegalArgumentException("Ubuntu metadata format or architecture is unsupported.");
        long compressedBytes = parsePositiveLong(values.get("COMPRESSED_BYTES"), "compressed size");
        long expandedBytes = parsePositiveLong(values.get("EXPANDED_BYTES"), "expanded size");
        String sha256 = values.get("SHA256");
        if (sha256 == null || !SHA256_PATTERN.matcher(sha256).matches())
            throw new IllegalArgumentException("Ubuntu metadata SHA-256 is invalid.");
        if (expandedBytes < compressedBytes)
            throw new IllegalArgumentException("Ubuntu metadata sizes are inconsistent.");
        return new UbuntuRootfsManifest(compressedBytes, expandedBytes, sha256);
    }

    long getRecommendedFreeBytes() {
        if (compressedBytes > Long.MAX_VALUE - expandedBytes - SAFETY_MARGIN_BYTES)
            return Long.MAX_VALUE;
        return compressedBytes + expandedBytes + SAFETY_MARGIN_BYTES;
    }

    private static long parsePositiveLong(String value, String label) {
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("Ubuntu metadata is missing the " + label + ".");
        try {
            long number = Long.parseLong(value);
            if (number <= 0) throw new NumberFormatException("not positive");
            return number;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Ubuntu metadata has an invalid " + label + ".", e);
        }
    }
}
