# cert-alert

Monitors TLS certificates on endpoints you care about and raises an alert before they
expire.

Point it at a list of `host:port` targets; it connects to each one on a schedule, reads
the certificate the endpoint presents, records the result, and alerts when a certificate
is close to expiry, already expired, or the endpoint has stopped answering.

## Status

This is the project skeleton: the domain model, persistence, scheduling, alerting, and
REST API are in place and covered by tests. Authentication, a UI, and additional alert
channels are not implemented yet — see [Next steps](#next-steps).

## Tech stack

| Concern    | Choice                                   |
|------------|------------------------------------------|
| Language   | Java 21                                  |
| Framework  | Spring Boot 4.1                          |
| Build      | Gradle (Kotlin DSL) with wrapper         |
| Persistence| Spring Data JPA + Flyway migrations      |
| Database   | H2 by default, PostgreSQL for deployment |
| Tests      | JUnit 5, Mockito, MockMvc                |

## Running it

```bash
./gradlew bootRun --args='--spring.profiles.active=dev'
```

The `dev` profile uses an in-memory database, enables the H2 console at `/h2-console`,
turns on debug logging, and scans every five minutes. With no profile the app uses an H2
file database under `./data`, so it also runs standalone with no setup.

```bash
./gradlew test    # run the test suite
./gradlew build   # compile, test, and produce the executable jar
java -jar build/libs/cert-alert-0.0.1-SNAPSHOT.jar
```

### Against PostgreSQL

```bash
export CERT_ALERT_DB_URL=jdbc:postgresql://localhost:5432/certalert
export CERT_ALERT_DB_USER=certalert
export CERT_ALERT_DB_PASSWORD=...
./gradlew bootRun --args='--spring.profiles.active=postgres'
```

Flyway owns the schema and applies migrations on startup. Hibernate is set to `validate`,
so a mapping that drifts from the migrations fails fast at boot rather than silently
altering tables.

## API

All endpoints live under `/api/v1/targets`. Errors are returned as RFC 7807 problem
details.

| Method   | Path                  | Purpose                                     |
|----------|-----------------------|---------------------------------------------|
| `GET`    | `/`                   | List targets with their latest check summary |
| `POST`   | `/`                   | Add a target                                 |
| `GET`    | `/{id}`               | Fetch one target                             |
| `PUT`    | `/{id}`               | Replace a target                             |
| `DELETE` | `/{id}`               | Remove a target and its history              |
| `POST`   | `/{id}/check`         | Check one target now                         |
| `POST`   | `/check`              | Check every enabled target now               |
| `GET`    | `/{id}/checks`        | Paged check history (`page`, `size`)         |

```bash
curl -X POST http://localhost:8080/api/v1/targets \
  -H 'Content-Type: application/json' \
  -d '{"name":"prod-api","hostname":"api.example.com","port":443}'

curl -X POST http://localhost:8080/api/v1/targets/1/check
```

```json
{
  "id": 1,
  "status": "EXPIRING_SOON",
  "checkedAt": "2026-09-16T09:38:29Z",
  "subject": "CN=api.example.com",
  "issuer": "CN=Example CA",
  "serialNumber": "212d841bea9dffa1ce19b36a961f4d78",
  "notBefore": "2026-09-16T09:38:20Z",
  "notAfter": "2026-10-06T09:38:20Z",
  "daysUntilExpiry": 19
}
```

Actuator is exposed at `/actuator` (`health`, `info`, `metrics`, `loggers`).

## How it works

```
CertificateScanScheduler  (cron)
        |
CertificateSweepService   one transaction per target, so one bad endpoint
        |                 cannot stall or roll back the rest of the sweep
CertificateMonitorService
        |-- CertificateInspector          opens TLS, reads the leaf certificate
        |-- CertificateStatusEvaluator    expiry -> status + severity
        |-- CertificateCheckRepository    appends to the history
        `-- AlertDispatcher --> AlertNotifier beans (log, email, ...)
```

### Statuses

| Status          | Meaning                                              |
|-----------------|------------------------------------------------------|
| `VALID`         | Expires beyond the warning window                    |
| `EXPIRING_SOON` | Expires within `warning-threshold-days`              |
| `EXPIRED`       | Past its `notAfter` date                             |
| `UNREACHABLE`   | Connection or TLS handshake failed                   |

Severity is `WARNING`, or `CRITICAL` once a certificate is inside
`critical-threshold-days`, expired, or the endpoint is unreachable.

### Alert suppression

A target that stays in the same unhealthy state re-alerts at most once per
`alert-repeat-interval`. Any change of state alerts immediately, and a target returning to
`VALID` sends one `INFO` recovery alert and resets its alert bookkeeping.

### A note on trust

The inspector completes its handshake with a trust manager that accepts every chain. That
is deliberate: an expired or self-signed certificate is exactly what this tool needs to
report on, and a validating trust manager would abort the handshake before the certificate
could be read. Nothing is ever sent over these connections, and validity is judged from
the certificate's own dates. Do not reuse `CertificateInspector` as a general-purpose
HTTPS client.

## Configuration

Everything below sits under the `cert-alert` prefix in `application.yml`.

| Property                  | Default       | Purpose                                      |
|---------------------------|---------------|----------------------------------------------|
| `warning-threshold-days`  | `30`          | Window that marks a certificate expiring soon |
| `critical-threshold-days` | `7`           | Window that escalates severity to critical    |
| `connect-timeout`         | `10s`         | TCP connect timeout                           |
| `read-timeout`            | `10s`         | TLS handshake read timeout                    |
| `alert-repeat-interval`   | `24h`         | Minimum gap between repeat alerts             |
| `scan-cron`               | `0 0 * * * *` | Sweep schedule, evaluated in UTC              |
| `scan-enabled`            | `true`        | Set false to drive checks via the API only    |
| `alerts.logging.enabled`  | `true`        | Write alerts to the application log           |
| `alerts.email.enabled`    | `false`       | Email alerts; needs `spring.mail.*`           |
| `alerts.email.to`         | `[]`          | Recipients                                    |

### Adding an alert channel

Implement `AlertNotifier` and expose it as a bean. `AlertDispatcher` picks up every
implementation on the classpath and isolates failures, so a broken channel cannot stop the
others.

```java
@Component
class SlackAlertNotifier implements AlertNotifier {

    @Override
    public void send(CertificateAlert alert) {
        // POST alert.summary() to a webhook
    }
}
```

## Project layout

```
src/main/java/com/winllc/certalert/
├── alert/       notifier SPI, dispatcher, log and email channels
├── config/      configuration properties, clock
├── domain/      JPA entities and enums
├── repository/  Spring Data repositories
├── scheduling/  cron-driven sweep
├── service/     inspection, evaluation, monitoring, CRUD
└── web/         REST controller, DTOs, problem-detail handling
src/main/resources/db/migration/   Flyway migrations
```

## Next steps

- Secure the API (nothing is authenticated today)
- Retention/pruning for the `certificate_check` history
- More alert channels (Slack, PagerDuty, webhooks)
- Certificate chain reporting, not just the leaf
- A UI over the target list
