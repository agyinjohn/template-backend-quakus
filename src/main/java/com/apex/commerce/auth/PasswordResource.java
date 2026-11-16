package com.apex.commerce.auth;

import com.apex.commerce.common.ApiException;
import com.apex.commerce.mail.AccountEmail;
import io.quarkus.elytron.security.common.BcryptUtil;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Path("/api/auth/password")
public class PasswordResource {

    public record ForgotRequest(@NotBlank @Email String email) {}

    public record ResetRequest(
            @NotBlank @Size(max = 200) String token,
            @NotBlank @Size(min = 8, max = 100, message = "must be at least 8 characters") String password) {}

    public record ChangeRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 8, max = 100, message = "must be at least 8 characters") String newPassword) {}

    static final Duration TOKEN_TTL = Duration.ofHours(1);
    /** Reset emails per account per hour; more requests are silently ignored. */
    static final int MAX_REQUESTS_PER_HOUR = 3;
    private static final SecureRandom RANDOM = new SecureRandom();

    @Inject
    Event<AccountEmail> emails;

    @Inject
    Sessions sessions;

    @Inject
    CurrentUser currentUser;

    @ConfigProperty(name = "app.frontend-url")
    String frontendUrl;

    /**
     * Emails a reset link if the account exists. The response is the same
     * either way, so this can't be used to find out who has an account.
     */
    @POST
    @Path("/forgot")
    @Transactional
    public Response forgot(@Valid ForgotRequest req) {
        AppUser.findByEmail(req.email()).ifPresent(user -> {
            Instant hourAgo = Instant.now().minus(Duration.ofHours(1));
            if (PasswordResetToken.count("user = ?1 and createdAt > ?2", user, hourAgo) >= MAX_REQUESTS_PER_HOUR) {
                return;
            }
            // only the newest link works
            PasswordResetToken.update("usedAt = ?1 where user = ?2 and usedAt is null", Instant.now(), user);
            byte[] raw = new byte[32];
            RANDOM.nextBytes(raw);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
            PasswordResetToken t = new PasswordResetToken();
            t.user = user;
            t.tokenHash = PasswordResetToken.hash(token);
            t.expiresAt = Instant.now().plus(TOKEN_TTL);
            t.persist();
            emails.fire(new AccountEmail(AccountEmail.Kind.PASSWORD_RESET, user.email, user.fullName, user.store.name,
                    frontendUrl.replaceAll("/$", "") + "/reset-password?token=" + token, TOKEN_TTL.toMinutes()));
        });
        return Response.accepted(Map.of("message",
                "If an account exists for that email, we've sent a link to reset the password.")).build();
    }

    /** Sets a new password from a reset link and logs the owner in. Other sessions are signed out. */
    @POST
    @Path("/reset")
    @Transactional
    public AuthResource.Session reset(@Valid ResetRequest req) {
        PasswordResetToken t = PasswordResetToken.<PasswordResetToken>find("tokenHash", PasswordResetToken.hash(req.token()))
                .firstResult();
        if (t == null || !t.usable()) {
            throw ApiException.badRequest("This reset link has expired or was already used. Please request a new one.");
        }
        t.usedAt = Instant.now();
        AppUser user = t.user;
        setPassword(user, req.password());
        PasswordResetToken.update("usedAt = ?1 where user = ?2 and usedAt is null", Instant.now(), user);
        return sessions.create(user);
    }

    /** For a logged-in owner. Returns a fresh login token; every other session is signed out. */
    @POST
    @Path("/change")
    @RolesAllowed(AppUser.ROLE_OWNER)
    @Transactional
    public AuthResource.Session change(@Valid ChangeRequest req) {
        AppUser user = AppUser.findById(currentUser.user().id);
        if (!BcryptUtil.matches(req.currentPassword(), user.passwordHash)) {
            throw ApiException.badRequest("Your current password is incorrect");
        }
        setPassword(user, req.newPassword());
        return sessions.create(user);
    }

    private void setPassword(AppUser user, String password) {
        user.passwordHash = BcryptUtil.bcryptHash(password);
        // tokens carry whole seconds; truncating keeps the new session (issued now) valid
        user.passwordChangedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        emails.fire(new AccountEmail(AccountEmail.Kind.PASSWORD_CHANGED, user.email, user.fullName, user.store.name, null, 0));
    }
}
