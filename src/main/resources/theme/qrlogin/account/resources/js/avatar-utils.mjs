export function cropRect(width, height, zoom, x, y) {
  const edge = Math.min(width, height) / Math.max(1, Math.min(3, zoom));
  return { x: (width - edge) * Math.max(0, Math.min(100, x)) / 100,
    y: (height - edge) * Math.max(0, Math.min(100, y)) / 100, edge };
}

export function accountAvatarUrl(environment, origin) {
  const base = new URL(environment.serverBaseUrl.endsWith('/') ? environment.serverBaseUrl : `${environment.serverBaseUrl}/`, origin);
  if (base.origin !== origin) throw new Error('invalid_origin');
  return new URL(`realms/${encodeURIComponent(environment.realm)}/avatars/me`, base).href;
}

export function refreshDelay(expiresAt, now = Date.now()) {
  return expiresAt ? Math.max(1000, Math.min(900000, Date.parse(expiresAt) - now - 30000)) : 900000;
}

export const messages = {
  en: {
    title: 'User avatar', intro: 'A familiar face across your account and connected applications.',
    current: 'Current avatar', choose: 'Choose an image', hint: 'JPEG or PNG, up to 5 MB and 16 million pixels. Drag the sliders to crop your image.',
    preview: 'Avatar preview', zoom: 'Zoom', horizontal: 'Horizontal position', vertical: 'Vertical position',
    save: 'Save avatar', cancel: 'Cancel', remove: 'Remove avatar', removeTitle: 'Remove your avatar?',
    removeHint: 'Your account will display the default avatar.', confirmRemove: 'Remove', loading: 'Loading avatar…',
    saved: 'Avatar saved.', removed: 'Avatar removed.', uploading: 'Uploading', processing: 'Preparing image…',
    retry: 'Try again', generic: 'Could not update the avatar. Please try again.',
    invalid_session: 'Your session has expired. Reload the account console to sign in again.',
    avatars_disabled: 'Avatar editing is not enabled for this realm. Please contact your administrator.',
    client_not_allowed: 'The account console is not enabled for avatar editing. Please contact your administrator.',
    not_allowed: 'This account cannot use avatars.', invalid_csrf: 'The page session changed. Please try again.',
    invalid_origin: 'Open the account console through its configured public address.',
    image_too_large: 'Choose an image smaller than 5 MB.', image_dimensions_exceeded: 'Choose an image with no more than 16 million pixels.',
    unsupported_image: 'Choose a non-animated JPEG or PNG image.', invalid_image: 'This image cannot be decoded. Please choose another.',
    storage_capacity_exceeded: 'Avatar storage is full. Please contact your administrator.',
    avatar_storage_unavailable: 'Avatar storage is temporarily unavailable. Please try again later.',
    image_processing_busy: 'Image processing is busy. Please try again shortly.'
  },
  zh: {
    title: '用户头像', intro: '让账户中心和关联应用中的你更容易被认出。',
    current: '当前头像', choose: '选择图片', hint: '支持 JPEG、PNG，最大 5 MB、1600 万像素。可通过滑块调整裁剪区域。',
    preview: '头像预览', zoom: '缩放', horizontal: '水平位置', vertical: '垂直位置',
    save: '保存头像', cancel: '取消', remove: '删除头像', removeTitle: '确定删除头像？',
    removeHint: '删除后将显示默认头像。', confirmRemove: '删除', loading: '正在加载头像…',
    saved: '头像已保存。', removed: '头像已删除。', uploading: '正在上传', processing: '正在处理图片…',
    retry: '重试', generic: '头像更新失败，请重试。',
    invalid_session: '登录已过期，请刷新账户中心重新登录。',
    avatars_disabled: '当前 Realm 尚未启用头像编辑，请联系管理员。',
    client_not_allowed: '账户中心尚未获得头像编辑权限，请联系管理员。',
    not_allowed: '当前账户无法使用头像功能。', invalid_csrf: '页面会话已变化，请重试。',
    invalid_origin: '请通过已配置的公开地址访问账户中心。',
    image_too_large: '请选择不超过 5 MB 的图片。', image_dimensions_exceeded: '请选择不超过 1600 万像素的图片。',
    unsupported_image: '请选择非动画的 JPEG 或 PNG 图片。', invalid_image: '无法读取该图片，请重新选择。',
    storage_capacity_exceeded: '头像存储空间已满，请联系管理员。',
    avatar_storage_unavailable: '头像存储暂时不可用，请稍后重试。',
    image_processing_busy: '图片处理繁忙，请稍后重试。'
  }
};
