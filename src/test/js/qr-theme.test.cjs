const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = path.join(__dirname, '../../main/resources/theme/qrlogin/login');
test('glass and password styling keep visibility hooks and readable fallback', () => {
    const css = fs.readFileSync(path.join(root, 'resources/css/ecosystem.css'), 'utf8');
    assert.match(css, /backdrop-filter: blur\(28px\) saturate\(135%\)/);
    assert.match(css, /@supports not/);
    assert.match(css, /var\(--auth-card-fallback\)/);
    assert.match(css, /input-group:has\(\[data-password-toggle\]\)/);
    assert.match(css, /i\.fa-eye-slash::before/);
    assert.match(css, /\.pf-v5-c-button\[data-password-toggle\]:focus-visible/);
});
test('ecosystem shell preserves native security and registration layout hooks', () => {
    const layout = fs.readFileSync(path.join(root, 'template.ftl'), 'utf8');
    assert.match(layout, /class="auth-visual"/);
    assert.match(layout, /auth-services/);
    const css = fs.readFileSync(path.join(root, 'resources/css/ecosystem.css'), 'utf8');
    assert.match(css, /ecosystem-scene\.png/);
    assert.match(css, /prefers-reduced-motion/);
    assert.match(layout, /startSessionPolling/);
    assert.match(layout, /checkAuthSession/);
    assert.match(layout, /passwordVisibility\.js/);
    assert.match(layout, /<#nested "form">/);
    assert.match(layout, /displayRequiredFields/);
    assert.match(layout, /kcSanitize\(message.summary\)/);
});
test('automatic completion keeps a hidden native submitter', () => {
    const form = fs.readFileSync(path.join(root, 'qr-login.ftl'), 'utf8');
    assert.match(form, /<button hidden[^>]*value="poll"[^>]*id="qr-check"/);
    assert.match(form, /value="restart"/);
    assert.match(form, /qrJavascriptRequired/);
    assert.match(form, /id="qr-state-overlay" role="status" aria-live="polite"/);
    assert.match(form, /id="qr-overlay-status"/);
    assert.doesNotMatch(form, /qr-expiry-overlay|qr-denied-overlay/);
});
test('inline QR mode preserves native fallback and execution restoration', () => {
    const login = fs.readFileSync(path.join(root, 'login.ftl'), 'utf8');
    const script = fs.readFileSync(path.join(root, 'resources/js/modal.js'), 'utf8');
    assert.match(login, /id="qr-dialog-close"/);
    assert.doesNotMatch(login + script, /qr-dialog-back|backButton/);
    assert.match(login, /<section hidden id="qr-login-dialog"/);
    assert.match(login, /aria-labelledby="qr-login-open"/);
    assert.match(login, /aria-controls="kc-form-login"/);
    assert.doesNotMatch(script, /showModal/);
    assert.match(script, /authenticationExecution: passwordExecution/);
    assert.match(script, /passwordForm.hidden = qr/);
    assert.match(script, /redirect: "error"/);
});

test('login layout does not expose the generic try-another-way entry', () => {
    const layout = fs.readFileSync(path.join(root, 'template.ftl'), 'utf8');
    assert.doesNotMatch(layout, /showTryAnotherWayLink/);
    assert.doesNotMatch(layout, /name="tryAnotherWay"/);
    assert.doesNotMatch(layout, /id="try-another-way"/);
});

test('native language options remain readable in the dark login theme', () => {
    const layout = fs.readFileSync(path.join(root, 'template.ftl'), 'utf8');
    const css = fs.readFileSync(path.join(root, 'resources/css/ecosystem.css'), 'utf8');
    assert.match(layout, /class="auth-title-row">\s*<h1[\s\S]*?class="auth-language-control"/);
    assert.match(layout, /class="auth-language-chevron"[\s\S]*?<path d="M1 1\.25 6 6\.25l5-5"/);
    assert.doesNotMatch(layout, /kcFormControlToggleIcon|M31\.3 192h257\.3/);
    assert.match(css, /#login-select-toggle\s*\{[^}]*color-scheme:\s*dark/s);
    assert.match(css, /#login-select-toggle\s*\{[^}]*appearance:\s*none/s);
    assert.match(css, /#login-select-toggle:focus-visible\s*\{[^}]*outline:\s*none/s);
    assert.match(css, /\.auth-language-chevron svg\s*\{[^}]*stroke-width:\s*1\.5/s);
    assert.match(css, /#login-select-toggle option\s*\{[^}]*color:\s*#eaf2fb;[^}]*background-color:\s*#16263b/s);
    assert.match(css, /#login-select-toggle option:checked\s*\{[^}]*color:\s*#fff;[^}]*background-color:\s*#245b8f/s);
});
