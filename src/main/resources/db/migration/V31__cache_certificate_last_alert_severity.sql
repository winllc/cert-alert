-- The severity of the last expiry alert raised about a cached certificate. A certificate is
-- alerted on as it comes into the warning window, at WARNING, and nothing more happened until
-- it expired: critical-threshold-days escalated nothing, because severity was only worked out
-- on a status change. With this the hourly refresh escalates it to CRITICAL once, when it
-- comes inside the threshold.
--
-- What is expiring already and still outside the default threshold (7 days) is taken to have
-- had its WARNING, so it escalates in its turn. What is inside it already is left alone: an
-- upgrade should not send a burst of alerts about certificates that crossed before it.

ALTER TABLE cached_certificate ADD COLUMN last_alert_severity VARCHAR(16);
UPDATE cached_certificate SET last_alert_severity = 'WARNING'
    WHERE status = 'EXPIRING_SOON' AND not_after > CURRENT_TIMESTAMP + INTERVAL '7' DAY;
