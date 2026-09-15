import { chromium, request, expect } from '@playwright/test';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const base = 'https://localhost:8547';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const password = 'Avatar-Acceptance-Only-2026!';
const api = await request.newContext({ ignoreHTTPSErrors: true });
let browser;
try {
  const auth = await api.post(`${base}/realms/master/protocol/openid-connect/token`, { form: {
    client_id: 'admin-cli', grant_type: 'password', username: 'avatar-fixture-admin', password
  } });
  expect(auth.status()).toBe(200);
  const headers = { Authorization: `Bearer ${(await auth.json()).access_token}` };
  // The administrator belongs to master even while managing avatar-acceptance.
  const master = await (await api.get(`${base}/admin/realms/master`, { headers })).json();
  const masterComponents = `${base}/admin/realms/master/components`;
  const previous = (await (await api.get(masterComponents, { headers })).json()).find(c => c.providerId === 'avatarSettings');
  const masterSettings = { providerId: 'avatarSettings', providerType: 'org.keycloak.services.ui.extend.UiTabProvider', parentId: master.id,
    config: { enabled: ['true'], 'public-base-url': [base], clients: ['admin-cli'], 'url-ttl-seconds': ['900'], 'max-storage-bytes': ['1073741824'] } };
  expect((previous ? await api.put(`${masterComponents}/${previous.id}`, { headers, data: masterSettings }) :
    await api.post(masterComponents, { headers, data: masterSettings })).ok()).toBe(true);
  const logo = await fs.readFile(path.join(root, 'src/main/resources/theme/qrlogin/login/resources/img/brand-logo.png'));
  expect((await api.put(`${base}/realms/master/avatars/me`, { headers, multipart: { file: { name: 'avatar.png', mimeType: 'image/png', buffer: logo } } })).status()).toBe(200);
  // Theme selection changes only this project's isolated fixture.
  expect((await api.put(`${base}/admin/realms/master`, { headers, data: {
    adminTheme: 'qrlogin', internationalizationEnabled: true, supportedLocales: ['en', 'zh-CN'], defaultLocale: 'zh-CN'
  } })).status()).toBe(204);
  expect((await api.put(`${base}/admin/realms/avatar-acceptance`, { headers, data: { adminTheme: 'qrlogin' } })).status()).toBe(204);
  const userToken = await api.post(`${base}/realms/avatar-acceptance/protocol/openid-connect/token`, { form: {
    client_id: 'avatar-web', grant_type: 'password', username: 'alice', password
  } });
  const userHeaders = { Authorization: `Bearer ${(await userToken.json()).access_token}` };
  const componentUrl = `${base}/admin/realms/avatar-acceptance/components`;
  expect((await api.get(componentUrl, { headers: userHeaders })).status()).toBe(403);
  expect((await api.post(componentUrl, { headers: userHeaders, data: {} })).status()).toBe(403);
  browser = await chromium.launch({ headless: true, channel: 'chrome' });
  const context = await browser.newContext({ ignoreHTTPSErrors: true, viewport: { width: 1440, height: 1050 }, locale: 'zh-CN' });
  const page = await context.newPage();
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.goto(`${base}/admin/master/console/#/avatar-acceptance/realm-settings/avatars`);
  await page.locator('#username').fill('avatar-fixture-admin');
  await page.locator('#password').fill(password); await page.locator('#kc-login').click();
  await expect(page.getByTestId('public-base-url')).toBeVisible({ timeout: 30000 });
  await expect(page.locator('header img.pf-v5-c-avatar')).toHaveAttribute('src', /\/realms\/master\/avatars\/content\//);
  await expect(page.locator('header img.pf-v5-c-avatar')).toHaveJSProperty('naturalWidth', 64);
  const unlistedAuth = await api.post(`${base}/realms/avatar-acceptance/protocol/openid-connect/token`, { form: {
    client_id: 'avatar-wrong-client', grant_type: 'password', username: 'alice', password
  } });
  expect(unlistedAuth.status()).toBe(200);
  const unlistedHeaders = { Authorization: `Bearer ${(await unlistedAuth.json()).access_token}` };
  const meUrl = `${base}/realms/avatar-acceptance/avatars/batch`;
  await expect(page.getByTestId('allow-realm-clients')).not.toBeChecked();
  await page.locator('label.pf-v5-c-switch[for="allow-realm-clients"]').click();
  await page.getByTestId('save').click();
  await expect.poll(async () => (await api.post(meUrl, { headers: unlistedHeaders, data: { userIds: ['avatar-alice'] } })).status()).toBe(200);
  await page.reload();
  await expect(page.getByTestId('allow-realm-clients')).toBeChecked();
  await page.locator('label.pf-v5-c-switch[for="allow-realm-clients"]').click();
  await page.getByTestId('save').click();
  await expect.poll(async () => (await api.post(meUrl, { headers: unlistedHeaders, data: { userIds: ['avatar-alice'] } })).status()).toBe(403);
  await expect(page.getByTestId('audience')).toHaveCount(0);
  await expect(page.getByText('公开访问地址（警告：HTTP 明文传输图片）', { exact: true })).toBeVisible();
  await expect(page.getByTestId('public-base-url')).toBeEditable();
  await page.getByTestId('public-base-url').fill('http://localhost:8546');
  await page.getByTestId('max-storage-bytes').fill('2147483648');
  await page.getByTestId('save').click();
  await expect.poll(async () => {
    const components = await (await api.get(componentUrl, { headers })).json();
    return components.find(c => c.providerId === 'avatarSettings')?.config['max-storage-bytes']?.[0];
  }).toBe('2147483648');
  await page.reload();
  await expect(page.getByTestId('max-storage-bytes')).toHaveValue('2147483648');
  await expect(page.getByTestId('public-base-url')).toHaveValue('http://localhost:8546');
  await fs.mkdir(path.join(root, '.local/avatar-live/screenshots'), { recursive: true });
  await page.screenshot({ path: path.join(root, '.local/avatar-live/screenshots/avatar-admin.png'), fullPage: true });
  const settings = (await (await api.get(componentUrl, { headers })).json()).find(c => c.providerId === 'avatarSettings');
  expect(Object.keys(settings.config).some(key => /secret|signing|storage-path/.test(key))).toBe(false);
  expect(settings.config.audience).toBeUndefined();
  settings.config.enabled = ['false'];
  expect((await api.put(`${componentUrl}/${settings.id}`, { headers, data: settings })).status()).toBe(204);
  expect((await api.get(`${base}/realms/avatar-acceptance/avatars/me`, { headers: userHeaders })).status()).toBe(404);
  settings.config.enabled = ['true'];
  expect((await api.put(`${componentUrl}/${settings.id}`, { headers, data: settings })).status()).toBe(204);
  expect((await api.get(`${base}/realms/avatar-acceptance/avatars/me`, { headers: userHeaders })).status()).toBe(200);
  const invalid = structuredClone(settings); invalid.config['url-ttl-seconds'] = ['1'];
  expect((await api.put(`${componentUrl}/${settings.id}`, { headers, data: invalid })).status()).toBe(400);
  expect(errors).toEqual([]);
  expect((await api.delete(`${base}/realms/master/avatars/me`, { headers })).status()).toBe(200);
  await page.evaluate(() => window.dispatchEvent(new Event('qrlogin-avatar-changed')));
  await expect(page.locator('header img.pf-v5-c-avatar')).toHaveCount(0);
  console.log('PASS: native admin tab renders, saves, reloads; user access denied; invalid settings rejected; disable/enable applies without restart; no secrets returned');
} finally { await browser?.close(); await api.dispose(); }

