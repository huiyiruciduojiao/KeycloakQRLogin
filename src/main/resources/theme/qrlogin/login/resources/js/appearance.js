/* Runs before styles to avoid a light flash; stores only a visual preference. */
(() => {
    'use strict';
    const key = 'ysit.auth.appearance';
    const root = document.documentElement;
    const system = window.matchMedia('(prefers-color-scheme: dark)');
    const valid = value => ['auto', 'light', 'dark'].includes(value);
    let mode = 'auto';
    try { const saved = localStorage.getItem(key); if (valid(saved)) mode = saved; } catch (_) { /* Storage may be disabled. */ }
    const apply = () => {
        const dark = mode === 'dark' || (mode === 'auto' && system.matches);
        root.dataset.appearance = mode;
        root.dataset.theme = dark ? 'dark' : 'light';
        root.classList.toggle('pf-v5-theme-dark', dark);
        root.style.colorScheme = dark ? 'dark' : 'light';
        const select = document.getElementById('auth-theme-mode');
        if (select) select.value = mode;
    };
    apply();
    system.addEventListener('change', apply);
    window.addEventListener('storage', event => {
        if (event.key !== key && event.key !== null) return;
        mode = valid(event.newValue) ? event.newValue : 'auto';
        apply();
    });
    document.addEventListener('DOMContentLoaded', () => {
        const select = document.getElementById('auth-theme-mode');
        const control = document.getElementById('auth-appearance');
        if (!select || !control) return;
        control.hidden = false;
        select.value = mode;
        select.addEventListener('change', () => {
            mode = valid(select.value) ? select.value : 'auto';
            try { localStorage.setItem(key, mode); } catch (_) { /* Keep working without persistence. */ }
            apply();
        });
    });
})();
