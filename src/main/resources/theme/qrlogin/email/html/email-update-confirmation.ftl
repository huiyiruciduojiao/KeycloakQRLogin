<#import "template.ftl" as layout>
<@layout.emailLayout fallbackUrl=link>
${kcSanitize(msg("emailUpdateConfirmationBodyHtml",link, newEmail, realmName, linkExpirationFormatter(linkExpiration)))?no_esc}
</@layout.emailLayout>
