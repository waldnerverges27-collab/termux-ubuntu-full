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
DEBOOTSTRAP_BIN="$(command -v debootstrap 2>/dev/null || true)"
if [[ -z "$DEBOOTSTRAP_BIN" && -x /usr/sbin/debootstrap ]]; then
  DEBOOTSTRAP_BIN=/usr/sbin/debootstrap
fi
QEMU_BIN="$(command -v qemu-aarch64-static 2>/dev/null || true)"
if [[ -z "$QEMU_BIN" && -x /usr/bin/qemu-aarch64-static ]]; then
  QEMU_BIN=/usr/bin/qemu-aarch64-static
fi
if [[ -z "$DEBOOTSTRAP_BIN" ]]; then echo "Missing host prerequisite: debootstrap" >&2; exit 1; fi
if [[ -z "$QEMU_BIN" ]]; then echo "Missing host prerequisite: qemu-aarch64-static" >&2; exit 1; fi
KEYRING="${UBUNTU_ARCHIVE_KEYRING:-/usr/share/keyrings/ubuntu-archive-keyring.gpg}"
if [[ ! -s "$KEYRING" ]] || ! command -v gpg >/dev/null 2>&1 || ! command -v gpgv >/dev/null 2>&1; then
  echo "Missing trusted Ubuntu archive keyring, gpg or gpgv at $KEYRING. Run setup_android_env.sh first." >&2
  exit 1
fi
KEY_FINGERPRINTS="$(gpg --batch --show-keys --with-colons "$KEYRING" 2>/dev/null | awk -F: '$1 == "fpr" {print $10}')"
if ! grep -Fxq '790BC7277767219C42C86F933B4FE6ACC0B21F32' <<< "$KEY_FINGERPRINTS" ||
   ! grep -Fxq 'F6ECB3762474EDA9D21B7022871920D1991BC93C' <<< "$KEY_FINGERPRINTS"; then
  echo "Ubuntu archive keyring is missing the trusted 2012/2018 archive signing keys." >&2
  exit 1
fi

sudo rm -rf -- "$ROOTFS"
sudo mkdir -p "$ROOTFS"
sudo "$DEBOOTSTRAP_BIN" --keyring="$KEYRING" --arch="$ARCH" --foreign --variant=minbase \
  --components=main,restricted,universe,multiverse \
  "$SUITE" "$ROOTFS" "$MIRROR"

sudo install -D -m 0755 "$QEMU_BIN" "$ROOTFS/usr/bin/qemu-aarch64-static"
sudo cp -L /etc/resolv.conf "$ROOTFS/etc/resolv.conf"

ROOTFS="$ROOTFS" bash "$SCRIPT_DIR/resume-rootfs.sh"
