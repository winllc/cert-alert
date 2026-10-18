package com.winllc.certalert.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.domain.UserEmailAlias;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.repository.UserEmailAliasRepository;
import com.winllc.certalert.security.DirectoryPrincipal;
import com.winllc.certalert.security.DirectoryPrincipalResolver;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/**
 * An address added here binds a person to servers and notices; it never makes anybody an
 * administrator.
 *
 * <p>Run with address editing open to anyone signed in, the documented option that made
 * this matter: there, a reader could add an administrator's address to their own entry and
 * be one at their next sign-in. The administrator is listed by uid, and by address as well,
 * as a configuration written for an earlier release may: the address grants nothing, and
 * is still refused as somebody else's.
 */
@SpringBootTest(properties = {
    "cert-alert.security.contact-editors=AUTHENTICATED",
    "cert-alert.security.admin-identifiers=admin,admin@example.gov"
})
@ActiveProfiles("test")
class AdminIdentifierAliasTest {

    private static final String ADMIN = "admin@example.gov";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private DirectoryUserRepository users;

    @Autowired
    private UserEmailAliasRepository aliases;

    @Autowired
    private DirectoryPrincipalResolver resolver;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private MockMvc mockMvc;
    private Long readerId;

    @BeforeEach
    void seed() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        users.deleteAll();

        DirectoryUser reader = new DirectoryUser("uid=reader,ou=people");
        reader.setUid("reader");
        reader.setDisplayName("Rea Der");
        reader.refreshIdentifiers("reader@example.gov");
        reader.markSynced(Instant.now());
        readerId = users.save(reader).getId();
    }

    @Test
    void anAdministratorsAddressCannotBeAddedAsSomebodysOwn() throws Exception {
        mockMvc.perform(post("/api/v1/users/{id}/addresses", readerId)
                        .with(signedIn(readerId, "reader@example.gov"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"address\":\"Admin@Example.gov\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString(
                        "cert-alert.security.admin-identifiers")));

        assertThat(aliases.findByUserIdOrderByAddressAsc(readerId)).isEmpty();
        assertThat(users.findIdentifiersById(readerId)).doesNotContain(ADMIN);
    }

    /** Anything else is added as ever: that is what the feature is for. */
    @Test
    void anOrdinaryAddressIsStillAdded() throws Exception {
        mockMvc.perform(post("/api/v1/users/{id}/addresses", readerId)
                        .with(signedIn(readerId, "reader@example.gov"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"address\":\"ops-list@example.gov\",\"kind\":\"GROUP\"}"))
                .andExpect(status().isCreated());

        assertThat(users.findIdentifiersById(readerId)).contains("ops-list@example.gov");
    }

    /**
     * One already there - from before this was refused, or put in the table by hand - still
     * binds the person to servers named after it, and grants nothing.
     */
    @Test
    void anAdministratorsAddressAlreadyAddedGrantsNothing() {
        transactionTemplate.executeWithoutResult(status -> {
            DirectoryUser reader = users.findById(readerId).orElseThrow();
            aliases.save(new UserEmailAlias(
                    reader, ADMIN, UserEmailAlias.Kind.PERSONAL, null, "someone", Instant.now()));
            reader.refreshIdentifiers(reader.getEmail(), List.of(ADMIN));
        });
        assertThat(users.findIdentifiersById(readerId)).as("it still binds servers").contains(ADMIN);

        DirectoryPrincipal principal = resolver.principalFor(
                users.findById(readerId).orElseThrow(), DirectoryPrincipal.AuthenticationMethod.LDAP);

        assertThat(principal.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly(DirectoryPrincipalResolver.ROLE_USER);
    }

    private static RequestPostProcessor signedIn(Long userId, String username) {
        DirectoryPrincipal principal = new DirectoryPrincipal(
                username,
                username,
                userId,
                "uid=reader,ou=people",
                DirectoryPrincipal.AuthenticationMethod.LDAP,
                List.of(new SimpleGrantedAuthority(DirectoryPrincipalResolver.ROLE_USER)));
        return SecurityMockMvcRequestPostProcessors.authentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }
}
