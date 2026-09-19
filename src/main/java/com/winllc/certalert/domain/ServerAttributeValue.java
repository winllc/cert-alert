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

/**
 * What one server holds for one managed attribute.
 *
 * <p>A row per value rather than a list in a column: a multi-valued attribute is then a set
 * of rows that can be counted, searched and constrained, and the unique key is what stops
 * the same value being recorded twice.
 */
@Entity
@Table(
        name = "server_attribute_value",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_server_attribute_value",
                columnNames = {"definition_id", "server_id", "attribute_value"}),
        indexes = {
            @Index(name = "idx_server_attribute_value_server", columnList = "server_id"),
            @Index(name = "idx_server_attribute_value_definition", columnList = "definition_id")
        })
public class ServerAttributeValue {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "server_attribute_value_seq")
    @SequenceGenerator(
            name = "server_attribute_value_seq",
            sequenceName = "server_attribute_value_seq",
            allocationSize = 50)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(
            name = "definition_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_server_attribute_value_definition"))
    private ServerAttributeDefinition definition;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "server_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_server_attribute_value_server"))
    private DirectoryServer server;

    @Column(name = "attribute_value", nullable = false, length = 1000)
    private String value;

    /** Where it sits among the values of a multi-valued attribute. */
    @Column(name = "sort_order", nullable = false)
    private int position;

    @Column(name = "updated_by", length = 320)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ServerAttributeValue() {
        // for JPA
    }

    public ServerAttributeValue(
            ServerAttributeDefinition definition,
            DirectoryServer server,
            String value,
            int position,
            String actor,
            Instant now) {

        this.definition = definition;
        this.server = server;
        this.value = value;
        this.position = position;
        this.updatedBy = actor;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public ServerAttributeDefinition getDefinition() {
        return definition;
    }

    public DirectoryServer getServer() {
        return server;
    }

    public String getValue() {
        return value;
    }

    public int getPosition() {
        return position;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
