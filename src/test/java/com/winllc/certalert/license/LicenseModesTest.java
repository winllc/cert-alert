package com.winllc.certalert.license;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.enssolutions.license.LicenseManager;
import com.winllc.certalert.support.TestLicenses;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
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

/**
 * The three modes the license puts the application in, as a browser and a script see them:
 * unlicensed (only sign-in and the license screen), read-only (look, don't touch), and full
 * with some features left out (those refused and hidden, the rest as normal).
 */
@SpringBootTest
@ActiveProfiles("test")
class LicenseModesTest {

    private static final String DATATABLES_BODY = "{\"draw\":1,\"start\":0,\"length\":10,"
            + "\"columns\":[{\"data\":\"displayName\",\"searchable\":true,\"orderable\":true,"
            + "\"search\":{\"value\":\"\",\"regex\":false}}],"
            + "\"order\":[],\"search\":{\"value\":\"\",\"regex\":false}}";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private LicenseManager licenses;

    @Autowired
    private Licensing licensing;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @AfterEach
    void restore() {
        TestLicenses.restoreFull(licenses);
    }

    @Nested
    class Unlicensed {

        @BeforeEach
        void noLicense() {
            TestLicenses.uninstall(licenses);
        }

        @Test
        void anAdministratorIsSentToTheLicenseScreen() throws Exception {
            mockMvc.perform(get("/users").with(admin()).accept(MediaType.TEXT_HTML))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/admin/license"));
            mockMvc.perform(get("/admin/license").with(admin()))
                    .andExpect(status().isOk())
                    .andExpect(view().name("license"))
                    .andExpect(content().string(containsString("No license is installed")))
                    .andExpect(content().string(containsString(licensing.installationId())))
                    .andExpect(content().string(containsString("data-feature=\"multi-directory\"")));
        }

        @Test
        void anyoneElseIsToldToAskAnAdministrator() throws Exception {
            mockMvc.perform(get("/servers").with(reader()).accept(MediaType.TEXT_HTML))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/unlicensed"));
            mockMvc.perform(get("/unlicensed").with(reader()))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("This application is not licensed")))
                    .andExpect(content().string(containsString("action=\"/logout\"")));
            // The license screen itself is not theirs.
            mockMvc.perform(get("/admin/license").with(reader()))
                    .andExpect(status().isForbidden());
        }

        @Test
        void aScriptGetsLicenseRequired() throws Exception {
            mockMvc.perform(get("/api/v1/stats/users").with(reader()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("license_required"))
                    .andExpect(jsonPath("$.state").value("MISSING"))
                    .andExpect(jsonPath("$.message").value(containsString("No license is installed")));
            mockMvc.perform(post("/api/v1/sync").with(admin()).with(csrf()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("license_required"));
        }

        @Test
        void signingInStillWorks() throws Exception {
            mockMvc.perform(get("/login").with(anonymous()))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Sign in")));
            mockMvc.perform(get("/actuator/health/liveness").with(anonymous()))
                    .andExpect(status().isOk());
            // Somebody not signed in is asked to, rather than told about licensing.
            mockMvc.perform(get("/users").with(anonymous()).accept(MediaType.TEXT_HTML))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/login"));
        }

        @Test
        void theStatusStillAnswers() throws Exception {
            mockMvc.perform(get("/api/v1/license").with(reader()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value("MISSING"))
                    .andExpect(jsonPath("$.mode").value("UNLICENSED"))
                    .andExpect(jsonPath("$.installationId").doesNotExist());
            mockMvc.perform(get("/api/v1/license").with(admin()))
                    .andExpect(jsonPath("$.installationId").value(licensing.installationId()));
        }
    }

    @Nested
    class ReadOnly {

        @BeforeEach
        void expired() {
            TestLicenses.install(licenses, TestLicenses.license().expired().sign());
        }

        @Test
        void readingStillWorks() throws Exception {
            mockMvc.perform(get("/api/v1/stats/users").with(reader()))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/users").with(reader()))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("id=\"license-read-only\"")))
                    .andExpect(content().string(containsString("expired")));
            // The search tables read by POST, and keep working.
            mockMvc.perform(post("/api/v1/datatables/users").with(reader()).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(DATATABLES_BODY))
                    .andExpect(status().isOk());
            // A listed feature's data stays visible.
            mockMvc.perform(get("/api/v1/metrics").with(reader()))
                    .andExpect(status().isOk());
        }

        @Test
        void changingAnythingIsRefused() throws Exception {
            mockMvc.perform(post("/api/v1/sync").with(admin()).with(csrf()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("license_read_only"))
                    .andExpect(jsonPath("$.state").value("EXPIRED"));
            mockMvc.perform(post("/api/v1/projects").with(admin()).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("license_read_only"));
        }

        @Test
        void theSyncButtonIsNotOffered() throws Exception {
            mockMvc.perform(get("/users").with(admin()))
                    .andExpect(status().isOk())
                    .andExpect(content().string(not(containsString("id=\"sync-now\""))));
        }

        @Test
        void jobsPause() {
            assertThatFalse(licensing.mayRun("directory-sync", "test sweep"));
            assertThatFalse(licensing.mayRun("audit trim"));
        }
    }

    @Nested
    class FullWithFeaturesLeftOut {

        @BeforeEach
        void withoutSome() {
            TestLicenses.install(licenses, TestLicenses.license()
                    .without("metrics", "projects", "risky-names", "endpoint-checks", "directory-cleanup",
                            "changelog-sync", "revocation-checks")
                    .sign());
        }

        @Test
        void aFeatureNotIncludedIsRefusedWithAReason() throws Exception {
            mockMvc.perform(get("/api/v1/metrics").with(reader()))
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.error").value("feature_not_licensed"))
                    .andExpect(jsonPath("$.feature").value("metrics"))
                    .andExpect(jsonPath("$.state").value("VALID"));
            mockMvc.perform(post("/api/v1/sync/prune").with(admin()).with(csrf()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.feature").value("directory-cleanup"));
            mockMvc.perform(post("/api/v1/servers/1/probe").with(admin()).with(csrf()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.feature").value("endpoint-checks"));
            mockMvc.perform(get("/api/v1/projects").with(reader()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.feature").value("projects"));
            mockMvc.perform(get("/api/v1/revocation").with(admin()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.feature").value("revocation-checks"));
            mockMvc.perform(get("/api/v1/changelog").with(reader()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.feature").value("changelog-sync"));
        }

        @Test
        void aPageNotIncludedSaysSo() throws Exception {
            mockMvc.perform(get("/metrics").with(reader()).accept(MediaType.TEXT_HTML))
                    .andExpect(status().isForbidden())
                    .andExpect(view().name("license-required"))
                    .andExpect(content().string(containsString("Metrics is not included in the license")));
        }

        @Test
        void whatIsNotIncludedIsNotShown() throws Exception {
            mockMvc.perform(get("/servers").with(admin()))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("href=\"/servers\"")))
                    .andExpect(content().string(containsString("id=\"sync-now\"")))
                    .andExpect(content().string(not(containsString("href=\"/metrics\""))))
                    .andExpect(content().string(not(containsString("href=\"/projects\""))))
                    .andExpect(content().string(not(containsString("id=\"project-filter\""))))
                    .andExpect(content().string(not(containsString("id=\"risk-filter\""))))
                    .andExpect(content().string(not(containsString("id=\"changelog-status\""))));
            mockMvc.perform(get("/admin").with(admin()))
                    .andExpect(status().isOk())
                    .andExpect(content().string(not(containsString("id=\"revocation-admin\""))));
        }

        @Test
        void theCoreStillAnswers() throws Exception {
            mockMvc.perform(get("/api/v1/stats/users").with(reader()))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/users").with(reader()))
                    .andExpect(status().isOk());
        }

        @Test
        void theLicenseScreenTicksWhatIsIncluded() throws Exception {
            mockMvc.perform(get("/admin/license").with(admin()))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Licensed to Test Suite, ENS QA")))
                    .andExpect(content().string(containsString("of 1000000 directory users")));
            mockMvc.perform(get("/api/v1/license").with(reader()))
                    .andExpect(jsonPath("$.state").value("VALID"))
                    .andExpect(jsonPath("$.features[0]").value("directory-sync"))
                    .andExpect(jsonPath("$.features.length()").value(4));
        }
    }

    @Nested
    class Full {

        @Test
        void everythingIsShown() throws Exception {
            mockMvc.perform(get("/servers").with(admin()))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("href=\"/metrics\"")))
                    .andExpect(content().string(containsString("href=\"/projects\"")))
                    .andExpect(content().string(containsString("id=\"project-filter\"")))
                    .andExpect(content().string(containsString("id=\"risk-filter\"")))
                    .andExpect(content().string(not(containsString("id=\"license-evaluation\""))));
            mockMvc.perform(get("/api/v1/metrics").with(reader()))
                    .andExpect(status().isOk());
        }

        @Test
        void anEvaluationSaysSoOnEveryPage() throws Exception {
            TestLicenses.install(licenses, TestLicenses.license().evaluation().expiresIn(Duration.ofDays(30)).sign());
            mockMvc.perform(get("/servers").with(reader()))
                    .andExpect(content().string(containsString("id=\"license-evaluation\"")));
            mockMvc.perform(get("/login").with(anonymous()))
                    .andExpect(content().string(containsString("id=\"license-evaluation\"")));
        }

        @Test
        void administratorsAreRemindedToRenew() throws Exception {
            TestLicenses.install(licenses, TestLicenses.license().expiresIn(Duration.ofDays(10)).sign());
            mockMvc.perform(get("/servers").with(admin()))
                    .andExpect(content().string(containsString("id=\"license-renewal\"")))
                    .andExpect(content().string(containsString("The license expires in 10 days.")));
            mockMvc.perform(get("/servers").with(reader()))
                    .andExpect(content().string(not(containsString("id=\"license-renewal\""))));
        }
    }

    private static void assertThatFalse(boolean value) {
        org.assertj.core.api.Assertions.assertThat(value).isFalse();
    }

    private static RequestPostProcessor admin() {
        return SecurityMockMvcRequestPostProcessors.user("root").roles("USER", "ADMIN");
    }

    private static RequestPostProcessor reader() {
        return SecurityMockMvcRequestPostProcessors.user("reader").roles("USER");
    }
}
