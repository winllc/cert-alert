package com.winllc.certalert.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.CertificateRisk;
import com.winllc.certalert.domain.CertificateStatus;
import com.winllc.certalert.domain.DirectoryServer;
import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.repository.ServerContactRepository;
import com.winllc.certalert.service.ServerContactService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Exercises the search tables end to end: a real DataTables request body, the real
 * specifications, a real database.
 */
@SpringBootTest
@ActiveProfiles("test")
@WithMockUser(roles = "USER")
class DirectoryDataTablesControllerTest {

    private static final String USERS = "/api/v1/datatables/users";
    private static final String SERVERS = "/api/v1/datatables/servers";

    /** Column order matches the users table in the template. */
    private static final String[] USER_COLUMNS = {
        "id", "displayName", "uid", "email", "title", "organization",
        "organizationalUnit", "certificateCount", "certificateStatus",
        "earliestExpiry", "latestExpiry", "lastSyncedAt", "dn"
    };

    /** Column order matches the servers table in the template. */
    private static final String[] SERVER_COLUMNS = {
        "id", "commonName", "serverUrl", "serverPocDisplay", "description", "organization",
        "organizationalUnit", "certificateCount", "certificateStatus",
        "earliestExpiry", "latestExpiry", "lastSyncedAt", "dn"
    };

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private DirectoryUserRepository userRepository;

    @Autowired
    private DirectoryServerRepository serverRepository;

    @Autowired
    private ServerContactRepository contactRepository;

    @Autowired
    private ServerContactService contactService;

    private MockMvc mockMvc;

    @BeforeEach
    void seed() {
        // With the security filter chain in place, so these tests run against the same
        // stack a browser hits rather than an unprotected one.
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        contactRepository.deleteAll();
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
        mockMvc.perform(post(USERS).contentType(MediaType.APPLICATION_JSON).content(usersRequest(0, 2, null, null))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draw").value(1))
                .andExpect(jsonPath("$.recordsTotal").value(4))
                .andExpect(jsonPath("$.recordsFiltered").value(4))
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void globalSearchMatchesAcrossSearchableColumns() throws Exception {
        mockMvc.perform(post(USERS).contentType(MediaType.APPLICATION_JSON)
                        .content(usersRequest(0, 10, "Archer", null))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsFiltered").value(1))
                .andExpect(jsonPath("$.data[0].email").value("alice@example.gov"));
    }

    @Test
    void perColumnSearchNarrowsToThatColumn() throws Exception {
        mockMvc.perform(post(USERS).contentType(MediaType.APPLICATION_JSON)
                        .content(usersRequest(0, 10, null, "bob"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsFiltered").value(1))
                .andExpect(jsonPath("$.data[0].uid").value("bob"));
    }

    @Test
    void expiredFilterReturnsOnlyEntitiesHoldingAnExpiredCertificate() throws Exception {
        mockMvc.perform(post(USERS + "?expired=true").contentType(MediaType.APPLICATION_JSON)
                        .content(usersRequest(0, 10, null, null))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsFiltered").value(1))
                .andExpect(jsonPath("$.data[0].uid").value("bob"))
                .andExpect(jsonPath("$.data[0].certificateStatus").value("EXPIRED"));
    }

    @Test
    void notExpiredFilterExcludesExpiredAndCertificatelessEntities() throws Exception {
        mockMvc.perform(post(USERS + "?expired=false").contentType(MediaType.APPLICATION_JSON)
                        .content(usersRequest(0, 10, null, null))
                        .with(csrf()))
                .andExpect(status().isOk())
                // alice (valid) and carol (expiring soon); bob is expired, dave has none.
                .andExpect(jsonPath("$.recordsFiltered").value(2));
    }

    @Test
    void hideExpiredKeepsCertificatelessEntities() throws Exception {
        mockMvc.perform(post(USERS + "?hideExpired=true").contentType(MediaType.APPLICATION_JSON)
                        .content(usersRequest(0, 10, null, null))
                        .with(csrf()))
                .andExpect(status().isOk())
                // alice, carol and dave; only bob holds an expired certificate.
                .andExpect(jsonPath("$.recordsFiltered").value(3))
                .andExpect(jsonPath("$.data[?(@.uid == 'bob')]").isEmpty());
    }

    @Test
    void statusFilterSelectsASingleState() throws Exception {
        mockMvc.perform(post(USERS + "?certificateStatus=EXPIRING_SOON").contentType(MediaType.APPLICATION_JSON)
                        .content(usersRequest(0, 10, null, null))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsFiltered").value(1))
                .andExpect(jsonPath("$.data[0].uid").value("carol"));
    }

    @Test
    void hasCertificatesFilterFindsEntitiesWithNone() throws Exception {
        mockMvc.perform(post(USERS + "?hasCertificates=false").contentType(MediaType.APPLICATION_JSON)
                        .content(usersRequest(0, 10, null, null))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsFiltered").value(1))
                .andExpect(jsonPath("$.data[0].uid").value("dave"))
                .andExpect(jsonPath("$.data[0].certificateStatus").value("NONE"));
    }

    @Test
    void expiringWithinDaysWindowsOnTheNextExpiry() throws Exception {
        mockMvc.perform(post(USERS + "?expiringWithinDays=30").contentType(MediaType.APPLICATION_JSON)
                        .content(usersRequest(0, 10, null, null))
                        .with(csrf()))
                .andExpect(status().isOk())
                // carol expires in 10 days; alice is 400 days out and bob already expired.
                .andExpect(jsonPath("$.recordsFiltered").value(1))
                .andExpect(jsonPath("$.data[0].uid").value("carol"));
    }

    @Test
    void serversFilterByPointOfContactEmail() throws Exception {
        mockMvc.perform(post(SERVERS + "?pocEmail=alice@example.gov").contentType(MediaType.APPLICATION_JSON)
                        .content(serversRequest(0, 10, null))
                        .with(csrf()))
                .andExpect(status().isOk())
                // web01 lists alice, web02 lists her in a different case; db01 does not.
                .andExpect(jsonPath("$.recordsFiltered").value(2));
    }

    @Test
    void pointOfContactMatchIgnoresCase() throws Exception {
        mockMvc.perform(post(SERVERS + "?pocEmail=ALICE@EXAMPLE.GOV").contentType(MediaType.APPLICATION_JSON)
                        .content(serversRequest(0, 10, null))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsFiltered").value(2));
    }

    @Test
    void serversCanBeFilteredByTheUsersOwnId() throws Exception {
        Long bobId = userRepository.findByDn("uid=bob,ou=people").orElseThrow().getId();

        mockMvc.perform(post(SERVERS + "?pocUserId=" + bobId).contentType(MediaType.APPLICATION_JSON)
                        .content(serversRequest(0, 10, null))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsFiltered").value(2));
    }

    @Test
    void anUnknownUserIdMatchesNoServersRatherThanAllOfThem() throws Exception {
        mockMvc.perform(post(SERVERS + "?pocUserId=999999").contentType(MediaType.APPLICATION_JSON)
                        .content(serversRequest(0, 10, null))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsFiltered").value(0))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void pointOfContactAndCertificateFiltersCombine() throws Exception {
        mockMvc.perform(post(SERVERS + "?pocEmail=alice@example.gov&expired=true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(serversRequest(0, 10, null))
                        .with(csrf()))
                .andExpect(status().isOk())
                // Of alice's two servers only web02 holds an expired certificate.
                .andExpect(jsonPath("$.recordsFiltered").value(1))
                .andExpect(jsonPath("$.data[0].commonName").value("web02"));
    }

    @Test
    void serverRowsCarryTheFlattenedContactList() throws Exception {
        mockMvc.perform(post(SERVERS).contentType(MediaType.APPLICATION_JSON)
                        .content(serversRequest(0, 10, "web02"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsFiltered").value(1))
                .andExpect(jsonPath("$.data[0].serverPocDisplay").value("alice@example.gov, bob@example.gov"));
    }

    // ---------------------------------------------------------------------------------
    // The day the last certificate runs out
    // ---------------------------------------------------------------------------------

    /**
     * Two dates, and they answer different questions. Erin's next certificate expires in
     * three days and her last in nearly three years, so "expiring within 30 days" finds her
     * and "everything gone before next year" does not.
     */
    /**
     * What both tables open on. Soonest to expire first is the only order that puts the
     * work at the top, and the reason it was not the default is where a NULL sorts: an
     * entry publishing no certificate has no expiry at all, and databases disagree - H2
     * puts nulls first ascending, PostgreSQL last. Settled in configuration rather than
     * left to whichever database is underneath, so dave is last here and would be last
     * there.
     */
    @Test
    void theDefaultOrderIsSoonestToExpireFirstWithNothingToExpireLast() throws Exception {
        mockMvc.perform(post(USERS).contentType(MediaType.APPLICATION_JSON)
                        .content(orderedBy(USER_COLUMNS, "earliestExpiry"))
                        .with(csrf()))
                .andExpect(status().isOk())
                // bob expired 5 days ago, carol goes in 10, alice in 400, dave holds none.
                .andExpect(jsonPath("$.data[0].uid").value("bob"))
                .andExpect(jsonPath("$.data[1].uid").value("carol"))
                .andExpect(jsonPath("$.data[2].uid").value("alice"))
                .andExpect(jsonPath("$.data[3].uid").value("dave"))
                .andExpect(jsonPath("$.data[3].earliestExpiry").doesNotExist());
    }

    /** The same for servers, which have their own seed and their own table. */
    @Test
    void andTheServersTableOpensTheSameWay() throws Exception {
        mockMvc.perform(post(SERVERS).contentType(MediaType.APPLICATION_JSON)
                        .content(orderedBy(SERVER_COLUMNS, "earliestExpiry"))
                        .with(csrf()))
                .andExpect(status().isOk())
                // web02 expired 2 days ago, web01 goes in 200, db01 in 500.
                .andExpect(jsonPath("$.data[0].commonName").value("web02"))
                .andExpect(jsonPath("$.data[1].commonName").value("web01"))
                .andExpect(jsonPath("$.data[2].commonName").value("db01"));
    }

    @Test
    void theLastExpiryIsADifferentDateFromTheNext() throws Exception {
        Instant now = Instant.now();
        userRepository.save(user("uid=erin,ou=people", "erin", "Erin Ellis", "erin@example.gov",
                certificate("CN=erin-a", now.plus(Duration.ofDays(3)), now),
                certificate("CN=erin-b", now.plus(Duration.ofDays(1000)), now)));

        mockMvc.perform(post(USERS + "?expiringWithinDays=30").contentType(MediaType.APPLICATION_JSON)
                        .content(usersRequest(0, 10, null, null))
                        .with(csrf()))
                .andExpect(status().isOk())
                // carol, 10 days out, and erin, 3 days out.
                .andExpect(jsonPath("$.recordsFiltered").value(2));

        mockMvc.perform(post(USERS + "?latestExpiryFrom=" + day(900) + "&latestExpiryTo=" + day(1100))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(usersRequest(0, 10, null, null))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsFiltered").value(1))
                .andExpect(jsonPath("$.data[0].uid").value("erin"))
                .andExpect(jsonPath("$.data[0].certificateCount").value(2));
    }

    /** Either end may be left open: everything gone by a date, or nothing until one. */
    @Test
    void oneEndOfTheRangeIsEnough() throws Exception {
        mockMvc.perform(post(USERS + "?latestExpiryTo=" + day(30)).contentType(MediaType.APPLICATION_JSON)
                        .content(usersRequest(0, 10, null, null))
                        .with(csrf()))
                .andExpect(status().isOk())
                // bob expired five days ago and carol runs out in ten; alice is 400 days
                // out, and dave publishes nothing, so he has no such date at all.
                .andExpect(jsonPath("$.recordsFiltered").value(2));

        mockMvc.perform(post(USERS + "?latestExpiryFrom=" + day(30)).contentType(MediaType.APPLICATION_JSON)
                        .content(usersRequest(0, 10, null, null))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsFiltered").value(1))
                .andExpect(jsonPath("$.data[0].uid").value("alice"));
    }

    /** The day named is inside the range, whatever hour of it the certificate expires at. */
    @Test
    void bothEndsIncludeTheDayTheyName() throws Exception {
        String carolsDay = day(10);

        mockMvc.perform(post(USERS + "?latestExpiryFrom=" + carolsDay + "&latestExpiryTo=" + carolsDay)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(usersRequest(0, 10, null, null))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsFiltered").value(1))
                .andExpect(jsonPath("$.data[0].uid").value("carol"));
    }

    @Test
    void serversTakeTheSameRange() throws Exception {
        mockMvc.perform(post(SERVERS + "?latestExpiryFrom=" + day(100) + "&latestExpiryTo=" + day(300))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(serversRequest(0, 10, null))
                        .with(csrf()))
                .andExpect(status().isOk())
                // web01 at 200 days; web02 has expired and db01 is 500 days out.
                .andExpect(jsonPath("$.recordsFiltered").value(1))
                .andExpect(jsonPath("$.data[0].commonName").value("web01"));
    }

    @Test
    void theRangeCombinesWithEverythingElse() throws Exception {
        mockMvc.perform(post(SERVERS + "?latestExpiryTo=" + day(600) + "&poc=bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(serversRequest(0, 10, null))
                        .with(csrf()))
                .andExpect(status().isOk())
                // bob contacts web02 and db01, both of which run out inside 600 days.
                .andExpect(jsonPath("$.recordsFiltered").value(2));
    }

    // ---------------------------------------------------------------------------------
    // Points of contact, by name
    // ---------------------------------------------------------------------------------

    /** A search box is typed into, not pasted into: part of a value has to match. */
    @Test
    void serversMatchPartOfAContactName() throws Exception {
        mockMvc.perform(post(SERVERS + "?poc=ali").contentType(MediaType.APPLICATION_JSON)
                        .content(serversRequest(0, 10, null))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsFiltered").value(2));

        mockMvc.perform(post(SERVERS + "?poc=@example.gov").contentType(MediaType.APPLICATION_JSON)
                        .content(serversRequest(0, 10, null))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsFiltered").value(3));
    }

    /**
     * The interesting half: a contact added here by picking somebody out of the directory
     * stores their address, so searching for their name has to reach through the link.
     */
    @Test
    void searchingByNameFindsAServerContactedThroughAPerson() throws Exception {
        Long carolId = userRepository.findByDn("uid=carol,ou=people").orElseThrow().getId();
        Long db01 = serverRepository.findByDn("cn=db01,ou=servers").orElseThrow().getId();
        contactService.addUser(db01, carolId, "alice");

        mockMvc.perform(post(SERVERS + "?poc=carol").contentType(MediaType.APPLICATION_JSON)
                        .content(serversRequest(0, 10, null))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsFiltered").value(1))
                .andExpect(jsonPath("$.data[0].commonName").value("db01"));
    }

    /**
     * On the people table the question is the other way round: who does this serverPOC
     * value mean? Every value the join uses is matched, which is more than the columns show.
     */
    @Test
    void peopleAreFoundByWhatAServerPocCouldCallThem() throws Exception {
        mockMvc.perform(post(USERS + "?poc=chase").contentType(MediaType.APPLICATION_JSON)
                        .content(usersRequest(0, 10, null, null))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsFiltered").value(1))
                .andExpect(jsonPath("$.data[0].uid").value("carol"));

        mockMvc.perform(post(USERS + "?poc=bob@example.gov").contentType(MediaType.APPLICATION_JSON)
                        .content(usersRequest(0, 10, null, null))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsFiltered").value(1))
                .andExpect(jsonPath("$.data[0].uid").value("bob"));
    }

    @Test
    void aContactNameNobodyAnswersToMatchesNothing() throws Exception {
        mockMvc.perform(post(USERS + "?poc=nobody").contentType(MediaType.APPLICATION_JSON)
                        .content(usersRequest(0, 10, null, null))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsFiltered").value(0));

        mockMvc.perform(post(SERVERS + "?poc=nobody").contentType(MediaType.APPLICATION_JSON)
                        .content(serversRequest(0, 10, null))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsFiltered").value(0));
    }

    /** A blank box is no filter at all, rather than a match against the empty string. */
    @Test
    void anEmptySearchNarrowsNothing() throws Exception {
        mockMvc.perform(post(USERS + "?poc=&latestExpiryFrom=").contentType(MediaType.APPLICATION_JSON)
                        .content(usersRequest(0, 10, null, null))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsFiltered").value(4));
    }

    /** The day this many days from now, as the date inputs send it. */
    private String day(int daysFromNow) {
        return LocalDate.ofInstant(Instant.now().plus(Duration.ofDays(daysFromNow)), ZoneOffset.UTC).toString();
    }

    // ---------------------------------------------------------------------------------
    // Certificates whose names are worth a second look
    // ---------------------------------------------------------------------------------

    /** What a certificate is good for, as distinct from what state it is in. */
    @Test
    void serversAreFoundByWhatTheirCertificatesAreGoodFor() throws Exception {
        Instant now = Instant.now();
        CachedCertificate wildcard = certificate("CN=*.example.gov", now.plus(Duration.ofDays(90)), now);
        wildcard.describeNames(2, List.of(CertificateRisk.WILDCARD));
        CachedCertificate sprawling = certificate("CN=batch", now.plus(Duration.ofDays(90)), now);
        sprawling.describeNames(40, List.of(CertificateRisk.MANY_NAMES, CertificateRisk.MANY_DOMAINS));

        serverRepository.save(server("cn=wild01,ou=servers", "wild01", "wild01.example.gov",
                List.of("alice@example.gov"), wildcard));
        serverRepository.save(server("cn=batch01,ou=servers", "batch01", "batch01.example.gov",
                List.of("alice@example.gov"), sprawling));

        mockMvc.perform(post(SERVERS + "?risk=any").contentType(MediaType.APPLICATION_JSON)
                        .content(serversRequest(0, 10, null))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsFiltered").value(2));

        mockMvc.perform(post(SERVERS + "?risk=WILDCARD").contentType(MediaType.APPLICATION_JSON)
                        .content(serversRequest(0, 10, null))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsFiltered").value(1))
                .andExpect(jsonPath("$.data[0].commonName").value("wild01"));

        mockMvc.perform(post(SERVERS + "?risk=MANY_DOMAINS").contentType(MediaType.APPLICATION_JSON)
                        .content(serversRequest(0, 10, null))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsFiltered").value(1))
                .andExpect(jsonPath("$.data[0].commonName").value("batch01"));
    }

    /** An entry with two flagged certificates is one row, and the count has to agree. */
    @Test
    void anEntryWithSeveralFlaggedCertificatesIsCountedOnce() throws Exception {
        Instant now = Instant.now();
        CachedCertificate first = certificate("CN=*.a.example.gov", now.plus(Duration.ofDays(90)), now);
        first.describeNames(1, List.of(CertificateRisk.WILDCARD));
        CachedCertificate second = certificate("CN=*.b.example.gov", now.plus(Duration.ofDays(120)), now);
        second.describeNames(1, List.of(CertificateRisk.WILDCARD));
        serverRepository.save(server("cn=twice01,ou=servers", "twice01", "twice01.example.gov",
                List.of("alice@example.gov"), first, second));

        mockMvc.perform(post(SERVERS + "?risk=WILDCARD").contentType(MediaType.APPLICATION_JSON)
                        .content(serversRequest(0, 10, null))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsFiltered").value(1));
    }

    @Test
    void aKindOfRiskThatDoesNotExistIsARejectedRequest() throws Exception {
        mockMvc.perform(post(SERVERS + "?risk=SOMETHING_ELSE").contentType(MediaType.APPLICATION_JSON)
                        .content(serversRequest(0, 10, null))
                        .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    /** The rows carry the flags and the honest count, which is what the page prints. */
    @Test
    void theRowsSayWhatIsWorryingAboutTheNames() throws Exception {
        Instant now = Instant.now();
        CachedCertificate wildcard = certificate("CN=*.example.gov", now.plus(Duration.ofDays(90)), now);
        wildcard.describeNames(3, List.of(CertificateRisk.WILDCARD));
        Long serverId = serverRepository.save(server("cn=wild02,ou=servers", "wild02", "wild02.example.gov",
                        List.of("alice@example.gov"), wildcard))
                .getId();

        mockMvc.perform(get("/api/v1/servers/{id}/certificates", serverId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].subjectAltNameCount").value(3))
                .andExpect(jsonPath("$[0].risks[0].name").value("WILDCARD"))
                .andExpect(jsonPath("$[0].risks[0].label").value("Wildcard"))
                .andExpect(jsonPath("$[0].risks[0].severe").value(false));
    }

    @Test
    void aTableRequestWithoutACsrfTokenIsRejected() throws Exception {
        mockMvc.perform(post(USERS).contentType(MediaType.APPLICATION_JSON)
                        .content(usersRequest(0, 10, null, null)))
                .andExpect(status().isForbidden());
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
                "SHA-256",
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

    /** The body DataTables posts when the table is sorted by a column, as both now are. */
    private String orderedBy(String[] columns, String column) {
        int index = java.util.Arrays.asList(columns).indexOf(column);
        return request(columns, 0, 10, null, null, null)
                .replace("\"order\":[]", "\"order\":[{\"column\":" + index + ",\"dir\":\"asc\"}]");
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
