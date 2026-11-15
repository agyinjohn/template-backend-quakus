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
            @Min(0) @Max(5) Integer rating) {}

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

    private Product owned(Long id) {
        Store store = currentUser.store();
        Product p = Product.findById(id);
        if (p == null || !p.store.id.equals(store.id)) {
            throw ApiException.notFound("Product");
        }
        return p;
    }
}
