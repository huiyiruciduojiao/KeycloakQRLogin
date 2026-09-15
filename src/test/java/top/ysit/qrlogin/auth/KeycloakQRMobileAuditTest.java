package top.ysit.qrlogin.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KeycloakQRMobileAuditTest {
    @Test void transactionFingerprintIsStableAndDoesNotExposeTheTransactionId() {
        String id = "8s-Rj4bW_kJquUXcJjGhYLkTvgHYBQ7m1L3oN6ZcX20";
        String fingerprint = KeycloakQRMobileAudit.fingerprint(id);

        assertEquals(32, fingerprint.length());
        assertEquals(fingerprint, KeycloakQRMobileAudit.fingerprint(id));
        assertNotEquals(id, fingerprint);
        assertFalse(fingerprint.contains(id));
    }

    @Test void missingTransactionIdHasAConstantNonSecretMarker() {
        assertEquals("missing", KeycloakQRMobileAudit.fingerprint(null));
        assertEquals("missing", KeycloakQRMobileAudit.fingerprint(" "));
    }
}
