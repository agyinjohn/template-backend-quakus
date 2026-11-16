package com.apex.commerce;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stand-in for api.paystack.co, run as its own HTTP server during tests (like
 * the real one, it's a separate service). Accepts secret keys starting with
 * "sk_test_good"; any other key gets 401, like Paystack.
 */
public class FakePaystack implements QuarkusTestResourceLifecycleManager {

    public record Txn(String key, long amount, String currency, String email, String callbackUrl) {}

    public static final Map<String, Txn> TRANSACTIONS = new ConcurrentHashMap<>();
    /** Override the verify outcome for a reference: "success" (default), "failed", "abandoned", or "amount:123". */
    public static final Map<String, String> OUTCOMES = new ConcurrentHashMap<>();
    public static volatile boolean down = false;

    private static final ObjectMapper JSON = new ObjectMapper();
    private HttpServer server;

    @Override
    public Map<String, String> start() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        server.createContext("/", this::handle);
        server.start();
        return Map.of("app.paystack.base-url", "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Override
    public void stop() {
        if (server != null) server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        String key = auth != null && auth.startsWith("Bearer ") ? auth.substring(7) : "";
        if (down) {
            send(ex, 503, Map.of("status", false, "message", "Service unavailable"));
        } else if (!key.startsWith("sk_test_good")) {
            send(ex, 401, Map.of("status", false, "message", "Invalid key"));
        } else if (path.equals("/transaction") && ex.getRequestMethod().equals("GET")) {
            send(ex, 200, Map.of("status", true, "data", List.of()));
        } else if (path.equals("/transaction/initialize")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = JSON.readValue(ex.getRequestBody(), Map.class);
            String currency = (String) body.get("currency");
            if (!List.of("GHS", "NGN", "USD").contains(currency)) {
                send(ex, 400, Map.of("status", false, "message", "Currency not supported by merchant"));
                return;
            }
            String ref = (String) body.get("reference");
            TRANSACTIONS.put(ref, new Txn(key, ((Number) body.get("amount")).longValue(), currency,
                    (String) body.get("email"), (String) body.get("callback_url")));
            send(ex, 200, Map.of("status", true, "message", "Authorization URL created", "data",
                    Map.of("authorization_url", "https://checkout.paystack.test/" + ref, "access_code", "ac_" + ref, "reference", ref)));
        } else if (path.startsWith("/transaction/verify/")) {
            String ref = path.substring("/transaction/verify/".length());
            Txn t = TRANSACTIONS.get(ref);
            if (t == null || !t.key().equals(key)) {
                send(ex, 400, Map.of("status", false, "message", "Transaction reference not found"));
                return;
            }
            String outcome = OUTCOMES.getOrDefault(ref, "success");
            long amount = outcome.startsWith("amount:") ? Long.parseLong(outcome.substring(7)) : t.amount();
            send(ex, 200, Map.of("status", true, "data", Map.of(
                    "status", outcome.startsWith("amount:") ? "success" : outcome, "reference", ref,
                    "amount", amount, "currency", t.currency(), "channel", "mobile_money")));
        } else {
            send(ex, 404, Map.of("status", false, "message", "Not found"));
        }
    }

    private static void send(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}
