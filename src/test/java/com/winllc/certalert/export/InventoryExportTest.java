package com.winllc.certalert.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.winllc.certalert.audit.AuditStore;
import com.winllc.certalert.audit.StoredEvent;
import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.CertificateStatus;
import com.winllc.certalert.domain.DirectoryServer;
import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.domain.OutboundEmail;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.repository.OutboundEmailRepository;
import com.winllc.certalert.repository.ServerContactRepository;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The certificate inventory: filtered the same way for the list, the export and a saved view;
 * exported as CSV and Excel with formulas neutralised and the export audited; saved views kept
 * per person; and one contact added to many servers. Scheduled reports: ScheduledReportTest.
 */
@SpringBootTest
@ActiveProfiles("test")
class InventoryExportTest {

    /** A subject a hostile CA - or a typo - could put in a certificate, which a spreadsheet would run. */
    private static final String FORMULA = "=HYPERLINK(\"http://evil.example/\",\"click\")";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private DirectoryUserRepository users;

    @Autowired
    private DirectoryServerRepository servers;

    @Autowired
    private ServerContactRepository contacts;

    @Autowired
    private OutboundEmailRepository outbox;

    @Autowired
    private ScheduledReports reports;

    @Autowired
    private ExportProperties properties;

    @Autowired
    private AuditStore audit;

    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mockMvc;
    private Long weakServer;
    private Long strongServer;

    @BeforeEach
    void seed() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        contacts.deleteAll();
        servers.deleteAll();
        users.deleteAll();
        Instant now = Instant.now();
        users.save(user("uid=erin,ou=people", "erin", "Erin Exporter",
                certificate("CN=erin", "CN=Federal Bridge CA", now.plus(Duration.ofDays(400)), "SHA-256", "EC", 256, now)));
        weakServer = servers.save(server("cn=legacy01,ou=servers", "legacy01",
                certificate(FORMULA, "CN=Old Internal CA", now.plus(Duration.ofDays(12)), "SHA-1", "RSA", 1024, now))).getId();
        strongServer = servers.save(server("cn=web03,ou=servers", "web03",
                certificate("CN=web03.example.gov", "CN=DigiCert TLS RSA SHA256 2020 CA1", now.minus(Duration.ofDays(3)),
                        "SHA-256", "RSA", 2048, now))).getId();
    }

    @AfterEach
    void restore() {
        properties.setMaxRows(200_000);
        properties.setReportRecipientDomains(List.of());
        jdbc.update("DELETE FROM saved_view");
        jdbc.update("DELETE FROM scheduled_report");
    }

    private static RequestPostProcessor reader(String name) {
        return SecurityMockMvcRequestPostProcessors.user(name).roles("USER");
    }

    private static RequestPostProcessor admin() {
        return SecurityMockMvcRequestPostProcessors.user("root").roles("USER", "ADMIN");
    }

    @Test
    void theCryptoFiltersNarrowTheList() throws Exception {
        mockMvc.perform(get("/api/v1/certificates").param("hashAlgorithm", "sha-1").with(reader("r")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].ownerName").value("legacy01"))
                .andExpect(jsonPath("$.content[0].keySize").value(1024));
        mockMvc.perform(get("/api/v1/certificates").param("keyAlgorithm", "RSA").param("keySizeMax", "2047").with(reader("r")))
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get("/api/v1/certificates").param("issuer", "digicert").with(reader("r")))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].status").value("EXPIRED"));
        mockMvc.perform(get("/api/v1/certificates").param("ownerType", "USER").with(reader("r")))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].ownerName").value("Erin Exporter"));
        mockMvc.perform(get("/api/v1/certificates").param("expiresWithinDays", "30").with(reader("r")))
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get("/api/v1/certificates").param("status", "EXPIRED", "EXPIRING_SOON")
                        .param("sort", "notAfter,desc").with(reader("r")))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].ownerName").value("legacy01"));
        // A wildcard typed into the search is a character, not a wildcard.
        mockMvc.perform(get("/api/v1/certificates").param("q", "%").with(reader("r")))
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/v1/certificates").param("sort", "subjectDn;drop table").with(reader("r")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/summary/expiry").with(reader("r")))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.byStatus.EXPIRED").value(1))
                .andExpect(jsonPath("$.expiringWithinDays.30").value(1))
                .andExpect(jsonPath("$.byOwnerType.SERVER").value(2));
    }

    @Test
    void aCsvExportHonoursTheFiltersNeutralisesFormulasAndIsAudited() throws Exception {
        long head = audit.headSeq();
        byte[] body = mockMvc.perform(get("/api/v1/certificates/export").param("format", "csv")
                        .param("ownerType", "SERVER").param("sort", "notAfter,desc").with(reader("r")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("text/csv")))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.matchesPattern(
                        "attachment; filename=\"certalert-certificates-\\d{8}-\\d{6}\\.csv\"")))
                .andReturn().getResponse().getContentAsByteArray();
        String csv = new String(body, StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿Owner type,Owner,");
        List<String> lines = csv.lines().toList();
        assertThat(lines).hasSize(3);
        assertThat(lines.get(1)).startsWith("SERVER,legacy01,").contains("\"'=HYPERLINK(\"\"http://evil.example/\"\",\"\"click\"\")\"");
        assertThat(lines.get(2)).contains("web03", "EXPIRED", ",-");
        // Negative days are a number, not text, so they are left alone.
        assertThat(lines.get(2)).doesNotContain("'-");

        assertThat(audit.after(head, 100)).filteredOn(event -> event.action().equals("inventory.exported"))
                .singleElement().satisfies(event -> assertThat(event.line())
                        .contains("\"format\":\"csv\"", "\"count\":2", "ownerType=SERVER"));
    }

    @Test
    void anExcelExportIsAWorkbookWithItsFiltersAndNoFormulas() throws Exception {
        byte[] body = mockMvc.perform(get("/api/v1/certificates/export").param("format", "xlsx")
                        .param("hashAlgorithm", "SHA-1").with(reader("r")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", CertificateExport.Format.XLSX.contentType()))
                .andReturn().getResponse().getContentAsByteArray();
        Map<String, String> parts = unzip(body);
        assertThat(parts).containsKeys("[Content_Types].xml", "xl/workbook.xml", "xl/styles.xml",
                "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml");
        DocumentBuilderFactory xml = DocumentBuilderFactory.newInstance();
        xml.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        for (String part : parts.values()) {
            xml.newDocumentBuilder().parse(new ByteArrayInputStream(part.getBytes(StandardCharsets.UTF_8)));
        }
        String sheet = parts.get("xl/worksheets/sheet1.xml");
        assertThat(sheet).contains("<pane ySplit=\"1\"", "&apos;=HYPERLINK".replace("&apos;", "'"), "legacy01", "s=\"1\"")
                .doesNotContain("<f>");
        assertThat(sheet.split("<row ")).hasSize(3);
        assertThat(parts.get("xl/worksheets/sheet2.xml")).contains("Filters", "hashAlgorithm=[SHA-1]");
    }

    @Test
    void anExportStopsAtTheLimitAndSaysSo() throws Exception {
        properties.setMaxRows(1);
        String csv = mockMvc.perform(get("/api/v1/certificates/export").with(reader("r")))
                .andExpect(header().string("X-CertAlert-Truncated", "true"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv.lines()).hasSize(2);
    }

    @Test
    void savedViewsAreEachPersonsOwn() throws Exception {
        String saved = mockMvc.perform(post("/api/v1/saved-views").with(reader("alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"page\":\"certificates\",\"name\":\"Weak crypto\",\"query\":\"hashAlgorithm=SHA-1&keySizeMax=2047&status=EXPIRING_SOON\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Weak crypto"))
                .andReturn().getResponse().getContentAsString();
        String id = saved.replaceAll(".*\"id\":(\\d+).*", "$1");
        // Saving under the same name replaces it.
        mockMvc.perform(post("/api/v1/saved-views").with(reader("alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"page\":\"certificates\",\"name\":\"Weak crypto\",\"query\":\"hashAlgorithm=SHA-1\"}"))
                .andExpect(jsonPath("$.id").value(Long.valueOf(id)));
        mockMvc.perform(get("/api/v1/saved-views").with(reader("alice")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].query").value("hashAlgorithm=SHA-1"));

        // Bob sees none of Alice's, and cannot delete hers.
        mockMvc.perform(get("/api/v1/saved-views").with(reader("bob"))).andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(delete("/api/v1/saved-views/" + id).with(reader("bob")).with(csrf())).andExpect(status().isNotFound());

        // Only filters are kept.
        for (String query : List.of("javascript=alert(1)", "status=NOPE", "expiresFrom=yesterday", "sort=password,asc")) {
            mockMvc.perform(post("/api/v1/saved-views").with(reader("alice")).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"page\":\"certificates\",\"name\":\"x\",\"query\":\"" + query + "\"}"))
                    .andExpect(status().isBadRequest());
        }
        // An auditor, who changes nothing, still keeps views of their own.
        mockMvc.perform(post("/api/v1/saved-views").with(SecurityMockMvcRequestPostProcessors.user("aud").roles("USER", "AUDITOR"))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Mine\",\"query\":\"\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(delete("/api/v1/saved-views/" + id).with(reader("alice")).with(csrf())).andExpect(status().isNoContent());
    }

    @Test
    void oneContactIsAddedToManyServersWhereThePersonMayManageThem() throws Exception {
        mockMvc.perform(post("/api/v1/servers/contacts/bulk").with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serverIds\":[" + weakServer + "," + strongServer + ",999999],\"email\":\"pki-team@example.gov\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(2))
                .andExpect(jsonPath("$.notFound[0]").value(999999));
        // Again: already there.
        mockMvc.perform(post("/api/v1/servers/contacts/bulk").with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serverIds\":[" + weakServer + "],\"email\":\"pki-team@example.gov\"}"))
                .andExpect(jsonPath("$.alreadyContact[0]").value(weakServer));
        // A reader with nothing to do with these servers changes none of them.
        mockMvc.perform(post("/api/v1/servers/contacts/bulk").with(reader("stranger")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serverIds\":[" + weakServer + "],\"email\":\"me@example.gov\"}"))
                .andExpect(jsonPath("$.added").value(0))
                .andExpect(jsonPath("$.refused[0]").value(weakServer));
    }

    @Test
    void theCertificatesPageRendersForAnyoneSignedIn() throws Exception {
        String page = mockMvc.perform(get("/certificates").with(reader("r")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(page).contains("id=\"certificate-filters\"", "id=\"export-xlsx\"", "id=\"saved-views\"")
                // Scheduled reports are for administrators and auditors.
                .doesNotContain("id=\"reports-card\"");
        assertThat(mockMvc.perform(get("/certificates").with(admin())).andReturn().getResponse().getContentAsString())
                .contains("id=\"report-form\"");
    }

    private static Map<String, String> unzip(byte[] bytes) throws Exception {
        Map<String, String> parts = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                parts.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return parts;
    }

    private static CachedCertificate certificate(
            String subject, String issuer, Instant notAfter, String hash, String key, int size, Instant now) {
        CachedCertificate certificate = new CachedCertificate(
                String.format("%064x", Math.abs((long) subject.hashCode())), "0A", subject, issuer,
                now.minus(Duration.ofDays(365)), notAfter, hash.replace("-", "") + "with" + key, hash, key, size, null, now);
        CertificateStatus status = !notAfter.isAfter(now) ? CertificateStatus.EXPIRED
                : Duration.between(now, notAfter).toDays() < 30 ? CertificateStatus.EXPIRING_SOON : CertificateStatus.VALID;
        certificate.updateStatus(status, now);
        return certificate;
    }

    private static DirectoryUser user(String dn, String uid, String name, CachedCertificate... certificates) {
        DirectoryUser user = new DirectoryUser(dn);
        user.setUid(uid);
        user.setDisplayName(name);
        user.setCommonName(name);
        user.setIcEmail(uid + "@example.gov");
        for (CachedCertificate certificate : certificates) {
            user.addCertificate(certificate);
        }
        user.refreshIdentifiers(uid + "@example.gov");
        user.markSynced(Instant.now());
        user.refreshCertificateSummary();
        return user;
    }

    private static DirectoryServer server(String dn, String cn, CachedCertificate... certificates) {
        DirectoryServer server = new DirectoryServer(dn);
        server.setCommonName(cn);
        server.setServerUrl("https://" + cn + ".example.gov");
        server.setServerPocs(List.of("owner@example.gov"));
        for (CachedCertificate certificate : certificates) {
            server.addCertificate(certificate);
        }
        server.markSynced(Instant.now());
        server.refreshCertificateSummary();
        return server;
    }
}
