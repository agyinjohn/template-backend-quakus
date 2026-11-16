package com.apex.commerce.auth;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * A one-time password reset link. Only a SHA-256 hash of the token is stored,
 * so a database leak can't be used to reset anyone's password.
 */
@Entity
@Table(name = "password_reset_tokens")
public class PasswordResetToken extends PanacheEntity {

    @ManyToOne(optional = false)
    public AppUser user;

    @Column(nullable = false, unique = true, length = 64)
    public String tokenHash;

    @Column(nullable = false)
    public Instant expiresAt;

    public Instant usedAt;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();

    public boolean usable() {
        return usedAt == null && Instant.now().isBefore(expiresAt);
    }

    public static String hash(String token) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
