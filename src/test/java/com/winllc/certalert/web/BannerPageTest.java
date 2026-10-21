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
 * <p>It is real text - an element at each end of the body, announced by a screen reader as
 * the marking (CA-16) - rather than generated CSS content, which some screen readers skip.
 * Every page template includes both, and the last test here holds a page added later to it.
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

    private static final String TOP = "<div class=\"classification-banner classification-banner-top\" role=\"region\" "
            + "aria-label=\"Classification\">UNCLASSIFIED//FOUO &lt;b&gt;</div>";
    private static final String BOTTOM = "<div class=\"classification-banner classification-banner-bottom\" role=\"region\" "
            + "aria-label=\"Classification, end of page\">UNCLASSIFIED//FOUO &lt;b&gt;</div>";

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
                .andExpect(content().string(Matchers.containsString(TOP)))
                .andExpect(content().string(Matchers.containsString(BOTTOM)))
                // The configured values reach the stylesheet, and the page is padded to clear them.
                .andExpect(content().string(Matchers.containsString("height: 2rem")))
                .andExpect(content().string(Matchers.containsString("background: #006400")))
                .andExpect(content().string(Matchers.containsString("padding-top: 2rem")))
                // The words are in the page, not in generated content a screen reader may skip.
                .andExpect(content().string(Matchers.not(Matchers.containsString("content: \"UNCLASSIFIED"))));
    }

    @Test
    void andOnTheSignInPage() throws Exception {
        mockMvc.perform(get("/login").accept(MediaType.TEXT_HTML).with(anonymous()))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString(TOP)))
                .andExpect(content().string(Matchers.containsString(BOTTOM)));
    }

    @Test
    void andOnTheErrorPage() throws Exception {
        mockMvc.perform(get("/no-such-page").accept(MediaType.TEXT_HTML))
                .andExpect(status().isNotFound())
                .andExpect(content().string(Matchers.containsString(TOP)))
                .andExpect(content().string(Matchers.containsString(BOTTOM)));
    }

    @Test
    void andOnAMissingEntry() throws Exception {
        mockMvc.perform(get("/users/99999999").accept(MediaType.TEXT_HTML))
                .andExpect(status().isNotFound())
                .andExpect(content().string(Matchers.containsString(TOP)));
    }

    /** The marking is text: an angle bracket in it is shown, never taken as markup. */
    @Test
    void theTextCannotBecomeMarkup() throws Exception {
        mockMvc.perform(get("/servers").accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("UNCLASSIFIED//FOUO &lt;b&gt;")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("UNCLASSIFIED//FOUO <b>"))));
    }

    @Autowired
    private com.winllc.certalert.service.MailOutbox outbox;

    @Autowired
    private com.winllc.certalert.repository.OutboundEmailRepository emails;

    /** Email carries the same marking: the subject, and the top and bottom of the text and the HTML. */
    @Test
    void everyEmailCarriesTheMarking() {
        Long id = outbox.queue(new com.winllc.certalert.domain.OutboundEmail(
                com.winllc.certalert.domain.OutboundEmail.Kind.ALERT, "cert-alert@example.gov",
                java.util.List.of("pki@example.gov"), "[cert-alert] A certificate expires", "Renew it.",
                "<html><body style=\"margin:0\"><p>Renew it.</p></body></html>", null, null, java.time.Instant.now()), false);
        com.winllc.certalert.domain.OutboundEmail email = emails.findById(id).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(email.getSubject())
                .isEqualTo("[UNCLASSIFIED//FOUO <b>] [cert-alert] A certificate expires");
        org.assertj.core.api.Assertions.assertThat(email.getTextBody())
                .startsWith("UNCLASSIFIED//FOUO <b>\n\nRenew it.").endsWith("UNCLASSIFIED//FOUO <b>\n");
        org.assertj.core.api.Assertions.assertThat(email.getHtmlBody())
                .startsWith("<html><body style=\"margin:0\"><div style=\"background:#006400")
                .contains("UNCLASSIFIED//FOUO &lt;b&gt;</div><p>Renew it.</p><div")
                .doesNotContain("FOUO <b>")
                .endsWith("</div></body></html>");
        emails.deleteById(id);
    }

    /** Every page template draws both bars - so a page added tomorrow cannot leave them out. */
    @Test
    void everyPageTemplateIncludesBothBars() throws Exception {
        org.springframework.core.io.Resource[] pages = new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                .getResources("classpath:/templates/*.html");
        org.assertj.core.api.Assertions.assertThat(pages).hasSizeGreaterThan(15);
        for (org.springframework.core.io.Resource page : pages) {
            String template = page.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            org.assertj.core.api.Assertions.assertThat(template).as(page.getFilename())
                    .contains("classificationBanner('top')", "classificationBanner('bottom')");
        }
    }
}
