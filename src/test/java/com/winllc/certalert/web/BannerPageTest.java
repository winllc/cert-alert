package com.winllc.certalert.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The banner has to be on every page, not on most of them.
 *
 * <p>What it says is the classification of what is on the screen, so a page that misses it
 * is the one that matters: the sign-in page before anyone is signed in, and the error page
 * after something has gone wrong, are both still pages showing this application's data.
 */
@SpringBootTest(properties = {
    "cert-alert.banner.enabled=true",
    "cert-alert.banner.text=UNCLASSIFIED//FOUO <b>",
    "cert-alert.banner.background=#006400",
    "cert-alert.banner.height=2rem"
})
@ActiveProfiles("test")
@WithMockUser(roles = "USER")
class BannerPageTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }

    @Test
    void bothBarsAreOnAnOrdinaryPage() throws Exception {
        mockMvc.perform(get("/users").accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("classification-banner-top")))
                .andExpect(content().string(Matchers.containsString("classification-banner-bottom")))
                .andExpect(content().string(Matchers.containsString("UNCLASSIFIED//FOUO")))
                // The configured values reach the stylesheet, and the page is padded to clear them.
                .andExpect(content().string(Matchers.containsString("--banner-height: 2rem")))
                .andExpect(content().string(Matchers.containsString("--banner-bg: #006400")))
                .andExpect(content().string(Matchers.containsString("padding-top: var(--banner-height)")));
    }

    @Test
    void andOnTheSignInPage() throws Exception {
        mockMvc.perform(get("/login").accept(MediaType.TEXT_HTML).with(anonymous()))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("classification-banner-top")))
                .andExpect(content().string(Matchers.containsString("classification-banner-bottom")));
    }

    @Test
    void andOnTheErrorPage() throws Exception {
        mockMvc.perform(get("/no-such-page").accept(MediaType.TEXT_HTML))
                .andExpect(status().isNotFound())
                .andExpect(content().string(Matchers.containsString("classification-banner-top")));
    }

    @Test
    void andOnAMissingEntry() throws Exception {
        mockMvc.perform(get("/users/99999999").accept(MediaType.TEXT_HTML))
                .andExpect(status().isNotFound())
                .andExpect(content().string(Matchers.containsString("classification-banner-top")));
    }

    /**
     * The marking is text. Rendered as markup it would be at best mangled - a caveat in
     * angle brackets swallowed by the browser - and at worst a way to put tags on every
     * page in the application from a properties file.
     */
    @Test
    void theTextIsRenderedAsTextRatherThanAsMarkup() throws Exception {
        mockMvc.perform(get("/servers").accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("UNCLASSIFIED//FOUO &lt;b&gt;")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("UNCLASSIFIED//FOUO <b>"))));
    }
}
