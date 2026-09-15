const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/theme/qrlogin/login/resources/js/script.js'), 'utf8');

function fixture(fetch) {
    const tasks = new Map(), intervals = new Map(), handlers = {};
    let now = Date.now();
    let nextId = 0, navigations = 0;
    const elements = {
        'qr-check': { disabled: false },
        'qr-network-error': { hidden: true }, 'qr-status': { textContent: '' }
        , 'qr-state-overlay': { hidden: true }, 'qr-overlay-status': { textContent: '' }, 'qr-overlay-restart': { hidden: true },
        'qr-countdown': { hidden: true }, 'qr-time-left': { textContent: '' }
    };
    const form = {
        classList: { add: name => { form.expiredClass = name; } },
        action: 'http://localhost/realms/master/login-actions/authenticate?session_code=old',
        dataset: { qrState: 'PENDING', qrExpiry: String(now + 120000), qrPending: 'Waiting', qrScanned: 'Scanned', qrConfirmed: 'Confirmed',
            qrDenied: 'Denied on phone', qrExpired: 'Expired', qrCancelled: 'Cancelled', qrConsumed: 'Consumed' },
        addEventListener: (name, handler) => { handlers[name] = handler; },
        dispatchEvent: event => handlers[event.type]?.(event),
        requestSubmit: button => {
            let prevented = false;
            handlers.submit({ submitter: button, preventDefault: () => { prevented = true; } });
            if (!prevented) navigations++;
        }
    };
    elements['qr-login-form'] = form;
    const document = { visibilityState: 'visible', getElementById: id => elements[id], addEventListener: (name, fn) => { if (name === 'DOMContentLoaded') fn(); } };
    const window = {
        location: { href: form.action, origin: 'http://localhost' },
        setTimeout: (fn, delay) => { const id = ++nextId; tasks.set(id, { fn, delay }); return id; },
        clearTimeout: id => tasks.delete(id), addEventListener: () => {}
        , setInterval: fn => { const id = ++nextId; intervals.set(id, fn); return id; },
        clearInterval: id => intervals.delete(id)
    };
    vm.runInNewContext(source, { document, window, URL, Date: {now: () => now}, AbortController, Event, fetch });
    return { form, elements, document, tasks, intervals, advance: ms => { now += ms; for (const fn of intervals.values()) fn(); }, navigations: () => navigations, tick: async () => {
        const [id, task] = tasks.entries().next().value;
        tasks.delete(id); await task.fn();
    } };
}
const response = state => ({ ok: true, headers: { get: () => 'application/json' }, json: async () => ({
    state, expiresAt: Date.now() + 120000,
    action: 'http://localhost/realms/master/login-actions/authenticate?session_code=new'
}) });

test('pending polling refreshes the action without navigating or overlapping', async () => {
    let calls = 0;
    const f = fixture(async (_, opts) => { calls++; assert.equal(opts.body, 'operation=status'); assert.equal(opts.redirect, 'error'); return response('SCANNED'); });
    await f.tick(); await f.tick();
    assert.equal(calls, 2); assert.equal(f.navigations(), 0);
    assert.match(f.form.action, /session_code=new/);
    assert.equal(f.elements['qr-state-overlay'].hidden, false);
    assert.equal(f.elements['qr-overlay-status'].textContent, 'Scanned');
    assert.equal(f.elements['qr-status'].hidden, true);
    assert.equal(f.tasks.size, 1);
});
test('confirmed status navigates exactly once using the native form', async () => {
    const f = fixture(async () => response('CONFIRMED'));
    await f.tick();
    assert.equal(f.elements['qr-state-overlay'].hidden, false);
    assert.equal(f.elements['qr-overlay-status'].textContent, 'Confirmed');
    assert.equal(f.navigations(), 1); assert.equal(f.tasks.size, 0);
});
test('denied status stays on the QR page and offers a new QR code', async () => {
    const f = fixture(async () => response('DENIED'));
    await f.tick();
    assert.equal(f.navigations(), 0);
    assert.equal(f.elements['qr-overlay-status'].textContent, 'Denied on phone');
    assert.equal(f.elements['qr-state-overlay'].hidden, false);
    assert.equal(f.elements['qr-countdown'].hidden, true);
    assert.equal(f.elements['qr-check'].disabled, true);
    assert.equal(f.elements['qr-overlay-restart'].hidden, false);
    assert.equal(f.form.expiredClass, 'qr-is-denied');
    assert.equal(f.tasks.size, 0);
    assert.equal(f.intervals.size, 0);
});
test('network failures stop after three attempts and preserve manual retry', async () => {
    const f = fixture(async () => { throw new Error('offline'); });
    await f.tick(); await f.tick(); await f.tick();
    assert.equal(f.tasks.size, 0); assert.equal(f.navigations(), 0);
    assert.equal(f.elements['qr-network-error'].hidden, false);
    assert.equal(f.elements['qr-check'].disabled, false);
});
test('expired and hidden pages do not make status requests', async () => {
    let calls = 0; const f = fixture(async () => { calls++; return response('PENDING'); });
    f.document.visibilityState = 'hidden'; await f.tick(); assert.equal(calls, 0);
    f.document.visibilityState = 'visible'; f.form.dataset.qrExpiry = '0'; await f.tick();
    assert.equal(calls, 0); assert.equal(f.elements['qr-state-overlay'].hidden, false);
    assert.equal(f.elements['qr-overlay-status'].textContent, 'Expired');
});
test('manual submission waits for the in-flight action URL', async () => {
    let resolve; const f = fixture(() => new Promise(r => { resolve = r; }));
    const pending = f.tick();
    f.form.requestSubmit(f.elements['qr-check']); assert.equal(f.navigations(), 0);
    resolve(response('PENDING')); await pending;
    assert.equal(f.navigations(), 1); assert.match(f.form.action, /session_code=new/);
});
test('countdown independently masks expiration and stops timers without a server request', () => {
    const f = fixture(async () => { throw Error('must not fetch'); });
    assert.equal(f.elements['qr-time-left'].textContent, '02:00');
    f.advance(1000);
    assert.equal(f.elements['qr-time-left'].textContent, '01:59');
    f.advance(119000);
    assert.equal(f.elements['qr-state-overlay'].hidden, false);
    assert.equal(f.elements['qr-overlay-status'].textContent, 'Expired');
    assert.equal(f.elements['qr-overlay-restart'].hidden, false);
    assert.equal(f.form.expiredClass, 'qr-is-expired');
    assert.equal(f.elements['qr-status'].hidden, true);
    assert.equal(f.elements['qr-countdown'].hidden, true);
    assert.equal(f.elements['qr-check'].disabled, true);
    assert.equal(f.tasks.size, 0); assert.equal(f.intervals.size, 0);
    assert.equal(f.navigations(), 0);
});
test('server expiration masks the QR even when browser time is behind', async () => {
    const f = fixture(async () => response('EXPIRED'));
    await f.tick();
    assert.equal(f.elements['qr-state-overlay'].hidden, false);
    assert.equal(f.elements['qr-overlay-status'].textContent, 'Expired');
    assert.equal(f.tasks.size, 0); assert.equal(f.intervals.size, 0);
});
test('closing QR panel stops its countdown', () => {
    const f = fixture(async () => response('PENDING'));
    f.form.dispatchEvent(new Event('qr:stop'));
    assert.equal(f.tasks.size, 0); assert.equal(f.intervals.size, 0);
});
