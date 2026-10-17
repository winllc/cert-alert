package com.winllc.certalert.revocation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Where a certificate may send this application for its revocation data, and where it may
 * not. Addresses only: nothing here opens a connection.
 */
class FetchPolicyTest {

    private final FetchPolicy open = FetchPolicy.of(List.of());

    @Test
    void cloudMetadataIsNeverFetchedFrom() {
        assertRefused(open, "http://169.254.169.254/latest/meta-data/");
        assertRefused(open, "http://[fd00:ec2::254]/latest/meta-data/");
        assertRefused(open, "http://100.100.100.200/latest/meta-data/");
        // Not even when somebody names it: an allowlist is for picking hosts, not for
        // reopening the one address no CRL lives at.
        assertRefused(FetchPolicy.of(List.of("169.254.0.0/16", "169.254.169.254")), "http://169.254.169.254/");
    }

    @Test
    void linkLocalAndUnspecifiedAddressesAreNeverFetchedFrom() {
        assertRefused(open, "http://169.254.10.20/ca.crl");
        assertRefused(open, "http://[fe80::1]/ca.crl");
        assertRefused(open, "http://0.0.0.0:8080/ca.crl");
        assertRefused(open, "http://[::]/ca.crl");
        // Metadata reached through NAT64's prefix is still metadata.
        assertRefused(open, "http://[64:ff9b::a9fe:a9fe]/");
    }

    @Test
    void loopbackIsOnlyFetchedFromWhenNamed() {
        assertRefused(open, "http://127.0.0.1:9090/actuator/prometheus");
        assertRefused(open, "http://localhost/ca.crl");
        assertRefused(open, "http://[::1]/ca.crl");

        assertAllowed(FetchPolicy.of(List.of("localhost")), "http://localhost:8080/ca.crl");
        assertAllowed(FetchPolicy.of(List.of("127.0.0.0/8")), "http://127.0.0.1/ca.crl");
    }

    @Test
    void aConfiguredResponderIsTrustedWhereverItIs() {
        // A local relay somebody configured is their decision, not a certificate's.
        FetchPolicy policy = FetchPolicy.of(List.of("crl.example.gov"), "http://127.0.0.1:8888/ocsp");

        assertAllowed(policy, "http://127.0.0.1:8888/ocsp");
        assertRefused(policy, "http://127.0.0.2/ocsp");
    }

    @Test
    void anAllowlistKeepsFetchesToTheHostsItNames() {
        FetchPolicy policy = FetchPolicy.of(List.of("10.20.0.0/16", "*.pki.example", "127.0.0.1"));

        assertAllowed(policy, "http://10.20.1.2/ca.crl");
        assertRefused(policy, "http://10.21.1.2/ca.crl");
        assertAllowed(policy, "http://127.0.0.1/ca.crl");

        // A name under the domain, but not the domain itself or a lookalike - refused before
        // anything is looked up.
        FetchPolicy names = FetchPolicy.of(List.of("*.pki.example"));
        assertRefused(names, "http://pki.example/ca.crl");
        assertRefused(names, "http://evilpki.example/ca.crl");
        assertThatThrownBy(() -> names.check(URI.create("http://crl.pki.example/ca.crl")))
                // Allowed: what stops it here is that the name does not resolve.
                .isNotInstanceOf(FetchPolicy.RefusedDestinationException.class);
    }

    @Test
    void privateAddressesAreOrdinaryDistributionPoints() {
        // A PKI inside an enclave publishes on the enclave's addresses.
        assertAllowed(open, "http://10.1.2.3/ca.crl");
        assertAllowed(open, "http://192.168.0.10/ca.crl");
    }

    @Test
    void onlyWebAndDirectorySchemesAreFetched() {
        assertRefused(open, "file:///etc/passwd");
        assertRefused(open, "ftp://10.1.2.3/ca.crl");
        assertRefused(open, "jar:http://10.1.2.3/x.jar!/ca.crl");
        assertAllowed(open, "ldap://10.1.2.3/cn=CA,dc=example?certificateRevocationList");
    }

    @Test
    void aRedirectMayOnlyLeadToTheWeb() {
        assertThatThrownBy(() -> open.checkRedirect(URI.create("ldap://10.1.2.3/cn=CA")))
                .isInstanceOf(FetchPolicy.RefusedDestinationException.class);
        assertThatThrownBy(() -> open.checkRedirect(URI.create("file:///etc/shadow")))
                .isInstanceOf(FetchPolicy.RefusedDestinationException.class);
        assertThatCode(() -> open.checkRedirect(URI.create("https://10.1.2.3/ca.crl"))).doesNotThrowAnyException();
    }

    private static void assertRefused(FetchPolicy policy, String url) {
        assertThatThrownBy(() -> policy.check(URI.create(url)))
                .as(url)
                .isInstanceOf(FetchPolicy.RefusedDestinationException.class);
    }

    private static void assertAllowed(FetchPolicy policy, String url) {
        assertThatCode(() -> policy.check(URI.create(url))).as(url).doesNotThrowAnyException();
    }
}
