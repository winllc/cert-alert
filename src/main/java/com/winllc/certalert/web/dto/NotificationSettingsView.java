package com.winllc.certalert.web.dto;

import com.winllc.certalert.service.NotificationSettingsService;
import java.time.Instant;

/**
 * What the page shows about the round-up: how far ahead it looks, where that came from, and
 * whether the person looking may change it.
 *
 * @param leadDays days before expiry at which a certificate is reported
 * @param configuredLeadDays what the configuration file says, which is what it falls back to
 * @param fromConfiguration whether nobody has set it here yet
 * @param minimumLeadDays the bounds, so the page enforces the same ones the service does
 * @param emailEnabled whether anything is actually emailed; the round-up shows on the page regardless
 * @param editable whether this request may change it
 */
public record NotificationSettingsView(
        int leadDays,
        int configuredLeadDays,
        boolean fromConfiguration,
        int minimumLeadDays,
        int maximumLeadDays,
        Instant updatedAt,
        String updatedBy,
        boolean emailEnabled,
        boolean editable) {

    public static NotificationSettingsView of(
            NotificationSettingsService.Settings settings, boolean emailEnabled, boolean editable) {

        return new NotificationSettingsView(
                settings.leadDays(),
                settings.configuredLeadDays(),
                settings.fromConfiguration(),
                NotificationSettingsService.MINIMUM_LEAD_DAYS,
                NotificationSettingsService.MAXIMUM_LEAD_DAYS,
                settings.updatedAt(),
                settings.updatedBy(),
                emailEnabled,
                editable);
    }
}
