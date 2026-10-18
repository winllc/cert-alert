package com.winllc.certalert.security;

import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.repository.DirectoryUserRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Says at startup which {@code cert-alert.security.admin-identifiers} entries grant nothing,
 * and what to list instead.
 *
 * <p>Administrator rights are matched against the DN of a person's entry and their uid
 * only (see {@link DirectoryPrincipalResolver#roleIdentifiersOf}). Earlier releases also
 * matched the addresses the directory publishes, and a configuration written then may list
 * administrators by address. Such an entry is not an error - the application starts, and
 * everybody else it lists is still an administrator - but it is ignored, and the person it
 * meant signs in as a reader until it is replaced. That is said here, once, with the uid
 * and DN the directory cache holds for the address where it holds one.
 */
@Component
public class AdminIdentifierCheck {

    private static final Logger log = LoggerFactory.getLogger(AdminIdentifierCheck.class);

    private final DirectoryPrincipalResolver resolver;
    private final DirectoryUserRepository users;
    private final SecurityProperties properties;

    public AdminIdentifierCheck(
            DirectoryPrincipalResolver resolver, DirectoryUserRepository users, SecurityProperties properties) {
        this.resolver = resolver;
        this.users = users;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional(readOnly = true)
    public void warnAboutAddresses() {
        List<String> ignored = resolver.ignoredAdminIdentifiers();
        for (String address : ignored) {
            log.warn("cert-alert.security.admin-identifiers lists '{}', an e-mail address, and it is ignored: "
                            + "administrator rights match only a uid or the DN of a directory entry, because a "
                            + "directory may let people change their own mail. {}",
                    address, insteadOf(address));
        }
        long granting = properties.getAdminIdentifiers().stream()
                .filter(value -> value != null && !value.isBlank())
                .count() - ignored.size();
        if (!ignored.isEmpty() && granting == 0) {
            log.warn("cert-alert.security.admin-identifiers (CERT_ALERT_SECURITY_ADMIN_IDENTIFIERS) names no uid "
                    + "or DN, so nobody signs in as an administrator until it does");
        }
    }

    /** What to list in its place: the cached holder's uid and DN, or how to find them. */
    String insteadOf(String address) {
        List<DirectoryUser> holders;
        try {
            String value = DirectoryPrincipalResolver.normalise(address);
            holders = users.findByIdentifier(value).stream()
                    .filter(user -> resolver.directoryIdentifiersOf(user).contains(value))
                    .toList();
        } catch (RuntimeException e) {
            log.debug("Could not look {} up in the directory cache: {}", address, e.toString());
            holders = List.of();
        }
        if (holders.size() == 1) {
            DirectoryUser holder = holders.getFirst();
            return holder.getUid() != null
                    ? "The directory cache has it as the address of uid '%s' (%s): list '%s', or that DN, instead."
                            .formatted(holder.getUid(), holder.getDn(), holder.getUid())
                    : "The directory cache has it as the address of %s: list that DN instead."
                            .formatted(holder.getDn());
        }
        return "List that administrator's uid, or the DN of their entry, instead.";
    }
}
