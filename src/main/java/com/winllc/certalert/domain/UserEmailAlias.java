package com.winllc.certalert.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Locale;

/**
 * An address a person answers to that the directory does not publish for them.
 *
 * <p>The directory holds up to five addresses per person and this application indexes all
 * of them, but a server's {@code serverPOC} is written by whoever runs the server, and they
 * write what they use: an old address, a role address, or the team's distribution list. A
 * server named after a list has no contact at all as far as the directory is concerned.
 *
 * <p>So an address added here joins the set a {@code serverPOC} is matched against, and
 * from then on that server is one of theirs. A {@link Kind#GROUP} address is expected to
 * belong to several people at once - that is the whole point of a list - and each of them
 * carries their own row for it.
 */
@Entity
@Table(
        name = "user_email_alias",
        uniqueConstraints =
                @UniqueConstraint(name = "uk_user_email_alias", columnNames = {"user_id", "address"}),
        indexes = {
            @Index(name = "idx_user_email_alias_user", columnList = "user_id"),
            @Index(name = "idx_user_email_alias_address", columnList = "address")
        })
public class UserEmailAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "user_email_alias_seq")
    @SequenceGenerator(name = "user_email_alias_seq", sequenceName = "user_email_alias_seq", allocationSize = 50)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "user_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_user_email_alias_user"))
    private DirectoryUser user;

    /** Lowercased, like every other value the point-of-contact join matches on. */
    @Column(nullable = false, length = 320)
    private String address;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Kind kind = Kind.PERSONAL;

    /** What it is, in somebody's words: "old address", "platform team list". */
    @Column(length = 255)
    private String label;

    @Column(name = "added_by", length = 320)
    private String addedBy;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;

    protected UserEmailAlias() {
        // for JPA
    }

    public UserEmailAlias(DirectoryUser user, String address, Kind kind, String label, String addedBy, Instant addedAt) {
        this.user = user;
        this.address = normalise(address);
        this.kind = kind == null ? Kind.PERSONAL : kind;
        this.label = label == null || label.isBlank() ? null : label.trim();
        this.addedBy = addedBy;
        this.addedAt = addedAt;
    }

    /** Whether the address is this person's or a list they are on. */
    public enum Kind {
        /** Theirs alone: an old address, one the directory does not publish. */
        PERSONAL,
        /**
         * A list or role address several people answer. Nothing behaves differently for
         * one - the join is the same - but a server contacted by a list has more than one
         * person behind it, and a page that does not say so is confusing.
         */
        GROUP
    }

    private static String normalise(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    public Long getId() {
        return id;
    }

    public DirectoryUser getUser() {
        return user;
    }

    public String getAddress() {
        return address;
    }

    public Kind getKind() {
        return kind;
    }

    public String getLabel() {
        return label;
    }

    public String getAddedBy() {
        return addedBy;
    }

    public Instant getAddedAt() {
        return addedAt;
    }
}
