"use strict";
document.addEventListener("DOMContentLoaded", () => {
    const entry = document.getElementById("qr-login-entry");
    const dialog = document.getElementById("qr-login-dialog");
    if (!entry || !dialog) return;
    const content = document.getElementById("qr-dialog-content");
    const openButton = document.getElementById("qr-login-open");
    const closeButton = document.getElementById("qr-dialog-close");
    const passwordExecution = new URL(entry.action).searchParams.get("execution");
    if (!passwordExecution) return; // Native full-page fallback if this is not a selectable password execution.
    const passwordForm = document.getElementById("kc-form-login");
    if (!passwordForm) return;
    closeButton.hidden = false;
    entry.classList.add("is-enhanced");
    entry.setAttribute("role", "tablist");
    for (const button of [closeButton, openButton]) {
        button.setAttribute("role", "tab");
        button.removeAttribute("aria-pressed");
    }
    const selectMode = qr => {
        dialog.hidden = !qr;
        passwordForm.hidden = qr;
        document.body.classList.toggle("qr-mode", qr);
        openButton.setAttribute("aria-selected", String(qr));
        closeButton.setAttribute("aria-selected", String(!qr));
        openButton.tabIndex = qr ? 0 : -1;
        closeButton.tabIndex = qr ? -1 : 0;
    };
    selectMode(false);
    let busy = false, currentForm = null, currentAction = entry.action;
    const safeAction = value => {
        const url = new URL(value, location.href);
        if (url.origin !== location.origin || !url.pathname.endsWith("/login-actions/authenticate")) throw new Error("Invalid action");
        return url.href;
    };
    const post = async (action, values, json = false) => {
        const controller = new AbortController();
        const timeout = setTimeout(() => controller.abort(), 10000);
        try {
            const response = await fetch(safeAction(action), {
                method: "POST", credentials: "same-origin", cache: "no-store", redirect: "error",
                headers: { "Content-Type": "application/x-www-form-urlencoded", "Accept": json ? "application/json" : "text/html" },
                body: new URLSearchParams(values), signal: controller.signal
            });
            if (!response.ok) throw new Error("Action failed");
            return json ? response.json() : new DOMParser().parseFromString(await response.text(), "text/html");
        } finally { clearTimeout(timeout); }
    };
    const pause = async () => {
        if (!currentForm) return;
        currentForm.dispatchEvent(new Event("qr:stop"));
        if (currentForm.dataset.qrBusy === "true") await new Promise(resolve => currentForm.addEventListener("qr:idle", resolve, { once: true }));
        currentAction = safeAction(currentForm.action);
    };
    const showError = () => {
        content.setAttribute("aria-busy", "false");
        content.querySelector(".qr-loading-placeholder")?.remove();
        let message = dialog.querySelector(".qr-modal-error");
        if (!message) { message = document.createElement("p"); message.className = "qr-modal-error"; message.setAttribute("role", "alert"); content.append(message); }
        message.textContent = dialog.dataset.error;
    };
    const mount = page => {
        const form = page.getElementById("qr-login-form");
        if (!form || form.querySelector("script,iframe,object")) throw new Error("QR form missing");
        form.action = safeAction(form.action);
        currentAction = form.action;
        currentForm = document.importNode(form, true);
        content.replaceChildren(currentForm);
        content.setAttribute("aria-busy", "false");
        // Only automatic completion navigates. QR refresh stays inside this panel.
        currentForm.addEventListener("submit", async event => {
            const operation = event.submitter?.value;
            if (operation !== "restart" && operation !== "cancel") return;
            event.preventDefault();
            if (busy) return;
            if (operation === "cancel") { await close(); return; }
            busy = true;
            try { await pause(); mount(await post(currentAction, { operation: "restart" })); }
            catch (_) { showError(); }
            finally { busy = false; }
        });
        document.dispatchEvent(new Event("qr:mounted"));
    };
    const close = async () => {
        if (busy) return;
        busy = true; closeButton.disabled = true;
        try {
            await pause();
            if (currentForm) {
                const dismissed = await post(currentAction, { operation: "dismiss" }, true);
                currentAction = safeAction(dismissed.action);
            }
            // Restore Keycloak's selected password execution before re-enabling the original page.
            const page = await post(currentAction, { authenticationExecution: passwordExecution });
            if (!page.getElementById("kc-form-login")) throw new Error("Password flow not restored");
            for (const oldForm of document.querySelectorAll("form[id]")) {
                if (dialog.contains(oldForm)) continue;
                const fresh = page.getElementById(oldForm.id);
                if (fresh?.tagName === "FORM") oldForm.action = safeAction(fresh.action);
            }
            for (const anchor of document.querySelectorAll("a[id]")) {
                const fresh = page.getElementById(anchor.id);
                if (fresh?.tagName === "A" && new URL(fresh.href, location.href).origin === location.origin) anchor.href = fresh.href;
            }
            currentForm = null; currentAction = entry.action;
            content.replaceChildren(); selectMode(false); closeButton.focus();
        } catch (_) { showError(); }
        finally { busy = false; closeButton.disabled = false; }
    };
    entry.addEventListener("submit", async event => {
        event.preventDefault();
        if (busy || !dialog.hidden) return;
        busy = true;
        const placeholder = document.createElement("div");
        placeholder.className = "qr-loading-placeholder";
        const square = document.createElement("div");
        square.className = "qr-loading-square";
        square.setAttribute("aria-hidden", "true");
        const label = document.createElement("p");
        label.textContent = dialog.dataset.loading;
        placeholder.append(square, label);
        content.replaceChildren(placeholder);
        content.setAttribute("aria-busy", "true");
        selectMode(true);
        try { mount(await post(entry.action, new FormData(entry))); }
        catch (_) { showError(); }
        finally { busy = false; }
    });
    closeButton.addEventListener("click", () => { if (!dialog.hidden) return close(); });
    entry.addEventListener("keydown", event => {
        if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
        event.preventDefault();
        if (busy) return;
        const qr = event.key === "End" || (event.key !== "Home" && dialog.hidden);
        if (qr) { openButton.focus(); entry.requestSubmit(openButton); }
        else { closeButton.focus(); if (!dialog.hidden) close(); }
    });
    dialog.addEventListener("keydown", event => {
        if (event.key === "Escape") { event.preventDefault(); close(); }
    });
});
