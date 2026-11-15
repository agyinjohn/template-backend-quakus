package com.apex.commerce.auth;

import com.apex.commerce.common.ApiException;
import com.apex.commerce.common.Slugs;
import com.apex.commerce.store.Store;
import com.apex.commerce.store.StoreView;
import io.quarkus.elytron.security.common.BcryptUtil;
import io.smallrye.jwt.build.Jwt;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import java.time.Duration;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Path("/api/auth")
public class AuthResource {

    public record SignupRequest(
            @NotBlank @Size(max = 120) String fullName,
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8, max = 100, message = "must be at least 8 characters") String password,
            @NotBlank @Size(max = 80) String storeName) {}

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {}

    public record UserView(Long id, String fullName, String email, String role) {
        static UserView of(AppUser u) {
            return new UserView(u.id, u.fullName, u.email, u.role);
        }
    }

    public record Session(String token, UserView user, StoreView store) {}

    /** Store slugs that would clash with frontend routes. */
    private static final Set<String> RESERVED = Set.of(
            "admin", "api", "login", "signup", "onboarding", "templates", "media", "s", "www", "app");

    @Inject
    CurrentUser currentUser;

    @ConfigProperty(name = "app.jwt.issuer")
    String issuer;

    @ConfigProperty(name = "app.jwt.ttl-hours")
    long ttlHours;

    @POST
    @Path("/signup")
    @Transactional
    public Session signup(@Valid SignupRequest req) {
        String email = req.email().trim().toLowerCase();
        if (AppUser.findByEmail(email).isPresent()) {
            throw ApiException.conflict("An account with this email already exists");
        }
        Store store = new Store();
        store.name = req.storeName().trim();
        store.slug = Slugs.unique(store.name, s -> RESERVED.contains(s) || Store.findBySlug(s).isPresent());
        store.email = email;
        store.persist();

        AppUser user = new AppUser();
        user.email = email;
        user.fullName = req.fullName().trim();
        user.passwordHash = BcryptUtil.bcryptHash(req.password());
        user.store = store;
        user.persist();
        return session(user);
    }

    @POST
    @Path("/login")
    public Session login(@Valid LoginRequest req) {
        AppUser user = AppUser.findByEmail(req.email())
                .filter(u -> BcryptUtil.matches(req.password(), u.passwordHash))
                .orElseThrow(() -> ApiException.unauthorized("Email or password is incorrect"));
        return session(user);
    }

    @GET
    @Path("/me")
    @RolesAllowed(AppUser.ROLE_OWNER)
    public Session me() {
        AppUser user = currentUser.user();
        return new Session(null, UserView.of(user), StoreView.of(user.store));
    }

    private Session session(AppUser user) {
        String token = Jwt.issuer(issuer)
                .subject(String.valueOf(user.id))
                .upn(user.email)
                .groups(Set.of(user.role))
                .claim("storeId", user.store.id)
                .expiresIn(Duration.ofHours(ttlHours))
                .sign();
        return new Session(token, UserView.of(user), StoreView.of(user.store));
    }
}
