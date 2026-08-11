#!/usr/bin/env bash
# ============================================================================
# 构建客户端 + 管理端 APK
# 在虚拟机或开发机上执行
# 用法：bash deploy/build-apk.sh [debug|release] [public-url]
# 例如：bash deploy/build-apk.sh release https://lottery.example.com
# ============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_DIR"

BUILD_TYPE="${1:-debug}"
PUBLIC_URL="${2:-}"

# 颜色
GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'
info()  { echo -e "${GREEN}[INFO]${NC} $1"; }
step()  { echo -e "\n${BLUE}=== $1 ===${NC}"; }

# 检查环境
if [[ -z "${JAVA_HOME:-}" ]]; then
  if command -v java &>/dev/null; then
    export JAVA_HOME=$(dirname $(dirname $(readlink -f $(which java))))
  fi
fi
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
export PATH="$JAVA_HOME/bin:$PATH"

if [[ -z "${ANDROID_HOME:-}" ]]; then
  if [[ -d "/opt/android-sdk" ]]; then export ANDROID_HOME="/opt/android-sdk"
  elif [[ -d "$HOME/Android/Sdk" ]]; then export ANDROID_HOME="$HOME/Android/Sdk"
  elif [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then export ANDROID_HOME="$ANDROID_SDK_ROOT"
  else
    echo -e "${YELLOW}[WARN]${NC} ANDROID_HOME 未设置，假设是开发环境已配置"
  fi
fi
export PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools"

# 替换 baseUrl
if [[ -n "$PUBLIC_URL" ]]; then
  step "替换 ApiClient.BASE_URL = $PUBLIC_URL"
  sed -i "s|const val BASE_URL = .*|const val BASE_URL = \"$PUBLIC_URL\"|" \
    "$PROJECT_DIR/app/src/main/java/com/lottery/history/network/ApiClient.kt"
  sed -i "s|private const val BASE_URL = .*|private const val BASE_URL = \"$PUBLIC_URL\"|" \
    "$PROJECT_DIR/admin-app/src/main/java/com/lottery/admin/network/AdminApi.kt"
fi

step "构建 App ($BUILD_TYPE)"
if [[ "$BUILD_TYPE" == "release" ]]; then
  ./gradlew :app:assembleRelease :admin-app:assembleRelease --no-daemon --console=plain
  APP_APK="app/build/outputs/apk/release/app-release.apk"
  ADMIN_APK="admin-app/build/outputs/apk/release/admin-app-release.apk"
else
  ./gradlew :app:assembleDebug :admin-app:assembleDebug --no-daemon --console=plain
  APP_APK="app/build/outputs/apk/debug/app-debug.apk"
  ADMIN_APK="admin-app/build/outputs/apk/debug/admin-app-debug.apk"
fi

step "✅ 构建完成"
echo ""
ls -lh "$APP_APK" "$ADMIN_APK" 2>/dev/null
echo ""
echo -e "${GREEN}APK 路径：${NC}"
echo "  客户端 App: $PROJECT_DIR/$APP_APK"
echo "  管理端 APK: $PROJECT_DIR/$ADMIN_APK"
