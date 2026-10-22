package com.winllc.certalert.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

/**
 * What is kept fits the column it is kept in: a value too long for it, encrypted or not, is
 * refused before it is encrypted - not left to the database, or to the arithmetic.
 */
class WebhookSecretsTest {

    private static WebhookSecrets keyed() {
        WebhookProperties properties = new WebhookProperties();
        properties.setSecretKey(Base64.getEncoder().encodeToString(new byte[32]));
        return new WebhookSecrets(properties);
    }

    private static String url(int bytes, String filler) {
        String start = "https://hooks.example.org/";
        return start + filler.repeat((bytes - start.length()) / filler.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }

    @Test
    void theLongestUrlThatFitsEncryptedIsKeptAndOneByteMoreIsRefused() {
        WebhookSecrets secrets = keyed();
        int capacity = WebhookSecrets.encryptedCapacity(WebhookEndpoint.URL_LENGTH);
        assertThat(capacity).isEqualTo(2969);

        String longest = url(capacity, "a");
        assertThat(longest).hasSize(capacity);
        WebhookSecrets.Stored stored = secrets.storeUrl(longest);
        assertThat(stored.encrypted()).isTrue();
        assertThat(stored.value().length()).isLessThanOrEqualTo(WebhookEndpoint.URL_LENGTH);
        WebhookEndpoint endpoint = new WebhookEndpoint("x", stored, WebhookEndpoint.Format.JSON,
                EnumSet.of(WebhookEvent.JOB_FAILED), "t", Instant.now());
        assertThat(secrets.revealUrl(endpoint)).isEqualTo(longest);

        assertThatIllegalArgumentException().isThrownBy(() -> secrets.storeUrl(longest + "a"))
                .withMessageContaining("2969 bytes");
    }

    @Test
    void theBoundIsInBytesSoCharactersOutsideAsciiCountForWhatTheyTake() {
        // Under the 2000 characters a URL may have, and still too long once encrypted.
        String wide = url(3000, "é");
        assertThat(wide.length()).isLessThan(2000);
        assertThatIllegalArgumentException().isThrownBy(() -> keyed().storeUrl(wide));
    }

    @Test
    void withoutAKeyTheColumnIsTheBound() {
        WebhookSecrets plain = new WebhookSecrets(new WebhookProperties());
        assertThat(plain.storeUrl("a".repeat(WebhookEndpoint.URL_LENGTH)).encrypted()).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> plain.storeUrl("a".repeat(WebhookEndpoint.URL_LENGTH + 1)));
        assertThatIllegalArgumentException().isThrownBy(() -> plain.storeSecret("a".repeat(WebhookEndpoint.SECRET_LENGTH + 1)));
    }

    @Test
    void aGeneratedSecretFitsItsColumnEncrypted() {
        WebhookSecrets secrets = keyed();
        String secret = secrets.generate();
        WebhookSecrets.Stored stored = secrets.storeSecret(secret);
        assertThat(stored.value().length()).isLessThanOrEqualTo(WebhookEndpoint.SECRET_LENGTH);
        assertThatIllegalArgumentException().isThrownBy(() -> secrets.storeSecret(
                "a".repeat(WebhookSecrets.encryptedCapacity(WebhookEndpoint.SECRET_LENGTH) + 1)));
    }
}
