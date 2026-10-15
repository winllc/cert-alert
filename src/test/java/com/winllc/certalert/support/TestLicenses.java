package com.winllc.certalert.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

/**
 * Signs license files with the test key, the same way the vendor's LicenseTool does: every
 * field but the signature, sorted, as {@code key=value} lines, signed with Ed25519.
 *
 * <p>The tests run against {@code src/test/resources/license/ens-license-public.pem}, which
 * shadows the real public key on the test classpath - so what this signs is accepted in
 * tests and nowhere else.
 */
public final class TestLicenses {

    public static final String PRIVATE_KEY = "license/ens-license-test-private.pem";

    private TestLicenses() {}

    /** A license for {@code product} covering {@code features}, expiring as given (null: never). */
    public static String sign(String product, String features, LocalDate expires) {
        Map<String, String> fields = new TreeMap<>();
        fields.put("licenseId", "test-" + product + "-" + features.replace("*", "all").replace(',', '-'));
        fields.put("product", product);
        fields.put("licensee", "Test Suite");
        fields.put("issued", "2026-01-01");
        fields.put("expires", expires == null ? "never" : expires.toString());
        fields.put("features", features);
        return sign(fields);
    }

    /** Signs exactly these fields, for tests that want a field missing or odd. */
    public static String sign(Map<String, String> fields) {
        Map<String, String> sorted = new TreeMap<>(fields);
        StringBuilder canonical = new StringBuilder();
        sorted.forEach((k, v) -> canonical.append(k).append('=').append(v).append('\n'));
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(privateKey());
            signer.update(canonical.toString().getBytes(StandardCharsets.UTF_8));
            String signature = Base64.getEncoder().encodeToString(signer.sign());
            return "# ENS Solutions license. Any edit invalidates the signature.\n"
                    + canonical + "signature=" + signature + "\n";
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Writes a license to a file, for a context or a service that reads one. */
    public static Path write(Path file, String license) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            return Files.writeString(file, license);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static PrivateKey privateKey() throws GeneralSecurityException {
        try (InputStream in = TestLicenses.class.getClassLoader().getResourceAsStream(PRIVATE_KEY)) {
            if (in == null) {
                throw new IllegalStateException("No test license key at " + PRIVATE_KEY);
            }
            String pem = new String(in.readAllBytes(), StandardCharsets.US_ASCII);
            byte[] der = Base64.getDecoder().decode(pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", ""));
            return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
