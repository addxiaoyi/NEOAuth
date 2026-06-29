# AGENTS.md — DirectAuth

Single source of truth for any AI coding agent working in this repo (Claude Code, Gemini, Codex, Cursor, Aider, etc.). Keep this file up to date when architecture changes; do not maintain parallel `CLAUDE.md` / `GEMINI.md` copies.

---

## 1. Project Overview

**DirectAuth** is a server-side Minecraft authentication mod for **NeoForge 1.21.1** (Java 21). It lets a server run in offline mode safely by forcing players to `/register` and `/login` before they can play, with optional opt-in conversion to premium (online-mode) accounts and automatic player-data migration when that conversion happens.

- **Mod ID:** `directauth`
- **Main class:** `com.marcp.directauth.DirectAuth`
- **License:** MIT
- **Side:** server-required, client-required (declared in `neoforge.mods.toml`), but logic is server-side.

The `Documentation/` directory in the repo root is **not part of this mod** — it is the upstream NeoForged docs (Docusaurus v3) cloned for reference and is git-ignored. Do not treat it as production code or modify it as part of mod work.

---

## 2. Tech Stack & Versions

Authoritative values live in `gradle.properties`; the table below is a quick reference.

| Item | Value |
|---|---|
| Minecraft | `1.21.1` (range `[1.21.1, 1.22)`) |
| NeoForge | `21.1.64` (range `[21.1.64,)`) |
| Java | 21 (toolchain pinned) |
| Loader | `javafml`, range `[4,)` |
| Parchment mappings | `2024.07.28` for MC `1.21` |
| Gradle plugin | `net.neoforged.moddev` `2.0.121` |
| Persistence | SQLite via `org.xerial:sqlite-jdbc:3.46.0.0` (jar-in-jar) |
| Gradle wrapper | 9.2.0 |

Mixins are enabled (`directauth.mixins.json`). No access transformers, no coremods.

---

## 3. Repo Layout

```
DirectAuth/
├── build.gradle              # moddev plugin, runs, jarJar(sqlite-jdbc)
├── gradle.properties         # all versions & mod metadata
├── settings.gradle           # plugin management
├── src/
│   ├── main/
│   │   ├── java/com/marcp/directauth/
│   │   │   ├── DirectAuth.java       # @Mod entry — registers handlers, exposes singletons
│   │   │   ├── auth/                 # LoginManager, MojangAPI, ConfirmationManager
│   │   │   ├── commands/             # Brigadier command classes
│   │   │   ├── config/               # ModConfig, LangConfig (Gson JSON)
│   │   │   ├── data/                 # DatabaseManager (SQLite), PositionManager, MigrationManager, UserData, StoredLocation
│   │   │   ├── events/               # ConnectionHandler, PlayerRestrictionHandler
│   │   │   └── mixin/                # MixinServerLoginPacketListenerImpl, PlayerListAccessor
│   │   ├── resources/
│   │   │   ├── META-INF/             # mods.toml is generated from templates/
│   │   │   ├── directauth.mixins.json
│   │   │   └── assets/directauth/    # (lang/ here is placeholder — real i18n is JSON in worldserverconfig)
│   │   └── templates/META-INF/neoforge.mods.toml  # source for mods.toml expansion
│   └── generated/resources/  # output of `runData`
├── Documentation/            # GIT-IGNORED — upstream NeoForged docs, not part of this mod
├── run/  runs/  run-data/    # gitignored dev runtime dirs
├── build/  .gradle/  repo/   # gitignored gradle output
├── .github/workflows/build.yml  # CI: gradlew build on push/PR (Ubuntu, Temurin 21)
├── AGENTS.md                 # ← you are here
└── README.md
```

---

## 4. Source Architecture

### 4.1 Entrypoint — `DirectAuth.java`
- `@Mod("directauth")` constructor receives the mod event bus.
- Registers `ConnectionHandler` and `PlayerRestrictionHandler` on `NeoForge.EVENT_BUS`, and registers commands via `RegisterCommandsEvent`.
- Holds static singletons exposed via getters: `LoginManager`, `DatabaseManager`, `PositionManager`, `ModConfig`. **All cross-package access goes through these getters** — there is no DI container.
- `initDatabase(Path)` and `initConfig(Path)` are called by `ConnectionHandler#onServerStarted`, *not* in the constructor (they need the world path).

### 4.2 `auth/`
- **`LoginManager`** — central authentication state. Thread-safe (`ConcurrentHashMap`). Holds `authenticatedPlayers`, `failedAttempts`, `loginAttempts` (cooldown), `connectionTimes` (login timeout), `preLoginCache` (mixin-populated), `graceSessions` (IP-locked re-login). Runs a `ScheduledExecutorService` for grace-session cleanup at `sessionCleanupInterval` minutes.
- **Password hashing is PBKDF2WithHmacSHA256**, 100k iterations, 256-bit key, Base64 `salt:hash` encoding (see `LoginManager.ALGORITHM`).
- **`MojangAPI`** — async UUID lookup against `api.mojang.com/users/profiles/minecraft/{name}`, returns `CompletableFuture<String>`. Callbacks must be marshalled back to the main thread via `server.execute(...)`.
- **`ConfirmationManager`** — pending-action store (30s TTL) used by `/changepassword` and `/unregister`, drained by `/directauth confirm`.

### 4.3 `commands/`
All Brigadier-based. Registered through `RegisterCommandsEvent` in `DirectAuth`.

| Command | Args | Auth required? | Notes |
|---|---|---|---|
| `/register <password>` | 1 | no | Enforces `registrationDelay`, `maxAccountsPerIP`, password length bounds. Auto-authenticates on success. |
| `/login <password>` | 1 | no | Enforces `loginCooldownMs`, `maxLoginAttempts`. Restores position on success. |
| `/online [password]` | 0–1 | yes | Switches account to premium; verifies via `MojangAPI`; triggers `MigrationManager`. |
| `/changepassword <old> <new>` | 2 | yes | Requires `/directauth confirm`. Disables premium on success. |
| `/unregister <password>` | 1 | yes | Requires `/directauth confirm`. Kicks player after deletion. |
| `/logout` | 0 | yes | Invalidates grace session and kicks. |
| `/directauth confirm` | 0 | varies | Drains `ConfirmationManager`. |
| `/directauth online <user> <bool>` | 2 | **op** | Admin: toggle premium flag. |
| `/directauth resetpass <user> <newpw>` | 2 | **op** | Admin: force password reset. |
| `/directauth unregister <user>` | 1 | **op** | Admin: force-delete account. |

### 4.4 `events/`
- **`ConnectionHandler`** — `ServerStartedEvent` (init DB/config/scheduler), `ServerStoppingEvent` (close), `PlayerLoggedInEvent` (consume mixin pre-cache, attempt grace-session restore, otherwise teleport to spawn and save original position), `PlayerLoggedOutEvent` (pause session if authenticated, else drop).
- **`PlayerRestrictionHandler`** — enforces the unauthenticated-player jail. Anchors players with a `Map<UUID, Vec3>`, applies slowness/jump/blindness via `EntityTickEvent`, cancels block break, left/right click, item drop/pickup, mount, attack, chat (except `/register` `/login` `/online`), damage (set to 0), and healing. Sends the auth reminder every ~5 seconds (100 ticks). Kicks if `loginTimeout` elapsed.

### 4.5 `mixin/`
- **`MixinServerLoginPacketListenerImpl`** — async pre-load of `UserData` during the login handshake so `PlayerLoggedInEvent` doesn't block on SQLite.
- **`PlayerListAccessor`** — invoker for the protected `PlayerList.save()` used by `MigrationManager` to flush dirty player files before renaming on premium conversion.

### 4.6 `data/`
- **`DatabaseManager`** — single-threaded `ExecutorService` wrapping SQLite. Table:
  ```sql
  users(username TEXT PRIMARY KEY, passwordHash TEXT NOT NULL,
        isPremium INTEGER DEFAULT 0, onlineUUID TEXT, registrationIp TEXT)
  ```
  All CRUD is `*Async` returning `CompletableFuture`. On startup, if `DirectAuth_users.json` exists, it is imported and renamed to `DirectAuth_users.json.MIGRATED`.
- **`UserData`** — POJO mirror of the table row. `username` is always lowercased.
- **`PositionManager`** — JSON store of `UUID → StoredLocation` (dimension, x/y/z, yaw/pitch, health, food, saturation, fire ticks). Populated on join, drained on successful login.
- **`MigrationManager` / `MigrationMode`** — runs when a player flips to premium and the offline-UUID data must be moved to the online-UUID file/folder. Strategies:
  - `RENAME` — rename a single file (vanilla `playerdata`, `stats`, `advancements`, `skinrestorer`).
  - `DIRECTORY` — rename a UUID-named directory (death-tracker mods).
  - `TEXT_REPLACE` — rename and bulk-replace UUID strings (both dashed and undashed) inside the file (FTB Quests).
  Backs up any destination that already exists. Mapping is in `ModConfig.migrationMap`.

---

## 5. Configuration & Localization

### 5.1 `ModConfig` (`world/serverconfig/DirectAuth-config.json`)
Gson, pretty-printed. Loaded once on `ServerStartedEvent`; missing fields are filled with defaults and the file rewritten.

| Group | Fields (defaults) |
|---|---|
| General | `language` (`"en"`) |
| Session | `sessionGracePeriod` (600s), `sessionCleanupInterval` (10 min) |
| Password | `minPasswordLength` (4), `maxPasswordLength` (32) |
| Login flood | `maxLoginAttempts` (5), `loginCooldownMs` (3000), `loginTimeout` (60s) |
| Anti-bot | `registrationDelay` (1s), `maxAccountsPerIP` (5) |
| Migration | `migrationMap` — directory → `MigrationMode` |

### 5.2 `LangConfig` (`world/serverconfig/DirectAuth-lang-<code>.json`)
Not in `assets/<modid>/lang/`. Messages are flat string fields on `LangConfig` (≈70 keys: `msgWelcome`, `errWrongPassword`, `msgAuthReminder`, `msgPremiumSuccess`, etc.). Resolved via `DirectAuth.getConfig().getLang().<field>`. Placeholders use `String.format` (`%d`, `%s`). Color codes use Minecraft `§` sequences.

Both `en` and `es` files are written on startup; the active one is selected by `ModConfig.language`. To add a language, add `LangConfig.load(dir.resolve("DirectAuth-lang-<code>.json"), "<code>")` in `ModConfig#load` and supply translations in `LangConfig` static blocks.

### 5.3 Runtime files (all under `<world>/serverconfig/`)

| File | Purpose |
|---|---|
| `DirectAuth-config.json` | `ModConfig` |
| `DirectAuth-lang-en.json`, `DirectAuth-lang-es.json` | `LangConfig` per language |
| `directauth.db` | SQLite user store |
| `DirectAuth_positions.json` | `PositionManager` checkpoints |
| `DirectAuth_users.json.MIGRATED` | Legacy JSON backup (created once) |

---

## 6. Build & Dev Workflow

All commands from repo root. Java 21 must be on `PATH` or via a Gradle toolchain provider.

```powershell
.\gradlew build                 # compile + jar (output in build/libs/directauth.jar)
.\gradlew runClient             # dev client (run/ dir, DEBUG logs, REGISTRIES marker)
.\gradlew runServer             # dev server
.\gradlew runData               # data generation → src/generated/resources/
.\gradlew runGameTestServer     # gametest harness
.\gradlew publish               # publish to local repo/ maven
.\gradlew --refresh-dependencies
```

- Mod metadata is **generated**: edit `src/main/templates/META-INF/neoforge.mods.toml`, not `src/main/resources/META-INF/neoforge.mods.toml`. The `generateModMetadata` task expands `${mod_id}`, `${mod_version}`, `${neo_version}`, etc. from `gradle.properties` and runs on IDE sync.
- No tests, no Checkstyle/Spotless, no pre-commit hooks. The only CI is `.github/workflows/build.yml` running `./gradlew build` on Ubuntu.
- Working dir for runs is `run/`. Logs and the dev world live there.

---

## 7. Conventions & Gotchas

### Code style
- Standard Java conventions: `PascalCase` classes, `camelCase` methods/fields, `UPPER_SNAKE_CASE` constants.
- Comments and existing string literals mix English and Spanish (the author's native language). User-visible strings belong in `LangConfig`, never inline.
- UTF-8 enforced at compile time (`options.encoding = 'UTF-8'`).

### Threading
- Anything touching `DatabaseManager` is async — always chain via `.thenAcceptAsync(..., server)` or `server.execute(...)` to land mutations back on the server thread.
- `MojangAPI` callbacks must also be marshalled back to the server thread before touching player state.
- Player-state maps in `LoginManager` and `PlayerRestrictionHandler` are `ConcurrentHashMap` — preserve that when adding new ones.

### Singletons
- Don't `new` managers. Use `DirectAuth.getLoginManager()`, `getDatabaseManager()`, `getPositionManager()`, `getConfig()`.
- Initialization order matters: config and DB are wired in `ServerStartedEvent`, so don't touch them in the mod constructor.

### Anti-bot expectations
When changing registration / login flow, preserve all of: `registrationDelay`, `maxAccountsPerIP` (queried via `DatabaseManager#countAccountsByIP`), grace-session IP binding, login cooldown, max-attempts kick, anchored-position enforcement, and the `loginTimeout` kick. These are the security surface — regressions here are silent and dangerous.

### Premium migration
`/online` triggers `MigrationManager`. Adding support for a new mod's player-data folder = adding an entry to the default `migrationMap` in `ModConfig` plus, if the mod stores UUID strings inside files (like FTB Quests), use `TEXT_REPLACE`. Always backs up existing destinations — don't shortcut this.

### Things that look like bugs but aren't
- The `assets/examplemod/lang/en_us.json` placeholder file is leftover from the NeoForge mod template; real strings live in `LangConfig`.
- `Documentation/` is git-ignored and is upstream NeoForged docs, not project documentation. Don't `git add` it.

---

## 8. When You Change Things

- **Adding a command** → new class in `commands/`, register it from `DirectAuth#registerCommands` (called by `RegisterCommandsEvent`).
- **Adding a config field** → add field + default in `ModConfig`, the loader rewrites the JSON automatically on next start.
- **Adding a message** → add field in `LangConfig` with both English and Spanish defaults; reference via `getConfig().getLang().<field>`.
- **Adding a persistent column** → migrate the SQLite schema in `DatabaseManager#init` (ALTER TABLE with a try/catch on `SQLITE_ERROR` is the existing pattern); add the field to `UserData`.
- **Adding a restriction** → add a `@SubscribeEvent` method in `PlayerRestrictionHandler` and gate it on `LoginManager#isAuthenticated`.

Keep this file in sync with what's actually in the code. If you make an architectural change that contradicts a section here, update the section in the same PR.
