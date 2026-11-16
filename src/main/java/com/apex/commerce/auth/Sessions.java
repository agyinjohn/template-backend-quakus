package com.apex.commerce.auth;

import com.apex.commerce.store.StoreView;
import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Issues login tokens. */
@ApplicationScoped
public class Sessions {

    @ConfigProperty(name = "app.jwt.issuer")
    String issuer;

    @ConfigProperty(name = "app.jwt.ttl-hours")
    long ttlHours;

    public AuthResource.Session create(AppUser user) {
        String token = Jwt.issuer(issuer)
                .subject(String.valueOf(user.id))
                .upn(user.email)
                .groups(Set.of(user.role))
                .claim("storeId", user.store.id)
                .expiresIn(Duration.ofHours(ttlHours))
                .sign();
        return new AuthResource.Session(token, AuthResource.UserView.of(user), StoreView.of(user.store));
    }
}
