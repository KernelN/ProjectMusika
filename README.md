# Musika 🎵

An advanced, offline-first local audio player for Android built with **Jetpack Compose**, **Kotlin Coroutines / Flow**, and a nostalgia-fueled **retro Winamp-inspired** interface.

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.20-purple.svg)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026%2B)-green.svg)](https://developer.android.com)
[![Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20%2F%20Material%203-brightgreen.svg)](https://developer.android.com/jetpack/compose)

---

## ✨ Features

- **Dual Queue Playback Engine**
  - Independent regular queue and on-the-fly priority queue.
  - Seamless background playback with foreground service notifications and media session integration.
  - Granular repeat (`ALL`, `ONE`, `OFF`) and shuffle modes.

- **Intelligent Root Scanner & Hierarchy Management**
  - Automatic recursive scanning of musical roots.
  - Intelligent multi-disc album grouping (e.g., `CD1`, `Disc 2`, `Part I`).
  - Hierarchical folder navigation alongside traditional Artist / Album views.

- **Format Deduplication & Audio Quality Ranking**
  - Resolves duplicate tracks across different encodings (`FLAC`, `ALAC`, `WAV`, `AAC`, `MP3`).
  - Automatically identifies and prefers the highest fidelity format based on bitrate, sample rate, and bit depth.

- **Retro Winamp & Modern UI Blend**
  - Retro LCD displays, track position scrubbers, and LED-style volume/visualizer indicators.
  - Modern Jetpack Compose Material 3 bottom sheets, dialogs, and smooth motion transitions.

- **Web Metadata Fetcher & Confirmation Safeguard**
  - Fetches missing cover art and metadata.
  - Built-in write confirmation safeguard before persisting changes to local files or database.

- **Local Persistence & Sync Contract**
  - Backed by **Room Database** (SQLite) for ultra-fast queries and offline caching.
  - Abstract remote sync contract ready for cloud or local network synchronization.

---

## 🛠️ Tech Stack & Architecture

- **Language:** Kotlin 2.0.20
- **UI Toolkit:** Jetpack Compose with Material 3 & Compose Compiler 2.0
- **Architecture:** Clean Architecture + MVVM + Unidirectional Data Flow (UDF)
- **Database:** Room (KSP-generated DAOs and entities)
- **Concurrency:** Kotlin Coroutines & `StateFlow` / `SharedFlow`
- **Dependency Injection / Lifecycle:** AndroidX Lifecycle ViewModel & Services
- **Build System:** Gradle Kotlin DSL (`build.gradle.kts`) with Android Gradle Plugin 8.5.2
- **Compatibility:** Min SDK 26 (Android 8.0 Oreo), Target & Compile SDK 35 (Android 15)

---

## 📂 Project Structure

```text
app/src/main/java/com/musika/player/
├── MusikaApplication.kt        # App initialization & service orchestrator
├── core/
│   ├── model/                  # Domain models (Track, Album, Artist, FolderNode, etc.)
│   └── sync/                   # Remote synchronization contracts
├── data/
│   ├── local/                  # Room Database, DAOs, and Entities
│   ├── metadata/               # Web metadata fetcher & confirmation flows
│   ├── repository/             # Library and user preferences repositories
│   └── scanner/                # Audio parser, multi-disc detection, deduplicator
├── player/
│   ├── DualQueueEngine.kt      # Core playback queue manager
│   └── MusikaPlaybackService.kt# Android Foreground Media Service
└── ui/
    ├── MainActivity.kt         # Single-activity Compose host
    ├── MusikaMainScreen.kt     # Main screen orchestrator
    ├── MusikaViewModel.kt      # Presentation state & intents
    ├── components/             # Retro Winamp & Compose UI components
    └── theme/                  # Material 3 & retro color palette
```

---

## 🚀 Getting Started

### Prerequisites

- **Android Studio** Ladybug | 2024.2.1 or newer.
- **JDK:** OpenJDK 17 or higher.
- **Android SDK:** Platform API 35 with build-tools 35.0.0.

### Building from Source

1. Clone the repository:
   ```bash
   git clone https://github.com/KernelN/ProjectMusika.git
   cd ProjectMusika
   ```

2. Compile the debug APK using the Gradle wrapper:
   - On Windows:
     ```powershell
     .\gradlew.bat assembleDebug
     ```
   - On Linux / macOS:
     ```bash
     ./gradlew assembleDebug
     ```

3. The generated APK will be located at:
   ```text
   app/build/outputs/apk/debug/app-debug.apk
   ```

---

## 🧪 Testing

Run unit and robolectric tests:

```bash
.\gradlew.bat test
```

Test suites cover:
- Audio metadata parsing and multi-disc grouping
- Format deduplication and audio fidelity priority logic
- Dual queue playback engine transitions and sync contracts

---

## 📄 License

This project is licensed under the terms of the **GNU General Public License v3.0 (GPLv3)**.  
See the [LICENSE](LICENSE) file for details.
