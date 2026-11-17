# 📱 Gallery App

A modern, high-performance Photo & Video Gallery built with Kotlin + Jetpack Compose.

## Features

- **Smart Media Organization** — Timeline, Albums, and Videos tabs with dynamic grid views
- **High-Performance Scrolling** — Coil image loading with 30% RAM + 3% disk caching
- **Secure Vault** — AES-256-GCM encrypted hidden folder with biometric / PIN authentication
- **Media Editor** — Rotate, color filters, brightness & saturation adjustments
- **Scoped Storage** — MediaStore API with Android 13/14 `READ_MEDIA_*` permission support

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Architecture | MVVM + Clean Architecture (multi-module) |
| DI | Hilt |
| Image Loading | Coil 2 |
| Database | Room |
| Video | ExoPlayer (Media3) |
| Encryption | AES-256-GCM via Android Keystore |
| Auth | BiometricPrompt API |
| Persistence | DataStore Preferences |

## Module Structure

```
app/                    → Entry point, navigation, DI wiring
core/
  core-domain/          → Models, repository interfaces, use cases
  core-data/            → Repository impls, Room DB, MediaStore
  core-ui/              → Compose theme, shared components
  core-security/        → Vault crypto, biometric auth
feature/
  feature-gallery/      → Grid view, albums, timeline
  feature-viewer/       → Full-screen viewer, pinch-to-zoom, ExoPlayer
  feature-vault/        → Secure vault with PIN pad & biometric
  feature-editor/       → Crop, rotate, filters, adjustments
```

## Requirements

- Android Studio Ladybug (2024.2+)
- Android SDK 35
- Min SDK 26 (Android 8.0)
- JDK 17

## Building

```bash
./gradlew assembleDebug
```

## Security Notes

- Vault files are stored at `/data/data/com.gallery.app/files/vault/` — never on external storage
- AES-256-GCM encryption via Android Keystore (hardware-backed on supported devices)
- `FLAG_SECURE` prevents screenshots inside the Vault
- Originals are deleted from MediaStore after encryption
