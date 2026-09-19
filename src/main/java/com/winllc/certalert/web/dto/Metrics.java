package com.winllc.certalert.web.dto;

import java.util.List;
import java.util.Map;

/**
 * Everything the metrics page shows, in one document.
 *
 * <p>One request rather than eight: they are all counts over the same tables, they are all
 * wanted at once, and a page that arrives in eight pieces arrives eight times.
 *
 * @param certificates how many there are, by the state the cache holds them in
 * @param entries how many people and servers, and how many publish nothing
 * @param keys what the directory is signing with
 * @param hashes which digests are in use
 * @param signatures the same as the provider names them, for anything the digest hides
 * @param expiry what falls due, in windows
 * @param months issued against expiring, by month
 * @param notifications how many people were told, and how many deliveries were attempted
 * @param risks how many certificates carry each kind of risky name, and how many carry any
 * @param issuance what has been issued lately, for how long, and by whom
 * @param revocation what the issuing authorities have said, and how much has not been asked
 * @param attributes the directory broken down by what it says about itself
 * @param projects how many projects there are
 */
public record Metrics(
        Map<String, Long> certificates,
        Map<String, Long> entries,
        List<KeyUsage> keys,
        List<AlgorithmUsage> hashes,
        List<AlgorithmUsage> signatures,
        Map<String, Long> expiry,
        List<MonthPoint> months,
        Map<String, Long> notifications,
        Map<String, Long> risks,
        Issuance issuance,
        Revocation revocation,
        Attributes attributes,
        long projects) {

    /**
     * What has been issued, rather than what is expiring.
     *
     * <p>The two are different questions. Expiry says what falls due; issuance says what
     * the estate is doing - a renewal programme that has started, a policy change from
     * three-year certificates to ninety-day ones, an authority nobody knew was issuing.
     *
     * @param averageValidityDays over what was issued in the last year, or null where
     *     nothing was
     * @param issuers who issued what, the busiest first
     */
    public record Issuance(
            long last30Days,
            long last90Days,
            long last365Days,
            Long averageValidityDays,
            List<NameCount> issuers) {}

    /**
     * What the authorities have said.
     *
     * @param byStatus how many certificates are in each revocation state
     * @param reasons the reasons given, where they were given
     * @param oldestCheck when the least recently checked certificate was checked, which is
     *     how stale the worst of these answers is
     */
    public record Revocation(
            Map<String, Long> byStatus,
            long revokedLast30Days,
            long revokedLast365Days,
            List<NameCount> reasons,
            java.time.Instant oldestCheck,
            java.time.Instant newestCheck) {}

    /**
     * The directory grouped by what it says about itself.
     *
     * <p>Counted over entries rather than certificates, because "which office has the most
     * entries" is the question that says where the work is; and separately for people and
     * for servers, because an office with four hundred people and two servers is a
     * different thing from one with four hundred servers.
     */
    public record Attributes(
            List<NameCount> userDutyOrganizations,
            List<NameCount> userDutySubOrganizations,
            List<NameCount> userEmployeeTypes,
            List<NameCount> serverDutyOrganizations,
            List<NameCount> serverDutySubOrganizations,
            List<NameCount> serverEmployeeTypes) {}

    /**
     * One value and how many carry it.
     *
     * @param name blank where the directory publishes nothing, which the page reads out as
     *     "not stated" rather than hiding - an attribute nobody fills in is a finding
     */
    public record NameCount(String name, long count) {}

    /** @param label how it reads: "RSA 2048" */
    public record KeyUsage(String algorithm, Integer size, String label, long count) {}

    /** @param algorithm null where nothing could be determined, which reads as "unknown" */
    public record AlgorithmUsage(String algorithm, long count) {}

    /**
     * @param month the first day, as {@code 2026-01}
     * @param issued certificates whose validity began that month
     * @param expiring certificates whose validity ends that month
     */
    public record MonthPoint(String month, long issued, long expiring) {}
}
