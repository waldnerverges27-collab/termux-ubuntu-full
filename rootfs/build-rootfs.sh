#!/usr/bin/env bash
# Bootstrap a clean Ubuntu 26.04 ARM64 rootfs under /opt.
# For an interrupted build, use resume-rootfs.sh instead of rerunning this script.
set -Eeuo pipefail

SUITE="${SUITE:-resolute}"
ARCH="arm64"
MIRROR="${MIRROR:-https://ports.ubuntu.com/ubuntu-ports}"
ROOTFS="${ROOTFS:-/opt/ubuntu-rootfs}"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

if [[ "$ROOTFS" != /opt/ubuntu-rootfs ]]; then
  echo "Refusing unexpected rootfs path: $ROOTFS (expected /opt/ubuntu-rootfs)." >&2
  exit 1
fi
for command in debootstrap qemu-aarch64-static; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "Missing host prerequisite: $command" >&2
    exit 1
  fi
done

sudo rm -rf -- "$ROOTFS"
sudo mkdir -p "$ROOTFS"
sudo debootstrap --arch="$ARCH" --foreign --variant=minbase \
  --components=main,restricted,universe,multiverse \
  "$SUITE" "$ROOTFS" "$MIRROR"

sudo install -D -m 0755 "$(command -v qemu-aarch64-static)" \
  "$ROOTFS/usr/bin/qemu-aarch64-static"
sudo cp -L /etc/resolv.conf "$ROOTFS/etc/resolv.conf"

ROOTFS="$ROOTFS" bash "$SCRIPT_DIR/resume-rootfs.sh"
