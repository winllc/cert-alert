package com.winllc.certalert.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.ldap.autoconfigure.LdapProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

/**
 * A directory or a mail server that accepts a connection and then says nothing does not hold
 * whatever was talking to it for good: both are given timeouts out of the box.
 */
@SpringBootTest
@ActiveProfiles("test")
class ConnectionTimeoutsTest {

    @Autowired
    private LdapProperties ldap;

    @Autowired
    private Environment environment;

    @Test
    void everyDirectoryConnectionWaitsTenSecondsToConnectAndAMinuteForAnAnswer() {
        // The base environment, which Boot's own context source and the application's two
        // others (DirectoryEntryConnection, ChangelogConnection) all read.
        assertThat(ldap.getBaseEnvironment())
                .containsEntry("com.sun.jndi.ldap.connect.timeout", "10000")
                .containsEntry("com.sun.jndi.ldap.read.timeout", "60000");
    }

    @Test
    void theMailServerIsGivenThirtySecondsForEachStep() {
        Map<String, String> mail = Binder.get(environment)
                .bind("spring.mail.properties", Bindable.mapOf(String.class, String.class))
                .get();

        assertThat(mail)
                .containsEntry("mail.smtp.connectiontimeout", "30000")
                .containsEntry("mail.smtp.timeout", "30000")
                .containsEntry("mail.smtp.writetimeout", "30000")
                .containsEntry("mail.smtps.timeout", "30000");
    }
}
