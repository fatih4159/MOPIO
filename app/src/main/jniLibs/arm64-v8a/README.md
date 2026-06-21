# libproot.so — Required for Phase 0

Place a **static aarch64 proot binary** here named `libproot.so`.

Android extracts `.so` files from `nativeLibraryDir` at install time and allows
executing them (unlike other writable directories that are blocked by W^X).
This is how Termux ships proot.

## How to obtain

### Option A — Build from source (recommended for production)
```bash
git clone https://github.com/proot-me/proot
cd proot/src
# Cross-compile for aarch64 with static linking
make CC=aarch64-linux-gnu-gcc CFLAGS="-static" proot
cp proot ../../libproot.so
```

### Option B — Extract from Termux package (quickest for testing)
On a rooted Android device or in a Termux session:
```bash
pkg install proot
cp $(which proot) /path/to/project/app/src/main/jniLibs/arm64-v8a/libproot.so
```
Then pull it to your workstation:
```bash
adb pull /data/data/com.termux/files/usr/bin/proot ./libproot.so
```

## Verification
The Phase 0 Spike A screen (`ContainerSmokeTest.prootBinary_isExecutable`) verifies
that proot can actually be executed on the target Android version before proceeding.
