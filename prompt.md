# Claude Code Prompt — Fully On-Device Android PlatformIO / ESP32 IDE

> Paste everything below into Claude Code as the project brief. It is written to be
> executed in phases. Do **not** scaffold the whole app before the de-risking spikes
> in Phase 0 pass.

---

## 1. Role & Mission

You are the lead engineer building **PioDroid** (working title — rename freely): a
**fully self-contained, offline-capable Android IDE** for ESP32 firmware development
on top of **PlatformIO**. Everything runs **on the phone/tablet** — no cloud build, no
companion desktop, no network dependency after the one-time environment bootstrap.

The app must let a user:

1. Open / create a PlatformIO project and edit C/C++/INI files.
2. Clone / commit / push to GitHub.
3. **Build** the firmware **on-device** with real PlatformIO + the real Espressif
   toolchains (xtensa for ESP32/S2/S3, RISC-V for C3/C6/H2/P4).
4. **Flash** the resulting binary to a connected ESP32 over **USB-OTG**, with correct
   auto-reset into download mode.
5. Open a **serial monitor** on the same USB device.

This is a large project. Work incrementally, commit per milestone, and **validate the
risky assumptions with throwaway spikes before writing UI**.

---

## 2. The Three Hard Realities (read before designing anything)

These constraints define the architecture. Do not design around wishful thinking.

1. **Bionic vs glibc.** Android links against Bionic libc. The Espressif GCC
   toolchains and PlatformIO's prebuilt binaries expect **glibc**. They will not run
   in a plain Android process. → We must run the build inside a **glibc Linux rootfs
   via `proot`** (userspace, no root, ptrace-based). PlatformIO then downloads the
   `linux_aarch64` (glibc) toolchain packages and runs them inside that rootfs.

2. **USB serial is not a tty.** Android does not expose `/dev/ttyUSB0` to unprivileged
   userspace, so `esptool` **inside the proot container cannot open the port directly.**
   → The **native Android layer** owns the USB device (via `usb-serial-for-android`)
   and exposes it to the container as an **RFC2217 serial-over-TCP server on
   `127.0.0.1`**. esptool/pyserial connect with `rfc2217://127.0.0.1:<port>`, which
   carries DTR/RTS control lines — so the standard ESP32 auto-reset works **without
   reimplementing esptool**. This is the central design decision.

3. **Executable-from-writable-storage restrictions.** Recent Android enforces W^X for
   app-writable dirs. The `proot` binary itself ships as a bundled native lib
   (executable from the extracted nativeLibs dir, which is allowed). Guest binaries
   inside the rootfs are launched **through proot's loader**, not direct `exec()`, which
   is how Termux/proot-distro run glibc binaries successfully. **This MUST be validated
   in Phase 0** on a real device; the whole project depends on it.

---

## 3. Target Architecture (layered)

```
┌──────────────────────────────────────────────────────────┐
│  UI (Jetpack Compose): editor, file tree, build console,  │
│  flash button, serial monitor, git panel, board picker    │
├──────────────────────────────────────────────────────────┤
│  Orchestration (Kotlin):                                   │
│   • PlatformIO controller  (pio run / run -t upload)       │
│   • Project / platformio.ini parser                        │
│   • Git controller (JGit)                                  │
├───────────────┬──────────────────────────┬────────────────┤
│ Linux Backend │  USB / Flash Layer        │  Editor/Git    │
│ proot + glibc │  usb-serial-for-android   │  sora-editor   │
│ rootfs +      │  + RFC2217 server bridge  │  + JGit        │
│ PlatformIO    │  + native serial monitor  │                │
└───────────────┴──────────────────────────┴────────────────┘
        ▲                    ▲
        │ rfc2217://127.0.0.1:PORT (data + DTR/RTS)
        └────────────────────┘
```

The proot container produces `firmware.bin`; `pio run -t upload` (esptool) talks to the
USB device **only** through the RFC2217 bridge owned by the native layer.

---

## 4. Tech Stack (pinned — justify any deviation before changing)

- **Language / UI:** Kotlin, Jetpack Compose, Material 3.
- **Min/Target SDK:** minSdk 26, target the current latest stable. (The app is
  **sideloaded** — assume no Google Play policy constraints; this matters for the
  executable-storage situation.)
- **Container:** bundled static `proot` (aarch64) + a minimal **Debian/Ubuntu aarch64
  rootfs** (glibc). Bootstrap downloaded on first run, extracted to app-private
  storage. Provide `arm64-v8a` only initially; note `armeabi-v7a` as out of scope.
- **Build system in container:** Python 3 + `pip install platformio`.
- **USB serial:** `mik3y/usb-serial-for-android` (CP210x, CH340/CH341, FTDI, CDC-ACM).
- **Serial-over-TCP:** custom minimal **RFC2217** server in Kotlin bridging to the USB
  serial driver (must map SET_CONTROL → DTR/RTS).
- **Code editor:** `Rosemoe/sora-editor` (syntax highlight, line numbers, TextMate/
  tree-sitter grammars for C/C++/INI).
- **Git:** Eclipse **JGit** (pure-JVM) at the app layer; auth via GitHub PAT, optional
  OAuth device-flow later.
- **Long tasks:** foreground service + WorkManager so builds/flashes survive screen-off.

---

## 5. Phase 0 — De-risking Spikes (DO THIS FIRST, no UI)

Build a throwaway debug Activity that proves the foundation on a **real device**. Do not
proceed to Phase 1 until all four pass. Report results before continuing.

**Spike A — proot + glibc exec.** Bundle proot, extract a tiny Debian aarch64 rootfs,
and run `proot ... /bin/bash -c "uname -a && python3 --version"`. Capture stdout. Proves
Reality #3 on this device/Android version.

**Spike B — PlatformIO build.** Inside the container: `pip install platformio`, create a
minimal `esp32dev` blink project, run `pio run`. Confirm the xtensa toolchain downloads
as `linux_aarch64` and that `firmware.bin` is produced. Time it (set expectations:
proot/ptrace is slow → plan ccache later).

**Spike C — USB + RFC2217 round-trip.** Enumerate a connected ESP32 via
`usb-serial-for-android`, stand up the RFC2217 server, and from the container run
`python3 -c "import serial; s=serial.serial_for_url('rfc2217://127.0.0.1:PORT'); ..."`
to read the boot banner. Confirm **DTR/RTS toggling from the container reaches the USB
driver** (toggle into bootloader and read the ROM "waiting for download" prompt).

**Spike D — Real flash.** `pio run -t upload --upload-port rfc2217://127.0.0.1:PORT`
(or invoke esptool directly) and confirm a successful flash + verify + auto-reset on
real hardware.

If Spike A or C fails, stop and surface the failure with diagnostics — the architecture
must be revisited (e.g., fallback to a Termux-RUN_COMMAND backend), not papered over.

---

## 6. Phased Milestones

Each phase ends with: a working build, a short demo note, and granular commits.

**Phase 1 — Linux backend service.**
- `ContainerManager`: bootstrap (download + verify + extract rootfs), idempotent setup,
  `installPlatformIO()`, and `exec(cmd): Flow<String>` that streams stdout/stderr line
  by line. Run inside a foreground service. Handle ~1–2 GB storage footprint, low-space
  errors, and resumable rootfs download.

**Phase 2 — Editor & project model.**
- File tree over a chosen project dir (SAF/scoped storage aware), tabbed sora-editor,
  save, create file/folder. Parse `platformio.ini` → list `[env:*]`, board, framework.
- Board picker seeded from PlatformIO's board JSON (offline cache).

**Phase 3 — Build pipeline.**
- `PlatformIOController.build(env)` → streams `pio run -e <env>` to a build console with
  ANSI handling, error parsing (file:line jump-to-source), and cancel. Add ccache.

**Phase 4 — USB + Flash.**
- `usb-serial-for-android` device discovery, USB permission flow (intent filter +
  runtime grant), driver auto-detect. RFC2217 server. `upload(env)` →
  `pio run -e <env> -t upload --upload-port rfc2217://127.0.0.1:PORT`. Surface
  download-mode failures with the "hold BOOT" hint. Support manual chip override.

**Phase 5 — Serial monitor.**
- Native monitor reading directly from the USB driver (don't route the monitor through
  the container — simpler/faster): adjustable baud, timestamping, autoscroll, send line,
  hex view, line ending selector. Must coordinate port ownership with the flasher
  (release before upload, reattach after reset).

**Phase 6 — Git / GitHub.**
- JGit clone/pull/commit/push, branch list, diff view, staged/unstaged. PAT storage in
  EncryptedSharedPreferences/Keystore. Clone-into-project flow → opens in editor.

**Phase 7 — Polish.**
- First-run wizard (bootstrap progress), error taxonomy with actionable messages,
  build/flash status notifications, dark theme, settings (baud, upload speed, ccache,
  rootfs management/reset), basic onboarding for USB-OTG cable requirements.

---

## 7. Component Specs (the non-obvious parts)

### 7.1 RFC2217 bridge (critical)
- Implement the subset of RFC2217 that pyserial's `rfc2217://` client uses: Telnet
  negotiation, `COM-PORT-OPTION` SET_BAUDRATE, **SET_CONTROL (DTR on/off, RTS on/off)**,
  PURGE_DATA, and transparent data passthrough (with IAC `0xFF` byte-stuffing).
- Map SET_CONTROL DTR/RTS to `UsbSerialPort.setDTR()/setRTS()`. **The ESP32 auto-reset
  sequence depends entirely on this** — verify against esptool's classic reset
  (`DTR/RTS` wiggle) and the ESP32-S3/C3 USB-CDC/JTAG case.
- Single-client localhost server; bind ephemeral port; pass the chosen port into the
  container command.

### 7.2 Container command runner
- Always launch guest binaries via proot (never direct exec). Provide env: `HOME`,
  `PATH` including `~/.platformio/penv/bin`, `PLATFORMIO_CORE_DIR`. Bind-mount the
  project dir into the container so build artifacts land on app-visible storage.

### 7.3 USB device & permission
- `AndroidManifest`: `<uses-feature android:name="android.hardware.usb.host"/>` and an
  intent filter for `USB_DEVICE_ATTACHED` with a `device_filter.xml` covering common
  VID/PIDs (Silabs CP210x, WCH CH340, FTDI, espressif native USB). Handle runtime
  `UsbManager.requestPermission`.

### 7.4 PlatformIO integration
- Drive via CLI, parse JSON where available (`pio project config`, `pio boards`,
  `pio run` with `--json-output` where supported). Cache board/platform metadata for
  offline use. Expose targets: build, upload, clean, monitor, erase-flash.

---

## 8. UX Flows (minimum)
- **First run:** wizard → bootstrap container (progress %, resumable) → "ready".
- **New/Open project:** template (blink) or SAF folder pick or GitHub clone.
- **Edit → Build:** tap Build → streamed console → green/red result + jump to error.
- **Flash:** plug board → permission prompt → tap Flash → progress → success/reset.
- **Monitor:** tap Monitor → live log; auto-pause during flash.

---

## 9. Testing
- Unit: platformio.ini parser, RFC2217 framing/escaping, ANSI/error parsing.
- Instrumented: USB enumeration mock, container exec smoke test.
- Manual matrix (document results): ESP32-WROOM (CP2102), ESP32-S3 (native USB-CDC),
  ESP32-C3 (CH340) — build + flash + monitor each.

---

## 10. Known Risks & Mitigations (track these openly)
- **proot exec blocked on some Android builds** → Phase 0 Spike A gate; fallback design
  note: Termux `RUN_COMMAND` backend mode.
- **Missing `linux_aarch64` toolchain for a target chip** → detect, message clearly,
  document supported chip list per release.
- **Build speed (ptrace overhead)** → ccache, incremental builds, "build only changed
  env", keep expectations realistic in the UI.
- **USB port ownership conflicts (monitor vs flash)** → single `UsbPortBroker` that
  serializes access.
- **Storage pressure (~1–2 GB)** → preflight free-space check, rootfs reset/clear in
  settings, allow moving to adoptable storage where possible.
- **Auto-reset variance (S3/C3 USB-CDC vs UART bridge)** → configurable reset strategy,
  expose esptool `--before/--after` and the "hold BOOT" manual path.

---

## 11. Out of Scope (v1)
- iOS, armeabi-v7a, cloud/CI build, debugging (gdb/JTAG), OTA flashing, multi-device
  parallel flash, Play Store distribution.

---

## 12. How to Work
1. Start with **Phase 0 spikes**; report pass/fail with logs before building UI.
2. Keep the proot/rootfs and USB/RFC2217 layers behind clean interfaces so either can
   be swapped without touching UI.
3. Commit per milestone with clear messages; after each phase give me a 3-line status
   (done / proven on device / next).
4. When a decision has real trade-offs (rootfs distro, editor grammar engine, reset
   strategy), pause and ask rather than guessing.
5. Prefer correctness on real hardware over feature breadth. A reliable build→flash→
   monitor loop on one board beats a broad half-working UI.

Begin with Phase 0.
