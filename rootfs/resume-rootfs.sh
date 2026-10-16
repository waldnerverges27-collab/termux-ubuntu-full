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

for directory in dev proc sys; do
  sudo mkdir -p "$ROOTFS/$directory"
done

cleanup_mounts() {
  local directory
  for directory in dev proc sys; do
    if mountpoint -q "$ROOTFS/$directory"; then
      sudo umount -R "$ROOTFS/$directory" 2>/dev/null || true
    fi
  done
}

if [[ -f "$ROOTFS/etc/termux-ubuntu-rootfs" ]]; then
  cleanup_mounts
  echo "Rootfs is already configured; packaging it."
  ROOTFS="$ROOTFS" TARBALL="$TARBALL" bash "$SCRIPT_DIR/package-rootfs.sh"
  exit 0
fi

QEMU_BIN="$(command -v qemu-aarch64-static 2>/dev/null || true)"
if [[ -z "$QEMU_BIN" && -x /usr/bin/qemu-aarch64-static ]]; then
  QEMU_BIN=/usr/bin/qemu-aarch64-static
fi
if [[ -z "$QEMU_BIN" ]]; then
  echo "Missing host prerequisite: qemu-aarch64-static" >&2
  exit 1
fi
sudo install -D -m 0755 "$QEMU_BIN" "$ROOTFS/usr/bin/qemu-aarch64-static"
sudo cp -L /etc/resolv.conf "$ROOTFS/etc/resolv.conf"
cleanup_mounts

mount_rootfs() {
  local directory
  for directory in dev proc sys; do
    if ! mountpoint -q "$ROOTFS/$directory"; then
      if [[ "$directory" == proc ]]; then
        sudo mount -t proc proc "$ROOTFS/proc"
      else
        sudo mount --rbind "/$directory" "$ROOTFS/$directory"
      fi
      sudo mount --make-rslave "$ROOTFS/$directory"
    fi
  done
}

mounted=1
cleanup() {
  if [[ "$mounted" -eq 1 ]]; then cleanup_mounts; fi
}
trap cleanup EXIT

if [[ -x /usr/lib/systemd/systemd-binfmt ]]; then
  sudo /usr/lib/systemd/systemd-binfmt || true
fi
mount_rootfs

if [[ -x "$ROOTFS/debootstrap/debootstrap" ]]; then
  sudo chroot "$ROOTFS" /debootstrap/debootstrap --second-stage
elif [[ ! -s "$ROOTFS/var/lib/dpkg/status" || ! -x "$ROOTFS/usr/bin/dpkg" ]]; then
  echo "Rootfs is neither resumable nor a configured dpkg tree; rebuild it." >&2
  exit 1
fi

# debootstrap may detach /proc after its second stage; restore pseudo-filesystems
# before resuming pending package scripts (Java's postinst requires /proc).
mount_rootfs
sudo chroot "$ROOTFS" /usr/bin/qemu-aarch64-static -L / /usr/bin/dpkg --configure -a
sudo install -m 0755 "$SCRIPT_DIR/configure-rootfs.sh" \
  "$ROOTFS/tmp/termux-configure-rootfs.sh"
sudo chroot "$ROOTFS" /usr/bin/env DEBIAN_FRONTEND=noninteractive \
  /bin/bash /tmp/termux-configure-rootfs.sh
sudo rm -f "$ROOTFS/tmp/termux-configure-rootfs.sh"

cleanup_mounts
mounted=0
trap - EXIT
ROOTFS="$ROOTFS" TARBALL="$TARBALL" bash "$SCRIPT_DIR/package-rootfs.sh"
