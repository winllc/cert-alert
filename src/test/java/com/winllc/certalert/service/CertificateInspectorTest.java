package com.winllc.certalert.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.winllc.certalert.config.CertAlertProperties;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class CertificateInspectorTest {

    @Test
    void ipLiteralsAreDetectedSoSniIsSkipped() {
        assertThat(CertificateInspector.isIpLiteral("192.168.1.10")).isTrue();
        assertThat(CertificateInspector.isIpLiteral("::1")).isTrue();
        assertThat(CertificateInspector.isIpLiteral("example.com")).isFalse();
        assertThat(CertificateInspector.isIpLiteral("host-1.internal")).isFalse();
    }

    @Test
    void unreachableEndpointRaisesInspectionException() throws IOException {
        int closedPort = findClosedPort();
        CertAlertProperties properties = new CertAlertProperties();
        properties.setConnectTimeout(Duration.ofMillis(500));
        properties.setReadTimeout(Duration.ofMillis(500));

        CertificateInspector inspector = new CertificateInspector(properties);

        assertThatThrownBy(() -> inspector.inspect("127.0.0.1", closedPort))
                .isInstanceOf(CertificateInspectionException.class)
                .hasMessageContaining("Unable to retrieve certificate from 127.0.0.1:" + closedPort);
    }

    /** Binds an ephemeral port and releases it, so nothing is listening there. */
    private int findClosedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        }
    }
}
