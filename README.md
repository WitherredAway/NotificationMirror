# Notification Mirror - WearOS App

Fully customizable lightweight WearOS app for mirroring app notifications from phone to watch with replying and buttons.

Made with help from Devin AI.

Mirrors notifications from your Android phone to your Wear OS watch, with reply support.

## Why?

Some apps (like WhatsApp) have their own Wear OS app that handles notifications independently. This means you get different notification behavior on your watch vs phone. This app lets you keep the watch app installed while mirroring your phone's notification experience to the watch, giving you a consistent notification style and the ability to reply directly.

E.g. I personally hate the WhatsApp watch app notifications. It's buggy and not thought through. Sometimes it'll buzz me for "You've been added to this group" notifications on a group I've been in forever. I hate how it beeps and vibrates even tho I have my phone open and possibly even on WhatsApp. I hate that you can't mute specific chats ONLY on the watch. I hate how stiff and uncustomizable they are. BUT I don't wanna uninstall the app either because it's nice to be able to read chat history and send voice messages. 

So that's why this app exists! It basically lets you have the watch app installed AND receive phone app notifications at the same time, with MUCH more granular customization and configuration. E.g. push notifications filtered by words and apps, change phone screen on behaviour, set custom vibration patterns, reply and press notification buttons from the watch, etc.

## Features

### Core
- **Notification mirroring** — captures phone notifications via `NotificationListenerService` and forwards them to your Wear OS watch via Wearable Data Layer API (`MessageClient`)
- **Inline reply** — reply to mirrored notifications directly from the watch (WhatsApp, Telegram, Discord, etc.) via `RemoteInput`
- **All notification actions forwarded** — not just reply, but every action button (mark as read, archive, mute, etc.) is mirrored to the watch
- **Auto-dismiss sync** — when a notification is dismissed on the phone, it's automatically dismissed on the watch (configurable)

### Filtering
- **App whitelist** — pick which apps' notifications get mirrored; if none selected, all apps are mirrored
- **Keyword whitelist (regex)** — only mirror notifications matching these patterns
- **Keyword blacklist (regex)** — never mirror notifications matching these patterns

### Watch Notifications
- **Per-app notification groups** — each source app's notifications stack separately on the watch (own channels/groups)
- **App icons** — the phone serializes each app's icon as a large icon displayed on the watch notification
- **"Open on Watch" button** — if the source app has a companion app on the watch (WhatsApp, Spotify, etc.), a button appears to open it (configurable)
- **Quick-mute from watch** — every mirrored notification has a "Mute Xmin" button to temporarily stop mirroring that app (duration configurable)
- **Per-app vibration patterns** — set custom vibration patterns for any app
- **Default vibration pattern** — customizable fallback pattern for apps without a custom one
- **Notification priority** — configurable (High / Default / Low)
- **Auto-cancel on tap** — configurable
- **BigText threshold** — configurable character count before expanded text kicks in

### Behavior Settings
- **DND sync** — skip mirroring when phone is in Do Not Disturb mode (configurable)
- **Screen-off mirroring mode** — three modes: always mirror / only when screen off / mirror but silent when screen on
- **Mirror ongoing notifications** — optionally mirror persistent notifications (music players, timers, etc.)

### Notification Log
- **Permanent log** — all SENT notifications are logged (no expiry, only cleared manually)
- **Search (regex)** — search through logs with regex on both phone and watch
- **App filter dropdown** — filter log by source app
- **Export** — export logs as CSV + JSON via Android share sheet (phone)

### Watch App
- **Notification Settings shortcut** — button to open WearOS granular notification settings
- **Wear Tile** — "Notification Counts" tile for your watch face showing per-app notification counts

### Phone App
- **Test notification button** — send a custom test notification to the watch on behalf of any app

## Setup

### Download
1. Download the phone and watch apps from the [releases page](https://github.com/WitherredAway/NotificationMirror/releases) (or build the apks yourself, scroll down for that)

### Install
You'll have to sideload the watch apk, e.g. using [Wear Installer 2](https://play.google.com/store/apps/details?id=org.freepoc.wearinstaller2&hl=en_IN) or [Geminiman WearOS Manager](https://play.google.com/store/apps/details?id=com.geminiman.wearosmanager&hl=en_IN) or adb, etc

If Google Play prevents you from installing the phone apk, please use [Install With Options](https://github.com/zacharee/InstallWithOptions) to install it.

1. Install the `mobile` APK on your Android phone
2. Install the `wear` APK on your Wear OS watch
3. Open the phone app and grant **Notification Access** permission, disable Battery Optimization.
4. Open the watch app and grant the Notification permission.
5. Make sure phone and watch are paired

### Usage
1. Once notification access is granted, the phone app runs in the background
2. Any notification that appears on your phone will be mirrored to your watch
3. For notifications that support replies (WhatsApp, Telegram, etc.), you can reply directly from the watch
4. Replies are sent back to the phone and executed on the original notification's reply action

## How It Works

1. **Phone captures notification** → `NotificationListenerService` intercepts it
2. **Phone sends to watch** → Via `MessageClient` (Wearable Data Layer API)
3. **Watch displays notification** → As a local notification with the same title/text
4. **User replies on watch** → Reply text sent back to phone via `MessageClient`
5. **Phone executes reply** → Uses the original notification's `RemoteInput` action

## Notes

- The app skips ongoing/foreground service notifications to avoid noise
- When a notification is dismissed on the phone, it's also dismissed on the watch
- Both devices must be connected via Bluetooth for mirroring to work
- The phone and watch apps share the same application ID for Wearable API pairing

## Architecture

This is a multi-module Android project:

- **`mobile/`** - Phone companion app
  - `NotificationListener` - A `NotificationListenerService` that captures all phone notifications
  - Forwards notifications to the watch via the Wearable Data Layer API (MessageClient)
  - `ReplyReceiverService` - Receives replies from the watch and executes them on the original notification's reply action

- **`wear/`** - Wear OS watch app
  - `NotificationReceiverService` - Receives mirrored notifications and displays them as local watch notifications
  - Supports inline reply via `RemoteInput` on notifications that support it
  - `ReplyActivity` - Handles sending replies back to the phone

## Building
### Prerequisites
- Android Studio (Arctic Fox or later)
- Android SDK 34
- Wear OS emulator or physical watch

### Build
1. Open the project in Android Studio
2. Sync Gradle
3. Build both `mobile` and `wear` modules
