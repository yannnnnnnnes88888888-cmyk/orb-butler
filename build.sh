#!/usr/bin/env bash
# 本地构建（Linux / macOS / WSL）：没有 gradle 就自动下载一份
set -e
cd "$(dirname "$0")"

if ! command -v gradle >/dev/null 2>&1; then
  echo "没有找到 gradle，正在下载 Gradle 8.2 ..."
  mkdir -p .gradle-dist
  curl -sSL -o .gradle-dist/gradle.zip https://services.gradle.org/distributions/gradle-8.2-bin.zip
  unzip -q -o .gradle-dist/gradle.zip -d .gradle-dist
  export PATH="$PWD/.gradle-dist/gradle-8.2/bin:$PATH"
fi

# 有签名文件就打正式包，没有也能打出可安装的包
if [ ! -f orb.jks ]; then
  keytool -genkeypair -v -keystore orb.jks -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass orb1234 -keypass orb1234 -alias orb \
    -dname "CN=OrbButler, OU=App, O=Orb, L=Orb, S=Orb, C=CN"
fi

gradle assembleRelease assembleDebug --no-daemon
echo ""
echo "APK 输出目录："
ls -lh app/build/outputs/apk/release/ app/build/outputs/apk/debug/ 2>/dev/null || true
