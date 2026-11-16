package com.apex.commerce;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import com.apex.commerce.orders.CustomerOrder;
import com.apex.commerce.payments.PaymentService;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@io.quarkus.test.common.QuarkusTestResource(FakePaystack.class)
class PaymentsTest {

    static final String GOOD_KEY = "sk_test_good" + "a".repeat(30);

    @Inject
    MockMailbox mailbox;

    @Inject
    PaymentService payments;

    record Shop(String token, String slug, int productId) {}

    @BeforeEach
    void reset() {
        mailbox.clear();
        FakePaystack.OUTCOMES.clear();
        FakePaystack.down = false;
    }

    Shop shop(boolean connect) {
        var s = given().contentType(ContentType.JSON)
                .body(Map.of("fullName", "Owner", "email", UUID.randomUUID() + "@o.test", "password", "secret123", "storeName", "Pay Shop"))
                .post("/api/auth/signup").then().statusCode(200).extract();
        String token = s.path("token");
        given().auth().oauth2(token).contentType(ContentType.JSON)
                .body(Map.of("name", "Pay Shop", "currency", "GHS", "email", "owner@payshop.test")).put("/api/admin/store").then().statusCode(200);
        if (connect) {
            given().auth().oauth2(token).contentType(ContentType.JSON).body(Map.of("secretKey", GOOD_KEY))
                    .put("/api/admin/store/payments/paystack").then().statusCode(200);
        }
        int pid = given().auth().oauth2(token).contentType(ContentType.JSON)
                .body(Map.of("name", "Shea Butter", "price", 12.5, "stock", 4)).post("/api/admin/products")
                .then().statusCode(201).extract().path("id");
        return new Shop(token, s.path("store.slug"), pid);
    }

    io.restassured.response.ValidatableResponse checkout(Shop shop, String method, int qty, String email) {
        return given().contentType(ContentType.JSON)
                .body(Map.of("customerName", "Abena", "email", email, "address", "4 Lane", "city", "Accra",
                        "paymentMethod", method, "items", List.of(Map.of("productId", shop.productId(), "quantity", qty))))
                .post("/api/public/stores/" + shop.slug() + "/orders").then();
    }

    int stock(Shop shop) {
        return given().auth().oauth2(shop.token()).get("/api/admin/products/" + shop.productId()).then().extract().path("stock");
    }

    void awaitMails(String to, int n) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            var m = mailbox.getMailsSentTo(to);
            if (m != null && m.size() >= n) return;
            Thread.sleep(50);
        }
        fail("expected " + n + " mails to " + to);
    }

    @Test
    void connectingPaystackValidatesAndHidesTheKey() {
        Shop shop = shop(false);
        var auth = given().auth().oauth2(shop.token()).contentType(ContentType.JSON);
        auth.body(Map.of("secretKey", "pk_test_" + "a".repeat(30))).put("/api/admin/store/payments/paystack")
                .then().statusCode(400).body("message", containsString("public key"));
        given().auth().oauth2(shop.token()).contentType(ContentType.JSON).body(Map.of("secretKey", "sk_test_bad" + "b".repeat(30)))
                .put("/api/admin/store/payments/paystack").then().statusCode(400).body("message", containsString("didn't accept"));
        String body = given().auth().oauth2(shop.token()).contentType(ContentType.JSON).body(Map.of("secretKey", GOOD_KEY))
                .put("/api/admin/store/payments/paystack").then().statusCode(200)
                .body("paystackConnected", is(true)).body("paystackMode", is("test"))
                .body("paystackKeyHint", is("sk_test_…aaaa"))
                .body("webhookUrl", endsWith("/api/public/stores/" + shop.slug() + "/payments/paystack-webhook"))
                .extract().asString();
        assertFalse(body.contains(GOOD_KEY), "secret key is never returned");
        assertFalse(given().auth().oauth2(shop.token()).get("/api/admin/store").asString().contains(GOOD_KEY));
        given().get("/api/public/stores/" + shop.slug()).then()
                .body("paymentMethods", contains("PAYSTACK", "BANK_TRANSFER", "CASH_ON_DELIVERY"));
    }

    @Test
    void onlinePaymentFlow() throws Exception {
        Shop shop = shop(true);
        String shopper = UUID.randomUUID() + "@s.test";
        var res = checkout(shop, "PAYSTACK", 2, shopper).statusCode(201)
                .body("paymentUrl", startsWith("https://checkout.paystack.test/"))
                .body("paid", is(false)).body("confirmationEmail", nullValue()).extract();
        String number = res.path("number");
        String ref = reference(number);
        assertTrue(ref.startsWith(number + "-") && ref.length() > number.length() + 8, "unguessable reference");
        var txn = FakePaystack.TRANSACTIONS.get(ref);
        assertEquals(2500, txn.amount(), "amount sent in pesewas");
        assertEquals("GHS", txn.currency());
        assertTrue(txn.callbackUrl().endsWith("/s/" + shop.slug() + "/checkout/complete"));
        assertEquals(2, 4 - stock(shop), "stock reserved while paying");
        Thread.sleep(300);
        var none = mailbox.getMailsSentTo(shopper);
        assertTrue(none == null || none.isEmpty(), "no confirmation before payment");

        // shopper returns from Paystack
        given().contentType(ContentType.JSON).body(Map.of("reference", ref))
                .post("/api/public/stores/" + shop.slug() + "/payments/verify").then().statusCode(200)
                .body("paid", is(true)).body("status", is("PAID")).body("confirmationEmail", is(shopper));
        awaitMails(shopper, 1);
        assertTrue(mailbox.getMailsSentTo(shopper).get(0).getHtml().contains("payment by mobile money was successful"));
        awaitMails("owner@payshop.test", 1);
        assertTrue(mailbox.getMailsSentTo("owner@payshop.test").get(0).getHtml().contains("Paid online"));

        // verifying again (refresh) changes nothing and sends no more emails
        given().contentType(ContentType.JSON).body(Map.of("reference", ref))
                .post("/api/public/stores/" + shop.slug() + "/payments/verify").then().statusCode(200).body("paid", is(true));
        Thread.sleep(300);
        assertEquals(1, mailbox.getMailsSentTo(shopper).size());
        given().auth().oauth2(shop.token()).get("/api/admin/orders").then()
                .body("items[0].paid", is(true)).body("items[0].paymentChannel", is("mobile_money"))
                .body("items[0].paymentMethod", is("PAYSTACK"));
    }

    @Test
    void failedOrUnderpaidPaymentsDontMarkOrdersPaid() {
        Shop shop = shop(true);
        String r1 = reference(checkout(shop, "PAYSTACK", 1, "a@s.test").statusCode(201).extract().path("number"));
        FakePaystack.OUTCOMES.put(r1, "failed");
        given().contentType(ContentType.JSON).body(Map.of("reference", r1))
                .post("/api/public/stores/" + shop.slug() + "/payments/verify").then().statusCode(200)
                .body("paid", is(false)).body("status", is("CANCELLED"));
        String r2 = reference(checkout(shop, "PAYSTACK", 1, "b@s.test").statusCode(201).extract().path("number"));
        FakePaystack.OUTCOMES.put(r2, "amount:100");
        given().contentType(ContentType.JSON).body(Map.of("reference", r2))
                .post("/api/public/stores/" + shop.slug() + "/payments/verify").then().statusCode(200).body("paid", is(false));
        // order numbers alone can't be used to look up or cancel payments
        String n3 = checkout(shop, "PAYSTACK", 1, "c@s.test").statusCode(201).extract().path("number");
        given().contentType(ContentType.JSON).body(Map.of("reference", n3))
                .post("/api/public/stores/" + shop.slug() + "/payments/verify").then().statusCode(404);
    }

    @Test
    void cancellingOnPaystackFreesStockForARetry() {
        Shop shop = shop(true);
        String r1 = reference(checkout(shop, "PAYSTACK", 3, "k@s.test").statusCode(201).extract().path("number"));
        checkout(shop, "PAYSTACK", 3, "k@s.test").statusCode(409); // only 1 left while the first attempt holds 3
        FakePaystack.OUTCOMES.put(r1, "abandoned");
        given().contentType(ContentType.JSON).body(Map.of("reference", r1))
                .post("/api/public/stores/" + shop.slug() + "/payments/verify").then().body("status", is("CANCELLED"));
        assertEquals(4, stock(shop));
        checkout(shop, "PAYSTACK", 3, "k@s.test").statusCode(201);
    }

    @Test
    void paymentsStillInProgressAreLeftOpen() {
        Shop shop = shop(true);
        String r = reference(checkout(shop, "PAYSTACK", 1, "l@s.test").statusCode(201).extract().path("number"));
        FakePaystack.OUTCOMES.put(r, "ongoing"); // e.g. waiting for the shopper to approve on their phone
        given().contentType(ContentType.JSON).body(Map.of("reference", r))
                .post("/api/public/stores/" + shop.slug() + "/payments/verify").then()
                .body("paid", is(false)).body("status", is("PENDING"));
        assertEquals(3, stock(shop));
    }

    @Transactional
    String reference(String number) {
        return CustomerOrder.<CustomerOrder>find("number", number).firstResult().paymentReference;
    }

    @Test
    void paystackDownRollsBackTheOrder() {
        Shop shop = shop(true);
        FakePaystack.down = true;
        checkout(shop, "PAYSTACK", 1, "c@s.test").statusCode(502).body("message", containsString("another payment method"));
        assertEquals(4, stock(shop), "stock released");
        given().auth().oauth2(shop.token()).get("/api/admin/orders").then().body("total", is(0));
    }

    @Test
    void webhookMarksPaidOnlyWithValidSignature() throws Exception {
        Shop shop = shop(true);
        String number = checkout(shop, "PAYSTACK", 1, "d@s.test").statusCode(201).extract().path("number");
        String event = "{\"event\":\"charge.success\",\"data\":{\"status\":\"success\",\"reference\":\"" + reference(number)
                + "\",\"amount\":1250,\"currency\":\"GHS\",\"channel\":\"card\"}}";
        String url = "/api/public/stores/" + shop.slug() + "/payments/paystack-webhook";
        given().contentType(ContentType.JSON).header("x-paystack-signature", "deadbeef").body(event).post(url).then().statusCode(401);
        given().contentType(ContentType.JSON).header("x-paystack-signature", sign(GOOD_KEY, event)).body(event).post(url).then().statusCode(200);
        given().auth().oauth2(shop.token()).get("/api/admin/orders").then()
                .body("items[0].paid", is(true)).body("items[0].status", is("PAID")).body("items[0].paymentChannel", is("card"));
        awaitMails("d@s.test", 1);
    }

    @Test
    void manualMethodsAndValidation() {
        Shop shop = shop(false);
        checkout(shop, "PAYSTACK", 1, "e@s.test").statusCode(400).body("message", containsString("doesn't accept"));
        var auth = given().auth().oauth2(shop.token()).contentType(ContentType.JSON);
        auth.body(Map.of("bankTransferEnabled", true, "bankTransferInstructions", "", "cashOnDeliveryEnabled", false))
                .put("/api/admin/store/payments").then().statusCode(400);
        given().auth().oauth2(shop.token()).contentType(ContentType.JSON)
                .body(Map.of("bankTransferEnabled", false, "cashOnDeliveryEnabled", false))
                .put("/api/admin/store/payments").then().statusCode(400).body("message", containsString("at least one"));
        given().auth().oauth2(shop.token()).contentType(ContentType.JSON)
                .body(Map.of("bankTransferEnabled", true, "bankTransferInstructions", "GCB Bank, Pay Shop Ltd, 1234567890", "cashOnDeliveryEnabled", false))
                .put("/api/admin/store/payments").then().statusCode(200);
        checkout(shop, "CASH_ON_DELIVERY", 1, "f@s.test").statusCode(400);
        checkout(shop, "BANK_TRANSFER", 1, "g@s.test").statusCode(201)
                .body("paymentInstructions", is("GCB Bank, Pay Shop Ltd, 1234567890")).body("paid", is(false))
                .body("confirmationEmail", is("g@s.test"));
        // owner confirms the transfer
        Integer id = given().auth().oauth2(shop.token()).get("/api/admin/orders").then().extract().path("items[0].id");
        given().auth().oauth2(shop.token()).contentType(ContentType.JSON).body(Map.of("status", "PAID"))
                .patch("/api/admin/orders/" + id).then().statusCode(200).body("paid", is(true));
        // can't disconnect into a store with no payment methods... (not connected here) and webhook without key is rejected
        given().contentType(ContentType.JSON).header("x-paystack-signature", "x").body("{}")
                .post("/api/public/stores/" + shop.slug() + "/payments/paystack-webhook").then().statusCode(401);
    }

    @Test
    void abandonedPaymentsExpireAndReleaseStock() {
        Shop shop = shop(true);
        String number = checkout(shop, "PAYSTACK", 3, "h@s.test").statusCode(201).extract().path("number");
        assertEquals(1, stock(shop));
        FakePaystack.OUTCOMES.put(reference(number), "abandoned");
        Long id = backdate(number);
        payments.expire(id);
        assertEquals(4, stock(shop), "stock released");
        given().auth().oauth2(shop.token()).get("/api/admin/orders/" + id).then()
                .body("status", is("CANCELLED")).body("notes", containsString("payment not completed"));

        // an order the shopper actually paid (webhook missed) is confirmed instead of cancelled
        String paidNumber = checkout(shop, "PAYSTACK", 1, "i@s.test").statusCode(201).extract().path("number");
        Long paidId = backdate(paidNumber);
        payments.expire(paidId);
        given().auth().oauth2(shop.token()).get("/api/admin/orders/" + paidId).then().body("status", is("PAID"));
    }

    @Test
    void scheduledJobExpiresStaleOrders() {
        Shop shop = shop(true);
        String number = checkout(shop, "PAYSTACK", 2, "j@s.test").statusCode(201).extract().path("number");
        FakePaystack.OUTCOMES.put(reference(number), "abandoned");
        Long id = backdate(number);
        payments.expireUnpaid();
        given().auth().oauth2(shop.token()).get("/api/admin/orders/" + id).then().body("status", is("CANCELLED"));
        assertEquals(4, stock(shop));
    }

    @Transactional
    Long backdate(String number) {
        CustomerOrder o = CustomerOrder.find("number", number).firstResult();
        o.createdAt = Instant.now().minusSeconds(7200);
        return o.id;
    }

    static String sign(String key, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }
}
