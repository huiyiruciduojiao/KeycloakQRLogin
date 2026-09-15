<#import "template.ftl" as layout>
<@layout.emailLayout fallbackUrl=link>
${kcSanitize(msg("emailVerificationBodyHtml",link, linkExpiration, realmName, linkExpirationFormatter(linkExpiration)))?no_esc}
</@layout.emailLayout>
