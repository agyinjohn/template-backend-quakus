package com.apex.commerce.marketing;

import com.apex.commerce.auth.AppUser;
import com.apex.commerce.auth.CurrentUser;
import com.apex.commerce.catalog.CatalogViews.Page;
import com.apex.commerce.common.ApiException;
import com.apex.commerce.store.Store;
import io.quarkus.panache.common.Sort;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.stream.Collectors;

/** Admin → Subscribers. (Sign-ups arrive through {@link NewsletterSignupResource}.) */
@Path("/api/admin/subscribers")
public class SubscriberResource {

    public record SignUp(@NotBlank @Email @Size(max = 200) String email, @Size(max = 40) String source) {}

    public record SubscriberView(Long id, String email, String source, Instant createdAt) {
        static SubscriberView of(Subscriber s) {
            return new SubscriberView(s.id, s.email, s.source, s.createdAt);
        }
    }

    @Inject
    CurrentUser currentUser;

    @GET
    @RolesAllowed(AppUser.ROLE_OWNER)
    public Page<SubscriberView> list(@QueryParam("q") String q, @QueryParam("page") @DefaultValue("0") int page,
                                     @QueryParam("size") @DefaultValue("50") int size) {
        size = Math.min(Math.max(size, 1), 200);
        var query = q == null || q.isBlank()
                ? Subscriber.<Subscriber>find("store", Sort.descending("createdAt", "id"), currentUser.store())
                : Subscriber.<Subscriber>find("store = ?1 and email like ?2", Sort.descending("createdAt", "id"),
                        currentUser.store(), "%" + q.trim().toLowerCase() + "%");
        return new Page<>(query.page(page, size).list().stream().map(SubscriberView::of).toList(), query.count(), page, size);
    }

    /** All subscribers as CSV, for importing into an email tool (Mailchimp, Brevo…). */
    @GET
    @Path("/export.csv")
    @Produces("text/csv")
    @RolesAllowed(AppUser.ROLE_OWNER)
    public Response csv() {
        Store store = currentUser.store();
        String rows = Subscriber.<Subscriber>find("store", Sort.ascending("createdAt", "id"), store).stream()
                .map(s -> csvCell(s.email) + "," + csvCell(s.source) + "," + DateTimeFormatter.ISO_INSTANT.format(s.createdAt))
                .collect(Collectors.joining("\n"));
        return Response.ok("email,source,subscribed_at\n" + rows + (rows.isEmpty() ? "" : "\n"))
                .header("Content-Disposition", "attachment; filename=\"" + store.slug + "-subscribers.csv\"")
                .build();
    }

    /** Remove someone, e.g. when they ask to unsubscribe. */
    @DELETE
    @Path("/{id}")
    @RolesAllowed(AppUser.ROLE_OWNER)
    @Transactional
    public Response delete(@PathParam("id") Long id) {
        Subscriber s = Subscriber.findById(id);
        if (s == null || !s.store.id.equals(currentUser.store().id)) {
            throw ApiException.notFound("Subscriber");
        }
        s.delete();
        return Response.noContent().build();
    }

    /** Quotes a CSV value, and stops spreadsheet apps treating it as a formula. */
    public static String csvCell(String v) {
        if (v == null) {
            return "";
        }
        String safe = v.matches("^[=+\\-@\\t\\r].*") ? "'" + v : v;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
}
