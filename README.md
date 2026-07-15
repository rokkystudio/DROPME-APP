# DROPME Android

DROPME Android is the Android client for transferring files to a Windows machine running DROPME Server on the same local Wi-Fi network.

This repository contains only the Android application.
The Windows application lives in a separate repository.

## Scope

Current implementation focus:

- Android Share integration via `ACTION_SEND` and `ACTION_SEND_MULTIPLE`
- Wi-Fi-only transfer flow
- local network scan for DROPME Windows servers over HTTP
- upload to Windows endpoint `PUT /dropme/upload`
- server picker when more than one Windows server is found

Planned next stages:

- main app connection screen
- SAF folder selection
- Android-hosted WebDAV server
- Windows control session for mounting and unmounting
- full WebDAV write operations

## Project structure

Key application packages:

- `com.rokkystudio.dropme`
- `com.rokkystudio.dropme.network`
- `com.rokkystudio.dropme.storage`
- `com.rokkystudio.dropme.ui`

Main entry points:

- `MainActivity` — app launcher activity
- `ShareActivity` — receives Android Share intents and uploads files to Windows

## Requirements

- Android Studio
- Android SDK matching the project Gradle configuration
- Windows machine running DROPME Server
- Android device connected to the same Wi-Fi network as the Windows server

## Build

Debug build from the project root:

```bash
./gradlew :app:assembleDebug
```

On Windows:

```bat
gradlew.bat :app:assembleDebug
```

Generated APK:

- `app/build/outputs/apk/debug/app-debug.apk`

## Runtime behavior

### Share flow

1. Another Android app sends one or more file `Uri` objects to DROPME.
2. DROPME checks that the active network is Wi-Fi.
3. DROPME scans the local subnet for Windows DROPME servers.
4. If one server is found, upload starts immediately.
5. If multiple servers are found, the user selects a target server.
6. Each shared file is streamed through `ContentResolver` to the Windows upload endpoint.

### Network rules

- transfer is allowed only over Wi-Fi
- no fallback to mobile data
- client sockets are created through the selected Android Wi-Fi `Network`
- local network policy or VPN blocking is surfaced as a user-visible error

## Protocol assumptions for Stage 1

Server discovery request:

- `GET http://<ip>:49231/dropme/info`

Expected response fields:

- `app == "DROPME"`
- `role == "windows-server"`
- `protocolVersion == 1`

Upload request:

- `PUT http://<host>:49231/dropme/upload?name=<urlencoded filename>`

Request body:

- raw file bytes from `ContentResolver.openInputStream(uri)`

## Localization

- default UI strings: English
- Russian translations: `app/src/main/res/values-ru/strings.xml`

## Backup configuration

This repository does not currently define Android backup or data extraction rules.

The previous template files were removed because they were sample placeholders and did not describe an intentional backup policy for DROPME data.

## Notes for development

- This project does not use a permanent background service for the MVP.
- Share uploads are intended for local Wi-Fi only.
- File `Uri` values are read through `ContentResolver`; filesystem paths are not assumed.
- If you add persisted app data later, define an explicit backup policy before reintroducing backup rule XML files.

