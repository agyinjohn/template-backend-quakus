package com.apex.commerce.store;

import com.apex.commerce.templates.TemplateCatalog;

public record StoreView(
        Long id, String name, String slug, String templateId, TemplateCatalog.Template template,
        String tagline, String logoUrl, String email, String phone, String address, String currency,
        String announcement, String facebookUrl, String instagramUrl, String twitterUrl, boolean published) {

    public static StoreView of(Store s) {
        return new StoreView(s.id, s.name, s.slug, s.templateId,
                s.templateId == null ? null : TemplateCatalog.find(s.templateId).orElse(null),
                s.tagline, s.logoUrl, s.email, s.phone, s.address, s.currency, s.announcement,
                s.facebookUrl, s.instagramUrl, s.twitterUrl, s.published);
    }
}
