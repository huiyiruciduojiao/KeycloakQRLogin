import { chromium, request, expect } from '@playwright/test';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// Hardcoded loopback-only fixture; never accepts a production base URL or real user credentials.
const base = 'https://localhost:8547';
const realm = `${base}/realms/avatar-acceptance`;
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const screenshots = path.join(root, '.local/avatar-live/screenshots');
await fs.mkdir(screenshots, { recursive: true });
const api = await request.newContext({ ignoreHTTPSErrors: true });
const errors = [];
const password = 'Avatar-Acceptance-Only-2026!';
async function token(client, user = 'alice') {
  const response = await api.post(`${realm}/protocol/openid-connect/token`, { form: {
    client_id: client, grant_type: 'password', username: user, password
  } });
  expect(response.status(), `Token issuance for test client ${client}`).toBe(200);
  return (await response.json()).access_token;
}
const image = await fs.readFile(path.join(root, 'src/main/resources/theme/qrlogin/login/resources/img/brand-logo.png'));
let browser;
try {
  // Configure only the generated fixture's native account client; never changes any existing realm.
  const bootstrap = await api.post(`${base}/realms/master/protocol/openid-connect/token`, { form: {
    client_id: 'admin-cli', grant_type: 'password', username: 'avatar-fixture-admin', password
  } });
  expect(bootstrap.status(), 'Isolated fixture administrator').toBe(200);
  const adminHeaders = { Authorization: `Bearer ${(await bootstrap.json()).access_token}` };
  // Native component storage is the same persistence API used by the admin settings form.
  const adminRealm = `${base}/admin/realms/avatar-acceptance`;
  const realmInfo = await (await api.get(adminRealm, { headers: adminHeaders })).json();
  const componentsUrl = `${adminRealm}/components`;
  const components = await (await api.get(componentsUrl, { headers: adminHeaders })).json();
  const settings = { providerId: 'avatarSettings', providerType: 'org.keycloak.services.ui.extend.UiTabProvider', parentId: realmInfo.id,
    config: { enabled: ['true'], 'public-base-url': [base], clients: ['avatar-web', 'account-console', 'avatar-wrong-audience'], 'url-ttl-seconds': ['900'], 'max-storage-bytes': ['1073741824'] } };
  const existingSettings = components.find(component => component.providerId === settings.providerId);
  const saved = existingSettings ? await api.put(`${componentsUrl}/${existingSettings.id}`, { headers: adminHeaders, data: settings }) :
    await api.post(componentsUrl, { headers: adminHeaders, data: settings });
  expect([201, 204]).toContain(saved.status());
  const access = await token('avatar-web');
  const headers = { Authorization: `Bearer ${access}` };
  expect((await api.get(`${realm}/avatars/me`)).status()).toBe(401);
  expect((await api.get(`${realm}/avatars/me`, { headers: { Authorization: 'Bearer invalid' } })).status()).toBe(401);
  const unlistedHeaders = { Authorization: `Bearer ${await token('avatar-wrong-client')}` };
  expect((await api.get(`${realm}/avatars/me`, { headers: unlistedHeaders })).status()).toBe(200);
  expect((await api.delete(`${realm}/avatars/me`, { headers: unlistedHeaders })).status()).toBe(403);
  expect((await api.get(`${realm}/avatars/me`, { headers: { Authorization: `Bearer ${await token('avatar-wrong-audience')}` } })).status()).toBe(200);
  expect((await api.delete(`${realm}/avatars/me`, { headers })).status()).toBe(200);
  const upload = await api.put(`${realm}/avatars/me`, { headers, multipart: { file: { name: 'logo.png', mimeType: 'image/png', buffer: image } } });
  expect(upload.status(), 'Multipart image upload').toBe(200);
  const avatar = await upload.json();
  // A separate page origin exercises browser CORS enforcement, not just API headers.
  const corsBrowser = await chromium.launch({ headless: true, channel: 'chrome' });
  try {
    const corsContext = await corsBrowser.newContext({ ignoreHTTPSErrors: true });
    const corsPage = await corsContext.newPage();
    corsPage.on('console', message => { if (message.type() === 'error') console.log(message.text().replace(/\?exp=[^\s'"]+/g, '?[redacted]')); });
    const corsResponse = await api.get(avatar.url, { headers: { Origin: 'https://app.example.test' } });
    expect(corsResponse.status()).toBe(200);
    expect(corsResponse.headers()['access-control-allow-origin']).toBe('https://app.example.test');
    await corsPage.route('https://app.example.test/**', route => route.fulfill({ contentType: 'text/html', body: '<!doctype html><title>Avatar CORS fixture</title>' }));
    await corsPage.goto('https://app.example.test/');
    const crossOrigin = await corsPage.evaluate(async url => {
      const image = await fetch(url, { credentials: 'omit' });
      const size = (await image.blob()).size;
      const etag = image.headers.get('ETag');
      const revalidated = await fetch(url, { credentials: 'omit', headers: { 'If-None-Match': etag } });
      return { status: image.status, size, etag, revalidated: revalidated.status };
    }, avatar.url);
    expect(crossOrigin.status).toBe(200); expect(crossOrigin.size).toBeGreaterThan(0);
    expect(crossOrigin.etag).toBeTruthy(); expect([200, 304]).toContain(crossOrigin.revalidated);
  } finally { await corsBrowser.close(); }
  expect(avatar.status).toBe('CUSTOM');
  expect(avatar.url.startsWith(`${realm}/avatars/content/`)).toBe(true);
  const loaded = await api.get(avatar.url);
  expect(loaded.status()).toBe(200); expect(loaded.headers()['content-type']).toContain('image/png');
  expect((await api.get(avatar.url, { headers: { 'If-None-Match': loaded.headers().etag } })).status()).toBe(304);
  const bad = new URL(avatar.url); bad.searchParams.set('sig', 'a'.repeat(43));
  expect((await api.get(bad.href)).status()).toBe(404);
  expect((await api.get(avatar.url.replace('/128?', '/256?'))).status()).toBe(404);
  expect((await api.get(avatar.url.replace('/avatar-acceptance/', '/master/'))).status()).toBe(404);
  const batch = await api.post(`${realm}/avatars/batch`, { headers, data: { userIds: ['avatar-alice', 'avatar-bob', 'missing', 'avatar-alice'], size: 128 } });
  expect(batch.status()).toBe(200);
  expect((await batch.json()).items.map(item => item.status)).toEqual(['CUSTOM', 'DEFAULT', 'UNAVAILABLE']);
  expect((await api.post(`${realm}/avatars/batch`, { headers, data: { userIds: Array(101).fill('avatar-alice') } })).status()).toBe(400);
  expect((await api.put(`${realm}/avatars/me`, { headers, multipart: { file: { name: 'bad.svg', mimeType: 'image/svg+xml', buffer: Buffer.from('<svg/>') } } })).status()).toBe(415);
  expect((await api.put(`${realm}/avatars/me`, { headers, multipart: { file: { name: 'logo.png', mimeType: 'image/png', buffer: image }, userId: 'avatar-bob' } })).status()).toBe(400);
  const preflight = await api.fetch(`${realm}/avatars/batch`, { method: 'OPTIONS', headers: { Origin: 'https://app.example.test', 'Access-Control-Request-Method': 'POST', 'Access-Control-Request-Headers': 'authorization,content-type' } });
  expect(preflight.status()).toBe(204); expect(preflight.headers()['access-control-allow-origin']).toBe('https://app.example.test');
  expect((await api.get(`${realm}/avatars/me`, { headers: { ...headers, Origin: 'https://evil.test' } })).status()).toBe(403);
  expect((await api.delete(`${realm}/avatars/me`, { headers })).status()).toBe(200);
  expect((await api.get(avatar.url, { headers: { 'If-None-Match': loaded.headers().etag } })).status()).toBe(404);
  console.log('PASS: live bearer, multipart, batch, signatures, cache, CORS, and deletion checks');

  browser = await chromium.launch({ headless: true, channel: 'chrome' });
  const context = await browser.newContext({ ignoreHTTPSErrors: true, viewport: { width: 1440, height: 1000 }, locale: 'zh-CN' });
  const page = await context.newPage();
  page.on('pageerror', error => errors.push(error.message));
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text().replace(/Bearer\s+\S+/g, 'Bearer [redacted]')); });
  await page.goto(`${realm}/account/content/avatar`);
  await page.locator('#username').fill('alice');
  await page.locator('#password').fill(password);
  await page.locator('#kc-login').click();
  await expect(page.getByRole('heading', { name: '用户头像', exact: true })).toBeVisible({ timeout: 30000 });
  await expect(page.getByRole('button', { name: '选择图片', exact: true }).last()).toBeEnabled({ timeout: 20000 });
  await page.locator('input[type=file]').setInputFiles({ name: 'avatar.png', mimeType: 'image/png', buffer: image });
  await expect(page.getByRole('img', { name: '头像预览' })).toBeVisible();
  await page.getByRole('slider', { name: '缩放' }).fill('1.5');
  await page.getByRole('slider', { name: '水平位置' }).fill('70');
  await page.screenshot({ path: path.join(screenshots, 'avatar-desktop.png'), fullPage: true });
  await page.getByRole('button', { name: '保存头像' }).click();
  await expect(page.getByText('头像已保存。', { exact: true })).toBeVisible({ timeout: 15000 });
  await expect(page.locator('header img.pf-v5-c-avatar')).toHaveAttribute('src', /\/avatars\/content\//);
  await expect(page.locator('header img.pf-v5-c-avatar')).toHaveJSProperty('naturalWidth', 64);
  await page.reload();
  await expect(page.getByRole('button', { name: '删除头像', exact: true })).toBeVisible();
  await expect(page.getByAltText('当前头像')).toHaveJSProperty('naturalWidth', 128);
  await page.setViewportSize({ width: 390, height: 844 });
  // PatternFly retains the expanded desktop sidebar when the viewport changes.
  if (await page.locator('#page-sidebar').getAttribute('aria-hidden') === 'false') {
    await page.locator('#nav-toggle').click();
  }
  await expect(page.locator('#page-sidebar')).toHaveAttribute('aria-hidden', 'true');
  await page.locator('input[type=file]').setInputFiles({ name: 'avatar.png', mimeType: 'image/png', buffer: image });
  await expect(page.getByRole('img', { name: '头像预览' })).toBeVisible();
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: path.join(screenshots, 'avatar-mobile.png'), fullPage: true });
  await page.getByRole('button', { name: '取消', exact: true }).click();
  await page.getByRole('button', { name: '删除头像', exact: true }).click();
  await expect(page.getByRole('button', { name: '删除', exact: true })).toBeFocused();
  await page.getByRole('button', { name: '删除', exact: true }).click();
  await expect(page.getByText('头像已删除。', { exact: true })).toBeVisible();
  await expect(page.locator('header img.pf-v5-c-avatar')).toHaveCount(0);
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto(`${realm}/account/`);
  await expect(page.locator('#firstName')).toBeVisible({ timeout: 15000 });
  await page.goto(`${realm}/account/account-security/device-activity`);
  await expect(page.locator('#app')).toContainText('Chrome', { timeout: 15000 });
  expect(errors, 'Browser errors').toEqual([]);
  console.log('PASS: account login, native navigation, crop, upload, reload, mobile layout, and delete');
} finally {
  await browser?.close(); await api.dispose();
}
