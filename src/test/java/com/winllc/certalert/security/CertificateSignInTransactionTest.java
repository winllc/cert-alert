package com.winllc.certalert.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.winllc.certalert.repository.CachedCertificateRepository;
import com.winllc.certalert.support.TestCa;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * A certificate's revocation check can wait on the network; the database lookup that
 * follows it is what needs a transaction. The check runs before one is begun, so a slow
 * responder does not hold a database connection per signing-in request.
 */
class CertificateSignInTransactionTest {

    @Test
    void theRevocationCheckRunsOutsideTheTransactionAndTheLookupInsideIt() {
        X509Certificate certificate = new TestCa("Example Transaction CA")
                .issue("person", Instant.now().minus(Duration.ofDays(1)), Instant.now().plus(Duration.ofDays(30)), null, null)
                .certificate();
        List<String> seen = new ArrayList<>();

        ClientCertificateChecks checks = mock(ClientCertificateChecks.class);
        doAnswer(invocation -> {
            seen.add("check in a transaction: " + TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(checks).check(any(), anyList());
        CachedCertificateRepository certificates = mock(CachedCertificateRepository.class);
        when(certificates.findByFingerprint(anyString())).thenAnswer(invocation -> {
            seen.add("lookup in a transaction: " + TransactionSynchronizationManager.isActualTransactionActive());
            return List.of();
        });
        SecurityProperties properties = new SecurityProperties();
        properties.getX509().setRequireKnownCertificate(true);

        X509DirectoryUserDetailsService service = new X509DirectoryUserDetailsService(
                certificates,
                mock(DirectoryPrincipalResolver.class),
                properties,
                checks,
                mock(CertificateMapping.class),
                Clock.systemUTC(),
                new RecordingTransactions());

        assertThatThrownBy(() -> service.loadUserDetails(new PreAuthenticatedAuthenticationToken("person", certificate)))
                .isInstanceOf(UsernameNotFoundException.class);
        assertThat(seen).containsExactly("check in a transaction: false", "lookup in a transaction: true");
    }

    /** Begins and ends transactions that hold nothing, so that one being active can be seen. */
    private static final class RecordingTransactions extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // nothing to begin
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            // nothing to commit
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // nothing to roll back
        }
    }
}
