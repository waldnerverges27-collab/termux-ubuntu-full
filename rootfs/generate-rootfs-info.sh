#!/usr/bin/env bash
# Regenerate the APK's small integrity/space manifest from the packaged rootfs.
set -Eeuo pipefail

ROOTFS_TARBALL="${1:-/opt/ubuntu-rootfs.tar.xz}"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
OUTPUT="$PROJECT_DIR/app/src/main/assets/ubuntu-rootfs.info"

if [[ ! -s "$ROOTFS_TARBALL" ]]; then
  echo "Missing Ubuntu ARM64 archive: $ROOTFS_TARBALL" >&2
  exit 1
fi

python3 - "$ROOTFS_TARBALL" "$OUTPUT" <<'PY'
import hashlib
import os
import sys
import tarfile
import tempfile

archive_path, output_path = sys.argv[1:]
marker_data = None
expanded_bytes = 0
hardlink_count = 0
special_entries = []
dev_directory_present = False
try:
    with tarfile.open(archive_path, mode='r:xz') as archive:
        for member in archive:
            if member.isfile():
                expanded_bytes += member.size
            if member.islnk():
                hardlink_count += 1
            if member.isdev() or member.isfifo():
                special_entries.append(member.name)
            if member.isdir() and member.name.rstrip('/') in ('ubuntu-rootfs/dev', 'dev'):
                dev_directory_present = True
            if member.name.endswith('/etc/termux-ubuntu-rootfs') or member.name == 'etc/termux-ubuntu-rootfs':
                stream = archive.extractfile(member)
                marker_data = stream.read(4096).decode('utf-8', 'replace') if stream else None
except (OSError, tarfile.TarError) as error:
    raise SystemExit('Could not inspect the Ubuntu rootfs archive: ' + str(error))

if not marker_data or 'ARCH=arm64' not in marker_data.splitlines():
    raise SystemExit('Archive is missing its ARM64 Ubuntu marker.')
if expanded_bytes <= 0:
    raise SystemExit('Archive contains no regular files.')
if hardlink_count:
    raise SystemExit(f'Archive contains {hardlink_count} hard links; run normalize-rootfs-for-android.py before packaging.')
if special_entries:
    raise SystemExit('Archive contains special nodes that Android cannot extract: ' + ', '.join(special_entries[:8]))
if not dev_directory_present:
    raise SystemExit('Archive is missing ubuntu-rootfs/dev, required by the Termux /dev bind mount.')

hasher = hashlib.sha256()
with open(archive_path, 'rb') as stream:
    for block in iter(lambda: stream.read(1024 * 1024), b''):
        hasher.update(block)
compressed_bytes = os.path.getsize(archive_path)
content = (
    'FORMAT=1\n'
    'ARCH=arm64\n'
    f'COMPRESSED_BYTES={compressed_bytes}\n'
    f'EXPANDED_BYTES={expanded_bytes}\n'
    f'SHA256={hasher.hexdigest()}\n'
)
os.makedirs(os.path.dirname(output_path), exist_ok=True)
fd, temporary = tempfile.mkstemp(prefix='.ubuntu-rootfs.info.', dir=os.path.dirname(output_path))
try:
    with os.fdopen(fd, 'w', encoding='utf-8') as stream:
        stream.write(content)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, output_path)
except Exception:
    try:
        os.unlink(temporary)
    except OSError:
        pass
    raise
print(f'Wrote {output_path}: {compressed_bytes} compressed bytes, {expanded_bytes} expanded bytes, SHA-256 {hasher.hexdigest()}')
PY
