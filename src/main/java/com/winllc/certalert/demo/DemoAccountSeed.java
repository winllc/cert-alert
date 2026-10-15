package com.winllc.certalert.demo;

import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldif.LDIFException;
import com.unboundid.ldif.LDIFReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

/**
 * Reads the seed that defines the demo's sign-in accounts.
 *
 * <p>The seed is an LDIF because the accounts are directory entries: what a visitor signs
 * in as, and what the sign-in page prints about them, are the same thing, and keeping them
 * in one file means they cannot disagree. Editing who the demo offers is editing that file
 * and nothing else.
 *
 * <p>Two things are substituted into it, because neither can be written down in advance:
 * the suffix the demo was configured with, and the password it was configured with. Both
 * appear once per entry, so the file reads as an ordinary LDIF rather than as a template.
 */
final class DemoAccountSeed {

    private static final Logger log = LoggerFactory.getLogger(DemoAccountSeed.class);

    private DemoAccountSeed() {}

    /**
     * The accounts the seed defines, ready to be added to a directory.
     *
     * @param baseDn the suffix to hang them under
     * @param password what each of them signs in with
     * @return the entries, or empty where the seed is missing or unreadable
     */
    static List<Entry> read(String baseDn, String password) {
        String ldif;
        try (InputStream source = new ClassPathResource(DemoAccounts.LDIF).getInputStream()) {
            ldif = new String(source.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // A demo without its accounts still runs and still shows a directory; what it
            // cannot do is tell anybody how to sign in. Worth saying loudly, not worth
            // refusing to start over.
            log.error("Demo: could not read {}, so the sign-in page will offer no accounts", DemoAccounts.LDIF, e);
            return List.of();
        }

        ldif = ldif.replace("{base}", baseDn).replace("{password}", password);

        List<Entry> entries = new ArrayList<>();
        try (LDIFReader reader =
                new LDIFReader(new ByteArrayInputStream(ldif.getBytes(StandardCharsets.UTF_8)))) {
            Entry entry;
            while ((entry = reader.readEntry()) != null) {
                entries.add(entry);
            }
        } catch (IOException | LDIFException e) {
            log.error("Demo: {} is not valid LDIF, so the sign-in page will offer no accounts",
                    DemoAccounts.LDIF, e);
            return List.of();
        }
        return entries;
    }
}
