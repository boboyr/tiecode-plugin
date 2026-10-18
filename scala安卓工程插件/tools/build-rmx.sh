#!/usr/bin/env bash
#
# 在没有 Android SDK / Gradle 的机器上把本插件打成 .rmx。
#
# 依赖：javac / java / aapt2 / zipalign，以及
#   - 带 resources.arsc 的 android.jar（默认 /root/tools/lib/android-stripped.jar）
#   - r8.jar（含 D8：java -cp r8.jar com.android.tools.r8.D8）
#
# 和 Android 构建一样，先 aapt2 link 生成 R.java 再 javac（插件代码里用了 R.drawable.*）。
#
# 用法：
#   ./tools/build-rmx.sh              # 打包
#   MIN_SDK=33 ./tools/build-rmx.sh   # 改最低 API
#
# locale 兜底：容器里 LANG/LC_ALL 可能是空的，javac 默认 US-ASCII，
# 遇到中文路径 / 注释就报 "unmappable character" 或 file not found。
export LC_ALL="${LC_ALL:-C.UTF-8}"
export LANG="${LANG:-C.UTF-8}"
export JDK_JAVA_OPTIONS="${JDK_JAVA_OPTIONS:--Dfile.encoding=UTF-8}"
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

APP_ID="com.scala.android"
# 版本号可用环境变量覆盖（与下面的 MIN_SDK / TARGET_SDK / OUT_NAME 一致）：
#   VERSION_CODE=12 VERSION_NAME=1.11 ./tools/build-rmx.sh
VERSION_CODE="${VERSION_CODE:-11}"
VERSION_NAME="${VERSION_NAME:-1.10}"
# Scala 3.8 的编译器引用了 java.lang.invoke.VarHandle（Android 13 / API 33 起可用），
# 所以插件本身最低就是 API 33。
MIN_SDK="${MIN_SDK:-33}"
TARGET_SDK="${TARGET_SDK:-35}"
OUT_NAME="scala安卓工程插件-${VERSION_NAME}.rmx"

WORK="${WORK:-/tmp/scalaandroid-rmx}"
rm -rf "$WORK"
mkdir -p "$WORK"/{src,gen,res,flat,classes,dex,apk,lib,assets}

echo "==> 1/6 准备源码 / 资源 / assets"
# 模板目录 → 单文件 zip（运行时优先用它，见 ZipUtils：AssetManager.list() 对嵌套目录不可靠）
bash "$ROOT/tools/build-template-zip.sh"
# 结绳插件工程模板 → 单文件 zip（同上；ScalaPluginProjectTemplate 运行时优先用这个）
bash "$ROOT/tools/build-plugin-template-zip.sh"
cp -r "$ROOT/app/src/main/java/." "$WORK/src/"
cp -r "$ROOT/app/src/main/res/." "$WORK/res/"
cp -r "$ROOT/app/src/main/assets/." "$WORK/assets/"
cp "$ANDROID_JAR" "$WORK/lib/android.jar"
cp "$STUDIO_API" "$WORK/lib/studio-api.jar"

echo "==> 2/6 aapt2 compile + link（顺便生成 R.java）"
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
    --java "$WORK/gen" \
    --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
    "$WORK/flat/res.zip"

echo "==> 3/6 javac 编译插件（Java 8 字节码 + R.java）"
find "$WORK/src" "$WORK/gen" -name '*.java' > "$WORK/sources.txt"
SCALA_JARS="$(ls "$ROOT"/app/libs/scala/*.jar | tr '\n' ':')"
"$JAVAC" -encoding UTF-8 -nowarn -source 8 -target 8 \
    -cp "$WORK/lib/android.jar:$WORK/lib/studio-api.jar:$SCALA_JARS" \
    -d "$WORK/classes" @"$WORK/sources.txt"
( cd "$WORK/classes" && jar cf "$WORK/plugin-classes.jar" . )

echo "==> 4/6 D8 生成 classes.dex（插件 class + Scala 编译器）"
# 随插件打包的 Scala 编译器直接进 dex，运行时进程内调用 dotty。
"$JAVA" -Xmx3g -cp "$R8_JAR" com.android.tools.r8.D8 \
    --min-api "$MIN_SDK" --lib "$WORK/lib/android.jar" \
    --output "$WORK/dex" \
    "$WORK/plugin-classes.jar" \
    "$ROOT"/app/libs/scala/*.jar

echo "==> 5/6 组装 .rmx（base.apk 条目 + dex + program.json + assets）"
"$PYTHON" - "$WORK" "$ROOT" "$OUT_NAME" "$VERSION_CODE" "$VERSION_NAME" <<'PY'
import os, re, sys, zipfile
work, root, out_name, version_code, version_name = sys.argv[1:6]
out = os.path.join(work, "apk", out_name)


def add_tree(z, base_dir, prefix):
    for current, _dirs, files in os.walk(base_dir):
        for name in sorted(files):
            path = os.path.join(current, name)
            rel = os.path.relpath(path, base_dir).replace(os.sep, "/")
            zi = zipfile.ZipInfo(prefix + rel, date_time=(2026, 1, 1, 0, 0, 0))
            zi.compress_type = zipfile.ZIP_DEFLATED
            zi.external_attr = 0o644 << 16
            with open(path, "rb") as handle:
                z.writestr(zi, handle.read())


def plugin_manifest():
    """读插件清单，并把 versionCode / versionName 同步成本次构建的版本。

    宿主的插件列表读的就是 META-INF/program.json，手工维护的版本号容易忘改
    （APK 是 1.12、插件列表还显示 1.10），所以这里以 VERSION_CODE / VERSION_NAME 为准。
    """
    path = os.path.join(root, "app/src/main/resources/META-INF/program.json")
    with open(path, encoding="utf-8") as handle:
        text = handle.read()
    text, n1 = re.subn(r'("versionCode"\s*:\s*)\d+', r'\g<1>' + version_code,
                       text, count=1)
    text, n2 = re.subn(r'("versionName"\s*:\s*)"[^"]*"',
                       r'\g<1>"' + version_name + '"', text, count=1)
    if n1 != 1 or n2 != 1:
        raise SystemExit("program.json 里没找到 versionCode / versionName，无法同步版本号：" + path)
    print("plugin manifest version: %s (%s)" % (version_name, version_code))
    return text


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
            z.write(os.path.join(work, "dex", name), name, compress_type=zipfile.ZIP_DEFLATED)
    z.writestr("META-INF/program.json", plugin_manifest(),
               compress_type=zipfile.ZIP_DEFLATED)
    add_tree(z, os.path.join(work, "assets"), "assets/")
print("assembled:", out)
PY

echo "==> 6/6 zipalign + 拷贝到工程根"
"$ZIPALIGN" -p -f 4 "$WORK/apk/$OUT_NAME" "$WORK/apk/aligned-$OUT_NAME"
cp "$WORK/apk/aligned-$OUT_NAME" "$ROOT/$OUT_NAME"
if command -v aapt >/dev/null 2>&1; then
    aapt dump badging "$ROOT/$OUT_NAME" | head -3 || true
fi
echo "OK -> $ROOT/$OUT_NAME"
