package com.apex.commerce.mail;

import com.apex.commerce.orders.CustomerOrder;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.reactive.ReactiveMailer;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Currency;
import java.util.Locale;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Sends order emails once the order is safely committed. Sending is
 * asynchronous and failures are only logged: a mail outage never blocks
 * or rolls back a checkout.
 */
@ApplicationScoped
public class OrderMailer {

    private static final Logger LOG = Logger.getLogger(OrderMailer.class);

    private static final Map<CustomerOrder.Status, String[]> STATUS_COPY = Map.of(
            CustomerOrder.Status.PAID, new String[] {"Payment received", "We've received your payment and are preparing your order."},
            CustomerOrder.Status.SHIPPED, new String[] {"Your order is on its way", "Good news: your order has been shipped."},
            CustomerOrder.Status.DELIVERED, new String[] {"Your order has been delivered", "Your order has been delivered. We hope you love it!"},
            CustomerOrder.Status.CANCELLED, new String[] {"Your order was cancelled", "Your order has been cancelled. If you've already paid, the store will arrange a refund."});

    @Inject
    ReactiveMailer mailer;

    @Location("emails/order-placed-customer.html")
    Template placedCustomer;

    @Location("emails/order-placed-owner.html")
    Template placedOwner;

    @Location("emails/order-status.html")
    Template statusChanged;

    @ConfigProperty(name = "app.mail.enabled")
    boolean enabled;

    @ConfigProperty(name = "app.frontend-url")
    String frontendUrl;

    @ConfigProperty(name = "quarkus.mailer.from")
    String from;

    public boolean isEnabled() {
        return enabled;
    }

    void onOrderEvent(@Observes(during = TransactionPhase.AFTER_SUCCESS) OrderEmail e) {
        if (!enabled) {
            return;
        }
        String base = frontendUrl.replaceAll("/$", "");
        var data = Map.<String, Object>of(
                "o", e,
                "total", money(e.total(), e.currency()),
                "lines", e.items().stream().map(l -> Map.of(
                        "name", l.variant() == null ? l.name() : l.name() + " (" + l.variant() + ")", "quantity", l.quantity(),
                        "unit", money(l.unitPrice(), e.currency()), "line", money(l.lineTotal(), e.currency()))).toList(),
                "storeUrl", base + "/s/" + e.storeSlug() + "/",
                "adminUrl", base + "/admin#/orders/" + e.orderId());
        if (e.kind() == OrderEmail.Kind.PLACED) {
            send(toShopper(e, "Order " + e.number() + " confirmed: " + e.storeName(), render(placedCustomer, data)));
            if (e.ownerInbox() != null) {
                send(Mail.withHtml(e.ownerInbox(), "New order " + e.number() + " (" + data.get("total") + ")",
                        render(placedOwner, data)).setReplyTo(e.customerEmail()));
            }
        } else {
            String[] copy = STATUS_COPY.get(e.status());
            if (copy == null) {
                return;
            }
            var withCopy = new java.util.HashMap<>(data);
            withCopy.put("heading", copy[0]);
            withCopy.put("message", copy[1]);
            send(toShopper(e, copy[0] + ": order " + e.number(), render(statusChanged, withCopy)));
        }
    }

    /**
     * Shopper emails show the store's name as the sender (sent from the platform's
     * address), and replies go to the store.
     */
    private Mail toShopper(OrderEmail e, String subject, String html) {
        Mail mail = Mail.withHtml(e.customerEmail(), subject, html).setFrom(fromAs(e.storeName()));
        if (e.ownerInbox() != null) {
            mail.setReplyTo(e.ownerInbox());
        }
        return mail;
    }

    /** "Store Name" <platform address>, with the name quoted so commas, quotes or brackets are safe. */
    String fromAs(String displayName) {
        var m = java.util.regex.Pattern.compile("<([^>]+)>").matcher(from);
        String address = m.find() ? m.group(1) : from.trim();
        String name = displayName.replaceAll("[\\r\\n]", " ").replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + name + "\" <" + address + ">";
    }

    private void send(Mail mail) {
        mailer.send(mail).subscribe().with(
                ok -> LOG.debugf("Sent '%s' to %s", mail.getSubject(), mail.getTo()),
                err -> LOG.errorf(err, "Could not send '%s' to %s", mail.getSubject(), mail.getTo()));
    }

    private static String render(Template t, Map<String, Object> data) {
        var instance = t.instance();
        data.forEach(instance::data);
        return instance.render();
    }

    static String money(BigDecimal amount, String currency) {
        try {
            NumberFormat f = NumberFormat.getCurrencyInstance(Locale.US);
            f.setCurrency(Currency.getInstance(currency));
            return f.format(amount);
        } catch (IllegalArgumentException ex) {
            return currency + " " + amount.setScale(2, java.math.RoundingMode.HALF_UP);
        }
    }
}
