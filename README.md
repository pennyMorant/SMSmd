# SMSmd

A small native Android app that forwards incoming SMS messages through one selected email or Telegram profile.

## Features

- Receives new SMS without reading message history
- Forwards incoming messages when forwarding is enabled
- Reassembles multipart messages and prevents duplicate forwarding
- Includes Gmail and Outlook SMTP provider presets plus custom SMTP
- Shows email and Telegram together in one delivery profile list
- Supports multiple encrypted SMTP profiles and one Telegram Bot profile
- Uses exactly one selected delivery profile for each incoming SMS
- Supports SMTP with required STARTTLS or implicit TLS
- Sends the complete SMS through a Telegram Bot with a tested Bot Token and Chat ID
- Detects recent Telegram chats after the user starts the bot
- Requires a successful real delivery test before a profile can be selected
- Persists delivery work and retries temporary network/SMTP failures
- Classifies authentication, certificate, and permanent SMTP failures
- Shows only the most recent delivery result
- Optional failure notifications with a dedicated Android channel
- In-app request to allow reliable background operation outside battery optimization
- Checks GitHub Releases in Settings, verifies the APK digest, package, version, and signing certificate, then offers installation
- Encrypts SMTP passwords, Telegram Bot tokens, queued senders, message bodies, and delivery snapshots with Android Keystore AES-GCM
- Binds each queued message to the encrypted delivery configuration selected when it was received
- Removes message content from the local queue after final success or failure

## Requirements

- Android 13 (API 33) or newer
- Android SDK 37
- JDK 17 or newer

## Build

```sh
./gradlew assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Releases

GitHub Actions builds a signed release APK and AAB when a semantic version tag such as
`v1.0.0` is pushed. The workflow can also be started manually with a version tag.

Configure these repository secrets before the first release:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Each GitHub Release includes the APK, AAB, and a `SHA256SUMS` file.

## Developer verification

Register the app with Android Developer Console or Google Play Console before
public sideload distribution:

- Package name: `io.github.pennymorant.smsmd`
- Signing certificate SHA-256: `EE:78:31:27:40:53:83:BF:DF:3F:B8:35:0A:55:41:2D:A7:CD:F9:2D:9B:7C:B1:3C:59:B2:3E:E4:2A:B2:60:49`

Keep using the same release signing key for every update. Changing the key will
break the package registration and prevent installed copies from updating.

## Setup

1. Open Settings and tap the add button.
2. Choose Email or Telegram in the bottom sheet.
3. For email, choose Gmail, Outlook, or Custom SMTP and enter the account details.
4. For Telegram, create a bot with BotFather, send `/start`, then enter the Bot Token and Chat ID.
5. Run the real send test and select the verified profile with its radio button.
6. Grant incoming SMS access in Settings and enable forwarding on the Home tab.

## Privacy

The app does not read SMS history or contacts. SMTP credentials, Telegram Bot tokens,
and queued message content are encrypted with an Android Keystore-backed AES-GCM key.
Message content is removed from the local queue after final delivery processing.
Telegram Bot chats are not end-to-end encrypted.

SMS access is a restricted permission on Google Play and requires an approved core use case and permissions declaration before Play distribution.
