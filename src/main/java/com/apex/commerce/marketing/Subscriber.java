package com.apex.commerce.marketing;

import com.apex.commerce.store.Store;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/** A shopper who signed up to a store's newsletter. */
@Entity
@Table(name = "subscribers", uniqueConstraints = @UniqueConstraint(columnNames = {"store_id", "email"}))
public class Subscriber extends PanacheEntity {

    @JsonIgnore
    @ManyToOne(optional = false)
    public Store store;

    @Column(nullable = false)
    public String email;

    /** Where they signed up, e.g. "footer" or "home-banner". */
    public String source;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();
}
