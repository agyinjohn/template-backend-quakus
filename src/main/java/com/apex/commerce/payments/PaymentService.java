package com.apex.commerce.payments;

import com.apex.commerce.catalog.Product;
import com.apex.commerce.common.SecretBox;
import com.apex.commerce.mail.OrderEmail;
import com.apex.commerce.orders.CustomerOrder;
import com.apex.commerce.orders.OrderItem;
import com.apex.commerce.store.Store;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Online payments through each store's own Paystack account.
 *
 * <p>Flow: checkout creates the order (stock reserved, unpaid) and a Paystack
 * transaction; the shopper pays on Paystack's page and returns to the store,
 * which asks us to verify. Paystack's webhook confirms the same payment in
 * case the shopper never returns. Whichever arrives first marks the order paid;
 * the confirmation emails are sent exactly once, at that moment.
 */
@ApplicationScoped
public class PaymentService {

    private static final Logger LOG = Logger.getLogger(PaymentService.class);

    @Inject
    PaystackClient paystack;

    @Inject
    SecretBox secrets;

    @Inject
    Event<OrderEmail> orderEvents;

    @ConfigProperty(name = "app.frontend-url")
    String frontendUrl;

    @ConfigProperty(name = "app.payment.expiry-minutes")
    long expiryMinutes;

    public String secretKey(Store store) {
        if (!store.paystackConnected()) {
            throw new IllegalStateException("Store " + store.slug + " has no Paystack account connected");
        }
        return secrets.decrypt(store.paystackSecretKeyEnc);
    }

    /** Creates the Paystack transaction for a just-created order and returns the payment page URL. */
    public String start(CustomerOrder order) {
        Store store = order.store;
        // unguessable: the shopper's return link carries it, and it can release the order's stock
        order.paymentReference = order.number + "-" + randomToken(10);
        String callback = frontendUrl.replaceAll("/$", "") + "/s/" + store.slug + "/checkout/complete";
        return paystack.initialize(secretKey(store), order.email, order.total, order.currency, order.paymentReference,
                callback, Map.of("orderNumber", order.number, "store", store.slug)).authorizationUrl();
    }

    /** Paystack statuses meaning the shopper didn't pay and won't with this transaction. */
    private static final java.util.Set<String> UNPAID_FINAL = java.util.Set.of("failed", "abandoned", "reversed");

    /**
     * Called when the shopper returns from Paystack. Marks the order paid if it
     * was; if Paystack says the payment failed or was abandoned, cancels the
     * attempt right away so its reserved stock is free for the shopper's retry.
     * Payments still in progress (e.g. awaiting mobile-money approval) are left
     * for the webhook or the expiry job. Safe to call repeatedly.
     */
    @Transactional
    public CustomerOrder confirm(Store store, String reference) {
        CustomerOrder order = CustomerOrder.find("store = ?1 and paymentReference = ?2", store, reference).firstResult();
        if (order == null || order.isPaid() || !order.isAwaitingPayment()) {
            return order;
        }
        PaystackClient.Verification v = paystack.verify(secretKey(store), reference);
        apply(order, v);
        if (!order.isPaid() && UNPAID_FINAL.contains(v.status())) {
            release(order, "Payment not completed (shopper returned from Paystack without paying).");
        }
        return CustomerOrder.findById(order.id);
    }

    private static void release(CustomerOrder order, String note) {
        for (OrderItem item : order.items) {
            com.apex.commerce.catalog.Inventory.release(item.productId, item.variantId, item.quantity);
        }
        order.status = CustomerOrder.Status.CANCELLED;
        order.notes = (order.notes == null ? "" : order.notes + "\n") + note;
    }

    private static String randomToken(int length) {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        var rnd = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    /**
     * Paystack webhook. Returns false if the signature doesn't match the store's
     * key (the request didn't come from that store's Paystack account).
     */
    @Transactional
    public boolean webhook(Store store, String rawBody, String signature, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        if (!store.paystackConnected() || signature == null || !validSignature(secretKey(store), rawBody, signature)) {
            return false;
        }
        try {
            var event = mapper.readTree(rawBody);
            if (!"charge.success".equals(event.path("event").asText())) {
                return true;
            }
            var v = PaystackClient.verification(event.path("data"));
            CustomerOrder order = CustomerOrder.find("store = ?1 and paymentReference = ?2", store, v.reference()).firstResult();
            if (order != null && !order.isPaid()) {
                apply(order, v);
            }
            return true;
        } catch (java.io.IOException e) {
            LOG.warnf("Unreadable Paystack webhook for store %s", store.slug);
            return true;
        }
    }

    static boolean validSignature(String secretKey, String body, String signature) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            byte[] expected = HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)))
                    .getBytes(StandardCharsets.UTF_8);
            return MessageDigest.isEqual(expected, signature.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            return false;
        }
    }

    /** Marks the order paid if Paystack says the full amount was paid in the right currency. */
    private void apply(CustomerOrder order, PaystackClient.Verification v) {
        if (!v.successful()) {
            return;
        }
        long expected = PaystackClient.toMinorUnits(order.total);
        if (v.amount() != expected || !order.currency.equalsIgnoreCase(v.currency())) {
            LOG.errorf("Paystack amount mismatch for order %s: paid %d %s, expected %d %s",
                    order.number, v.amount(), v.currency(), expected, order.currency);
            return;
        }
        boolean wasCancelled = order.status == CustomerOrder.Status.CANCELLED;
        // conditional update: if the webhook and the shopper's return race, only one wins
        int won = CustomerOrder.update(
                "paidAt = ?1, status = ?2, paymentChannel = ?3 where id = ?4 and paidAt is null",
                Instant.now(), CustomerOrder.Status.PAID, v.channel(), order.id);
        if (won == 0) {
            return;
        }
        CustomerOrder.getEntityManager().refresh(order);
        if (wasCancelled) {
            // paid after the unpaid order expired: take the stock back if we still can
            boolean short_ = false;
            for (OrderItem item : order.items) {
                if (item.productId != null
                        && !com.apex.commerce.catalog.Inventory.reserve(item.productId, item.variantId, item.quantity)) {
                    short_ = true;
                }
            }
            order.notes = (order.notes == null ? "" : order.notes + "\n")
                    + "Paid after the payment window expired." + (short_ ? " Some items are out of stock: check before shipping." : "");
        }
        orderEvents.fire(OrderEmail.of(OrderEmail.Kind.PLACED, order));
    }

    /**
     * Cancels online-payment orders left unpaid, releasing their stock. Each one
     * is checked with Paystack first in case the payment went through but the
     * shopper never came back and the webhook was missed.
     */
    @Scheduled(every = "5m", delayed = "1m", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void expireUnpaid() {
        for (Long id : staleOrderIds()) {
            try {
                expire(id);
            } catch (RuntimeException e) {
                LOG.warnf(e, "Could not check unpaid order %d", id);
            }
        }
    }

    @Transactional
    public List<Long> staleOrderIds() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(expiryMinutes));
        return CustomerOrder.<CustomerOrder>find(
                "paymentMethod = ?1 and paidAt is null and status = ?2 and createdAt < ?3",
                CustomerOrder.PaymentMethod.PAYSTACK, CustomerOrder.Status.PENDING, cutoff)
                .list().stream().map(o -> o.id).toList();
    }

    @Transactional
    public void expire(Long id) {
        CustomerOrder order = CustomerOrder.findById(id);
        if (order == null || !order.isAwaitingPayment()) {
            return;
        }
        if (order.store.paystackConnected()) {
            try {
                apply(order, paystack.verify(secretKey(order.store), order.paymentReference));
                if (order.isPaid()) {
                    return;
                }
            } catch (PaystackClient.PaystackException e) {
                // e.g. "Transaction reference not found": the shopper never paid
            }
        }
        release(order, "Cancelled automatically: payment not completed.");
    }
}
