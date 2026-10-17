#!/usr/bin/env python3
"""Make an ARM64 Ubuntu tree safe for extraction by an unprivileged Android app.

Android app sandboxes cannot create Unix device nodes, and some Android filesystems
or policies reject hard-link creation. Preserve applet behavior by replacing each
in-tree hard-link alias with a relative symlink to one canonical file. Device nodes
under /dev are omitted because the Termux launcher bind-mounts the host /dev.
"""
from __future__ import annotations

import os
import stat
import sys
from collections import defaultdict


def normalize(root: str) -> tuple[int, int]:
    root = os.path.abspath(root)
    marker = os.path.join(root, "etc", "termux-ubuntu-rootfs")
    try:
        with open(marker, encoding="utf-8") as stream:
            marker_lines = {line.strip() for line in stream}
    except OSError as error:
        raise SystemExit(f"Missing ARM64 Ubuntu rootfs marker at {marker}: {error}")
    if "ARCH=arm64" not in marker_lines:
        raise SystemExit(f"Refusing to normalize a rootfs without ARCH=arm64 marker: {root}")

    groups: dict[tuple[int, int], list[str]] = defaultdict(list)
    removed_devices = 0

    for directory, subdirectories, filenames in os.walk(root, topdown=True, followlinks=False):
        subdirectories.sort()
        filenames.sort()
        for name in subdirectories + filenames:
            path = os.path.join(directory, name)
            mode = os.lstat(path).st_mode
            if stat.S_ISDIR(mode) or stat.S_ISLNK(mode):
                continue
            relative = os.path.relpath(path, root).replace(os.sep, "/")
            if stat.S_ISREG(mode):
                info = os.stat(path, follow_symlinks=False)
                if info.st_nlink > 1:
                    groups[(info.st_dev, info.st_ino)].append(path)
                continue

            # Keep the /dev directory and its ordinary symlinks/directories, but
            # omit special nodes that the app sandbox is not permitted to mknod.
            if relative.split("/", 1)[0] == "dev" and (
                stat.S_ISCHR(mode) or stat.S_ISBLK(mode) or stat.S_ISFIFO(mode) or stat.S_ISSOCK(mode)
            ):
                os.unlink(path)
                removed_devices += 1
                continue
            raise SystemExit(f"Unsupported special file outside /dev in rootfs: {relative}")

    converted_links = 0
    for paths in groups.values():
        if len(paths) < 2:
            continue
        canonical = min(paths, key=lambda item: os.path.relpath(item, root))
        for alias in sorted(paths, key=lambda item: os.path.relpath(item, root)):
            if alias == canonical:
                continue
            relative_target = os.path.relpath(canonical, os.path.dirname(alias))
            os.unlink(alias)
            os.symlink(relative_target, alias)
            if os.path.realpath(alias) != os.path.realpath(canonical):
                raise SystemExit(f"Could not preserve hard-link target for {alias}")
            converted_links += 1

    if not os.path.isdir(os.path.join(root, "dev")):
        raise SystemExit("Rootfs is missing /dev, required for the launcher's /dev bind mount.")
    return converted_links, removed_devices


def main() -> int:
    if len(sys.argv) != 2:
        print(f"Usage: {sys.argv[0]} ROOTFS_DIR", file=sys.stderr)
        return 2
    links, devices = normalize(sys.argv[1])
    print(f"Android extraction compatibility: replaced {links} hard links with relative symlinks; removed {devices} special /dev nodes.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
