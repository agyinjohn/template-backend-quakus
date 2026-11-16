package com.apex.commerce.templates;

import com.apex.commerce.catalog.CatalogViews.CategoryView;
import com.apex.commerce.catalog.CatalogViews.ProductView;
import com.apex.commerce.catalog.Product;
import com.apex.commerce.common.ApiException;
import com.apex.commerce.common.Slugs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Path("/api/templates")
public class TemplateResource {

    /** Demo content for previewing a template before a store has products. Nothing is saved. */
    public record Sample(List<CategoryView> categories, List<ProductView> products) {}

    @Inject
    ObjectMapper mapper;

    @GET
    public List<TemplateCatalog.Template> list() {
        return TemplateCatalog.ALL;
    }

    @GET
    @Path("/{id}/sample")
    public Sample sample(@PathParam("id") String id) {
        if (!TemplateCatalog.exists(id)) {
            throw ApiException.notFound("Template");
        }
        JsonNode set;
        try (InputStream in = getClass().getResourceAsStream("/sample-catalog.json")) {
            set = mapper.readTree(in).get(id);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<CategoryView> categories = new ArrayList<>();
        long catId = -1;
        for (JsonNode c : set.get("categories")) {
            String name = c.asText();
            long count = 0;
            for (JsonNode p : set.get("products")) {
                if (p.path("category").asText().equals(name)) {
                    count++;
                }
            }
            categories.add(new CategoryView(catId--, name, Slugs.slugify(name), null, categories.size(), count));
        }
        List<ProductView> products = new ArrayList<>();
        long pid = -1;
        Instant now = Instant.now();
        for (JsonNode p : set.get("products")) {
            String catName = p.path("category").asText();
            CategoryView cat = categories.stream().filter(c -> c.name().equals(catName)).findFirst().orElse(null);
            JsonNode old = p.get("compareAtPrice");
            products.add(new ProductView(pid, p.get("name").asText(), Slugs.slugify(p.get("name").asText()),
                    "A customer favourite.", "Sample product shown in the template preview.",
                    p.get("price").decimalValue(), old == null || old.isNull() ? null : old.decimalValue(),
                    null, 25, List.of(p.get("image").asText()), List.of(), p.path("featured").asBoolean(),
                    Product.Status.ACTIVE, p.path("rating").asInt(5),
                    cat == null ? null : new CategoryView(cat.id(), cat.name(), cat.slug(), null, cat.position(), -1),
                    now.minusSeconds(-pid * 60), now, List.of(), List.of()));
            pid--;
        }
        return new Sample(categories, products);
    }
}
