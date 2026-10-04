![NEOauth Logo](src/main/resources/logo.png)

# NEOauth

**NEOauth** 是适用于 **NeoForge 1.21.1** 的纯服务端登录与认证模组。客户端无需安装 NEOauth，正版客户端、离线客户端和皮肤站客户端都可以连接服务器。

> 当前版本：**1.3.37**
> Minecraft：**1.21.1**  · NeoForge：**21.1.x**  · 服务端：`online-mode=false`

## 功能概览

- 纯服务端运行，客户端不需要安装模组
- 离线密码注册与登录：`/register <密码>`、`/login <密码>`
- 正版自动识别与自动登录，无需执行 `/online`
- 正版玩家首次进入自动创建账号
- Mojang 验证超时、失败或服务不可用时，已知正版身份可使用备用密码登录
- 正版 UUID 与签名 `textures` 属性缓存，支持离线显示正版皮肤
- 正版名称被离线客户端使用时，按离线身份进入密码登录，不再强制正版握手
- SQLite 异步存储，不需要 MySQL
- Argon2id 密码哈希，兼容旧 PBKDF2 数据并在成功登录后升级
- 默认中文提示，内置中文、英文、西班牙文
- 默认登录时间 300 秒（5 分钟），带 BossBar 倒计时和周期性登录指引
- 断线后 10 分钟内可恢复会话，默认不绑定 IP
- 可选 TOTP 双因素认证，默认关闭
- 可配置注册冷却、IP 注册数量限制、密码长度和登录尝试次数
- 支持正版 UUID 数据迁移和可配置模组数据迁移

## 玩家登录指引

玩家进入后会看到清晰的中文提示：

```text
已有账号：/login <密码>
新玩家：/register <密码>
```

未认证期间，服务器会显示登录倒计时，并每隔一段时间重复发送一次指引，避免玩家因为没有看到首次提示而不知道下一步操作。

### 常用命令

| 命令 | 用途 |
| --- | --- |
| `/register <密码>` | 注册离线账号 |
| `/login <密码>` | 登录已有账号 |
| `/logout` | 注销当前会话并断开连接 |
| `/changepassword <旧密码> <新密码>` | 修改密码 |
| `/setpassword <密码>` | 为自动创建的正版账号设置备用密码 |
| `/unregister <密码>` | 删除自己的账号 |
| `/totp setup` | 创建 TOTP（需要配置开启） |
| `/totp verify <验证码>` | 验证 TOTP |
| `/totp disable` | 关闭 TOTP |

`/online` 仍保留兼容，但在 `premiumAutoLogin=true` 时正版玩家不需要执行它。

## 正版与离线登录策略

NEOauth 会根据数据库记录、客户端 UUID 和正版验证结果选择登录方式：

```text
已知正版账号 + 匹配正版 UUID
  → 正版 UUID + 签名皮肤 + 自动登录

已知正版名称 + 不匹配正版 UUID（离线客户端）
  → 离线 UUID + 密码登录

普通离线账号
  → 离线 UUID + 密码登录

Mojang 验证失败/超时
  → 已知正版身份使用缓存身份或备用密码
  → 未知玩家进入注册/密码流程
```

离线玩家不会因为服务器尝试正版验证而被强制等待加密握手。正版名称的离线玩家也不会再因为客户端 UUID 不同而直接收到 `无效会话`。

## 皮肤处理

正版验证成功后，NEOauth 会保留 Mojang 返回的签名 `textures` 属性，并在登录后刷新玩家列表资料。

皮肤缓存保存在 SQLite 中。Mojang 暂时不可用时，已缓存的正版皮肤仍可用于已知正版身份。

可选的离线同名皮肤功能由以下配置控制：

```toml
offlineSkinByName = true
```

启用后，离线密码登录的玩家可以按用户名尝试获取可信皮肤，但不会因此获得正版身份，也不会改变离线 UUID。

## 安装

1. 下载 `neoauth-1.3.37.jar`。
2. 将文件放入 NeoForge 1.21.1 服务端的 `mods` 文件夹。
3. 确保服务器使用：

   ```properties
   online-mode=false
   ```

4. 启动服务器。
5. 首次启动后编辑：

   ```text
   config/Neoauth/Neoauth.toml
   ```

NEOauth 只生成一个统一 TOML 配置文件，不会再拆分生成多个配置文件。

## 默认配置重点

```toml
[config]
language = "zh"
loginTimeout = 300
sessionGracePeriod = 600
sessionBindToIp = false
premiumAutoLogin = true
premiumAutoRegister = true
premiumLoginFallbackOnFailure = true
offlineSkinByName = true
totpEnabled = false
maxAccountsPerIP = 0
```

### 重要配置说明

- `loginTimeout`：未认证玩家被踢出的时间，默认 300 秒。
- `sessionGracePeriod`：断线后可恢复会话的时间，默认 600 秒。
- `sessionBindToIp`：默认 `false`，不会因为 IP 变化导致会话失效。
- `premiumAutoLogin`：启用正版自动识别和自动登录。
- `premiumAutoRegister`：正版玩家首次验证成功时自动创建账号。
- `premiumLoginFallbackOnFailure`：Mojang 验证失败时允许已知正版身份回退。
- `offlineSkinByName`：离线密码账号是否尝试加载同名皮肤。
- `totpEnabled`：TOTP 默认关闭，需要时手动改为 `true`。
- `maxAccountsPerIP`：默认 `0`，表示不限制同一 IP 的注册数量；设置为正整数后启用限制。

修改配置后执行：

```text
/directauth reload
```

部分涉及登录协议或模组加载的改动需要重启服务器。

## 数据位置与迁移

- 配置：`config/Neoauth/Neoauth.toml`
- 数据库：`world/serverconfig/directauth.db`
- 旧版 JSON 配置和语言文件会在迁移成功后处理
- 默认支持常见的 `playerdata`、`stats`、`advancements`、`deaths`、`ftbquests` 和 `skinrestorer`

修改迁移规则前请备份世界。对大型整合包，建议先在副本服务器验证 UUID 和模组数据迁移结果。

## 故障排查

### 玩家不知道如何登录

确认玩家能看到聊天提示或顶部 BossBar。使用：

```text
/login <密码>
```

已有账号，或：

```text
/register <密码>
```

新玩家。

### 看到“无效会话”

确认：

1. 服务端是 `online-mode=false`；
2. 玩家使用的是最新 NEOauth JAR；
3. 没有其他认证模组在前置代理或后端提前拦截登录；
4. 后端日志中是否出现 `NEOauth` 登录记录；
5. 玩家是正版客户端还是离线客户端，以及是否使用了正版玩家名称。

NEOauth 的目标行为是：正版客户端走正版自动登录，离线客户端即使使用正版名称也走离线 UUID 与密码登录。

### 皮肤没有显示

正版皮肤需要客户端缓存刷新和签名 `textures` 属性。请确认后端日志是否出现：

```text
Premium profile ... contains signed textures
Refreshed signed skin profile ...
```

若服务器到 Mojang 或皮肤站网络不可用，NEOauth 会使用 SQLite 中已有的签名皮肤缓存；首次获取皮肤仍需要可信皮肤源可访问。

## 构建

要求：Java 21、网络可访问 Gradle/Minecraft 依赖仓库。

Windows：

```powershell
./gradlew.bat clean build
```

Linux/macOS：

```bash
./gradlew clean build
```

构建产物：

```text
build/libs/neoauth-1.3.37.jar
```

## 兼容性

- Minecraft：1.21.1
- NeoForge：21.1.x
- 服务端：Dedicated Server
- 客户端：原版客户端即可，不需要安装 NEOauth
- 不建议与多个同时拦截 LOGIN 阶段的认证模组叠加使用

## English summary

NEOauth is a server-side authentication mod for NeoForge 1.21.1. It supports offline password login, automatic premium detection, signed skin caching, Mojang outage fallback, premium-name cracked login fallback, asynchronous SQLite storage, Chinese/English/Spanish messages, configurable migration, and optional TOTP.

Players do not need to install the mod. The default login timeout is 300 seconds, and unauthenticated players periodically receive clear `/login <password>` or `/register <password>` instructions.

## License

MIT License.
