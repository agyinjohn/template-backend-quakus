package com.apex.commerce;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(FakePaystack.class)
class DeliveryTaxTest {

    @Inject
    MockMailbox mailbox;

    record Shop(String token, String slug, int productId) {}

    Shop shop() {
        var s = given().contentType(ContentType.JSON)
                .body(Map.of("fullName", "Ekow", "email", UUID.randomUUID() + "@d.test", "password", "secret123", "storeName", "Ekow Home"))
                .post("/api/auth/signup").then().statusCode(200).extract();
        String token = s.path("token");
        given().auth().oauth2(token).contentType(ContentType.JSON).body(Map.of("name", "Ekow Home", "currency", "GHS"))
                .put("/api/admin/store").then().statusCode(200);
        int pid = given().auth().oauth2(token).contentType(ContentType.JSON)
                .body(Map.of("name", "Clay Pot", "price", 100, "stock", 20)).post("/api/admin/products")
                .then().statusCode(201).extract().path("id");
        return new Shop(token, s.path("store.slug"), pid);
    }

    List<Integer> setupDelivery(Shop shop) {
        return given().auth().oauth2(shop.token()).contentType(ContentType.JSON).body(Map.of("options", List.of(
                        Map.of("name", "Accra", "description", "1–2 days", "price", 20, "freeOver", 500),
                        Map.of("name", "Pick up from our shop", "price", 0, "pickup", true))))
                .put("/api/admin/store/delivery").then().statusCode(200)
                .body("name", contains("Accra", "Pick up from our shop")).extract().path("id");
    }

    Map<String, Object> order(Shop shop, Integer deliveryId, int qty, String method) {
        Map<String, Object> m = new HashMap<>(Map.of("customerName", "Esi", "email", "esi-" + shop.slug() + "@d.test", "address", "4 Ring Rd", "city", "Accra",
                "paymentMethod", method, "items", List.of(Map.of("productId", shop.productId(), "quantity", qty))));
        if (deliveryId != null) m.put("deliveryOptionId", deliveryId);
        return m;
    }

    @Test
    void deliveryAndTaxesAreChargedAndRecorded() throws Exception {
        Shop shop = shop();
        List<Integer> ids = setupDelivery(shop);
        given().auth().oauth2(shop.token()).contentType(ContentType.JSON).body(Map.of("enabled", true, "included", false,
                        "applyToDelivery", false, "rates", List.of(Map.of("name", "VAT", "rate", 15), Map.of("name", "NHIL", "rate", 2.5))))
                .put("/api/admin/store/taxes").then().statusCode(200).body("rates.size()", is(2));
        given().get("/api/public/stores/" + shop.slug()).then()
                .body("deliveryOptions.name", contains("Accra", "Pick up from our shop"))
                .body("deliveryOptions[0].freeOver", is(500.0f)).body("taxesEnabled", is(true)).body("taxIncluded", is(false));

        // live quote: 2 × 100 + Accra 20, VAT 30 + NHIL 5 on the items
        given().contentType(ContentType.JSON)
                .body(Map.of("items", List.of(Map.of("productId", shop.productId(), "quantity", 2)), "deliveryOptionId", ids.get(0)))
                .post("/api/public/stores/" + shop.slug() + "/quote").then().statusCode(200)
                .body("subtotal", is(200.0f)).body("deliveryFee", is(20.0f)).body("taxTotal", is(35.0f)).body("total", is(255.0f))
                .body("taxes.name", contains("VAT", "NHIL"));
        // before a delivery option is chosen: items and taxes only
        given().contentType(ContentType.JSON).body(Map.of("items", List.of(Map.of("productId", shop.productId(), "quantity", 2))))
                .post("/api/public/stores/" + shop.slug() + "/quote").then().statusCode(200)
                .body("deliveryName", nullValue()).body("total", is(235.0f));

        // checkout must pick a delivery option, and is charged what the quote said
        given().contentType(ContentType.JSON).body(order(shop, null, 2, "CASH_ON_DELIVERY"))
                .post("/api/public/stores/" + shop.slug() + "/orders").then().statusCode(400).body("message", containsString("delivery option"));
        given().contentType(ContentType.JSON).body(order(shop, ids.get(0), 2, "CASH_ON_DELIVERY"))
                .post("/api/public/stores/" + shop.slug() + "/orders").then().statusCode(201).body("total", is(255.0f));
        given().auth().oauth2(shop.token()).get("/api/admin/orders").then()
                .body("items[0].deliveryName", is("Accra")).body("items[0].deliveryFee", is(20.0f))
                .body("items[0].taxTotal", is(35.0f)).body("items[0].taxes[0].amount", is(30.0f)).body("items[0].subtotal", is(200.0f));
        String to = "esi-" + shop.slug() + "@d.test";
        for (int i = 0; i < 100 && (mailbox.getMailsSentTo(to) == null || mailbox.getMailsSentTo(to).isEmpty()); i++) Thread.sleep(50);
        String html = mailbox.getMailsSentTo(to).get(0).getHtml();
        assertTrue(html.contains("Delivery: Accra") && html.contains("VAT (15%)") && html.contains("NHIL (2.5%)"), html);

        // free delivery from GHS 500 of items
        given().contentType(ContentType.JSON).body(order(shop, ids.get(0), 5, "CASH_ON_DELIVERY"))
                .post("/api/public/stores/" + shop.slug() + "/orders").then().statusCode(201).body("total", is(587.5f)); // 500 + 87.5 tax
    }

    @Test
    void pickupNeedsNoAddressAndPaystackChargesTheFullTotal() {
        Shop shop = shop();
        List<Integer> ids = setupDelivery(shop);
        given().auth().oauth2(shop.token()).contentType(ContentType.JSON).body(Map.of("secretKey", PaymentsTest.GOOD_KEY))
                .put("/api/admin/store/payments/paystack").then().statusCode(200);
        Map<String, Object> pickup = order(shop, ids.get(1), 1, "CASH_ON_DELIVERY");
        pickup.remove("address");
        pickup.remove("city");
        given().contentType(ContentType.JSON).body(pickup).post("/api/public/stores/" + shop.slug() + "/orders").then().statusCode(201);
        given().auth().oauth2(shop.token()).get("/api/admin/orders").then()
                .body("items[0].pickup", is(true)).body("items[0].address", nullValue());
        // home delivery still needs an address
        Map<String, Object> noAddress = order(shop, ids.get(0), 1, "CASH_ON_DELIVERY");
        noAddress.remove("address");
        given().contentType(ContentType.JSON).body(noAddress).post("/api/public/stores/" + shop.slug() + "/orders")
                .then().statusCode(400).body("message", containsString("delivery address"));
        // taxes included in prices: shown, not added
        given().auth().oauth2(shop.token()).contentType(ContentType.JSON).body(Map.of("enabled", true, "included", true,
                        "applyToDelivery", true, "rates", List.of(Map.of("name", "VAT", "rate", 25))))
                .put("/api/admin/store/taxes").then().statusCode(200);
        String number = given().contentType(ContentType.JSON).body(order(shop, ids.get(0), 1, "PAYSTACK"))
                .post("/api/public/stores/" + shop.slug() + "/orders").then().statusCode(201)
                .body("total", is(120.0f)).extract().path("number");
        String ref = given().auth().oauth2(shop.token()).get("/api/admin/orders").then().extract().path("items[0].paymentReference");
        assertTrue(ref.startsWith(number));
        assertEquals(12000, FakePaystack.TRANSACTIONS.get(ref).amount(), "Paystack is charged items + delivery");
        given().auth().oauth2(shop.token()).get("/api/admin/orders").then()
                .body("items[0].taxIncluded", is(true)).body("items[0].taxTotal", is(24.0f)); // 120 × 25/125
    }

    @Test
    void settingsValidationAndEditing() {
        Shop shop = shop();
        var auth = given().auth().oauth2(shop.token()).contentType(ContentType.JSON);
        auth.body(Map.of("options", List.of(Map.of("name", "Accra", "price", 1), Map.of("name", "accra", "price", 2))))
                .put("/api/admin/store/delivery").then().statusCode(400).body("message", containsString("called"));
        given().auth().oauth2(shop.token()).contentType(ContentType.JSON).body(Map.of("enabled", true, "rates", List.of()))
                .put("/api/admin/store/taxes").then().statusCode(400);
        given().auth().oauth2(shop.token()).contentType(ContentType.JSON)
                .body(Map.of("enabled", true, "rates", List.of(Map.of("name", "VAT", "rate", 150))))
                .put("/api/admin/store/taxes").then().statusCode(400);
        List<Integer> ids = setupDelivery(shop);
        // editing keeps ids; removing an option deletes it; empty list = no delivery charge
        given().auth().oauth2(shop.token()).contentType(ContentType.JSON).body(Map.of("options", List.of(
                        Map.of("id", ids.get(1), "name", "Shop pickup", "price", 0, "pickup", true))))
                .put("/api/admin/store/delivery").then().statusCode(200).body("id", contains(ids.get(1))).body("name", contains("Shop pickup"));
        given().auth().oauth2(shop.token()).contentType(ContentType.JSON).body(Map.of("options", List.of()))
                .put("/api/admin/store/delivery").then().statusCode(200).body("size()", is(0));
        given().contentType(ContentType.JSON).body(order(shop, null, 1, "CASH_ON_DELIVERY"))
                .post("/api/public/stores/" + shop.slug() + "/orders").then().statusCode(201).body("total", is(100.0f));
    }
}
