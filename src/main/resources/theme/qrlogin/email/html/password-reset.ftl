<#import "template.ftl" as layout>
<@layout.emailLayout fallbackUrl=link>
${kcSanitize(msg("passwordResetBodyHtml",link, linkExpiration, realmName, linkExpirationFormatter(linkExpiration)))?no_esc}
</@layout.emailLayout>
