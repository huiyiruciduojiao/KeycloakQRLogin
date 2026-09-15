<#-- Based on Keycloak 26.4.2 keycloak.v2/login.ftl; preserve native login behavior. -->
<#import "template.ftl" as layout>
<#import "field.ftl" as field>
<#import "buttons.ftl" as buttons>
<#import "social-providers.ftl" as identityProviders>
<#import "passkeys.ftl" as passkeys>
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('username','password') displayInfo=realm.password && realm.registrationAllowed && !registrationDisabled??; section>
    <#if section = "header">
        ${msg("loginAccountTitle")}
    <#elseif section = "form">
        <div id="kc-form"><div id="kc-form-wrapper">
            <#-- Use only choices allowed by the current Keycloak flow, never a hardcoded execution ID. -->
            <#if auth?? && auth.authenticationSelections??>
                <#list auth.authenticationSelections as selection>
                    <#if selection.displayName == "qr-login-authenticator-display-name">
                        <form id="qr-login-entry" class="auth-login-tabs" action="${url.loginAction}" method="post">
                            <input type="hidden" name="authenticationExecution" value="${selection.authExecId}">
                            <button type="button" id="qr-dialog-close" hidden aria-pressed="true" aria-controls="kc-form-login">${msg("authAccountLogin")}</button>
                            <button type="submit" aria-pressed="false" aria-controls="qr-login-dialog" id="qr-login-open" class="pf-v5-c-button pf-m-secondary pf-m-block">${msg("qrDirectLogin")}</button>
                        </form>
                        <section hidden id="qr-login-dialog" aria-labelledby="qr-login-open" data-loading="${msg('qrLoading')}" data-error="${msg('qrModalError')}">
                            <div id="qr-dialog-content" aria-live="polite"></div>
                        </section>
                        <#break>
                    </#if>
                </#list>
            </#if>
            <#if realm.password>
                <form id="kc-form-login" class="${properties.kcFormClass!}" onsubmit="login.disabled = true; return true;" action="${url.loginAction}" method="post" novalidate="novalidate">
                    <#if !usernameHidden??>
                        <#assign label><#if !realm.loginWithEmailAllowed>${msg("username")}<#elseif !realm.registrationEmailAsUsername>${msg("usernameOrEmail")}<#else>${msg("email")}</#if></#assign>
                        <@field.input name="username" label=label error=kcSanitize(messagesPerField.getFirstError('username','password'))?no_esc
                            autofocus=true autocomplete="${(enableWebAuthnConditionalUI?has_content)?then('username webauthn', 'username')}" value=login.username!'' />
                        <@field.password name="password" label=msg("password") error="" forgotPassword=realm.resetPasswordAllowed autofocus=usernameHidden?? autocomplete="current-password">
                            <#if realm.rememberMe && !usernameHidden??><@field.checkbox name="rememberMe" label=msg("rememberMe") value=login.rememberMe?? /></#if>
                        </@field.password>
                    <#else>
                        <@field.password name="password" label=msg("password") forgotPassword=realm.resetPasswordAllowed autofocus=usernameHidden?? autocomplete="current-password">
                            <#if realm.rememberMe && !usernameHidden??><@field.checkbox name="rememberMe" label=msg("rememberMe") value=login.rememberMe?? /></#if>
                        </@field.password>
                    </#if>
                    <input type="hidden" id="id-hidden-input" name="credentialId" <#if auth.selectedCredential?has_content>value="${auth.selectedCredential}"</#if>/>
                    <@buttons.loginButton />
                </form>
            </#if>
        </div></div>
        <@passkeys.conditionalUIData />
    <#elseif section = "socialProviders">
        <#if realm.password && social.providers?? && social.providers?has_content>
            <#-- Preserve legacy IdP records/links for rollback, but do not advertise the removed v1 endpoint. -->
            <#assign visibleProviders = social.providers?filter(p -> p.alias != (properties.qrLegacyIdpAlias!'qrlogin'))>
            <#if visibleProviders?has_content><@identityProviders.show social={"providers": visibleProviders}/></#if>
        </#if>
    <#elseif section = "info">
        <#if realm.password && realm.registrationAllowed && !registrationDisabled??>
            <div id="kc-registration-container"><div id="kc-registration">
                <span>${msg("noAccount")} <a href="${url.registrationUrl}">${msg("doRegister")}</a></span>
            </div></div>
        </#if>
    </#if>
</@layout.registrationLayout>
