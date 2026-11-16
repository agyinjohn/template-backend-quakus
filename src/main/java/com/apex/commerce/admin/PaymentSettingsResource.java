package com.apex.commerce.admin;

import com.apex.commerce.auth.AppUser;
import com.apex.commerce.auth.CurrentUser;
import com.apex.commerce.common.ApiException;
import com.apex.commerce.common.SecretBox;
import com.apex.commerce.payments.PaystackClient;
import com.apex.commerce.store.Store;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** How a store gets paid. The Paystack secret key is write-only: it's never returned. */
@Path("/api/admin/store/payments")
@RolesAllowed(AppUser.ROLE_OWNER)
public class PaymentSettingsResource {

    public record PaymentSettings(
            boolean paystackConnected, String paystackMode, String paystackKeyHint, String webhookUrl,
            boolean bankTransferEnabled, String bankTransferInstructions, boolean cashOnDeliveryEnabled) {}

    public record PaymentSettingsUpdate(
            boolean bankTransferEnabled,
            @Size(max = 2000) String bankTransferInstructions,
            boolean cashOnDeliveryEnabled) {}

    public record PaystackConnect(@Size(max = 200) String secretKey) {}

    private static final Pattern SECRET_KEY = Pattern.compile("sk_(test|live)_[A-Za-z0-9]{20,}");

    @Inject
    CurrentUser currentUser;

    @Inject
    SecretBox secrets;

    @Inject
    PaystackClient paystack;

    @ConfigProperty(name = "app.public-url")
    String publicUrl;

    @GET
    public PaymentSettings get() {
        return view(currentUser.store());
    }

    @PUT
    @Transactional
    public PaymentSettings update(@Valid PaymentSettingsUpdate req) {
        Store s = Store.findById(currentUser.store().id);
        if (!req.bankTransferEnabled() && !req.cashOnDeliveryEnabled() && !s.paystackConnected()) {
            throw ApiException.badRequest("Keep at least one payment method on, or connect Paystack first");
        }
        if (req.bankTransferEnabled() && (req.bankTransferInstructions() == null || req.bankTransferInstructions().isBlank())) {
            throw ApiException.badRequest("Add bank transfer instructions (bank, account name and number) so shoppers know where to pay");
        }
        s.bankTransferEnabled = req.bankTransferEnabled();
        s.bankTransferInstructions = req.bankTransferInstructions() == null ? null : req.bankTransferInstructions().trim();
        s.cashOnDeliveryEnabled = req.cashOnDeliveryEnabled();
        return view(s);
    }

    /** Connects (or replaces) the store's Paystack account after checking the key with Paystack. */
    @PUT
    @Path("/paystack")
    @Transactional
    public PaymentSettings connect(@Valid PaystackConnect req) {
        String key = req.secretKey() == null ? "" : req.secretKey().trim();
        if (key.startsWith("pk_")) {
            throw ApiException.badRequest("That's your public key. Paste the secret key (it starts with sk_live_ or sk_test_).");
        }
        if (!SECRET_KEY.matcher(key).matches()) {
            throw ApiException.badRequest("Paste your Paystack secret key: it starts with sk_live_ or sk_test_");
        }
        try {
            paystack.validateKey(key);
        } catch (PaystackClient.PaystackException e) {
            throw e.status == 401
                    ? ApiException.badRequest("Paystack didn't accept this key. Copy it again from Paystack: Settings, API keys and webhooks.")
                    : new ApiException(Response.Status.BAD_GATEWAY, "Couldn't reach Paystack to check the key. Please try again.");
        }
        Store s = Store.findById(currentUser.store().id);
        s.paystackSecretKeyEnc = secrets.encrypt(key);
        s.paystackKeyHint = key.substring(0, key.indexOf('_', 3) + 1) + "…" + key.substring(key.length() - 4);
        return view(s);
    }

    @DELETE
    @Path("/paystack")
    @Transactional
    public PaymentSettings disconnect() {
        Store s = Store.findById(currentUser.store().id);
        if (!s.bankTransferEnabled && !s.cashOnDeliveryEnabled) {
            throw ApiException.badRequest("Turn on bank transfer or cash on delivery before disconnecting Paystack, so shoppers can still pay");
        }
        s.paystackSecretKeyEnc = null;
        s.paystackKeyHint = null;
        return view(s);
    }

    private PaymentSettings view(Store s) {
        String mode = s.paystackKeyHint == null ? null : s.paystackKeyHint.startsWith("sk_live") ? "live" : "test";
        return new PaymentSettings(s.paystackConnected(), mode, s.paystackKeyHint,
                publicUrl.replaceAll("/$", "") + "/api/public/stores/" + s.slug + "/payments/paystack-webhook",
                s.bankTransferEnabled, s.bankTransferInstructions, s.cashOnDeliveryEnabled);
    }
}
