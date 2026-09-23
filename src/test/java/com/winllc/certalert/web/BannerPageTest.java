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
 *
 * <p>It is drawn from the head fragment, which is the one thing every page includes, so
 * what these look for is the rule rather than an element.
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
                .andExpect(content().string(Matchers.containsString("body::before")))
                .andExpect(content().string(Matchers.containsString("body::after")))
                // The declaration has to be valid CSS, not merely contain the text. Escaped
                // inlining would render the quotes as &quot; here, which is a declaration
                // the browser discards - and a banner that silently does not appear.
                .andExpect(content().string(
                        Matchers.containsString("content: \"UNCLASSIFIED//FOUO")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("&quot;"))))
                // The configured values reach the stylesheet, and the page is padded to clear them.
                .andExpect(content().string(Matchers.containsString("height: 2rem")))
                .andExpect(content().string(Matchers.containsString("background: #006400")))
                .andExpect(content().string(Matchers.containsString("padding-top: 2rem")));
    }

    @Test
    void andOnTheSignInPage() throws Exception {
        mockMvc.perform(get("/login").accept(MediaType.TEXT_HTML).with(anonymous()))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("body::before")))
                .andExpect(content().string(Matchers.containsString("body::after")));
    }

    @Test
    void andOnTheErrorPage() throws Exception {
        mockMvc.perform(get("/no-such-page").accept(MediaType.TEXT_HTML))
                .andExpect(status().isNotFound())
                .andExpect(content().string(Matchers.containsString("body::before")));
    }

    @Test
    void andOnAMissingEntry() throws Exception {
        mockMvc.perform(get("/users/99999999").accept(MediaType.TEXT_HTML))
                .andExpect(status().isNotFound())
                .andExpect(content().string(Matchers.containsString("body::before")));
    }

    /**
     * The marking goes into a stylesheet, where an angle bracket left as itself would end
     * the style element and put whatever followed into the page as markup. It arrives
     * already escaped as a CSS code point instead.
     */
    @Test
    void theTextCannotEndTheStyleElement() throws Exception {
        mockMvc.perform(get("/servers").accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("UNCLASSIFIED//FOUO \\3c b\\3e ")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("UNCLASSIFIED//FOUO <b>"))));
    }
}
