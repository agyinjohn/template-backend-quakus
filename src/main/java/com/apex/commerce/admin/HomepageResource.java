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

/** Admin → Homepage: the hero banners at the top of the store's home page. */
@Path("/api/admin/store/banners")
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
    public List<Store.Banner> get() {
        List<Store.Banner> b = currentUser.store().banners;
        return b == null ? List.of() : b;
    }

    @PUT
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

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String trim(String s) {
        return blank(s) ? null : s.trim();
    }
}
