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
            @NotEmpty @Size(max = 50) List<@Valid CheckoutItem> items,
            /** Defaults to the store's first available method. */
            CustomerOrder.PaymentMethod paymentMethod) {}

    /**
     * {@code confirmationEmail}: the address a confirmation was sent to, or null if none was sent (yet).
     * {@code paymentUrl}: for online payment, the Paystack page to send the shopper to.
     * {@code paymentInstructions}: for bank transfer, the store's instructions.
     */
    public record OrderConfirmation(String number, BigDecimal total, String currency, String status,
                                    String confirmationEmail, String paymentMethod, boolean paid,
                                    String paymentUrl, String paymentInstructions) {}

    public record VerifyRequest(@NotBlank @Size(max = 100) String reference) {}

    @jakarta.inject.Inject
    com.apex.commerce.payments.PaymentService payments;

    @jakarta.inject.Inject
    jakarta.enterprise.event.Event<com.apex.commerce.mail.OrderEmail> orderEvents;

    @jakarta.inject.Inject
    com.apex.commerce.mail.OrderMailer mailer;

    @jakarta.inject.Inject
    com.fasterxml.jackson.databind.ObjectMapper mapper;

    /**
     * Paystack webhook. Each store adds this URL in its own Paystack dashboard
     * (Settings, API keys and webhooks). Requests are authenticated by their
     * HMAC signature using the store's secret key.
     */
    @POST
    @Path("/payments/paystack-webhook")
    @jakarta.ws.rs.Consumes(jakarta.ws.rs.core.MediaType.WILDCARD)
    public Response paystackWebhook(@PathParam("slug") String slug, String body,
                                    @jakarta.ws.rs.HeaderParam("x-paystack-signature") String signature) {
        Store store = Store.findBySlug(slug).orElseThrow(() -> ApiException.notFound("Store"));
        return payments.webhook(store, body, signature, mapper)
                ? Response.ok().build()
                : Response.status(Response.Status.UNAUTHORIZED).build();
    }

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
        order.paymentMethod = choosePaymentMethod(store, req.paymentMethod());

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

        if (order.paymentMethod == CustomerOrder.PaymentMethod.PAYSTACK) {
            // stock stays reserved while the shopper pays; emails go out once payment is confirmed
            String url;
            try {
                url = payments.start(order);
            } catch (com.apex.commerce.payments.PaystackClient.PaystackException e) {
                // roll back the order and stock reservation: nothing was charged
                throw new ApiException(Response.Status.BAD_GATEWAY,
                        "Online payment isn't available right now. Please choose another payment method or try again. ("
                                + e.getMessage() + ")");
            }
            return Response.status(Response.Status.CREATED).entity(confirmation(order, null, url)).build();
        }
        // emails go out after the transaction commits (see OrderMailer)
        orderEvents.fire(com.apex.commerce.mail.OrderEmail.of(com.apex.commerce.mail.OrderEmail.Kind.PLACED, order));
        return Response.status(Response.Status.CREATED)
                .entity(confirmation(order, mailer.isEnabled() ? order.email : null, null))
                .build();
    }

    /**
     * Called by the storefront when the shopper comes back from Paystack. Asks
     * Paystack whether the payment succeeded; safe to call more than once.
     */
    @POST
    @Path("/payments/verify")
    public OrderConfirmation verifyPayment(@PathParam("slug") String slug, @Valid VerifyRequest req) {
        Store store = published(slug);
        CustomerOrder order;
        try {
            order = payments.confirm(store, req.reference());
        } catch (com.apex.commerce.payments.PaystackClient.PaystackException e) {
            throw new ApiException(Response.Status.BAD_GATEWAY, "Couldn't confirm the payment with Paystack. Please refresh in a moment.");
        }
        if (order == null) {
            throw ApiException.notFound("Order");
        }
        return confirmation(order, order.isPaid() && mailer.isEnabled() ? order.email : null, null);
    }

    private OrderConfirmation confirmation(CustomerOrder o, String emailedTo, String paymentUrl) {
        String instructions = o.paymentMethod == CustomerOrder.PaymentMethod.BANK_TRANSFER ? o.store.bankTransferInstructions : null;
        return new OrderConfirmation(o.number, o.total, o.currency, o.status.name(), emailedTo,
                o.paymentMethod.name(), o.isPaid(), paymentUrl, instructions);
    }

    private static CustomerOrder.PaymentMethod choosePaymentMethod(Store store, CustomerOrder.PaymentMethod requested) {
        List<CustomerOrder.PaymentMethod> available = StoreView.paymentMethods(store);
        if (available.isEmpty()) {
            throw ApiException.badRequest("This store isn't accepting orders right now (no payment methods are set up)");
        }
        if (requested == null) {
            return available.get(0);
        }
        if (!available.contains(requested)) {
            throw ApiException.badRequest("This store doesn't accept " + requested.label.toLowerCase());
        }
        return requested;
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
