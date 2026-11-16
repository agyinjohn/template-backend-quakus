package com.apex.commerce.mail;

import com.apex.commerce.orders.CustomerOrder;
import java.math.BigDecimal;
import java.util.List;

/**
 * Everything an order email needs, captured inside the checkout/status-change
 * transaction so the emails can be sent after commit without touching the database.
 */
@io.quarkus.qute.TemplateData
public record OrderEmail(
        Kind kind,
        String storeName, String storeSlug, String storeEmail, String storePhone, String ownerEmail,
        String number, Long orderId, CustomerOrder.Status status,
        String customerName, String customerEmail, String phone, String address, String city, String country,
        String notes, List<Line> items, BigDecimal total, String currency,
        String paymentMethod, boolean paid, String paymentChannel, String paymentInstructions) {

    public enum Kind { PLACED, STATUS_CHANGED }

    public record Line(String name, int quantity, BigDecimal unitPrice, BigDecimal lineTotal) {}

    public static OrderEmail of(Kind kind, CustomerOrder o) {
        var s = o.store;
        String owner = com.apex.commerce.auth.AppUser.<com.apex.commerce.auth.AppUser>find("store", s)
                .firstResultOptional().map(u -> u.email).orElse(null);
        return new OrderEmail(kind, s.name, s.slug, s.email, s.phone, owner,
                o.number, o.id, o.status, o.customerName, o.email, o.phone, o.address, o.city, o.country, o.notes,
                o.items.stream().map(i -> new Line(i.name, i.quantity, i.unitPrice, i.lineTotal)).toList(),
                o.total, o.currency, o.paymentMethod.label, o.isPaid(), channelLabel(o.paymentChannel),
                o.paymentMethod == CustomerOrder.PaymentMethod.BANK_TRANSFER && !o.isPaid() ? s.bankTransferInstructions : null);
    }

    static String channelLabel(String channel) {
        if (channel == null) {
            return null;
        }
        return switch (channel) {
            case "mobile_money" -> "mobile money";
            case "bank_transfer" -> "bank transfer";
            default -> channel.replace('_', ' ');
        };
    }

    /** Where the store owner hears about new orders: the store's contact email, else the owner's login. */
    public String ownerInbox() {
        return storeEmail != null && !storeEmail.isBlank() ? storeEmail : ownerEmail;
    }
}
