package top.ysit.qrlogin.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in integration checks for the isolated fixture. Never runs against production by default. */
@EnabledIfSystemProperty(named = "qr.live", matches = "true")
class QRLiveIT {
    static final String BASE = "http://127.0.0.1:8180/realms/qr-refactor-test";
    static final String PASSWORD = "Local-QR-Test-Only-2026!";
    static final ObjectMapper JSON = new ObjectMapper();
    record Browser(HttpClient client, String action, JsonNode qr, String verifier) {}
    static String form(Map<String, String> data) {
        return data.entrySet().stream().map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(java.util.stream.Collectors.joining("&"));
    }
    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    static HttpResponse<String> post(HttpClient client, String url, Map<String,String> values) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(values))).build(), HttpResponse.BodyHandlers.ofString());
    }
    static String token(String user, String client) throws Exception {
        var r = post(HttpClient.newHttpClient(), BASE + "/protocol/openid-connect/token",
                Map.of("grant_type", "password", "client_id", client, "username", user, "password", PASSWORD));
        assertEquals(200, r.statusCode(), "Test mobile token issuance failed");
        return JSON.readTree(r.body()).path("access_token").asText();
    }
    static Browser browser() throws Exception {
        String verifier = UUID.randomUUID().toString() + UUID.randomUUID();
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder().cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NEVER).build();
        String url = BASE + "/protocol/openid-connect/auth?" + form(Map.of("client_id", "qr-web",
                "redirect_uri", "http://127.0.0.1:8180/test-callback", "response_type", "code",
                "scope", "openid", "state", "qr-test", "code_challenge_method", "S256", "code_challenge", challenge));
        var response = client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "QR authentication page failed");
        // Match browser Cookie headers; Java otherwise quotes version-1 cookie values.
        cookies.getCookieStore().getCookies().forEach(cookie -> {
            cookie.setVersion(0);
            // Browsers treat loopback as trustworthy; the JDK client does not send Secure cookies over HTTP.
            // This exception is restricted to the hardcoded isolated loopback fixture.
            cookie.setSecure(false);
        });
        String html = response.body();
        var action = Pattern.compile("<form[^>]*id=\"qr-login-form\"[^>]*action=\"([^\"]+)\"").matcher(html);
        assertTrue(action.find(), "QR form missing: " + html.substring(0, Math.min(html.length(), 300)));
        var image = Pattern.compile("src=\"data:image/png;base64,([^\"]+)\"").matcher(html);
        assertTrue(image.find(), "QR image missing");
        var bitmap = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(image.group(1))));
        String payload = new MultiFormatReader().decode(new BinaryBitmap(
                new HybridBinarizer(new BufferedImageLuminanceSource(bitmap)))).getText();
        JsonNode qr = JSON.readTree(payload);
        assertEquals(2, qr.path("v").asInt());
        assertFalse(qr.has("token")); assertFalse(qr.has("kc_session"));
        return new Browser(client, action.group(1).replace("&amp;", "&"), qr, verifier);
    }
    static HttpResponse<String> mobile(Browser b, String operation, String token) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(BASE + "/qr-login/transactions/"
                        + b.qr().path("transaction").asText() + "/" + operation))
                .header("Authorization", "Bearer " + token).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }
    @Test void fullApprovalProducesCodeAndPkceTokensAndPreventsReplay() throws Exception {
        String token = token("alice", "qr-mobile"); Browser b = browser();
        assertEquals(200, mobile(b, "scan", token).statusCode());
        assertEquals(200, mobile(b, "confirm", token).statusCode());
        var done = post(b.client(), b.action(), Map.of("operation", "poll"));
        assertEquals(302, done.statusCode(), "Expected authorization callback");
        String location = done.headers().firstValue("location").orElseThrow();
        assertTrue(location.startsWith("http://127.0.0.1:8180/test-callback?"));
        var code = Pattern.compile("[?&]code=([^&]+)").matcher(location); assertTrue(code.find());
        var exchange = post(HttpClient.newHttpClient(), BASE + "/protocol/openid-connect/token",
                Map.of("grant_type", "authorization_code", "client_id", "qr-web", "code", URLDecoder.decode(code.group(1), StandardCharsets.UTF_8),
                        "code_verifier", b.verifier(), "redirect_uri", "http://127.0.0.1:8180/test-callback"));
        assertEquals(200, exchange.statusCode(), "PKCE exchange failed");
        String access = JSON.readTree(exchange.body()).path("access_token").asText();
        JsonNode claims = JSON.readTree(Base64.getUrlDecoder().decode(access.split("\\.")[1]));
        assertEquals("alice", claims.path("preferred_username").asText());
        assertEquals("qr-web", claims.path("azp").asText());
        assertEquals(409, mobile(b, "confirm", token).statusCode());
    }
    @Test void rejectsInvalidTokenAudienceAndAuthorizedParty() throws Exception {
        Browser b = browser();
        assertEquals(401, mobile(b, "scan", "not-a-token").statusCode());
        assertEquals(401, mobile(b, "scan", token("alice", "qr-wrong-audience")).statusCode());
        assertEquals(403, mobile(b, "scan", token("alice", "qr-wrong-client")).statusCode());
    }
    @Test void bindsUserAndDenialStaysOnQrPage() throws Exception {
        String alice = token("alice", "qr-mobile"), bob = token("bob", "qr-mobile"); Browser b = browser();
        assertEquals(200, mobile(b, "scan", alice).statusCode());
        assertEquals(409, mobile(b, "confirm", bob).statusCode());
        assertEquals(200, mobile(b, "deny", alice).statusCode());
        var result = post(b.client(), b.action(), Map.of("operation", "poll"));
        assertEquals(200, result.statusCode());
        assertTrue(result.body().contains("data-qr-state=\"DENIED\""));
        assertFalse(result.headers().firstValue("location").orElse("").contains("error=access_denied"));
    }
    @Test void restartingInvalidatesPreviousQr() throws Exception {
        String token = token("alice", "qr-mobile"); Browser b = browser();
        assertEquals(200, post(b.client(), b.action(), Map.of("operation", "restart")).statusCode());
        assertEquals(409, mobile(b, "scan", token).statusCode());
    }
}
