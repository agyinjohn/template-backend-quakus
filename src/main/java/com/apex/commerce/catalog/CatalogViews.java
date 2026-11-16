package com.apex.commerce.catalog;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class CatalogViews {

    private CatalogViews() {}

    public record CategoryView(Long id, String name, String slug, String imageUrl, int position, long productCount) {
        public static CategoryView of(Category c) {
            return new CategoryView(c.id, c.name, c.slug, c.imageUrl, c.position,
                    Product.count("category", c));
        }

        public static CategoryView brief(Category c) {
            return c == null ? null : new CategoryView(c.id, c.name, c.slug, c.imageUrl, c.position, -1);
        }
    }

    public record ProductView(
            Long id, String name, String slug, String shortDescription, String description,
            BigDecimal price, BigDecimal compareAtPrice, String sku, int stock,
            List<String> images, List<String> tags, boolean featured, Product.Status status, int rating,
            CategoryView category, Instant createdAt, Instant updatedAt,
            List<Product.Option> options, List<VariantView> variants) {

        public static ProductView of(Product p) {
            return new ProductView(p.id, p.name, p.slug, p.shortDescription, p.description, p.price,
                    p.compareAtPrice, p.sku, p.stock, List.copyOf(p.images), List.copyOf(p.tags), p.featured,
                    p.status, p.rating, CategoryView.brief(p.category), p.createdAt, p.updatedAt,
                    p.options == null ? List.of() : List.copyOf(p.options),
                    p.hasVariants() ? p.variants.stream().map(VariantView::of).toList() : List.of());
        }
    }

    /** {@code price}/{@code compareAtPrice} are null when the variant uses the product's prices. */
    public record VariantView(Long id, List<String> values, String title, BigDecimal price, BigDecimal compareAtPrice,
                              String sku, int stock, String imageUrl) {
        public static VariantView of(ProductVariant v) {
            return new VariantView(v.id, v.values(), v.title(), v.price, v.compareAtPrice, v.sku, v.stock, v.imageUrl);
        }
    }

    public record Page<T>(List<T> items, long total, int page, int size) {}
}
