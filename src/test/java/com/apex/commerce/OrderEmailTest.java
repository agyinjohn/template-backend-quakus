package com.apex.commerce;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class OrderEmailTest {

    @Inject
    MockMailbox mailbox;

    @BeforeEach
    void clear() {
        mailbox.clear();
    }

    /** Emails are sent asynchronously after commit, so wait for them. */
    List<Mail> await(String to, int count) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            List<Mail> mails = mailbox.getMailsSentTo(to);
            if (mails != null && mails.size() >= count) {
                return mails;
            }
            Thread.sleep(50);
        }
        fail("Expected " + count + " email(s) to " + to + ", got " + mailbox.getMailsSentTo(to));
        return null;
    }

    @Test
    void checkoutEmailsShopperAndOwnerThenStatusUpdates() throws Exception {
        String ownerLogin = UUID.randomUUID() + "@owner.test";
        var signup = given().contentType(ContentType.JSON)
                .body(Map.of("fullName", "Efua", "email", ownerLogin, "password", "secret123", "storeName", "Efua <Crafts>"))
                .post("/api/auth/signup").then().statusCode(200).extract();
        String token = signup.path("token");
        String slug = signup.path("store.slug");
        // a separate contact email for the store receives the owner notifications
        given().auth().oauth2(token).contentType(ContentType.JSON)
                .body(Map.of("name", "Efua <Crafts>", "email", "shop@efua.test", "phone", "+233 24 000 0000"))
                .put("/api/admin/store").then().statusCode(200);
        Integer pid = given().auth().oauth2(token).contentType(ContentType.JSON)
                .body(Map.of("name", "Woven Basket", "price", 30, "stock", 5))
                .post("/api/admin/products").then().statusCode(201).extract().path("id");

        String shopper = UUID.randomUUID() + "@shopper.test";
        String number = given().contentType(ContentType.JSON)
                .body(Map.of("customerName", "Yaw", "email", shopper, "address", "3 Palm St", "city", "Kumasi",
                        "notes", "Payment: Cash on delivery", "items", List.of(Map.of("productId", pid, "quantity", 2))))
                .post("/api/public/stores/" + slug + "/orders")
                .then().statusCode(201).body("confirmationEmail", is(shopper)).extract().path("number");

        Mail confirmation = await(shopper, 1).get(0);
        assertEquals("Order " + number + " confirmed: Efua <Crafts>", confirmation.getSubject());
        assertEquals("\"Efua <Crafts>\" <orders@apex-commerce.local>", confirmation.getFrom());
        assertEquals(List.of("shop@efua.test"), confirmation.getReplyTo() == null ? List.of() : List.of(confirmation.getReplyTo()));
        String html = confirmation.getHtml();
        assertTrue(html.contains("Woven Basket"), html);
        assertTrue(html.contains("$60.00"), "line total and total formatted");
        assertTrue(html.contains("Efua &lt;Crafts&gt;"), "store name is HTML-escaped");
        assertTrue(html.contains("Cash on delivery"));
        assertTrue(html.contains("/s/" + slug + "/"));

        Mail ownerMail = await("shop@efua.test", 1).get(0);
        assertTrue(ownerMail.getSubject().startsWith("New order " + number));
        assertEquals(shopper, ownerMail.getReplyTo());
        assertTrue(ownerMail.getHtml().contains("/admin#/orders/"));
        var toLogin = mailbox.getMailsSentTo(ownerLogin);
        assertTrue(toLogin == null || toLogin.isEmpty(), "owner login isn't used when the store has a contact email");

        Integer orderId = given().auth().oauth2(token).get("/api/admin/orders").then().extract().path("items[0].id");
        given().auth().oauth2(token).contentType(ContentType.JSON).body(Map.of("status", "SHIPPED"))
                .patch("/api/admin/orders/" + orderId).then().statusCode(200);
        Mail shipped = await(shopper, 2).get(1);
        assertEquals("Your order is on its way: order " + number, shipped.getSubject());

        // setting the same status again doesn't email twice
        given().auth().oauth2(token).contentType(ContentType.JSON).body(Map.of("status", "SHIPPED"))
                .patch("/api/admin/orders/" + orderId).then().statusCode(200);
        Thread.sleep(300);
        assertEquals(2, mailbox.getMailsSentTo(shopper).size());
    }

    @Test
    void ownerLoginIsUsedWhenStoreHasNoContactEmail() throws Exception {
        String ownerLogin = UUID.randomUUID() + "@owner.test";
        var signup = given().contentType(ContentType.JSON)
                .body(Map.of("fullName", "Kwame", "email", ownerLogin, "password", "secret123", "storeName", "Kwame Kits"))
                .post("/api/auth/signup").then().statusCode(200).extract();
        String token = signup.path("token");
        given().auth().oauth2(token).contentType(ContentType.JSON).body(Map.of("name", "Kwame Kits"))
                .put("/api/admin/store").then().statusCode(200);
        Integer pid = given().auth().oauth2(token).contentType(ContentType.JSON)
                .body(Map.of("name", "Jersey", "price", 20, "stock", 5))
                .post("/api/admin/products").then().extract().path("id");
        given().contentType(ContentType.JSON)
                .body(Map.of("customerName", "Ama", "email", "ama@x.test", "address", "1 Road", "city", "Accra",
                        "items", List.of(Map.of("productId", pid, "quantity", 1))))
                .post("/api/public/stores/" + signup.path("store.slug") + "/orders").then().statusCode(201);
        assertTrue(await(ownerLogin, 1).get(0).getSubject().startsWith("New order"));
    }
}
