#!/usr/bin/env bash
# Idempotent Android + native ARM64 Ubuntu build setup.
# Run after a session restart: bash ~/setup_android_env.sh
set -Eeuo pipefail

# Minimal build containers may omit the system-administration directories.
export PATH="/usr/local/sbin:/usr/sbin:/sbin:$PATH"

SWAP_SIZE_GB=8
ANDROID_SDK=/opt/android-sdk
GRADLE_DIR=/opt/gradle
GRADLE_CACHE=/opt/gradle-cache
ANDROID_USER_HOME=/opt/android-user
TMP_DIR=/opt/tmp
ROOTFS_TARBALL=/opt/ubuntu-rootfs.tar.xz
ROOTFS_DIR=/opt/ubuntu-rootfs
PROOT_ASSET_BACKUP_DIR=/opt/termux-assets
PROJECT_DIR=/opt/work/work/termux-app
GIT_DIR=/opt/work/.termux-repo
REPOSITORY_URL=https://github.com/waldnerverges27-collab/termux-ubuntu-full.git

log() { printf '[setup] %s\n' "$*"; }

sudo mkdir -p "$TMP_DIR" "$GRADLE_DIR" "$GRADLE_CACHE" "$ANDROID_USER_HOME" "$ANDROID_SDK"
sudo chown -R "$USER:$USER" "$TMP_DIR" "$GRADLE_DIR" "$GRADLE_CACHE" "$ANDROID_USER_HOME" "$ANDROID_SDK"
export TMPDIR="$TMP_DIR"
export JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$TMP_DIR"

# 1. Optional swap for large Android/NDK builds.
if command -v swapon >/dev/null 2>&1 && ! swapon --show 2>/dev/null | grep -q '/swapfile'; then
  if [ ! -f /swapfile ]; then
    log "Creating ${SWAP_SIZE_GB}G swap file..."
    sudo fallocate -l "${SWAP_SIZE_GB}G" /swapfile
    sudo chmod 600 /swapfile
    sudo mkswap /swapfile >/dev/null
  fi
  sudo swapon /swapfile
fi
grep -q '^/swapfile' /etc/fstab 2>/dev/null || echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab >/dev/null

# 2. Host build prerequisites. JDK 21 is retained for Android Gradle-plugin
# compatibility; the bundled Ubuntu rootfs gets the current Ubuntu default JDK.
log "Installing host build and ARM64 rootfs prerequisites..."
sudo apt-get update -qq
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y \
  openjdk-21-jdk unzip wget curl python3 tar xz-utils gnupg gpgv \
  debootstrap qemu-user-static binfmt-support

# Debian hosts do not ship Ubuntu's archive keyring. Install the official
# keyring only after checking Ubuntu's published archive-key fingerprints.
UBUNTU_KEYRING=/usr/share/keyrings/ubuntu-archive-keyring.gpg
KEYRING_TMP="$TMP_DIR/ubuntu-archive-keyring.gpg"
KEYRING_VALID=0
if [ -s "$UBUNTU_KEYRING" ]; then
  KEYRING_FINGERPRINTS="$(gpg --batch --show-keys --with-colons "$UBUNTU_KEYRING" 2>/dev/null | awk -F: '$1 == "fpr" {print $10}')"
  if grep -Fxq 790BC7277767219C42C86F933B4FE6ACC0B21F32 <<< "$KEYRING_FINGERPRINTS" &&
     grep -Fxq F6ECB3762474EDA9D21B7022871920D1991BC93C <<< "$KEYRING_FINGERPRINTS"; then
    KEYRING_VALID=1
  fi
fi
if [ "$KEYRING_VALID" != 1 ]; then
  log "Installing and fingerprint-checking the official Ubuntu archive keyring..."
  curl -fsSL https://archive.ubuntu.com/ubuntu/project/ubuntu-archive-keyring.gpg -o "$KEYRING_TMP"
  KEYRING_FINGERPRINTS="$(gpg --batch --show-keys --with-colons "$KEYRING_TMP" | awk -F: '$1 == "fpr" {print $10}')"
  grep -Fxq 790BC7277767219C42C86F933B4FE6ACC0B21F32 <<< "$KEYRING_FINGERPRINTS"
  grep -Fxq F6ECB3762474EDA9D21B7022871920D1991BC93C <<< "$KEYRING_FINGERPRINTS"
  sudo install -D -m 0644 "$KEYRING_TMP" "$UBUNTU_KEYRING"
fi
rm -f "$KEYRING_TMP"
sudo apt-get clean
sudo rm -rf /var/lib/apt/lists/*

# 3. Private project checkout. Prefer the existing separate Git directory so
# recovery does not need credentials. If it is gone, provide GITHUB_TOKEN as an
# environment variable with read/write access; no token is stored in this script.
if [ ! -e "$PROJECT_DIR/.git" ]; then
  sudo mkdir -p /opt/work "$(dirname "$PROJECT_DIR")"
  sudo chown -R "$USER:$USER" /opt/work
  if [ -d "$GIT_DIR" ]; then
    git --git-dir="$GIT_DIR" worktree prune
    git --git-dir="$GIT_DIR" worktree add "$PROJECT_DIR" main
  elif [ -n "${GITHUB_TOKEN:-}" ]; then
    ASKPASS="$TMP_DIR/github-askpass.sh"
    cat > "$ASKPASS" <<'ASKPASS_SCRIPT'
#!/usr/bin/env sh
case "$1" in
  *Username*) printf '%s\n' 'x-access-token' ;;
  *Password*) printf '%s\n' "$GITHUB_TOKEN" ;;
  *) exit 1 ;;
esac
ASKPASS_SCRIPT
    chmod 700 "$ASKPASS"
    export GIT_ASKPASS="$ASKPASS"
    export GIT_TERMINAL_PROMPT=0
    git clone --separate-git-dir="$GIT_DIR" "$REPOSITORY_URL" "$PROJECT_DIR"
    rm -f "$ASKPASS"
    unset GIT_ASKPASS GIT_TERMINAL_PROMPT GITHUB_TOKEN
  else
    echo "Private repository checkout is missing. Restore /opt/work/.termux-repo or set GITHUB_TOKEN and rerun." >&2
    exit 1
  fi
fi

# Preserve the small Termux proot executable outside the disposable checkout.
# The APK's installer requires a native AArch64 Android ELF binary.
PROOT_ASSET_DIR="$PROJECT_DIR/app/src/main/assets"
PROOT_ASSET="$PROOT_ASSET_DIR/proot-aarch64"
PROOT_BACKUP="$PROOT_ASSET_BACKUP_DIR/proot-aarch64"
sudo mkdir -p "$PROOT_ASSET_BACKUP_DIR" "$PROOT_ASSET_DIR"
sudo chown "$USER:$USER" "$PROOT_ASSET_BACKUP_DIR"
if [ ! -s "$PROOT_BACKUP" ] && [ -s "$PROOT_ASSET" ]; then
  cp "$PROOT_ASSET" "$PROOT_BACKUP"
fi
if [ ! -s "$PROOT_BACKUP" ]; then
  echo "Missing ARM64 proot asset and recovery copy: $PROOT_ASSET" >&2
  exit 1
fi
if [ ! -s "$PROOT_BACKUP.sha256" ]; then
  sha256sum "$PROOT_BACKUP" | awk '{print $1}' > "$PROOT_BACKUP.sha256"
fi
PROOT_HASH="$(cat "$PROOT_BACKUP.sha256")"
if [ "$(sha256sum "$PROOT_BACKUP" | awk '{print $1}')" != "$PROOT_HASH" ]; then
  echo "Saved ARM64 proot asset failed SHA-256 validation." >&2
  exit 1
fi
if [ ! -s "$PROOT_ASSET" ] || ! cmp -s "$PROOT_BACKUP" "$PROOT_ASSET"; then
  cp "$PROOT_BACKUP" "$PROOT_ASSET.tmp"
  mv -f "$PROOT_ASSET.tmp" "$PROOT_ASSET"
fi
python3 - "$PROOT_ASSET" <<'PY'
import sys
header = open(sys.argv[1], 'rb').read(20)
if len(header) < 20 or header[:4] != b'\x7fELF' or header[4] != 2 or header[5] != 1 or int.from_bytes(header[18:20], 'little') != 183:
    raise SystemExit('proot asset is not a little-endian AArch64 ELF binary')
PY

# 4. Android command-line tools: resolve the latest Linux package from Google's
# official repository metadata rather than pinning an old download build ID.
read -r CMDLINE_TOOLS_VERSION CMDLINE_TOOLS_URL <<< "$(python3 - <<'PY'
import urllib.request
import xml.etree.ElementTree as ET
repo = ET.fromstring(urllib.request.urlopen(
    'https://dl.google.com/android/repository/repository2-1.xml', timeout=30
).read())
package = next(p for p in repo.findall('.//remotePackage')
               if p.get('path') == 'cmdline-tools;latest')
revision = package.find('revision')
version = '.'.join(x.text for x in revision if x.tag in ('major', 'minor', 'micro'))
archive = next(a for a in package.findall('./archives/archive')
               if a.findtext('host-os') == 'linux')
url = 'https://dl.google.com/android/repository/' + archive.findtext('./complete/url')
print(version, url)
PY
)"
cmdline_revision() {
  local properties="$1/source.properties"
  [ -r "$properties" ] || return 0
  sed -n 's/^Pkg.Revision=//p' "$properties" | head -1 || true
}
CURRENT_CMDLINE_VERSION="$(cmdline_revision "$ANDROID_SDK/cmdline-tools/latest")"
if [ "$CURRENT_CMDLINE_VERSION" != "$CMDLINE_TOOLS_VERSION" ]; then
  log "Installing Android command-line tools $CMDLINE_TOOLS_VERSION..."
  CANDIDATE="$ANDROID_SDK/cmdline-tools/latest-2"
  if [ "$(cmdline_revision "$CANDIDATE")" = "$CMDLINE_TOOLS_VERSION" ]; then
    sudo rm -rf "$ANDROID_SDK/cmdline-tools/latest"
    sudo mv "$CANDIDATE" "$ANDROID_SDK/cmdline-tools/latest"
  else
    mkdir -p "$ANDROID_SDK/cmdline-tools" "$TMP_DIR/android-cli"
    curl -fsSL "$CMDLINE_TOOLS_URL" -o "$TMP_DIR/cmdline-tools.zip"
    rm -rf "$TMP_DIR/android-cli" "$ANDROID_SDK/cmdline-tools/latest.new"
    mkdir -p "$TMP_DIR/android-cli"
    unzip -q "$TMP_DIR/cmdline-tools.zip" -d "$TMP_DIR/android-cli"
    mv "$TMP_DIR/android-cli/cmdline-tools" "$ANDROID_SDK/cmdline-tools/latest.new"
    sudo rm -rf "$ANDROID_SDK/cmdline-tools/latest"
    sudo mv "$ANDROID_SDK/cmdline-tools/latest.new" "$ANDROID_SDK/cmdline-tools/latest"
    rm -rf "$TMP_DIR/android-cli" "$TMP_DIR/cmdline-tools.zip"
  fi
fi

export ANDROID_HOME="$ANDROID_SDK"
export ANDROID_SDK_ROOT="$ANDROID_SDK"
export ANDROID_USER_HOME
printf '%s\n' --no-metrics > "$ANDROID_USER_HOME/.androidrc"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

JAVA_BIN="$(readlink -f "$(command -v javac)")"
JAVA_HOME="$(dirname "$(dirname "$JAVA_BIN")")"
export JAVA_HOME

# Match SDK/NDK versions pinned by this checkout so the app build is reproducible.
COMPILE_SDK="$(sed -n 's/^compileSdkVersion=//p' "$PROJECT_DIR/gradle.properties" | tail -1)"
NDK_VERSION="$(sed -n 's/^ndkVersion=//p' "$PROJECT_DIR/gradle.properties" | tail -1)"
: "${COMPILE_SDK:=36}"
: "${NDK_VERSION:=29.0.14206865}"

log "Installing required Android SDK components (Android CLI metrics disabled)..."
ANDROID_CLI="$ANDROID_HOME/cmdline-tools/latest/bin/android"
# The SDK manager's compatibility shim now delegates to Android CLI. Route its
# configuration under /opt and use .androidrc to disable telemetry.
yes | env HOME="$ANDROID_USER_HOME" ANDROID_USER_HOME="$ANDROID_USER_HOME" sdkmanager --licenses >/dev/null 2>&1 || true
env HOME="$ANDROID_USER_HOME" ANDROID_USER_HOME="$ANDROID_USER_HOME" \
  "$ANDROID_CLI" --no-metrics --sdk="$ANDROID_HOME" sdk install \
  "cmdline-tools;latest" \
  "platform-tools" \
  "platforms;android-${COMPILE_SDK}" \
  "build-tools;${COMPILE_SDK}.0.0" \
  "ndk;${NDK_VERSION}" >/dev/null

# 5. Install current stable Gradle (checksum verified). The project wrapper is
# still used by the build; this system Gradle is available for general use.
GRADLE_METADATA="$(curl -fsSL https://services.gradle.org/versions/current)"
read -r GRADLE_VERSION GRADLE_URL GRADLE_SHA256 <<< "$(python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["version"],d["downloadUrl"],d["checksum"])' <<< "$GRADLE_METADATA")"
INSTALLED_GRADLE=""
if [ -x "$GRADLE_DIR/current/bin/gradle" ]; then
  INSTALLED_GRADLE="$("$GRADLE_DIR/current/bin/gradle" --version | awk '/^Gradle / {print $2; exit}')"
fi
if [ "$INSTALLED_GRADLE" != "$GRADLE_VERSION" ]; then
  log "Installing Gradle $GRADLE_VERSION..."
  curl -fsSL "$GRADLE_URL" -o "$TMP_DIR/gradle.zip"
  printf '%s  %s\n' "$GRADLE_SHA256" "$TMP_DIR/gradle.zip" | sha256sum -c -
  unzip -q "$TMP_DIR/gradle.zip" -d "$GRADLE_DIR"
  ln -sfn "$GRADLE_DIR/gradle-$GRADLE_VERSION" "$GRADLE_DIR/current"
  rm -f "$TMP_DIR/gradle.zip"
fi

# 6. Build or restore the Ubuntu 26.04 native ARM64 archive outside the snapshot.
rootfs_archive_is_arm64() {
  local archive="$1"
  local marker
  [ -s "$archive" ] || return 1
  marker="$(tar -xJOf "$archive" ubuntu-rootfs/etc/termux-ubuntu-rootfs 2>/dev/null || true)"
  grep -Fxq 'ARCH=arm64' <<< "$marker"
}

if ! rootfs_archive_is_arm64 "$ROOTFS_TARBALL"; then
  if [ -f "$ROOTFS_DIR/etc/termux-ubuntu-rootfs" ] && grep -Fxq 'ARCH=arm64' "$ROOTFS_DIR/etc/termux-ubuntu-rootfs"; then
    log "Packaging the existing ARM64 rootfs..."
    (cd "$PROJECT_DIR" && bash rootfs/package-rootfs.sh)
  elif [ -x "$ROOTFS_DIR/usr/bin/dpkg" ] && [ -s "$ROOTFS_DIR/var/lib/dpkg/status" ]; then
    log "Resuming configuration of the existing ARM64 Ubuntu rootfs..."
    sudo /usr/lib/systemd/systemd-binfmt
    chmod +x "$PROJECT_DIR/rootfs/resume-rootfs.sh" "$PROJECT_DIR/rootfs/configure-rootfs.sh"
    (cd "$PROJECT_DIR" && ./rootfs/resume-rootfs.sh)
  else
    log "Building Ubuntu 26.04 ARM64 rootfs (this can take a while)..."
    sudo /usr/lib/systemd/systemd-binfmt
    chmod +x "$PROJECT_DIR/rootfs/build-rootfs.sh" "$PROJECT_DIR/rootfs/configure-rootfs.sh"
    (cd "$PROJECT_DIR" && ./rootfs/build-rootfs.sh)
  fi
fi

if ! rootfs_archive_is_arm64 "$ROOTFS_TARBALL"; then
  echo "Ubuntu rootfs archive is missing or is not ARM64: $ROOTFS_TARBALL" >&2
  exit 1
fi
printf '%s\n' "$(sha256sum "$ROOTFS_TARBALL" | awk '{print $1}')" | sudo tee "$ROOTFS_TARBALL.sha256" >/dev/null
sudo chown "$USER:$USER" "$ROOTFS_TARBALL.sha256"

ROOTFS_ASSET="$PROJECT_DIR/app/src/main/assets/ubuntu-rootfs.tar.xz"
mkdir -p "$(dirname "$ROOTFS_ASSET")"
ARCHIVE_HASH="$(sha256sum "$ROOTFS_TARBALL" | awk '{print $1}')"
ASSET_HASH=""
if [ -s "$ROOTFS_ASSET" ]; then ASSET_HASH="$(sha256sum "$ROOTFS_ASSET" | awk '{print $1}')"; fi
if [ "$ARCHIVE_HASH" != "$ASSET_HASH" ]; then
  log "Staging the ARM64 rootfs as an ignored APK asset..."
  rm -f "$ROOTFS_ASSET.tmp"
  if ! ln "$ROOTFS_TARBALL" "$ROOTFS_ASSET.tmp" 2>/dev/null; then
    cp "$ROOTFS_TARBALL" "$ROOTFS_ASSET.tmp"
  fi
  mv -f "$ROOTFS_ASSET.tmp" "$ROOTFS_ASSET"
fi
bash "$PROJECT_DIR/rootfs/generate-rootfs-info.sh" "$ROOTFS_TARBALL"

# Keep the archive and staged asset under /opt, but remove the unpacked tree to
# free space for Gradle's asset merge and APK output. Set KEEP_UNPACKED_ROOTFS=1
# when the expanded filesystem is needed for inspection.
if [ "${KEEP_UNPACKED_ROOTFS:-0}" != 1 ] && [ -d "$ROOTFS_DIR" ]; then
  sudo rm -rf "$ROOTFS_DIR"
fi

# 7. Reusable environment for shells and build commands.
cat > "$HOME/.android_env" <<EOF
export JAVA_HOME=$JAVA_HOME
export ANDROID_HOME=$ANDROID_SDK
export ANDROID_SDK_ROOT=\$ANDROID_HOME
export ANDROID_USER_HOME=$ANDROID_USER_HOME
export GRADLE_HOME=$GRADLE_DIR/current
export GRADLE_USER_HOME=$GRADLE_CACHE
export TMPDIR=$TMP_DIR
export JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=$TMP_DIR
export PATH=\$JAVA_HOME/bin:\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:\$ANDROID_HOME/build-tools/${COMPILE_SDK}.0.0:\$GRADLE_HOME/bin:\$PATH
EOF
sudo tee /etc/profile.d/android.sh >/dev/null <<EOF
export JAVA_HOME=$JAVA_HOME
export ANDROID_HOME=$ANDROID_SDK
export ANDROID_SDK_ROOT=\$ANDROID_HOME
export ANDROID_USER_HOME=$ANDROID_USER_HOME
export GRADLE_HOME=$GRADLE_DIR/current
export GRADLE_USER_HOME=$GRADLE_CACHE
export TMPDIR=$TMP_DIR
export JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=$TMP_DIR
export PATH=\$JAVA_HOME/bin:\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:\$ANDROID_HOME/build-tools/${COMPILE_SDK}.0.0:\$GRADLE_HOME/bin:\$PATH
EOF
sudo chmod 644 /etc/profile.d/android.sh
grep -q '.android_env' "$HOME/.bashrc" 2>/dev/null || echo 'source "$HOME/.android_env"' >> "$HOME/.bashrc"

log "Recovery setup complete. Rootfs SHA-256: $ARCHIVE_HASH"
log "Run: source ~/.android_env"
