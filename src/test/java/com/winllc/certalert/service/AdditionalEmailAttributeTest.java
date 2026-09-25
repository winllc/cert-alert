package com.winllc.certalert.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.domain.UserEmailAlias;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.support.EmbeddedDirectory;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Addresses out of attributes the schema does not define.
 *
 * <p>The IC FSD names five, and a real directory usually carries more: an agency's own
 * {@code alternateMail}, a mail-system attribute that predates the schema, a team address
 * kept on the person who owns it. A {@code serverPOC} written with one of those names
 * nobody as far as the join is concerned, and the server it belongs to quietly has no
 * contact at all.
 *
 * <p>Driven through the real sweep against a real directory, because the interesting parts
 * are all at that edge: whether the attribute is asked for at all, what a multi-valued one
 * yields, and what happens to a value that is not an address.
 */
@SpringBootTest
@ActiveProfiles("test")
class AdditionalEmailAttributeTest {

    private static EmbeddedDirectory directory;

    @Autowired
    private DirectorySyncService syncService;

    @Autowired
    private DirectoryUserRepository users;

    @Autowired
    private DirectoryServerRepository servers;

    @Autowired
    private UserEmailAliasService aliasService;

    @Autowired
    private NotificationRecipients recipients;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private org.springframework.web.context.WebApplicationContext context;

    private org.springframework.test.web.servlet.MockMvc mockMvc;

    @BeforeAll
    static void publishADirectory() {
        directory = new EmbeddedDirectory();
        directory.addUserWithExtraAttributes(
                "extra",
                "Ex Tra",
                "ex.tra@intelink.ic.gov",
                Map.of(
                        // Multi-valued, and one of the values holds two addresses at once -
                        // which is what a directory filled in through a form looks like.
                        "alternateMail",
                        List.of("ex.tra@agency.example.gov, tra.team@agency.example.gov", "Duty Officer"),
                        "legacyMail",
                        List.of("etra@old.example.gov")));

        // The server names them by an address only the extra attribute knows.
        directory.addServer(
                "extra-server",
                "https://extra-server.example.gov",
                new String[] {"tra.team@agency.example.gov"});
    }

    @AfterAll
    static void stop() {
        if (directory != null) {
            directory.close();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.ldap.urls", () -> directory.url());
        registry.add("spring.ldap.base", () -> EmbeddedDirectory.BASE_DN);
        registry.add("cert-alert.ldap.user.search-base", () -> "ou=people");
        registry.add("cert-alert.ldap.server.search-base", () -> "ou=servers");
        registry.add("cert-alert.ldap.user.additional-email-attributes", () -> "alternateMail,legacyMail");
        // Naming one of them here makes it the address the tables show.
        registry.add("cert-alert.ldap.user.email-precedence", () -> "alternateMail,icEmail,mail");
    }

    @BeforeEach
    void sweep() {
        mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
        if (users.findByDn(dn()).isEmpty()) {
            syncService.syncUsers();
            syncService.syncServers();
        }
    }

    /** Every address the extra attributes hold, and nothing that is not one. */
    @Test
    void addressesFromTheExtraAttributesAreIndexed() {
        assertThat(identifiers())
                .contains(
                        "ex.tra@intelink.ic.gov",
                        "ex.tra@agency.example.gov",
                        "tra.team@agency.example.gov",
                        "etra@old.example.gov")
                // "Duty Officer" sits in an attribute declared as a place addresses live,
                // so it is bad data rather than a second kind of value: indexing it would
                // widen what a serverPOC matches to a label nothing can be sent to.
                .doesNotContain("duty officer");
    }

    /**
     * The point of indexing them: a server whose contact was written with an address only
     * the extra attribute knows resolves to the person, so they hear about its certificates.
     */
    @Test
    void aServerNamedByOneOfThemResolvesToThePerson() {
        Long serverId = servers.findByDn("cn=extra-server," + EmbeddedDirectory.SERVERS_DN)
                .orElseThrow()
                .getId();
        Long userId = users.findByDn(dn()).orElseThrow().getId();

        assertThat(recipients.forServer(serverId))
                .extracting(NotificationRecipients.Recipient::userId)
                .contains(userId);
    }

    /** Naming an extra attribute in the precedence list makes it the primary address. */
    @Test
    void anExtraAttributeCanLead() {
        DirectoryUser person = users.findByDn(dn()).orElseThrow();

        assertThat(person.getEmail()).isEqualTo("ex.tra@agency.example.gov");
    }

    /**
     * Adding an address here rebuilds the identifier set from what the entry holds, so
     * anything only the directory knows has to be held on the entry - otherwise one alias
     * edit would drop every extra address until the next sweep.
     */
    @Test
    void editingTheAddressesAddedHereKeepsTheOnesFromTheDirectory() {
        Long userId = users.findByDn(dn()).orElseThrow().getId();

        aliasService.add(userId, "ex.tra@lists.example.gov", UserEmailAlias.Kind.GROUP, "A list", "alice");

        assertThat(identifiers())
                .contains("ex.tra@lists.example.gov")
                .contains("tra.team@agency.example.gov", "etra@old.example.gov");
    }

    /** And a person's own page shows them, or the join would have no visible reason. */
    @Test
    void thePersonsPageListsThemWithTheRest() throws Exception {
        Long userId = users.findByDn(dn()).orElseThrow().getId();

        mockMvc.perform(get("/api/v1/users/" + userId + "/addresses")
                        .with(SecurityMockMvcRequestPostProcessors.user("reader").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.directory").value(org.hamcrest.Matchers.hasItems(
                        "ex.tra@agency.example.gov", "tra.team@agency.example.gov", "etra@old.example.gov")));
    }

    private static String dn() {
        return "uid=extra," + EmbeddedDirectory.PEOPLE_DN;
    }

    private List<String> identifiers() {
        return transactionTemplate.execute(status -> List.copyOf(
                users.findByDn(dn()).orElseThrow().getIdentifiers()));
    }
}
