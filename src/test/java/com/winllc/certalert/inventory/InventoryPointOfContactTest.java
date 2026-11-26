package com.winllc.certalert.inventory;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.winllc.certalert.domain.DirectoryServer;
import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.repository.ServerContactRepository;
import java.time.Instant;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * {@code GET /api/v1/servers?pocUserId=} and {@code ?pocEmail=}: what an identity product
 * asks when a person leaves (the servers that need a new point of contact).
 */
@SpringBootTest
@ActiveProfiles("test")
@WithMockUser(roles = "USER")
class InventoryPointOfContactTest {

    @Autowired private WebApplicationContext context;
    @Autowired private DirectoryUserRepository users;
    @Autowired private DirectoryServerRepository servers;
    @Autowired private ServerContactRepository contacts;

    private MockMvc mockMvc;

    @BeforeEach
    void seed() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        contacts.deleteAll();
        servers.deleteAll();
        users.deleteAll();
        users.save(user("uid=alice,ou=people", "alice", "alice@example.gov"));
        users.save(user("uid=bob,ou=people", "bob", "bob@example.gov"));
        servers.save(server("cn=web01,ou=servers", "web01", List.of("alice@example.gov")));
        servers.save(server("cn=web02,ou=servers", "web02", List.of("ALICE@example.gov", "bob@example.gov")));
        servers.save(server("cn=db01,ou=servers", "db01", List.of("bob@example.gov")));
    }

    @Test
    void aPersonsIdGivesExactlyTheirServers() throws Exception {
        Long bob = users.findByDn("uid=bob,ou=people").orElseThrow().getId();
        mockMvc.perform(get("/api/v1/servers").param("pocUserId", bob.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].commonName").value(Matchers.containsInAnyOrder("web02", "db01")));
        Long alice = users.findByDn("uid=alice,ou=people").orElseThrow().getId();
        mockMvc.perform(get("/api/v1/servers").param("pocUserId", alice.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].commonName").value(Matchers.containsInAnyOrder("web01", "web02")));
    }

    @Test
    void anUnknownIdMatchesNoServer() throws Exception {
        mockMvc.perform(get("/api/v1/servers").param("pocUserId", "999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void anAddressMatchesCaseAside() throws Exception {
        mockMvc.perform(get("/api/v1/servers").param("pocEmail", "ALICE@EXAMPLE.GOV"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
        // Without either, every server.
        mockMvc.perform(get("/api/v1/servers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    private static DirectoryUser user(String dn, String uid, String email) {
        DirectoryUser user = new DirectoryUser(dn);
        user.setUid(uid);
        user.setDisplayName(uid);
        user.setCommonName(uid);
        user.setIcEmail(email);
        user.refreshIdentifiers(email);
        user.markSynced(Instant.now());
        user.refreshCertificateSummary();
        return user;
    }

    private static DirectoryServer server(String dn, String cn, List<String> pocs) {
        DirectoryServer server = new DirectoryServer(dn);
        server.setCommonName(cn);
        server.setServerUrl(cn + ".example.gov");
        server.setServerPocs(pocs);
        server.markSynced(Instant.now());
        server.refreshCertificateSummary();
        return server;
    }
}
