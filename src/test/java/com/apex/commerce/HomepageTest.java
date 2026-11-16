package com.apex.commerce;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
class HomepageTest {

    static Map<String, Object> banner(String title, String link) {
        Map<String, Object> m = new HashMap<>();
        m.put("title", title);
        m.put("eyebrow", "New season");
        m.put("buttonLabel", "Shop now");
        if (link != null) m.put("link", link);
        return m;
    }

    @Test
    void ownersSetBannersAndShoppersSeeThem() {
        var s = given().contentType(ContentType.JSON)
                .body(Map.of("fullName", "Abla", "email", UUID.randomUUID() + "@h.test", "password", "secret123", "storeName", "Abla Bags"))
                .post("/api/auth/signup").then().statusCode(200).extract();
        String token = s.path("token");
        String slug = s.path("store.slug");
        given().get("/api/public/stores/" + slug).then().body("banners.size()", is(0));

        Map<String, Object> first = banner("Handwoven bags\nfor every day", "category:bags");
        first.put("videoUrl", "https://www.youtube.com/watch?v=abc123");
        given().auth().oauth2(token).contentType(ContentType.JSON)
                .body(Map.of("banners", List.of(first, banner("Gift wrapping", "https://example.com/gifts"), banner("Sale", "/s/" + slug + "/shop"))))
                .put("/api/admin/store/banners").then().statusCode(200).body("title", contains("Handwoven bags\nfor every day", "Gift wrapping", "Sale"));
        given().get("/api/public/stores/" + slug).then()
                .body("banners[0].link", is("category:bags")).body("banners[0].videoUrl", startsWith("https://www.youtube.com"));

        var auth = given().auth().oauth2(token).contentType(ContentType.JSON);
        auth.body(Map.of("banners", List.of(banner("", null)))).put("/api/admin/store/banners").then().statusCode(400);
        given().auth().oauth2(token).contentType(ContentType.JSON).body(Map.of("banners", List.of(banner("X", "javascript:alert(1)"))))
                .put("/api/admin/store/banners").then().statusCode(400).body("message", containsString("links"));
        Map<String, Object> badVideo = banner("X", null);
        badVideo.put("videoUrl", "https://evil.test/v");
        given().auth().oauth2(token).contentType(ContentType.JSON).body(Map.of("banners", List.of(badVideo)))
                .put("/api/admin/store/banners").then().statusCode(400);
        List<Map<String, Object>> six = java.util.Collections.nCopies(6, banner("X", null));
        given().auth().oauth2(token).contentType(ContentType.JSON).body(Map.of("banners", six))
                .put("/api/admin/store/banners").then().statusCode(400);
        // clearing goes back to the template's banner
        given().auth().oauth2(token).contentType(ContentType.JSON).body(Map.of("banners", List.of()))
                .put("/api/admin/store/banners").then().statusCode(200).body("size()", is(0));
    }

    @Test
    void promoBlocksFeaturesAndTestimonials() {
        var s = given().contentType(ContentType.JSON)
                .body(Map.of("fullName", "Yaw", "email", UUID.randomUUID() + "@h.test", "password", "secret123", "storeName", "Yaw Home"))
                .post("/api/auth/signup").then().statusCode(200).extract();
        String token = s.path("token");
        String slug = s.path("store.slug");
        given().get("/api/public/stores/" + slug).then()
                .body("homepage.promos.size()", is(0)).body("homepage.features", nullValue()).body("homepage.testimonials.size()", is(0));

        Map<String, Object> promo = new HashMap<>(Map.of("title", "Kente stoles", "eyebrow", "", "buttonLabel", "Shop stoles", "link", "category:stoles"));
        Map<String, Object> body = new HashMap<>();
        body.put("promos", Map.of("ecomas-01/offer-1", promo, "ecomas-01/about", Map.of("hidden", true)));
        body.put("features", List.of(Map.of("title", "Free delivery in Accra", "text", "On orders over GHS 300")));
        body.put("featuresHidden", false);
        body.put("testimonials", List.of(Map.of("name", "Ama K.", "quote", "Beautiful work, fast delivery.", "rating", 5)));
        given().auth().oauth2(token).contentType(ContentType.JSON).body(body).put("/api/admin/store/homepage").then().statusCode(200);

        given().get("/api/public/stores/" + slug).then()
                .body("homepage.promos.'ecomas-01/offer-1'.title", is("Kente stoles"))
                .body("homepage.promos.'ecomas-01/offer-1'.eyebrow", is("")) // "" = remove that line
                .body("homepage.promos.'ecomas-01/about'.hidden", is(true))
                .body("homepage.features[0].title", is("Free delivery in Accra"))
                .body("homepage.testimonials[0].name", is("Ama K."));

        // sending null for a block resets it; other blocks stay
        Map<String, Object> reset = new HashMap<>();
        reset.put("ecomas-01/about", null);
        given().auth().oauth2(token).contentType(ContentType.JSON).body(Map.of("promos", reset, "featuresHidden", true))
                .put("/api/admin/store/homepage").then().statusCode(200)
                .body("promos.'ecomas-01/about'", nullValue()).body("promos.'ecomas-01/offer-1'.title", is("Kente stoles"))
                .body("featuresHidden", is(true));

        given().auth().oauth2(token).contentType(ContentType.JSON)
                .body(Map.of("promos", Map.of("bad key!", Map.of("title", "x")))).put("/api/admin/store/homepage").then().statusCode(400);
        given().auth().oauth2(token).contentType(ContentType.JSON)
                .body(Map.of("promos", Map.of("ecomas-01/offer-1", Map.of("link", "javascript:x")))).put("/api/admin/store/homepage").then().statusCode(400);
        given().auth().oauth2(token).contentType(ContentType.JSON)
                .body(Map.of("testimonials", List.of(Map.of("name", "X", "quote", "ok", "rating", 9)))).put("/api/admin/store/homepage").then().statusCode(400);
    }
}
