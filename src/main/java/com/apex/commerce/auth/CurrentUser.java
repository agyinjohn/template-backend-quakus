package com.apex.commerce.auth;

import com.apex.commerce.common.ApiException;
import com.apex.commerce.store.Store;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

/** The signed-in store owner and their store (the tenant every admin query is scoped to). */
@RequestScoped
public class CurrentUser {

    @Inject
    JsonWebToken jwt;

    private AppUser user;

    public AppUser user() {
        if (user == null) {
            if (jwt == null || jwt.getSubject() == null) {
                throw ApiException.unauthorized("Not signed in");
            }
            user = AppUser.findById(Long.valueOf(jwt.getSubject()));
            if (user == null) {
                throw ApiException.unauthorized("Account no longer exists");
            }
            // a password change/reset signs out every session that existed before it
            if (user.passwordChangedAt != null && jwt.getIssuedAtTime() < user.passwordChangedAt.getEpochSecond()) {
                user = null;
                throw ApiException.unauthorized("Your password was changed. Please log in again.");
            }
        }
        return user;
    }

    public Store store() {
        return user().store;
    }
}
