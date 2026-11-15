package com.apex.commerce.admin;

import com.apex.commerce.auth.AppUser;
import com.apex.commerce.auth.CurrentUser;
import com.apex.commerce.catalog.SampleData;
import com.apex.commerce.common.ApiException;
import com.apex.commerce.store.Store;
import com.apex.commerce.store.StoreView;
import com.apex.commerce.templates.TemplateCatalog;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import java.util.Map;

@Path("/api/admin/store")
@RolesAllowed(AppUser.ROLE_OWNER)
public class StoreAdminResource {

    public record StoreUpdate(
            @NotBlank @Size(max = 80) String name,
            @Size(max = 160) String tagline,
            String logoUrl,
            @Email String email,
            @Size(max = 40) String phone,
            @Size(max = 300) String address,
            @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter currency code like USD") String currency,
            @Size(max = 2000) String announcement,
            String facebookUrl,
            String instagramUrl,
            String twitterUrl,
            Boolean published) {}

    public record TemplateChoice(@NotBlank String templateId, boolean addSampleProducts) {}

    @Inject
    CurrentUser currentUser;

    @Inject
    SampleData sampleData;

    @GET
    public StoreView get() {
        return StoreView.of(currentUser.store());
    }

    @PUT
    @Transactional
    public StoreView update(@Valid StoreUpdate req) {
        Store s = Store.findById(currentUser.store().id);
        s.name = req.name().trim();
        s.tagline = req.tagline();
        s.logoUrl = req.logoUrl();
        s.email = req.email();
        s.phone = req.phone();
        s.address = req.address();
        if (req.currency() != null) {
            s.currency = req.currency();
        }
        s.announcement = req.announcement();
        s.facebookUrl = req.facebookUrl();
        s.instagramUrl = req.instagramUrl();
        s.twitterUrl = req.twitterUrl();
        if (req.published() != null) {
            s.published = req.published();
        }
        return StoreView.of(s);
    }

    /** Pick or change the storefront design. Can be called any time. */
    @PUT
    @Path("/template")
    @Transactional
    public StoreView chooseTemplate(@Valid TemplateChoice req) {
        if (!TemplateCatalog.exists(req.templateId())) {
            throw ApiException.badRequest("Unknown template: " + req.templateId());
        }
        Store s = Store.findById(currentUser.store().id);
        s.templateId = req.templateId();
        if (req.addSampleProducts()) {
            sampleData.seed(s, req.templateId());
        }
        return StoreView.of(s);
    }

    @POST
    @Path("/sample-data")
    @Transactional
    public Map<String, Integer> addSampleData() {
        Store s = Store.findById(currentUser.store().id);
        String template = s.templateId == null ? TemplateCatalog.DEFAULT_ID : s.templateId;
        return Map.of("created", sampleData.seed(s, template));
    }
}
