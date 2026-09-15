<#import "template.ftl" as layout>
<@layout.emailLayout fallbackUrl=link>
${kcSanitize(msg("verifiableCredentialOfferBodyHtml",link, linkExpiration, realmName, credentialScopeDisplayName, linkExpirationFormatter(linkExpiration)))?no_esc}
</@layout.emailLayout>
