package com.apex.commerce;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ApiFlowTest {

    record Owner(String token, String slug) {}

    static Owner signup(String storeName) {
        var res = given().contentType(ContentType.JSON)
                .body(Map.of("fullName", "Ama Owner", "email", UUID.randomUUID() + "@shop.test",
                        "password", "secret123", "storeName", storeName))
                .post("/api/auth/signup")
                .then().statusCode(200)
                .body("token", notNullValue())
                .body("store.templateId", nullValue())
                .extract();
        return new Owner(res.path("token"), res.path("store.slug"));
    }

    static io.restassured.specification.RequestSpecification as(Owner o) {
        return given().auth().oauth2(o.token()).contentType(ContentType.JSON);
    }

    @Test
    void signupLoginAndMe() {
        String email = UUID.randomUUID() + "@shop.test";
        given().contentType(ContentType.JSON)
                .body(Map.of("fullName", "Kofi", "email", email, "password", "secret123", "storeName", "Kofi's Kicks"))
                .post("/api/auth/signup").then().statusCode(200).body("store.slug", startsWith("kofi-s-kicks"));
        given().contentType(ContentType.JSON)
                .body(Map.of("fullName", "Kofi", "email", email, "password", "secret123", "storeName", "Other"))
                .post("/api/auth/signup").then().statusCode(409);
        given().contentType(ContentType.JSON).body(Map.of("email", email, "password", "wrongpass"))
                .post("/api/auth/login").then().statusCode(401);
        String token = given().contentType(ContentType.JSON).body(Map.of("email", email, "password", "secret123"))
                .post("/api/auth/login").then().statusCode(200).extract().path("token");
        given().auth().oauth2(token).get("/api/auth/me").then().statusCode(200)
                .body("user.email", is(email)).body("store.name", is("Kofi's Kicks"));
    }

    @Test
    void signupValidation() {
        given().contentType(ContentType.JSON)
                .body(Map.of("fullName", "", "email", "nope", "password", "short", "storeName", "X"))
                .post("/api/auth/signup").then().statusCode(400)
                .body("fields.email", notNullValue()).body("fields.password", notNullValue());
    }

    @Test
    void adminRequiresLogin() {
        given().get("/api/admin/products").then().statusCode(401);
    }

    @Test
    void chooseAndChangeTemplateWithSampleData() {
        given().get("/api/templates").then().statusCode(200).body("size()", is(6)).body("[0].id", is("ecomas-01"));
        given().get("/api/templates/ecomas-03/sample").then().statusCode(200)
                .body("categories.size()", is(3)).body("products[0].images[0]", startsWith("/templates/ecomas/"));
        given().get("/api/templates/nope/sample").then().statusCode(404);
        Owner o = signup("Template Shop");
        as(o).body(Map.of("templateId", "nope", "addSampleProducts", false))
                .put("/api/admin/store/template").then().statusCode(400);
        as(o).body(Map.of("templateId", "ecomas-02", "addSampleProducts", true))
                .put("/api/admin/store/template").then().statusCode(200)
                .body("templateId", is("ecomas-02")).body("template.name", is("Ecomas Beauty"));
        as(o).get("/api/admin/products").then().body("total", is(12));
        as(o).get("/api/admin/categories").then().body("size()", is(3));
        // changing the template later keeps products
        as(o).body(Map.of("templateId", "ecomas-04", "addSampleProducts", false))
                .put("/api/admin/store/template").then().statusCode(200).body("templateId", is("ecomas-04"));
        given().get("/api/public/stores/" + o.slug()).then().statusCode(200)
                .body("templateId", is("ecomas-04")).body("name", is("Template Shop"));
        as(o).get("/api/admin/products").then().body("total", is(12));
    }

    @Test
    void productCrudAndTenantIsolation() {
        Owner a = signup("Store A");
        Owner b = signup("Store B");
        Integer catId = as(a).body(Map.of("name", "Shoes")).post("/api/admin/categories")
                .then().statusCode(201).extract().path("id");
        Integer id = as(a).body(Map.of("name", "Runner X", "price", 120.5, "compareAtPrice", 150,
                        "stock", 3, "categoryId", catId, "images", List.of("/media/1/a.jpg"), "featured", true))
                .post("/api/admin/products").then().statusCode(201)
                .body("slug", is("runner-x")).body("category.name", is("Shoes")).extract().path("id");

        as(a).body(Map.of("name", "Bad", "price", 10, "compareAtPrice", 5)).post("/api/admin/products")
                .then().statusCode(400);
        // store B can't see, edit or delete store A's product, or use its category
        as(b).get("/api/admin/products/" + id).then().statusCode(404);
        as(b).body(Map.of("name", "Hijack", "price", 1)).put("/api/admin/products/" + id).then().statusCode(404);
        as(b).delete("/api/admin/products/" + id).then().statusCode(404);
        as(b).body(Map.of("name", "X", "price", 1, "categoryId", catId)).post("/api/admin/products")
                .then().statusCode(400);
        as(b).get("/api/admin/products").then().body("total", is(0));

        as(a).body(Map.of("name", "Runner X2", "price", 99, "stock", 3, "categoryId", catId))
                .put("/api/admin/products/" + id).then().statusCode(200).body("slug", is("runner-x2"));
        given().get("/api/public/stores/" + a.slug() + "/products?category=shoes").then()
                .body("total", is(1)).body("items[0].name", is("Runner X2"));
        given().get("/api/public/stores/" + a.slug() + "/products/runner-x2").then().statusCode(200);

        as(a).delete("/api/admin/categories/" + catId).then().statusCode(204);
        as(a).get("/api/admin/products/" + id).then().body("category", nullValue());
        as(a).delete("/api/admin/products/" + id).then().statusCode(204);
    }

    @Test
    void draftProductsAreHiddenFromStorefront() {
        Owner o = signup("Drafty");
        as(o).body(Map.of("name", "Secret", "price", 5, "status", "DRAFT")).post("/api/admin/products")
                .then().statusCode(201);
        given().get("/api/public/stores/" + o.slug() + "/products").then().body("total", is(0));
        given().get("/api/public/stores/" + o.slug() + "/products/secret").then().statusCode(404);
    }

    @Test
    void checkoutUsesServerPricesAndStock() {
        Owner o = signup("Checkout Co");
        Integer pid = as(o).body(Map.of("name", "Mug", "price", 12.5, "stock", 2)).post("/api/admin/products")
                .then().statusCode(201).extract().path("id");
        Map<String, Object> customer = Map.of("customerName", "Esi", "email", "esi@x.test",
                "address", "1 Ring Rd", "city", "Accra", "country", "Ghana",
                "items", List.of(Map.of("productId", pid, "quantity", 2)));

        given().contentType(ContentType.JSON).body(customer).post("/api/public/stores/" + o.slug() + "/orders")
                .then().statusCode(201).body("total", is(25.0f)).body("number", startsWith("AX-"));
        // sold out now
        given().contentType(ContentType.JSON).body(customer).post("/api/public/stores/" + o.slug() + "/orders")
                .then().statusCode(409);

        Integer orderId = as(o).get("/api/admin/orders").then().body("total", is(1))
                .body("items[0].items[0].name", is("Mug")).extract().path("items[0].id");
        as(o).get("/api/admin/products/" + pid).then().body("stock", is(0));
        as(o).get("/api/admin/dashboard").then().body("orders", is(1)).body("revenue", is(25.0f))
                .body("lowStock.size()", is(1));

        // cancelling restocks
        as(o).body(Map.of("status", "CANCELLED")).patch("/api/admin/orders/" + orderId).then().statusCode(200);
        as(o).get("/api/admin/products/" + pid).then().body("stock", is(2));
        as(o).body(Map.of("status", "PAID")).patch("/api/admin/orders/" + orderId).then().statusCode(400);
    }

    @Test
    void uploadAndServeImage() {
        Owner o = signup("Pics");
        byte[] png = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");
        String path = given().auth().oauth2(o.token()).multiPart("file", "dot.png", png, "image/png")
                .post("/api/admin/uploads").then().statusCode(201)
                .body("url", startsWith("http")).extract().path("path");
        given().get(path).then().statusCode(200).contentType("image/png");
        given().auth().oauth2(o.token())
                .multiPart("file", "x.txt", "hello".getBytes(StandardCharsets.UTF_8), "text/plain")
                .post("/api/admin/uploads").then().statusCode(400);
        given().get("/media/1/../../pom.xml").then().statusCode(404);
    }

    @Test
    void unpublishedStoreIsHidden() {
        Owner o = signup("Hidden");
        as(o).body(Map.of("name", "Hidden", "published", false, "currency", "GHS")).put("/api/admin/store")
                .then().statusCode(200).body("currency", is("GHS"));
        given().get("/api/public/stores/" + o.slug()).then().statusCode(404);
    }
}
