package top.ysit.qrlogin.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.models.*;
import org.keycloak.representations.AccessToken;
import org.keycloak.services.managers.AuthenticationManager;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static top.ysit.qrlogin.auth.QRTransactionStore.Status.*;

class QRLoginResourceTest {
    private final KeycloakSession session = mock(KeycloakSession.class, RETURNS_DEEP_STUBS);
    private final QRTransactionStore store = new QRTransactionStore();
    private final UserModel user = mock(UserModel.class);
    private final ClientModel client = mock(ClientModel.class);
    private final AccessToken token = new AccessToken();
    private final Function<String, AuthenticationManager.AuthResult> auth = mock(Function.class);
    private final QRMobileAudit audit = mock(QRMobileAudit.class);
    private QRLoginResource resource;
    private QRTransactionStore.Transaction t;
    @BeforeEach void setup() {
        when(session.getContext().getRealm().getId()).thenReturn("r");
        when(session.clients().getClientByClientId(session.getContext().getRealm(), "mobile")).thenReturn(client);
        when(client.isEnabled()).thenReturn(true);
        when(user.getId()).thenReturn("alice"); when(user.isEnabled()).thenReturn(true);
        token.issuedFor("mobile");
        when(auth.apply("qr-api")).thenReturn(new AuthenticationManager.AuthResult(user, mock(UserSessionModel.class), token, client));
        t = store.create(new QRTransactionStore.Binding("r", "root", "tab", "client", "exec"), "mobile", "qr-api", "Portal", 120);
        resource = new QRLoginResource(session, store, auth, audit);
    }
    @Test void approvesWithoutReturningTokens() {
        try (var response = resource.scan(t.id())) {
            assertEquals(200, response.getStatus()); assertEquals("no-store", response.getHeaderString("Cache-Control"));
            assertFalse(response.getEntity().toString().contains("token"));
        }
        assertEquals(200, resource.confirm(t.id()).getStatus());
        assertEquals(CONFIRMED, store.get("r", t.id()).status());
        verify(auth, times(2)).apply("qr-api");
        verify(audit).record(eq(QRMobileAudit.Action.SCAN), eq(QRMobileAudit.Outcome.SUCCESS),
                eq(t.id()), any(QRTransactionStore.Transaction.class), any(AuthenticationManager.AuthResult.class));
        verify(audit).record(eq(QRMobileAudit.Action.CONFIRM), eq(QRMobileAudit.Outcome.SUCCESS),
                eq(t.id()), any(QRTransactionStore.Transaction.class), any(AuthenticationManager.AuthResult.class));
    }
    @Test void requiresValidBearerAuthentication() {
        when(auth.apply("qr-api")).thenReturn(null);
        assertEquals(401, resource.scan(t.id()).getStatus());
        assertEquals(PENDING, store.get("r", t.id()).status());
        verify(audit).record(QRMobileAudit.Action.SCAN, QRMobileAudit.Outcome.INVALID_TOKEN,
                t.id(), t, null);
    }
    @Test void refusesAuthenticationResultWithoutAnAccessToken() {
        var incomplete = new AuthenticationManager.AuthResult(user, mock(UserSessionModel.class), null, client);
        when(auth.apply("qr-api")).thenReturn(incomplete);
        assertEquals(401, resource.scan(t.id()).getStatus());
        assertEquals(PENDING, store.get("r", t.id()).status());
        verify(audit).record(QRMobileAudit.Action.SCAN, QRMobileAudit.Outcome.INVALID_TOKEN,
                t.id(), t, incomplete);
    }
    @Test void refusesWrongAuthorizedParty() {
        token.issuedFor("other-client"); assertEquals(403, resource.scan(t.id()).getStatus());
        verify(audit).record(eq(QRMobileAudit.Action.SCAN), eq(QRMobileAudit.Outcome.NOT_ALLOWED),
                eq(t.id()), eq(t), any(AuthenticationManager.AuthResult.class));
    }
    @Test void usersWithoutClientRolesCanScanAndDeny() {
        assertEquals(200, resource.scan(t.id()).getStatus());
        assertEquals(200, resource.deny(t.id()).getStatus());
        assertEquals(DENIED, store.get("r", t.id()).status());
        verify(audit).record(eq(QRMobileAudit.Action.DENY), eq(QRMobileAudit.Outcome.SUCCESS),
                eq(t.id()), any(QRTransactionStore.Transaction.class), any(AuthenticationManager.AuthResult.class));
    }
    @Test void refusesDisabledUserAndMissingUserSession() {
        when(user.isEnabled()).thenReturn(false);
        assertEquals(403, resource.scan(t.id()).getStatus());
        when(user.isEnabled()).thenReturn(true);
        when(auth.apply("qr-api")).thenReturn(new AuthenticationManager.AuthResult(user, null, token, client));
        assertEquals(401, resource.scan(t.id()).getStatus());
    }
    @Test void refusesServiceAccountsAndDisabledClients() {
        when(user.getServiceAccountClientLink()).thenReturn("service-client");
        assertEquals(403, resource.scan(t.id()).getStatus());
        when(user.getServiceAccountClientLink()).thenReturn(null); when(client.isEnabled()).thenReturn(false);
        assertEquals(403, resource.scan(t.id()).getStatus());
    }
    @Test void refusesConfirmationByAnotherUser() {
        assertEquals(200, resource.scan(t.id()).getStatus());
        when(user.getId()).thenReturn("bob");
        assertEquals(409, resource.confirm(t.id()).getStatus());
        assertEquals(SCANNED, store.get("r", t.id()).status());
        verify(audit).record(eq(QRMobileAudit.Action.CONFIRM), eq(QRMobileAudit.Outcome.INVALID_TRANSITION),
                eq(t.id()), any(QRTransactionStore.Transaction.class), any(AuthenticationManager.AuthResult.class));
    }
    @Test void rejectsCrossRealmAndMalformedIds() {
        when(session.getContext().getRealm().getId()).thenReturn("other-realm");
        assertEquals(404, resource.scan(t.id()).getStatus());
        assertEquals(404, resource.scan("..").getStatus()); verifyNoInteractions(auth);
        verify(audit).record(QRMobileAudit.Action.SCAN, QRMobileAudit.Outcome.TRANSACTION_NOT_FOUND,
                t.id(), null, null);
        verify(audit).record(QRMobileAudit.Action.SCAN, QRMobileAudit.Outcome.TRANSACTION_NOT_FOUND,
                "..", null, null);
    }
}
