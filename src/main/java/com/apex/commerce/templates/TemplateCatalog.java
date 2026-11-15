package com.apex.commerce.templates;

import java.util.List;
import java.util.Optional;

/**
 * The storefront designs a store owner can choose from. The frontend holds the
 * HTML/CSS for each id; the backend only needs to know which ids are valid.
 * Add a new entry here when a new template pack is added to the frontend.
 */
public final class TemplateCatalog {

    public record Template(String id, String name, String industry, String description, String previewImage) {}

    private static final String PREVIEWS = "/templates/ecomas/previews/";

    public static final List<Template> ALL = List.of(
            new Template("ecomas-01", "Ecomas Home", "Furniture & jewellery",
                    "Warm hero slider, category tiles, featured collections and best sellers.",
                    PREVIEWS + "ecomas-01.jpg"),
            new Template("ecomas-02", "Ecomas Beauty", "Beauty & cosmetics",
                    "Soft pastel layout for skincare and make-up with best-seller carousels.",
                    PREVIEWS + "ecomas-02.jpg"),
            new Template("ecomas-03", "Ecomas Fashion", "Fashion & apparel",
                    "Editorial fashion layout with new collections and weekly features.",
                    PREVIEWS + "ecomas-03.jpg"),
            new Template("ecomas-04", "Ecomas Electronics", "Electronics & gadgets",
                    "Dense marketplace layout with deal of the day and countdown offers.",
                    PREVIEWS + "ecomas-04.jpg"),
            new Template("ecomas-05", "Ecomas Wellness", "Sports & wellness",
                    "Clean wellness layout with category shortcuts and featured products.",
                    PREVIEWS + "ecomas-05.jpg"),
            new Template("ecomas-06", "Ecomas Jewellery", "Jewellery & gifts",
                    "Elegant jewellery layout with trending products and gift collections.",
                    PREVIEWS + "ecomas-06.jpg"));

    public static final String DEFAULT_ID = "ecomas-01";

    private TemplateCatalog() {}

    public static Optional<Template> find(String id) {
        return ALL.stream().filter(t -> t.id().equals(id)).findFirst();
    }

    public static boolean exists(String id) {
        return find(id).isPresent();
    }
}
