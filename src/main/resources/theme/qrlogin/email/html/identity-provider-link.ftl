<#import "template.ftl" as layout>
<@layout.emailLayout fallbackUrl=link>
${kcSanitize(msg("identityProviderLinkBodyHtml", identityProviderDisplayName, realmName, identityProviderContext.username, link, linkExpiration, linkExpirationFormatter(linkExpiration)))?no_esc}
</@layout.emailLayout>
