package com.winllc.certalert.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.winllc.certalert.repository.NotificationSettingRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Reading and setting how far ahead the round-up looks.
 *
 * <p>Anybody signed in may read it: it is what decides whether they hear about their own
 * certificate in time. Changing it is an administrator's, because it changes what every
 * point of contact in the directory is written to about, not just their own.
 */
@SpringBootTest
@ActiveProfiles("test")
class NotificationSettingsApiTest {

    private static final String SETTINGS = "/api/v1/notifications/settings";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private NotificationSettingRepository settings;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        settings.deleteAll();
    }

    @AfterEach
    void clear() {
        settings.deleteAll();
    }

    @Test
    void anybodySignedInCanSeeWhatItIsSetTo() throws Exception {
        mockMvc.perform(get(SETTINGS).with(user("reader").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leadDays").value(30))
                .andExpect(jsonPath("$.configuredLeadDays").value(30))
                .andExpect(jsonPath("$.fromConfiguration").value(true))
                .andExpect(jsonPath("$.minimumLeadDays").value(1))
                .andExpect(jsonPath("$.maximumLeadDays").value(365))
                // Email is off in the tests, and the page says so rather than implying it sent.
                .andExpect(jsonPath("$.emailEnabled").value(false))
                .andExpect(jsonPath("$.editable").value(false));
    }

    @Test
    void anAdministratorSetsItAndItStays() throws Exception {
        mockMvc.perform(put(SETTINGS)
                        .with(user("alice").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"leadDays\":45}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leadDays").value(45))
                .andExpect(jsonPath("$.fromConfiguration").value(false))
                .andExpect(jsonPath("$.updatedBy").value("alice"))
                .andExpect(jsonPath("$.editable").value(true));

        mockMvc.perform(get(SETTINGS).with(user("reader").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leadDays").value(45))
                .andExpect(jsonPath("$.configuredLeadDays").value(30))
                .andExpect(jsonPath("$.updatedBy").value("alice"));
    }

    @Test
    void aReaderCannotChangeIt() throws Exception {
        mockMvc.perform(put(SETTINGS)
                        .with(user("reader").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"leadDays\":45}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get(SETTINGS).with(user("reader").roles("USER")))
                .andExpect(jsonPath("$.leadDays").value(30));
    }

    @Test
    void aNumberThatIsNotAWarningIsRejected() throws Exception {
        for (String body : new String[] {"{\"leadDays\":0}", "{\"leadDays\":366}", "{\"leadDays\":-5}"}) {
            mockMvc.perform(put(SETTINGS)
                            .with(user("alice").roles("ADMIN"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value("Days before expiry must be between 1 and 365"));
        }
        mockMvc.perform(get(SETTINGS).with(user("alice").roles("ADMIN")))
                .andExpect(jsonPath("$.fromConfiguration").value(true));
    }

    @Test
    void nobodySignedInSeesNothing() throws Exception {
        mockMvc.perform(get(SETTINGS).with(anonymous())).andExpect(status().isUnauthorized());
    }
}
