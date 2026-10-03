![DirectAuth Logo](src/main/resources/logo.png)

# NEOauth

NEOauth is a **server-side** authentication mod for Minecraft NeoForge 1.21.1. It provides a secure login system for offline-mode servers, with optional auto-login for players who own a legitimate Minecraft account.

It is strictly server-side: clients do **not** need to install this mod to join. Players can connect with any vanilla client.

All database operations run asynchronously, so the main server thread never freezes during logins.

## Key Features

* **Server-Side Only**: Install it on your server and players join with any vanilla client. Nothing to install client-side.
* **Zero-Configuration Database**: Uses an embedded SQLite database. No MySQL or external database server required.
* **No Lag**: All database I/O is performed asynchronously on a separate thread pool.
* **Strong Security**: Passwords use Argon2id with unique salts. Existing PBKDF2 accounts remain compatible and are upgraded to Argon2id after a successful login.
* **Online Auto-Login**: Registered accounts are checked against Mojang during login and, when verified, skip `/online` and `/login` on future sessions.
* **Automatic Premium Login**: Registered accounts are checked against Mojang during login. A successful premium session logs the player in without `/login` or `/online` and migrates existing offline-UUID data once.
* **Known Premium Fallback**: If Mojang verification fails or times out for a previously verified account, NEOauth automatically accepts the stored premium UUID and cached signed skin properties without asking for `/login`. Unknown/offline accounts still require a password when premium verification fails.
* **Session Grace Period**: If a player disconnects and reconnects within the configured grace period, they stay authenticated without logging in again. The default is 30 minutes, and the grace session is not restricted to the previous IP address. NEOauth stores a short-lived session marker, never a plaintext password.
* **Anti-Bot Protection**: Configurable registration delay and a maximum number of accounts per IP address.
* **Strict Restrictions**: Unauthenticated players cannot move, chat, interact with blocks/entities, drop or pick up items, attack, gain XP, or regenerate health.
* **Smart Data Migration**: When a player switches from offline to online mode their UUID changes, so the mod automatically migrates their data to the new UUID (see below).
* **Localization**: Ships with English (`en`), Simplified Chinese (`zh`) and Spanish (`es`); fully customizable message strings.
* **Optional TOTP 2FA**: Disabled by default; enable `totpEnabled` in the unified TOML before using `/totp setup`. Setup displays one-time recovery codes; each code is invalidated individually when used.

## Commands

### Player commands

| Command | Usage | Description |
| :--- | :--- | :--- |
| **/register** | `/register <password>` | Creates a local password account. Not required for a first login verified as a legitimate Mojang account when `premiumAutoRegister=true`. |
| **/login** | `/login <password>` | Authenticates your session. |
| **/logout** | `/logout` | Logs you out (disconnects you from the server). |
| **/changepassword** | `/changepassword <oldPassword> <newPassword>` | Changes your password. Requires being logged in. |
| **/setpassword** | `/setpassword <password>` | Sets the fallback password for a first-time automatically created premium account. |
| **/unregister** | `/unregister <password>` | Permanently deletes your own account (confirms with your password). |
| **/online** | `/online <password>` | Legacy manual verification command. **Not required** when `premiumAutoLogin=true`; NEOauth verifies legitimate accounts automatically during LOGIN. |
| **/directauth confirm** | `/directauth confirm` | Confirms a pending action when the mod requests it. |

### Admin commands (require OP level 4)

| Command | Usage | Description |
| :--- | :--- | :--- |
| **/directauth online** | `/directauth online <user> <true\|false>` | Manually toggles a player's online-mode status. |
| **/directauth resetpass** | `/directauth resetpass <user> <newPassword>` | Resets a player's password. |
| **/directauth unregister** | `/directauth unregister <user>` | Force-deletes a player's account (and kicks them if online). |
| **/directauth reload** | `/directauth reload` | Reloads the config and language files from disk at runtime — no server restart needed. |
| **/directauth resetlang** | `/directauth resetlang` | Deletes the language files so they regenerate from the mod's built-in defaults (discards manual edits). |

## ⚠️ Important: Online Mode Migration

When `premiumAutoLogin=true` (the default), NEOauth verifies a registered player's Mojang session during the LOGIN protocol. On success, the player's UUID changes from the offline UUID to the real Mojang UUID and NEOauth migrates their data automatically. The player does not need to run `/online`.

**By default** the mod already migrates vanilla data (inventory, ender chest, advancements, statistics) **and** a few common mods (graves/deaths, FTB Quests, SkinRestorer).

If your server uses **other** mods that store per-player data (e.g. Curios, Astral Sorcery, FTB Teams), the server administrator must add those folder names to the migration config before automatic premium detection runs. Otherwise that mod-specific progress may be lost.

> **Always back up your world before migrating on a heavily modded server.**

## Installation

1. Download the `.jar` file.
2. Place it in the `mods` folder of your NeoForge 1.21.1 server.
3. Restart the server.

On first launch the following files are generated:

* Config: `world/serverconfig/NEOauth-config.json`
* Unified config: `config/Neoauth/Neoauth.toml` (contains all settings and English/Chinese/Spanish messages)
* Database: `world/serverconfig/directauth.db`

## Configuration

Edit `world/serverconfig/NEOauth-config.json` to customize:

* **Language**: `language` (`"en"`, `"zh"` or `"es"`). The default is `"zh"` (Simplified Chinese), stored in `config/Neoauth/Neoauth.toml`.
* **Security**: `minPasswordLength`, `maxPasswordLength`, `maxLoginAttempts`, `loginCooldownMs`, `loginTimeout` (seconds before a non-authenticated player is kicked), and `premiumLoginFallbackOnFailure` (allow a known premium account to use its existing password if Mojang verification fails; defaults to `true`). The fallback retains that account's database `onlineUUID`.

  ```json
  {
    "premiumLoginFallbackOnFailure": true
  }
  ```

  Set it to `false` to reject known premium accounts whenever Mojang session verification fails. With it enabled, a failed premium handshake does **not** switch to an offline UUID: the database UUID remains the player's UUID and `/login <password>` is still required before playing. `premiumAutoLogin` controls automatic Mojang checks and defaults to `true`. `premiumAutoRegister` defaults to `true` and creates a premium account automatically on the first verified login. The player can then run `/setpassword <password>` once to configure password fallback. `premiumVerificationTimeoutSeconds` defaults to `15`; after that time, a known premium account uses password fallback even if Mojang does not return a classified error.
* **Sessions**: `sessionGracePeriod` (seconds a session survives after disconnect; default `600` = 10 minutes) and `sessionCleanupInterval` (minutes between cleanups of expired sessions).
* **Anti-Bot**: `registrationDelay` (seconds to wait before a fresh player can register) and `maxAccountsPerIP`. `maxAccountsPerIP` defaults to `0`, so registration is not limited by IP unless you set a positive value. `totpEnabled` defaults to `false`; when enabled, players can use `/totp setup`, `/totp verify`, and `/totp disable`.
* **Data Migration**: `migrationMap` — a map of *folder name → migration mode*. To support an extra mod, add its data folder there. Available modes:
    * `RENAME` — rename a single file that is named after the UUID (most vanilla data, SkinRestorer).
    * `DIRECTORY` — move/rename a whole folder named after the UUID (e.g. graves).
    * `TEXT_REPLACE` — rename the file **and** replace UUID strings inside its contents (e.g. FTB Quests).

  Example:

  ```json
  "migrationMap": {
    "playerdata": "RENAME",
    "stats": "RENAME",
    "advancements": "RENAME",
    "deaths": "DIRECTORY",
    "ftbquests": "TEXT_REPLACE",
    "skinrestorer": "RENAME"
  }
  ```

Message strings can be edited in the `NEOauth-lang-*.json` files.

## Troubleshooting (FAQ)

**Q: I keep getting teleported back when I move / I can't eat or regenerate health.**
A: You aren't authenticated yet. Use `/register <password>` (first time) or `/login <password>`.

**Q: I lost my items from [some mod] after automatic premium detection.**
A: The server administrator likely hadn't added that mod's data folder to `migrationMap` before automatic migration. Contact your admin and restore the backup created by DirectAuth if necessary.

## Technical Details

* **Hashing**: Argon2id with unique salts; legacy PBKDF2 hashes are accepted once and transparently upgraded after login.
* **Storage**: SQLite at `world/serverconfig/directauth.db` — no external database server. A legacy `NEOauth_users.json` is migrated automatically on first run if present.
* **Session Management**: Sessions are validated against the internal database (and Mojang's session servers for online users).
* **Protection**: The login listener is injected at high priority to prevent unauthorized packet processing.

## Compatibility

* **Loader**: NeoForge 1.21.1
* **Side**: Server-side only — works with any vanilla client and can be included in any modpack.
* **Supported**: Dedicated servers and local LAN worlds.
* **Not supported**: BungeeCord/Velocity networks that need player data synchronized across multiple server instances (e.g. Lobby → Survival transfers), since data is stored in the local world directory.

## License

This project is licensed under the MIT License.
