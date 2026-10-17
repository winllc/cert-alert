package com.winllc.certalert.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * The two escape hatches, against a real server: the policy reported rather than enforced
 * ({@code ENS_CSP_REPORT_ONLY}), and the session cookie marked Secure behind a proxy that
 * terminates TLS ({@code ENS_COOKIE_SECURE}). The cookie's other attributes come from the
 * container, which is why this is not a MockMvc test.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"ENS_CSP_REPORT_ONLY=true", "ENS_COOKIE_SECURE=true"})
@ActiveProfiles("test")
class ReportOnlyAndSecureCookieTest {

    @LocalServerPort
    private int port;

    @Test
    void thePolicyIsReportedAndTheCookieIsSecure() throws Exception {
        HttpResponse<String> response = HttpClient.newBuilder()
                .proxy(HttpClient.Builder.NO_PROXY)
                .build()
                .send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/login")).build(),
                        HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Security-Policy-Report-Only"))
                .hasValue(SecurityHeaders.CONTENT_SECURITY_POLICY);
        assertThat(response.headers().firstValue("Content-Security-Policy")).isEmpty();

        List<String> cookies = response.headers().allValues("Set-Cookie");
        assertThat(cookies).anySatisfy(cookie -> assertThat(cookie)
                .startsWith("JSESSIONID=")
                .contains("HttpOnly")
                .contains("SameSite=Lax")
                .contains("Secure"));
    }
}
