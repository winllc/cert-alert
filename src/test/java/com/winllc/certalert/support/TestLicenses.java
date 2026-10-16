package com.winllc.certalert.support;

import com.enssolutions.license.LicenseCheck;
import com.enssolutions.license.LicenseManager;
import com.enssolutions.license.LicenseVerifier;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.NamedParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Signs {@code ens-license/1} licenses with the ENS licensing portal's published TEST key -
 * the Ed25519 key whose seed is 32 bytes of {@code 0x07}, key id {@value #KEY_ID} - the way
 * the portal signs real ones: a token {@code <kid>.<base64url(payload)>.<base64url(sig)>},
 * the signature over the ASCII {@code <kid>.<payload>}, wrapped in BEGIN/END ENS LICENSE.
 *
 * <p>Only test configuration trusts this key ({@link TestLicenseConfiguration}); the
 * application itself trusts the production key alone, so nothing signed here is accepted by
 * a build anybody runs.
 */
public final class TestLicenses {

    public static final String PRODUCT = "certalert";

    /** The published TEST public key (licensing/fixtures/test-public-key.txt). */
    public static final String PUBLIC_KEY = "MCowBQYDK2VwAyEA6kpsY+KcUgq+9VB7Ey7F+ZVHdq6+vnuSQh7qaRRG0iw=";
    public static final String KEY_ID = "324be2dea8bc4446";

    /** Every CertAlert feature in the portal's catalog. */
    public static final List<String> ALL_FEATURES = List.of(
            "directory-sync", "changelog-sync", "notifications", "metrics", "projects", "revocation-checks",
            "endpoint-checks", "risky-names", "directory-cleanup", "classification-banners", "multi-directory");

    private static final KeyPair KEYS = testKeyPair();

    private TestLicenses() {}

    /** A verifier trusting only the TEST key. */
    public static LicenseVerifier verifier() {
        return LicenseVerifier.trusting(PUBLIC_KEY);
    }

    /** A year's subscription for every feature, sized well beyond anything a test syncs. */
    public static Builder license() {
        return new Builder();
    }

    public static String full() {
        return license().sign();
    }

    /** Installs a license, failing the test if the installation refuses it. */
    public static LicenseCheck install(LicenseManager licenses, String license) {
        try {
            return licenses.install(license);
        } catch (Exception e) {
            throw new IllegalStateException("Could not install a test license: " + e.getMessage(), e);
        }
    }

    /** Puts back the full license every test context starts with. */
    public static void restoreFull(LicenseManager licenses) {
        install(licenses, full());
    }

    /** Removes the license file, leaving the installation unlicensed. */
    public static void uninstall(LicenseManager licenses) {
        try {
            Files.deleteIfExists(licenses.licenseFile());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static final class Builder {
        private String id = "lic_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        private int revision = 1;
        private String product = PRODUCT;
        private String edition = "subscription";
        private List<String> features = new ArrayList<>(ALL_FEATURES);
        private long quantity = 1_000_000;
        private String unit = "directory users";
        private Instant notBefore = Instant.now().minus(Duration.ofDays(1));
        private Instant expiresAt = Instant.now().plus(Duration.ofDays(365));
        private int graceDays = 30;
        private Instant maintenanceUntil;
        private String installationId;
        private boolean watermark;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder revision(int revision) {
            this.revision = revision;
            return this;
        }

        public Builder product(String product) {
            this.product = product;
            return this;
        }

        /** An evaluation: no grace, and the evaluation banner. */
        public Builder evaluation() {
            this.edition = "evaluation";
            this.graceDays = 0;
            this.watermark = true;
            return this;
        }

        /** Exactly these features, by catalog id. */
        public Builder features(String... ids) {
            this.features = new ArrayList<>(Arrays.asList(ids));
            return this;
        }

        /** Every feature but these. */
        public Builder without(String... ids) {
            this.features.removeAll(Arrays.asList(ids));
            return this;
        }

        public Builder quantity(long quantity) {
            this.quantity = quantity;
            return this;
        }

        public Builder expiresIn(Duration duration) {
            this.expiresAt = Instant.now().plus(duration);
            return this;
        }

        /** Expired, and past its grace period: read-only. */
        public Builder expired() {
            this.notBefore = Instant.now().minus(Duration.ofDays(500));
            this.expiresAt = Instant.now().minus(Duration.ofDays(60));
            return this;
        }

        public Builder installation(String installationId) {
            this.installationId = installationId;
            return this;
        }

        public String sign() {
            String payload = "{"
                    + str("format", "ens-license/1") + ","
                    + str("id", id) + ","
                    + "\"revision\":" + revision + ","
                    + str("issuer", "ENS Solutions, LLC") + ","
                    + str("product", product) + ","
                    + str("edition", edition) + ","
                    + "\"licensee\":{" + str("name", "Test Suite") + "," + str("organization", "ENS QA") + ","
                    + str("email", "qa@example.gov") + "},"
                    + "\"limit\":{\"quantity\":" + quantity + "," + str("unit", unit) + "},"
                    + "\"features\":[" + String.join(",", features.stream()
                            .map(f -> quote(f.contains(".") ? f : product + "." + f)).toList()) + "],"
                    + str("support", "standard") + ","
                    + str("issuedAt", notBefore.toString()) + ","
                    + str("notBefore", notBefore.toString()) + ","
                    + "\"expiresAt\":" + (expiresAt == null ? "null" : quote(expiresAt.toString())) + ","
                    + "\"graceDays\":" + graceDays + ","
                    + "\"maintenanceUntil\":" + (maintenanceUntil == null ? "null" : quote(maintenanceUntil.toString())) + ","
                    + "\"installationId\":" + (installationId == null ? "null" : quote(installationId)) + ","
                    + "\"watermark\":" + watermark
                    + "}";
            String body = KEY_ID + "." + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
            String token = body + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature(body));
            return "# ENS Solutions license file (test)\n# Product: " + product + "\n"
                    + "-----BEGIN ENS LICENSE-----\n" + token + "\n-----END ENS LICENSE-----\n";
        }
    }

    private static byte[] signature(String body) {
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(KEYS.getPrivate());
            signer.update(body.getBytes(StandardCharsets.US_ASCII));
            return signer.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String str(String name, String value) {
        return quote(name) + ":" + quote(value);
    }

    private static String quote(String value) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }

    /**
     * The TEST key pair. The JDK's Ed25519 generator takes its 32-byte private seed from
     * the random source, so a source that only ever yields 0x07 gives the portal's
     * published test key (PKCS#8 {@code 302e020100300506032b657004220420} + 32 × 0x07).
     */
    private static KeyPair testKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
            generator.initialize(NamedParameterSpec.ED25519, new SecureRandom() {
                @Override
                public void nextBytes(byte[] bytes) {
                    Arrays.fill(bytes, (byte) 0x07);
                }
            });
            KeyPair pair = generator.generateKeyPair();
            String published = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
            if (!PUBLIC_KEY.equals(published)) {
                throw new IllegalStateException("The derived test key is not the published one: " + published);
            }
            return pair;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
