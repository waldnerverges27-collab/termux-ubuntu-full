#!/usr/bin/env bash
# Build Ubuntu 25.04 rootfs with full dev toolset for Termux/proot.
# Run on a Debian/Ubuntu host with debootstrap, proot, chroot.
set -euo pipefail

SUITE=plucky
ARCH=amd64
MIRROR=http://archive.ubuntu.com/ubuntu
ROOTFS=/opt/ubuntu-rootfs
TARBALL=/opt/ubuntu-rootfs.tar.xz

sudo rm -rf "$ROOTFS"
sudo debootstrap --arch=$ARCH --components=main,restricted,universe,multiverse $SUITE "$ROOTFS" $MIRROR

sudo mount --bind /dev "$ROOTFS/dev"
sudo mount --bind /proc "$ROOTFS/proc"
sudo mount --bind /sys "$ROOTFS/sys"

sudo chroot "$ROOTFS" /bin/bash -c '
set -e
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends \
  build-essential git curl wget rsync openssh-client tmux htop vim nano \
  python3 python3-pip python3-venv python3-dev \
  nodejs npm \
  golang-go \
  openjdk-21-jdk maven \
  cmake make clang gdb strace lsof \
  net-tools iproute2 dnsutils \
  zip unzip tar xz-utils ca-certificates locales tzdata \
  bash-completion jq tree ripgrep fd-find bat fzf zsh fish btop ncdu httpie

# Latest Gradle
cd /tmp
wget -q https://services.gradle.org/distributions/gradle-9.2.1-bin.zip -O gradle.zip
mkdir -p /opt/gradle
unzip -q gradle.zip -d /opt/gradle
ln -sfn /opt/gradle/gradle-9.2.1 /opt/gradle/current
ln -sfn /opt/gradle/current/bin/gradle /usr/local/bin/gradle
rm -f /tmp/gradle.zip

# Latest Node (LTS)
cd /tmp
wget -q https://nodejs.org/dist/v22.14.0/node-v22.14.0-linux-x64.tar.xz -O node.tar.xz
tar -xf node.tar.xz -C /usr/local --strip-components=1
rm -f /tmp/node.tar.xz

# Latest Rust via rustup
curl -sSf https://sh.rustup.rs | RUSTUP_HOME=/opt/rustup CARGO_HOME=/opt/cargo sh -s -- -y --default-toolchain stable --profile default
RUSTUP_HOME=/opt/rustup CARGO_HOME=/opt/cargo /opt/cargo/bin/rustup default stable

# Env profiles
cat > /etc/profile.d/gradle.sh <<EOF
export GRADLE_HOME=/opt/gradle/current
export PATH=/opt/gradle/current/bin:\$PATH
EOF
cat > /etc/profile.d/rust.sh <<EOF
export RUSTUP_HOME=/opt/rustup
export CARGO_HOME=/opt/cargo
export PATH=/opt/cargo/bin:\$PATH
EOF
cat > /etc/profile.d/go.sh <<EOF
export GOROOT=/usr/lib/go
export PATH=/usr/lib/go/bin:\$PATH
EOF
cat > /etc/ld.so.conf.d/openjdk.conf <<EOF
/usr/lib/jvm/java-21-openjdk-amd64/lib
/usr/lib/jvm/java-21-openjdk-amd64/lib/server
EOF
ldconfig

# Cleanup
apt-get clean
rm -rf /var/cache/apt/archives/* /var/lib/apt/lists/* /tmp/* /var/tmp/* /var/log/* /var/crash/*
rm -rf /usr/share/doc/* /usr/share/man/* /usr/share/info/*
find /usr/share/locale -mindepth 1 -maxdepth 1 ! -name "en*" -exec rm -rf {} + 2>/dev/null || true
'

sudo umount "$ROOTFS/dev" "$ROOTFS/proc" "$ROOTFS/sys"
sudo rm -f "$TARBALL"
sudo env XZ_OPT="-1 -T0" tar -cJf "$TARBALL" -C /opt ubuntu-rootfs
echo "Created $TARBALL ($(du -h $TARBALL | cut -f1))"
