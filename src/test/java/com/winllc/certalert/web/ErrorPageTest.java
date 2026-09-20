package com.winllc.certalert.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

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
 * What a failure looks like to each of the two audiences.
 *
 * <p>The same exceptions have to reach a browser as a page and a script as problem detail,
 * so every case here is asked for twice. The point being held down is that neither answer
 * leaks into the other's medium: a mistyped address in a browser used to serve JSON.
 */
@SpringBootTest
@ActiveProfiles("test")
@WithMockUser(roles = "USER")
class ErrorPageTest {

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
    void anAddressThatMatchesNothingIsAPageForABrowser() throws Exception {
        mockMvc.perform(get("/no-such-page").accept(MediaType.TEXT_HTML))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(view().name("error"))
                // The page picks its wording from this rather than printing the exception.
                .andExpect(model().attribute("status", 404))
                .andExpect(model().attribute("path", "/no-such-page"));
    }

    @Test
    void andIsProblemDetailForEverythingElse() throws Exception {
        mockMvc.perform(get("/no-such-page").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404));
    }

    /**
     * The API answers JSON whatever the caller said it accepts. A browser pointed at an
     * endpoint is still a script's endpoint, and a page there would break the caller that
     * meant to parse it.
     */
    @Test
    void theApiStaysJsonEvenWhenAskedForHtml() throws Exception {
        mockMvc.perform(get("/api/v1/nope").accept(MediaType.TEXT_HTML))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void anEntryThatIsNotThereKeepsItsOwnPage() throws Exception {
        // Not the generic page: there is something specific to say about a missing entry,
        // which is that the directory may have stopped publishing it.
        mockMvc.perform(get("/users/99999999").accept(MediaType.TEXT_HTML))
                .andExpect(status().isNotFound())
                .andExpect(view().name("not-found"))
                .andExpect(model().attributeExists("message"));
    }

    @Test
    void andIsProblemDetailOnTheApi() throws Exception {
        mockMvc.perform(get("/api/v1/users/99999999/certificates").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Resource not found"))
                .andExpect(jsonPath("$.detail").value("No directory user with id 99999999"));
    }

    /**
     * A caller that says nothing about what it accepts gets the page.
     *
     * <p>Only a request that names a machine format is taken to want one. Saying nothing
     * is what {@code curl} and every other bare client do, and outside the API there is
     * nothing but pages to serve them.
     */
    @Test
    void sayingNothingAboutHtmlStillGetsThePage() throws Exception {
        mockMvc.perform(get("/users/99999999"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("not-found"));

        mockMvc.perform(get("/no-such-page"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error"));
    }

    /**
     * Reached by Boot's error dispatch, which is what serves a denial from the filter chain
     * - the one path no exception handler here ever sees. Before there was a template of
     * this name it was the Whitelabel page.
     */
    @Test
    void theErrorDispatchRendersTheSamePage() throws Exception {
        mockMvc.perform(get("/error").accept(MediaType.TEXT_HTML))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(view().name("error"));
    }
}
