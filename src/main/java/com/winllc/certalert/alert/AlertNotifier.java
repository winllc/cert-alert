package com.winllc.certalert.alert;

/**
 * A delivery channel for alerts. Implementations are discovered as beans and every one of
 * them receives every alert; add a channel by adding a bean.
 */
public interface AlertNotifier {

    /**
     * Delivers an alert. Implementations should be quick, and may throw - the dispatcher
     * isolates failures so one broken channel cannot stop the others.
     */
    void send(CertificateAlert alert);

    /** Channel name used in logs. */
    default String channelName() {
        return getClass().getSimpleName();
    }
}
