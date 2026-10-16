package com.winllc.certalert.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The head of the audit chain: the last seq handed out and that event's hash. One row, locked
 * by every writer for the moment it takes to append - which is what keeps seq gapless and
 * the chain unbroken across threads, and across instances sharing one database.
 */
@Entity
@Table(name = "audit_chain_head")
public class AuditChainHead {

    public static final int ID = 1;

    @Id
    private Integer id;

    @Column(name = "last_seq", nullable = false)
    private long lastSeq;

    @Column(name = "last_hash", nullable = false, length = 64)
    private String lastHash;

    protected AuditChainHead() {
        // for JPA
    }

    public long getLastSeq() {
        return lastSeq;
    }

    public String getLastHash() {
        return lastHash;
    }

    void advance(long seq, String hash) {
        if (seq != lastSeq + 1) {
            throw new IllegalStateException("Audit seq " + seq + " does not follow " + lastSeq);
        }
        this.lastSeq = seq;
        this.lastHash = hash;
    }
}
