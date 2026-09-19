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
        long projects) {

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
