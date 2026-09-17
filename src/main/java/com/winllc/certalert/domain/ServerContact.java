package com.winllc.certalert.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * A point of contact somebody added to a server here, rather than one the directory
 * published.
 *
 * <p>The directory's own {@code serverPOC} is authoritative and read-only: a sweep
 * overwrites it, which is exactly what should happen to a cached copy of somebody else's
 * data. These rows sit alongside it and survive a sweep, because they are this
 * application's data, not the directory's.
 *
 * <p>A contact is either a person in the directory or a bare address. A person is held as
 * a link rather than a copy of their address, so the filters that ask "which servers is
 * this person responsible for" find it, and so a change of address in the directory does
 * not strand the contact. An address that happens to belong to somebody the directory
 * knows is linked to them on the way in, so both routes land in the same place.
 */
@Entity
@Table(
        name = "server_contact",
        uniqueConstraints = {
            @UniqueConstraint(name = "uk_server_contact_user", columnNames = {"server_id", "user_id"}),
            @UniqueConstraint(name = "uk_server_contact_email", columnNames = {"server_id", "email"})
        },
        indexes = {
            @Index(name = "idx_server_contact_server", columnList = "server_id"),
            @Index(name = "idx_server_contact_user", columnList = "user_id"),
            @Index(name = "idx_server_contact_email", columnList = "email")
        })
public class ServerContact {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "server_contact_seq")
    @SequenceGenerator(name = "server_contact_seq", sequenceName = "server_contact_seq", allocationSize = 50)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "server_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_server_contact_server"))
    private DirectoryServer server;

    /** The person this contact is, when it is a person the directory knows. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", foreignKey = @ForeignKey(name = "fk_server_contact_user"))
    private DirectoryUser user;

    /**
     * The address to chase, lowercased. Null only for a linked person the directory
     * publishes no address for - they can still be shown and filtered on, just not written
     * to.
     *
     * <p>For a linked person this is a copy of their address as it stood when the contact
     * was added, and {@link #address()} reads through the link instead. It is stored so the
     * unique constraint can see it: adding somebody by name and then again by their address
     * would otherwise make two contacts out of one person.
     */
    @Column(length = 320)
    private String email;

    /** Whoever added it, as the directory names them. Null for anything added before sign-in. */
    @Column(name = "added_by", length = 320)
    private String addedBy;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;

    protected ServerContact() {
        // for JPA
    }

    private ServerContact(DirectoryServer server, DirectoryUser user, String email, String addedBy, Instant addedAt) {
        this.server = server;
        this.user = user;
        this.email = normalise(email);
        this.addedBy = addedBy;
        this.addedAt = addedAt;
    }

    /** A contact that is a person in the directory, carrying their address where they have one. */
    public static ServerContact forUser(DirectoryServer server, DirectoryUser user, String addedBy, Instant addedAt) {
        return new ServerContact(server, user, user.getEmail(), addedBy, addedAt);
    }

    /** A contact that is an address and nothing more. */
    public static ServerContact forEmail(DirectoryServer server, String email, String addedBy, Instant addedAt) {
        return new ServerContact(server, null, email, addedBy, addedAt);
    }

    /**
     * Where to write. Read through the link for a person, so a change of address in the
     * directory is picked up without anything here having to be refreshed.
     */
    public String address() {
        if (user != null && user.getEmail() != null && !user.getEmail().isBlank()) {
            return user.getEmail();
        }
        return email;
    }

    /** What to show: the person's name where there is one, otherwise the bare address. */
    public String label() {
        if (user != null) {
            String name = user.getDisplayName() != null ? user.getDisplayName() : user.getCommonName();
            if (name != null && !name.isBlank()) {
                return name;
            }
            if (user.getUid() != null && !user.getUid().isBlank()) {
                return user.getUid();
            }
        }
        return email;
    }

    private static String normalise(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        return trimmed.isEmpty() ? null : trimmed;
    }

    public Long getId() {
        return id;
    }

    public DirectoryServer getServer() {
        return server;
    }

    public DirectoryUser getUser() {
        return user;
    }

    public String getEmail() {
        return email;
    }

    public String getAddedBy() {
        return addedBy;
    }

    public Instant getAddedAt() {
        return addedAt;
    }
}
