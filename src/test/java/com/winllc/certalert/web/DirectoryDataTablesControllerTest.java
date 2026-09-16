package com.winllc.certalert.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.CertificateStatus;
import com.winllc.certalert.domain.DirectoryServer;
import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.DirectoryUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Exercises the search tables end to end: a real DataTables request body, the real
 * specifications, a real database.
 */
@SpringBootTest
@ActiveProfiles("test")
class DirectoryDataTablesControllerTest {

    private static final String USERS = "/api/v1/datatables/users";
    private static final String SERVERS = "/api/v1/datatables/servers";

    /** Column order matches the users table in the template. */
    private static final String[] USER_COLUMNS = {
        "id", "displayName", "uid", "email", "title", "organization",
        "organizationalUnit", "certificateCount", "certificateStatus",
        "earliestExpiry", "lastSyncedAt", "dn"
    };

    /** Column order matches the servers table in the template. */
    private static final String[] SERVER_COLUMNS = {
        "id", "commonName", "serverUrl", "serverPocDisplay", "description", "organization",
        "organizationalUnit", "certificateCount", "certificateStatus",
        "earliestExpiry", "lastSyncedAt", "dn"
    };

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private DirectoryUserRepository userRepository;

    @Autowired
    private DirectoryServerRepository serverRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void seed() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        serverRepository.deleteAll();
        userRepository.deleteAll();

        Instant now = Instant.now();

        userRepository.save(user("uid=alice,ou=people", "alice", "Alice Archer", "alice@example.gov",
                certificate("CN=alice", now.plus(Duration.ofDays(400)), now)));
        userRepository.save(user("uid=bob,ou=people", "bob", "Bob Baker", "bob@example.gov",
                certificate("CN=bob", now.minus(Duration.ofDays(5)), now)));
        userRepository.save(user("uid=carol,ou=people", "carol", "Carol Chase", "carol@example.gov",
                certificate("CN=carol", now.plus(Duration.ofDays(10)), now)));
        userRepository.save(user("uid=dave,ou=people", "dave", "Dave Doyle", "dave@example.gov"));

        serverRepository.save(server("cn=web01,ou=servers", "web01", "web01.example.gov",
                List.of("alice@example.gov"), certificate("CN=web01", now.plus(Duration.ofDays(200)), now)));
        serverRepository.save(server("cn=web02,ou=servers", "web02", "web02.example.gov",
                List.of("ALICE@example.gov", "bob@example.gov"),
                certificate("CN=web02", now.minus(Duration.ofDays(2)), now)));
        serverRepository.save(server("cn=db01,ou=servers", "db01", "db01.example.gov",
                List.of("bob@example.gov"), certificate("CN=db01", now.plus(Duration.ofDays(500)), now)));
    }

    @Test
    void returnsAPageOfUsersWithTheTotalCounts() throws Exception {
        mockMvc.perform(post(USERS).contentType(MediaType.APPLICATION_JSON).content(usersRequest(0, 2, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draw").value(1))
                .andExpect(jsonPath("$.recordsTotal").value(4))
                .andExpect(jsonPath("$.recordsFiltered").value(4))
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void globalSearchMatchesAcrossSearchableColumns() throws Exception {
        mockMvc.perform(post(USERS).contentType(MediaType.APPLICATION_JSON)
                        .content(usersRequest(0, 10, "Archer", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsFiltered").value(1))
                .andExpect(jsonPath("$.data[0].email").value("alice@example.gov"));
    }

    @Test
    void perColumnSearchNarrowsToThatColumn() throws Exception {
        mockMvc.perform(post(USERS).contentType(MediaType.APPLICATION_JSON)
                        .content(usersRequest(0, 10, null, "bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsFiltered").value(1))
                .andExpect(jsonPath("$.data[0].uid").value("bob"));
    }

    @Test
    void expiredFilterReturnsOnlyEntitiesHoldingAnExpiredCertificate() throws Exception {
        mockMvc.perform(post(USERS + "?expired=true").contentType(MediaType.APPLICATION_JSON)
                        .content(usersRequest(0, 10, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsFiltered").value(1))
                .andExpect(jsonPath("$.data[0].uid").value("bob"))
                .andExpect(jsonPath("$.data[0].certificateStatus").value("EXPIRED"));
    }

    @Test
    void notExpiredFilterExcludesExpiredAndCertificatelessEntities() throws Exception {
        mockMvc.perform(post(USERS + "?expired=false").contentType(MediaType.APPLICATION_JSON)
                        .content(usersRequest(0, 10, null, null)))
                .andExpect(status().isOk())
                // alice (valid) and carol (expiring soon); bob is expired, dave has none.
                .andExpect(jsonPath("$.recordsFiltered").value(2));
    }

    @Test
    void statusFilterSelectsASingleState() throws Exception {
        mockMvc.perform(post(USERS + "?certificateStatus=EXPIRING_SOON").contentType(MediaType.APPLICATION_JSON)
                        .content(usersRequest(0, 10, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsFiltered").value(1))
                .andExpect(jsonPath("$.data[0].uid").value("carol"));
    }

    @Test
    void hasCertificatesFilterFindsEntitiesWithNone() throws Exception {
        mockMvc.perform(post(USERS + "?hasCertificates=false").contentType(MediaType.APPLICATION_JSON)
                        .content(usersRequest(0, 10, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsFiltered").value(1))
                .andExpect(jsonPath("$.data[0].uid").value("dave"))
                .andExpect(jsonPath("$.data[0].certificateStatus").value("NONE"));
    }

    @Test
    void expiringWithinDaysWindowsOnTheNextExpiry() throws Exception {
        mockMvc.perform(post(USERS + "?expiringWithinDays=30").contentType(MediaType.APPLICATION_JSON)
                        .content(usersRequest(0, 10, null, null)))
                .andExpect(status().isOk())
                // carol expires in 10 days; alice is 400 days out and bob already expired.
                .andExpect(jsonPath("$.recordsFiltered").value(1))
                .andExpect(jsonPath("$.data[0].uid").value("carol"));
    }

    @Test
    void serversFilterByPointOfContactEmail() throws Exception {
        mockMvc.perform(post(SERVERS + "?pocEmail=alice@example.gov").contentType(MediaType.APPLICATION_JSON)
                        .content(serversRequest(0, 10, null)))
                .andExpect(status().isOk())
                // web01 lists alice, web02 lists her in a different case; db01 does not.
                .andExpect(jsonPath("$.recordsFiltered").value(2));
    }

    @Test
    void pointOfContactMatchIgnoresCase() throws Exception {
        mockMvc.perform(post(SERVERS + "?pocEmail=ALICE@EXAMPLE.GOV").contentType(MediaType.APPLICATION_JSON)
                        .content(serversRequest(0, 10, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsFiltered").value(2));
    }

    @Test
    void serversCanBeFilteredByTheUsersOwnId() throws Exception {
        Long bobId = userRepository.findByDn("uid=bob,ou=people").orElseThrow().getId();

        mockMvc.perform(post(SERVERS + "?pocUserId=" + bobId).contentType(MediaType.APPLICATION_JSON)
                        .content(serversRequest(0, 10, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsFiltered").value(2));
    }

    @Test
    void anUnknownUserIdMatchesNoServersRatherThanAllOfThem() throws Exception {
        mockMvc.perform(post(SERVERS + "?pocUserId=999999").contentType(MediaType.APPLICATION_JSON)
                        .content(serversRequest(0, 10, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsFiltered").value(0))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void pointOfContactAndCertificateFiltersCombine() throws Exception {
        mockMvc.perform(post(SERVERS + "?pocEmail=alice@example.gov&expired=true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(serversRequest(0, 10, null)))
                .andExpect(status().isOk())
                // Of alice's two servers only web02 holds an expired certificate.
                .andExpect(jsonPath("$.recordsFiltered").value(1))
                .andExpect(jsonPath("$.data[0].commonName").value("web02"));
    }

    @Test
    void serverRowsCarryTheFlattenedContactList() throws Exception {
        mockMvc.perform(post(SERVERS).contentType(MediaType.APPLICATION_JSON)
                        .content(serversRequest(0, 10, "web02")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsFiltered").value(1))
                .andExpect(jsonPath("$.data[0].serverPocDisplay").value("alice@example.gov, bob@example.gov"));
    }

    private DirectoryUser user(String dn, String uid, String displayName, String email, CachedCertificate... certs) {
        DirectoryUser user = new DirectoryUser(dn);
        user.setUid(uid);
        user.setDisplayName(displayName);
        user.setCommonName(displayName);
        user.setIcEmail(email);
        user.setOrganization("Example Agency");
        for (CachedCertificate certificate : certs) {
            user.addCertificate(certificate);
        }
        user.refreshIdentifiers(email);
        user.markSynced(Instant.now());
        user.refreshCertificateSummary();
        return user;
    }

    private DirectoryServer server(String dn, String cn, String serverUrl, List<String> pocs, CachedCertificate... certs) {
        DirectoryServer server = new DirectoryServer(dn);
        server.setCommonName(cn);
        server.setServerUrl(serverUrl);
        server.setServerPocs(pocs);
        server.setOrganization("Example Agency");
        for (CachedCertificate certificate : certs) {
            server.addCertificate(certificate);
        }
        server.markSynced(Instant.now());
        server.refreshCertificateSummary();
        return server;
    }

    /** Builds a certificate already carrying the state the roll-up should report. */
    private CachedCertificate certificate(String subject, Instant notAfter, Instant now) {
        CachedCertificate certificate = new CachedCertificate(
                String.format("%064x", Math.abs((long) subject.hashCode())),
                "01",
                subject,
                "CN=Example CA",
                now.minus(Duration.ofDays(365)),
                notAfter,
                "SHA256withRSA",
                "RSA",
                2048,
                null,
                now);
        certificate.updateStatus(statusFor(notAfter, now), now);
        return certificate;
    }

    private CertificateStatus statusFor(Instant notAfter, Instant now) {
        if (!notAfter.isAfter(now)) {
            return CertificateStatus.EXPIRED;
        }
        return Duration.between(now, notAfter).toDays() < 30
                ? CertificateStatus.EXPIRING_SOON
                : CertificateStatus.VALID;
    }

    private String usersRequest(int start, int length, String globalSearch, String uidSearch) {
        return request(USER_COLUMNS, start, length, globalSearch, "uid", uidSearch);
    }

    private String serversRequest(int start, int length, String globalSearch) {
        return request(SERVER_COLUMNS, start, length, globalSearch, null, null);
    }

    /** The JSON body DataTables posts for a server-side table. */
    private String request(
            String[] columns,
            int start,
            int length,
            String globalSearch,
            String searchColumn,
            String columnSearchValue) {

        StringBuilder json = new StringBuilder("{\"draw\":1,\"start\":")
                .append(start)
                .append(",\"length\":")
                .append(length)
                .append(",\"search\":{\"value\":\"")
                .append(globalSearch == null ? "" : globalSearch)
                .append("\",\"regex\":false},\"order\":[],\"columns\":[");
        for (int i = 0; i < columns.length; i++) {
            // The expand column carries the id and is neither searched nor ordered on.
            boolean searchable = !columns[i].equals("id");
            String search = columns[i].equals(searchColumn) && columnSearchValue != null ? columnSearchValue : "";
            json.append(i == 0 ? "" : ",")
                    .append("{\"data\":\"")
                    .append(columns[i])
                    .append("\",\"name\":\"\",\"searchable\":")
                    .append(searchable)
                    .append(",\"orderable\":true,\"search\":{\"value\":\"")
                    .append(search)
                    .append("\",\"regex\":false}}");
        }
        return json.append("]}").toString();
    }
}
