"use strict";
const startQrPolling = () => {
    const form = document.getElementById("qr-login-form");
    if (!form || !["PENDING", "SCANNED", "CONFIRMED", "DENIED"].includes(form.dataset.qrState)) return;
    const check = document.getElementById("qr-check");
    const error = document.getElementById("qr-network-error");
    const status = document.getElementById("qr-status");
    const actionPath = new URL(form.action).pathname;
    let timer, clockTimer, controller, inFlight = false, submitted = false, queuedButton = null, failures = 0, isExpired = false;
    const countdown = document.getElementById("qr-countdown");
    const timeLeft = document.getElementById("qr-time-left");
    const overlay = document.getElementById("qr-state-overlay");
    const overlayStatus = document.getElementById("qr-overlay-status");
    const overlayRestart = document.getElementById("qr-overlay-restart");
    const labels = { PENDING: form.dataset.qrPending, SCANNED: form.dataset.qrScanned,
        CONFIRMED: form.dataset.qrConfirmed, DENIED: form.dataset.qrDenied,
        EXPIRED: form.dataset.qrExpired, CANCELLED: form.dataset.qrCancelled, CONSUMED: form.dataset.qrConsumed };
    const mask = state => {
        form.classList.add("qr-is-masked");
        overlayStatus.textContent = labels[state];
        overlay.hidden = false;
        overlayRestart.hidden = true;
        status.hidden = true;
    };
    const expired = (force = false) => {
        if (!force && !isExpired && Date.now() < Number(form.dataset.qrExpiry)) return false;
        isExpired = true;
        form.dataset.qrState = "EXPIRED";
        mask("EXPIRED");
        form.classList.add("qr-is-expired");
        overlayRestart.hidden = false;
        countdown.hidden = true;
        status.hidden = true;
        error.hidden = true;
        check.disabled = true;
        window.clearTimeout(timer);
        window.clearInterval(clockTimer);
        return true;
    };
    const updateCountdown = () => {
        if (submitted || expired()) return;
        const seconds = Math.max(0, Math.ceil((Number(form.dataset.qrExpiry) - Date.now()) / 1000));
        timeLeft.textContent = String(Math.floor(seconds / 60)).padStart(2, "0") + ":" + String(seconds % 60).padStart(2, "0");
        countdown.hidden = false;
    };
    const denied = () => {
        mask("DENIED");
        form.classList.add("qr-is-denied");
        overlayRestart.hidden = false;
        error.hidden = true;
        countdown.hidden = true;
        check.disabled = true;
        window.clearTimeout(timer);
        window.clearInterval(clockTimer);
    };
    const poll = async () => {
        if (submitted || inFlight || expired()) return;
        if (document.visibilityState === "hidden") {
            timer = window.setTimeout(poll, 3000);
            return;
        }
        inFlight = true;
        form.dataset.qrBusy = "true";
        controller = new AbortController();
        const timeout = window.setTimeout(() => controller.abort(), 10000);
        let finish = false;
        try {
            const response = await fetch(form.action, {
                method: "POST", credentials: "same-origin", cache: "no-store", redirect: "error",
                headers: { "Accept": "application/json", "Content-Type": "application/x-www-form-urlencoded" },
                body: "operation=status", signal: controller.signal
            });
            if (!response.ok || !response.headers.get("content-type")?.includes("application/json")) throw new Error("status unavailable");
            const data = await response.json();
            const next = new URL(data.action, window.location.href);
            if (next.origin !== window.location.origin || next.pathname !== actionPath) throw new Error("invalid action");
            if (!["PENDING", "SCANNED", "CONFIRMED", "DENIED", "CANCELLED", "CONSUMED", "EXPIRED"].includes(data.state)) throw new Error("invalid state");
            if (!Number.isFinite(data.expiresAt)) throw new Error("invalid expiry");
            form.action = next.href;
            form.dataset.qrExpiry = String(data.expiresAt);
            form.dataset.qrState = data.state;
            failures = 0;
            error.hidden = true;
            if (labels[data.state]) status.textContent = labels[data.state];
            if (data.state === "EXPIRED") expired(true);
            else if (data.state === "DENIED") denied();
            else {
                if (data.state !== "PENDING") mask(data.state);
                updateCountdown();
                finish = !isExpired && !["PENDING", "SCANNED"].includes(data.state);
            }
        } catch (_) {
            failures += 1;
            if (!submitted && !isExpired) error.hidden = false;
        } finally {
            window.clearTimeout(timeout);
            inFlight = false;
            form.dataset.qrBusy = "false";
            form.dispatchEvent(new Event("qr:idle"));
        }
        if (submitted) return;
        // Complete via real navigation so OAuth callbacks and MFA are never swallowed by fetch.
        if (queuedButton || finish) {
            const button = queuedButton || check;
            queuedButton = null;
            form.requestSubmit(button);
        } else if (failures < 3 && ["PENDING", "SCANNED"].includes(form.dataset.qrState) && !expired()) {
            timer = window.setTimeout(poll, 3000 * Math.max(1, failures));
        }
    };
    form.addEventListener("submit", event => {
        window.clearTimeout(timer);
        if (inFlight) { event.preventDefault(); queuedButton = event.submitter || check; return; }
        submitted = true;
        window.clearInterval(clockTimer);
    });
    form.addEventListener("qr:stop", () => { submitted = true; window.clearTimeout(timer); window.clearInterval(clockTimer); });
    window.addEventListener("pagehide", () => {
        submitted = true;
        window.clearTimeout(timer);
        window.clearInterval(clockTimer);
        controller?.abort();
    }, { once: true });
    if (form.dataset.qrState === "DENIED") { denied(); return; }
    if (form.dataset.qrState !== "PENDING") mask(form.dataset.qrState);
    timer = window.setTimeout(poll, form.dataset.qrState === "CONFIRMED" ? 100 : 1000);
    clockTimer = window.setInterval(updateCountdown, 1000);
    updateCountdown();
};
document.addEventListener("DOMContentLoaded", startQrPolling);
document.addEventListener("qr:mounted", startQrPolling);
