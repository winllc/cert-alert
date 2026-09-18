package com.winllc.certalert.service;

import com.winllc.certalert.config.NotificationProperties;
import com.winllc.certalert.domain.NotificationSetting;
import com.winllc.certalert.repository.NotificationSettingRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * How many days before expiry the round-up writes to people, and who decided.
 *
 * <p>This is the number that gets argued about once a deployment is real: thirty days is
 * too late for a certificate whose renewal needs a change request raised, and a fortnight of
 * noise for a service that reissues weekly. It starts at whatever
 * {@code cert-alert.notifications.digest.window} says and is settled from the UI after
 * that, because the people who know the answer are not the people with access to the
 * configuration file.
 *
 * <p>Nothing here caches: one indexed read of a single row, once per round-up and once per
 * page that shows it, against a value somebody may change at any moment.
 */
@Service
public class NotificationSettingsService {

    private static final Logger log = LoggerFactory.getLogger(NotificationSettingsService.class);

    /** Below a day there is nothing to warn about; beyond a year it is not a warning. */
    public static final int MINIMUM_LEAD_DAYS = 1;

    public static final int MAXIMUM_LEAD_DAYS = 365;

    private final NotificationSettingRepository settings;
    private final NotificationProperties properties;
    private final Clock clock;

    public NotificationSettingsService(
            NotificationSettingRepository settings, NotificationProperties properties, Clock clock) {
        this.settings = settings;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Settings current() {
        int configured = configuredLeadDays();
        return settings.findById(NotificationSetting.SINGLETON_ID)
                .map(setting -> new Settings(
                        setting.getLeadDays(), configured, false, setting.getUpdatedAt(), setting.getUpdatedBy()))
                .orElseGet(() -> new Settings(configured, configured, true, null, null));
    }

    /** What the round-up asks for, and the only thing it needs. */
    @Transactional(readOnly = true)
    public int leadDays() {
        return current().leadDays();
    }

    @Transactional
    public Settings update(int leadDays, String actor) {
        if (leadDays < MINIMUM_LEAD_DAYS || leadDays > MAXIMUM_LEAD_DAYS) {
            throw new IllegalArgumentException("Days before expiry must be between %d and %d"
                    .formatted(MINIMUM_LEAD_DAYS, MAXIMUM_LEAD_DAYS));
        }
        Instant now = Instant.now(clock);
        NotificationSetting setting = settings
                .findById(NotificationSetting.SINGLETON_ID)
                .map(existing -> {
                    existing.update(leadDays, actor, now);
                    return existing;
                })
                .orElseGet(() -> settings.save(new NotificationSetting(leadDays, actor, now)));

        log.info("The expiry round-up now looks {} day(s) ahead, set by {}", leadDays, actor);
        return new Settings(
                setting.getLeadDays(), configuredLeadDays(), false, setting.getUpdatedAt(), setting.getUpdatedBy());
    }

    /**
     * What the round-up looks ahead by, and where that came from.
     *
     * @param leadDays days before expiry at which a certificate is reported
     * @param configuredLeadDays what the configuration file says, which is what resetting returns to
     * @param fromConfiguration whether nobody has set it here yet
     * @param updatedAt when it was last set, or null while it never has been
     * @param updatedBy who set it
     */
    public record Settings(
            int leadDays, int configuredLeadDays, boolean fromConfiguration, Instant updatedAt, String updatedBy) {}

    /** The configured window, in whole days, kept inside the bounds the UI enforces. */
    private int configuredLeadDays() {
        Duration window = properties.getDigest().getWindow();
        long days = window == null ? MAXIMUM_LEAD_DAYS : window.toDays();
        return (int) Math.min(Math.max(days, MINIMUM_LEAD_DAYS), MAXIMUM_LEAD_DAYS);
    }
}
