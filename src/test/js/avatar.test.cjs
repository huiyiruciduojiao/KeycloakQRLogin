const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const account = path.resolve(__dirname, '../../main/resources/theme/qrlogin/account');
const helpers = import(pathToFileURL(path.join(account, 'resources/js/avatar-utils.mjs')));

test('crop remains square and inside image at every slider boundary', async () => {
  const { cropRect } = await helpers;
  for (const [width, height] of [[100, 200], [200, 100], [100, 100]]) {
    for (const zoom of [1, 2, 3]) for (const x of [0, 50, 100]) for (const y of [0, 50, 100]) {
      const rect = cropRect(width, height, zoom, x, y);
      assert.ok(rect.x >= 0 && rect.y >= 0);
      assert.ok(rect.x + rect.edge <= width + 0.0001);
      assert.ok(rect.y + rect.edge <= height + 0.0001);
      assert.equal(rect.edge, Math.min(width, height) / zoom);
    }
  }
});
test('account endpoint preserves context path and rejects cross-origin configuration', async () => {
  const { accountAvatarUrl } = await helpers;
  assert.equal(accountAvatarUrl({ serverBaseUrl: 'https://sso.test/auth', realm: 'team one' }, 'https://sso.test'),
    'https://sso.test/auth/realms/team%20one/avatars/me');
  assert.throws(() => accountAvatarUrl({ serverBaseUrl: 'https://evil.test', realm: 'one' }, 'https://sso.test'), /invalid_origin/);
});
test('refresh runs before expiry and translated errors cover both locales', async () => {
  const { refreshDelay, messages } = await helpers;
  assert.equal(refreshDelay(new Date(900000).toISOString(), 0), 870000);
  assert.equal(refreshDelay(new Date(1).toISOString(), 2), 1000);
  assert.deepEqual(Object.keys(messages.zh).sort(), Object.keys(messages.en).sort());
});
test('account navigation retains native pages and the explicit avatar route', () => {
  const content = JSON.parse(fs.readFileSync(path.join(account, 'resources/content.json')));
  assert.deepEqual(content.map(entry => entry.label), ['personalInfo', 'avatarTitle', 'accountSecurity', 'applications', 'verifiableCredentials', 'groups', 'organizations', 'resources']);
  assert.equal(content[1].path, 'content/avatar');
  assert.ok(fs.existsSync(path.join(account, 'resources/js/avatar-page.js')));
});
