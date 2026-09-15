<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=true; section>
  <#if section == "header">
    ${msg("qrTitle")}
  <#elseif section == "form">
    <form id="qr-login-form" class="${properties.kcFormClass!}<#if qrState != "PENDING"> qr-is-masked</#if><#if qrState == "DENIED"> qr-is-denied</#if>" action="${url.loginAction}" method="post" data-qr-state="${qrState}" data-qr-expiry="${qrExpiry}"
          data-qr-pending="${msg('qrStatePENDING')}" data-qr-scanned="${msg('qrStateSCANNED')}" data-qr-confirmed="${msg('qrStateCONFIRMED')}"
          data-qr-denied="${msg('qrStateDENIED')}" data-qr-expired="${msg('qrExpired')}"
          data-qr-cancelled="${msg('qrStateCANCELLED')}" data-qr-consumed="${msg('qrStateCONSUMED')}">
      <p class="qr-instructions">${msg("qrInstructions")}</p>
      <div class="qr-image-frame">
        <img src="${qrImage}" width="240" height="240" alt="${msg('qrImageAlt')}" />
        <div id="qr-state-overlay" role="status" aria-live="polite" <#if qrState == "PENDING">hidden</#if>>
          <strong id="qr-overlay-status">${msg("qrState" + qrState)}</strong>
          <button id="qr-overlay-restart" class="qr-refresh" type="submit" name="operation" value="restart" <#if qrState != "DENIED">hidden</#if>>${msg("qrRestart")}</button>
        </div>
      </div>
      <p id="qr-countdown" role="timer" aria-live="off" hidden>${msg("qrTimeRemaining")} <strong id="qr-time-left"></strong></p>
      <p class="qr-match-code"><span>${msg("qrMatchCode")}</span><strong>${qrCode}</strong></p>
      <p id="qr-status" role="status" aria-live="polite" <#if qrState != "PENDING">hidden</#if>>${msg("qrState" + qrState)}</p>
      <p id="qr-network-error" role="status" hidden>${msg("qrNetworkError")}</p>
      <#-- Hidden native submitter used only by automatic completion. -->
      <button hidden type="submit" name="operation" value="poll" id="qr-check" tabindex="-1" aria-hidden="true">${msg("qrCheck")}</button>
      <noscript><p>${msg("qrJavascriptRequired")}</p></noscript>
      <div class="qr-actions">
        <button class="qr-refresh" type="submit" name="operation" value="restart"><span aria-hidden="true">↻</span> ${msg("qrRestart")}</button>
        <button class="qr-cancel" type="submit" name="operation" value="cancel">${msg("doCancel")}</button>
      </div>
    </form>
  </#if>
</@layout.registrationLayout>
