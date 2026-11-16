package com.apex.commerce.pricing;

import com.apex.commerce.store.Store;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Works out an order's delivery fee, taxes and total. Used for the live
 * checkout quote and for the order itself, so the shopper always pays exactly
 * what they were shown.
 */
public final class Pricing {

    private Pricing() {}

    public record TaxRate(String name, BigDecimal rate) {}

    public record TaxLine(String name, BigDecimal rate, BigDecimal amount) {}

    /**
     * @param taxIncluded true when item prices (and delivery) already include the taxes:
     *                    the tax lines then show how much of the total is tax, and nothing is added.
     */
    public record Quote(BigDecimal subtotal, String deliveryName, BigDecimal deliveryFee, boolean freeDelivery,
                        List<TaxLine> taxes, BigDecimal taxTotal, boolean taxIncluded, BigDecimal total) {}

    public static Quote quote(Store store, BigDecimal subtotal, DeliveryOption delivery) {
        BigDecimal fee = BigDecimal.ZERO;
        boolean free = false;
        if (delivery != null) {
            free = delivery.freeOver != null && subtotal.compareTo(delivery.freeOver) >= 0;
            fee = free ? BigDecimal.ZERO : delivery.price;
        }
        List<TaxRate> rates = store.taxesEnabled && store.taxRates != null ? store.taxRates : List.of();
        BigDecimal taxable = store.taxDelivery ? subtotal.add(fee) : subtotal;
        BigDecimal totalRate = rates.stream().map(TaxRate::rate).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<TaxLine> lines = new ArrayList<>();
        BigDecimal taxTotal = BigDecimal.ZERO;
        for (TaxRate r : rates) {
            BigDecimal amount;
            if (store.taxIncluded) {
                // the share of a tax-inclusive amount that is this tax: amount × rate / (100 + all rates)
                amount = taxable.multiply(r.rate()).divide(BigDecimal.valueOf(100).add(totalRate), 2, RoundingMode.HALF_UP);
            } else {
                amount = taxable.multiply(r.rate()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            }
            lines.add(new TaxLine(r.name(), r.rate(), amount));
            taxTotal = taxTotal.add(amount);
        }
        BigDecimal total = subtotal.add(fee).add(store.taxIncluded ? BigDecimal.ZERO : taxTotal);
        return new Quote(scale(subtotal), delivery == null ? null : delivery.name, scale(fee), free, lines,
                scale(taxTotal), store.taxIncluded, scale(total));
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
