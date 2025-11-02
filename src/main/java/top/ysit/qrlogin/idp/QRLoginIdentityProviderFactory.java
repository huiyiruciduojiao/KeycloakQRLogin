package top.ysit.qrlogin.idp;

import org.keycloak.Config.Scope;
import org.keycloak.broker.provider.AbstractIdentityProviderFactory;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;
import top.ysit.qrlogin.config.QRLoginConfig;
import top.ysit.qrlogin.core.SessionStore;
import top.ysit.qrlogin.core.security.SignatureConfig;
import top.ysit.qrlogin.core.util.QRLoginStoreUtil;

import java.util.*;

public class QRLoginIdentityProviderFactory extends AbstractIdentityProviderFactory<QRLoginIdentityProvider> {
    public static final String PROVIDER_ID = "qrlogin";


    @Override
    public String getName() {
        return "QR Login";
    }

    @Override
    public QRLoginIdentityProvider create(KeycloakSession session, IdentityProviderModel model) {
        // 直接实例化，不依赖注入
        SessionStore store = QRLoginStoreUtil.getSharedSessionStore(session, session.getContext().getRealm());
        QRLoginConfig config = new QRLoginConfig(model);
        return new QRLoginIdentityProvider(session, config, store);
    }

    @Override
    public QRLoginIdentityProvider create(KeycloakSession session) {
        IdentityProviderModel model = createConfig();
        QRLoginConfig config = new QRLoginConfig(model);
        SessionStore store = QRLoginStoreUtil.getSharedSessionStore(session, session.getContext().getRealm());
        return new QRLoginIdentityProvider(session, config, store);
    }


    @Override
    public Map<String, String> parseConfig(KeycloakSession session, String configJson) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(configJson, new com.fasterxml.jackson.core.type.TypeReference<>() {
            });
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse provider config JSON", e);
        }
    }

    @Override
    public IdentityProviderModel createConfig() {
        // 返回带有默认配置的 IdentityProviderModel
        IdentityProviderModel model = new IdentityProviderModel();
        model.setConfig(getDefaultConfig());
        return model;
    }

    private Map<String, String> getDefaultConfig() {
        Map<String, String> config = new HashMap<>();
        config.put("sessionTtlSeconds", "120");
        config.put("pollIntervalMs", "1500");
        config.put("storeType", "memory");
        config.put("redisUri", "redis://127.0.0.1:6379");
        config.put("redisNamespace", "qrlogin:");
        config.put("hmacSecret", "change-me");
        config.put("algorithm", SignatureConfig.SignatureAlgorithm.HMAC_SHA256.toString());
        config.put("timeWindowSeconds", "5");
        return config;
    }


    @Override
    public void init(Scope config) {
        // 初始化逻辑
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // 后初始化逻辑
    }

    @Override
    public void close() {
        // 清理资源
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        List<ProviderConfigProperty> props = new ArrayList<>();
        props.add(prop("sessionTtlSeconds", "Session TTL (s)", "二维码会话有效期(秒)", ProviderConfigProperty.STRING_TYPE, "120"));
        props.add(prop("pollIntervalMs", "Poll Interval (ms)", "前端轮询间隔(毫秒)", ProviderConfigProperty.STRING_TYPE, "1500"));
        props.add(prop("storeType", "Store Type", "redis 或 memory", "memory", Arrays.asList("redis", "memory")));
        props.add(prop("redisUri", "Redis URI", "如 redis://127.0.0.1:6379", ProviderConfigProperty.STRING_TYPE, "redis://127.0.0.1:6379"));
        props.add(prop("redisNamespace", "Redis Namespace", "键前缀", ProviderConfigProperty.STRING_TYPE, "qrlogin:"));
        props.add(prop("hmacSecret", "HMAC Secret", "App 签名校验秘钥", ProviderConfigProperty.PASSWORD, "change-me"));
        props.add(prop("algorithm", "Algorithm", "签名算法", SignatureConfig.SignatureAlgorithm.HMAC_SHA256.toString(), SignatureConfig.SignatureAlgorithm.toStringList()));
        props.add(prop("timeWindowSeconds", "Time Window Seconds", "请求有效时间范围(秒)", ProviderConfigProperty.INTEGER_TYPE,"5"));
        return props;
    }

    private ProviderConfigProperty prop(String name, String label, String help, String type, String def) {
        ProviderConfigProperty p = new ProviderConfigProperty();
        p.setName(name);
        p.setLabel(label);
        p.setHelpText(help);
        p.setType(type);
        p.setDefaultValue(def);
        return p;
    }

    private ProviderConfigProperty prop(String name, String label, String help, String def, List<String> options) {
        ProviderConfigProperty p = prop(name, label, help, ProviderConfigProperty.LIST_TYPE, def);
        p.setOptions(options);
        return p;
    }
}