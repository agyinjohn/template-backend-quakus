package com.apex.commerce.catalog;

import com.apex.commerce.store.Store;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "products", uniqueConstraints = @UniqueConstraint(columnNames = {"store_id", "slug"}))
public class Product extends PanacheEntity {

    public enum Status { ACTIVE, DRAFT }

    @ManyToOne(optional = false)
    public Store store;

    @ManyToOne
    public Category category;

    @Column(nullable = false)
    public String name;

    @Column(nullable = false)
    public String slug;

    @Column(length = 500)
    public String shortDescription;

    @Column(length = 10000)
    public String description;

    @Column(nullable = false, precision = 12, scale = 2)
    public BigDecimal price;

    /** Original price shown struck through when the product is on sale. */
    @Column(precision = 12, scale = 2)
    public BigDecimal compareAtPrice;

    public String sku;

    /** For products with variants, kept equal to the sum of the variants' stock. */
    @Column(nullable = false)
    public int stock = 0;

    /** Up to 3 options, e.g. [{name: "Size", values: ["S","M","L"]}, {name: "Colour", ...}]. */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(length = 4000)
    public List<Option> options = new ArrayList<>();

    @jakarta.persistence.OneToMany(mappedBy = "product", cascade = jakarta.persistence.CascadeType.ALL, orphanRemoval = true)
    @jakarta.persistence.OrderBy("position, id")
    public List<ProductVariant> variants = new ArrayList<>();

    public record Option(String name, List<String> values) {}

    public boolean hasVariants() {
        return options != null && !options.isEmpty();
    }

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "product_images", joinColumns = @JoinColumn(name = "product_id"))
    @OrderColumn(name = "position")
    @Column(name = "url", length = 1000)
    public List<String> images = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "product_tags", joinColumns = @JoinColumn(name = "product_id"))
    @Column(name = "tag")
    public List<String> tags = new ArrayList<>();

    @Column(nullable = false)
    public boolean featured = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public Status status = Status.ACTIVE;

    /** 0-5 stars shown on product cards. */
    @Column(nullable = false)
    public int rating = 5;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();

    @Column(nullable = false)
    public Instant updatedAt = Instant.now();
}
