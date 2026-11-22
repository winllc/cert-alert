package com.winllc.certalert.audit;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Tests share one database, and several empty the audit table while the chain head stays
 * where it was. Verification reports events missing from the start unless retention pruned
 * them, so a test that verifies records that gap as pruned first.
 */
final class PrunedTrail {

    private PrunedTrail() {}

    /** Marks everything before the oldest retained event (or the whole trail, when empty) pruned. */
    static void asIfPruned(JdbcTemplate jdbc) {
        jdbc.update("update audit_chain_head set pruned_through_seq = "
                + "coalesce((select min(seq) from audit_event), last_seq + 1) - 1, pruned_through_hash = null");
    }
}
