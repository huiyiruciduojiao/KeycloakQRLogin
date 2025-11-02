package top.ysit.qrlogin.core.util;


import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

public class CryptoUtil {
    public static String hmacSha256Base64(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 验证签名
     * @param data 数据
     * @param signature 签名
     * @param secret 密钥
     * @return 验证结果
     */
    public static boolean verifyHmacSha256Base64(String data, String signature, String secret) {
        try {
            String expectedSignature = hmacSha256Base64(data, secret);
            return expectedSignature.equals(signature);
        } catch (Exception e) {
            return false;
        }
    }

}