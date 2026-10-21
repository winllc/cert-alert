package com.winllc.certalert.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * A webhook's signing secret: made, kept and used.
 *
 * <p>The endpoint's URL is kept the same way, encrypted with the same key where it is set:
 * a chat webhook's URL is a credential too.
 *
 * <p><b>Made</b> as {@code whsec_} and 32 random bytes, base64url, and shown to the
 * administrator once. <b>Kept</b> in the database - it has to be, to sign with - encrypted
 * with AES-GCM under {@code cert-alert.webhooks.secret-key} where that is set.
 * <b>Used</b> to sign each delivery: HMAC-SHA256 over {@code <timestamp>.<body>}, sent as
 * {@code X-CertAlert-Signature: t=<timestamp>,v1=<hex>} (see the README for verifying it).
 */
@Component
public class WebhookSecrets {

    private static final Logger log = LoggerFactory.getLogger(WebhookSecrets.class);

    public static final String PREFIX = "whsec_";

    /** What an encrypted secret starts with in the table. */
    private static final String ENCRYPTED = "v1:";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public WebhookSecrets(WebhookProperties properties) {
        this.key = keyOf(properties.getSecretKey());
        if (key == null) {
            log.warn("cert-alert.webhooks.secret-key is not set: webhook signing secrets are stored unencrypted "
                    + "in the database. Set a base64 AES key to encrypt them.");
        }
    }

    /** Whether a key is configured, so what is stored is encrypted. */
    public boolean encrypts() {
        return key != null;
    }

    /** How a secret is held in the table. */
    record Stored(String value, boolean encrypted) {}

    public String generate() {
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    Stored store(String secret) {
        if (key == null) {
            return new Stored(secret, false);
        }
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] sealed = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(sealed, 0, out, iv.length, sealed.length);
            return new Stored(ENCRYPTED + Base64.getEncoder().encodeToString(out), true);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt a webhook secret", e);
        }
    }

    String reveal(WebhookEndpoint endpoint) {
        return open(endpoint.getStoredSecret(), endpoint.isSecretEncrypted(), "signing secret", endpoint.getId());
    }

    /** The endpoint's URL as it was given. */
    String revealUrl(WebhookEndpoint endpoint) {
        return open(endpoint.getStoredUrl(), endpoint.isUrlEncrypted(), "URL", endpoint.getId());
    }

    private String open(String stored, boolean encrypted, String what, Long id) {
        if (!encrypted) {
            return stored;
        }
        if (key == null || !stored.startsWith(ENCRYPTED)) {
            throw new IllegalStateException("The " + what + " of webhook " + id
                    + " is encrypted, and cert-alert.webhooks.secret-key is not the key it was encrypted with");
        }
        try {
            byte[] in = Base64.getDecoder().decode(stored.substring(ENCRYPTED.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, in, 0, 12));
            return new String(cipher.doFinal(in, 12, in.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("The " + what + " of webhook " + id
                    + " cannot be decrypted with cert-alert.webhooks.secret-key; set it again", e);
        }
    }

    /**
     * A URL as anybody but an administrator sees it, and as the audit trail records it: the
     * scheme, host and port, never the path or query - which for a Slack or Teams webhook is
     * the credential.
     */
    public static String redact(String url) {
        try {
            java.net.URI uri = new java.net.URI(url);
            if (uri.getHost() == null) {
                return "[redacted]";
            }
            boolean more = (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !uri.getRawPath().equals("/"))
                    || uri.getRawQuery() != null;
            return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort())
                    + (more ? "/[redacted]" : "/");
        } catch (java.net.URISyntaxException | RuntimeException e) {
            return "[redacted]";
        }
    }

    /** {@code t=<timestamp>,v1=<hex HMAC-SHA256 of "<timestamp>.<body>">}. */
    public static String signature(String secret, long timestamp, String body) {
        return "t=" + timestamp + ",v1=" + hmac(secret, timestamp + "." + body);
    }

    /**
     * Checks a signature header as a receiver would: the HMAC, compared in constant time, and
     * the timestamp no further than {@code toleranceSeconds} from now.
     */
    public static boolean verify(String secret, String header, String body, long now, long toleranceSeconds) {
        if (header == null) {
            return false;
        }
        Long timestamp = null;
        String given = null;
        for (String part : header.split(",")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length == 2 && pair[0].equals("t")) {
                try {
                    timestamp = Long.parseLong(pair[1]);
                } catch (NumberFormatException e) {
                    return false;
                }
            } else if (pair.length == 2 && pair[0].equals("v1")) {
                given = pair[1];
            }
        }
        if (timestamp == null || given == null || Math.abs(now - timestamp) > toleranceSeconds) {
            return false;
        }
        String expected = hmac(secret, timestamp + "." + body);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII), given.getBytes(StandardCharsets.US_ASCII));
    }

    private static String hmac(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }

    private static SecretKeySpec keyOf(String configured) {
        if (configured == null || configured.isBlank()) {
            return null;
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(configured.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("cert-alert.webhooks.secret-key is not base64", e);
        }
        if (bytes.length != 16 && bytes.length != 24 && bytes.length != 32) {
            throw new IllegalStateException("cert-alert.webhooks.secret-key must be 16, 24 or 32 bytes, base64");
        }
        return new SecretKeySpec(bytes, "AES");
    }
}
