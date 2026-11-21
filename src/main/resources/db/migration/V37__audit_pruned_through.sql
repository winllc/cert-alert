-- The chain head remembers how far retention pruned.
--
-- Verification accepted any oldest retained event as "after pruning", so events deleted from
-- the start of the trail by hand verified as intact. Retention now records, in the transaction
-- that deletes, the last seq it deleted and that event's hash; verification requires the oldest
-- retained event to be seq 1 or the one right after it, chained to that hash.
--
-- An installation that has already pruned (an audit.pruned event is in the trail) starts with
-- the seq before its oldest event and no hash, which verification accepts as given until
-- retention next prunes.
ALTER TABLE audit_chain_head ADD COLUMN pruned_through_seq BIGINT NOT NULL DEFAULT 0;
ALTER TABLE audit_chain_head ADD COLUMN pruned_through_hash VARCHAR(64);

UPDATE audit_chain_head SET pruned_through_seq = (SELECT MIN(seq) - 1 FROM audit_event)
 WHERE (SELECT MIN(seq) FROM audit_event) > 1
   AND EXISTS (SELECT 1 FROM audit_event WHERE event_action = 'audit.pruned');
