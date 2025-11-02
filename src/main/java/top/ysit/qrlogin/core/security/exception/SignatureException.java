package top.ysit.qrlogin.core.security.exception;

public class SignatureException extends Exception {
    public SignatureException(String message) {
        super(message);
    }

    public SignatureException(String message, Throwable cause) {
        super(message, cause);
    }
}
