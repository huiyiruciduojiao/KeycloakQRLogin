# Keycloak QR Login

易识IT 开发的基于二维码的 Keycloak 登录插件。

## 项目概述

本项目是一个 Keycloak 身份提供商（Identity Provider）插件，实现了基于二维码的登录功能。用户可以通过扫描二维码，在移动设备上确认登录，实现更便捷、安全的身份验证。

主要特性：
- 支持二维码生成和展示
- 支持扫码状态轮询
- 支持基于JWT Token的身份验证
- 支持内存和Redis两种会话存储方式
- 完整的签名验证机制保证通信安全
- 可配置的各项参数

## 技术架构

### 主要组件

1. **QRLoginIdentityProvider**: 核心身份提供商实现
2. **QRLoginEndpoint**: REST API端点，处理扫码、确认等操作
3. **SessionStore**: 会话存储接口及实现（内存/Redis）
4. **SignatureUtil**: 签名工具，保障通信安全
5. **前端脚本**: 实现二维码展示和状态轮询

### 数据流

```

1. 用户访问登录页面 -> 2. 点击"二维码登录" -> 3. 生成二维码并展示
   ↓
4. 用户使用App扫描二维码 <- 5. App调用/qr/scan接口
   ↓
6. 用户在App确认登录 -> 7. App调用/qr/confirm接口
   ↓
8. 前端轮询/qr/status获取状态 -> 9. 跳转到确认后的回调URL完成登录
```
## 配置说明

### 插件配置项

| 配置项 | 说明 | 默认值 |
|-------|------|--------|
| Session TTL (s) | 二维码会话有效期(秒) | 120 |
| Poll Interval (ms) | 前端轮询间隔(毫秒) | 1500 |
| Store Type | 存储类型(redis或memory) | memory |
| Redis URI | Redis连接地址 | redis://127.0.0.1:6379 |
| Redis Namespace | Redis键前缀 | qrlogin: |
| HMAC Secret | 签名校验密钥 | change-me |
| Algorithm | 签名算法 | HmacSHA256 |
| Time Window Seconds | 请求有效时间范围(秒) | 5 |

### 环境要求

- Keycloak 20+
- Java 17+
- Redis (可选，用于分布式部署)

## 安装部署

1. 构建项目:
   ``` bash
    mvn clean package
   ```
2. 将生成的JAR包复制到Keycloak的providers目录:
   ```bash
    cp target/keycloak-qr-login-*.jar $KEYCLOAK_HOME/providers/
   ```
3. 重启Keycloak服务

4. 在Keycloak管理界面中配置QR Login身份提供商

## API 接口

详细API文档请参考 [OpenAPI 文档](src/main/resources/openapi/qrlogin-openapi.yaml)

主要接口:
- POST `/qr/scan`: 处理二维码扫描
- POST `/qr/confirm`: 处理登录确认
- GET `/qr/status`: 查询二维码状态

## 开发指南

### 项目结构

```

src/main/java/top/ysit/qrlogin
├── config                 # 配置类
├── core                   # 核心组件
│   ├── security           # 安全相关
│   ├── store              # 会话存储
│   └── util               # 工具类
├── idp                    # 身份提供商实现
│   ├── resource           # REST资源
│   │   ├── endpoint       # 端点实现
│   │   └── factory        # 工厂类
│   └── QRLoginIdentityProvider*
└── spi                    # SPI实现
```
### 扩展功能

1. 添加新的存储实现: `core\SessionStore.java` 接口
2. 自定义签名算法: 扩展 `SignatureConfig.SignatureAlgorithm`
3. 修改前端样式: 编辑主题文件 `theme/qrlogin/login/resources/js/script.js`

## 安全说明

1. 所有API调用均需签名验证
2. 时间戳验证防止重放攻击
3. JWT Token验证确保用户身份合法性
4. 会话具有固定有效期，防止长期有效攻击

## 特殊说明

1. Redis功能暂时没有开发完整，待完善。
2. 基于IDP ISP 实现的，所以用户第一次登录时，需要绑定账号
3. 前端登录效果需要依赖`theme/qrlogin/login/resources/js/script.js`文件

## 许可证

Apache License 2.0

## 联系我们

易识IT 

