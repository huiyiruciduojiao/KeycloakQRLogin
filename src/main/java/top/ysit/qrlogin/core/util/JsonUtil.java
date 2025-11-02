package top.ysit.qrlogin.core.util;

import com.fasterxml.jackson.databind.ObjectMapper;

public class JsonUtil {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public static String toJson(Object o){ try { return MAPPER.writeValueAsString(o);} catch (Exception e){ throw new RuntimeException(e);} }
    public static <T> T fromJson(String s, Class<T> c){ try { return MAPPER.readValue(s,c);} catch (Exception e){ throw new RuntimeException(e);} }
}