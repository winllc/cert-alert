package com.winllc.certalert.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.security.DirectoryPrincipal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The filters on the servers page.
 *
 * <p>One of them names the person reading it, which is the only filter on either search
 * page that cannot be written down in advance: it needs the signed-in account resolved to
 * a directory entry. Where there is no such entry - a service account, or a name the
 * directory holds twice - the switch would narrow the table to nothing, so it is not
 * offered at all.
 */
@SpringBootTest
@ActiveProfiles("test")
class ServersPageTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private DirectoryUserRepository users;

    private MockMvc mockMvc;
    private Long aliceId;

    @BeforeEach
    void seed() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        users.deleteAll();

        DirectoryUser alice = new DirectoryUser("uid=alice,ou=people");
        alice.setUid("alice");
        alice.setDisplayName("Alice Archer");
        alice.refreshIdentifiers("alice@example.gov");
        alice.markSynced(Instant.now());
        aliceId = users.save(alice).getId();
    }

    /** The switch carries the entry's id, which is what the filter travels as. */
    @Test
    void somebodyTheDirectoryKnowsIsOfferedTheirOwnServers() throws Exception {
        mockMvc.perform(get("/servers").with(signedIn(aliceId, "alice")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"only-mine\"")))
                .andExpect(content().string(containsString("data-user-id=\"" + aliceId + "\"")))
                .andExpect(content().string(containsString("Only mine")));
    }

    /**
     * Signing in by password gives a principal with no entry id on it - the bind knows the
     * name it bound as and nothing else - so the name is looked up. The switch has to work
     * for those accounts too, which are most of them.
     */
    @Test
    void anAccountWhoseEntryIsFoundByNameIsOfferedItToo() throws Exception {
        mockMvc.perform(get("/servers").with(signedIn(null, "alice@example.gov")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-user-id=\"" + aliceId + "\"")));
    }

    /**
     * An account with no entry of its own is not offered a switch that could only ever show
     * an empty table.
     */
    @Test
    void anAccountWithNoDirectoryEntryIsNotOfferedIt() throws Exception {
        mockMvc.perform(get("/servers").with(signedIn(null, "svc-monitoring")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("id=\"only-mine\""))))
                // And the rest of the filters are all still there.
                .andExpect(content().string(containsString("id=\"cert-state\"")))
                .andExpect(content().string(containsString("id=\"poc-email\"")));
    }

    /**
     * The expired switch is gone: the certificate state list already says what to do about
     * expired entries, and the two of them disagreeing was the confusing part - a list
     * reading "Any" beside a switch quietly hiding half the directory.
     */
    @Test
    void thereIsNoSeparateExpiredSwitch() throws Exception {
        mockMvc.perform(get("/servers").with(signedIn(aliceId, "alice")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("id=\"show-expired\""))))
                .andExpect(content().string(containsString("Has an expired certificate")));
    }

    /**
     * What has already lapsed is not what is about to, so it is out of the way to begin
     * with - and the option that does it is the one selected, rather than a switch
     * somewhere else quietly doing it while the list reads "Any".
     */
    @Test
    void expiredEntriesAreOutOfTheWayByDefaultAndTheListSaysSo() throws Exception {
        mockMvc.perform(get("/servers").with(signedIn(aliceId, "alice")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<option value=\"standing\" selected>Hide expired")))
                // And reaching them is a choice on the same list, not a hunt.
                .andExpect(content().string(containsString("Any, expired included")));
    }

    /** The people page keeps its own, which this did not touch. */
    @Test
    void thePeoplePageIsUnchanged() throws Exception {
        mockMvc.perform(get("/users").with(signedIn(aliceId, "alice")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"show-expired\"")));
    }

    private RequestPostProcessor signedIn(Long directoryUserId, String username) {
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                new DirectoryPrincipal(
                        username,
                        username,
                        directoryUserId,
                        directoryUserId == null ? null : "uid=" + username + ",ou=people",
                        DirectoryPrincipal.AuthenticationMethod.LDAP,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        return SecurityMockMvcRequestPostProcessors.authentication(authentication);
    }
}
