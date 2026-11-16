package com.apex.commerce.auth;

import com.apex.commerce.store.Store;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Optional;

@Entity
@Table(name = "users")
public class AppUser extends PanacheEntity {

    public static final String ROLE_OWNER = "owner";

    @Column(nullable = false, unique = true)
    public String email;

    @Column(nullable = false)
    public String passwordHash;

    @Column(nullable = false)
    public String fullName;

    @Column(nullable = false)
    public String role = ROLE_OWNER;

    @ManyToOne(optional = false)
    public Store store;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();

    /** Login tokens issued before this moment are rejected (set when the password changes). */
    public Instant passwordChangedAt;

    public static Optional<AppUser> findByEmail(String email) {
        return find("email", email.trim().toLowerCase()).firstResultOptional();
    }
}
