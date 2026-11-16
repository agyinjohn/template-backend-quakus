package com.apex.commerce.admin;

import com.apex.commerce.auth.AppUser;
import com.apex.commerce.auth.CurrentUser;
import com.apex.commerce.common.ApiException;
import com.apex.commerce.store.Store;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Admin → Homepage: hero banners, promo blocks, feature strip and testimonials. */
@Path("/api/admin/store")
@RolesAllowed(AppUser.ROLE_OWNER)
public class HomepageResource {

    public record BannerInput(
            @Size(max = 80) String eyebrow,
            @NotBlank(message = "is required for every banner") @Size(max = 120) String title,
            @Size(max = 300) String text,
            @Size(max = 40) String buttonLabel,
            @Size(max = 500) String link,
            @Size(max = 1000) String imageUrl,
            @Size(max = 500) String videoUrl) {}

    public record Banners(@Size(max = 5, message = "can have at most 5 banners") List<@Valid BannerInput> banners) {}

    /** shop · category:slug · product:slug · https://… · /path */
    private static final Pattern LINK = Pattern.compile("shop|(category|product):[a-z0-9-]+|https?://\\S+|/\\S*");
    private static final Pattern VIDEO = Pattern.compile("https://(www\\.)?(youtube\\.com|youtu\\.be|vimeo\\.com)/\\S+");

    @Inject
    CurrentUser currentUser;

    @GET
    @Path("/banners")
    public List<Store.Banner> get() {
        List<Store.Banner> b = currentUser.store().banners;
        return b == null ? List.of() : b;
    }

    @PUT
    @Path("/banners")
    @Transactional
    public List<Store.Banner> set(@Valid Banners req) {
        List<Store.Banner> out = new ArrayList<>();
        for (BannerInput in : req.banners() == null ? List.<BannerInput>of() : req.banners()) {
            String link = blank(in.link()) ? null : in.link().trim();
            if (link != null && !LINK.matcher(link).matches()) {
                throw ApiException.badRequest("Banner links must be a page in your store or start with https://");
            }
            String video = blank(in.videoUrl()) ? null : in.videoUrl().trim();
            if (video != null && !VIDEO.matcher(video).matches()) {
                throw ApiException.badRequest("Video links must be YouTube or Vimeo links");
            }
            out.add(new Store.Banner(trim(in.eyebrow()), in.title().trim(), trim(in.text()), trim(in.buttonLabel()),
                    link, trim(in.imageUrl()), video));
        }
        Store s = Store.findById(currentUser.store().id);
        s.banners = out;
        return out;
    }

    // ------------------------------------------------------------ promo blocks, features, testimonials
    public record PromoInput(@Size(max = 80) String eyebrow, @Size(max = 120) String title, @Size(max = 400) String text,
                             @Size(max = 40) String buttonLabel, @Size(max = 500) String link,
                             @Size(max = 1000) String imageUrl, Boolean hidden) {}

    public record FeatureInput(@NotBlank @Size(max = 40) String title, @Size(max = 80) String text) {}

    public record TestimonialInput(@NotBlank @Size(max = 60) String name, @Size(max = 60) String role,
                                   @NotBlank @Size(max = 400) String quote,
                                   @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(5) Integer rating) {}

    public record ContentInput(
            @Size(max = 100) java.util.Map<@jakarta.validation.constraints.Pattern(regexp = "[a-z0-9-]+/[a-z0-9-]+") String, @Valid PromoInput> promos,
            @Size(max = 6, message = "can have at most 6 items") List<@Valid FeatureInput> features,
            boolean featuresHidden,
            @Size(max = 12, message = "can have at most 12 reviews") List<@Valid TestimonialInput> testimonials) {}

    @GET
    @Path("/homepage")
    public com.apex.commerce.store.StoreView.HomepageContent content() {
        return com.apex.commerce.store.StoreView.HomepageContent.of(currentUser.store());
    }

    /**
     * Saves the home page's promo blocks, feature strip and testimonials. Promo
     * entries replace only the blocks sent; send null for a key to reset it to the template.
     */
    @PUT
    @Path("/homepage")
    @Transactional
    public com.apex.commerce.store.StoreView.HomepageContent setContent(@Valid ContentInput req) {
        Store s = Store.findById(currentUser.store().id);
        if (req.promos() != null) {
            var promos = new java.util.HashMap<>(s.promos == null ? java.util.Map.<String, Store.Promo>of() : s.promos);
            for (var e : req.promos().entrySet()) {
                PromoInput in = e.getValue();
                if (in == null) {
                    promos.remove(e.getKey());
                    continue;
                }
                String link = blank(in.link()) ? null : in.link().trim();
                if (link != null && !LINK.matcher(link).matches()) {
                    throw ApiException.badRequest("Button links must be a page in your store or start with https://");
                }
                promos.put(e.getKey(), new Store.Promo(in.eyebrow(), in.title(), in.text(), in.buttonLabel(), link,
                        trim(in.imageUrl()), Boolean.TRUE.equals(in.hidden()) ? Boolean.TRUE : null));
            }
            s.promos = promos;
        }
        s.features = req.features() == null ? null
                : new ArrayList<>(req.features().stream().map(f -> new Store.Feature(f.title().trim(), trim(f.text()))).toList());
        s.featuresHidden = req.featuresHidden();
        s.testimonials = new ArrayList<>((req.testimonials() == null ? List.<TestimonialInput>of() : req.testimonials()).stream()
                .map(t -> new Store.Testimonial(t.name().trim(), trim(t.role()), t.quote().trim(), t.rating() == null ? 5 : t.rating()))
                .toList());
        return com.apex.commerce.store.StoreView.HomepageContent.of(s);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String trim(String s) {
        return blank(s) ? null : s.trim();
    }
}
