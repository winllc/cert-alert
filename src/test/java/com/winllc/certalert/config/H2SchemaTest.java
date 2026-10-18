package com.winllc.certalert.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * The migrations on H2, the default database, after the connection that ran them has gone -
 * which in a running application is when the pool retires it.
 *
 * <p>H2 2.4 ties a CHECK on a list of constants to the connection that created it, and once
 * that connection closes, every insert the constraint applies to fails. V20 rewrote the one
 * such constraint, on server_attribute_definition; this holds it to that.
 */
class H2SchemaTest {

    @Test
    void theConstraintsStillHoldOnceTheMigratingConnectionHasClosed() throws SQLException {
        // A database of its own, there for as long as this connection is open.
        String url = "jdbc:h2:mem:schema-" + UUID.randomUUID();
        try (Connection keepOpen = DriverManager.getConnection(url, "sa", "")) {
            SingleConnectionDataSource migrating = new SingleConnectionDataSource(url, "sa", "", true);
            Flyway.configure().dataSource(migrating).load().migrate();
            migrating.destroy();

            try (Statement statement = keepOpen.createStatement()) {
                assertThatCode(() -> statement.executeUpdate(insertAttribute(1, "CHOICE")))
                        .doesNotThrowAnyException();
                // And it is still the rule it was.
                assertThatThrownBy(() -> statement.executeUpdate(insertAttribute(2, "COLOUR")))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("CK_SERVER_ATTRIBUTE_DEFINITION_TYPE")
                        .hasMessageContaining("violation");
            }
        }
    }

    private static String insertAttribute(long id, String type) {
        return "INSERT INTO server_attribute_definition"
                + " (id, name, ldap_attribute, type, multi_valued, display_order, created_at, updated_at)"
                + " VALUES (" + id + ", 'attribute " + id + "', 'attr" + id + "', '" + type + "', FALSE, 0,"
                + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)";
    }
}
