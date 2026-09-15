# 用户头像接入与运维

本文面向业务 Web / Android 接入方、Keycloak 管理员和部署运维。契约以当前仓库实现为准，目标版本固定为 **Keycloak 26.7.3**。头像模块与扫码登录独立：接入头像不需要修改 Browser Flow、配置 QR 执行器或分配扫码角色。

## 最小接入流程

1. 部署方在单节点测试实例安装插件，启用 `declarative-ui`，准备 `data/avatars` 持久化目录，按下文设置管理员主题。
2. Realm 管理员在“Realm 设置 → 用户头像”启用模块，填写浏览器/客户端可访问的 Keycloak 根地址。默认关闭“领域允许”，将业务 client ID 加入允许客户端；如需要账户页面上传和删除，加入 `account-console`。
3. 业务应用复用当前 Realm 的 OIDC 登录与 Access Token。头像不需要 audience mapper、额外角色或新的登录流程；跨域 Web 应用另配置客户端 Web Origins。
4. 用 `GET /me` 读取当前用户头像；列表、聊天等场景使用 `POST /batch` 传入当前 Realm 的用户 ID；上传和删除只能操作 Token 用户本人。
5. 将返回的图片 URL 直接交给图片组件，不携带 Bearer。自定义地址到期前重新查询；收到替换/删除通知时重新查询，不手工拼装或修改签名地址。

例如：Keycloak 根地址为 `https://sso.example.com/auth`、Realm 为 `demo` 时，接口根地址为 `https://sso.example.com/auth/realms/demo/avatars`。管理员“公开访问地址”填写前者，而不是 Realm issuer 或 `/avatars` 地址。域名和用户 ID 示例均为占位值。

## 能力与边界

- 固定 Keycloak **26.7.3**，同一插件 JAR 内独立的 `avatars` Realm REST Provider，不参与扫码事务。
- 同 Realm、启用且有有效会话的普通用户可以查询其他启用普通用户的头像，只能修改自己的头像。服务账号首版拒绝，不按邮箱关联用户。
- REST 上传 JPEG / 非动画 PNG，文件最多 **5 MiB（5,242,880 字节）**、**4,000,000 像素（宽 × 高）**；服务端先检查尺寸，再完整解码、中心裁剪、去元数据并重新编码 64 / 128 / 256 像素 PNG。透明区域合成白底，不保留原文件或动画。
- 账户页面允许选择最多 5 MiB、1600 万像素的原图，在浏览器裁剪为 512 × 512 PNG 后上传；这不表示 REST 接受 1600 万像素原文件。自行接入上传时应在客户端压缩/裁剪到 REST 限制以内，服务端仍独立校验。
- 账户中心提供预览、缩放/位置裁剪、进度、删除确认、中英文和窄屏布局。账户页面通过官方 KeycloakProvider 复用内存中的 Token，不持久化 Token。
- 管理后台和账户中心顶部展示登录用户的有效自定义头像；图片预加载成功后才替换默认头像。上传/删除通过页面事件和同源 BroadcastChannel 通知刷新，并在窗口恢复焦点或每 30 秒重新查询。失败或删除时回退默认头像。管理后台读取登录领域的用户头像，不使用当前被管理领域的用户 ID。
- 不提供任意远程图片 URL 抓取，不自动写 OIDC `picture`，不改用户属性、账户链接或数据库结构。
- **仅单节点**。本地文件锁拒绝两个进程共享同一目录；这不是集群实现。需要独立持久化卷，不能使用容器临时文件层。
- 图像处理固定使用 Java headless AWT，不需要 X11、桌面环境或 `libXrender`。运行时仍须包含 JDK/JRE 的 `java.desktop` 模块及其 headless 原生库；不要使用裁掉该模块的自定义 jlink 镜像。

## 管理员界面配置

进入 **管理员控制台 → 选择 Realm → Realm 设置 → 用户头像**。配置持久化到当前 Realm 的组件数据中；不读取 `QR_AVATAR_*` 环境变量或头像 Provider 文本参数。

| 表单项 | 默认 | 说明 |
| --- | --- | --- |
| 启用用户头像 | 关闭 | 只对当前 Realm 生效；关闭保留文件 |
| 公开访问地址 | 必填（启用时） | Keycloak HTTP 或 HTTPS 根地址（界面显示 HTTP 明文传输警告），保留 /auth 等路径，不含 /realms |
| 领域允许（允许领域内所有客户端） | 关闭 | 开启后允许当前 Realm 内所有已启用客户端，忽略白名单；仍需有效普通用户会话 |
| 允许的客户端 | 领域允许关闭时必填 | 逐项添加现有且启用的客户端 ID；账户中心添加 account-console；开启领域允许时保留列表但不使用 |
| 图片地址有效期（秒） | 900 | 60–3600 秒 |
| 头像存储容量（字节） | 1073741824 | 当前 Realm 的独立配额，至少 1 MiB；替换时需容纳新旧两个版本 |

保存需要 `manage-realm` 权限，读取需要 `view-realm`，均通过 Keycloak 原生组件管理接口检查并记录管理员事件。保存对新请求生效，无需重启；已开始的请求使用其配置快照。降低配额不会删除已有图片，只限制新写入。每个 Realm 只能有一份头像设置；异常重复记录时失败关闭。

### 原生管理员扩展的安装条件

Keycloak 26.7.3 的 `UiTabProvider` 是受 **declarative-ui** 功能开关控制的实验性界面扩展。安装插件时，部署需要启用这一 Keycloak 功能（启动/build 参数 `--features=declarative-ui`，已有其他 features 应保留）。这是原生扩展能力的一次性安装条件，不是头像业务配置；头像业务参数全部通过管理员表单维护。未启用此能力时原生控制台不显示页签，不能声称仅复制 JAR 即可显示。

管理员登录所在 Realm 及被管理的目标 Realm 的 Admin theme 均应选用 `qrlogin`，以加载新增中英文标签；例如从 master 登录管理其他 Realm 时，两处均需设置。该配置也在管理员界面的 Themes 页操作。

### 存储与密钥管理

- 图片自动存入 `<Keycloak安装目录>/data/avatars/SHA256(realmId)/`，其中继续使用模块内部的 Realm/user/object 分层。不给 Realm 管理员开放任意服务器路径输入，避免通过配置写入主机其他目录。持久化卷仍由部署平台负责。
- 头像签名使用当前 Realm 的 HS256 密钥，通过独立用途标识派生子密钥；不向浏览器返回密钥，不保存头像专用的明文 Secret 配置。
- 密钥生命周期在原生 **Realm 设置 → 密钥** 管理。创建更高优先级的 HS256 HMAC provider 后，新地址使用新 key；旧 provider 保持 enabled、可设为 passive，供旧地址验证；等待最长 TTL 后再禁用旧 provider。禁用旧 key 会使对应头像 URL 在服务端失效。
- HS256 provider 可能同时服务 Realm 的其他功能，轮换应考虑其全部使用方；不要为了头像自动删除现有密钥。数据库备份包含 Realm 配置与密钥，仍需对备份实施访问控制。
- 普通保存头像设置不更改用户、客户端、mapper、登录流程或密钥 provider。

### 客户端与网络

1. 不校验头像专用 audience，无需添加 audience mapper；仍校验允许的客户端、有效用户及会话。旧配置中的 audience 被忽略，下次保存时移除。HTTP 图片地址允许保存；界面常显明文传输警告，HTTPS 页面可能因混合内容策略阻止 HTTP 图片。
2. 账户中心仍使用 Authorization Code + PKCE；不为头像功能启用密码模式或服务账号。
3. 跨域 Web 调用需在调用客户端的 Web Origins 配置具体 Origin；支持 Keycloak 的 `+` 解析，拒绝 `*` 通配。服务器同源 Origin 允许。OPTIONS 只开放 `me` 与 `batch`，响应不启用跨域 Cookie。
4. 网关对整个 multipart 请求设置 **6 MiB 请求上限**、合理的上传超时和按用户/IP限流，防止上传解析阶段消耗资源；服务端另有 5 MiB 文件读取与像素上限、最多两个并发图片解码器。不能将解码器限制视为完整流量限流。
5. 不开放存储目录静态映射，不在访问日志中记录完整签名查询串；业务页面图片建议 `referrerpolicy="no-referrer"`。

## REST 契约

基路径：`/realms/{realm}/avatars`。完整机器可读契约：`src/main/resources/openapi/avatar-openapi.yaml`。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/me?size=128` | 本人头像信息 |
| PUT | `/me` | multipart 单个 `file` 字段；不能传目标 userId |
| DELETE | `/me` | 幂等删除，返回默认头像信息 |
| POST | `/batch` | 批量获取头像地址 |
| GET | `/content/{asset}/{size}?exp=...&kid=...&sig=...` | 无需 Bearer 的签名图片地址 |
| GET | `/default/{size}` | 通用默认头像 |

管理与查询接口均需 `Authorization: Bearer <access-token>`。`GET /me` 只需当前 Realm 有效普通用户的 Access Token 和会话，不受头像客户端白名单或“领域允许”开关限制，签发客户端仍必须存在且启用；跨域浏览器请求仍校验其 Web Origins。上传、删除和批量查询继续执行头像客户端策略。ID Token / Refresh Token 不能替代 Access Token。

### 鉴权与客户端策略

| 场景 | Bearer / 会话 | 头像客户端策略 | 补充检查 |
| --- | --- | --- | --- |
| GET `/me` | 同 Realm 启用普通用户的有效会话 | 不受白名单/领域允许限制 | Token 的 `azp`（签发 client ID）对应客户端必须存在且启用 |
| PUT / DELETE `/me` | 同上 | 白名单；或开启领域允许后允许本 Realm 全部启用客户端 | 修改对象只能是 Token 用户，无目标用户参数 |
| POST `/batch` | 同上 | 同上 | 目标也必须是本 Realm 可访问的启用普通用户 |
| 签名图片 GET | 不需要 | 不检查调用方 client | 校验签名、有效期、当前版本和目标用户状态 |
| 默认图片 GET | 不需要 | 不检查调用方 client | 模块和 Realm 必须启用 |

没有 Cookie 认证回退；服务账号不支持，管理员 Token 也不能替其他用户上传。所有功能默认关闭，`GET /me` 的客户端策略豁免并不绕过模块开关或用户/会话检查。头像无 audience 要求，扫码登录的 audience 要求保持独立。

用户 ID 使用同一 Realm 的 Keycloak 用户标识，例如业务已有的 OIDC `sub` 映射；不要传用户名、邮箱，或按邮箱自动建立账号关联。跨 Realm 相同字符串不表示同一个用户。

### 本人读取、上传与删除

`GET /me?size=64` 返回一个头像对象，不是 `{items: [...]}`。省略 size 时为 128，仅接受 64、128、256。上传和删除均返回 128px 的最新头像对象；需要其他尺寸时再次 GET，不要修改返回 URL 的 size 部分。

```json
{
  "userId": "user-id-1",
  "status": "DEFAULT",
  "url": "https://sso.example.com/realms/example/avatars/default/128",
  "version": "default-v1",
  "expiresAt": null
}
```

以下为 Bash/curl 示例。`AVATAR_API` 和 `ACCESS_TOKEN` 由调用方在本地设置，使用已有用户会话的 Access Token，不额外申请管理员权限。不要将真实 Token 写入脚本、工单、日志或提交到仓库；共享终端还需注意命令历史和进程参数可见性。

```bash
# AVATAR_API=https://sso.example.com/realms/example/avatars
# ACCESS_TOKEN=<当前用户的有效 Access Token>

curl --fail-with-body "$AVATAR_API/me?size=128" \
  -H "Authorization: Bearer $ACCESS_TOKEN"

curl --fail-with-body -X PUT "$AVATAR_API/me" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -F "file=@./avatar.png;type=image/png"

curl --fail-with-body -X DELETE "$AVATAR_API/me" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

必须是 `PUT` 而不是 POST，multipart 恰好一个 `file` 字段。不得添加 `userId`、裁剪坐标或额外文件。让 HTTP 库生成 multipart boundary，不手工设置裸 `Content-Type: multipart/form-data`。删除本来没有头像的用户仍成功返回 DEFAULT。

### 批量请求

```json
{
  "userIds": ["user-id-1", "user-id-2", "user-id-3"],
  "size": 128
}
```

- 原始数组 1–100 个 ID，总 JSON 请求最多 64 KiB；单个 ID 非空，最多 1024 个字符。
- ID 是不透明字符串，不要求 UUID。按首次出现顺序去重，返回每个唯一 ID 的结果。
- `CUSTOM`：有头像，url 为签名 URL，version 为当前随机资产 ID，expiresAt 为 UTC ISO 时间。
- `DEFAULT`：用户可访问但未设置头像，url 为默认图片，version 为 `default-v1`，expiresAt 为空。
- `UNAVAILABLE`：不存在、禁用或不允许访问，url/version/expiresAt 为空或省略。不区分具体原因，不附带姓名邮箱等资料。
- 认证失败、参数错误、存储失败以整批错误返回，不能当成“没有头像”。

```json
{
  "items": [
    {"userId":"user-id-1","status":"CUSTOM","url":"https://sso.example.com/realms/example/avatars/content/0123456789abcdef0123456789abcdef/128?exp=1900000000&kid=v1&sig=...","version":"0123456789abcdef0123456789abcdef","expiresAt":"2030-03-17T17:46:40Z"},
    {"userId":"user-id-2","status":"DEFAULT","url":"https://sso.example.com/realms/example/avatars/default/128","version":"default-v1"},
    {"userId":"user-id-3","status":"UNAVAILABLE"}
  ]
}
```

示例域名、ID、签名和时间仅说明响应形状。

### Web 示例

下例复用已有 `keycloak-js` 实例。`issuer` 来自可信部署配置，例如 `https://sso.example.com/realms/example`，不能取自任意二维码或用户输入。Token 只用于可信 API，不发送给返回的图片地址。

```js
const api = `${issuer.replace(/\/$/, '')}/avatars`;

async function avatarRequest(path, options = {}) {
  await keycloak.updateToken(30);
  if (!keycloak.authenticated || !keycloak.token) throw new Error('login_required');
  const response = await fetch(`${api}${path}`, {
    ...options,
    credentials: 'omit', cache: 'no-store',
    headers: { ...options.headers, Authorization: `Bearer ${keycloak.token}` }
  });
  const data = await response.json().catch(() => null);
  if (!response.ok) {
    const error = new Error(data?.error || 'avatar_request_failed');
    error.status = response.status;
    throw error;
  }
  return data;
}

const me = await avatarRequest('/me?size=64');

// userIds 原始长度不得超过 100；更长列表由业务端分批，限制并发。
const { items } = await avatarRequest('/batch', {
  method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ userIds, size: 128 })
});
const byId = new Map(items.map(item => [item.userId, item]));

// file 已满足格式、文件大小和像素限制；需要自定义裁剪时先生成 Blob。
const form = new FormData();
form.append('file', file, file.name);
const updated = await avatarRequest('/me', { method: 'PUT', body: form });
const deleted = await avatarRequest('/me', { method: 'DELETE' });
```

示例按调用场景选用，不应在读取头像后立即自动上传/删除。`file` 和 `userIds` 由业务页面提供。

```html
<!-- src 使用查询返回的 URL；不添加 Authorization、Token 或自造签名参数。 -->
<img src="返回的头像 URL" width="64" height="64" alt="用户头像"
     referrerpolicy="no-referrer">
```

显示前可预加载图片；加载失败回退业务默认图。UNAVAILABLE 不应显示身份详情或被转换成“该用户没有设置头像”。签名图片没有为 Canvas 读取提供专用 CORS 契约，不要假设跨域图片可无污染地绘制/导出；裁剪应使用本地选择文件。

### 刷新与缓存约定

- 查询结果按 `issuer + 当前登录账号 + 目标 userId + size` 隔离，登出、切换账号或 Realm 时清空内存缓存。不要持久化 Token 或把签名 URL 当作永久 OIDC `picture`。
- CUSTOM 使用 `expiresAt` 安排提前刷新，例如提前 30 秒并加入随机抖动；应用恢复前台时检查到期。`version` 相同不代表 URL 未过期，每次查询的有效期可能不同。
- DEFAULT 没有签名有效期，但用户以后可能上传头像，因此业务数据仍需按刷新策略重新查询。默认图片本身也受模块启用状态影响。
- 上传/删除成功后以响应更新本人头像，作废业务缓存并通知当前业务 UI。原生控制台的页面事件/BroadcastChannel 仅在同源上下文生效，不是跨域应用或移动端通知协议；这些调用方需要自己的刷新机制。
- 图片返回 404 时最多重新查询一次最新地址；若模块已关闭或仍不可用，回退本地默认图，避免无限循环。

### Android / 服务端调用方

使用已有 HTTP 客户端向 `/batch` POST 同样 JSON，带有效用户 Access Token。按 `userId` 建 Map 后将非空 `url` 交给图片加载组件，无需为图片请求追加 Token。默认地址可长期复用；自定义地址按 `expiresAt` 刷新。失败要区分 Token 失效、输入错误、临时服务故障。首版服务账号不开放，业务后端如需独立机器身份查询，应另行设计显式只读客户端策略。

Android 上传使用 multipart PUT、字段名 `file`；删除使用 DELETE。图片加载客户端不得全局向外部图片域注入用户 Bearer。浏览器 CORS 不适用于原生 HTTP 客户端，但鉴权、同 Realm 和客户端策略完全相同。业务后端没有仅凭 client credentials 查询头像的接口；不得收集或持久化用户 Token 来绕过这一限制。

## 错误码与排查

模块处理的错误通常返回 `{"error":"client_not_allowed"}` 等 JSON；反向代理、multipart 解析器及 Keycloak 框架错误可能是其他格式，调用方要容忍非 JSON 响应。不要只依赖错误字符串而忽略 HTTP 状态。

| HTTP | error | 处理建议 |
| --- | --- | --- |
| 400 | `invalid_size` | 只用 64 / 128 / 256 |
| 400 | `invalid_user_ids` | 检查原始 1–100 条、非空 ID、单 ID 最多 1024 字符 |
| 400 | `invalid_request` / `expected_single_file` / `invalid_image` | 检查 JSON、恰好一个 file 字段及可解码图片；修正后再提交 |
| 401 | `invalid_session` | 检查 Access Token 与会话；通过既有 OIDC 流程刷新或重新登录 |
| 403 | `client_not_allowed` | 核对 Token azp、客户端启用状态、白名单/领域允许；GET-me 不需要白名单但仍要求有效客户端 |
| 403 | `not_allowed` | 核对会话所属 Realm、用户启用状态，排除服务账号 |
| 403 | `origin_not_allowed` | 检查实际 Origin、对应客户端的精确 Web Origins 及预检路径 |
| 404 | `avatars_disabled` | 检查当前 Realm/module 开关；不是用户没有头像 |
| 404 | `avatar_unavailable` | 地址过期、签名不匹配、已替换/删除或用户不可用；重新查询最新地址，不自行改签名 |
| 413 | `image_too_large` / `image_dimensions_exceeded` / `request_too_large` | 压缩/裁剪文件，或减少 JSON；网关 413 也需单独排查 |
| 415 | `unsupported_image` | 改用 JPEG 或非动画 PNG，不支持 SVG/GIF/WebP/APNG |
| 429 | `image_processing_busy` | 节点两个解码槽忙，延迟并有限重试；不要并发反复上传 |
| 503 | `avatar_storage_unavailable` / `ambiguous_avatar_configuration` | 运维检查目录权限、独占锁、磁盘/元数据及重复 Realm 组件；不要伪装成 DEFAULT |
| 503 | `avatar_image_runtime_unavailable` | 检查运行时是否包含 `java.desktop` 及 headless 原生库；升级修复版 JAR 并重启，不要求安装 X11 桌面依赖 |
| 507 | `storage_capacity_exceeded` | 当前 Realm 配额不足；由管理员调整配额或安排离线清理，不自动删除现有头像 |

上传超时、网络断开或成功事件记录异常时，不确定结果应先 GET-me 对账；请求失败不一定意味着文件未提交。对 400/403/413/415/507 不做无条件自动重试，对临时故障采用有上限的退避。框架异常也可能返回 500，需要运维日志定位。

当前 CORS OPTIONS 缓存 Realm 启用客户端的 Origin 快照 60 秒，配置模式/白名单/公开地址变化使用不同缓存键，预检响应 Max-Age 为 300 秒。客户端 Web Origins 或启用状态变更可能需要等待预检缓存更新；实际 Bearer 请求始终重新校验对应客户端和 Origin，旧预检成功不授权实际请求。

若日志出现 `libawt_xawt.so` / `libXrender.so.1`，说明正在运行的是未包含本修复的旧插件，或 JVM 中已经提前初始化了非 headless AWT。先核对部署 JAR 的版本/摘要并完整重启进程。修复版模块在首次图像处理类初始化时自行选择 headless 后端，**不要求**额外设置 `JAVA_OPTS_APPEND`，也不依赖 `libXrender`、DISPLAY、X11 服务或桌面软件包。不要以安装完整 X11 桌面作为修复，也不要在未验证新 JAR 的情况下反复上传。

上传/删除成功通过 Keycloak `UPDATE_PROFILE` 事件记录 `avatar_action=upload/delete`；模块处理的 401/403/404 拒绝通过 `UPDATE_PROFILE_ERROR` 记录 `avatar_action=reject` 和错误码。要留存需配置原生事件保存或监听器。不要将 Bearer、完整签名 URL 或存储内部路径写入业务日志。

## 缓存、持久化与失败语义

- 签名图片和默认图片支持跨域 fetch，按当前 Realm 已启用客户端的 Web Origins 回显具体允许来源。图片没有调用客户端身份，因此该来源检查不使用管理/批量接口的客户端白名单；签名、用户和版本仍须有效。图片来源快照最长缓存 60 秒，修改 Web Origins 后可能需要等待其刷新。
- 图片 200/304 均包含 `Vary: Origin`；允许跨域时包含 `Access-Control-Allow-Origin` 并暴露 ETag。图片 GET 预检允许 `If-None-Match`。图片 fetch 使用 `credentials: 'omit'`，不携带 Cookie 或额外 Bearer Token。升级修复后重新获取签名 URL，避免沿用浏览器中缺少 CORS 头的旧缓存。

- JSON 响应 `no-store`。签名图片为 private cache，最多 300 秒且不超过剩余签名 TTL，支持 ETag。签名和当前用户/版本校验发生在 304 判断前。
- 知道签名 URL 的人可在到期前使用或转发它；它不是调用者绑定的登录权限。已下载或仍在浏览器缓存期内的图片不能远程收回。
- 替换/删除后旧 URL 服务端读取失败；正在进行的读取或客户端已有副本不受追溯撤销。
- 相对于前述每 Realm 管理目录，内部目录结构为 `SHA256(realmId)/users/SHA256(userId).ref` 指向 `SHA256(realmId)/objects/<randomAssetId>/`，其中保存 owner 与三张 PNG；也就是完整路径包含外层管理目录和内层 Realm 隔离目录。路径不使用上传文件名或原始用户 ID。业务端不要依赖目录布局，部署备份整个 `data/avatars`。
- 元数据完全独立于 Keycloak User Profile，兼容可读取但不可修改的用户存储。图片文件写入并 force 后，使用文件系统原子替换 `.ref` 作为提交点；不持有 Keycloak model/session 对象。
- 写入与删除串行化，最后提交者生效。图片提交独立于 Keycloak DB 事务，不能承诺跨文件系统/数据库的原子提交。上传/删除成功事件通过 Keycloak UPDATE_PROFILE 管道记录 `avatar_action`，需启用用户事件保存或监听器才能留存；事件写入失败不应被当作图片必然未保存，重试前可查询本人头像。
- 旧文件清理失败不撤销已提交版本，日志提示离线维护。崩溃可能留下未引用对象；文件/目录的断电持久性依赖底层文件系统和存储设备，不等同于数据库级断电保证。
- 用户禁用或删除会立即阻止后续服务端图片读取，但不会自动删除该用户的文件；删除账户前可先通过该用户自助接口清除头像，批量数据保留/清理应由管理员单独管理。

### 离线清理

停止使用该头像目录的 Keycloak 实例后执行，命令会获取相同的独占文件锁。先预览：

```text
java -cp KeycloakQRLogin-2.1.jar top.ysit.qrlogin.avatar.AvatarMaintenance /absolute/keycloak/data/avatars/<realmId的SHA256>
```

确认预览后加 `--delete`。只清理超过 24 小时且没有 `.ref` 引用的对象，保留当前头像、近期对象、用户引用。遇到损坏引用、未知对象内容或符号链接会中止，不递归清空目录。不连接 Keycloak，不判断用户是否已删除；空目录及临时 pointer 文件不在自动清理范围。

命令中的路径应替换为实际的每 Realm 管理目录，不能传整个 Keycloak 安装目录或 `data/avatars` 总目录。先停机并备份，再按每 Realm 分别预览；Windows 路径使用引号。`--delete` 是明确的数据删除操作，不属于普通接入步骤。

## 构建

```text
npm ci --prefix account-ui --ignore-scripts
npm run build --prefix account-ui
mvn clean verify
node --test src/test/js/*.test.cjs
```

- Java 源码目标 17；实际 Keycloak 26.7.3 验收使用 Java 21。
- 账户 UI 固定 `@keycloak/keycloak-account-ui` / `@keycloak/keycloak-ui-shared` 为 26.7.3，依赖由 `account-ui/package-lock.json` 锁定。
- 原生 26.7.3 `content.json` 动态模块路由存在缺陷，因此使用官方组件库构建主题入口，显式添加头像 route，保留原生功能开关与账户页面。没有复制或改写登录、密码、安全会话业务逻辑。
- 官方组件库内置的 PatternFly PageContext 与宿主 Page 默认不共享，导致导航按钮失效；构建适配器 `account-ui/scripts/account-page-context.mjs` 将该上下文统一到宿主 PatternFly。适配器绑定 26.7.3 的结构，匹配失败会中止构建；升级组件库时必须重新检查并执行浏览器导航回归。
- `resources/avatar-dist` 和 `resources/.vite/manifest.json` 是随 JAR 分发的构建产物；UI 源码变更后必须重新运行 npm build，普通 Maven 构建不需要下载 Node 依赖。
- 管理后台复用固定版本的官方发行资源，通过 `python scripts/build-admin-avatar.py /path/org.keycloak.keycloak-admin-ui-26.7.3.jar` 在原生 Masthead 中加入头像 Hook；各业务页面保持原生代码。生成目录 `admin/resources/avatar-admin/<内容摘要>/` 和 Vite manifest 随 JAR 分发。升级 Keycloak 时必须重新审查构建适配器；结构不匹配会中止构建。共享 Hook 位于 `common-avatar/use-console-avatar.js`，修改后须同时重建账户主题和管理员资源。
- 从 master 登录的管理员需要在 master 开启头像模块，头像存放于 master。目标领域的头像开关不会替登录领域启用功能。

## 启用、迁移和回滚

1. 明确目标实例与 Realm，先备份当前插件 JAR、Realm 配置、主题及受影响客户端配置；已有头像目录需停止写入后整体备份。数据库备份并不包含本模块图片和元数据。
2. 准备 Keycloak data 目录的持久化卷和网关限制，停机后将新 JAR 安装到目标 Keycloak 的 `providers`，替换旧版本，不能同时保留两个同名 Provider 版本。启用 `declarative-ui` 并保留其他功能参数，按原部署方式 build/restart。不自动更改监听地址、防火墙、代理或登录流程。
3. 在管理员界面的 Themes 页选择 `qrlogin` Admin 主题（同时检查管理员登录 Realm），再在头像页配置公开 URL、客户端与容量。目标 Realm 的 Account theme 为 `qrlogin` 时可见头像页 `/realms/{realm}/account/content/avatar`，如有 `/auth` 上下文则相应保留。只接入 REST 的业务端不需要替换 Account theme；账户头像页和顶部自定义头像需要对应主题。
4. 按下文验收清单验证上传、其他用户批量查询、图片读取、重启持久化和删除。现有未设置头像的用户返回 DEFAULT，不迁移或修改联合账户链接，不把原 OIDC picture 自动导入本模块。
5. 快速关闭：在管理员头像页关闭“启用用户头像”并保存，保留存储目录；业务应用回退本地默认图。完整回滚：停止实例，恢复旧 JAR、原主题选择及本次变更的配置，再 build/restart。头像接入不需要添加 mapper，也无需撤销已有扫码 audience mapper；不要删除原角色、客户端、用户或图片目录。共享 JAR 还含扫码模块，恢复旧包前需评估扫码功能版本影响。

本次实现前 Git 快照：`codex/backup-before-avatar-20260915-100954`，提交 `26e8c913205682d63a041edf43141a277d4cd9a1`。快照通过独立 index 创建，保留了原暂存状态。需检查旧代码可在独立工作区打开该分支；不要对当前含其他改动的工作区执行 `reset --hard`。

## 接入验收清单

自动测试只能使用隔离 fixture；以下生产/业务环境检查需获得明确目标授权，不自动操作原 Realm 或部署。

- 配置：默认关闭时 API 返回 404；原生头像页签可见、权限正确、保存和重新加载一致；白名单/领域允许切换后符合策略，无 avatar audience mapper 或秘密表单字段。
- 鉴权：缺失/失效 Token、错误 Realm、禁用用户、服务账号被拒绝；白名单外启用客户端 GET-me 成功，而上传/删除/batch 仍按策略拒绝。普通用户不能替其他用户修改头像。
- 上传：真实 Bearer + multipart PUT 成功，图片是 64/128/256 PNG；检查 5 MiB、400 万像素边界、不支持格式、额外字段、忙状态和配额不足，失败不破坏旧头像。
- 查询：批量三种状态、非 UUID ID、首次出现去重、原始 100 条上限，未知/禁用用户不泄露身份；存储异常不能返回 DEFAULT。
- 图片：真实 PNG 请求与 ETag 304；签名篡改、到期、换尺寸、跨 Realm、替换/删除后的旧地址均不成功；检查代理下公开 URL、TLS/混合内容和缓存头。
- 前端：本地裁剪、上传/删除、刷新、导航、Token 刷新、窄屏和错误提示；顶部头像预加载失败回退默认。管理员管理其他 Realm 时仍读取登录 Realm 的本人头像。
- 运维：独立持久化卷、重启恢复、权限和独占锁、网关请求限制、事件留存、备份/恢复、密钥轮换与回滚。第二节点共享目录不属于支持范围。

本仓库的版本化验收证据见 [头像验收记录](AVATAR-TEST-20260915.md)。单元测试、实际 HTTP、浏览器、目标部署及存储故障演练应分别记录，不能互相替代。

## 隔离验收

`scripts/start-avatar-fixture.ps1` 仅使用项目 `.local/avatar-live` 下的独立发行副本；复制 Keycloak 26.7.3 的 bin/lib，不能复制原数据库、配置或 providers。脚本导入固定 `avatar-acceptance` 测试 Realm，生成本地测试证书，监听 127.0.0.1:8546/8547，管理端口 9547。它不适合生产部署。

先运行 `npm run test:browser --prefix account-ui`，使用测试 Realm 的测试账户验证 HTTP 和无头 Chrome，并在该测试 Realm 创建/更新头像配置。再运行 `npm run test:admin --prefix account-ui`，验证原生表单编辑、保存、重新加载及管理员权限；此测试仅为隔离 master 和 avatar-acceptance 选择 qrlogin Admin 主题。测试凭据为明确的本地 fixture 值，Token 只在内存中使用。浏览器证书忽略仅针对本地测试上下文。真实生产代理、只读联合用户目录、移动端和存储故障演练仍需在对应环境单独验收。
