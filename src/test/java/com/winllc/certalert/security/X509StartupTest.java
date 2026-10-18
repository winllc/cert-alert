package com.winllc.certalert.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.winllc.certalert.support.TestCa;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;

/** Header mode refuses to start without what makes a forwarded certificate believable. */
@ExtendWith(OutputCaptureExtension.class)
class X509StartupTest {

    private static final String SECRET = "a-secret-of-at-least-thirty-two-characters";

    @TempDir
    Path directory;

    @Test
    void headerModeNeedsALongEnoughSecret() throws Exception {
        EnsX509Properties x509 = headerMode();
        x509.setProxySecret(null);
        assertThatThrownBy(() -> startup(x509, new MockEnvironment()).check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ENS_X509_PROXY_SECRET");

        x509.setProxySecret("too-short");
        assertThatThrownBy(() -> startup(x509, new MockEnvironment()).check())
                .hasMessageContaining("at least 32");
    }

    @Test
    void theSecretMayComeFromAFile() throws Exception {
        EnsX509Properties x509 = headerMode();
        x509.setProxySecret(null);
        Path file = directory.resolve("proxy-secret");
        Files.writeString(file, SECRET + "\n");
        x509.setProxySecretFile(file.toString());

        assertThatCode(() -> startup(x509, new MockEnvironment()).check()).doesNotThrowAnyException();
    }

    @Test
    void headerModeNeedsTheCaFile() throws Exception {
        EnsX509Properties x509 = headerMode();
        x509.setTrustedCaFile(null);
        assertThatThrownBy(() -> startup(x509, new MockEnvironment()).check())
                .hasMessageContaining("ENS_X509_TRUSTED_CA_FILE");

        x509.setTrustedCaFile(directory.resolve("missing.pem").toString());
        assertThatThrownBy(() -> startup(x509, new MockEnvironment()).check())
                .hasMessageContaining("could not be read");
    }

    @Test
    void headerModeRefusesTheNativeForwardedHeaderStrategy() throws Exception {
        MockEnvironment environment = new MockEnvironment().withProperty("server.forward-headers-strategy", "native");
        assertThatThrownBy(() -> startup(headerMode(), environment).check())
                .hasMessageContaining("native");
        assertThatCode(() -> startup(headerMode(),
                        new MockEnvironment().withProperty("server.forward-headers-strategy", "framework")).check())
                .doesNotThrowAnyException();
    }

    @Test
    void aValueThatIsNotOneOfItsChoicesStopsTheStart() {
        EnsX509Properties x509 = new EnsX509Properties();
        x509.setRevocation("sometimes");
        assertThatThrownBy(() -> startup(x509, new MockEnvironment()).check())
                .hasMessageContaining("ENS_X509_REVOCATION");

        EnsX509Properties fail = new EnsX509Properties();
        fail.setRevocationFail("maybe");
        assertThatThrownBy(() -> startup(fail, new MockEnvironment()).check())
                .hasMessageContaining("ENS_X509_REVOCATION_FAIL");
    }

    /** The default - a live check, soft-fail - starts, and says what it lets through. */
    @Test
    void theDefaultRevocationCheckStartsAndSaysItIsSoftFail(CapturedOutput output) {
        assertThatCode(() -> startup(new EnsX509Properties(),
                        new MockEnvironment().withProperty("server.ssl.enabled", "true")).check())
                .doesNotThrowAnyException();
        assertThat(output.getOut())
                .contains("checked for revocation at sign-in by default (ocsp-then-crl), soft-fail")
                .contains("ENS_X509_REVOCATION_FAIL=closed");
    }

    @Test
    void servletModeWithRevocationOffStartsAsBefore() {
        EnsX509Properties off = new EnsX509Properties();
        off.setRevocation("off");
        assertThatCode(() -> startup(off,
                        new MockEnvironment().withProperty("server.ssl.enabled", "true")).check())
                .doesNotThrowAnyException();
    }

    private EnsX509Properties headerMode() throws Exception {
        Path ca = directory.resolve("ca.pem");
        Files.writeString(ca, new TestCa("Startup CA").certificatePem());
        EnsX509Properties x509 = new EnsX509Properties();
        x509.setSource("header");
        x509.setProxySecret(SECRET);
        x509.setTrustedCaFile(ca.toString());
        return x509;
    }

    private static X509Startup startup(EnsX509Properties x509, MockEnvironment environment) {
        TrustedCas cas = new TrustedCas(x509);
        return new X509Startup(x509, new SecurityProperties(), new ForwardedCertificates(x509, cas), cas, environment);
    }
}
