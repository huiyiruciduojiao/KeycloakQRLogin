package top.ysit.qrlogin.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.authentication.*;
import org.keycloak.models.*;
import org.keycloak.sessions.AuthenticationSessionModel;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import org.mockito.ArgumentCaptor;
import java.net.URI;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static top.ysit.qrlogin.auth.QRTransactionStore.Status.*;

class QRLoginAuthenticatorTest {
    private final QRTransactionStore store = new QRTransactionStore();
    private final QRLoginAuthenticator authenticator = new QRLoginAuthenticator(store);
    private final AuthenticationFlowContext context = mock(AuthenticationFlowContext.class, RETURNS_DEEP_STUBS);
    private final UserModel user = mock(UserModel.class);
    private final QRTransactionStore.Binding binding = new QRTransactionStore.Binding("r", "root", "tab", "client", "exec");
    private QRTransactionStore.Transaction transaction;
    @BeforeEach void setup() {
        when(context.getRealm().getId()).thenReturn("r");
        when(context.getRealm().getName()).thenReturn("r");
        AuthenticationSessionModel s = context.getAuthenticationSession();
        when(s.getParentSession().getId()).thenReturn("root");
        when(s.getTabId()).thenReturn("tab");
        when(s.getClient().getId()).thenReturn("client");
        when(context.getExecution().getId()).thenReturn("exec");
        when(context.getHttpRequest().getDecodedFormParameters()).thenReturn(new MultivaluedHashMap<>());
        when(context.getUser()).thenReturn(null);
        when(context.getSession().users().getUserById(context.getRealm(), "alice")).thenReturn(user);
        when(user.getId()).thenReturn("alice"); when(user.isEnabled()).thenReturn(true);
        transaction = store.create(binding, "mobile", "qr-api", "Portal", 120);
        when(s.getAuthNote("qr-login.transaction")).thenReturn(transaction.id());
        store.advance("r", transaction.id(), "alice", SCANNED);
        store.advance("r", transaction.id(), "alice", CONFIRMED);
    }
    @Test void completesThroughKeycloakContextOnlyOnce() {
        authenticator.action(context);
        verify(context).setUser(user); verify(context).success();
        assertEquals(CONSUMED, store.get("r", transaction.id()).status());
        authenticator.action(context);
        verify(context, times(1)).success();
    }
    @Test void disabledUserCannotComplete() {
        when(user.isEnabled()).thenReturn(false);
        authenticator.action(context);
        verify(context, never()).success();
        assertEquals(CANCELLED, store.get("r", transaction.id()).status());
    }
    @Test void cannotReplaceUserIdentifiedByAnEarlierExecution() {
        UserModel other = mock(UserModel.class); when(other.getId()).thenReturn("bob");
        when(context.getUser()).thenReturn(other);
        authenticator.action(context);
        verify(context).failure(AuthenticationFlowError.INVALID_USER);
        verify(context, never()).setUser(any());
    }
    @Test void lockedUserCannotComplete() {
        when(context.getProtector().isTemporarilyDisabled(context.getSession(), context.getRealm(), user)).thenReturn(true);
        authenticator.action(context); verify(context, never()).success();
    }
    @Test void wrongTabCannotConsume() {
        when(context.getAuthenticationSession().getTabId()).thenReturn("other-tab");
        authenticator.action(context);
        verify(context, never()).success();
        assertEquals(CONFIRMED, store.get("r", transaction.id()).status());
    }
    @Test void cancelInvalidatesTransaction() {
        var params = new MultivaluedHashMap<String, String>(); params.putSingle("operation", "cancel");
        when(context.getHttpRequest().getDecodedFormParameters()).thenReturn(params);
        authenticator.action(context);
        verify(context).cancelLogin(); assertEquals(CANCELLED, store.get("r", transaction.id()).status());
    }
    @Test void mobileDenialStaysOnQrPageInsteadOfCancellingClientLogin() {
        store.clear();
        transaction = store.create(binding, "mobile", "qr-api", "Portal", 120);
        store.advance("r", transaction.id(), "alice", SCANNED);
        store.advance("r", transaction.id(), "alice", DENIED);
        when(context.getAuthenticationSession().getAuthNote("qr-login.transaction")).thenReturn(transaction.id());
        when(context.getUriInfo().getBaseUri()).thenReturn(URI.create("http://localhost/"));

        authenticator.action(context);

        verify(context).challenge(any(Response.class));
        verify(context, never()).cancelLogin();
        verify(context, never()).success();
        assertEquals(DENIED, store.get("r", transaction.id()).status());
    }
    private Response statusResponse() {
        var params = new MultivaluedHashMap<String, String>(); params.putSingle("operation", "status");
        when(context.getHttpRequest().getDecodedFormParameters()).thenReturn(params);
        when(context.generateAccessCode()).thenReturn("next-code");
        when(context.getActionUrl("next-code")).thenReturn(URI.create("http://localhost/login-actions/authenticate?session_code=next-code"));
        authenticator.action(context);
        var response = ArgumentCaptor.forClass(Response.class);
        verify(context).challenge(response.capture());
        verify(context, never()).success(); verify(context, never()).setUser(any());
        return response.getValue();
    }
    @Test void backgroundStatusNeverConsumesConfirmation() {
        Response response = statusResponse();
        assertEquals(200, response.getStatus());
        assertEquals("no-store", response.getHeaderString("Cache-Control"));
        assertTrue(response.getEntity().toString().contains("CONFIRMED"));
        assertTrue(response.getEntity().toString().contains("next-code"));
        assertFalse(response.getEntity().toString().contains("alice"));
        assertEquals(CONFIRMED, store.get("r", transaction.id()).status());
    }
    @Test void backgroundStatusRejectsAnotherTab() {
        when(context.getAuthenticationSession().getTabId()).thenReturn("other");
        assertTrue(statusResponse().getEntity().toString().contains("EXPIRED"));
        assertEquals(CONFIRMED, store.get("r", transaction.id()).status());
    }
    @Test void backgroundStatusHandlesMissingTransaction() {
        when(context.getAuthenticationSession().getAuthNote("qr-login.transaction")).thenReturn("missing");
        assertTrue(statusResponse().getEntity().toString().contains("EXPIRED"));
    }
    @Test void dismissInvalidatesQrWithoutCancellingTheWholeLogin() {
        var params = new MultivaluedHashMap<String, String>(); params.putSingle("operation", "dismiss");
        when(context.getHttpRequest().getDecodedFormParameters()).thenReturn(params);
        when(context.generateAccessCode()).thenReturn("next-code");
        when(context.getActionUrl("next-code")).thenReturn(URI.create("http://localhost/login-actions/authenticate"));
        authenticator.action(context);
        assertEquals(CANCELLED, store.get("r", transaction.id()).status());
        verify(context, never()).success(); verify(context, never()).cancelLogin();
        verify(context.getAuthenticationSession()).removeAuthNote("qr-login.transaction");
    }
}
