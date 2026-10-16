#!/usr/bin/env bash
# Configure a debootstrapped Ubuntu 26.04 ARM64 guest. Run inside its chroot as root.
set -Eeuo pipefail

export DEBIAN_FRONTEND=noninteractive
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

if [[ "$(dpkg --print-architecture)" != arm64 ]]; then
  echo "Refusing to configure a non-ARM64 rootfs." >&2
  exit 1
fi

# Prevent packages from attempting to start services in a proot guest.
POLICY_CREATED=0
if [[ ! -e /usr/sbin/policy-rc.d ]]; then
  printf '#!/bin/sh\nexit 101\n' > /usr/sbin/policy-rc.d
  chmod 0755 /usr/sbin/policy-rc.d
  POLICY_CREATED=1
fi
cleanup_policy() {
  if [[ "$POLICY_CREATED" -eq 1 ]]; then rm -f /usr/sbin/policy-rc.d; fi
}
trap cleanup_policy EXIT

apt-get update
apt-get full-upgrade -y
apt-get install -y --no-install-recommends \
  bash-completion bat btop build-essential ca-certificates clang cmake curl \
  dnsutils fd-find fish fzf gdb git htop httpie jq lsof locales make maven \
  ncdu net-tools openssh-client procps python3 python3-dev python3-pip \
  python3-venv ripgrep rsync strace tar tmux tree tzdata unzip vim wget \
  xz-utils zip zsh default-jdk

# Configure a UTF-8 locale and a predictable shell environment.
printf 'en_US.UTF-8 UTF-8\n' > /etc/locale.gen
locale-gen en_US.UTF-8
update-locale LANG=C.UTF-8

# Install the current Node.js LTS release from the official ARM64 distribution.
NODE_VERSION="$(curl -fsSL https://nodejs.org/dist/index.json | python3 -c '
import json, sys
releases = json.load(sys.stdin)
release = next(item for item in releases if item.get("lts") and "linux-arm64" in item.get("files", []))
print(release["version"])
')"
NODE_ARCHIVE="node-${NODE_VERSION}-linux-arm64.tar.xz"
NODE_BASE="https://nodejs.org/dist/${NODE_VERSION}"
curl -fsSL "${NODE_BASE}/${NODE_ARCHIVE}" -o /tmp/node.tar.xz
NODE_SHA256="$(curl -fsSL "${NODE_BASE}/SHASUMS256.txt" | awk -v file="$NODE_ARCHIVE" '$2 == file {print $1}')"
if [[ -z "$NODE_SHA256" ]]; then echo "Node.js archive checksum not found." >&2; exit 1; fi
printf '%s  %s\n' "$NODE_SHA256" /tmp/node.tar.xz | sha256sum -c -
tar -xJf /tmp/node.tar.xz -C /usr/local --strip-components=1
rm -f /tmp/node.tar.xz

# Install the latest stable Go ARM64 archive and verify Google's published digest.
read -r GO_VERSION GO_ARCHIVE GO_SHA256 < <(curl -fsSL 'https://go.dev/dl/?mode=json' | python3 -c '
import json, sys
release = json.load(sys.stdin)[0]
archive = next(item for item in release["files"]
               if item["os"] == "linux" and item["arch"] == "arm64" and item["kind"] == "archive")
print(release["version"], archive["filename"], archive["sha256"])
')
curl -fsSL "https://go.dev/dl/${GO_ARCHIVE}" -o /tmp/go.tar.gz
printf '%s  %s\n' "$GO_SHA256" /tmp/go.tar.gz | sha256sum -c -
rm -rf /usr/local/go
tar -xzf /tmp/go.tar.gz -C /usr/local
rm -f /tmp/go.tar.gz
export GOROOT=/usr/local/go
export PATH="$GOROOT/bin:$PATH"

# Install the current Gradle distribution from the official version metadata.
read -r GRADLE_VERSION GRADLE_URL GRADLE_SHA256 < <(curl -fsSL https://services.gradle.org/versions/current | python3 -c '
import json, sys
release = json.load(sys.stdin)
print(release["version"], release["downloadUrl"], release["checksum"])
')
curl -fsSL "$GRADLE_URL" -o /tmp/gradle.zip
printf '%s  %s\n' "$GRADLE_SHA256" /tmp/gradle.zip | sha256sum -c -
mkdir -p /opt/gradle
unzip -q -o /tmp/gradle.zip -d /opt/gradle
ln -sfn "/opt/gradle/gradle-${GRADLE_VERSION}" /opt/gradle/current
ln -sfn /opt/gradle/current/bin/gradle /usr/local/bin/gradle
rm -f /tmp/gradle.zip

# Install the latest stable Rust toolchain for this ARM64 guest.
export RUSTUP_HOME=/opt/rustup
export CARGO_HOME=/opt/cargo
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs \
  | sh -s -- -y --default-toolchain stable --profile default
/opt/cargo/bin/rustup update stable
export PATH="$CARGO_HOME/bin:$PATH"

# Persist tool paths for interactive login shells.
cat > /etc/profile.d/termux-devtools.sh <<'PROFILE'
export GRADLE_HOME=/opt/gradle/current
export RUSTUP_HOME=/opt/rustup
export CARGO_HOME=/opt/cargo
export GOROOT=/usr/local/go
export GOPATH="$HOME/go"
export PATH="$GRADLE_HOME/bin:$CARGO_HOME/bin:$GOROOT/bin:/usr/local/bin:$PATH"
PROFILE
JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
cat > /etc/profile.d/termux-java.sh <<PROFILE
export JAVA_HOME=${JAVA_HOME}
export PATH=\$JAVA_HOME/bin:\$PATH
PROFILE

# Record the actual tool versions for validation by the packager and installer.
NODE_VERSION_INSTALLED="$(node --version)"
GO_VERSION_INSTALLED="$(go version | awk '{print $3}')"
GRADLE_VERSION_INSTALLED="$(/opt/gradle/current/bin/gradle --version | awk '/^Gradle / {print $2; exit}')"
RUST_VERSION_INSTALLED="$(/opt/cargo/bin/rustc --version | awk '{print $2}')"
JAVA_VERSION_INSTALLED="$(java -version 2>&1 | sed -n '1s/.*version "\([^"]*\)".*/\1/p')"
UBUNTU_CODENAME="$(. /etc/os-release && printf '%s' "$VERSION_CODENAME")"
{
  echo 'FORMAT=1'
  echo 'DISTRIBUTION=Ubuntu'
  echo "CODENAME=${UBUNTU_CODENAME}"
  echo 'ARCH=arm64'
  echo "BUILD_DATE=$(date -u +%F)"
  echo "NODE_VERSION=${NODE_VERSION_INSTALLED}"
  echo "GO_VERSION=${GO_VERSION_INSTALLED}"
  echo "GRADLE_VERSION=${GRADLE_VERSION_INSTALLED}"
  echo "RUST_VERSION=${RUST_VERSION_INSTALLED}"
  echo "JAVA_VERSION=${JAVA_VERSION_INSTALLED}"
} > /etc/termux-ubuntu-rootfs

if [[ "$POLICY_CREATED" -eq 1 ]]; then rm -f /usr/sbin/policy-rc.d; fi
apt-get clean
rm -rf /var/lib/apt/lists/* /var/cache/apt/archives/* /tmp/* /var/tmp/*
rm -rf /usr/share/doc/* /usr/share/man/* /usr/share/info/* /var/log/* /var/crash/*
find /usr/share/locale -mindepth 1 -maxdepth 1 ! -name 'en*' -exec rm -rf {} + 2>/dev/null || true

AUDIT="$(dpkg --audit || true)"
if [[ -n "$AUDIT" ]]; then
  printf 'dpkg audit found incomplete package configuration:\n%s\n' "$AUDIT" >&2
  exit 1
fi
printf 'Configured Ubuntu %s ARM64: Node %s, Go %s, Gradle %s, Rust %s, Java %s\n' \
  "$UBUNTU_CODENAME" "$NODE_VERSION_INSTALLED" "$GO_VERSION_INSTALLED" \
  "$GRADLE_VERSION_INSTALLED" "$RUST_VERSION_INSTALLED" "$JAVA_VERSION_INSTALLED"
