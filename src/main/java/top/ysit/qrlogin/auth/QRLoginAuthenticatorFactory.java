package top.ysit.qrlogin.auth;

import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.*;
import org.keycloak.provider.ProviderConfigProperty;
import java.util.List;

public final class QRLoginAuthenticatorFactory implements AuthenticatorFactory {
    public static final String ID = "qr-login-authenticator";
    private final QRTransactionStore store = new QRTransactionStore();
    public QRTransactionStore store() { return store; }
    public static QRTransactionStore store(KeycloakSession session) {
        return ((QRLoginAuthenticatorFactory) session.getKeycloakSessionFactory()
                .getProviderFactory(Authenticator.class, ID)).store();
    }
    @Override public Authenticator create(KeycloakSession session) { return new QRLoginAuthenticator(store); }
    @Override public String getId() { return ID; }
    @Override public String getDisplayType() { return "QR Login (single node)"; }
    @Override public String getReferenceCategory() { return "qr-login"; }
    @Override public boolean isConfigurable() { return true; }
    @Override public boolean isUserSetupAllowed() { return false; }
    @Override public String getHelpText() { return "Existing same-realm user approves login using a mobile bearer token. Single-node only."; }
    @Override public AuthenticationExecutionModel.Requirement[] getRequirementChoices() {
        return new AuthenticationExecutionModel.Requirement[] { AuthenticationExecutionModel.Requirement.REQUIRED,
                AuthenticationExecutionModel.Requirement.ALTERNATIVE, AuthenticationExecutionModel.Requirement.DISABLED };
    }
    @Override public List<ProviderConfigProperty> getConfigProperties() {
        return List.of(property("mobileClientId", "Mobile client ID", "Required authorized party (azp).", null),
                property("audience", "Token audience", "Required audience dedicated to QR confirmation.", null),
                property("ttlSeconds", "QR validity (seconds)", "30 to 300 seconds.", "120"));
    }
    private ProviderConfigProperty property(String name, String label, String help, String defaultValue) {
        ProviderConfigProperty p = new ProviderConfigProperty();
        p.setName(name); p.setLabel(label); p.setHelpText(help);
        p.setType(ProviderConfigProperty.STRING_TYPE); p.setDefaultValue(defaultValue); return p;
    }
    @Override public void init(Config.Scope config) {}
    @Override public void postInit(KeycloakSessionFactory factory) {}
    @Override public void close() { store.clear(); }
}
