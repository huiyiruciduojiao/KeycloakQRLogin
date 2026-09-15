const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/theme/qrlogin/login/resources/js/appearance.js'), 'utf8');
function setup(saved, dark, blocked = false) {
    const events = {}, inputEvents = {}, media = { matches: dark, addEventListener: (_, fn) => { events.media = fn; } };
    const root = { dataset: {}, style: {}, classList: { toggle: (_, on) => { root.darkClass = on; } } };
    const select = { value: '', addEventListener: (_, fn) => { inputEvents.change = fn; } }, control = { hidden: true };
    let stored = saved;
    vm.runInNewContext(source, {
        document: { documentElement: root, getElementById: id => id === 'auth-theme-mode' ? select : control, addEventListener: (_, fn) => { events.ready = fn; } },
        window: { matchMedia: () => media, addEventListener: (_, fn) => { events.storage = fn; } },
        localStorage: { getItem: () => { if (blocked) throw Error(); return stored; }, setItem: (_, value) => { if (blocked) throw Error(); stored = value; } }
    });
    events.ready();
    return { root, select, control, media, events, change(value) { select.value = value; inputEvents.change(); }, stored: () => stored };
}
test('default auto follows system changes', () => {
    const p = setup(null, true); assert.equal(p.root.dataset.theme, 'dark'); assert.equal(p.select.value, 'auto');
    p.media.matches = false; p.events.media(); assert.equal(p.root.dataset.theme, 'light'); assert.equal(p.control.hidden, false);
});
test('manual preference persists and ignores system changes', () => {
    const p = setup('light', true); assert.equal(p.root.dataset.theme, 'light'); p.change('dark');
    assert.equal(p.stored(), 'dark'); p.media.matches = false; p.events.media(); assert.equal(p.root.dataset.theme, 'dark');
    assert.equal(setup(p.stored(), false).root.dataset.theme, 'dark');
});
test('invalid values and blocked storage remain safe', () => {
    assert.equal(setup('bad', false).select.value, 'auto');
    const p = setup(null, false, true); p.change('dark'); assert.equal(p.root.dataset.theme, 'dark');
});
test('cross-tab preference sync and reset', () => {
    const p = setup('light', true); p.events.storage({ key: 'ysit.auth.appearance', newValue: 'dark' });
    assert.equal(p.select.value, 'dark'); p.events.storage({ key: null, newValue: null }); assert.equal(p.select.value, 'auto');
});
