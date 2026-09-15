import { useEffect, useState } from 'react';

// Called inside the native masthead with its existing Keycloak instance.
// No additional login, token storage, or interception of console requests.
export function useConsoleAvatar(keycloak) {
  const [url, setUrl] = useState();
  useEffect(() => {
    let stopped = false, timer, request, version = 0;
    const channel = typeof BroadcastChannel === 'function' ? new BroadcastChannel('qrlogin-avatar-changed') : null;
    async function refresh() {
      const current = ++version;
      clearTimeout(timer); request?.abort(); request = new AbortController();
      try {
        if (!keycloak.authenticated) { setUrl(undefined); return; }
        const base = new URL(keycloak.authServerUrl, location.href);
        if (base.origin !== location.origin) { setUrl(undefined); return; }
        await keycloak.updateToken(30);
        const endpoint = `${base.href.replace(/\/$/, '')}/realms/${encodeURIComponent(keycloak.realm)}/avatars/me?size=64`;
        const response = await fetch(endpoint, { headers: { Authorization: `Bearer ${keycloak.token}` },
          credentials: 'omit', cache: 'no-store', signal: request.signal });
        const item = response.ok ? await response.json() : null;
        if (stopped || current !== version) return;
        if (item?.status !== 'CUSTOM' || !item.url) { setUrl(undefined); return; }
        const parsed = new URL(item.url);
        if (!['http:', 'https:'].includes(parsed.protocol)) { setUrl(undefined); return; }
        // Display only images the browser can actually load, including mixed-content policy.
        const image = new Image(); image.referrerPolicy = 'no-referrer';
        image.onload = () => { if (!stopped && current === version) setUrl(parsed.href); };
        image.onerror = () => { if (!stopped && current === version) setUrl(undefined); };
        image.src = parsed.href;
      } catch (error) {
        if (!stopped && current === version && error.name !== 'AbortError') setUrl(undefined);
      } finally {
        if (!stopped && current === version) timer = setTimeout(refresh, 30000);
      }
    }
    const changed = () => { if (!document.hidden) refresh(); };
    window.addEventListener('qrlogin-avatar-changed', changed);
    window.addEventListener('focus', changed);
    document.addEventListener('visibilitychange', changed);
    if (channel) channel.onmessage = changed;
    refresh();
    return () => { stopped = true; version++; clearTimeout(timer); request?.abort(); channel?.close();
      window.removeEventListener('qrlogin-avatar-changed', changed); window.removeEventListener('focus', changed);
      document.removeEventListener('visibilitychange', changed); };
  }, [keycloak]);
  return url;
}
