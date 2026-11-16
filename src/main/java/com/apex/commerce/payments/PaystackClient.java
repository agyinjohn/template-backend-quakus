package com.apex.commerce.payments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Minimal Paystack API client. Every call uses the secret key of the store being
 * paid, because each store connects its own Paystack account.
 * Docs: https://paystack.com/docs/api/transaction/
 */
@ApplicationScoped
public class PaystackClient {

    /** Paystack rejected the request (bad key, unsupported currency...). */
    public static class PaystackException extends RuntimeException {
        public final int status;

        public PaystackException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    public record Initialized(String authorizationUrl, String reference) {}

    /** Result of verifying a transaction. {@code amount} is in the currency's minor unit (e.g. pesewas). */
    public record Verification(String status, long amount, String currency, String reference, String channel) {
        public boolean successful() {
            return "success".equals(status);
        }
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "app.paystack.base-url")
    String baseUrl;

    /** Checks a secret key works by listing one transaction. */
    public void validateKey(String secretKey) {
        call("GET", "/transaction?perPage=1", secretKey, null);
    }

    public Initialized initialize(String secretKey, String email, BigDecimal amount, String currency,
                                  String reference, String callbackUrl, Map<String, Object> metadata) {
        JsonNode data = call("POST", "/transaction/initialize", secretKey, Map.of(
                "email", email,
                "amount", toMinorUnits(amount),
                "currency", currency,
                "reference", reference,
                "callback_url", callbackUrl,
                "metadata", metadata)).path("data");
        return new Initialized(data.path("authorization_url").asText(), data.path("reference").asText(reference));
    }

    public Verification verify(String secretKey, String reference) {
        JsonNode data = call("GET", "/transaction/verify/" + URLEncoder.encode(reference, StandardCharsets.UTF_8),
                secretKey, null).path("data");
        return verification(data);
    }

    public static Verification verification(JsonNode data) {
        return new Verification(data.path("status").asText(), data.path("amount").asLong(),
                data.path("currency").asText(), data.path("reference").asText(), data.path("channel").asText(null));
    }

    /** Paystack amounts are integers in the minor unit (pesewas, kobo, cents). */
    public static long toMinorUnits(BigDecimal amount) {
        return amount.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
    }

    private JsonNode call(String method, String path, String secretKey, Object body) {
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(baseUrl.replaceAll("/$", "") + path))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + secretKey)
                    .header("Accept", "application/json");
            if (body == null) {
                req.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                req.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            }
            HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode json = res.body() == null || res.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(res.body());
            if (res.statusCode() >= 300 || !json.path("status").asBoolean(false)) {
                throw new PaystackException(res.statusCode(), json.path("message").asText("Paystack request failed"));
            }
            return json;
        } catch (IOException e) {
            throw new PaystackException(502, "Could not reach Paystack: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PaystackException(502, "Interrupted while calling Paystack");
        }
    }
}
