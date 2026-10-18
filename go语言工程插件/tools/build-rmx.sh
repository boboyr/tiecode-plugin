#!/usr/bin/env bash
#
# 在没有完整 Android SDK / Gradle 的机器上把本插件打成 .rmx。
#
# 依赖（本机已就位）：
#   javac/java  -> JDK 17 (/usr/lib/jvm/java-17-openjdk-arm64)
#   aapt2/zipalign -> /usr/lib/android-sdk/build-tools/29.0.3 (arm64)
#   android.jar -> /root/tools/lib/android-stripped.jar (旧 aapt2 能加载的平台 jar)
#   r8.jar      -> /root/tools/lib/r8.jar (含 D8)
#
# 用法： ./tools/build-rmx.sh
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

ANDROID_JAR="${ANDROID_JAR:-/root/tools/lib/android-stripped.jar}"
R8_JAR="${R8_JAR:-/root/tools/lib/r8.jar}"
STUDIO_API="${STUDIO_API:-$ROOT/app/libs/api/studio-api-1.0-preview4.jar}"
GRADLE_CACHE="${GRADLE_CACHE:-/root/.gradle/caches/modules-2/files-2.1}"

JAVA="${JAVA:-/usr/lib/jvm/java-17-openjdk-arm64/bin/java}"
JAVAC="${JAVAC:-javac}"
AAPT2="${AAPT2:-/usr/lib/android-sdk/build-tools/29.0.3/aapt2}"
ZIPALIGN="${ZIPALIGN:-/usr/lib/android-sdk/build-tools/29.0.3/zipalign}"
PYTHON="${PYTHON:-python3}"

APP_ID="com.goproject.plugin"
VERSION_CODE="4"
VERSION_NAME="1.3"
MIN_SDK="26"
TARGET_SDK="36"
OUT_NAME="go语言工程插件-1.3.rmx"

WORK="${WORK:-/tmp/goproject-rmx}"
rm -rf "$WORK"
mkdir -p "$WORK"/{src,res,flat,classes,dex,apk,libs,gen}

echo "==> 1/6 准备源码、依赖 classpath 与 res"
cp -r "$ROOT/app/src/main/java/." "$WORK/src/"
cp -r "$ROOT/app/src/main/res/." "$WORK/res/"
cp "$ANDROID_JAR" "$WORK/libs/android.jar"
cp "$STUDIO_API" "$WORK/libs/studio-api.jar"

# 本插件自带的实现库（SDL Java 胶水 + 解压 tar.xz 用的压缩库）
cp "$ROOT"/app/libs/*.jar "$WORK/libs/" 2>/dev/null || true

# 从 gradle 缓存取第三方依赖（editor-lsp 是 aar：取 classes.jar，res 并入 res 目录）
find_jar() { find "$GRADLE_CACHE/$1" -name "$2" | head -1; }
EDLSP_AAR=$(find_jar "io.github.rosemoe/editor-lsp/0.24.6" "editor-lsp-0.24.6.aar")
"$PYTHON" - "$EDLSP_AAR" "$WORK/libs" "$WORK/res" <<'PY'
import sys, zipfile
aar, libs, res = sys.argv[1], sys.argv[2], sys.argv[3]
z = zipfile.ZipFile(aar)
with z.open("classes.jar") as f, open(libs + "/editor-lsp-classes.jar", "wb") as o:
    o.write(f.read())
# aar 的 res 并入工程 res（--auto-add-overlay 兜重）
with zipfile.ZipFile(aar) as z:
    for i in z.infolist():
        if i.filename.startswith("res/") and not i.is_dir():
            import os
            dst = os.path.join(res, i.filename)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            with open(dst, "wb") as o:
                o.write(z.read(i.filename))
PY
cp "$(find_jar "org.eclipse.lsp4j/org.eclipse.lsp4j/1.0.0" "org.eclipse.lsp4j-1.0.0.jar")" "$WORK/libs/"
cp "$(find_jar "org.eclipse.lsp4j/org.eclipse.lsp4j.jsonrpc/1.0.0" "org.eclipse.lsp4j.jsonrpc-1.0.0.jar")" "$WORK/libs/"
cp "$(find_jar "com.google.code.gson/gson/2.14.0" "gson-2.14.0.jar")" "$WORK/libs/"
cp "$(find_jar "org.jetbrains.kotlin/kotlin-stdlib/2.3.10" "kotlin-stdlib-2.3.10.jar")" "$WORK/libs/"
cp "$(find_jar "org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.10.2" "kotlinx-coroutines-core-jvm-1.10.2.jar")" "$WORK/libs/"
cp "$(find_jar "org.jetbrains.kotlinx/kotlinx-coroutines-android/1.10.2" "kotlinx-coroutines-android-1.10.2.jar")" "$WORK/libs/"

CP="$WORK/libs/android.jar:$WORK/libs/studio-api.jar"
for j in "$WORK"/libs/*.jar; do
    case "$j" in */android.jar|*/studio-api.jar) ;; *) CP="$CP:$j" ;; esac
done

echo "==> 2/6 aapt2 生成资源、清单与 R.java（必须先于 javac）"
cat > "$WORK/AndroidManifest.xml" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="$APP_ID"
    android:versionCode="$VERSION_CODE"
    android:versionName="$VERSION_NAME">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
    <application android:label="@string/app_name" />
</manifest>
EOF
"$AAPT2" compile --dir "$WORK/res" -o "$WORK/flat/res.zip"
"$AAPT2" link -o "$WORK/apk/base.apk" -I "$WORK/libs/android.jar" \
    --manifest "$WORK/AndroidManifest.xml" \
    --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
    --auto-add-overlay \
    --java "$WORK/gen" \
    "$WORK/flat/res.zip"

echo "==> 3/6 javac 编译插件（Java 8 字节码）"
find "$WORK/src" "$WORK/gen" -name '*.java' > "$WORK/sources.txt"
"$JAVAC" -encoding UTF-8 -nowarn -source 8 -target 8 \
    -cp "$CP" \
    -d "$WORK/classes" @"$WORK/sources.txt"
( cd "$WORK/classes" && jar cf "$WORK/plugin-classes.jar" . )

echo "==> 3/6 D8 生成 classes.dex（含全部依赖库）"
"$JAVA" -Xmx2048m -cp "$R8_JAR" com.android.tools.r8.D8 \
    --min-api "$MIN_SDK" --lib "$WORK/libs/android.jar" \
    --output "$WORK/dex" \
    "$WORK/plugin-classes.jar" $(ls "$WORK"/libs/*.jar | grep -v "android.jar\|studio-api.jar")

echo "==> 4/6 D8 生成 classes.dex（含全部依赖库）"
"$JAVA" -Xmx2048m -cp "$R8_JAR" com.android.tools.r8.D8 \
    --min-api "$MIN_SDK" --lib "$WORK/libs/android.jar" \
    --output "$WORK/dex" \
    "$WORK/plugin-classes.jar" $(ls "$WORK"/libs/*.jar | grep -v "android.jar\|studio-api.jar")

echo "==> 5/6 组装 .rmx"
"$PYTHON" - "$WORK" "$ROOT" "$OUT_NAME" <<'PY'
import os, sys, zipfile
work, root, out_name = sys.argv[1], sys.argv[2], sys.argv[3]
out = os.path.join(work, "apk", out_name)
seen = set()
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    def add(name, data, stored=False):
        if name in seen:
            return
        seen.add(name)
        zi = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
        zi.compress_type = zipfile.ZIP_STORED if stored else zipfile.ZIP_DEFLATED
        zi.external_attr = 0o644 << 16
        z.writestr(zi, data)

    # base.apk：二进制 AndroidManifest + res + resources.arsc(必须不压缩)
    base = zipfile.ZipFile(os.path.join(work, "apk", "base.apk"))
    for info in base.infolist():
        if info.is_dir():
            continue
        add(info.filename, base.read(info.filename),
            stored=info.filename.endswith(".arsc"))

    # classes.dex（D8 产物，多 dex 时全部带上）
    for d in sorted(os.listdir(os.path.join(work, "dex"))):
        if d.endswith(".dex"):
            with open(os.path.join(work, "dex", d), "rb") as f:
                add(d, f.read())

    # 插件清单 program.json
    with open(os.path.join(root, "app/src/main/resources/META-INF/program.json"), "rb") as f:
        add("META-INF/program.json", f.read())

    # assets：libSDL3.so 必须 STORED（结绳安装器按 available()+单次 read() 读取）
    assets = os.path.join(root, "app/src/main/assets")
    for dirpath, _, files in os.walk(assets):
        for fn in sorted(files):
            full = os.path.join(dirpath, fn)
            rel = "assets/" + os.path.relpath(full, assets).replace(os.sep, "/")
            with open(full, "rb") as f:
                add(rel, f.read(), stored=fn.endswith(".so"))

    # 依赖 jar 里的非 class 条目（kotlin builtins / META-INF services 等，AGP 同样会带上）
    libs = os.path.join(work, "libs")
    for j in sorted(os.listdir(libs)):
        if not j.endswith(".jar") or j in ("android.jar", "studio-api.jar"):
            continue
        with zipfile.ZipFile(os.path.join(libs, j)) as dz:
            for info in dz.infolist():
                n = info.filename
                if info.is_dir() or n.endswith((".class", "/")):
                    continue
                if n.startswith("META-INF/") and (
                        n == "META-INF/MANIFEST.MF"
                        or n.endswith((".SF", ".RSA", ".EC", ".DSA"))
                        or n.startswith("META-INF/versions/")):
                    continue
                if n.startswith("META-INF/versions/"):
                    continue
                add(n, dz.read(info.filename))
print("assembled:", out)
PY

echo "==> 6/6 zipalign"
"$ZIPALIGN" -p -f 4 "$WORK/apk/$OUT_NAME" "$WORK/apk/aligned-$OUT_NAME"
cp "$WORK/apk/aligned-$OUT_NAME" "$ROOT/$OUT_NAME"
if [ -x /usr/lib/android-sdk/build-tools/29.0.3/aapt ]; then
    /usr/lib/android-sdk/build-tools/29.0.3/aapt dump badging "$ROOT/$OUT_NAME" 2>/dev/null | head -3 || true
fi
echo "OK -> $ROOT/$OUT_NAME"
