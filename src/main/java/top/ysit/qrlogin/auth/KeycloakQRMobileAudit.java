package top.ysit.qrlogin.auth;

import org.keycloak.events.EventBuilder;
import org.keycloak.events.EventType;
import org.keycloak.models.KeycloakSession;
import org.keycloak.services.managers.AuthenticationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** Emits QR mobile actions into Keycloak's user-event pipeline without recording QR secrets or tokens. */
final class KeycloakQRMobileAudit implements QRMobileAudit {
    private static final String EVENT_KIND = "qr_mobile_transaction";
    private final KeycloakSession session;

    KeycloakQRMobileAudit(KeycloakSession session) {
        this.session = session;
    }

    @Override
    public void record(Action action, Outcome outcome, String requestedId,
                       QRTransactionStore.Transaction transaction,
                       AuthenticationManager.AuthResult authentication) {
        boolean success = outcome == Outcome.SUCCESS;
        EventBuilder event = new EventBuilder(session.getContext().getRealm(), session,
                session.getContext().getConnection())
                .event(success ? EventType.OAUTH2_DEVICE_VERIFY_USER_CODE
                        : EventType.OAUTH2_DEVICE_VERIFY_USER_CODE_ERROR)
                .detail("qr_event", EVENT_KIND)
                .detail("qr_action", action.name().toLowerCase(Locale.ROOT))
                .detail("qr_outcome", outcome.name().toLowerCase(Locale.ROOT))
                .detail("qr_transaction_fingerprint", fingerprint(requestedId));

        if (transaction != null) {
            event.client(transaction.mobileClient())
                    .detail("qr_browser_client", transaction.binding().client())
                    .detail("qr_state", transaction.status().name().toLowerCase(Locale.ROOT));
        }
        if (authentication != null) {
            if (authentication.getUser() != null) event.user(authentication.getUser());
            if (authentication.getSession() != null) event.session(authentication.getSession());
        }

        if (success) event.success();
        else event.error(outcome.name().toLowerCase(Locale.ROOT));
    }

    static String fingerprint(String transactionId) {
        if (transactionId == null || transactionId.isBlank()) return "missing";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(transactionId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
