package com.apex.commerce.store;

import com.apex.commerce.orders.CustomerOrder;
import com.apex.commerce.templates.TemplateCatalog;
import java.util.ArrayList;
import java.util.List;

public record StoreView(
        Long id, String name, String slug, String templateId, TemplateCatalog.Template template,
        String tagline, String logoUrl, String email, String phone, String address, String currency,
        String announcement, String facebookUrl, String instagramUrl, String twitterUrl, boolean published,
        List<CustomerOrder.PaymentMethod> paymentMethods,
        List<DeliveryOptionView> deliveryOptions, boolean taxesEnabled, boolean taxIncluded,
        List<com.apex.commerce.pricing.Pricing.TaxRate> taxRates,
        List<Store.Banner> banners,
        HomepageContent homepage) {

    /** Everything on the home page below the hero that the owner can change. */
    public record HomepageContent(java.util.Map<String, Store.Promo> promos, List<Store.Feature> features,
                                  boolean featuresHidden, List<Store.Testimonial> testimonials) {
        public static HomepageContent of(Store s) {
            return new HomepageContent(s.promos == null ? java.util.Map.of() : java.util.Map.copyOf(s.promos),
                    s.features == null ? null : List.copyOf(s.features), s.featuresHidden,
                    s.testimonials == null ? List.of() : List.copyOf(s.testimonials));
        }
    }

    public record DeliveryOptionView(Long id, String name, String description, java.math.BigDecimal price,
                                     java.math.BigDecimal freeOver, boolean pickup) {
        public static DeliveryOptionView of(com.apex.commerce.pricing.DeliveryOption o) {
            return new DeliveryOptionView(o.id, o.name, o.description, o.price, o.freeOver, o.pickup);
        }
    }

    public static StoreView of(Store s) {
        return new StoreView(s.id, s.name, s.slug, s.templateId,
                s.templateId == null ? null : TemplateCatalog.find(s.templateId).orElse(null),
                s.tagline, s.logoUrl, s.email, s.phone, s.address, s.currency, s.announcement,
                s.facebookUrl, s.instagramUrl, s.twitterUrl, s.published, paymentMethods(s),
                com.apex.commerce.pricing.DeliveryOption.<com.apex.commerce.pricing.DeliveryOption>list(
                        "store", io.quarkus.panache.common.Sort.ascending("position", "id"), s)
                        .stream().map(DeliveryOptionView::of).toList(),
                s.taxesEnabled, s.taxIncluded,
                s.taxesEnabled && s.taxRates != null ? List.copyOf(s.taxRates) : List.of(),
                s.banners == null ? List.of() : List.copyOf(s.banners),
                HomepageContent.of(s));
    }

    /** Payment methods shoppers can choose, online first. */
    public static List<CustomerOrder.PaymentMethod> paymentMethods(Store s) {
        List<CustomerOrder.PaymentMethod> out = new ArrayList<>();
        if (s.paystackConnected()) {
            out.add(CustomerOrder.PaymentMethod.PAYSTACK);
        }
        if (s.bankTransferEnabled) {
            out.add(CustomerOrder.PaymentMethod.BANK_TRANSFER);
        }
        if (s.cashOnDeliveryEnabled) {
            out.add(CustomerOrder.PaymentMethod.CASH_ON_DELIVERY);
        }
        return out;
    }
}
