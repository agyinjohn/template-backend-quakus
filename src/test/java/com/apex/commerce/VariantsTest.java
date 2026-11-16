package com.apex.commerce;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
class VariantsTest {

    @Inject
    MockMailbox mailbox;

    String signup() {
        return given().contentType(ContentType.JSON)
                .body(Map.of("fullName", "Kojo", "email", UUID.randomUUID() + "@v.test", "password", "secret123", "storeName", "Kojo Wear"))
                .post("/api/auth/signup").then().statusCode(200).extract().path("token");
    }

    static Map<String, Object> variant(List<String> values, Object price, int stock) {
        Map<String, Object> m = new HashMap<>();
        m.put("values", values);
        m.put("stock", stock);
        if (price != null) m.put("price", price);
        return m;
    }

    static Map<String, Object> tee(List<Map<String, Object>> variants) {
        return Map.of("name", "Adinkra Tee", "price", 100, "stock", 0,
                "options", List.of(Map.of("name", "Size", "values", List.of("S", "M", "L")),
                        Map.of("name", "Colour", "values", List.of("Black", "White"))),
                "variants", variants);
    }

    @Test
    void createEditAndBuyVariants() throws Exception {
        String token = signup();
        String slug = given().auth().oauth2(token).get("/api/auth/me").then().extract().path("store.slug");
        var created = given().auth().oauth2(token).contentType(ContentType.JSON)
                .body(tee(List.of(variant(List.of("S", "Black"), null, 2), variant(List.of("m", "black"), null, 1),
                        variant(List.of("L", "White"), 120, 0))))
                .post("/api/admin/products").then().statusCode(201)
                .body("stock", is(3)).body("options.size()", is(2))
                .body("variants.title", contains("S / Black", "M / Black", "L / White")) // values normalised to the option's spelling
                .body("variants[2].price", is(120)).body("variants[0].price", nullValue())
                .extract();
        int productId = created.path("id");
        int sBlack = created.path("variants[0].id");
        int mBlack = created.path("variants[1].id");

        // editing keeps variant ids (matched by id or by values) and recomputes total stock
        given().auth().oauth2(token).contentType(ContentType.JSON)
                .body(tee(List.of(variant(List.of("S", "Black"), null, 5), variant(List.of("M", "Black"), 110, 1))))
                .put("/api/admin/products/" + productId).then().statusCode(200)
                .body("stock", is(6)).body("variants.size()", is(2))
                .body("variants[0].id", is(sBlack)).body("variants[1].id", is(mBlack));

        var publicProduct = given().get("/api/public/stores/" + slug + "/products/adinkra-tee").then().statusCode(200)
                .body("variants.size()", is(2)).extract();
        assertEquals(110f, ((Number) publicProduct.path("variants[1].price")).floatValue());

        String url = "/api/public/stores/" + slug + "/orders";
        Map<String, Object> base = Map.of("customerName", "Akua", "email", "akua@v.test", "address", "1 Road", "city", "Tema",
                "paymentMethod", "CASH_ON_DELIVERY");
        // a variant must be chosen
        given().contentType(ContentType.JSON).body(with(base, List.of(Map.of("productId", productId, "quantity", 1))))
                .post(url).then().statusCode(400).body("message", containsString("choose size and colour"));
        // variant price is used and its own stock is checked
        given().contentType(ContentType.JSON)
                .body(with(base, List.of(Map.of("productId", productId, "variantId", mBlack, "quantity", 2))))
                .post(url).then().statusCode(409).body("message", is("Only 1 left of Adinkra Tee (M / Black)"));
        given().contentType(ContentType.JSON).body(with(base, List.of(
                        Map.of("productId", productId, "variantId", mBlack, "quantity", 1),
                        Map.of("productId", productId, "variantId", sBlack, "quantity", 2))))
                .post(url).then().statusCode(201).body("total", is(310.0f)); // 110 + 2 × 100
        given().contentType(ContentType.JSON)
                .body(with(base, List.of(Map.of("productId", productId, "variantId", mBlack, "quantity", 1))))
                .post(url).then().statusCode(409).body("message", is("Adinkra Tee (M / Black) is sold out"));

        var order = given().auth().oauth2(token).get("/api/admin/orders").then()
                .body("items[0].items.variantTitle", containsInAnyOrder("M / Black", "S / Black")).extract();
        given().auth().oauth2(token).get("/api/admin/products/" + productId).then()
                .body("stock", is(3)).body("variants[0].stock", is(3)).body("variants[1].stock", is(0));

        // cancelling puts the variant stock back
        int orderId = order.path("items[0].id");
        given().auth().oauth2(token).contentType(ContentType.JSON).body(Map.of("status", "CANCELLED"))
                .patch("/api/admin/orders/" + orderId).then().statusCode(200);
        given().auth().oauth2(token).get("/api/admin/products/" + productId).then()
                .body("stock", is(6)).body("variants[0].stock", is(5)).body("variants[1].stock", is(1));

        // emails name the variant
        for (int i = 0; i < 100 && (mailbox.getMailsSentTo("akua@v.test") == null || mailbox.getMailsSentTo("akua@v.test").isEmpty()); i++) Thread.sleep(50);
        assertTrue(mailbox.getMailsSentTo("akua@v.test").get(0).getHtml().contains("Adinkra Tee (M / Black)"));

        // removing all options turns it back into a simple product
        given().auth().oauth2(token).contentType(ContentType.JSON)
                .body(Map.of("name", "Adinkra Tee", "price", 100, "stock", 7))
                .put("/api/admin/products/" + productId).then().statusCode(200)
                .body("variants.size()", is(0)).body("options.size()", is(0)).body("stock", is(7));
    }

    @Test
    void invalidOptionsAreRejected() {
        String token = signup();
        var auth = given().auth().oauth2(token).contentType(ContentType.JSON);
        auth.body(tee(List.of())).post("/api/admin/products").then().statusCode(400).body("message", containsString("at least one variant"));
        given().auth().oauth2(token).contentType(ContentType.JSON).body(tee(List.of(variant(List.of("XL", "Black"), null, 1))))
                .post("/api/admin/products").then().statusCode(400).body("message", containsString("\"XL\" isn't a value of Size"));
        given().auth().oauth2(token).contentType(ContentType.JSON)
                .body(tee(List.of(variant(List.of("S", "Black"), null, 1), variant(List.of("S", "BLACK"), null, 1))))
                .post("/api/admin/products").then().statusCode(400).body("message", containsString("listed twice"));
        given().auth().oauth2(token).contentType(ContentType.JSON).body(tee(List.of(variant(List.of("S"), null, 1))))
                .post("/api/admin/products").then().statusCode(400).body("message", containsString("every option"));
        given().auth().oauth2(token).contentType(ContentType.JSON)
                .body(Map.of("name", "X", "price", 1, "stock", 1, "options",
                        List.of(Map.of("name", "Size", "values", List.of("S")), Map.of("name", "size", "values", List.of("M"))),
                        "variants", List.of(variant(List.of("S", "M"), null, 1))))
                .post("/api/admin/products").then().statusCode(400).body("message", containsString("used twice"));
    }

    static Map<String, Object> with(Map<String, Object> base, List<Map<String, Object>> items) {
        Map<String, Object> m = new HashMap<>(base);
        m.put("items", items);
        return m;
    }
}
