package com.apex.commerce.marketing;

import com.apex.commerce.common.ApiException;
import com.apex.commerce.marketing.SubscriberResource.SignUp;
import com.apex.commerce.store.Store;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import java.util.Map;

@Path("/api/public/stores/{slug}/subscribe")
public class NewsletterSignupResource {

    /**
     * Newsletter sign-up from the storefront. Signing up again is fine (no
     * duplicate, same answer), so the response never reveals who is subscribed.
     */
    @POST
        @Transactional
    public Map<String, String> subscribe(@PathParam("slug") String slug, @Valid SignUp req) {
        Store store = Store.findBySlug(slug).filter(s -> s.published).orElseThrow(() -> ApiException.notFound("Store"));
        String email = req.email().trim().toLowerCase();
        if (Subscriber.count("store = ?1 and email = ?2", store, email) == 0) {
            Subscriber s = new Subscriber();
            s.store = store;
            s.email = email;
            s.source = req.source() == null || req.source().isBlank() ? null : req.source().trim();
            s.persist();
        }
        return Map.of("message", "Thanks for subscribing to " + store.name + "!");
    }

}
