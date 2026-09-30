package com.winllc.certalert.demo;

import java.util.List;
import javax.naming.directory.Attributes;
import javax.naming.directory.SearchControls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.ldap.core.AttributesMapper;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.BaseLdapPathContextSource;
import org.springframework.stereotype.Component;

/**
 * The accounts a demo offers, read out of the directory that holds them.
 *
 * <p>Out of the directory rather than out of a list here, because that is what they are: a
 * visitor signs in by binding as one of them, so the directory decides whether an account
 * exists at all. A constant in the code saying otherwise is a second source of truth that
 * goes stale the moment somebody edits the seed - and a sign-in page printing a username
 * the directory has never heard of is worse than printing nothing.
 *
 * <p>They are found by the attributes the seed gives them and nothing else. A demo pointed
 * at a directory that is already running - {@code generate-directory: false} - finds no
 * entry carrying them and offers no accounts, which is the right answer rather than a
 * failure: those people are not in that directory, and inviting somebody to sign in as
 * them would be inviting them to fail.
 *
 * <p>Read once and kept. The seed does not change under a running demo, and a directory
 * search per rendering of the sign-in page would be a search per visitor.
 *
 * @see #LDIF the seed itself, which is where the accounts are written
 */
@Component
@Conditional(DemoMode.On.class)
public class DemoAccounts {

    private static final Logger log = LoggerFactory.getLogger(DemoAccounts.class);

    /** The seed the demo loads into its own directory. */
    public static final String LDIF = "demo-accounts.ldif";

    /** What to call the account. Its presence is what marks an entry as one of these. */
    public static final String ROLE = "demoRole";

    /** One line on what this role gets that the others do not. */
    public static final String SUMMARY = "demoSummary";

    /** Where it sits in the list, most to least. */
    public static final String ORDER = "demoOrder";

    /** Everything the sign-in page prints about one account. */
    public record Account(String role, String uid, String displayName, String summary, int order) {}

    private final LdapTemplate template;
    private final String peopleBase;

    /** Read on first use rather than at startup, so nothing depends on bean ordering. */
    private volatile List<Account> accounts;

    public DemoAccounts(BaseLdapPathContextSource contextSource, com.winllc.certalert.ldap.LdapProperties ldap) {
        this.template = new LdapTemplate(contextSource);
        this.peopleBase = ldap.getUser().getSearchBase();
    }

    /** In the order the sign-in page lists them, or empty where the directory holds none. */
    public List<Account> all() {
        List<Account> known = accounts;
        if (known == null) {
            synchronized (this) {
                if (accounts == null) {
                    accounts = read();
                }
                known = accounts;
            }
        }
        return known;
    }

    private List<Account> read() {
        try {
            SearchControls controls = new SearchControls();
            controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
            controls.setReturningAttributes(new String[] {"uid", "displayName", "cn", ROLE, SUMMARY, ORDER});

            List<Account> found = template.search(
                    peopleBase, "(%s=*)".formatted(ROLE), controls, (AttributesMapper<Account>) this::toAccount);
            List<Account> ordered = found.stream()
                    .filter(account -> account.uid() != null && account.role() != null)
                    .sorted((one, other) -> Integer.compare(one.order(), other.order()))
                    .toList();

            if (ordered.isEmpty()) {
                log.warn("Demo: the directory holds no entry carrying {}, so the sign-in page has no "
                        + "accounts to offer. With cert-alert.demo.generate-directory off, the directory "
                        + "being demonstrated has to carry them itself - see {}.", ROLE, LDIF);
            } else {
                log.info("Demo: offering {} account(s) on the sign-in page: {}",
                        ordered.size(), ordered.stream().map(Account::uid).toList());
            }
            return ordered;
        } catch (RuntimeException e) {
            // A sign-in page that will not render is worse than one without the tile: the
            // accounts are a convenience, and somebody who knows a username can still type it.
            log.warn("Demo: could not read the accounts from the directory: {}", e.toString());
            return List.of();
        }
    }

    private Account toAccount(Attributes attributes) throws javax.naming.NamingException {
        return new Account(
                value(attributes, ROLE),
                value(attributes, "uid"),
                displayNameOf(attributes),
                value(attributes, SUMMARY),
                order(value(attributes, ORDER)));
    }

    private static String displayNameOf(Attributes attributes) throws javax.naming.NamingException {
        String displayName = value(attributes, "displayName");
        return displayName != null ? displayName : value(attributes, "cn");
    }

    private static String value(Attributes attributes, String name) throws javax.naming.NamingException {
        var attribute = attributes.get(name);
        return attribute == null ? null : String.valueOf(attribute.get());
    }

    /** An unreadable or absent order sorts last rather than failing the whole list. */
    private static int order(String value) {
        try {
            return value == null ? Integer.MAX_VALUE : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }
}
