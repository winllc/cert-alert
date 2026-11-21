package com.winllc.certalert.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.winllc.certalert.support.TestCa;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * The forwarded certificate arrives with every request, so what it costs to believe it is
 * paid once: a chain that validated is reused for the same header, and the secret file is
 * read again only when it changes.
 */
class ForwardedCertificatesCacheTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef-proxy";

    @TempDir
    Path directory;

    @Test
    void theSecretFileIsReadAgainOnlyWhenItChanges() throws Exception {
        Path file = directory.resolve("proxy-secret");
        Files.writeString(file, SECRET + "-one\n");
        FileTime written = Files.getLastModifiedTime(file);
        EnsX509Properties settings = new EnsX509Properties();
        settings.setProxySecretFile(file.toString());
        ForwardedCertificates forwarded = new ForwardedCertificates(settings, new TrustedCas(settings));

        assertThat(forwarded.proxySecret()).isEqualTo(SECRET + "-one");

        // The same size and time: taken to be the same file, and not read.
        Files.writeString(file, SECRET + "-two\n");
        Files.setLastModifiedTime(file, written);
        assertThat(forwarded.proxySecret()).isEqualTo(SECRET + "-one");

        // Rotated: a new time, and it is read.
        Files.writeString(file, SECRET + "-three\n");
        Files.setLastModifiedTime(file, FileTime.from(written.toInstant().plusSeconds(5)));
        assertThat(forwarded.proxySecret()).isEqualTo(SECRET + "-three");
    }

    @Test
    void aValidatedChainIsReusedForTheSameHeaderUntilTheCaFileChanges() throws Exception {
        TestCa ca = new TestCa("Example Forwarding CA");
        Path caFile = directory.resolve("ca.pem");
        Files.writeString(caFile, ca.certificatePem());
        EnsX509Properties settings = new EnsX509Properties();
        settings.setTrustedProxies("127.0.0.1/32");
        settings.setProxySecret(SECRET);
        settings.setTrustedCaFile(caFile.toString());
        ForwardedCertificates forwarded = new ForwardedCertificates(settings, new TrustedCas(settings));
        X509Certificate person = ca.issue("person@example.gov", Instant.now().minus(Duration.ofDays(1)),
                Instant.now().plus(Duration.ofDays(30)), null, null).certificate();

        List<X509Certificate> first = forwarded.extract(request(settings, person));
        List<X509Certificate> second = forwarded.extract(request(settings, person));

        assertThat(first).first().isEqualTo(person);
        assertThat(second).as("not validated again").isSameAs(first);

        // A CA file that no longer holds the issuer: what was remembered against it is not used.
        Files.writeString(caFile, new TestCa("Some Other CA").certificatePem());
        Files.setLastModifiedTime(caFile, FileTime.from(Instant.now().plusSeconds(5)));
        assertThatThrownBy(() -> forwarded.extract(request(settings, person)))
                .isInstanceOf(ForwardedCertificates.Refused.class);
    }

    private static MockHttpServletRequest request(EnsX509Properties settings, X509Certificate certificate)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader(ForwardedCertificates.PROXY_SECRET_HEADER, SECRET);
        request.addHeader(settings.getHeader(), "-----BEGIN CERTIFICATE----- "
                + Base64.getEncoder().encodeToString(certificate.getEncoded()) + " -----END CERTIFICATE-----");
        return request;
    }
}
