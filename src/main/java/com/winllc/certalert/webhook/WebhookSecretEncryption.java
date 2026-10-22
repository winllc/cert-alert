package com.winllc.certalert.webhook;

import com.winllc.certalert.audit.AuditLog;
import com.winllc.certalert.audit.AuditRecord;
import com.winllc.certalert.cluster.JobLocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Encrypts, at startup, the webhook URLs and signing secrets saved before
 * {@code cert-alert.webhooks.secret-key} was set - which would otherwise stay as they were
 * typed until somebody edited them.
 *
 * <p>Safe to run on every start and on every replica at once: under the database lock every
 * job takes, and each row is replaced only by a conditional update that still finds it
 * unencrypted and unchanged since it was read - so a row is encrypted once, never twice, and
 * never over a change made in between. Without a key there is nothing to do.
 */
@Component
public class WebhookSecretEncryption {

    private static final Logger log = LoggerFactory.getLogger(WebhookSecretEncryption.class);

    static final String LOCK = "webhook-secret-encryption";

    private final WebhookEndpointRepository endpoints;
    private final WebhookSecrets secrets;
    private final JobLocks locks;
    private final AuditLog audit;
    private final TransactionTemplate transaction;

    public WebhookSecretEncryption(
            WebhookEndpointRepository endpoints,
            WebhookSecrets secrets,
            JobLocks locks,
            AuditLog audit,
            PlatformTransactionManager transactions) {
        this.endpoints = endpoints;
        this.secrets = secrets;
        this.locks = locks;
        this.audit = audit;
        this.transaction = new TransactionTemplate(transactions);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStart() {
        try {
            encryptStored();
        } catch (RuntimeException e) {
            log.error("Could not encrypt the webhooks' stored URLs and secrets; tried again at the next start", e);
        }
    }

    /** @return how many values were encrypted by this call */
    public int encryptStored() {
        if (!secrets.encrypts()) {
            return 0;
        }
        int[] encrypted = {0};
        locks.runExclusively(LOCK, () -> {
            for (WebhookEndpoint endpoint : endpoints.findUnencrypted()) {
                Integer done;
                try {
                    done = transaction.execute(status -> {
                        int count = 0;
                        if (!endpoint.isUrlEncrypted()) {
                            count += endpoints.encryptUrl(endpoint.getId(), endpoint.getStoredUrl(),
                                    secrets.storeUrl(endpoint.getStoredUrl()).value());
                        }
                        if (!endpoint.isSecretEncrypted()) {
                            count += endpoints.encryptSecret(endpoint.getId(), endpoint.getStoredSecret(),
                                    secrets.storeSecret(endpoint.getStoredSecret()).value());
                        }
                        return count;
                    });
                } catch (IllegalArgumentException e) {
                    // Too long to keep encrypted: left as it is, and the others still done.
                    log.warn("Webhook {} was left unencrypted: {}", endpoint.getId(), e.getMessage());
                    continue;
                }
                encrypted[0] += done == null ? 0 : done;
            }
        });
        if (encrypted[0] > 0) {
            log.info("Encrypted {} stored webhook URL(s) and secret(s) with cert-alert.webhooks.secret-key", encrypted[0]);
            audit.recordQuietly(AuditRecord.of(AuditRecord.Category.CONFIGURATION, "webhook.secrets-encrypted")
                    .actor(AuditRecord.Actor.system("startup"))
                    .detail("count", encrypted[0]));
        }
        return encrypted[0];
    }
}
