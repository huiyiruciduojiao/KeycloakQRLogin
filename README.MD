# Keycloak QR Login 2

基于 Keycloak **26.7.3 Authentication SPI** 的同 Realm 手机扫码登录。手机批准一个已有浏览器认证事务，浏览器通过 Keycloak 原生 action 完成认证。无需 QR IdP 或首次联邦账号绑定。

## 支持范围

- 同 Realm 已登录移动用户；校验 Bearer token、指定 audience、指定 azp；同 Realm 的有效普通用户无需额外角色。
- 一次性事务，绑定 Realm、root authentication session、tab、client UUID、execution；扫描与确认绑定相同用户 ID。
- 手机 `scan`、`confirm`、`deny` 的成功与失败都会写入独立的 Keycloak 用户安全事件；事件包含动作、结果、用户/会话、客户端和不可逆事务指纹，不记录 bearer token 或原始二维码事务 ID。
- PENDING → SCANNED → CONFIRMED → CONSUMED，以及 DENIED、CANCELLED；到期立即失效。
- **仅支持单个 Keycloak 节点**。存储最多 10,000 条未到期事务，并限制每个 Realm 最多 1,000 条，避免单个 Realm 耗尽整个节点的事务池；重启丢失待登录事务。集群/Redis 尚未实现，不能使用负载均衡粘性会话替代跨节点存储。
- 用户 token 不写入事务、不进入二维码/URL、不返回给浏览器。浏览器没有公开状态查询接口，通过当前 Keycloak action POST 检查状态。
- 中英文登录主题；禁用 JavaScript 时可以手动点击“检查确认状态”。
- 事务消费后如果后续 Keycloak 请求/事务失败，需要重新扫码；不会放回已消费事务。

## 用户头像

插件新增独立头像模块：自助上传/更换/删除、默认头像、64/128/256 像素图片，以及传入最多 100 个用户 ID 批量获取可直接展示的签名地址。账户中心包含头像预览与裁剪页面。模块默认关闭，在管理员控制台“Realm 设置 → 用户头像”配置开关、允许客户端、公开地址、有效期和容量；存储目录与签名密钥由服务端管理。不使用头像环境变量或配置文本。原生管理员扩展需在安装时启用 Keycloak 26.7.3 的 `declarative-ui` 功能，详见接入文档。

配置、接口示例、构建与回滚见 [头像接入文档](docs/AVATAR-INTEGRATION.md)，机器可读契约见 [Avatar OpenAPI](src/main/resources/openapi/avatar-openapi.yaml)，验证范围见 [头像验收记录](docs/AVATAR-TEST-20260915.md)。首版仅支持单节点；未自动部署到原实例。

## 构建步骤

使用 Maven 3.9+ 和 JDK 17+ 编译。实际服务器运行使用目标 Keycloak 版本所支持的 Java 运行时。仓库未附 Maven Wrapper。

```shell
mvn clean verify
```

产物：`target/KeycloakQRLogin-2.1.jar`（shaded 插件，包含 ZXing，不打包 Keycloak）。
每次部署前必须 clean 构建，避免 target 中残留旧 SPI service 文件。

## 配置与部署

1. 按 [迁移与回滚](docs/MIGRATION.md) 备份现有插件、Realm 配置和账号数据。
2. 在停止的测试 Keycloak 实例中用新 JAR 替换旧 JAR（不能同时放置两个版本）；执行 `bin/kc.sh build` 或 Windows 的 `bin/kc.bat build`，再启动。
3. Realm Settings → Themes，将 Login Theme、Account Theme、Admin Console Theme 和 Email Theme 分别选择 `qrlogin`。账户页继承 26.7.3 的 `keycloak.v3` React 主题，管理界面继承 `keycloak.v2` React 主题；二者与登录页使用相同的 `brand-logo.png`。邮件主题继承官方 `keycloak` 内容模板，统一覆盖所有 HTML 邮件布局，并为 18 类原生纯文本邮件追加自动发送/请勿回复页脚。如果已有定制主题，应逐项合并并重新验证，不能直接复制旧版本页面模板。
4. 复制当前 Browser Flow，再加入 `QR Login (single node)` 执行器。先仅对测试 client 使用 Browser Flow override。
5. 设置执行器配置：
   - `mobileClientId`：允许批准登录的移动 App client ID，必填。
   - `audience`：专用于扫码确认 API 的 token audience，必填。
   - `ttlSeconds`：30–300 秒，默认 120。
6. 无需创建或分配扫码专用角色。配置 audience mapper，让 access token 的 aud 包含执行器配置的 audience。App 使用 Authorization Code + PKCE 登录同一 Realm。
7. 保留密码等回退路径。将 QR 和密码配置成并列 ALTERNATIVE 子流程，并根据实际策略安排后续 OTP/条件 MFA；不要把扫码视为自动满足 MFA。Flow 的 REQUIRED/ALTERNATIVE 组合必须在测试 Realm 验证。
8. 配置 HTTPS，并在入口网关为认证发起和手机端操作设置合理限流。移动 App 只允许预配置的可信 HTTPS issuer/域名，绝不能把 Bearer token 发到二维码任意指定的地址。

在扫码被配置为后续身份验证步骤时，确认用户必须和前一步识别的用户一致。新的执行器不会删除或自动重绑现有 federated identity。

## App 协议 v2（与旧接口不兼容）

二维码 JSON：

```json
{
  "v": 2,
  "type": "keycloak_qr_login",
  "transaction": "43-character-base64url-random-id",
  "verification_uri": "https://id.example.com/realms/demo/qr-login",
  "user_code": "4782",
  "expires_at": 1789382400000
}
```

不包含浏览器 authentication session ID、token 或签名。expires_at 是 UTC epoch 毫秒。

App 必须先校验二维码的版本和可信地址，再调用：

```http
POST /realms/demo/qr-login/transactions/{transaction}/scan
Authorization: Bearer <access-token>
```

成功响应包含 status、服务端 application 名称、user_code、expires_at。显示这些服务端字段，让用户核对应用和电脑上的校验码；必须由用户明确确认后调用 confirm，拒绝则调用 deny。

```http
POST /realms/demo/qr-login/transactions/{transaction}/confirm
Authorization: Bearer <access-token>
```

scan、confirm、deny 均无请求体。同一用户重试当前已完成的操作可幂等成功；不同用户操作或非法状态转换返回 409。401 表示凭据无效；403 表示 client/用户不满足策略；404 表示事务不存在、过期或不属于当前 Realm。

登录页点击“二维码登录”会在当前页面打开紧凑弹窗，通过当前流程允许的扫码执行器生成二维码；等待及刷新二维码期间不改变主页面地址。弹窗仅保留刷新与右上角关闭，不显示手动检查按钮。浏览器约每 3 秒向当前 Keycloak action 发送 `operation=status` 的后台 POST，仅更新状态，不整页刷新。关闭弹窗先通过 `dismiss` 作废二维码，再切回密码执行器并更新原页面表单地址，保留已填写的输入。后台标签页暂停轮询，连续网络失败三次停止自动检查，提示刷新二维码重试。二维码状态离开 `PENDING` 后立即遮挡二维码，并只在遮挡层显示新状态；手机确认后提交隐藏的原生表单按钮，继续后续步骤、MFA 或 OAuth 跳转。手机拒绝或二维码过期后停留在扫码界面，可直接生成新二维码，不向业务客户端导航 `access_denied` 错误。不支持 dialog 时回退到独立二维码页；扫码登录需要 JavaScript，禁用时显示启用提示。短验证码是用户核对提示，不能彻底阻止攻击者转发整个二维码的钓鱼攻击。

登录与注册共用浅色现代主题 `auth-modern.css`，保留 Keycloak 原生注册模板、动态用户资料字段、密码校验与错误提示。主题不会自动开放 Realm 注册。

手机端接入步骤、Token 要求、状态流转、错误处理与联调清单见 [扫码接入指南](docs/QR-INTEGRATION.md)。机器可读接口详见 [OpenAPI](src/main/resources/openapi/qrlogin-openapi.yaml)。

## 测试与验收

单元测试覆盖事务过期、身份绑定、Realm/浏览器隔离、幂等、并发消费、容量限制、配置校验、认证器用户状态和 REST token 策略。Keycloak 原生 token 验证通过调用其 BearerTokenAuthenticator 复用，单元测试不等于真实 JWT/SSO 验收。

上线前必须在真实 Keycloak 26.7.3 完成：登录/账户/管理/邮件主题加载，密码/扫码切换，手机批准及拒绝，错误 issuer/aud/azp，过期与撤销 token，禁用用户，多标签页，Required Actions，MFA，SMTP HTML/纯文本邮件和 OAuth code/PKCE 回调测试。升级 Keycloak 需重新编译并做这些兼容性验证。

Keycloak 26.4.2 升级到已选定目标版本 26.7.3 的生产影响、等价环境要求和回滚边界见 [生产升级评估](docs/KEYCLOAK-UPGRADE-ASSESSMENT.md)。源码依赖已固定为 26.7.3，但这不代表任何测试或生产实例已完成升级；测试、预生产和生产必须使用同一精确版本和同一插件构建产物。

架构约束见 [AGENTS.md](AGENTS.md)。

