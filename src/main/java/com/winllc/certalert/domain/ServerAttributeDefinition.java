package com.winllc.certalert.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * An attribute an administrator decided this deployment keeps about its servers.
 *
 * <p>The directory's schema is somebody else's and fixed. What a team wants recorded
 * against a server - the environment it belongs to, whether it is in scope for an audit,
 * which budget pays for it - is not in that schema and will not be added to it, so it is
 * defined here instead and filled in per server.
 */
@Entity
@Table(
        name = "server_attribute_definition",
        uniqueConstraints =
                @UniqueConstraint(name = "uk_server_attribute_definition_name", columnNames = "name"))
public class ServerAttributeDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "server_attribute_definition_seq")
    @SequenceGenerator(
            name = "server_attribute_definition_seq",
            sequenceName = "server_attribute_definition_seq",
            allocationSize = 50)
    private Long id;

    /** What it is called, which is also its label on the page. */
    @Column(nullable = false, length = 120)
    private String name;

    /** What it is for, shown under the control so nobody has to guess. */
    @Column(length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ServerAttributeType type;

    /** Whether a server may hold several of these. Never true for a boolean. */
    @Column(name = "multi_valued", nullable = false)
    private boolean multiValued;

    /** Where it sits among the others, so the page reads in the order somebody chose. */
    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    /** What a choice attribute may be set to, in the order the drop-down lists them. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "server_attribute_option",
            joinColumns = @JoinColumn(
                    name = "definition_id",
                    foreignKey = @ForeignKey(name = "fk_server_attribute_option_definition")))
    @OrderColumn(name = "sort_order")
    @Column(name = "option_value", length = 200, nullable = false)
    private List<String> options = new ArrayList<>();

    @Column(name = "created_by", length = 320)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_by", length = 320)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ServerAttributeDefinition() {
        // for JPA
    }

    public ServerAttributeDefinition(
            String name,
            String description,
            ServerAttributeType type,
            boolean multiValued,
            List<String> options,
            int displayOrder,
            String actor,
            Instant now) {

        this.name = name;
        this.description = description;
        this.type = type;
        this.multiValued = multiValued;
        this.options = new ArrayList<>(options);
        this.displayOrder = displayOrder;
        this.createdBy = actor;
        this.createdAt = now;
        this.updatedBy = actor;
        this.updatedAt = now;
    }

    public void update(
            String name,
            String description,
            ServerAttributeType type,
            boolean multiValued,
            List<String> options,
            int displayOrder,
            String actor,
            Instant now) {

        this.name = name;
        this.description = description;
        this.type = type;
        this.multiValued = multiValued;
        // Replaced in place: Hibernate owns this collection, and swapping the instance would
        // delete and re-insert every row on every save.
        this.options.clear();
        this.options.addAll(options);
        this.displayOrder = displayOrder;
        this.updatedBy = actor;
        this.updatedAt = now;
    }

    /** Whether this value is one the attribute may hold at all. */
    public boolean permits(String value) {
        return switch (type) {
            case TEXT -> value != null && !value.isBlank();
            case CHOICE -> options.contains(value);
            case BOOLEAN -> "true".equals(value) || "false".equals(value);
        };
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public ServerAttributeType getType() {
        return type;
    }

    public boolean isMultiValued() {
        return multiValued;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    public List<String> getOptions() {
        return options;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
