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

    // ------------------------------------------------------------ payments
    /** Paystack secret key, encrypted with {@link com.apex.commerce.common.SecretBox}. Never sent to clients. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Column(length = 500)
    public String paystackSecretKeyEnc;

    /** Last 4 characters and mode of the key, for showing "sk_live_…a1b2" in settings. */
    public String paystackKeyHint;

    // defaults let existing rows upgrade in place
    @Column(nullable = false, columnDefinition = "boolean default true")
    public boolean bankTransferEnabled = true;

    @Column(length = 2000)
    public String bankTransferInstructions;

    @Column(nullable = false, columnDefinition = "boolean default true")
    public boolean cashOnDeliveryEnabled = true;

    // ------------------------------------------------------------ homepage
    /** Homepage hero banners, in order. Empty: the template's own demo banner is shown. */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(length = 10000)
    public java.util.List<Banner> banners = new java.util.ArrayList<>();

    /**
     * @param link "shop", "category:&lt;slug&gt;", "product:&lt;slug&gt;", or a URL (https://… or /…)
     */
    public record Banner(String eyebrow, String title, String text, String buttonLabel, String link,
                         String imageUrl, String videoUrl) {}

    /**
     * Owner's changes to the template's promo blocks, keyed "&lt;templateId&gt;/&lt;block&gt;" (e.g.
     * "ecomas-01/offer-1"). A missing field keeps the template's text; "" removes it.
     */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(length = 20000)
    public java.util.Map<String, Promo> promos = new java.util.HashMap<>();

    public record Promo(String eyebrow, String title, String text, String buttonLabel, String link,
                        String imageUrl, Boolean hidden) {}

    /** The "Free delivery / Returns / …" strip. Null: the template's items. */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(length = 4000)
    public java.util.List<Feature> features;

    public record Feature(String title, String text) {}

    @Column(nullable = false, columnDefinition = "boolean default false")
    public boolean featuresHidden = false;

    /** Real customer reviews. Empty: the template's testimonial section is hidden (its reviews are made up). */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(length = 10000)
    public java.util.List<Testimonial> testimonials = new java.util.ArrayList<>();

    public record Testimonial(String name, String role, String quote, Integer rating) {}

    // ------------------------------------------------------------ taxes
    @Column(nullable = false, columnDefinition = "boolean default false")
    public boolean taxesEnabled = false;

    /** e.g. [{name: "VAT", rate: 15}, {name: "NHIL", rate: 2.5}] */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(length = 2000)
    public java.util.List<com.apex.commerce.pricing.Pricing.TaxRate> taxRates = new java.util.ArrayList<>();

    /** Prices already include tax (shown as "includes VAT …"); otherwise tax is added at checkout. */
    @Column(nullable = false, columnDefinition = "boolean default true")
    public boolean taxIncluded = true;

    /** Charge tax on the delivery fee too. */
    @Column(nullable = false, columnDefinition = "boolean default false")
    public boolean taxDelivery = false;

    public boolean paystackConnected() {
        return paystackSecretKeyEnc != null;
    }

    @Column(nullable = false)
    public Instant createdAt = Instant.now();

    public static Optional<Store> findBySlug(String slug) {
        return find("slug", slug).firstResultOptional();
    }
}
