#!/usr/bin/env bash
#
# 把工程模板目录打成单个 assets 资源（模板在运行时优先用这个 zip，见 ZipUtils 的说明：
# 插件运行时的 AssetManager.list() 对手工 zip 里的嵌套目录不可靠，而 open() 没问题）。
#
# 源：app/src/main/assets/templates/project/scala_android_sbt/**
# 产物：app/src/main/assets/templates/project/scala_android_sbt.zip
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/app/src/main/assets/templates/project/scala_android_sbt"
OUT="$ROOT/app/src/main/assets/templates/project/scala_android_sbt.zip"
PYTHON="${PYTHON:-python3}"

[ -d "$SRC" ] || { echo "缺少模板目录：$SRC" >&2; exit 1; }

"$PYTHON" - "$SRC" "$OUT" <<'PY'
import os, sys, zipfile
src, out = sys.argv[1], sys.argv[2]
count = 0
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for current, dirs, files in os.walk(src):
        dirs.sort()
        for name in sorted(files):
            path = os.path.join(current, name)
            rel = os.path.relpath(path, src).replace(os.sep, "/")
            info = zipfile.ZipInfo(rel, date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            with open(path, "rb") as handle:
                z.writestr(info, handle.read())
            count += 1
print("template zip: %d 个文件 -> %s" % (count, out))
PY
