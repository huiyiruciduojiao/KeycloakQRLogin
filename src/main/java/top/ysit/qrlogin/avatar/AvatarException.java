package top.ysit.qrlogin.avatar;

/** Public, credential-free error contract. */
final class AvatarException extends RuntimeException {
    final int status;
    final String code;
    AvatarException(int status, String code) { super(code); this.status = status; this.code = code; }
}
