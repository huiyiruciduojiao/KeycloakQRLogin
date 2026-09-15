const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const resources = path.join(__dirname, '../../main/resources');
const themeRoot = path.join(resources, 'theme/qrlogin');
const read = (...parts) => fs.readFileSync(path.join(themeRoot, ...parts), 'utf8');
const hash = (...parts) => crypto.createHash('sha256')
    .update(fs.readFileSync(path.join(themeRoot, ...parts))).digest('hex');

test('theme provider exposes all four Keycloak theme types', () => {
    const descriptor = JSON.parse(fs.readFileSync(path.join(resources, 'META-INF/keycloak-themes.json'), 'utf8'));
    assert.deepEqual(descriptor.themes, [{ name: 'qrlogin', types: ['login', 'account', 'admin', 'email'] }]);
});

test('26.7.3 React consoles inherit native themes and share the login logo bytes', () => {
    assert.match(read('account', 'theme.properties'), /^parent=keycloak\.v3$/m);
    assert.match(read('admin', 'theme.properties'), /^parent=keycloak\.v2$/m);
    assert.match(read('account', 'theme.properties'), /^styles=css\/ysit-account\.css css\/avatar\.css$/m);
    assert.match(read('admin', 'theme.properties'), /^styles=css\/ysit-admin\.css$/m);
    assert.match(read('account', 'theme.properties'), /^logo=img\/brand-lockup\.svg$/m);
    assert.match(read('admin', 'theme.properties'), /^logo=\/img\/brand-lockup\.svg$/m);
    const loginHash = hash('login', 'resources', 'img', 'brand-logo.png');
    assert.equal(hash('account', 'resources', 'img', 'brand-logo.png'), loginHash);
    assert.equal(hash('admin', 'resources', 'img', 'brand-logo.png'), loginHash);
    const accountBrand = read('account', 'resources', 'img', 'brand-lockup.svg');
    const adminBrand = read('admin', 'resources', 'img', 'brand-lockup.svg');
    assert.match(accountBrand, /易识IT统一身份认证/);
    assert.match(accountBrand, /data:image\/png;base64,/);
    assert.equal(accountBrand, adminBrand);
});

test('console branding does not override native Keycloak colors', () => {
    for (const type of ['account', 'admin']) {
        const css = read(type, 'resources', 'css', `ysit-${type}.css`);
        assert.match(css, /keycloak__pageheader_brand/);
        assert.doesNotMatch(css, /background(?:-color)?\s*:/);
        assert.doesNotMatch(css, /(?:^|\s)color\s*:/m);
        assert.doesNotMatch(css, /pf-v5-c-button|pf-v5-c-nav__link|pf-v5-c-page__sidebar/);
        assert.doesNotMatch(css, /#0f766e|#115e59|linear-gradient/);
    }
});

test('email theme covers every native 26.7.3 HTML and plain-text mail path', () => {
    const expected = [
        'email-test.ftl', 'email-update-confirmation.ftl', 'email-verification-with-code.ftl',
        'email-verification.ftl', 'event-login_error.ftl', 'event-remove_credential.ftl',
        'event-remove_totp.ftl', 'event-update_credential.ftl', 'event-update_password.ftl',
        'event-update_totp.ftl', 'event-user_disabled_by_permanent_lockout.ftl',
        'event-user_disabled_by_temporary_lockout.ftl', 'executeActions.ftl',
        'identity-provider-link.ftl', 'org-invite.ftl', 'password-reset.ftl',
        'verifiable-credential-offer.ftl', 'workflow-notification.ftl'
    ];
    const actual = fs.readdirSync(path.join(themeRoot, 'email/text')).sort();
    assert.deepEqual(actual, expected.sort());
    for (const file of actual) assert.match(read('email', 'text', file), /msg\("ysitEmailFooter"\)/, file);
    const htmlLayout = read('email', 'html', 'template.ftl');
    assert.match(htmlLayout, /<#macro emailLayout(?:\s+fallbackUrl="")?>/);
    assert.match(htmlLayout, /msg\("ysitEmailFooter"\)/);
    assert.match(htmlLayout, /img\/brand-logo\.png/);
    assert.match(htmlLayout, /<title>\$\{subject!realmName\}<\/title>/);
    assert.match(htmlLayout, /ysit-brand-name[^>]*>\$\{realmName\}<\/div>/);
    assert.match(htmlLayout, /alt="\$\{realmName\}"/);
    assert.doesNotMatch(htmlLayout, /ysitMailBrand|#0f766e|linear-gradient/);
    assert.match(htmlLayout, /background:#0d2745/);
    assert.match(htmlLayout, /class="ysit-subject"[^>]*>\$\{subject!realmName\}<\/td>/);
    assert.match(htmlLayout, /user\?\? && user\.username\?has_content/);
    assert.doesNotMatch(htmlLayout, /\.ysit-account\s*\{\s*display:\s*none/);
    assert.match(htmlLayout, /class="ysit-brand-name"[^>]*overflow-wrap:anywhere;word-break:break-word;[^>]*>\$\{realmName\}<\/div>/);
    assert.match(htmlLayout, /class="ysit-account"[^>]*>[\s\S]*?overflow-wrap:anywhere;word-break:break-word;[^>]*>\$\{user\.username\}<\/td>/);
    assert.match(htmlLayout, /class="ysit-subject"[^>]*overflow-wrap:anywhere;word-break:break-word;[^>]*>\$\{subject!realmName\}<\/td>/);
    assert.match(htmlLayout, /class="ysit-header"[\s\S]*?table-layout:fixed/);
    assert.match(htmlLayout, /background: #1677e8/);
    assert.equal(hash('email', 'resources', 'img', 'brand-logo.png'), hash('login', 'resources', 'img', 'brand-logo.png'));
});

test('Chinese account emails use the realm display name as context, not as the password owner', () => {
    const messages = read('email', 'messages', 'messages_zh_CN.properties');
    assert.match(messages, /passwordResetBody=我们收到了重置您在“\{2\}”中的账户密码的请求/);
    assert.match(messages, /emailVerificationBody=请验证您在“\{2\}”中的账户所使用的电子邮箱/);
    assert.match(messages, /emailUpdateConfirmationBody=我们收到了将您在“\{2\}”中的账户邮箱更新为 \{1\} 的请求/);
    assert.match(messages, /executeActionsBody=“\{2\}”的管理员要求您完成以下账户操作/);
    assert.doesNotMatch(messages, /修改账户 \{2\} 的密码|修改\{2\}的密码/);
});

test('every major action email exposes a copyable fallback URL', () => {
    const linkedTemplates = [
        'email-update-confirmation.ftl', 'email-verification.ftl', 'executeActions.ftl',
        'identity-provider-link.ftl', 'org-invite.ftl', 'password-reset.ftl',
        'verifiable-credential-offer.ftl'
    ];
    for (const file of linkedTemplates) {
        const html = read('email', 'html', file);
        assert.match(html, /<@layout\.emailLayout fallbackUrl=link>/, file);
    }
    const layout = read('email', 'html', 'template.ftl');
    assert.match(layout, /<#macro emailLayout fallbackUrl="">/);
    assert.match(layout, /<#if fallbackUrl\?has_content>/);
    assert.match(layout, /class="ysit-fallback-url"/);
    assert.match(layout, />\$\{fallbackUrl\}<\/div>/);
    assert.match(layout, /overflow-wrap:anywhere;word-break:break-all/);
    const zh = read('email', 'messages', 'messages_zh_CN.properties');
    const en = read('email', 'messages', 'messages_en.properties');
    assert.match(zh, /ysitFallbackLinkIntro=如果按钮无法正常打开，请复制以下完整地址到浏览器访问/);
    assert.match(en, /ysitFallbackLinkIntro=If the button does not open correctly/);
});
