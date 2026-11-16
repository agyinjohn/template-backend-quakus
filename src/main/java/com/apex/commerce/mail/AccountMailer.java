package com.apex.commerce.mail;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.reactive.ReactiveMailer;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class AccountMailer {

    private static final Logger LOG = Logger.getLogger(AccountMailer.class);

    @Inject
    ReactiveMailer mailer;

    @Location("emails/password-reset.html")
    Template reset;

    @Location("emails/password-changed.html")
    Template changed;

    @ConfigProperty(name = "app.mail.enabled")
    boolean enabled;

    @ConfigProperty(name = "app.frontend-url")
    String frontendUrl;

    void on(@Observes(during = TransactionPhase.AFTER_SUCCESS) AccountEmail e) {
        if (!enabled) {
            return;
        }
        boolean isReset = e.kind() == AccountEmail.Kind.PASSWORD_RESET;
        String html = (isReset ? reset : changed).data("e", e)
                .data("loginUrl", frontendUrl.replaceAll("/$", "") + "/login")
                .data("forgotUrl", frontendUrl.replaceAll("/$", "") + "/forgot-password")
                .render();
        Mail mail = Mail.withHtml(e.to(), isReset ? "Reset your Apex Commerce password" : "Your Apex Commerce password was changed", html);
        mailer.send(mail).subscribe().with(
                ok -> LOG.debugf("Sent %s email", e.kind()),
                err -> LOG.errorf(err, "Could not send %s email", e.kind()));
    }
}
