package com.winllc.certalert.license;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enssolutions.license.LicenseManager;
import com.enssolutions.license.LicenseState;
import com.winllc.certalert.support.TestLicenses;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Installing a license from the license screen: a good one takes effect at once, and one
 * this installation cannot use is refused with the reason, leaving what was there.
 */
@SpringBootTest
@ActiveProfiles("test")
class LicenseUploadTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private LicenseManager licenses;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        TestLicenses.uninstall(licenses);
    }

    @AfterEach
    void restore() {
        TestLicenses.restoreFull(licenses);
    }

    @Test
    void aValidLicenseIsInstalledAndTakesEffectAtOnce() throws Exception {
        String license = TestLicenses.license().id("lic_upload_valid_0001").sign();

        upload(license)
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/license"))
                .andExpect(flash().attribute("notice", containsString("License installed. Licensed to Test Suite")));

        assertThat(licenses.current().state()).isEqualTo(LicenseState.VALID);
        assertThat(licenses.current().license().orElseThrow().id()).isEqualTo("lic_upload_valid_0001");
    }

    @Test
    void aLicenseCanBePasted() throws Exception {
        mockMvc.perform(post("/admin/license").with(admin()).with(csrf())
                        .param("text", TestLicenses.license().id("lic_upload_pasted_001").sign()))
                .andExpect(flash().attribute("notice", containsString("License installed")));

        assertThat(licenses.current().license().orElseThrow().id()).isEqualTo("lic_upload_pasted_001");
    }

    @Test
    void aLicenseForAnotherProductIsRefused() throws Exception {
        TestLicenses.restoreFull(licenses);
        String installed = licenses.current().license().orElseThrow().id();

        upload(TestLicenses.license().product("readyroom").sign())
                .andExpect(flash().attribute("error", containsString("This is a license for readyroom, not certalert.")));

        assertThat(licenses.current().license().orElseThrow().id()).isEqualTo(installed);
        assertThat(licenses.current().state()).isEqualTo(LicenseState.VALID);
    }

    @Test
    void aLicenseBoundToAnotherInstallationIsRefused() throws Exception {
        upload(TestLicenses.license().installation("CA-ZZZZ-ZZZZ").sign())
                .andExpect(flash().attribute("error", containsString("bound to installation CA-ZZZZ-ZZZZ")));

        assertThat(licenses.current().state()).isEqualTo(LicenseState.MISSING);
    }

    @Test
    void aLicenseBoundToThisInstallationIsAccepted() throws Exception {
        upload(TestLicenses.license().installation(licenses.installationId()).sign())
                .andExpect(flash().attribute("notice", containsString("License installed")));

        assertThat(licenses.current().state()).isEqualTo(LicenseState.VALID);
    }

    @Test
    void somethingThatIsNotALicenseIsRefused() throws Exception {
        String tampered = TestLicenses.full().replace("-----BEGIN ENS LICENSE-----\n", "-----BEGIN ENS LICENSE-----\nx");
        upload(tampered)
                .andExpect(flash().attribute("error", containsString("That isn't a valid ENS license")));
        upload("hello")
                .andExpect(flash().attribute("error", containsString("That isn't a valid ENS license")));

        assertThat(licenses.current().state()).isEqualTo(LicenseState.MISSING);
    }

    @Test
    void onlyAnAdministratorMayInstallOne() throws Exception {
        mockMvc.perform(multipart("/admin/license")
                        .file(file(TestLicenses.full()))
                        .with(SecurityMockMvcRequestPostProcessors.user("reader").roles("USER"))
                        .with(csrf()))
                .andExpect(status().isForbidden());

        assertThat(licenses.current().state()).isEqualTo(LicenseState.MISSING);
    }

    private org.springframework.test.web.servlet.ResultActions upload(String license) throws Exception {
        return mockMvc.perform(multipart("/admin/license").file(file(license)).with(admin()).with(csrf()));
    }

    private static MockMultipartFile file(String license) {
        return new MockMultipartFile("file", "license.lic", "text/plain", license.getBytes(StandardCharsets.UTF_8));
    }

    private static RequestPostProcessor admin() {
        return SecurityMockMvcRequestPostProcessors.user("root").roles("USER", "ADMIN");
    }
}
