package com.winllc.certalert.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.Instant;

/** A named set of filters one person keeps for a page: the page's query string. */
@Entity
@Table(name = "saved_view")
public class SavedView {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "saved_view_seq")
    @SequenceGenerator(name = "saved_view_seq", sequenceName = "saved_view_seq", allocationSize = 50)
    private Long id;

    @Column(nullable = false, length = 320)
    private String owner;

    @Column(nullable = false, length = 32)
    private String page;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 2000)
    private String query;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SavedView() {
        // for JPA
    }

    SavedView(String owner, String page, String name, String query, Instant createdAt) {
        this.owner = owner;
        this.page = page;
        this.name = name;
        this.query = query;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getOwner() {
        return owner;
    }

    public String getPage() {
        return page;
    }

    public String getName() {
        return name;
    }

    public String getQuery() {
        return query;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    void replace(String query) {
        this.query = query;
    }
}
