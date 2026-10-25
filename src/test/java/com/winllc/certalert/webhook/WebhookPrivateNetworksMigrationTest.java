package com.winllc.certalert.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * V29: every webhook that exists at the upgrade keeps the private address it may have (CA-24);
 * one saved after it is held to the new default.
 */
class WebhookPrivateNetworksMigrationTest {

    @Test
    void webhooksSavedBeforeTheUpgradeKeepTheirPrivateAddress() throws SQLException {
        String url = "jdbc:h2:mem:v29-" + UUID.randomUUID();
        try (Connection keepOpen = DriverManager.getConnection(url, "sa", "");
                Statement statement = keepOpen.createStatement()) {
            SingleConnectionDataSource dataSource = new SingleConnectionDataSource(url, "sa", "", true);
            Flyway.configure().dataSource(dataSource).target("28").load().migrate();
            statement.executeUpdate(insert(1));

            Flyway.configure().dataSource(dataSource).load().migrate();
            statement.executeUpdate(insert(2));
            dataSource.destroy();

            try (ResultSet rows = statement.executeQuery(
                    "SELECT id, private_networks_allowed FROM webhook_endpoint ORDER BY id")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getBoolean(2)).as("saved before V29").isTrue();
                assertThat(rows.next()).isTrue();
                assertThat(rows.getBoolean(2)).as("saved after V29").isFalse();
            }
        }
    }

    private static String insert(long id) {
        return "INSERT INTO webhook_endpoint (id, name, url, format, events, secret, secret_encrypted, secret_prefix,"
                + " enabled, created_by, created_at, updated_at) VALUES (" + id + ", 'bridge " + id + "',"
                + " 'https://bridge.corp/x', 'JSON', 'certificate.expiring', 'whsec_x', FALSE, 'whsec_x', TRUE, 'test',"
                + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)";
    }
}
