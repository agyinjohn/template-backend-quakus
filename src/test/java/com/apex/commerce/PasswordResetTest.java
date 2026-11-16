package com.apex.commerce;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import com.apex.commerce.auth.PasswordResetToken;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class PasswordResetTest {

    private static final Pattern LINK = Pattern.compile("/reset-password\\?token=([A-Za-z0-9_-]+)");

    @Inject
    MockMailbox mailbox;

    @BeforeEach
    void clear() {
        mailbox.clear();
    }

    String signup(String email) {
        return given().contentType(ContentType.JSON)
                .body(Map.of("fullName", "Adwoa", "email", email, "password", "oldpassword1", "storeName", "Adwoa Shop"))
                .post("/api/auth/signup").then().statusCode(200).extract().path("token");
    }

    void forgot(String email) {
        given().contentType(ContentType.JSON).body(Map.of("email", email)).post("/api/auth/password/forgot")
                .then().statusCode(202).body("message", containsString("If an account exists"));
    }

    List<Mail> mails(String to, int atLeast) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            List<Mail> m = mailbox.getMailsSentTo(to);
            if (m != null && m.size() >= atLeast) return m;
            Thread.sleep(50);
        }
        fail("expected " + atLeast + " mails to " + to + " but got " + mailbox.getMailsSentTo(to));
        return null;
    }

    String tokenFrom(Mail m) {
        var matcher = LINK.matcher(m.getHtml());
        assertTrue(matcher.find(), m.getHtml());
        return matcher.group(1);
    }

    void login(String email, String password, int status) {
        given().contentType(ContentType.JSON).body(Map.of("email", email, "password", password))
                .post("/api/auth/login").then().statusCode(status);
    }

    @Test
    void resetFlowSignsOutOldSessions() throws Exception {
        String email = UUID.randomUUID() + "@shop.test";
        String oldSession = signup(email);
        Thread.sleep(1100); // tokens carry whole seconds: make sure the reset happens in a later second

        forgot(email.toUpperCase()); // email matching ignores case
        Mail mail = mails(email, 1).get(0);
        assertEquals("Reset your Apex Commerce password", mail.getSubject());
        assertTrue(mail.getHtml().contains("expires in 60 minutes"));
        String token = tokenFrom(mail);

        String newSession = given().contentType(ContentType.JSON).body(Map.of("token", token, "password", "brandnewpass"))
                .post("/api/auth/password/reset").then().statusCode(200)
                .body("user.email", is(email)).extract().path("token");

        login(email, "oldpassword1", 401);
        login(email, "brandnewpass", 200);
        given().auth().oauth2(oldSession).get("/api/admin/store").then().statusCode(401)
                .body("message", containsString("password was changed"));
        given().auth().oauth2(newSession).get("/api/admin/store").then().statusCode(200);

        // links work once
        given().contentType(ContentType.JSON).body(Map.of("token", token, "password", "anotherpass1"))
                .post("/api/auth/password/reset").then().statusCode(400).body("message", containsString("expired or was already used"));
        assertEquals("Your Apex Commerce password was changed", mails(email, 2).get(1).getSubject());
    }

    @Test
    void unknownEmailGetsTheSameAnswerAndNoEmail() throws Exception {
        String email = UUID.randomUUID() + "@nobody.test";
        forgot(email);
        Thread.sleep(300);
        var m = mailbox.getMailsSentTo(email);
        assertTrue(m == null || m.isEmpty());
    }

    @Test
    void onlyTheNewestLinkWorksAndRequestsAreRateLimited() throws Exception {
        String email = UUID.randomUUID() + "@shop.test";
        signup(email);
        forgot(email);
        String first = tokenFrom(mails(email, 1).get(0));
        forgot(email);
        String second = tokenFrom(mails(email, 2).get(1));
        given().contentType(ContentType.JSON).body(Map.of("token", first, "password", "brandnewpass"))
                .post("/api/auth/password/reset").then().statusCode(400);
        forgot(email);
        mails(email, 3);
        forgot(email); // 4th within the hour: same response, no email
        Thread.sleep(400);
        assertEquals(3, mailbox.getMailsSentTo(email).size());
        assertNotEquals(first, second);
    }

    @Test
    void expiredLinksAndWeakPasswordsAreRejected() throws Exception {
        String email = UUID.randomUUID() + "@shop.test";
        signup(email);
        forgot(email);
        String token = tokenFrom(mails(email, 1).get(0));
        given().contentType(ContentType.JSON).body(Map.of("token", token, "password", "short"))
                .post("/api/auth/password/reset").then().statusCode(400).body("fields.password", notNullValue());
        expire(token);
        given().contentType(ContentType.JSON).body(Map.of("token", token, "password", "brandnewpass"))
                .post("/api/auth/password/reset").then().statusCode(400);
        given().contentType(ContentType.JSON).body(Map.of("token", "made-up-token", "password", "brandnewpass"))
                .post("/api/auth/password/reset").then().statusCode(400);
        login(email, "oldpassword1", 200);
    }

    @Test
    void changePasswordWhileLoggedIn() throws Exception {
        String email = UUID.randomUUID() + "@shop.test";
        String session = signup(email);
        Thread.sleep(1100);
        given().auth().oauth2(session).contentType(ContentType.JSON)
                .body(Map.of("currentPassword", "wrongpass", "newPassword", "brandnewpass"))
                .post("/api/auth/password/change").then().statusCode(400).body("message", containsString("current password"));
        String fresh = given().auth().oauth2(session).contentType(ContentType.JSON)
                .body(Map.of("currentPassword", "oldpassword1", "newPassword", "brandnewpass"))
                .post("/api/auth/password/change").then().statusCode(200).extract().path("token");
        given().auth().oauth2(session).get("/api/auth/me").then().statusCode(401);
        given().auth().oauth2(fresh).get("/api/auth/me").then().statusCode(200);
        login(email, "brandnewpass", 200);
        given().contentType(ContentType.JSON).body(Map.of("currentPassword", "x", "newPassword", "brandnewpass"))
                .post("/api/auth/password/change").then().statusCode(401);
        assertEquals("Your Apex Commerce password was changed", mails(email, 1).get(0).getSubject());
    }

    @Transactional
    void expire(String token) {
        PasswordResetToken t = PasswordResetToken.find("tokenHash", PasswordResetToken.hash(token)).firstResult();
        t.expiresAt = Instant.now().minusSeconds(1);
    }
}
