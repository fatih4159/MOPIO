# MOPIO — Mobile PlatformIO IDE for ESP32

A fully self-contained, offline-capable Android IDE for ESP32 firmware development.  
Everything runs **on the device** — no cloud build, no companion desktop, no network dependency after the one-time bootstrap.

---

## Features

| Capability | Details |
|---|---|
| **On-device build** | Full PlatformIO + Espressif toolchains inside a proot/glibc Linux container |
| **Flash via USB-OTG** | RFC2217 serial bridge with DTR/RTS auto-reset into download mode |
| **Serial monitor** | Native USB serial reader with hex view, timestamps, adjustable baud |
| **Code editor** | sora-editor with line numbers, syntax highlight, tabbed files |
| **Git** | Clone, commit, pull, push via JGit — no shell needed |
| **Fully offline** | After the one-time rootfs bootstrap, zero network required |

---

## Architecture

```
┌──────────────────────────────────────────────────────────┐
│  UI (Jetpack Compose + Material 3): editor, file tree,   │
│  build console, flash screen, serial monitor, git panel  │
├──────────────────────────────────────────────────────────┤
│  Orchestration (Kotlin):                                   │
│   • ContainerManager   (proot exec + bootstrap)           │
│   • PlatformIO controller (pio run / pio run -t upload)   │
│   • Git controller (JGit — clone/commit/pull/push)        │
├───────────────┬──────────────────────────┬────────────────┤
│ Linux Backend │  USB / Flash Layer        │  Editor / Git  │
│ proot + glibc │  usb-serial-for-android   │  sora-editor   │
│ rootfs +      │  + RFC2217 server bridge  │  + JGit        │
│ PlatformIO    │  + native serial monitor  │                │
└───────────────┴──────────────────────────┴────────────────┘
        ▲                    ▲
        │ rfc2217://127.0.0.1:PORT  (data + DTR/RTS)
        └────────────────────┘
```

### The Three Hard Realities

1. **Bionic vs glibc** — Android's Bionic libc is incompatible with Espressif's prebuilt GCC toolchains and PlatformIO binaries. MOPIO solves this by running all build tools inside a **Debian aarch64 rootfs via `proot`** (userspace, no root required).

2. **USB serial access from the container** — Android does not expose `/dev/ttyUSB*` to app userspace. The native Android layer owns the USB device via `usb-serial-for-android` and exposes it to the container as an **RFC2217 serial-over-TCP server** on `127.0.0.1`. esptool connects with `rfc2217://127.0.0.1:<port>`, which carries DTR/RTS control lines so the standard ESP32 auto-reset circuit works without modification.

3. **W^X restrictions** — Android enforces write-XOR-execute on app-writable directories. The `proot` binary ships as `libproot.so` inside `jniLibs/arm64-v8a/`, extracted at install time to `nativeLibraryDir` — the one directory Android allows executing from without root.

---

## Tech Stack

| Component | Library / Version |
|---|---|
| Language | Kotlin 2.0.21 |
| UI | Jetpack Compose + Material 3 (BOM 2024.09.00) |
| Build system | AGP 8.7.0 / Gradle 8.7 |
| Min SDK | 26 (Android 8.0) |
| ABI | `arm64-v8a` only |
| USB serial | `mik3y/usb-serial-for-android` 3.7.3 |
| Code editor | `Rosemoe/sora-editor` 0.23.4 |
| Git | Eclipse JGit 6.10.0 |
| Container | proot 5.4.0 (static aarch64) + Debian aarch64 rootfs |
| Networking | OkHttp 4.12.0 (rootfs download) |
| Archive | Apache Commons Compress 1.26.2 (tar.gz extraction) |
| Background tasks | WorkManager 2.9.0 |
| Secure storage | Jetpack Security Crypto 1.1.0-alpha06 |
| Navigation | Navigation Compose 2.8.0 |

---

## Supported Boards & USB Chips

| USB chip | Board examples |
|---|---|
| Silabs CP2102 / CP2104 | ESP32-WROOM DevKit (most common) |
| WCH CH340 / CH341 | ESP32 clones, many Arduino boards |
| FTDI FT232 / FT231 | Adafruit Feather ESP32, custom boards |
| Espressif native USB-CDC | ESP32-S3, ESP32-C3 (USB-JTAG/CDC) |

Supported target architectures inside PlatformIO:
- **xtensa-esp32** (ESP32, ESP32-S2, ESP32-S3)
- **riscv32-esp** (ESP32-C3, ESP32-C6, ESP32-H2, ESP32-P4)

---

## Getting Started

### Prerequisites

- Android device with **USB-OTG support** (check with a USB OTG adapter + mouse)
- Android 8.0 (API 26) or later, `arm64-v8a` ABI
- A USB-OTG cable (USB-A to USB-C or micro-USB depending on your phone)
- On first run: Wi-Fi connection to download the ~500 MB Debian rootfs + PlatformIO toolchains (~1–2 GB total after extraction)

### Installation

1. Download `mopio-debug.apk` from the [Releases](https://github.com/fatih4159/MOPIO/releases) page (or from the `build/` folder in this repository).
2. Enable **Install from unknown sources** on your device (Settings → Apps → Special app access → Install unknown apps).
3. Install the APK.

### First Run

1. Open MOPIO — the setup wizard starts automatically.
2. Tap **Get Started** → proot binary is verified.
3. Tap **Download Rootfs** → downloads and extracts the Debian aarch64 rootfs (requires ~2 GB free storage and Wi-Fi).
4. Tap **Open Projects** when done.

### Create Your First Project

- Tap the **+** FAB → **New Project** → enter a name → a minimal ESP32 blink project is created.
- Or tap **Clone from GitHub** → enter a repository URL and optional PAT for private repos.

### Build

1. Open a project → tap **Build** in the bottom bar.
2. The build console streams PlatformIO output with ANSI color stripped, error/warning highlighting, and a parsed error list at the bottom.
3. On success, tap **Flash** to proceed.

### Flash

1. Connect your ESP32 via USB-OTG.
2. Android shows "Allow MOPIO to access this USB device?" — tap **OK**.
3. In MOPIO, navigate to the project → tap **Flash**.
4. The RFC2217 bridge starts on a local port; `pio run -t upload` talks to it.
5. If flashing fails, hold the **BOOT** button on the ESP32 while tapping Flash to force download mode.

### Serial Monitor

- Tap **Monitor** from the project screen or directly after a successful flash.
- Adjust baud rate (default 115200), toggle timestamps, hex view, and autoscroll.
- Use the send field to write text to the serial port.

---

## Building from Source

### 1. Clone

```bash
git clone https://github.com/fatih4159/MOPIO.git
cd MOPIO
```

### 2. Get the proot binary

The `proot` static binary is required but is not committed to the repo (it is a binary blob).

```bash
./scripts/get_proot.sh        # downloads proot 5.4.0 aarch64 static binary
```

This places `libproot.so` at `app/src/main/jniLibs/arm64-v8a/libproot.so`.

Alternatively, extract it from a Termux installation:
```bash
# On device via Termux:
pkg install proot
cp $(which proot) /path/to/project/app/src/main/jniLibs/arm64-v8a/libproot.so
```

### 3. Build the debug APK

```bash
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`

### 4. Install on device

```bash
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

---

## Project Structure

```
MOPIO/
├── app/src/main/java/com/mopio/
│   ├── MainActivity.kt              # Single-activity host; USB_DEVICE_ATTACHED handler
│   ├── MopioApplication.kt          # App class; notification channels
│   ├── container/
│   │   ├── ContainerManager.kt      # proot exec layer; bootstrap; pio build/upload
│   │   ├── ContainerService.kt      # Foreground service for background builds
│   │   └── RootfsInstaller.kt       # Download, SHA256-verify, extract Debian rootfs
│   ├── git/
│   │   ├── GitController.kt         # JGit clone/pull/push/commit/status/branches
│   │   └── PatStorage.kt            # EncryptedSharedPreferences wrapper for GitHub PAT
│   ├── phase0/
│   │   ├── Phase0Screen.kt          # Hardware spike test UI (Settings → Hardware Spikes)
│   │   └── Phase0ViewModel.kt       # Runs spikes A–D sequentially
│   ├── platformio/
│   │   ├── AnsiStripper.kt          # Strips/annotates ANSI escape codes from build output
│   │   ├── BuildErrorParser.kt      # Parses GCC error:file:line:col diagnostics
│   │   └── PlatformIoIniParser.kt   # Parses platformio.ini → envs, board, framework
│   ├── ui/
│   │   ├── build/                   # Build console screen + ViewModel
│   │   ├── flash/                   # Flash screen + ViewModel
│   │   ├── git/                     # Git panel screen + ViewModel
│   │   ├── home/                    # Project list, new/clone dialogs
│   │   ├── monitor/                 # Serial monitor screen + ViewModel
│   │   ├── nav/                     # AppNav (NavHost) + Routes
│   │   ├── project/                 # Editor + file tree + ProjectViewModel
│   │   ├── settings/                # Settings screen
│   │   ├── setup/                   # First-run wizard + SetupViewModel
│   │   └── theme/                   # Material 3 colour tokens
│   └── usb/
│       ├── Rfc2217Server.kt         # RFC2217 serial-over-TCP bridge (DTR/RTS via SET_CONTROL)
│       ├── SerialMonitor.kt         # Native USB serial reader (SharedFlow<ByteArray>)
│       └── UsbPortBroker.kt         # Single USB port owner; serialises flash vs monitor
├── app/src/test/java/com/mopio/
│   ├── AnsiStripperTest.kt
│   ├── BuildErrorParserTest.kt
│   ├── PlatformIoIniParserTest.kt
│   └── Rfc2217FramingTest.kt
├── app/src/main/assets/
│   └── rootfs_config.json           # Rootfs URL + SHA256 (updated per release)
├── app/src/main/jniLibs/arm64-v8a/
│   └── libproot.so                  # Static proot binary (obtain via get_proot.sh)
└── scripts/
    └── get_proot.sh                 # Downloads proot static binary
```

---

## Phase Roadmap

| Phase | Status | Description |
|---|---|---|
| 0 — Spikes | ✅ | proot exec, PlatformIO build, USB+RFC2217, real flash |
| 1 — Container | ✅ | Bootstrap, rootfs download/verify/extract, foreground service |
| 2 — Editor | ✅ | File tree, tabbed sora-editor, platformio.ini parsing |
| 3 — Build pipeline | ✅ | Streamed build console, ANSI stripping, error parser, cancel |
| 4 — USB + Flash | ✅ | USB discovery, permission flow, RFC2217 bridge, pio upload |
| 5 — Serial monitor | ✅ | Native USB reader, hex view, timestamps, send, baud picker |
| 6 — Git / GitHub | ✅ | Clone, commit, pull, push, PAT storage |
| 7 — Polish | 🔄 | Notifications, settings, first-run wizard, error taxonomy |
| — | 🗓 | ccache for faster incremental builds |
| — | 🗓 | TextMate C/C++ grammar for syntax highlight |
| — | 🗓 | WorkManager for build survival across process death |
| — | 🗓 | SAF folder picker for external project directories |

---

## RFC2217 Bridge

The RFC2217 implementation in `Rfc2217Server.kt` handles the subset used by pyserial:

- Telnet option negotiation (`WILL`/`DO` OPT_COM_PORT = 44)
- `SET_BAUDRATE` (sub-option 1) — configures the USB serial port baud rate
- `SET_CONTROL` (sub-option 5) — maps to `UsbSerialPort.dtr`/`rts`:
  - 8 → DTR ON, 9 → DTR OFF
  - 11 → RTS ON, 12 → RTS OFF
- `PURGE_DATA` (sub-option 12) — flushes input/output buffers
- IAC `0xFF` byte-stuffing for transparent data passthrough

The ESP32 auto-reset circuit (DTR+RTS toggle) works without modification because pyserial/esptool's reset sequence is fully conveyed over TCP.

---

## USB Chip VID/PIDs

Declared in `app/src/main/res/xml/device_filter.xml` (decimal values):

| Chip | VID | PID |
|---|---|---|
| Silabs CP2102 | 4292 | 60000 |
| WCH CH340 | 6790 | 29987 |
| FTDI FT232 | 1027 | 24577 |
| Espressif native USB | 12346 | 4097 |

---

## Running Tests

```bash
# Unit tests (JVM — no device needed)
./gradlew test

# Instrumented tests (requires connected device/emulator)
./gradlew connectedAndroidTest
```

Unit tests cover: `PlatformIoIniParser`, `Rfc2217Server` framing, `BuildErrorParser`, `AnsiStripper`.

---

## Known Limitations

- **arm64-v8a only** — 32-bit ARM devices are out of scope.
- **Build speed** — proot uses ptrace for syscall interception, adding overhead. Expect 2–5× slower builds than native Linux. ccache is planned for Phase 3+.
- **Storage** — ~1–2 GB for the Debian rootfs + Espressif toolchains.
- **Auto-reset variance** — ESP32-S3 and ESP32-C3 with native USB-CDC may need the BOOT button if the auto-reset sequence differs from the classic DTR/RTS wiggle.
- **Background builds** — The build runs in a ViewModel coroutine scope; process death during a background build will cancel it. WorkManager integration is planned.

---

## License

[MIT License](LICENSE)
