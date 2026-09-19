package com.winllc.certalert.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.winllc.certalert.domain.CertificateRisk;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Reading what a certificate is good for.
 *
 * <p>A pure function, so this is the place to be exhaustive about the judgements: what
 * counts as a wildcard worth flagging, where "too many" starts, and what makes a name
 * ambiguous rather than merely long.
 */
class SubjectAltNamesTest {

    private static final int MANY = 20;
    private static final int DOMAINS = 3;

    @Test
    void ordinaryNamesAreNotWorthFlagging() {
        var assessment = assess("web01.example.gov", "www.example.gov");

        assertThat(assessment.count()).isEqualTo(2);
        assertThat(assessment.risks()).isEmpty();
        assertThat(assessment.isRisky()).isFalse();
        assertThat(assessment.flags()).isNull();
    }

    @Test
    void aWildcardIsFlaggedForWhatItCovers() {
        var assessment = assess("*.example.gov");

        assertThat(assessment.risks()).containsExactly(CertificateRisk.WILDCARD);
        assertThat(assessment.flags()).isEqualTo("WILDCARD");
    }

    /** A wildcard over a suffix everybody shares covers other people's hosts. */
    @Test
    void aWildcardHighEnoughUpIsWorse() {
        assertThat(assess("*.gov").risks())
                .containsExactlyInAnyOrder(CertificateRisk.WILDCARD, CertificateRisk.BROAD_WILDCARD);
        assertThat(assess("*.ic.gov").risks())
                .containsExactlyInAnyOrder(CertificateRisk.WILDCARD, CertificateRisk.BROAD_WILDCARD);
        assertThat(assess("*").risks())
                .containsExactlyInAnyOrder(CertificateRisk.WILDCARD, CertificateRisk.BROAD_WILDCARD);

        // One organization's own wildcard is not broad, however many hosts it covers.
        assertThat(assess("*.example.gov").risks()).containsExactly(CertificateRisk.WILDCARD);
        assertThat(assess("*.apps.example.gov").risks()).containsExactly(CertificateRisk.WILDCARD);
    }

    @Test
    void tooManyNamesIsFlaggedAtTheThreshold() {
        List<String> names = new java.util.ArrayList<>();
        for (int i = 0; i < MANY; i++) {
            names.add("host%02d.example.gov".formatted(i));
        }
        assertThat(SubjectAltNames.assess(names, MANY, DOMAINS).risks())
                .as("exactly the threshold is not over it")
                .isEmpty();

        names.add("one-more.example.gov");
        var assessment = SubjectAltNames.assess(names, MANY, DOMAINS);
        assertThat(assessment.count()).isEqualTo(MANY + 1);
        assertThat(assessment.risks()).containsExactly(CertificateRisk.MANY_NAMES);
    }

    /** Names spread across unrelated domains say only that somebody batched a renewal. */
    @Test
    void namesSpanningUnrelatedDomainsAreFlagged() {
        assertThat(assess("a.one.gov", "b.two.gov", "c.three.gov").risks())
                .as("three domains is the threshold")
                .isEmpty();
        assertThat(assess("a.one.gov", "b.two.gov", "c.three.gov", "d.four.gov").risks())
                .containsExactly(CertificateRisk.MANY_DOMAINS);

        // Subdomains of one domain are one domain, however many there are.
        assertThat(assess("a.example.gov", "b.example.gov", "c.example.gov", "d.example.gov", "e.example.gov")
                        .risks())
                .isEmpty();
    }

    /** A name below a shared two-part suffix is still one organization. */
    @Test
    void aTwoPartSuffixIsNotADomainOfItsOwn() {
        assertThat(assess("a.alpha.ic.gov", "b.alpha.ic.gov", "c.beta.ic.gov").risks()).isEmpty();
        assertThat(assess("a.alpha.ic.gov", "b.beta.ic.gov", "c.gamma.ic.gov", "d.delta.ic.gov").risks())
                .containsExactly(CertificateRisk.MANY_DOMAINS);
    }

    @Test
    void aNameWithNoDomainIsAmbiguous() {
        assertThat(assess("web01", "web01.example.gov").risks()).containsExactly(CertificateRisk.BARE_HOSTNAME);
        assertThat(assess("localhost").risks()).containsExactly(CertificateRisk.BARE_HOSTNAME);
    }

    @Test
    void severalThingsCanBeWrongAtOnce() {
        var assessment = assess("*.gov", "localhost");

        assertThat(assessment.risks()).containsExactlyInAnyOrder(
                CertificateRisk.WILDCARD, CertificateRisk.BROAD_WILDCARD, CertificateRisk.BARE_HOSTNAME);
        assertThat(assessment.flags()).isEqualTo("BARE_HOSTNAME,BROAD_WILDCARD,WILDCARD");
    }

    @Test
    void caseAndRepetitionAndBlanksAreNotNames() {
        var assessment = assess("Web01.Example.Gov", "web01.example.gov", "  ", null);

        assertThat(assessment.count()).as("the same name twice is one name").isEqualTo(1);
        assertThat(assessment.risks()).isEmpty();
    }

    @Test
    void aCertificateWithNoNamesAtAllIsNotRisky() {
        assertThat(SubjectAltNames.assess(null, MANY, DOMAINS)).isEqualTo(SubjectAltNames.Assessment.NONE);
        assertThat(SubjectAltNames.assess(List.of(), MANY, DOMAINS).risks()).isEmpty();
    }

    /** The stored form is what the pages read back; an ellipsis is truncation, not a name. */
    @Test
    void theStoredListReadsBackAsNames() {
        assertThat(SubjectAltNames.parse("a.example.gov, b.example.gov"))
                .containsExactly("a.example.gov", "b.example.gov");
        assertThat(SubjectAltNames.parse("a.example.gov, ...")).containsExactly("a.example.gov");
        assertThat(SubjectAltNames.parse(null)).isEmpty();
    }

    private SubjectAltNames.Assessment assess(String... names) {
        return SubjectAltNames.assess(java.util.Arrays.asList(names), MANY, DOMAINS);
    }
}
