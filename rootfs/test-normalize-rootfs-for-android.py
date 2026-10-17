#!/usr/bin/env python3
"""Small regression test for Android-safe rootfs normalization."""
import importlib.util
import os
import pathlib
import tempfile

SCRIPT = pathlib.Path(__file__).with_name("normalize-rootfs-for-android.py")
spec = importlib.util.spec_from_file_location("normalize_rootfs", SCRIPT)
assert spec and spec.loader
normalize_module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(normalize_module)

with tempfile.TemporaryDirectory(prefix="termux-rootfs-normalize-test-") as temporary:
    root = pathlib.Path(temporary)
    (root / "etc").mkdir()
    (root / "etc/termux-ubuntu-rootfs").write_text("ARCH=arm64\n", encoding="utf-8")
    (root / "dev").mkdir()
    (root / "usr/lib/cargo/bin/coreutils").mkdir(parents=True)
    (root / "usr/bin").mkdir()

    canonical = root / "usr/lib/cargo/bin/coreutils/seq"
    canonical.write_text("multicall test executable\n", encoding="utf-8")
    os.link(canonical, root / "usr/lib/cargo/bin/coreutils/chgrp")
    os.link(canonical, root / "usr/bin/coreutils")
    os.mkfifo(root / "dev/test-pipe")

    converted, removed = normalize_module.normalize(str(root))
    assert converted == 2, converted
    assert removed == 1, removed
    assert (root / "dev").is_dir()
    assert not (root / "dev/test-pipe").exists()
    canonical_after = root / "usr/bin/coreutils"
    for alias in (root / "usr/lib/cargo/bin/coreutils/chgrp", canonical):
        assert alias.is_symlink(), alias
        assert alias.read_text(encoding="utf-8") == "multicall test executable\n"
        assert os.path.realpath(alias) == os.path.realpath(canonical_after)

print("normalize-rootfs-for-android regression test passed")
