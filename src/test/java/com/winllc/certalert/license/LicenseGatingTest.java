package com.winllc.certalert.license;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.winllc.certalert.support.TestLicenses;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * A deployment licensed for revocation checking and nothing else: the licensed features it
 * does not cover are refused - as problem detail to a script, as a page to a browser - and
 * left off the bar, while the core and the one feature it does cover carry on.
 */
@SpringBootTest
@ActiveProfiles("test")
class LicenseGatingTest {

    @DynamicPropertySource
    static void revocationOnly(DynamicPropertyRegistry registry) throws IOException {
        Path file = Files.createTempDirectory("cert-alert-license").resolve("license.lic");
        TestLicenses.write(file, TestLicenses.sign(LicenseConfig.PRODUCT, "revocation", null));
        file.toFile().deleteOnExit();
        registry.add("ens.license.file", file::toString);
    }

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    void anUnlicensedEndpointIsForbiddenWithAReason() throws Exception {
        mockMvc.perform(get("/api/v1/metrics").with(reader()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.error").value("license_required"))
                .andExpect(jsonPath("$.feature").value("metrics"))
                .andExpect(jsonPath("$.message").value("Metrics requires an ENS Solutions license."));
    }

    @Test
    void anUnlicensedJobTriggerIsForbiddenToAnAdministratorToo() throws Exception {
        mockMvc.perform(post("/api/v1/sync/prune").with(admin()).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.feature").value("directory-cleanup"));
        mockMvc.perform(post("/api/v1/servers/1/probe").with(admin()).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.feature").value("tls-probe"));
        mockMvc.perform(get("/api/v1/projects").with(reader()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.feature").value("projects"));
    }

    @Test
    void anUnlicensedPageSaysWhatItNeeds() throws Exception {
        mockMvc.perform(get("/metrics").with(reader()).accept(MediaType.TEXT_HTML))
                .andExpect(status().isForbidden())
                .andExpect(view().name("license-required"))
                .andExpect(content().string(containsString("Metrics needs a license")));
    }

    @Test
    void theCoreStillAnswers() throws Exception {
        mockMvc.perform(get("/api/v1/stats/users").with(reader()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/users").with(reader()))
                .andExpect(status().isOk());
    }

    @Test
    void aLicensedFeatureStillAnswers() throws Exception {
        mockMvc.perform(get("/api/v1/revocation").with(admin()))
                .andExpect(status().isOk());
    }

    @Test
    void theBarLeavesOutWhatTheLicenseDoesNotCover() throws Exception {
        mockMvc.perform(get("/servers").with(reader()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/servers\"")))
                .andExpect(content().string(not(containsString("href=\"/metrics\""))))
                .andExpect(content().string(not(containsString("href=\"/projects\""))))
                .andExpect(content().string(not(containsString("id=\"project-filter\""))))
                .andExpect(content().string(not(containsString("id=\"changelog-status\""))));
    }

    @Test
    void theAdministrationPageSaysWhatIsLicensed() throws Exception {
        mockMvc.perform(get("/admin").with(admin()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Licensed to Test Suite, with no expiry date")))
                .andExpect(content().string(containsString("id=\"revocation-admin\"")));
    }

    @Test
    void theStatusIsReadableButOnlyAnAdministratorSeesThePath() throws Exception {
        mockMvc.perform(get("/api/v1/license").with(reader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("LICENSED"))
                .andExpect(jsonPath("$.features[0]").value("revocation"))
                .andExpect(jsonPath("$.file").doesNotExist());
        mockMvc.perform(get("/api/v1/license").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.file").exists());
    }

    private RequestPostProcessor admin() {
        return SecurityMockMvcRequestPostProcessors.user("root").roles("USER", "ADMIN");
    }

    private RequestPostProcessor reader() {
        return SecurityMockMvcRequestPostProcessors.user("reader").roles("USER");
    }
}
