package com.winllc.certalert.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The ENS baseline's response headers, on every chain, and the pages written so the
 * Content-Security-Policy can be enforced without breaking them.
 */
@SpringBootTest
@ActiveProfiles("test")
class SecurityHeadersTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private Environment environment;

    @Autowired
    private EnsSecurityProperties ens;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    void everyPageCarriesTheBaselineHeaders() throws Exception {
        mockMvc.perform(get("/login").accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", "default-src 'self'; script-src 'self'; "
                        + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:; "
                        + "connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'; "
                        + "object-src 'none'"))
                .andExpect(header().doesNotExist("Content-Security-Policy-Report-Only"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
                .andExpect(header().string(
                        "Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()"))
                // Plain HTTP: a browser would ignore it, so it is not sent.
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
    }

    @Test
    void hstsGoesOnASecureRequest() throws Exception {
        mockMvc.perform(get("/login").secure(true))
                .andExpect(header().string("Strict-Transport-Security", "max-age=31536000 ; includeSubDomains"));
    }

    @Test
    void theRefusalsCarryThemTooAsDoesTheSiemApi() throws Exception {
        mockMvc.perform(get("/users"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().exists("Content-Security-Policy"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
        mockMvc.perform(get("/api/audit/events"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("Content-Security-Policy"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"));
    }

    @Test
    void onlyTls12And13AreOffered() {
        List<String> protocols = Binder.get(environment)
                .bind("server.ssl.enabled-protocols", Bindable.listOf(String.class))
                .get();
        assertThat(protocols).containsExactly("TLSv1.3", "TLSv1.2");
    }

    /** The STIG's 15 minutes, before sign-in and after, unless a deployment says otherwise. */
    @Test
    void anIdleSessionEndsAfterFifteenMinutesByDefault() {
        assertThat(Binder.get(environment).bind("server.servlet.session.timeout", Duration.class).get())
                .isEqualTo(Duration.ofMinutes(15));
        assertThat(ens.getSession().getIdleTimeout()).isEqualTo(Duration.ofMinutes(15));
        assertThat(new EnsSecurityProperties().getSession().getIdleTimeout()).isEqualTo(Duration.ofMinutes(15));
    }

    /**
     * Nothing a template serves needs {@code 'unsafe-inline'} for scripts: no script element
     * without a src, and no event-handler attribute. A page that breaks this works in
     * development and goes dead in a browser enforcing the policy.
     */
    @Test
    void noTemplateHasAnInlineScriptOrHandler() throws IOException {
        Pattern inlineScript = Pattern.compile("<script(?![^>]*\\bsrc=)[^>]*>", Pattern.CASE_INSENSITIVE);
        Pattern handler = Pattern.compile("\\s(th:)?on[a-z]+\\s*=", Pattern.CASE_INSENSITIVE);
        Pattern javascriptUrl = Pattern.compile("javascript:", Pattern.CASE_INSENSITIVE);
        try (Stream<Path> files = Files.walk(Path.of("src/main/resources/templates"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".html")).toList()) {
                // Email templates are mail, rendered by a mail client, never served here.
                if (file.toString().contains("/email/")) {
                    continue;
                }
                String html = Files.readString(file);
                Matcher script = inlineScript.matcher(html);
                assertThat(script.find()).as("inline <script> in %s", file).isFalse();
                assertThat(handler.matcher(html).find()).as("event handler attribute in %s", file).isFalse();
                assertThat(javascriptUrl.matcher(html).find()).as("javascript: URL in %s", file).isFalse();
            }
        }
    }
}
