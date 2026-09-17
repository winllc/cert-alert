package com.winllc.certalert.alert;

import com.winllc.certalert.service.NotificationService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * The channel that tells the people concerned, rather than the operators.
 *
 * <p>Every other notifier delivers an alert somewhere: a log line, a mailbox. This one
 * turns it into a notification per point of contact, waiting on the page for each of them
 * the next time they sign in.
 *
 * <p>Ordered last so a failure here cannot stop the channels that leave the building.
 */
@Component
@Order(100)
public class NotificationAlertNotifier implements AlertNotifier {

    private final NotificationService notificationService;

    public NotificationAlertNotifier(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Override
    public void send(CertificateAlert alert) {
        notificationService.notifyAbout(alert);
    }

    @Override
    public String channelName() {
        return "notifications";
    }
}
