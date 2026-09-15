import { createElement as h, useEffect, useRef, useState } from 'react';
import { useEnvironment } from '@keycloak/keycloak-account-ui';
import { accountAvatarUrl, cropRect, messages, refreshDelay } from './avatar-utils.mjs';

// Uses the official account console's existing auth context. Tokens remain in memory.
export default function AvatarPage() {
  const { environment, keycloak } = useEnvironment();
  const t = messages[(environment.locale || document.documentElement.lang).startsWith('zh') ? 'zh' : 'en'];
  const [avatar, setAvatar] = useState(null);
  const [bitmap, setBitmap] = useState(null);
  const [crop, setCrop] = useState({ zoom: 1, x: 50, y: 50 });
  const [busy, setBusy] = useState(false);
  const [progress, setProgress] = useState(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [removing, setRemoving] = useState(false);
  const input = useRef(null), canvas = useRef(null), active = useRef(false), xhr = useRef(null);
  const imageRef = useRef(null), timer = useRef(null), controller = useRef(null), selection = useRef(0);
  const deleteConfirm = useRef(null), removeButton = useRef(null);
  let url;
  try { url = accountAvatarUrl(environment, location.origin); } catch { /* Render an actionable configuration error. */ }
  const fail = (code) => { if (active.current) setError(t[code] || t.generic); };

  async function load() {
    if (!url) throw new Error('invalid_origin');
    await keycloak.updateToken(30);
    const response = await fetch(url, { credentials: 'omit', headers: { Authorization: `Bearer ${keycloak.token}` }, cache: 'no-store', signal: controller.current?.signal });
    let data;
    try { data = await response.json(); } catch { throw new Error(response.status === 413 ? 'image_too_large' : 'avatar_storage_unavailable'); }
    if (!response.ok) throw new Error(data.error || 'generic');
    if (active.current) {
      setAvatar(data);
      clearTimeout(timer.current);
      timer.current = setTimeout(() => load().catch(e => fail(e.message)), refreshDelay(data.expiresAt));
    }
    return data;
  }
  useEffect(() => {
    active.current = true; controller.current = new AbortController();
    load().catch(e => { if (e.name !== 'AbortError') fail(e.message); });
    return () => {
      active.current = false; selection.current++; clearTimeout(timer.current); controller.current.abort();
      xhr.current?.abort(); imageRef.current?.close();
    };
  }, []);
  useEffect(() => {
    if (!bitmap || !canvas.current) return;
    const context = canvas.current.getContext('2d');
    const rect = cropRect(bitmap.width, bitmap.height, crop.zoom, crop.x, crop.y);
    context.fillStyle = '#fff'; context.fillRect(0, 0, 512, 512);
    context.drawImage(bitmap, rect.x, rect.y, rect.edge, rect.edge, 0, 0, 512, 512);
  }, [bitmap, crop]);
  useEffect(() => { if (removing) deleteConfirm.current?.focus(); }, [removing]);

  function clearSelection() {
    selection.current++; imageRef.current?.close(); imageRef.current = null;
    setBitmap(null); if (input.current) input.current.value = '';
  }
  async function choose(event) {
    const file = event.target.files?.[0]; if (!file) return;
    const id = ++selection.current;
    setError(''); setNotice(''); setRemoving(false);
    try {
      if (!['image/jpeg', 'image/png'].includes(file.type)) throw new Error('unsupported_image');
      if (file.size > 5 * 1024 * 1024) throw new Error('image_too_large');
      const image = await createImageBitmap(file);
      if (image.width * image.height > 16000000) { image.close(); throw new Error('image_dimensions_exceeded'); }
      if (!active.current || id !== selection.current) { image.close(); return; }
      imageRef.current?.close(); imageRef.current = image;
      setBitmap(image); setCrop({ zoom: 1, x: 50, y: 50 });
    } catch (e) { fail(e.message === 'The source image could not be decoded.' ? 'invalid_image' : e.message); }
  }
  async function mutate(remove) {
    setBusy(true); setProgress(null); setError(''); setNotice('');
    try {
      await keycloak.updateToken(30);
      if (!active.current) return;
      let body;
      if (!remove) {
        const blob = await new Promise(resolve => canvas.current.toBlob(resolve, 'image/png'));
        if (!blob) throw new Error('invalid_image');
        body = new FormData(); body.append('file', blob, 'avatar.png');
      }
      if (!active.current) return;
      const updated = await new Promise((resolve, reject) => {
        const request = new XMLHttpRequest(); xhr.current = request;
        request.open(remove ? 'DELETE' : 'PUT', url); request.timeout = 60000;
        request.setRequestHeader('Authorization', `Bearer ${keycloak.token}`);
        request.upload.onprogress = event => { if (active.current && event.lengthComputable) setProgress(Math.round(event.loaded / event.total * 100)); };
        request.onload = () => {
          let data;
          try { data = JSON.parse(request.responseText); } catch { reject(new Error(request.status === 413 ? 'image_too_large' : 'generic')); return; }
          if (request.status >= 200 && request.status < 300) resolve(data); else reject(new Error(data.error || 'generic'));
        };
        request.onerror = request.ontimeout = request.onabort = () => reject(new Error('generic'));
        request.send(body);
      });
      if (!active.current) return;
      setAvatar(updated); clearSelection(); setRemoving(false); setNotice(remove ? t.removed : t.saved);
      window.dispatchEvent(new Event('qrlogin-avatar-changed'));
      if (typeof BroadcastChannel === 'function') {
        const channel = new BroadcastChannel('qrlogin-avatar-changed'); channel.postMessage('changed'); channel.close();
      }
      clearTimeout(timer.current); timer.current = setTimeout(() => load().catch(e => fail(e.message)), refreshDelay(updated.expiresAt));
      input.current?.focus();
    } catch (e) { if (e.name !== 'AbortError') fail(e.message); }
    finally { if (active.current) { setBusy(false); setProgress(null); } xhr.current = null; }
  }
  const button = (text, props) => h('button', { type: 'button', className: 'avatar-button', ...props }, text);
  const slider = (key, label, min, max, step) => h('label', { className: 'avatar-slider', key },
    h('span', null, label), h('input', { type: 'range', min, max, step, value: crop[key], disabled: busy,
      onChange: event => setCrop(previous => ({ ...previous, [key]: Number(event.target.value) })) }));
  const disabled = busy || !avatar;
  return h('main', { className: 'avatar-page', 'aria-busy': busy },
    h('header', { className: 'avatar-heading' }, h('h1', null, t.title), h('p', null, t.intro)),
    h('section', { className: 'avatar-card', 'aria-label': t.title },
      error && h('div', { className: 'avatar-message avatar-error', role: 'alert' }, h('p', null, error),
        button(t.retry, { disabled: busy, onClick: () => { setError(''); load().catch(e => fail(e.message)); } })),
      notice && h('p', { className: 'avatar-message', role: 'status' }, notice),
      !avatar && !error && h('p', { role: 'status' }, t.loading),
      h('div', { className: 'avatar-current' },
        avatar && h('button', { className: 'avatar-image-button', type: 'button', disabled, onClick: () => input.current.click(), 'aria-label': t.choose },
          h('img', { src: avatar.url, alt: t.current, width: 128, height: 128, referrerPolicy: 'no-referrer', onError: () => fail('avatar_storage_unavailable') })),
        h('div', null, h('h2', null, t.current), h('p', { id: 'avatar-hint' }, t.hint),
          h('input', { ref: input, className: 'avatar-file', type: 'file', accept: 'image/jpeg,image/png', disabled,
            'aria-label': t.choose, 'aria-describedby': 'avatar-hint', onChange: choose }),
          button(t.choose, { disabled, onClick: () => input.current.click() }))),
      bitmap && h('div', { className: 'avatar-editor' },
        h('canvas', { ref: canvas, width: 512, height: 512, role: 'img', 'aria-label': t.preview }),
        h('div', { className: 'avatar-adjustments' }, h('h2', null, t.preview),
          slider('zoom', t.zoom, 1, 3, 0.05), slider('x', t.horizontal, 0, 100, 1), slider('y', t.vertical, 0, 100, 1),
          h('div', { className: 'avatar-actions' }, button(t.save, { disabled: busy, className: 'avatar-button avatar-primary', onClick: () => mutate(false) }),
            button(t.cancel, { disabled: busy, onClick: clearSelection })))),
      busy && h('div', { role: 'status', 'aria-live': 'polite', className: 'avatar-progress' },
        h('p', null, progress === null ? t.processing : `${t.uploading} ${progress}%`),
        h('progress', { max: 100, ...(progress === null ? {} : { value: progress }), 'aria-label': t.uploading })),
      avatar?.status === 'CUSTOM' && !bitmap && h('div', { className: 'avatar-delete' },
        removing ? h('div', { className: 'avatar-confirm', role: 'group', 'aria-label': t.removeTitle },
          h('h2', null, t.removeTitle), h('p', null, t.removeHint), h('div', { className: 'avatar-actions' },
            button(t.confirmRemove, { ref: deleteConfirm, disabled: busy, onClick: () => mutate(true) }),
            button(t.cancel, { disabled: busy, onClick: () => { setRemoving(false); setTimeout(() => removeButton.current?.focus(), 0); } }))) :
          button(t.remove, { ref: removeButton, disabled: busy, onClick: () => setRemoving(true) }))));
}
