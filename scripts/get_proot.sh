#!/usr/bin/env bash
# get_proot.sh — Download Termux's Android-patched proot and its shared-library dependencies.
#
# WHY Termux's proot instead of proot-me's static binary:
#   proot-me extracts a "loader" ELF to PROOT_TMP_DIR (inside app filesDir) then exec's it.
#   Android 10+ assigns app_data_file SELinux label to filesDir, which blocks execve().
#   Termux's fork injects the loader via /proc/self/mem instead, bypassing the restriction.
#
# The Termux proot binary is dynamically linked against /system/bin/linker64 (Android's own
# dynamic linker) and needs two Termux-specific shared libraries:
#   - libtalloc.so.2    (memory allocator)
#   - libandroid-shmem.so (POSIX shm shim)
# Both are bundled as lib*.so in jniLibs so Android installs them to nativeLibraryDir.
# ContainerManager creates a libtalloc.so.2 symlink at runtime and sets LD_LIBRARY_PATH.
#
# Usage: ./scripts/get_proot.sh
set -euo pipefail

DEST_DIR="app/src/main/jniLibs/arm64-v8a"
BASE="https://packages.termux.dev/apt/termux-main/pool/main"

TMPDIR=$(mktemp -d)
trap "rm -rf $TMPDIR" EXIT
mkdir -p "$TMPDIR/proot" "$TMPDIR/talloc" "$TMPDIR/shmem"

mkdir -p "$DEST_DIR"

echo "[get_proot] Downloading Termux proot 5.1.107.81 + dependencies for aarch64…"
curl -fsSL --progress-bar \
    "$BASE/p/proot/proot_5.1.107.81_aarch64.deb" \
    -o "$TMPDIR/proot.deb"
curl -fsSL --progress-bar \
    "$BASE/libt/libtalloc/libtalloc_2.4.3_aarch64.deb" \
    -o "$TMPDIR/libtalloc.deb"
curl -fsSL --progress-bar \
    "$BASE/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb" \
    -o "$TMPDIR/libandroid-shmem.deb"

echo "[get_proot] Extracting binaries…"

# proot binary -> libproot.so
ar p "$TMPDIR/proot.deb" data.tar.xz | \
    tar -xJ -C "$TMPDIR/proot" ./data/data/com.termux/files/usr/bin/proot
cp "$TMPDIR/proot/data/data/com.termux/files/usr/bin/proot" "$DEST_DIR/libproot.so"
chmod +x "$DEST_DIR/libproot.so"
echo "  ✓ libproot.so  ($(wc -c < "$DEST_DIR/libproot.so") bytes)"

# libtalloc: actual ELF is libtalloc.so.2.4.3 (soname: libtalloc.so.2).
# Android only installs lib*.so files from jniLibs, so we rename it to libtalloc.so.
# ContainerManager creates a proot-libs/libtalloc.so.2 symlink at runtime so that
# /system/bin/linker64 can find it when loading proot.
ar p "$TMPDIR/libtalloc.deb" data.tar.xz | \
    tar -xJ -C "$TMPDIR/talloc" ./data/data/com.termux/files/usr/lib/libtalloc.so.2.4.3
cp "$TMPDIR/talloc/data/data/com.termux/files/usr/lib/libtalloc.so.2.4.3" \
    "$DEST_DIR/libtalloc.so"
chmod +x "$DEST_DIR/libtalloc.so"
echo "  ✓ libtalloc.so  ($(wc -c < "$DEST_DIR/libtalloc.so") bytes)"

# libandroid-shmem: soname matches filename, no rename needed
ar p "$TMPDIR/libandroid-shmem.deb" data.tar.xz | \
    tar -xJ -C "$TMPDIR/shmem" ./data/data/com.termux/files/usr/lib/libandroid-shmem.so
cp "$TMPDIR/shmem/data/data/com.termux/files/usr/lib/libandroid-shmem.so" \
    "$DEST_DIR/libandroid-shmem.so"
chmod +x "$DEST_DIR/libandroid-shmem.so"
echo "  ✓ libandroid-shmem.so  ($(wc -c < "$DEST_DIR/libandroid-shmem.so") bytes)"

echo ""
echo "[get_proot] Done! Files in $DEST_DIR/:"
ls -lh "$DEST_DIR/"*.so
echo ""
echo "[get_proot] ELF types:"
file "$DEST_DIR/"*.so
