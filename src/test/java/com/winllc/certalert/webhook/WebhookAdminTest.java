package com.winllc.certalert.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.winllc.certalert.audit.AuditStore;
import com.winllc.certalert.audit.StoredEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** The Integrations page and the webhook endpoints behind it: who may do what, and what is refused. */
@SpringBootTest
@ActiveProfiles("test")
class WebhookAdminTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private AuditStore audit;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    private static RequestPostProcessor admin() {
        return SecurityMockMvcRequestPostProcessors.user("root").roles("USER", "ADMIN");
    }

    private static RequestPostProcessor auditor() {
        return SecurityMockMvcRequestPostProcessors.user("aud").roles("USER", "AUDITOR");
    }

    private static RequestPostProcessor reader() {
        return SecurityMockMvcRequestPostProcessors.user("reader").roles("USER");
    }

    private static String body(String url) {
        return "{\"name\":\"ops\",\"url\":\"" + url + "\",\"format\":\"JSON\",\"events\":[\"certificate.expiring\",\"job.failed\"]}";
    }

    @Test
    void anAdministratorAddsOneAndSeesItsSecretOnce() throws Exception {
        long head = audit.headSeq();
        String created = mockMvc.perform(post(WebhookAdminController.PATH).with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body("https://93.184.216.34/certalert")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.endpoint.events.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        String secret = created.replaceAll(".*\"secret\":\"([^\"]+)\".*", "$1");
        String id = created.replaceAll(".*\"endpoint\":\\{\"id\":(\\d+).*", "$1");
        assertThat(secret).startsWith(WebhookSecrets.PREFIX);

        String listed = mockMvc.perform(get(WebhookAdminController.PATH).with(auditor()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(listed).contains("93.184.216.34").doesNotContain(secret).doesNotContain("\"secret\"")
                // An auditor sees where it goes, not the path: for a chat webhook that is the credential.
                .doesNotContain("/certalert").contains("https://93.184.216.34/[redacted]");
        assertThat(mockMvc.perform(get(WebhookAdminController.PATH).with(admin())).andReturn().getResponse().getContentAsString())
                .contains("https://93.184.216.34/certalert");
        assertThat(audit.after(head, 100)).extracting(StoredEvent::action).contains("webhook.created");
        assertThat(audit.after(head, 100)).extracting(StoredEvent::line)
                .noneMatch(line -> line.contains(secret) || line.contains("/certalert"));

        // A new secret replaces the old, shown once again; then it goes.
        String rotated = mockMvc.perform(post(WebhookAdminController.PATH + "/" + id + "/secret").with(admin()).with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(rotated.replaceAll(".*\"secret\":\"([^\"]+)\".*", "$1")).startsWith(WebhookSecrets.PREFIX).isNotEqualTo(secret);
        mockMvc.perform(put(WebhookAdminController.PATH + "/" + id).with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ops\",\"url\":\"https://93.184.216.34/certalert\",\"events\":[\"job.failed\"],\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        mockMvc.perform(post(WebhookAdminController.PATH + "/" + id + "/test").with(admin()).with(csrf()))
                .andExpect(status().isAccepted());
        mockMvc.perform(get(WebhookAdminController.PATH + "/" + id + "/deliveries").with(auditor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventType").value("webhook.test"));
        mockMvc.perform(delete(WebhookAdminController.PATH + "/" + id).with(admin()).with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    void urlsThatAreNotHttpsOrPointInsideAreRefused() throws Exception {
        for (String url : new String[] {
            "http://93.184.216.34/x", "https://127.0.0.1/x", "https://169.254.169.254/latest/meta-data/",
            "https://[::1]/x", "https://user:pass@93.184.216.34/x", "javascript:alert(1)"
        }) {
            mockMvc.perform(post(WebhookAdminController.PATH).with(admin()).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(body(url)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void auditorsReadAndReadersDoNot() throws Exception {
        mockMvc.perform(post(WebhookAdminController.PATH).with(auditor()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body("https://93.184.216.34/x")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(WebhookAdminController.PATH).with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/integrations").with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/integrations").with(auditor())).andExpect(status().isOk());
        String page = mockMvc.perform(get("/admin/integrations").with(admin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(page).contains("API tokens", "Webhooks", "id=\"api-token-form\"");
    }
}
