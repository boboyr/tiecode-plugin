#!/usr/bin/env bash
#
# 自检脚本（在开发机的普通 JVM 上跑，不需要 Android 设备）：
#   1) sbt 解析器：解析一个"穷举常见写法"的 build.sbt
#   2) 依赖解析器：本地 m2 仓库上的传递依赖/版本仲裁/exclude/AAR 解包
#   3) 工程模板：解包 zip → 交给解析器读
#   4) APK 打包器：res.ap_ + dex → APK，核对压缩方式
#   5) 密钥库归一化：PKCS12 → 平台默认类型
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${WORK:-/tmp/scala-android-sbt-tests}"
FIXTURE="$WORK/fixture"
REPO="$WORK/m2repo"
WS="$WORK/workspace"

export LANG="${LANG:-C.UTF-8}"
export LC_ALL="${LC_ALL:-C.UTF-8}"
PYTHON="${PYTHON:-python3}"

rm -rf "$WORK"
mkdir -p "$WORK/out" "$FIXTURE/project" "$WS/lib"

echo "==> 编译 sbt 解析器与依赖解析器"
find "$ROOT/app/src/main/java/com/scala/android/sbt" \
     "$ROOT/app/src/main/java/com/scala/android/dependency" -name '*.java' > "$WORK/sources.txt"
# 第 1 步只编"纯 Java"部分（不碰 Android API）；其余自检在后面各自编
find "$ROOT/tests/com/scala/android/sbt" "$ROOT/tests/com/scala/android/dependency" \
     -name '*.java' >> "$WORK/sources.txt"
javac -encoding UTF-8 -nowarn -d "$WORK/out" @"$WORK/sources.txt"

echo "==> 造一个覆盖常见写法的 build.sbt"
cat > "$FIXTURE/project/build.properties" <<'EOF'
sbt.version=1.10.7
EOF
cat > "$FIXTURE/project/plugins.sbt" <<'EOF'
addSbtPlugin("io.github.botr" % "sbt-modern-android" % "1.0.0")
EOF
cat > "$FIXTURE/build.sbt" <<'EOF'
import modernandroid.AndroidKeys.*

ThisBuild / organization := "com.example.demo"
ThisBuild / scalaVersion := "2.13.12"
val okhttpVersion = "4.12.0"

lazy val app = (project in file("app"))
  .enablePlugins(modernandroid.AndroidApp)
  .settings(
    name := "demo-app",
    platformTarget := "android-34",
    androidApplicationId := "com.example.demo",
    androidMinSdk := 24,
    androidTargetSdk := 34,
    androidVersionCode := 7,
    resolvers ++= Seq(
      "Aliyun Public" at "https://maven.aliyun.com/repository/public/",
      Resolver.mavenCentral
    ),
    libraryDependencies ++= Seq(
      "com.squareup.okhttp3" %% "okhttp" % okhttpVersion,
      "com.google.code.gson" % "gson" % "2.11.0",
      "junit" % "junit" % "4.13.2" % Test
    ),
    libraryDependencies += "androidx.core" % "core-ktx" % "1.13.1" exclude("org.jetbrains.kotlin", "kotlin-stdlib"),
    Compile / scalacOptions ++= Seq("-deprecation", "-feature")
  )

lazy val root = (project in file(".")).aggregate(app).settings(name := "demo-root")
EOF

echo "==> 1) 解析器自检"
java -Dfile.encoding=UTF-8 -cp "$WORK/out" \
    com.scala.android.sbt.SbtParserHarness "$FIXTURE"

echo
echo "==> 造本地 m2 仓库（含 AAR、parent POM、版本冲突）"
"$PYTHON" - "$REPO" "$WS" <<'PY'
import os, sys, zipfile
repo, ws = sys.argv[1], sys.argv[2]

def w(path, text):
    p = os.path.join(repo, path)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    open(p, "w").write(text)

def jar(path, entries):
    p = os.path.join(repo, path)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with zipfile.ZipFile(p, "w") as z:
        for name, data in entries.items():
            z.writestr(name, data)

jar("com/example/liba/1.0/liba-1.0.jar", {"com/example/LibA.class": b"a"})
w("com/example/liba/1.0/liba-1.0.pom", """<project>
  <groupId>com.example</groupId><artifactId>liba</artifactId><version>1.0</version>
  <dependencies>
    <dependency><groupId>com.example</groupId><artifactId>libb</artifactId><version>1.0</version></dependency>
    <dependency><groupId>com.example</groupId><artifactId>libopt</artifactId><version>1.0</version><optional>true</optional></dependency>
    <dependency><groupId>junit</groupId><artifactId>junit</artifactId><version>4.13.2</version><scope>test</scope></dependency>
  </dependencies>
</project>""")

for v in ("1.0", "1.1"):
    jar(f"com/example/libb/{v}/libb-{v}.jar", {"com/example/LibB.class": v.encode()})
    w(f"com/example/libb/{v}/libb-{v}.pom",
      f"<project><groupId>com.example</groupId><artifactId>libb</artifactId><version>{v}</version></project>")

jar("com/example/libc/1.0/libc-1.0.jar", {"com/example/LibC.class": b"c"})
w("com/example/libc/1.0/libc-1.0.pom", """<project><groupId>com.example</groupId><artifactId>libc</artifactId><version>1.0</version>
 <dependencies><dependency><groupId>com.example</groupId><artifactId>libb</artifactId><version>1.1</version></dependency></dependencies></project>""")

w("com/example/parent/1.0/parent-1.0.pom", """<project><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version>
 <packaging>pom</packaging>
 <properties><libe.version>2.0.0</libe.version></properties>
 <dependencyManagement><dependencies>
   <dependency><groupId>com.example</groupId><artifactId>libe</artifactId><version>${libe.version}</version></dependency>
 </dependencies></dependencyManagement></project>""")
jar("com/example/libd/1.0/libd-1.0.jar", {"com/example/LibD.class": b"d"})
w("com/example/libd/1.0/libd-1.0.pom", """<project>
 <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
 <artifactId>libd</artifactId>
 <dependencies><dependency><groupId>com.example</groupId><artifactId>libe</artifactId></dependency></dependencies></project>""")
jar("com/example/libe/2.0.0/libe-2.0.0.jar", {"com/example/LibE.class": b"e"})
w("com/example/libe/2.0.0/libe-2.0.0.pom",
  "<project><groupId>com.example</groupId><artifactId>libe</artifactId><version>2.0.0</version></project>")

jar("com/example/libskip/1.0/libskip-1.0.jar", {"com/example/LibSkip.class": b"s"})
w("com/example/libskip/1.0/libskip-1.0.pom", """<project><groupId>com.example</groupId><artifactId>libskip</artifactId><version>1.0</version>
 <dependencies>
   <dependency><groupId>com.example</groupId><artifactId>libb</artifactId><version>1.0</version></dependency>
   <dependency><groupId>com.example</groupId><artifactId>libf</artifactId><version>3.0</version></dependency>
 </dependencies></project>""")
jar("com/example/libf/3.0/libf-3.0.jar", {"com/example/LibF.class": b"f"})
w("com/example/libf/3.0/libf-3.0.pom",
  "<project><groupId>com.example</groupId><artifactId>libf</artifactId><version>3.0</version></project>")

p = os.path.join(repo, "com/example/libandroid/1.0/libandroid-1.0.aar")
os.makedirs(os.path.dirname(p), exist_ok=True)
with zipfile.ZipFile(p, "w") as z:
    z.writestr("classes.jar", b"android-classes")
    z.writestr("AndroidManifest.xml", "<manifest package='com.example.libandroid'/>")
    z.writestr("res/values/values.xml", "<resources/>")
    z.writestr("assets/data.txt", "hello")
    z.writestr("jni/arm64-v8a/libexample.so", b"\x7fELF")
w("com/example/libandroid/1.0/libandroid-1.0.pom",
  "<project><groupId>com.example</groupId><artifactId>libandroid</artifactId><version>1.0</version><packaging>aar</packaging></project>")

os.makedirs(os.path.join(ws, "lib"), exist_ok=True)
with zipfile.ZipFile(os.path.join(ws, "lib/local-only.jar"), "w") as z:
    z.writestr("Local.class", b"L")
print("fixture ready:", repo)
PY

echo
echo "==> 2) 依赖解析器自检"
java -Dfile.encoding=UTF-8 -cp "$WORK/out" \
    com.scala.android.dependency.SbtResolverHarness "$REPO" "$WS"

echo
echo "==> 3) 工程模板自检（解包 zip → 交给解析器读）"
ANDROID_JAR="${ANDROID_JAR:-/root/tools/lib/android-stripped.jar}"
if [ -f "$ANDROID_JAR" ]; then
    if [ ! -f "$ROOT/app/src/main/assets/templates/project/scala_android_sbt.zip" ]; then
        bash "$ROOT/tools/build-template-zip.sh"
    fi
    javac -encoding UTF-8 -nowarn -cp "$ANDROID_JAR" -d "$WORK/out" \
        $(find "$ROOT/app/src/main/java/com/scala/android/sbt" -name '*.java') \
        "$ROOT/app/src/main/java/com/scala/android/util/ZipUtils.java" \
        "$ROOT/app/src/main/java/com/scala/android/util/AssetTree.java" \
        "$ROOT/tests/com/scala/android/TemplateHarness.java"
    java -Dfile.encoding=UTF-8 -cp "$WORK/out:$ANDROID_JAR" com.scala.android.TemplateHarness \
        "$ROOT/app/src/main/assets/templates/project/scala_android_sbt.zip" \
        "$WORK/template-check"
else
    echo "跳过：找不到 android.jar（可用 ANDROID_JAR=... 指定）"
fi

echo
echo "==> 4) APK 打包器自检（res.ap_ + dex → APK，核对压缩方式）"
APK_DIR="$WORK/apk-pack"
mkdir -p "$APK_DIR"
"$PYTHON" - "$APK_DIR" <<'PY'
import os, sys, zipfile
out = sys.argv[1]
with zipfile.ZipFile(os.path.join(out, "res.ap_"), "w") as z:
    z.writestr("AndroidManifest.xml", "<manifest package='com.example'/>")
    z.writestr("resources.arsc", b"\x02\x00\x0c\x00" * 64)
    z.writestr("res/values/values.xml", "<resources/>")
    z.writestr("res/raw/keep.bin", b"raw-data")
with open(os.path.join(out, "classes.dex"), "wb") as f:
    f.write(b"dex\n036\n" + b"\x00" * 512)
print("apk fixture ready")
PY
javac -encoding UTF-8 -nowarn -d "$WORK/out" \
    "$ROOT/app/src/main/java/com/scala/android/util/ApkPacker.java" \
    "$ROOT/tests/com/scala/android/ApkPackerHarness.java"
java -Dfile.encoding=UTF-8 -cp "$WORK/out" com.scala.android.ApkPackerHarness \
    "$APK_DIR/res.ap_" "$APK_DIR/classes.dex" "$APK_DIR/app-unsigned.apk" > "$APK_DIR/pack.log" 2>&1
tail -3 "$APK_DIR/pack.log"
"$PYTHON" - "$APK_DIR/app-unsigned.apk" <<'PY'
import sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
assert z.testzip() is None, "APK 损坏"
methods = {i.filename: i.compress_type for i in z.infolist()}
assert methods.get("resources.arsc") == zipfile.ZIP_STORED, "resources.arsc 必须不压缩"
assert methods.get("AndroidManifest.xml") == zipfile.ZIP_DEFLATED, "AndroidManifest 应压缩"
assert methods.get("classes.dex") == zipfile.ZIP_DEFLATED, "classes.dex 应压缩"
print("   resources.arsc=STORED，其余=DEFLATE，条目 %d 个 → 打包器自检通过" % len(methods))
PY

echo
echo "==> 5) 密钥库归一化自检（PKCS12 → 平台默认类型）"
KS="$ROOT/app/src/main/assets/templates/project/scala_android_sbt/release-test.keystore"
if [ -f "$KS" ]; then
    "$PYTHON" - "$KS" "$WORK/pkcs12/keystore.p12" <<'PY'
import os, subprocess, sys
src, dst = sys.argv[1], sys.argv[2]
os.makedirs(os.path.dirname(dst), exist_ok=True)
if os.path.exists(dst):
    os.remove(dst)
subprocess.check_call([
    "keytool", "-importkeystore",
    "-srckeystore", src, "-srcstoretype", "JKS", "-srcstorepass", "changeit",
    "-destkeystore", dst, "-deststoretype", "PKCS12", "-deststorepass", "changeit",
    "-srcalias", "release", "-destalias", "release",
    "-srckeypass", "changeit", "-destkeypass", "changeit", "-noprompt",
], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
print("pkcs12 fixture ready:", dst)
PY
    javac -encoding UTF-8 -nowarn -d "$WORK/out" \
        "$ROOT/app/src/main/java/com/scala/android/util/KeystoreTool.java" \
        "$ROOT/tests/com/scala/android/KeystoreToolHarness.java"
    java -Dfile.encoding=UTF-8 -cp "$WORK/out" com.scala.android.KeystoreToolHarness \
        "$WORK/pkcs12/keystore.p12" changeit changeit release JKS
else
    echo "跳过：找不到模板密钥库"
fi

echo
echo "全部自检通过。"
