package com.apex.commerce.admin;

import com.apex.commerce.auth.AppUser;
import com.apex.commerce.auth.CurrentUser;
import com.apex.commerce.catalog.CatalogViews.Page;
import com.apex.commerce.catalog.CatalogViews.ProductView;
import com.apex.commerce.catalog.Category;
import com.apex.commerce.catalog.Product;
import com.apex.commerce.common.ApiException;
import com.apex.commerce.common.Slugs;
import com.apex.commerce.store.Store;
import io.quarkus.panache.common.Sort;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Path("/api/admin/products")
@RolesAllowed(AppUser.ROLE_OWNER)
public class ProductAdminResource {

    public record ProductRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 500) String shortDescription,
            @Size(max = 10000) String description,
            @NotNull @DecimalMin(value = "0.00") BigDecimal price,
            @DecimalMin(value = "0.00") BigDecimal compareAtPrice,
            @Size(max = 80) String sku,
            @Min(0) int stock,
            Long categoryId,
            @Size(max = 12) List<String> images,
            @Size(max = 20) List<String> tags,
            boolean featured,
            Product.Status status,
            @Min(0) @Max(5) Integer rating,
            @Size(max = 3, message = "can have at most 3 options") List<@Valid OptionInput> options,
            @Size(max = 100, message = "can have at most 100 variants") List<@Valid VariantInput> variants) {}

    public record OptionInput(@NotBlank @Size(max = 30) String name,
                              @jakarta.validation.constraints.NotEmpty @Size(max = 30) List<@NotBlank @Size(max = 40) String> values) {}

    public record VariantInput(Long id, @jakarta.validation.constraints.NotEmpty List<String> values,
                               @DecimalMin("0.00") BigDecimal price, @DecimalMin("0.00") BigDecimal compareAtPrice,
                               @Size(max = 80) String sku, @Min(0) int stock, @Size(max = 1000) String imageUrl) {}

    @Inject
    CurrentUser currentUser;

    @GET
    public Page<ProductView> list(@QueryParam("q") String q,
                                  @QueryParam("categoryId") Long categoryId,
                                  @QueryParam("status") Product.Status status,
                                  @QueryParam("page") @DefaultValue("0") int page,
                                  @QueryParam("size") @DefaultValue("25") int size) {
        StringBuilder where = new StringBuilder("store = :store");
        Map<String, Object> params = new HashMap<>();
        params.put("store", currentUser.store());
        if (q != null && !q.isBlank()) {
            where.append(" and (lower(name) like :q or lower(sku) like :q)");
            params.put("q", "%" + q.toLowerCase().trim() + "%");
        }
        if (categoryId != null) {
            where.append(" and category.id = :cat");
            params.put("cat", categoryId);
        }
        if (status != null) {
            where.append(" and status = :status");
            params.put("status", status);
        }
        size = Math.min(Math.max(size, 1), 100);
        var query = Product.<Product>find(where.toString(), Sort.descending("createdAt", "id"), params);
        List<ProductView> items = query.page(page, size).list().stream().map(ProductView::of).toList();
        return new Page<>(items, query.count(), page, size);
    }

    @GET
    @Path("/{id}")
    public ProductView get(@PathParam("id") Long id) {
        return ProductView.of(owned(id));
    }

    @POST
    @Transactional
    public Response create(@Valid ProductRequest req) {
        Product p = new Product();
        p.store = currentUser.store();
        apply(p, req);
        p.slug = Slugs.unique(p.name, s -> Product.count("store = ?1 and slug = ?2", p.store, s) > 0);
        p.persist();
        return Response.status(Response.Status.CREATED).entity(ProductView.of(p)).build();
    }

    @PUT
    @Path("/{id}")
    @Transactional
    public ProductView update(@PathParam("id") Long id, @Valid ProductRequest req) {
        Product p = owned(id);
        String oldName = p.name;
        apply(p, req);
        if (!p.name.equals(oldName)) {
            p.slug = Slugs.unique(p.name,
                    s -> Product.count("store = ?1 and slug = ?2 and id <> ?3", p.store, s, p.id) > 0);
        }
        p.updatedAt = Instant.now();
        return ProductView.of(p);
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response delete(@PathParam("id") Long id) {
        owned(id).delete();
        return Response.noContent().build();
    }

    private void apply(Product p, ProductRequest req) {
        if (req.compareAtPrice() != null && req.compareAtPrice().compareTo(req.price()) <= 0) {
            throw ApiException.badRequest("compareAtPrice must be higher than price (it is the old price)");
        }
        p.name = req.name().trim();
        p.shortDescription = req.shortDescription();
        p.description = req.description();
        p.price = req.price();
        p.compareAtPrice = req.compareAtPrice();
        p.sku = req.sku();
        p.stock = req.stock();
        p.featured = req.featured();
        p.status = req.status() == null ? Product.Status.ACTIVE : req.status();
        p.rating = req.rating() == null ? 5 : req.rating();
        applyOptions(p, req);
        p.images = new ArrayList<>(req.images() == null ? List.of() : req.images());
        p.tags = new ArrayList<>(req.tags() == null ? List.of() : req.tags());
        p.category = null;
        if (req.categoryId() != null) {
            Category c = Category.findById(req.categoryId());
            if (c == null || !c.store.id.equals(currentUser.store().id)) {
                throw ApiException.badRequest("Unknown category");
            }
            p.category = c;
        }
    }

    /**
     * Replaces the product's options and variants. Variants are matched by id (or
     * by their option values) so existing ones keep their ids and order history.
     */
    private void applyOptions(Product p, ProductRequest req) {
        List<OptionInput> options = req.options() == null ? List.of() : req.options();
        List<VariantInput> variants = req.variants() == null ? List.of() : req.variants();
        var names = new java.util.HashSet<String>();
        List<Product.Option> clean = new ArrayList<>();
        for (OptionInput o : options) {
            String name = o.name().trim();
            if (!names.add(name.toLowerCase())) {
                throw ApiException.badRequest("Option names must be different (\"" + name + "\" is used twice)");
            }
            List<String> values = o.values().stream().map(String::trim).toList();
            if (new java.util.HashSet<>(values.stream().map(String::toLowerCase).toList()).size() != values.size()) {
                throw ApiException.badRequest("Each value of \"" + name + "\" must be different");
            }
            clean.add(new Product.Option(name, values));
        }
        if (clean.isEmpty()) {
            if (!variants.isEmpty()) {
                throw ApiException.badRequest("Add options (like Size or Colour) before adding variants");
            }
            p.options = new ArrayList<>();
            p.variants.clear();
            return;
        }
        if (variants.isEmpty()) {
            throw ApiException.badRequest("Add at least one variant (a combination of option values) with its stock");
        }
        var seen = new java.util.HashSet<String>();
        var existing = new java.util.HashMap<Long, com.apex.commerce.catalog.ProductVariant>();
        var byTitle = new java.util.HashMap<String, com.apex.commerce.catalog.ProductVariant>();
        for (var v : p.variants) {
            existing.put(v.id, v);
            byTitle.put(v.title().toLowerCase(), v);
        }
        List<com.apex.commerce.catalog.ProductVariant> next = new ArrayList<>();
        int total = 0;
        for (int i = 0; i < variants.size(); i++) {
            VariantInput in = variants.get(i);
            List<String> values = in.values().stream().map(v -> v == null ? "" : v.trim()).toList();
            if (values.size() != clean.size()) {
                throw ApiException.badRequest("Each variant needs a value for every option");
            }
            for (int j = 0; j < values.size(); j++) {
                final String val = values.get(j);
                final Product.Option option = clean.get(j);
                String match = option.values().stream().filter(a -> a.equalsIgnoreCase(val)).findFirst()
                        .orElseThrow(() -> ApiException.badRequest("\"" + val + "\" isn't a value of " + option.name()));
                values = replace(values, j, match); // normalise to the option's spelling
            }
            String title = String.join(" / ", values);
            if (!seen.add(title.toLowerCase())) {
                throw ApiException.badRequest("The variant " + title + " is listed twice");
            }
            BigDecimal effective = in.price() != null ? in.price() : req.price();
            BigDecimal compare = in.price() != null ? in.compareAtPrice() : req.compareAtPrice();
            if (compare != null && compare.compareTo(effective) <= 0) {
                throw ApiException.badRequest("Compare-at price for " + title + " must be higher than its price");
            }
            var v = in.id() != null && existing.containsKey(in.id()) ? existing.get(in.id()) : byTitle.get(title.toLowerCase());
            if (v == null || next.contains(v)) {
                v = new com.apex.commerce.catalog.ProductVariant();
                v.product = p;
            }
            v.setValues(values);
            v.price = in.price();
            v.compareAtPrice = in.price() != null ? in.compareAtPrice() : null;
            v.sku = in.sku() == null || in.sku().isBlank() ? null : in.sku().trim();
            v.stock = in.stock();
            v.imageUrl = in.imageUrl() == null || in.imageUrl().isBlank() ? null : in.imageUrl();
            v.position = i;
            next.add(v);
            total += in.stock();
        }
        p.options = clean;
        p.variants.retainAll(next);
        for (var v : next) {
            if (!p.variants.contains(v)) {
                p.variants.add(v);
            }
        }
        p.variants.sort(java.util.Comparator.comparingInt(v -> v.position));
        p.stock = total; // a product's stock is the total of its variants
    }

    private static List<String> replace(List<String> list, int index, String value) {
        List<String> copy = new ArrayList<>(list);
        copy.set(index, value);
        return copy;
    }

    private Product owned(Long id) {
        Store store = currentUser.store();
        Product p = Product.findById(id);
        if (p == null || !p.store.id.equals(store.id)) {
            throw ApiException.notFound("Product");
        }
        return p;
    }
}
