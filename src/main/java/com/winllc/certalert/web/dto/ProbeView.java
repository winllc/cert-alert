package com.winllc.certalert.web.dto;

import com.winllc.certalert.domain.ProbeFinding;
import com.winllc.certalert.service.ServerProbeService;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * What a probe found, as the page reads it.
 *
 * <p>The findings carry their words rather than their names: the page should not be the
 * place that decides what {@code SERVING_SUPERSEDED} means in English, and an operator
 * reading the JSON should not have to guess either.
 */
public record ProbeView(
        String host,
        int port,
        boolean reachable,
        String error,
        String protocol,
        String cipherSuite,
        Long elapsedMillis,
        ServerProbeService.Presented presented,
        List<String> chain,
        ServerProbeService.Expected expected,
        List<Finding> findings,
        boolean problem,
        Instant checkedAt) {

    /** One thing found, with the words to print and how loudly. */
    public record Finding(String name, String label, String severity, boolean problem) {

        static Finding of(ProbeFinding finding) {
            return new Finding(
                    finding.name(), finding.label(), finding.severity().name(), finding.isProblem());
        }
    }

    public static ProbeView from(ServerProbeService.Result result) {
        return new ProbeView(
                result.host(),
                result.port(),
                result.reachable(),
                result.error(),
                result.protocol(),
                result.cipherSuite(),
                result.elapsedMillis(),
                result.presented(),
                result.chain(),
                result.expected(),
                result.findings().stream()
                        // The answer first, then whatever else was noticed, worst of those
                        // first: the card is for the question, not for the remarks.
                        .sorted(Comparator.comparing((ProbeFinding finding) -> finding.answersTheQuestion() ? 0 : 1)
                                .thenComparing(finding -> -finding.severity().ordinal()))
                        .map(Finding::of)
                        .toList(),
                result.hasProblem(),
                result.checkedAt());
    }
}
