# SMSmd

A small native Android app that forwards incoming SMS messages through a selected SMTP profile.

## Features

- Receives new SMS without reading message history
- Forwards incoming messages when forwarding is enabled
- Reassembles multipart messages and prevents duplicate forwarding
- Includes Gmail and Outlook SMTP provider presets plus custom SMTP
- Supports multiple encrypted SMTP profiles with one active profile
- Supports SMTP with required STARTTLS or implicit TLS
- Requires a successful test email before forwarding can be enabled
- Persists delivery work and retries temporary network/SMTP failures
- Classifies authentication, certificate, and permanent SMTP failures
- Shows only the most recent delivery result
- Optional failure notifications with a dedicated Android channel
- Encrypts SMTP passwords, queued senders, and message bodies with Android Keystore AES-GCM
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

## Setup

1. Open Settings and tap the add button.
2. Choose Gmail, Outlook, or Custom SMTP. Provider server settings are filled automatically.
3. Enter the email address, app password, sender, and recipients.
4. Send a test email, then select the verified profile as active.
5. Grant incoming SMS access in Settings and enable forwarding on the Home tab.

## Privacy

The app does not read SMS history or contacts. SMTP credentials and queued message content are encrypted with an Android Keystore-backed AES-GCM key. Message content is removed from the local queue after final delivery processing.

SMS access is a restricted permission on Google Play and requires an approved core use case and permissions declaration before Play distribution.
