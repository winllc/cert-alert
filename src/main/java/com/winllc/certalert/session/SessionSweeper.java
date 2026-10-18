package com.winllc.certalert.session;

import com.winllc.certalert.audit.SessionAudit;
import com.winllc.certalert.cluster.JobLocks;
import com.winllc.certalert.security.SessionPolicy;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.serializer.support.DeserializingConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Clears expired sessions out of the database, and records the signed-in ones among them as
 * {@code auth.session-expired} ({@code idle}) - what the container's own expiry does for
 * the in-memory store, and what Spring Session's own clean-up would not.
 *
 * <p>Once a minute, on one replica at a time (see {@link JobLocks}). A stored session's
 * expiry is a few minutes beyond its idle limit (see {@link SessionPolicy}), so one that
 * comes back is refused and recorded on that request instead; these are the ones that never
 * came back. A session that a sign-out or the policy ended is not recorded again.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "cert-alert.session", name = "store", havingValue = "jdbc")
public class SessionSweeper implements BeanClassLoaderAware {

    private static final Logger log = LoggerFactory.getLogger(SessionSweeper.class);

    /** Sessions looked at per pass; the next pass takes the rest. */
    private static final int BATCH = 500;

    private final JdbcTemplate jdbc;
    private final JobLocks locks;
    private final SessionAudit audit;
    private final Clock clock;
    private DeserializingConverter deserializer = new DeserializingConverter();

    public SessionSweeper(javax.sql.DataSource dataSource, JobLocks locks, SessionAudit audit, Clock clock) {
        // Its own, for its row limit: the shared one is everybody's.
        this.jdbc = new JdbcTemplate(dataSource);
        this.locks = locks;
        this.audit = audit;
        this.clock = clock;
        this.jdbc.setMaxRows(BATCH);
    }

    @Override
    public void setBeanClassLoader(ClassLoader classLoader) {
        this.deserializer = new DeserializingConverter(classLoader);
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    public void sweep() {
        try {
            locks.runExclusively("session-sweep", (Runnable) this::sweepOnce);
        } catch (RuntimeException e) {
            log.warn("Clearing expired sessions failed; it is tried again shortly", e);
        }
    }

    /** @return how many expired sessions were cleared */
    public int sweepOnce() {
        long now = clock.millis();
        List<Map<String, Object>> expired = jdbc.queryForList(
                "SELECT PRIMARY_ID, SESSION_ID FROM SPRING_SESSION WHERE EXPIRY_TIME < ? ORDER BY EXPIRY_TIME", now);
        int cleared = 0;
        for (Map<String, Object> row : expired) {
            String primaryId = String.valueOf(row.get("PRIMARY_ID")).trim();
            String sessionId = String.valueOf(row.get("SESSION_ID")).trim();
            Map<String, Object> attributes = attributesOf(primaryId);
            // Deleted first, and only if it is still expired: a session used in the meantime
            // has moved its expiry on and is not this sweep's to clear or to record.
            if (jdbc.update("DELETE FROM SPRING_SESSION WHERE PRIMARY_ID = ? AND EXPIRY_TIME < ?", primaryId, now) == 0) {
                continue;
            }
            cleared++;
            if (attributes.get(SessionPolicy.SIGNED_IN_AT) != null
                    && attributes.get(SessionPolicy.ENDED) == null
                    && attributes.get(SessionPolicy.SIGNED_IN_AS) instanceof Authentication who) {
                audit.expired(who, sessionId, "idle", null);
            }
        }
        return cleared;
    }

    private Map<String, Object> attributesOf(String primaryId) {
        Map<String, Object> attributes = new HashMap<>();
        jdbc.query(
                "SELECT ATTRIBUTE_NAME, ATTRIBUTE_BYTES FROM SPRING_SESSION_ATTRIBUTES "
                        + "WHERE SESSION_PRIMARY_ID = ? AND ATTRIBUTE_NAME IN (?, ?, ?)",
                rs -> {
                    String name = rs.getString(1);
                    try {
                        attributes.put(name, deserializer.convert(rs.getBytes(2)));
                    } catch (RuntimeException e) {
                        // A class that has changed since: the session is cleared regardless.
                        log.debug("Could not read {} of an expired session: {}", name, e.toString());
                    }
                },
                primaryId,
                SessionPolicy.SIGNED_IN_AT,
                SessionPolicy.SIGNED_IN_AS,
                SessionPolicy.ENDED);
        return attributes;
    }
}
