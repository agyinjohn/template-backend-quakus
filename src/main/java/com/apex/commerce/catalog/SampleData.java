package com.apex.commerce.catalog;

import com.apex.commerce.common.Slugs;
import com.apex.commerce.store.Store;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Fills a new store with demo categories and products that match the chosen
 * template, so the storefront looks complete straight away. Owners then edit
 * or delete them from the admin.
 */
@ApplicationScoped
public class SampleData {

    @Inject
    ObjectMapper mapper;

    private JsonNode catalog;

    private JsonNode catalog() {
        if (catalog == null) {
            try (InputStream in = getClass().getResourceAsStream("/sample-catalog.json")) {
                catalog = mapper.readTree(in);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return catalog;
    }

    /** @return number of products created */
    @Transactional
    public int seed(Store store, String templateId) {
        JsonNode set = catalog().get(templateId);
        if (set == null) {
            set = catalog().elements().next();
        }
        Map<String, Category> categories = new HashMap<>();
        int pos = 0;
        for (JsonNode c : set.get("categories")) {
            String name = c.asText();
            Category cat = Category.<Category>find("store = ?1 and name = ?2", store, name).firstResult();
            if (cat == null) {
                cat = new Category();
                cat.store = store;
                cat.name = name;
                cat.slug = Slugs.unique(name, s -> Category.count("store = ?1 and slug = ?2", store, s) > 0);
                cat.position = pos;
                cat.persist();
            }
            pos++;
            categories.put(name, cat);
        }
        int created = 0;
        for (JsonNode p : set.get("products")) {
            Product product = new Product();
            product.store = store;
            product.name = p.get("name").asText();
            product.slug = Slugs.unique(product.name,
                    s -> Product.count("store = ?1 and slug = ?2", store, s) > 0);
            product.price = p.get("price").decimalValue();
            JsonNode old = p.get("compareAtPrice");
            product.compareAtPrice = old == null || old.isNull() ? null : old.decimalValue();
            product.images.add(p.get("image").asText());
            product.rating = p.path("rating").asInt(5);
            product.featured = p.path("featured").asBoolean(false);
            product.category = categories.get(p.path("category").asText());
            product.stock = 25;
            product.shortDescription = "A customer favourite from our " + product.category.name.toLowerCase()
                    + " range.";
            product.description = "Replace this sample description with the details of your product: "
                    + "materials, sizing, care instructions and what makes it special.";
            product.sku = "SAMPLE-" + (created + 1);
            if (product.price.compareTo(BigDecimal.ZERO) <= 0) {
                product.price = BigDecimal.ONE;
            }
            product.persist();
            created++;
        }
        return created;
    }
}
