#!/usr/bin/env bash
# Validate and archive the configured ARM64 guest as /opt/ubuntu-rootfs.tar.xz.
set -Eeuo pipefail

ROOTFS="${ROOTFS:-/opt/ubuntu-rootfs}"
TARBALL="${TARBALL:-/opt/ubuntu-rootfs.tar.xz}"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
if [[ "$ROOTFS" != /opt/ubuntu-rootfs || "$TARBALL" != /opt/ubuntu-rootfs.tar.xz ]]; then
  echo "Refusing unexpected rootfs or archive path." >&2
  exit 1
fi

MARKER="$ROOTFS/etc/termux-ubuntu-rootfs"
if [[ ! -f "$MARKER" ]] || ! grep -Fxq 'ARCH=arm64' "$MARKER"; then
  echo "Missing or non-ARM64 rootfs marker at $MARKER." >&2
  exit 1
fi
if [[ ! -x "$ROOTFS/usr/bin/bash" ]]; then
  echo "Ubuntu bash is missing from the rootfs." >&2
  exit 1
fi
if [[ -n "$(findmnt -rn -o TARGET | grep -E "^${ROOTFS}(/|$)" || true)" ]]; then
  echo "Rootfs still has mounted filesystems; unmount them before packaging." >&2
  exit 1
fi

# Audit package state before removing the temporary emulator. If it is absent
# (for example, after an interrupted package attempt), the host binfmt handler
# can execute the guest's ARM64 dpkg directly.
sudo rm -rf "$ROOTFS/var/lib/apt/lists"/* "$ROOTFS/var/cache/apt/archives"/* \
  "$ROOTFS/tmp"/* "$ROOTFS/var/tmp"/* "$ROOTFS/var/log"/* "$ROOTFS/var/crash"/*
if [[ -x "$ROOTFS/usr/bin/qemu-aarch64-static" ]]; then
  AUDIT="$(sudo chroot "$ROOTFS" /usr/bin/qemu-aarch64-static -L / /usr/bin/dpkg --audit || true)"
  ARCH="$(sudo chroot "$ROOTFS" /usr/bin/qemu-aarch64-static -L / /usr/bin/dpkg --print-architecture)"
else
  AUDIT="$(sudo chroot "$ROOTFS" /usr/bin/dpkg --audit || true)"
  ARCH="$(sudo chroot "$ROOTFS" /usr/bin/dpkg --print-architecture)"
fi
if [[ -n "$AUDIT" ]]; then
  printf 'dpkg audit found incomplete package configuration:\n%s\n' "$AUDIT" >&2
  exit 1
fi
if [[ "$ARCH" != arm64 ]]; then
  echo "dpkg reports $ARCH, expected arm64." >&2
  exit 1
fi

# Remove the host-only emulator after validation so it is never bundled.
sudo rm -f "$ROOTFS/usr/bin/qemu-aarch64-static"
# The APK extracts as an unprivileged Android app: normalize hard links and
# discard /dev device nodes before creating the archive.
sudo python3 "$SCRIPT_DIR/normalize-rootfs-for-android.py" "$ROOTFS"
sudo rm -f "$TARBALL.tmp"
sudo env XZ_OPT='-1 -T0' tar --numeric-owner --xattrs --acls \
  -cJf "$TARBALL.tmp" -C /opt ubuntu-rootfs
sudo mv -f "$TARBALL.tmp" "$TARBALL"
sudo chown "$(id -un):$(id -gn)" "$TARBALL"
sha256sum "$TARBALL" | awk '{print $1}' | sudo tee "$TARBALL.sha256" >/dev/null
sudo chown "$(id -un):$(id -gn)" "$TARBALL.sha256"

ARCHIVE_MARKER="$(tar -xJOf "$TARBALL" ubuntu-rootfs/etc/termux-ubuntu-rootfs)"
if ! grep -Fxq 'ARCH=arm64' <<< "$ARCHIVE_MARKER"; then
  echo "Packaged archive failed its ARM64 marker check." >&2
  exit 1
fi
bash "$(dirname -- "${BASH_SOURCE[0]}")/generate-rootfs-info.sh" "$TARBALL"
printf 'Rootfs archive: %s (%s)\n' "$TARBALL" "$(stat -c '%s bytes' "$TARBALL")"
printf 'Rootfs SHA-256: %s\n' "$(awk '{print $1}' "$TARBALL.sha256")"
printf 'Expanded rootfs size: %s\n' "$(sudo du -shx "$ROOTFS" | awk '{print $1}')"

# The validated archive is now the recovery source; remove the multi-GB expanded tree.
sudo rm -rf -- "$ROOTFS"
