# NotificationMirror — Project Knowledge

## Overview

NotificationMirror is an Android app that mirrors phone notifications to a Wear OS watch in real-time. It consists of two modules:
- **`mobile/`** — Phone app (sender). Captures notifications via `NotificationListenerService`, encrypts them, and sends to the watch via Google Play Services Wearable API.
- **`wear/`** — Watch app (receiver). Receives encrypted notifications, decrypts, and displays them as native watch notifications with reply, mute, snooze, open-on-watch, and open-on-phone actions.

Both modules share the same `applicationId`: `com.notifmirror.mobile` (including the wear module — this is intentional for Wearable API pairing).

## Build & Development

### Prerequisites
- Java 17 (required by AGP 8.2.2)
- Android SDK with compileSdk 34
- No CI pipeline configured — builds are local only

### Build Commands
```bash
# Debug build (both modules)
./gradlew assembleDebug

# Release build (requires signing env vars)
KEYSTORE_PASSWORD=<password> KEY_ALIAS=<alias> KEY_PASSWORD=<password> ./gradlew assembleRelease

# Build specific module
./gradlew :mobile:assembleDebug
./gradlew :wear:assembleDebug
```

### Signing
- Keystore file: `release-key.jks` (in project root)
- Signing config reads from environment variables: `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`
- Both modules use the same keystore and signing config
- The keystore password, key alias, and key password are stored as Devin secrets

### APK Output Locations
- Phone: `mobile/build/outputs/apk/release/mobile-release.apk`
- Watch: `wear/build/outputs/apk/release/wear-release.apk`

### APK Naming Convention
When copying/renaming APKs for the user, **always** use this format:
- `NotificationMirror-Phone-vX.x.x.apk` (e.g., `NotificationMirror-Phone-v1.7.0.apk`)
- `NotificationMirror-Watch-vX.x.x.apk` (e.g., `NotificationMirror-Watch-v1.7.0.apk`)

The version number must match the `versionName` in the build.gradle files. Never use suffixes like `-fix18`, `-v2`, etc.

### Version Management
- Version is defined in BOTH `mobile/build.gradle` and `wear/build.gradle`
- Both files must be updated together: `versionCode` (integer) and `versionName` (string)
- Both modules must always have matching version numbers
- `BUILD_TIMESTAMP` is auto-generated at build time via `buildConfigField`

### Lint / Type Checking
- No dedicated lint or typecheck commands configured
- The Kotlin compiler catches type errors during `assembleDebug`
- Run `./gradlew assembleDebug` to verify compilation

## Architecture & Data Flow

### Phone → Watch Notification Flow
1. **`NotificationListener.onNotificationPosted()`** — Android system calls this when any app posts a notification
2. **Filtering** — Checks: master toggle, DND mode, screen-off mode, app whitelist, keyword filters (global + per-app), group summary skip, content hash dedup
3. **Data extraction** — Title, text, bigText, subText, conversationTitle, conversation messages (MessagingStyle), app icon (base64), picture (base64), actions (with RemoteInput detection), contentIntent, notification tag, shortcutId
4. **JSON serialization** — All data packed into a JSONObject
5. **Payload safety** — If payload > 80KB, strips picture first; if still > 80KB, skips entirely
6. **Encryption** — AES-256-GCM via `CryptoHelper.encrypt()`
7. **Node discovery** — `Wearable.getNodeClient().connectedNodes`; if no nodes, queues in `OfflineQueue`
8. **Send** — `Wearable.getMessageClient().sendMessage(nodeId, "/notification", encryptedBytes)`
9. **Watch receives** — `NotificationReceiverService` (WearableListenerService) or `PersistentListenerService` (foreground service)
10. **`MessageHelper.handleMessage()`** — Routes by path to appropriate handler
11. **`NotificationHandler.handleNotification()`** — Decrypts, parses JSON, builds native Android notification with actions

### Watch → Phone Communication (Reverse Direction)
- **Reply**: Watch sends `/reply` message with notifKey + reply text → Phone's `ReplyReceiverService` fires the stored `Notification.Action` with RemoteInput
- **Action trigger**: Watch sends `/action` message → Phone fires the stored action
- **Open on Phone**: Watch sends `/open_app` message with notifKey → Phone fires stored `contentIntent` PendingIntent
- **Dismiss sync**: Phone sends `/notification_dismiss` when notification is dismissed → Watch cancels matching notification
- **Mirroring toggle**: Watch sends `/mirroring_toggle` → Phone toggles mirroring state
- **Request sync**: Watch sends `/request_sync` → Phone re-sends all active notifications
- **Request key**: Watch sends `/request_key` → Phone re-syncs encryption key

### Message Paths (Wearable API)
| Path | Direction | Purpose |
|------|-----------|--------|
| `/notification` | Phone→Watch | Encrypted notification payload |
| `/notification_dismiss` | Phone→Watch | Dismiss notification by key |
| `/notification_reconcile` | Phone→Watch | Full sync of active notification keys |
| `/action_result` | Phone→Watch | Result of action execution |
| `/request_logcat` | Phone→Watch | Trigger logcat export on watch |
| `/crypto_key` | Phone→Watch | AES-256 encryption key (via DataClient) |
| `/mirroring_state` | Phone→Watch | Mirroring enabled/disabled (via DataClient) |
| `/reply` | Watch→Phone | Reply text for a notification |
| `/action` | Watch→Phone | Trigger a notification action |
| `/open_app` | Watch→Phone | Open specific app/conversation on phone |
| `/open_settings` | Watch→Phone | Open phone settings |
| `/open_url` | Watch→Phone | Open URL on phone |
| `/mirroring_toggle` | Watch→Phone | Toggle mirroring state |
| `/request_sync` | Watch→Phone | Request full notification re-sync |
| `/request_key` | Watch→Phone | Request encryption key re-sync |
| `/resend_ongoing` | Watch→Phone | Request resend of ongoing notifications |
| `/snooze` | Watch→Phone | Snooze a notification |

### Encryption
- **Algorithm**: AES-256-GCM
- **Key generation**: `CryptoHelper.getOrCreateKey()` on phone, synced to watch via `DataClient` at path `/crypto_key`
- **Key storage**: Android `SharedPreferences` (base64-encoded) on both sides
- **Both modules have their own `CryptoHelper.kt`** — nearly identical but with slight differences (phone generates keys, watch only receives)

### Offline Queue
- `OfflineQueue.kt` (phone) — Stores notifications as JSON in `SharedPreferences` when watch is disconnected
- On reconnect, queued notifications are sent in order
- Phone also sends a reconciliation message (`/notification_reconcile`) with all active notification keys so watch can clean up stale ones

## Key Files

### Phone Module (`mobile/`)
| File | Purpose |
|------|--------|
| `NotificationListener.kt` | Core service — captures, filters, encrypts, sends notifications (~960 lines) |
| `ReplyReceiverService.kt` | WearableListenerService — handles replies, actions, open requests from watch |
| `SettingsManager.kt` | All settings with global defaults + per-app overrides (~600 lines) |
| `MainActivity.kt` | Phone UI — settings dashboard, connection status, version info |
| `AppSettingsActivity.kt` | Global settings UI (all toggle/slider settings) |
| `PerAppSettingsActivity.kt` | Per-app override settings UI |
| `FilterSettingsActivity.kt` | Keyword whitelist/blacklist regex filters |
| `AppPickerActivity.kt` | App whitelist picker (shows installed apps) |
| `LogActivity.kt` | Notification history viewer with search/filter |
| `CryptoHelper.kt` | AES-256-GCM encryption, key generation and sync |
| `OfflineQueue.kt` | Queue for notifications when watch disconnected |
| `WearSyncHelper.kt` | Utility for syncing state to watch via DataClient |
| `NotificationLog.kt` | SQLite-backed notification history |
| `UpdateChecker.kt` | GitHub releases auto-update checker |
| `VibrationPatternHelper.kt` | Vibration pattern parsing/validation |
| `SettingsUIHelper.kt` | Shared UI helper for settings activities |
| `AppListCache.kt` | Cached list of installed apps |

### Watch Module (`wear/`)
| File | Purpose |
|------|--------|
| `NotificationHandler.kt` | Core — builds watch notifications from JSON, handles conversation stacking (~975 lines) |
| `MessageHelper.kt` | Routes incoming messages by path to handlers |
| `PersistentListenerService.kt` | Foreground service listening for messages/data changes |
| `NotificationReceiverService.kt` | WearableListenerService for message/data events |
| `ReplyBroadcastReceiver.kt` | Handles inline reply from watch notification |
| `ReplyActivity.kt` | Full-screen reply UI (fallback) |
| `ActionBroadcastReceiver.kt` | Handles custom action button presses |
| `OpenOnPhoneBroadcastReceiver.kt` | Handles "Open on Phone" button |
| `MuteBroadcastReceiver.kt` | Handles per-app mute from notification |
| `MuteManager.kt` | Manages temporary app mutes with expiry |
| `SnoozeBroadcastReceiver.kt` | Handles notification snooze |
| `OngoingDismissReceiver.kt` | Handles dismissal of ongoing notifications |
| `MainActivity.kt` | Watch UI — mirroring toggle, connection status, log access |
| `LogActivity.kt` | Watch-side notification history viewer |
| `CryptoHelper.kt` | AES-256-GCM decryption, key storage |
| `NotificationLog.kt` | Watch-side notification history |
| `NotificationTileService.kt` | Wear OS tile showing notification counts |
| `NotificationComplicationService.kt` | Watch face complication showing latest notification |
| `TileActionActivity.kt` | Activity launched from tile actions |
| `BootReceiver.kt` | Restarts services after watch reboot |
| `PendingNotificationQueue.kt` | Queue for notifications received before key is available |

## Settings System

### Global Settings (SettingsManager)
All settings have global defaults. Key settings:
- **Mirroring toggle** — master on/off
- **DND mode** — Off (0) / Block (1) / Silent (2)
- **Screen mode** — Always (0) / Screen-off only (1) / Silent when on (2) / Vibrate-only when on (3)
- **Alert mode** — Sound (0) / Vibrate only (1) / Mute (2)
- **Ongoing mode** — None (0) / Ongoing only (1) / All persistent (2)
- **Priority** — High (1) / Default (0) / Low (-1)
- **App whitelist** — Set of package names (empty = all apps)
- **Keyword filters** — Whitelist and blacklist regex patterns (global + per-app)
- **Mute/Snooze duration** — Minutes (integer)
- **Battery saver** — Enabled + threshold percentage
- **Big text threshold** — Character count for BigTextStyle
- **Vibration pattern** — Comma-separated milliseconds (e.g., "0,200,100,200")
- **Complication source** — "most_recent" or specific app

### Per-App Overrides
Every global setting can be overridden per-app. Storage pattern:
- `per_app_{setting}_{packageName}` — the value
- `per_app_{setting}_enabled_{packageName}` — boolean flag (true = use per-app, false = use global)
- Access via `getEffective*()` methods which check per-app first, then fall back to global

### Settings SharedPreferences
- Phone: `notif_mirror_prefs` (via SettingsManager)
- Watch: `notif_mirror_settings` (direct SharedPreferences)
- App labels cache: `app_labels` (on watch, caches phone-provided labels)

## Notification JSON Schema
The JSON payload sent from phone to watch includes:
```
key, package, appLabel, title, text, subText, postTime,
actions[], icon (base64), picture (base64),
conversationMessages[] (sender, text), isMessagingStyle,
conversationTitle, isOngoing, notifTag, shortcutId,
muteDuration, notifPriority, bigTextThreshold, autoCancel,
autoDismissSync, showOpenButton, hasContentIntent,
showMuteButton, showSnoozeButton, snoozeDuration,
defaultVibration, vibrationPattern, keepHistory,
silent, vibrateOnly, hideContent, alertMode,
muteContinuation, batterySaverEnabled, batterySaverThreshold,
complicationSource, complicationApp
```

## Watch Notification Features
- **Conversation stacking** — Messages grouped by conversation key (uses sbn.key from Android, which is stable across content updates)
- **MessagingStyle rendering** — Multi-message conversations shown with sender names
- **Content hash dedup** — Prevents re-alerting on unchanged notifications (important for WhatsApp which re-posts all unread)
- **Reply suppression** — 5-second silence window after replying to prevent echo
- **Open on Watch** — Launches sideloaded app; tries conversation-level deep linking via notification tag/shortcutId for known apps (WhatsApp, Messenger, Telegram, Discord, Instagram)
- **Open on Phone** — Sends message to phone to fire stored contentIntent
- **Mute** — Temporarily mutes an app for configurable duration
- **Snooze** — Temporarily snoozes a specific notification
- **Per-app notification channels** — Each source app gets its own Android notification channel
- **Manual vibration** — Bypasses Android's notification channel vibration caching
- **Battery saver** — Skips notifications when watch battery is below threshold

## Conventions & Patterns

### Code Style
- Kotlin, no Compose (traditional Views + XML layouts)
- ViewBinding enabled for all activities
- Coroutines with `Dispatchers.IO` + `SupervisorJob` for async work
- `ConcurrentHashMap` for thread-safe caches
- Synchronized blocks for compound read-modify-write operations
- Constants defined as `companion object` vals/consts
- `Log.d(TAG, ...)` for debug logging with TAG = class-specific string

### Memory Management
- All caches have max size limits with pruning (e.g., MAX_PENDING_ACTIONS = 500)
- `ArrayDeque` for O(1) head removal in conversation history (capped at 50 messages)
- Content hash map pruned when exceeding MAX_CONTENT_HASHES

### Error Handling
- Try-catch around all notification processing with logging
- Graceful fallbacks (e.g., deep link fails → basic app launch)
- Encryption key sync retries up to 3 times with exponential backoff

### Branch Naming
- Format: `devin/<timestamp>-<description>` (e.g., `devin/1774123965-open-on-watch-deep-link`)

### Commit Message Format
- Conventional commits: `feat:`, `fix:`, `chore:`, `docs:`, `perf:`

## Testing
- No unit tests in the project
- Testing requires physical Android phone + Wear OS watch
- Cannot be tested end-to-end in CI or emulator environments (Wearable API requires real paired devices)
- Build verification: `./gradlew assembleDebug` is the primary check

## GitHub
- Repository: `WitherredAway/NotificationMirror`
- Owner: @WitherredAway
- Auto-update checker pulls from GitHub Releases
- No CI/CD pipeline — manual APK builds and GitHub releases
