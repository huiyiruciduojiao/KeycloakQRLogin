<#ftl output_format="HTML" auto_esc=true>
<#macro emailLayout fallbackUrl="">
<!doctype html>
<html lang="${locale.language}" dir="${(ltr)?then('ltr','rtl')}">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="color-scheme" content="dark">
  <meta name="supported-color-schemes" content="dark">
  <title>${subject!realmName}</title>
  <style>
    :root { color-scheme: dark; supported-color-schemes: dark; }
    .ysit-email-content p { margin: 0 0 18px; }
    .ysit-email-content a {
      box-sizing: border-box;
      display: block;
      width: 100%;
      max-width: 360px;
      margin: 26px auto;
      padding: 15px 24px;
      border: 1px solid #62a8ff;
      border-radius: 7px;
      background: #1677e8;
      color: #ffffff !important;
      font-size: 17px;
      font-weight: 700;
      line-height: 1.4;
      text-align: center;
      text-decoration: none;
    }
    .ysit-email-content a:hover { background: #0f65c8; color: #ffffff !important; }
    .ysit-fallback-url {
      overflow-wrap: anywhere;
      word-break: break-all;
    }
    @media only screen and (max-width: 640px) {
      .ysit-shell { padding: 16px 10px !important; }
      .ysit-card { border-radius: 8px !important; }
      .ysit-header, .ysit-content, .ysit-footer { padding-left: 20px !important; padding-right: 20px !important; }
      .ysit-brand-name { font-size: 20px !important; }
      .ysit-subject { font-size: 24px !important; }
    }
  </style>
</head>
<body style="margin:0;padding:0;background:#0a111c;color:#edf3fa;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','Microsoft YaHei',Arial,sans-serif;">
  <table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0" style="width:100%;background:#0a111c;">
    <tr>
      <td class="ysit-shell" align="center" style="padding:40px 16px;">
        <table class="ysit-card" role="presentation" width="680" cellspacing="0" cellpadding="0" border="0" style="width:100%;max-width:680px;background:#101927;border:1px solid #344b66;border-radius:10px;overflow:hidden;box-shadow:0 18px 48px rgba(0,0,0,.28);">
          <tr>
            <td class="ysit-header" style="padding:25px 34px;background:#0d2745;border-bottom:1px solid #294562;">
              <table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0" style="width:100%;table-layout:fixed;">
                <tr>
                  <td style="vertical-align:middle;">
                    <table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0" style="width:100%;table-layout:fixed;">
                      <tr>
                        <td width="52" style="width:52px;vertical-align:middle;">
                          <div style="width:38px;padding:7px;background:#ffffff;border-radius:7px;">
                          <img src="${url.resourcesUrl}/img/brand-logo.png" width="38" height="38" alt="${realmName}" style="display:block;width:38px;height:38px;object-fit:contain;">
                          </div>
                        </td>
                        <td style="padding-left:16px;vertical-align:middle;overflow-wrap:anywhere;word-break:break-word;">
                          <div class="ysit-brand-name" style="color:#ffffff;font-size:22px;font-weight:700;line-height:1.3;overflow-wrap:anywhere;word-break:break-word;">${realmName}</div>
                          <div style="padding-top:4px;color:#afc2d8;font-size:13px;letter-spacing:2px;line-height:1.4;overflow-wrap:anywhere;word-break:break-word;">${msg("ysitMailSlogan")}</div>
                        </td>
                      </tr>
                    </table>
                  </td>
                </tr>
                <#if user?? && user.username?has_content>
                  <tr>
                    <td class="ysit-account" style="padding-top:18px;vertical-align:middle;">
                      <table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0" style="width:100%;table-layout:fixed;border-top:1px solid #49617b;">
                        <tr><td style="padding-top:14px;color:#9fb1c6;font-size:12px;line-height:1.4;">${msg("ysitAccountIdentifier")}</td></tr>
                        <tr><td style="padding-top:4px;color:#ffffff;font-size:15px;font-weight:650;line-height:1.4;overflow-wrap:anywhere;word-break:break-word;">${user.username}</td></tr>
                      </table>
                    </td>
                  </tr>
                </#if>
              </table>
            </td>
          </tr>
          <tr>
            <td class="ysit-content" style="padding:40px 44px 34px;color:#e8eef6;font-size:15px;line-height:1.8;word-break:break-word;">
              <table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0" style="width:100%;table-layout:fixed;margin-bottom:28px;">
                <tr>
                  <td width="50" style="width:50px;height:50px;border-radius:50%;background:#142e50;color:#59a3ff;font-size:25px;line-height:50px;text-align:center;vertical-align:middle;">✉</td>
                  <td class="ysit-subject" style="padding-left:18px;color:#ffffff;font-size:28px;font-weight:750;line-height:1.35;vertical-align:middle;overflow-wrap:anywhere;word-break:break-word;">${subject!realmName}</td>
                </tr>
              </table>
              <div class="ysit-email-content">
                <#nested>
              </div>
              <#if fallbackUrl?has_content>
                <table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0" style="margin-top:30px;border-top:1px solid #2d4055;">
                  <tr>
                    <td style="padding-top:22px;color:#9fb0c4;font-size:12px;line-height:1.7;">
                      <div>${msg("ysitFallbackLinkIntro")}</div>
                      <div class="ysit-fallback-url" style="margin-top:8px;padding:12px 14px;border:1px solid #344b66;border-radius:6px;background:#0c1521;color:#c9d8e8;font-family:Consolas,'Courier New',monospace;font-size:11px;line-height:1.6;overflow-wrap:anywhere;word-break:break-all;">${fallbackUrl}</div>
                    </td>
                  </tr>
                </table>
              </#if>
            </td>
          </tr>
          <tr>
            <td class="ysit-footer" style="padding:20px 34px;border-top:1px solid #26384c;background:#0e1724;color:#9fb0c4;font-size:12px;line-height:1.7;text-align:center;">
              ${msg("ysitEmailFooter")}
            </td>
          </tr>
        </table>
      </td>
    </tr>
  </table>
</body>
</html>
</#macro>
