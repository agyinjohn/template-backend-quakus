package com.apex.commerce;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
class SubscriberTest {

    String[] shop(String name) {
        var s = given().contentType(ContentType.JSON)
                .body(Map.of("fullName", "Owner", "email", UUID.randomUUID() + "@n.test", "password", "secret123", "storeName", name))
                .post("/api/auth/signup").then().statusCode(200).extract();
        return new String[] {s.path("token"), s.path("store.slug")};
    }

    @Test
    void signUpListExportAndRemove() {
        String[] a = shop("News A");
        String[] b = shop("News B");
        String url = "/api/public/stores/" + a[1] + "/subscribe";
        given().contentType(ContentType.JSON).body(Map.of("email", "Esi@Example.test", "source", "footer")).post(url)
                .then().statusCode(200).body("message", containsString("News A"));
        // signing up again: same answer, no duplicate
        given().contentType(ContentType.JSON).body(Map.of("email", "esi@example.test")).post(url).then().statusCode(200);
        given().contentType(ContentType.JSON).body(Map.of("email", "=HYPERLINK(\"x\")@evil.test")).post(url).then().statusCode(400);
        given().contentType(ContentType.JSON).body(Map.of("email", "not-an-email")).post(url).then().statusCode(400);
        given().contentType(ContentType.JSON).body(Map.of("email", "kofi@example.test", "source", "home-banner")).post(url).then().statusCode(200);

        var list = given().auth().oauth2(a[0]).get("/api/admin/subscribers").then().statusCode(200)
                .body("total", is(2)).body("items.email", contains("kofi@example.test", "esi@example.test")).extract();
        given().auth().oauth2(b[0]).get("/api/admin/subscribers").then().body("total", is(0)); // other stores can't see them
        given().auth().oauth2(a[0]).get("/api/admin/subscribers?q=kofi").then().body("total", is(1));

        String csv = given().auth().oauth2(a[0]).get("/api/admin/subscribers/export.csv").then().statusCode(200)
                .header("Content-Disposition", containsString(a[1] + "-subscribers.csv")).extract().asString();
        assertTrue(csv.startsWith("email,source,subscribed_at\n"), csv);
        assertTrue(csv.contains("\"esi@example.test\",\"footer\","), csv);
        assertEquals(3, csv.trim().split("\n").length);

        int id = list.path("items[0].id");
        given().auth().oauth2(b[0]).delete("/api/admin/subscribers/" + id).then().statusCode(404);
        given().auth().oauth2(a[0]).delete("/api/admin/subscribers/" + id).then().statusCode(204);
        given().auth().oauth2(a[0]).get("/api/admin/subscribers").then().body("total", is(1));
    }

    @Test
    void csvCellsCannotBecomeFormulas() {
        assertEquals("\"'=1+1\"", com.apex.commerce.marketing.SubscriberResource.csvCell("=1+1"));
        assertEquals("\"a\"\"b\"", com.apex.commerce.marketing.SubscriberResource.csvCell("a\"b"));
    }
}
