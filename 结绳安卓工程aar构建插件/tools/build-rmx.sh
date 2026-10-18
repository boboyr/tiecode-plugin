#!/usr/bin/env bash
#
# 在没有 Android SDK / Gradle 的机器上把本插件打成 .rmx。
#
# 依赖：javac、java、aapt2、zipalign，以及带 resources.arsc 的完整 android.jar 和 r8.jar。
# （r8.jar 里含 D8：java -cp r8.jar com.android.tools.r8.D8）
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

ANDROID_JAR="${ANDROID_JAR:-/root/tools/lib/android-stripped.jar}"
R8_JAR="${R8_JAR:-/root/tools/lib/r8.jar}"
STUDIO_API="${STUDIO_API:-$ROOT/app/libs/api/studio-api-1.0-preview4.jar}"

JAVA="${JAVA:-java}"
JAVAC="${JAVAC:-javac}"
AAPT2="${AAPT2:-aapt2}"
ZIPALIGN="${ZIPALIGN:-zipalign}"
PYTHON="${PYTHON:-python3}"

APP_ID="com.tiecode.aarplugin"
VERSION_CODE="6"
VERSION_NAME="1.5"
MIN_SDK="26"
TARGET_SDK="35"
OUT_NAME="结绳安卓工程aar构建插件-1.5.rmx"

WORK="${WORK:-/tmp/aarplugin-rmx}"
rm -rf "$WORK"
mkdir -p "$WORK"/{src,res,flat,classes,dex,apk,lib}

echo "==> 1/5 准备源码与 classpath"
cp -r "$ROOT/app/src/main/java/." "$WORK/src/"
cp -r "$ROOT/app/src/main/res/." "$WORK/res/"
cp "$ANDROID_JAR" "$WORK/lib/android.jar"
cp "$STUDIO_API" "$WORK/lib/studio-api.jar"

echo "==> 2/5 javac 编译插件（Java 8 字节码）"
find "$WORK/src" -name '*.java' > "$WORK/sources.txt"
"$JAVAC" -encoding UTF-8 -nowarn -source 8 -target 8 \
    -cp "$WORK/lib/android.jar:$WORK/lib/studio-api.jar" \
    -d "$WORK/classes" @"$WORK/sources.txt"
( cd "$WORK/classes" && jar cf "$WORK/plugin-classes.jar" . )

echo "==> 3/5 D8 生成 classes.dex"
"$JAVA" -cp "$R8_JAR" com.android.tools.r8.D8 \
    --min-api "$MIN_SDK" --lib "$WORK/lib/android.jar" \
    --output "$WORK/dex" "$WORK/plugin-classes.jar"

echo "==> 4/5 aapt2 生成资源与清单"
cat > "$WORK/AndroidManifest.xml" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="$APP_ID"
    android:versionCode="$VERSION_CODE"
    android:versionName="$VERSION_NAME">
    <application android:label="@string/app_name" />
</manifest>
EOF
"$AAPT2" compile --dir "$WORK/res" -o "$WORK/flat/res.zip"
"$AAPT2" link -o "$WORK/apk/base.apk" -I "$WORK/lib/android.jar" \
    --manifest "$WORK/AndroidManifest.xml" \
    --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
    "$WORK/flat/res.zip"

echo "==> 5/5 组装 .rmx 并 zipalign"
"$PYTHON" - "$WORK" "$ROOT" "$OUT_NAME" <<'PY'
import os, sys, zipfile
work, root, out_name = sys.argv[1], sys.argv[2], sys.argv[3]
out = os.path.join(work, "apk", out_name)
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    base = zipfile.ZipFile(os.path.join(work, "apk", "base.apk"))
    for info in base.infolist():
        data = base.read(info.filename)
        method = zipfile.ZIP_STORED if info.filename.endswith(".arsc") else zipfile.ZIP_DEFLATED
        zi = zipfile.ZipInfo(info.filename, date_time=(2026, 1, 1, 0, 0, 0))
        zi.compress_type = method
        zi.external_attr = 0o644 << 16
        z.writestr(zi, data)
    z.write(os.path.join(work, "dex", "classes.dex"), "classes.dex",
            compress_type=zipfile.ZIP_DEFLATED)
    z.write(os.path.join(root, "app/src/main/resources/META-INF/program.json"),
            "META-INF/program.json", compress_type=zipfile.ZIP_DEFLATED)
print("assembled:", out)
PY
"$ZIPALIGN" -p -f 4 "$WORK/apk/$OUT_NAME" "$WORK/apk/aligned-$OUT_NAME"
cp "$WORK/apk/aligned-$OUT_NAME" "$ROOT/$OUT_NAME"
if command -v aapt >/dev/null 2>&1; then
    aapt dump badging "$ROOT/$OUT_NAME" | head -3 || true
fi
echo "OK -> $ROOT/$OUT_NAME"
