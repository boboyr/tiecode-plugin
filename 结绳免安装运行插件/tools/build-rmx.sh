#!/usr/bin/env bash
#
# 把本插件打成 .rmx（= 一个 APK：AndroidManifest + classes.dex + META-INF/program.json）。
# 不需要 Android SDK / Gradle，只用容器里的 javac / aapt2 / zipalign / r8。
#
# 用法： ./tools/build-rmx.sh
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

ANDROID_JAR="${ANDROID_JAR:-/root/tools/lib/android-stripped.jar}"
R8_JAR="${R8_JAR:-/root/tools/lib/r8.jar}"
STUDIO_API="${STUDIO_API:-/root/tools/lib/studio-api.jar}"

JAVA="${JAVA:-java}"
JAVAC="${JAVAC:-javac}"
AAPT2="${AAPT2:-aapt2}"
ZIPALIGN="${ZIPALIGN:-zipalign}"

APP_ID="com.dsh.nomount"
VERSION_CODE="2"
VERSION_NAME="0.2.0"
MIN_SDK="${MIN_SDK:-26}"
TARGET_SDK="${TARGET_SDK:-28}"
OUT_NAME="免安装运行器-${VERSION_NAME}.rmx"

WORK="${WORK:-/tmp/nomount-rmx}"
rm -rf "$WORK"
mkdir -p "$WORK"/{src,classes,dex,apk,lib}

echo "==> 1/5 收集源码"
cp -r "$ROOT/app/src/main/java/." "$WORK/src/"
cp "$ANDROID_JAR" "$WORK/lib/android.jar"
cp "$STUDIO_API" "$WORK/lib/studio-api.jar"

echo "==> 2/5 aapt2 link 生成 base.apk（只有清单）"
cat > "$WORK/AndroidManifest.xml" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="$APP_ID"
    android:versionCode="$VERSION_CODE"
    android:versionName="$VERSION_NAME">
    <application android:label="免安装运行器" />
</manifest>
EOF
"$AAPT2" link -o "$WORK/apk/base.apk" -I "$WORK/lib/android.jar" \
    --manifest "$WORK/AndroidManifest.xml" \
    --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME"

echo "==> 3/5 javac（Java 8 字节码）"
find "$WORK/src" -name '*.java' > "$WORK/sources.txt"
"$JAVAC" -encoding UTF-8 -nowarn -source 8 -target 8 \
    -cp "$WORK/lib/android.jar:$WORK/lib/studio-api.jar" \
    -d "$WORK/classes" @"$WORK/sources.txt"
( cd "$WORK/classes" && jar cf "$WORK/plugin-classes.jar" . )

echo "==> 4/5 D8 生成 classes.dex"
"$JAVA" -Xmx2g -cp "$R8_JAR" com.android.tools.r8.D8 \
    --min-api "$MIN_SDK" --lib "$WORK/lib/android.jar" \
    --output "$WORK/dex" \
    "$WORK/plugin-classes.jar"

echo "==> 5/5 组装 + 对齐 .rmx"
python3 - "$WORK" "$ROOT" "$OUT_NAME" <<'PY'
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
    for name in sorted(os.listdir(os.path.join(work, "dex"))):
        if name.endswith(".dex"):
            zi = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            zi.compress_type = zipfile.ZIP_DEFLATED
            zi.external_attr = 0o644 << 16
            z.writestr(zi, open(os.path.join(work, "dex", name), "rb").read())
    z.write(os.path.join(root, "app/src/main/resources/META-INF/program.json"),
            "META-INF/program.json", compress_type=zipfile.ZIP_DEFLATED)
print("assembled:", out)
PY
"$ZIPALIGN" -p -f 4 "$WORK/apk/$OUT_NAME" "$WORK/apk/aligned-$OUT_NAME"
cp "$WORK/apk/aligned-$OUT_NAME" "$ROOT/$OUT_NAME"
# 开发用热加载 payload：推到 sdcard 后插件会自动重载新代码，不必重启结绳
PAYLOAD_OUT="${PAYLOAD_OUT:-/sdcard/Tiecode/nomount-payload.dex}"
if [ -d "$(dirname "$PAYLOAD_OUT")" ]; then
    cp "$WORK/dex/classes.dex" "$PAYLOAD_OUT"
    echo "payload -> $PAYLOAD_OUT"
fi
echo "OK -> $ROOT/$OUT_NAME"
unzip -l "$ROOT/$OUT_NAME" | head -12
