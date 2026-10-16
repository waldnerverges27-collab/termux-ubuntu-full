#!/usr/bin/env bash
# Complete and package an interrupted Ubuntu ARM64 debootstrap tree.
set -Eeuo pipefail

ROOTFS="${ROOTFS:-/opt/ubuntu-rootfs}"
TARBALL="${TARBALL:-/opt/ubuntu-rootfs.tar.xz}"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

if [[ "$ROOTFS" != /opt/ubuntu-rootfs || ! -d "$ROOTFS" ]]; then
  echo "Expected an existing ARM64 rootfs at /opt/ubuntu-rootfs." >&2
  exit 1
fi

if [[ -f "$ROOTFS/etc/termux-ubuntu-rootfs" ]]; then
  echo "Rootfs is already configured; packaging it."
  ROOTFS="$ROOTFS" TARBALL="$TARBALL" bash "$SCRIPT_DIR/package-rootfs.sh"
  exit 0
fi
if [[ ! -x "$ROOTFS/debootstrap/debootstrap" ]]; then
  echo "The rootfs has no debootstrap second-stage program; rebuild it with build-rootfs.sh." >&2
  exit 1
fi
if ! command -v qemu-aarch64-static >/dev/null 2>&1; then
  echo "Missing host prerequisite: qemu-aarch64-static" >&2
  exit 1
fi

sudo install -D -m 0755 "$(command -v qemu-aarch64-static)" \
  "$ROOTFS/usr/bin/qemu-aarch64-static"
sudo cp -L /etc/resolv.conf "$ROOTFS/etc/resolv.conf"
for directory in dev proc sys; do
  sudo mkdir -p "$ROOTFS/$directory"
done

cleanup_mounts() {
  for directory in dev proc sys; do
    if mountpoint -q "$ROOTFS/$directory"; then
      sudo umount -R "$ROOTFS/$directory" 2>/dev/null || true
    fi
  done
}
cleanup_mounts

mount_rootfs() {
  local directory
  for directory in dev proc sys; do
    sudo mount --rbind "/$directory" "$ROOTFS/$directory"
    sudo mount --make-rslave "$ROOTFS/$directory"
  done
}

mounted=0
cleanup() {
  if [[ "$mounted" -eq 1 ]]; then cleanup_mounts; fi
}
trap cleanup EXIT

if [[ -x /usr/lib/systemd/systemd-binfmt ]]; then
  sudo /usr/lib/systemd/systemd-binfmt || true
fi
mount_rootfs
mounted=1

sudo chroot "$ROOTFS" /debootstrap/debootstrap --second-stage
sudo install -m 0755 "$SCRIPT_DIR/configure-rootfs.sh" \
  "$ROOTFS/tmp/termux-configure-rootfs.sh"
sudo chroot "$ROOTFS" /usr/bin/env DEBIAN_FRONTEND=noninteractive \
  /bin/bash /tmp/termux-configure-rootfs.sh
sudo rm -f "$ROOTFS/tmp/termux-configure-rootfs.sh"

cleanup_mounts
mounted=0
trap - EXIT
ROOTFS="$ROOTFS" TARBALL="$TARBALL" bash "$SCRIPT_DIR/package-rootfs.sh"
