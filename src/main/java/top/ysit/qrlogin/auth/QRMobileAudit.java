package top.ysit.qrlogin.auth;

import org.keycloak.services.managers.AuthenticationManager;

/** Security-audit boundary for authenticated mobile QR transaction mutations. */
interface QRMobileAudit {
    enum Action { SCAN, CONFIRM, DENY }
    enum Outcome { SUCCESS, TRANSACTION_NOT_FOUND, INVALID_TOKEN, NOT_ALLOWED, INVALID_TRANSITION }

    void record(Action action, Outcome outcome, String requestedId,
                QRTransactionStore.Transaction transaction,
                AuthenticationManager.AuthResult authentication);
}
