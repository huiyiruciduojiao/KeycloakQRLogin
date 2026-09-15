const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/theme/qrlogin/login/resources/js/modal.js'), 'utf8');
function setup() {
    const action = 'http://localhost/realms/test/login-actions/authenticate?execution=password';
    const events = {}, requests = [], responses = [];
    const node = (id, extra = {}) => ({
        id, action, hidden: false, dataset: {}, tagName: 'FORM', attrs: {}, handlers: {},
        classList: { add() {}, toggle() {} }, setAttribute(k,v) { this.attrs[k] = v; },
        removeAttribute(k) { delete this.attrs[k]; }, focus() {}, querySelector() { return null; },
        addEventListener(k,fn) { this.handlers[k] = fn; }, dispatchEvent() {},
        replaceChildren(...children) { this.children = children; }, append() {}, ...extra
    });
    const entry = node('qr-login-entry');
    const panel = node('qr-login-dialog', { hidden: true, contains() { return false; } });
    const password = node('kc-form-login', { typedUsername: 'preserved' });
    const open = node('qr-login-open'), close = node('qr-dialog-close'), content = node('qr-dialog-content');
    const nodes = Object.fromEntries([entry,panel,password,open,close,content].map(n=>[n.id,n]));
    const document = {
        body: node('body'), getElementById: id => nodes[id],
        addEventListener: (k,fn) => { events[k] = fn; }, dispatchEvent() {},
        importNode: form => form, createElement: () => node('error'),
        querySelectorAll: sel => sel === 'form[id]' ? [entry,password] : []
    };
    vm.runInNewContext(source, {
        document, location: {href:action, origin:'http://localhost'}, URL, URLSearchParams,
        FormData: class { constructor() { return [['authenticationExecution','qr']]; } },
        AbortController, setTimeout, clearTimeout, Event,
        DOMParser: class { parseFromString(page) { return page; } },
        fetch: async (url,options) => {
            requests.push({url, body:options.body.toString()});
            const result = responses.shift();
            if (result instanceof Error) throw result;
            return {ok:true, text:async()=>result, json:async()=>result};
        }
    });
    events.DOMContentLoaded();
    const qrPage = () => ({getElementById:id => id === 'qr-login-form' ? node(id,{action:action.replace('password','qr')}) : null});
    const passwordPage = {getElementById:id => ['kc-form-login','qr-login-entry'].includes(id) ? node(id,{action:action+'&fresh=1'}) : null};
    return {entry,panel,password,open,close,content,requests,responses,qrPage,passwordPage,
        start: () => entry.handlers.submit({preventDefault(){}}),
        stop: () => close.handlers.click()};
}
test('inline QR switch restores native actions without replacing password input', async () => {
    const p = setup();
    p.responses.push(p.qrPage());
    await p.start();
    assert.equal(p.panel.hidden,false); assert.equal(p.password.hidden,true);
    assert.equal(p.open.attrs['aria-selected'],'true');
    p.responses.push({action:'http://localhost/realms/test/login-actions/authenticate?execution=qr'},p.passwordPage);
    await p.stop();
    assert.equal(p.panel.hidden,true); assert.equal(p.password.hidden,false);
    assert.equal(p.password.typedUsername,'preserved');
    assert.match(p.password.action,/fresh=1/);
    assert.match(p.requests[1].body,/operation=dismiss/);
    assert.match(p.requests[2].body,/authenticationExecution=password/);
});
test('failed password restoration keeps the password form inaccessible', async () => {
    const p = setup(); p.responses.push(p.qrPage()); await p.start();
    p.responses.push(new Error('network')); await p.stop();
    assert.equal(p.password.hidden,true); assert.equal(p.panel.hidden,false);
    assert.equal(p.close.disabled,false);
});
test('repeated QR selection does not create another transaction', async () => {
    const p = setup(); p.responses.push(p.qrPage()); await p.start(); await p.start();
    assert.equal(p.requests.length,1);
    assert.equal(p.entry.attrs.role,'tablist');
    assert.equal(p.open.tabIndex,0);
});
test('loading reserves a skeleton until the fetched QR form is mounted', async () => {
    const p = setup();
    let resolve;
    p.responses.push(new Promise(done => { resolve = done; }));
    const pending = p.start();
    assert.equal(p.content.attrs['aria-busy'], 'true');
    assert.equal(p.content.children[0].className, 'qr-loading-placeholder');
    assert.equal(p.password.hidden, true);
    resolve(p.qrPage());
    await pending;
    assert.equal(p.content.attrs['aria-busy'], 'false');
    assert.equal(p.content.children[0].id, 'qr-login-form');
});
