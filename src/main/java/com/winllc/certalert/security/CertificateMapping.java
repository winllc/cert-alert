package com.winllc.certalert.security;

import com.winllc.certalert.domain.DirectoryUser;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;
import javax.naming.directory.SearchControls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ldap.core.ContextMapper;
import org.springframework.ldap.core.ContextSource;
import org.springframework.ldap.core.DirContextAdapter;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.stereotype.Component;

/**
 * Finds the person a certificate belongs to with {@code ENS_X509_USER_FILTER}: an LDAP filter
 * with the certificate's {@code {upn}}, {@code {edipi}}, {@code {email}} and {@code {uuid}}
 * filled in, searched for under {@code cert-alert.security.ldap.user-search-base}.
 *
 * <p>The weaker of the two ways in, used only for a certificate the directory does not
 * publish and only when {@code cert-alert.security.x509.require-known-certificate} is off:
 * it trusts what the certificate says about its holder, where the fingerprint match trusts
 * the certificate itself. So it is strict about everything else - exactly one entry, and one
 * the cache holds as an IC Person, or nobody is signed in; a filter naming a value the
 * certificate does not carry is not searched with at all; and every value is escaped.
 */
@Component
public class CertificateMapping {

    private static final Logger log = LoggerFactory.getLogger(CertificateMapping.class);

    private final EnsX509Properties settings;
    private final SecurityProperties security;
    private final DirectoryPrincipalResolver resolver;
    private final LdapTemplate ldap;

    public CertificateMapping(
            EnsX509Properties settings,
            SecurityProperties security,
            DirectoryPrincipalResolver resolver,
            ContextSource contextSource) {
        this.settings = settings;
        this.security = security;
        this.resolver = resolver;
        this.ldap = new LdapTemplate(contextSource);
        // A second match is the answer "more than one", not an error.
        this.ldap.setIgnoreSizeLimitExceededException(true);
    }

    public boolean isConfigured() {
        return settings.getUserFilter() != null;
    }

    /** The one cached person the filter finds for this certificate, if it finds exactly one. */
    public Optional<DirectoryUser> find(X509Certificate certificate) {
        String template = settings.getUserFilter();
        String subject = certificate.getSubjectX500Principal().getName();
        CertificateIdentity identity = CertificateIdentity.of(certificate);
        Optional<String> filter = identity.fill(template);
        if (filter.isEmpty()) {
            log.warn("Not mapping client certificate '{}': ENS_X509_USER_FILTER uses {}, which it does not carry",
                    subject, identity.missing(template));
            return Optional.empty();
        }
        SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        // Two is enough to know there is more than one.
        controls.setCountLimit(2);
        controls.setTimeLimit(10_000);
        controls.setReturningAttributes(new String[0]);
        ContextMapper<String> nameOf = context -> ((DirContextAdapter) context).getNameInNamespace();
        List<String> found = ldap.search(security.getLdap().getUserSearchBase(), filter.get(), controls, nameOf);
        if (found.size() != 1) {
            log.warn("Not mapping client certificate '{}': {} found {} directory entries, not one",
                    subject, filter.get(), found.size());
            return Optional.empty();
        }
        Optional<DirectoryUser> cached = resolver.findByDn(found.getFirst());
        if (cached.isEmpty()) {
            log.warn("Not mapping client certificate '{}': {} is not a person this application has synced",
                    subject, found.getFirst());
        }
        return cached;
    }
}
