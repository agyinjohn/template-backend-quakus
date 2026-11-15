package com.apex.commerce.store;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Optional;

/** A tenant: one customer's shop, rendered with the template they chose. */
@Entity
@Table(name = "stores")
public class Store extends PanacheEntity {

    @Column(nullable = false)
    public String name;

    @Column(nullable = false, unique = true)
    public String slug;

    /** Id from {@link com.apex.commerce.templates.TemplateCatalog}; null until the owner picks one. */
    public String templateId;

    public String tagline;
    public String logoUrl;
    public String email;
    public String phone;
    public String address;

    @Column(nullable = false)
    public String currency = "USD";

    @Column(length = 2000)
    public String announcement;

    public String facebookUrl;
    public String instagramUrl;
    public String twitterUrl;

    @Column(nullable = false)
    public boolean published = true;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();

    public static Optional<Store> findBySlug(String slug) {
        return find("slug", slug).firstResultOptional();
    }
}
