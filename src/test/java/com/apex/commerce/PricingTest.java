package com.apex.commerce;

import static org.junit.jupiter.api.Assertions.*;

import com.apex.commerce.pricing.DeliveryOption;
import com.apex.commerce.pricing.Pricing;
import com.apex.commerce.store.Store;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The money math, without the database. */
class PricingTest {

    static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    static Store store(boolean taxes, boolean included, boolean onDelivery, String... rates) {
        Store s = new Store();
        s.taxesEnabled = taxes;
        s.taxIncluded = included;
        s.taxDelivery = onDelivery;
        s.taxRates = new java.util.ArrayList<>();
        for (int i = 0; i < rates.length; i += 2) {
            s.taxRates.add(new Pricing.TaxRate(rates[i], d(rates[i + 1])));
        }
        return s;
    }

    static DeliveryOption delivery(String price, String freeOver) {
        DeliveryOption o = new DeliveryOption();
        o.name = "Accra";
        o.price = d(price);
        o.freeOver = freeOver == null ? null : d(freeOver);
        return o;
    }

    @Test
    void noDeliveryNoTax() {
        var q = Pricing.quote(store(false, true, false), d("150"), null);
        assertEquals(d("150.00"), q.total());
        assertNull(q.deliveryName());
        assertTrue(q.taxes().isEmpty());
    }

    @Test
    void deliveryFeeAndFreeThreshold() {
        var o = delivery("20", "500");
        assertEquals(d("320.00"), Pricing.quote(store(false, true, false), d("300"), o).total());
        var free = Pricing.quote(store(false, true, false), d("500"), o);
        assertEquals(d("0.00"), free.deliveryFee());
        assertTrue(free.freeDelivery());
        assertEquals(d("500.00"), free.total());
    }

    @Test
    void taxesAddedOnTop() {
        // Ghana-style: VAT 15% + NHIL 2.5% + GETFund 2.5% on GHS 200 of items, delivery not taxed
        var q = Pricing.quote(store(true, false, false, "VAT", "15", "NHIL", "2.5", "GETFund", "2.5"), d("200"), delivery("20", null));
        assertEquals(List.of(d("30.00"), d("5.00"), d("5.00")), q.taxes().stream().map(Pricing.TaxLine::amount).toList());
        assertEquals(d("40.00"), q.taxTotal());
        assertEquals(d("260.00"), q.total()); // 200 + 20 + 40
        // same, but delivery is taxed too: taxes on 220
        var withDelivery = Pricing.quote(store(true, false, true, "VAT", "15", "NHIL", "2.5", "GETFund", "2.5"), d("200"), delivery("20", null));
        assertEquals(d("44.00"), withDelivery.taxTotal());
        assertEquals(d("264.00"), withDelivery.total());
    }

    @Test
    void taxesIncludedInPrices() {
        // GHS 240 with 20% total tax included: 200 net + 40 tax; nothing is added
        var q = Pricing.quote(store(true, true, false, "VAT", "15", "NHIL", "2.5", "GETFund", "2.5"), d("240"), null);
        assertEquals(List.of(d("30.00"), d("5.00"), d("5.00")), q.taxes().stream().map(Pricing.TaxLine::amount).toList());
        assertTrue(q.taxIncluded());
        assertEquals(d("240.00"), q.total());
    }

    @Test
    void roundsEachTaxToTheCent() {
        var q = Pricing.quote(store(true, false, false, "VAT", "15"), d("9.99"), null);
        assertEquals(d("1.50"), q.taxTotal()); // 1.4985
        assertEquals(d("11.49"), q.total());
    }
}
