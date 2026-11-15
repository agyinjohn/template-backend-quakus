package com.apex.commerce.store;

import com.apex.commerce.catalog.CatalogViews.CategoryView;
import com.apex.commerce.catalog.CatalogViews.Page;
import com.apex.commerce.catalog.CatalogViews.ProductView;
import com.apex.commerce.catalog.Category;
import com.apex.commerce.catalog.Product;
import com.apex.commerce.common.ApiException;
import com.apex.commerce.orders.CustomerOrder;
import com.apex.commerce.orders.OrderItem;
import io.quarkus.panache.common.Sort;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Public, read-only API the storefront renders from, plus checkout. */
@Path("/api/public/stores/{slug}")
public class StorefrontResource {

    public record CheckoutItem(@NotNull Long productId, @Min(1) @Max(100) int quantity) {}

    public record CheckoutRequest(
            @NotBlank @Size(max = 120) String customerName,
            @NotBlank @Email String email,
            @Size(max = 40) String phone,
            @NotBlank @Size(max = 300) String address,
            @NotBlank @Size(max = 100) String city,
            @Size(max = 100) String country,
            @Size(max = 2000) String notes,
            @NotEmpty @Size(max = 50) List<@Valid CheckoutItem> items) {}

    public record OrderConfirmation(String number, BigDecimal total, String currency, String status) {}

    private static final SecureRandom RANDOM = new SecureRandom();

    @GET
    public StoreView store(@PathParam("slug") String slug) {
        return StoreView.of(published(slug));
    }

    @GET
    @Path("/categories")
    public List<CategoryView> categories(@PathParam("slug") String slug) {
        return Category.<Category>find("store", Sort.ascending("position", "name"), published(slug))
                .list().stream().map(CategoryView::of).toList();
    }

    @GET
    @Path("/products")
    public Page<ProductView> products(@PathParam("slug") String slug,
                                      @QueryParam("category") String categorySlug,
                                      @QueryParam("q") String q,
                                      @QueryParam("featured") Boolean featured,
                                      @QueryParam("sort") @DefaultValue("newest") String sort,
                                      @QueryParam("page") @DefaultValue("0") int page,
                                      @QueryParam("size") @DefaultValue("24") int size) {
        Store store = published(slug);
        StringBuilder where = new StringBuilder("store = :store and status = :active");
        Map<String, Object> params = new HashMap<>();
        params.put("store", store);
        params.put("active", Product.Status.ACTIVE);
        if (categorySlug != null && !categorySlug.isBlank()) {
            where.append(" and category.slug = :cat");
            params.put("cat", categorySlug);
        }
        if (q != null && !q.isBlank()) {
            where.append(" and lower(name) like :q");
            params.put("q", "%" + q.toLowerCase().trim() + "%");
        }
        if (Boolean.TRUE.equals(featured)) {
            where.append(" and featured = true");
        }
        Sort order = switch (sort) {
            case "price-asc" -> Sort.ascending("price");
            case "price-desc" -> Sort.descending("price");
            case "name" -> Sort.ascending("name");
            default -> Sort.descending("createdAt", "id");
        };
        size = Math.min(Math.max(size, 1), 100);
        var query = Product.<Product>find(where.toString(), order, params);
        return new Page<>(query.page(page, size).list().stream().map(ProductView::of).toList(),
                query.count(), page, size);
    }

    @GET
    @Path("/products/{productSlug}")
    public ProductView product(@PathParam("slug") String slug, @PathParam("productSlug") String productSlug) {
        Product p = Product.<Product>find("store = ?1 and slug = ?2 and status = ?3",
                published(slug), productSlug, Product.Status.ACTIVE).firstResult();
        if (p == null) {
            throw ApiException.notFound("Product");
        }
        return ProductView.of(p);
    }

    /**
     * Places an order. Prices always come from the database, never the client,
     * and stock is decremented atomically so two shoppers can't buy the last item.
     */
    @POST
    @Path("/orders")
    @Transactional
    public Response checkout(@PathParam("slug") String slug, @Valid CheckoutRequest req) {
        Store store = published(slug);
        CustomerOrder order = new CustomerOrder();
        order.store = store;
        order.currency = store.currency;
        order.customerName = req.customerName().trim();
        order.email = req.email().trim().toLowerCase();
        order.phone = req.phone();
        order.address = req.address();
        order.city = req.city();
        order.country = req.country();
        order.notes = req.notes();

        Map<Long, Integer> quantities = new HashMap<>();
        for (CheckoutItem i : req.items()) {
            quantities.merge(i.productId(), i.quantity(), Integer::sum);
        }
        BigDecimal subtotal = BigDecimal.ZERO;
        for (var entry : quantities.entrySet()) {
            Product p = Product.findById(entry.getKey());
            if (p == null || !p.store.id.equals(store.id) || p.status != Product.Status.ACTIVE) {
                throw ApiException.badRequest("Product " + entry.getKey() + " is not available");
            }
            int qty = entry.getValue();
            int updated = Product.update("stock = stock - ?1 where id = ?2 and stock >= ?1", qty, p.id);
            if (updated == 0) {
                throw ApiException.conflict("Only " + p.stock + " left of " + p.name);
            }
            OrderItem item = new OrderItem();
            item.order = order;
            item.productId = p.id;
            item.name = p.name;
            item.imageUrl = p.images.isEmpty() ? null : p.images.get(0);
            item.unitPrice = p.price;
            item.quantity = qty;
            item.lineTotal = p.price.multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);
            order.items.add(item);
            subtotal = subtotal.add(item.lineTotal);
        }
        order.subtotal = subtotal;
        order.total = subtotal; // shipping/tax rules can be added per store later
        order.number = newOrderNumber();
        order.persist();
        return Response.status(Response.Status.CREATED)
                .entity(new OrderConfirmation(order.number, order.total, order.currency, order.status.name()))
                .build();
    }

    private static String newOrderNumber() {
        String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String suffix;
        do {
            suffix = Integer.toString(10000 + RANDOM.nextInt(90000));
        } while (CustomerOrder.count("number", "AX-" + date + "-" + suffix) > 0);
        return "AX-" + date + "-" + suffix;
    }

    private static Store published(String slug) {
        return Store.findBySlug(slug).filter(s -> s.published)
                .orElseThrow(() -> ApiException.notFound("Store"));
    }
}
