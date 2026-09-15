package top.ysit.qrlogin.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit, interactive test of the original loopback instance. No password collection or database writes. */
@EnabledIfSystemProperty(named = "qr.y.live", matches = "true")
class QRYDriveIT {
    static final String BASE = "http://127.0.0.1:8080/realms/master";
    static final String CALLBACK = "http://127.0.0.1:5000/qr-mobile/callback";
    static final String WEB_CALLBACK = "http://127.0.0.1:5000/";
    record Browser(HttpClient client, CookieManager cookies, String action, JsonNode qr, String verifier, String passwordExecution) {}
    static String random() { return UUID.randomUUID().toString() + UUID.randomUUID(); }
    static String challenge(String verifier) throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    }
    static String match(String html, String regex) {
        var m = Pattern.compile(regex, Pattern.DOTALL).matcher(html);
        assertTrue(m.find(), "Expected login element not found");
        return m.group(1).replace("&amp;", "&");
    }
    static String action(String html, String id) {
        return match(html, "<form[^>]*id=\"" + id + "\"[^>]*action=\"([^\"]+)\"");
    }
    static void loopbackCookies(CookieManager manager) {
        // Only this hardcoded HTTP loopback fixture: match browsers' localhost Secure-cookie handling.
        manager.getCookieStore().getCookies().forEach(c -> { c.setVersion(0); c.setSecure(false); });
    }
    static Browser browser() throws Exception {
        String verifier = random();
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build();
        String url = BASE + "/protocol/openid-connect/auth?" + QRLiveIT.form(Map.of(
                "client_id", "test", "redirect_uri", WEB_CALLBACK, "response_type", "code", "scope", "openid",
                "state", random(), "code_challenge_method", "S256", "code_challenge", challenge(verifier)));
        var initial = client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, initial.statusCode());
        assertTrue(initial.body().contains("id=\"password\""), "Password option must remain available");
        assertFalse(initial.body().contains("id=\"social-qrlogin\""), "Removed v1 endpoint must not be advertised");
        loopbackCookies(cookies);
        String entry = match(initial.body(), "(<form[^>]*id=\"qr-login-entry\".*?</form>)");
        String qrExecution = match(entry, "name=\"authenticationExecution\"[^>]*value=\"([^\"]+)\"");
        var page = QRLiveIT.post(client, action(initial.body(), "qr-login-entry"), Map.of("authenticationExecution", qrExecution));
        assertEquals(200, page.statusCode());
        String image = match(page.body(), "src=\"data:image/png;base64,([^\"]+)\"");
        String data = new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(
                new BufferedImageLuminanceSource(ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(image))))))).getText();
        JsonNode qr = QRLiveIT.JSON.readTree(data);
        assertEquals(2, qr.path("v").asInt());
        assertEquals(BASE + "/qr-login", qr.path("verification_uri").asText());
        assertFalse(qr.has("token"));
        loopbackCookies(cookies);
        String passwordExecution = query(URI.create(action(initial.body(), "qr-login-entry")).getRawQuery()).get("execution");
        return new Browser(client, cookies, action(page.body(), "qr-login-form"), qr, verifier, passwordExecution);
    }
    static int mobile(Browser b, String operation, String token) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(BASE + "/qr-login/transactions/"
                + b.qr().path("transaction").asText() + "/" + operation))
                .header("Authorization", "Bearer " + token).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }
    static Map<String,String> query(String raw) {
        Map<String,String> values = new HashMap<>();
        if (raw != null) for (String pair : raw.split("&")) {
            String[] p = pair.split("=", 2);
            values.put(URLDecoder.decode(p[0], StandardCharsets.UTF_8), p.length == 2 ? URLDecoder.decode(p[1], StandardCharsets.UTF_8) : "");
        }
        return values;
    }
    @Test void originalRealmPreflight() throws Exception {
        Browser b = browser();
        String currentAction = b.action();
        for (int i = 0; i < 3; i++) {
            var response = QRLiveIT.post(b.client(), currentAction, Map.of("operation", "status"));
            assertEquals(200, response.statusCode());
            assertTrue(response.headers().firstValue("content-type").orElse("").contains("application/json"));
            assertTrue(response.headers().firstValue("cache-control").orElse("").contains("no-store"));
            JsonNode state = QRLiveIT.JSON.readTree(response.body());
            assertEquals("PENDING", state.path("state").asText());
            assertFalse(state.has("subject")); assertFalse(state.has("token"));
            currentAction = state.path("action").asText();
            assertTrue(currentAction.startsWith(BASE + "/login-actions/authenticate?"));
            loopbackCookies(b.cookies());
        }
        assertEquals(401, mobile(b, "scan", "not-a-token"));
        var refresh = QRLiveIT.post(b.client(), currentAction, Map.of("operation", "restart"));
        assertEquals(200, refresh.statusCode());
        assertTrue(refresh.body().contains("qr-login-form"));
        var dismiss = QRLiveIT.post(b.client(), action(refresh.body(), "qr-login-form"), Map.of("operation", "dismiss"));
        assertEquals(200, dismiss.statusCode());
        JsonNode dismissed = QRLiveIT.JSON.readTree(dismiss.body());
        var password = QRLiveIT.post(b.client(), dismissed.path("action").asText(), Map.of("authenticationExecution", b.passwordExecution()));
        assertEquals(200, password.statusCode());
        assertTrue(password.body().contains("id=\"kc-form-login\""));
        assertTrue(password.body().contains("id=\"qr-login-entry\""));
        System.out.println("Y_DIRECT_ENTRY_AND_BACKGROUND_STATUS_OK");
    }
    @Test void originalRealmInteractiveApproval() throws Exception {
        // Tokens live only in this process, never written to files, logs or the browser response.
        CompletableFuture<String> mobileToken = new CompletableFuture<>();
        String state = random(), verifier = random();
        String login = BASE + "/protocol/openid-connect/auth?" + QRLiveIT.form(Map.of(
                "client_id", "qr-mobile", "redirect_uri", CALLBACK, "response_type", "code", "scope", "openid",
                "state", state, "code_challenge_method", "S256", "code_challenge", challenge(verifier),
                "prompt", "login", "login_hint", "test001"));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 5000), 0);
        server.createContext("/qr-mobile/start", x -> {
            x.getResponseHeaders().set("Cache-Control", "no-store");
            x.getResponseHeaders().set("Location", login); x.sendResponseHeaders(302, -1); x.close();
        });
        server.createContext("/qr-mobile/callback", x -> {
            String message = "Login rejected. Return to the test start page."; int status = 400;
            try {
                var params = query(x.getRequestURI().getRawQuery());
                if (!state.equals(params.get("state")) || !params.containsKey("code")) throw new IllegalArgumentException();
                var response = QRLiveIT.post(HttpClient.newHttpClient(), BASE + "/protocol/openid-connect/token", Map.of(
                        "grant_type", "authorization_code", "client_id", "qr-mobile", "redirect_uri", CALLBACK,
                        "code", params.get("code"), "code_verifier", verifier));
                if (response.statusCode() != 200) throw new IllegalArgumentException();
                String token = QRLiveIT.JSON.readTree(response.body()).path("access_token").asText();
                JsonNode claims = QRLiveIT.JSON.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
                if (!"test001".equals(claims.path("preferred_username").asText())) throw new IllegalArgumentException();
                mobileToken.complete(token); status = 200;
                message = "Test account login accepted. You may return to Codex. No tokens are displayed or saved.";
            } catch (Exception ignored) { /* No credentials or authorization codes in logs. */ }
            byte[] body = message.getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            x.getResponseHeaders().set("Cache-Control", "no-store");
            x.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            x.sendResponseHeaders(status, body.length); x.getResponseBody().write(body); x.close();
        });
        server.start();
        try {
            Browser preflight = browser();
            assertEquals(401, mobile(preflight, "scan", "not-a-token"));
            System.out.println("Y_INSTANCE_PREFLIGHT_OK: password + QR choice + QR payload + invalid-token rejection");
            System.out.println("USER_LOGIN_REQUIRED: http://127.0.0.1:5000/qr-mobile/start (test001)");
            String token = mobileToken.get(15, TimeUnit.MINUTES);
            JsonNode claims = QRLiveIT.JSON.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
            assertEquals("qr-mobile", claims.path("azp").asText());
            assertTrue(claims.path("aud").toString().contains("qr-confirm"));
            assertFalse(claims.path("resource_access").has("master-realm"), "Mobile token must not carry admin roles");
            Browser b = browser();
            assertEquals(200, mobile(b, "scan", token));
            assertEquals(200, mobile(b, "confirm", token));
            var done = QRLiveIT.post(b.client(), b.action(), Map.of("operation", "poll"));
            assertEquals(302, done.statusCode(), "Expected callback; configured OTP/required actions may require an interactive step");
            URI callback = URI.create(done.headers().firstValue("location").orElseThrow());
            assertTrue(callback.toString().startsWith(WEB_CALLBACK + "?"));
            String code = query(callback.getRawQuery()).get("code"); assertNotNull(code);
            var exchange = QRLiveIT.post(HttpClient.newHttpClient(), BASE + "/protocol/openid-connect/token", Map.of(
                    "grant_type", "authorization_code", "client_id", "test", "redirect_uri", WEB_CALLBACK,
                    "code", code, "code_verifier", b.verifier()));
            assertEquals(200, exchange.statusCode());
            String access = QRLiveIT.JSON.readTree(exchange.body()).path("access_token").asText();
            JsonNode webClaims = QRLiveIT.JSON.readTree(Base64.getUrlDecoder().decode(access.split("\\.")[1]));
            assertEquals("test001", webClaims.path("preferred_username").asText());
            assertEquals(claims.path("sub").asText(), webClaims.path("sub").asText());
            assertEquals("test", webClaims.path("azp").asText());
            assertEquals(409, mobile(b, "confirm", token));
            Browser denied = browser();
            assertEquals(200, mobile(denied, "scan", token));
            assertEquals(200, mobile(denied, "deny", token));
            var denial = QRLiveIT.post(denied.client(), denied.action(), Map.of("operation", "poll"));
            assertEquals(200, denial.statusCode());
            assertTrue(denial.body().contains("data-qr-state=\"DENIED\""));
            assertFalse(denial.headers().firstValue("location").orElse("").contains("error=access_denied"));
            Browser restarted = browser();
            assertEquals(200, QRLiveIT.post(restarted.client(), restarted.action(), Map.of("operation", "restart")).statusCode());
            assertEquals(409, mobile(restarted, "scan", token));
            System.out.println("Y_INSTANCE_APPROVAL_OK: test001 subject preserved, PKCE exchange, replay, denial, restart");
        } finally { server.stop(0); }
    }
}
