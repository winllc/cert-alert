package com.winllc.certalert.license;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.enssolutions.license.LicenseManager;
import com.winllc.certalert.security.EnsSecurityProperties;
import com.winllc.certalert.support.TestLicenses;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * {@code ENS_LICENSE_MENU_VISIBLE}: the way to the license screen in the navigation - in
 * CertAlert the administration page's "Manage the license" link, the bar having no license
 * entry - shown by default and with {@code true}, gone with {@code false}; and with it gone
 * the screen itself, and every way out of a license problem, still there.
 */
class LicenseMenuVisibilityTest {

    private static final String MENU_LINK = "id=\"license-menu-link\"";

    /** The binding on its own: the default, and a value that is not a boolean stopping the start. */
    @Nested
    class TheSetting {

        private final ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(Properties.class)
                // As application.yml binds it.
                .withPropertyValues("ens.license-menu-visible=${ENS_LICENSE_MENU_VISIBLE:true}");

        @Test
        void isOnUnlessSaidOtherwise() {
            runner.run(context -> assertThat(context.getBean(EnsSecurityProperties.class).isLicenseMenuVisible())
                    .isTrue());
            assertThat(new EnsSecurityProperties().isLicenseMenuVisible()).isTrue();
        }

        @Test
        void falseTurnsItOff() {
            runner.withPropertyValues("ENS_LICENSE_MENU_VISIBLE=false")
                    .run(context -> assertThat(context.getBean(EnsSecurityProperties.class).isLicenseMenuVisible()).isFalse());
        }

        @Test
        void somethingElseStopsTheStart() {
            runner.withPropertyValues("ENS_LICENSE_MENU_VISIBLE=maybe")
                    .run(context -> assertThat(context).hasFailed()
                            .getFailure().hasStackTraceContaining("ens.license-menu-visible"));
        }

        /**
         * ENS_LICENSE, the license token (the release bundle sets it empty), maps to
         * {@code ens.license} by relaxed binding: no setting of ours may live there, or the
         * application does not start.
         */
        @Test
        void theLicenseTokenVariableLeavesItAlone() {
            for (String token : new String[] {"", "dGhlIGxpY2Vuc2UgdG9rZW4"}) {
                runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                                new SystemEnvironmentPropertySource("test-env",
                                        Map.of("ENS_LICENSE", token, "ENS_LICENSE_MENU_VISIBLE", "false"))))
                        .run(context -> {
                            assertThat(context).as("ENS_LICENSE=" + token).hasNotFailed();
                            assertThat(context.getBean(EnsSecurityProperties.class).isLicenseMenuVisible()).isFalse();
                        });
            }
        }

        @EnableConfigurationProperties(EnsSecurityProperties.class)
        static class Properties {
        }
    }

    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    class ByDefault {

        @Autowired
        private WebApplicationContext context;

        @Test
        void theAdministrationPageLeadsToTheLicenseScreen() throws Exception {
            MockMvc mockMvc = mockMvc(context);
            mockMvc.perform(get("/admin").with(admin()))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString(MENU_LINK)))
                    .andExpect(content().string(containsString("href=\"/admin/license\"")));
            mockMvc.perform(get("/admin").with(auditor()))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString(MENU_LINK)));
        }
    }

    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    @TestPropertySource(properties = "ENS_LICENSE_MENU_VISIBLE=true")
    class WhenTrue {

        @Autowired
        private WebApplicationContext context;

        @Test
        void theAdministrationPageLeadsToTheLicenseScreen() throws Exception {
            mockMvc(context).perform(get("/admin").with(admin()))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString(MENU_LINK)));
        }
    }

    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    @TestPropertySource(properties = "ENS_LICENSE_MENU_VISIBLE=false")
    class WhenFalse {

        @Autowired
        private WebApplicationContext context;

        @Autowired
        private LicenseManager licenses;

        private MockMvc mockMvc;

        @BeforeEach
        void setUp() {
            mockMvc = mockMvc(context);
        }

        @AfterEach
        void restore() {
            TestLicenses.restoreFull(licenses);
        }

        @Test
        void theLinkIsGoneAndTheStatusLineStays() throws Exception {
            mockMvc.perform(get("/admin").with(admin()))
                    .andExpect(status().isOk())
                    .andExpect(content().string(not(containsString(MENU_LINK))))
                    .andExpect(content().string(not(containsString("href=\"/admin/license\""))))
                    .andExpect(content().string(containsString("id=\"license-status\"")));
            mockMvc.perform(get("/admin").with(auditor()))
                    .andExpect(status().isOk())
                    .andExpect(content().string(not(containsString(MENU_LINK))));
        }

        @Test
        void theScreenStillOpensForWhoeverMay() throws Exception {
            mockMvc.perform(get("/admin/license").with(admin()))
                    .andExpect(status().isOk())
                    .andExpect(view().name("license"));
            mockMvc.perform(get("/admin/license").with(auditor()))
                    .andExpect(status().isOk())
                    .andExpect(view().name("license"));
            mockMvc.perform(get("/admin/license").with(reader()))
                    .andExpect(status().isForbidden());
        }

        @Test
        void unlicensedAnAdministratorIsStillSentThere() throws Exception {
            TestLicenses.uninstall(licenses);
            mockMvc.perform(get("/users").with(admin()).accept(MediaType.TEXT_HTML))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/admin/license"));
            mockMvc.perform(get("/admin/license").with(admin()))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("No license is installed")));
        }

        @Test
        void theReadOnlyBannerStillLinksToIt() throws Exception {
            TestLicenses.install(licenses, TestLicenses.license().expired().sign());
            mockMvc.perform(get("/users").with(admin()))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("id=\"license-read-only\"")))
                    .andExpect(content().string(containsString("Install a license")))
                    .andExpect(content().string(containsString("href=\"/admin/license\"")));
        }

        @Test
        void theRenewalReminderStillLinksToIt() throws Exception {
            TestLicenses.install(licenses, TestLicenses.license().expiresIn(Duration.ofDays(10)).sign());
            mockMvc.perform(get("/servers").with(admin()))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("id=\"license-renewal\"")))
                    .andExpect(content().string(containsString("Renew it")))
                    .andExpect(content().string(containsString("href=\"/admin/license\"")));
        }

        @Test
        void theLicenseRequiredPageStillLinksToIt() throws Exception {
            TestLicenses.install(licenses, TestLicenses.license().without("metrics").sign());
            mockMvc.perform(get("/metrics").with(admin()).accept(MediaType.TEXT_HTML))
                    .andExpect(status().isForbidden())
                    .andExpect(view().name("license-required"))
                    .andExpect(content().string(containsString("href=\"/admin/license\"")));
        }
    }

    private static MockMvc mockMvc(WebApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup(context)
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
}
