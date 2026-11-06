#!/usr/bin/env bash
# Build a Kylin V10 SP1 (x86_64) offline portable package from a Rebased Linux tar.gz archive.
set -euo pipefail

INPUT_TAR=""
VERSION=""
SUFFIX=""
OUT_DIR=""
ZH_PLUGIN_JAR=""
ZH_OVERLAY_DIR=""

while [ $# -gt 0 ]; do
  case "$1" in
    --input)
      INPUT_TAR="${2:-}"
      shift 2
      ;;
    --version)
      VERSION="${2:-}"
      shift 2
      ;;
    --suffix)
      SUFFIX="${2:-}"
      shift 2
      ;;
    --out)
      OUT_DIR="${2:-}"
      shift 2
      ;;
    --zh-plugin-jar)
      ZH_PLUGIN_JAR="${2:-}"
      shift 2
      ;;
    --zh-overlay)
      ZH_OVERLAY_DIR="${2:-}"
      shift 2
      ;;
    *)
      echo "ERROR: Unknown argument: $1" >&2
      echo "Usage: $0 --input <rebased.tar.gz> --version <x.y.z> --suffix <suffix> --out <dir> [--zh-plugin-jar <jar> --zh-overlay <dir>]" >&2
      exit 1
      ;;
  esac
done

if [ -z "$INPUT_TAR" ] || [ -z "$VERSION" ] || [ -z "$SUFFIX" ] || [ -z "$OUT_DIR" ]; then
  echo "ERROR: Missing required arguments." >&2
  echo "Usage: $0 --input <rebased.tar.gz> --version <x.y.z> --suffix <suffix> --out <dir> [--zh-plugin-jar <jar> --zh-overlay <dir>]" >&2
  exit 1
fi

if { [ -n "$ZH_PLUGIN_JAR" ] && [ -z "$ZH_OVERLAY_DIR" ]; } || { [ -z "$ZH_PLUGIN_JAR" ] && [ -n "$ZH_OVERLAY_DIR" ]; }; then
  echo "ERROR: --zh-plugin-jar and --zh-overlay must be provided together." >&2
  exit 1
fi

if [ ! -f "$INPUT_TAR" ]; then
  echo "ERROR: Input archive does not exist: $INPUT_TAR" >&2
  exit 1
fi

if [ -n "$ZH_PLUGIN_JAR" ]; then
  if [ ! -f "$ZH_PLUGIN_JAR" ]; then
    echo "ERROR: Chinese language pack jar does not exist: $ZH_PLUGIN_JAR" >&2
    exit 1
  fi
  if [ ! -d "$ZH_OVERLAY_DIR" ]; then
    echo "ERROR: Chinese language pack overlay directory does not exist: $ZH_OVERLAY_DIR" >&2
    exit 1
  fi
  ZH_PLUGIN_JAR="$(cd "$(dirname "$ZH_PLUGIN_JAR")" && pwd)/$(basename "$ZH_PLUGIN_JAR")"
  ZH_OVERLAY_DIR="$(cd "$ZH_OVERLAY_DIR" && pwd)"
fi

INPUT_TAR="$(cd "$(dirname "$INPUT_TAR")" && pwd)/$(basename "$INPUT_TAR")"
mkdir -p "$OUT_DIR"
OUT_DIR="$(cd "$OUT_DIR" && pwd)"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

ARCH="amd64"
PORTABLE_NAME="rebased-${VERSION}-${SUFFIX}_linux_${ARCH}_portable"
STAGE_ROOT="$(mktemp -d "${OUT_DIR}/.stage.XXXXXX")"
STAGE_DIR="$STAGE_ROOT/$PORTABLE_NAME"
OUT_TAR="$OUT_DIR/${PORTABLE_NAME}.tar.gz"
OUT_SHA256="${OUT_TAR}.sha256"

cleanup() {
  rm -rf "$STAGE_ROOT"
}
trap cleanup EXIT

mkdir -p "$STAGE_DIR"
rm -f "$OUT_TAR" "$OUT_SHA256"

echo "Extracting input archive: $INPUT_TAR"
tar -xzf "$INPUT_TAR" -C "$STAGE_DIR" --strip-components=1

echo "Reading productVendor and dataDirectoryName from product-info.json..."
PRODUCT_INFO_JSON="$STAGE_DIR/product-info.json"
if [ ! -f "$PRODUCT_INFO_JSON" ]; then
  echo "ERROR: Missing $PRODUCT_INFO_JSON in extracted distribution" >&2
  exit 1
fi
PRODUCT_VENDOR="$(python3 -c 'import json, sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["productVendor"])' "$PRODUCT_INFO_JSON")"
DATA_DIR_NAME="$(python3 -c 'import json, sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["dataDirectoryName"])' "$PRODUCT_INFO_JSON")"
if [ -z "$PRODUCT_VENDOR" ] || [ -z "$DATA_DIR_NAME" ]; then
  echo "ERROR: Failed to read productVendor or dataDirectoryName from $PRODUCT_INFO_JSON" >&2
  exit 1
fi
echo "Detected product paths: vendor=${PRODUCT_VENDOR}, dataDirectoryName=${DATA_DIR_NAME}"

echo "Verifying compareWithRef classes in vcs-git.jar..."
VCS_GIT_JAR="$STAGE_DIR/plugins/vcs-git/lib/vcs-git.jar"
if [ ! -f "$VCS_GIT_JAR" ]; then
  echo "ERROR: Missing $VCS_GIT_JAR in extracted distribution" >&2
  exit 1
fi
if ! unzip -l "$VCS_GIT_JAR" | grep -q "git4idea/actions/compareWithRef/"; then
  echo "ERROR: $VCS_GIT_JAR does not contain git4idea/actions/compareWithRef/ classes" >&2
  exit 1
fi
echo "Verified compareWithRef classes in vcs-git.jar."

if [ -n "$ZH_PLUGIN_JAR" ]; then
  echo "Patching and bundling Chinese language pack..."
  python3 "$SCRIPT_DIR/patch-zh-plugin.py" \
    --jar "$ZH_PLUGIN_JAR" \
    --overlay "$ZH_OVERLAY_DIR" \
    --dist "$STAGE_DIR" \
    --out "$STAGE_DIR/extra-plugins/localization-zh/lib/localization-zh.jar"
else
  echo "Skipping Chinese language pack."
fi

echo "Checking max GLIBC version across all ELF files..."
MAX_GLIBC=$(
  strings \
    "$STAGE_DIR/bin/rebased" \
    "$STAGE_DIR/bin/restarter" \
    "$STAGE_DIR/bin/fsnotifier" \
    "$STAGE_DIR/jbr/bin/"* \
    "$STAGE_DIR/jbr/lib/"*.so \
    "$STAGE_DIR/jbr/lib/server/"*.so \
    "$STAGE_DIR/lib/"*/*.so \
    "$STAGE_DIR/lib/"*/*/*.so 2>/dev/null \
  | grep -o "GLIBC_[0-9.]*" | sort -uV | tail -1
)
VER_NUM="${MAX_GLIBC#GLIBC_}"
echo "Detected max GLIBC symbol: ${MAX_GLIBC}"
if [ "$(printf "%s\n" "2.31" "$VER_NUM" | sort -V | tail -1)" != "2.31" ]; then
  echo "ERROR: glibc symbol ${VER_NUM} exceeds Kylin V10 SP1 baseline 2.31" >&2
  exit 1
fi

echo "Copying top-level icon..."
cp -f "$STAGE_DIR/bin/rebased.png" "$STAGE_DIR/rebased.png"

if [ -f "$SCRIPT_DIR/make-filter-demo-repo.sh" ]; then
  mkdir -p "$STAGE_DIR/.github/kylin"
  cp -f "$SCRIPT_DIR/make-filter-demo-repo.sh" "$STAGE_DIR/.github/kylin/make-filter-demo-repo.sh"
  chmod +x "$STAGE_DIR/.github/kylin/make-filter-demo-repo.sh"
fi

echo "Generating top-level launcher script (rebased)..."
cat > "$STAGE_DIR/rebased" <<'EOF'
#!/bin/sh
set -e

warn() {
  echo "[WARN] $*" >&2
}

resolve_self() {
  if command -v readlink >/dev/null 2>&1 && readlink -f "$0" >/dev/null 2>&1; then
    readlink -f "$0"
  elif command -v realpath >/dev/null 2>&1; then
    realpath "$0"
  else
    case "$0" in
      /*) printf "%s\n" "$0" ;;
      *) printf "%s/%s\n" "$(pwd)" "$0" ;;
    esac
  fi
}

escape_desktop_exec_arg() {
  printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g; s/`/\\`/g; s/\$/\\$/g'
}

SELF_PATH="$(resolve_self)"
APP_DIR="$(cd "$(dirname "$SELF_PATH")" && pwd)"
APP_ID="rebased"
WM_CLASS="jetbrains-rebased"
ICON_FILE="$APP_DIR/rebased.png"
ESCAPED_LAUNCHER="$(escape_desktop_exec_arg "$APP_DIR/rebased")"

PRODUCT_VENDOR="__PRODUCT_VENDOR__"
DATA_DIR_NAME="__DATA_DIR_NAME__"
XDG_DATA_HOME="${XDG_DATA_HOME:-$HOME/.local/share}"
XDG_CONFIG_HOME="${XDG_CONFIG_HOME:-$HOME/.config}"
XDG_CACHE_HOME="${XDG_CACHE_HOME:-$HOME/.cache}"
PLUGINS_DIR="$XDG_DATA_HOME/$PRODUCT_VENDOR/$DATA_DIR_NAME"
CONFIG_DIR="$XDG_CONFIG_HOME/$PRODUCT_VENDOR/$DATA_DIR_NAME"
CACHE_DIR="$XDG_CACHE_HOME/$PRODUCT_VENDOR/$DATA_DIR_NAME"
APPS_DIR="$XDG_DATA_HOME/applications"
ICON_128_DIR="$XDG_DATA_HOME/icons/hicolor/128x128/apps"

if [ "${1:-}" = "--uninstall" ]; then
  rm -f "$APPS_DIR/${APP_ID}.desktop" \
        "$APPS_DIR/${APP_ID}-uninstall.desktop" \
        "$APPS_DIR/${WM_CLASS}.desktop" \
        "$ICON_128_DIR/${APP_ID}.png" || warn "Failed to remove some desktop integration files."
  rm -rf "$PLUGINS_DIR/localization-zh" || warn "Failed to remove $PLUGINS_DIR/localization-zh."
  if command -v xdg-desktop-menu >/dev/null 2>&1; then
    xdg-desktop-menu forceupdate --mode user || warn "xdg-desktop-menu forceupdate failed."
  fi
  if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database "$APPS_DIR" || warn "update-desktop-database failed."
  fi
  echo "[已卸载] 已移除 Rebased 用户级开始菜单快捷方式、图标与中文语言包插件。"
  echo "如需彻底清理程序与个人数据，请手动删除："
  echo "  1. 程序目录: $APP_DIR"
  echo "  2. 配置目录: $CONFIG_DIR"
  echo "  3. 缓存目录: $CACHE_DIR"
  exit 0
fi

# Heal executable permissions if archive extraction stripped them.
chmod +x "$APP_DIR/bin/rebased" "$APP_DIR/bin/rebased.sh" "$APP_DIR/bin/fsnotifier" "$APP_DIR/bin/restarter" || warn "Failed to chmod bin executables."
chmod +x "$APP_DIR/jbr/bin/"* "$APP_DIR/jbr/lib/jexec" "$APP_DIR/jbr/lib/jspawnhelper" || warn "Failed to chmod jbr executables."

# Idempotently install user-level desktop entries and 128x128 icon.
mkdir -p "$APPS_DIR" "$ICON_128_DIR" || warn "Failed to create desktop integration directories."
if [ -f "$ICON_FILE" ]; then
  cp -f "$ICON_FILE" "$ICON_128_DIR/${APP_ID}.png" || warn "Failed to copy icon to $ICON_128_DIR."
else
  warn "Icon file not found: $ICON_FILE"
fi

if ! cat > "$APPS_DIR/${APP_ID}.desktop" <<DESKTOP_EOF
[Desktop Entry]
Type=Application
Version=1.0
Name=Rebased
Comment=Git 图形客户端 (Rebased)
Exec="${ESCAPED_LAUNCHER}" %f
Icon=${ICON_FILE}
StartupWMClass=${WM_CLASS}
StartupNotify=true
Categories=Development;RevisionControl;IDE;
Terminal=false
DESKTOP_EOF
then
  warn "Failed to write $APPS_DIR/${APP_ID}.desktop"
else
  chmod +x "$APPS_DIR/${APP_ID}.desktop" || warn "Failed to chmod $APPS_DIR/${APP_ID}.desktop"
fi

if ! cat > "$APPS_DIR/${APP_ID}-uninstall.desktop" <<UNINSTALL_EOF
[Desktop Entry]
Type=Application
Version=1.0
Name=卸载 Rebased
Comment=移除 Rebased 开始菜单快捷方式
Exec="${ESCAPED_LAUNCHER}" --uninstall
Icon=${ICON_FILE}
Categories=Development;
Terminal=true
UNINSTALL_EOF
then
  warn "Failed to write $APPS_DIR/${APP_ID}-uninstall.desktop"
else
  chmod +x "$APPS_DIR/${APP_ID}-uninstall.desktop" || warn "Failed to chmod $APPS_DIR/${APP_ID}-uninstall.desktop"
fi

if command -v xdg-desktop-menu >/dev/null 2>&1; then
  xdg-desktop-menu forceupdate --mode user || warn "xdg-desktop-menu forceupdate failed."
fi
if command -v update-desktop-database >/dev/null 2>&1; then
  update-desktop-database "$APPS_DIR" || warn "update-desktop-database failed."
fi

# Initialize default Simplified Chinese locale on first run and sync bundled localization-zh plugin.
EXTRA_ZH_DIR="$APP_DIR/extra-plugins/localization-zh"
EXTRA_ZH_JAR="$EXTRA_ZH_DIR/lib/localization-zh.jar"
if [ -d "$EXTRA_ZH_DIR" ] && [ -f "$EXTRA_ZH_JAR" ]; then
  if [ ! -d "$CONFIG_DIR" ]; then
    if mkdir -p "$CONFIG_DIR"; then
      printf 'i18n.locale\nzh-CN\n' > "$CONFIG_DIR/early-access-registry.txt" || warn "Failed to write $CONFIG_DIR/early-access-registry.txt"
    else
      warn "Failed to create $CONFIG_DIR"
    fi
  fi

  TARGET_ZH_DIR="$PLUGINS_DIR/localization-zh"
  TARGET_ZH_JAR="$TARGET_ZH_DIR/lib/localization-zh.jar"
  NEED_SYNC=1
  if [ -f "$TARGET_ZH_JAR" ] && command -v cmp >/dev/null 2>&1; then
    if cmp -s "$EXTRA_ZH_JAR" "$TARGET_ZH_JAR"; then
      NEED_SYNC=0
    fi
  fi

  if [ "$NEED_SYNC" -eq 1 ]; then
    if mkdir -p "$PLUGINS_DIR"; then
      TMP_ZH_DIR="$PLUGINS_DIR/.localization-zh.tmp.$$"
      rm -rf "$TMP_ZH_DIR"
      if cp -R "$EXTRA_ZH_DIR" "$TMP_ZH_DIR"; then
        rm -rf "$TARGET_ZH_DIR" && mv "$TMP_ZH_DIR" "$TARGET_ZH_DIR" || warn "Failed to replace $TARGET_ZH_DIR"
      else
        rm -rf "$TMP_ZH_DIR"
        warn "Failed to copy $EXTRA_ZH_DIR to $TMP_ZH_DIR"
      fi
    else
      warn "Failed to create $PLUGINS_DIR"
    fi
  fi
fi

export IDEA_RESTART_VIA_EXIT_CODE=88
while true; do
  set +e
  "$APP_DIR/bin/rebased" "$@"
  exit_code=$?
  set -e
  if [ "$exit_code" -ne "$IDEA_RESTART_VIA_EXIT_CODE" ]; then
    exit "$exit_code"
  fi
done
EOF

echo "Generating assertion-style dependency checker (check-deps.sh)..."
cat > "$STAGE_DIR/check-deps.sh" <<'EOF'
#!/bin/sh
# Rebased 银河麒麟桌面 V10 SP1 (x86_64) 离线环境依赖断言检测脚本

SELF_DIR="$(cd "$(dirname "$0")" && pwd)"
PRODUCT_VENDOR="__PRODUCT_VENDOR__"
DATA_DIR_NAME="__DATA_DIR_NAME__"
PLUGINS_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/$PRODUCT_VENDOR/$DATA_DIR_NAME"
fail=0

echo "=== 1. CPU 架构检测 ==="
ARCH="$(uname -m)"
if [ "$ARCH" = "x86_64" ]; then
  echo "[OK] CPU 架构: $ARCH"
else
  echo "[不匹配] 当前 CPU 架构为 $ARCH，本便携包仅适用于 x86_64 (amd64)"
  fail=1
fi

echo ""
echo "=== 2. glibc 版本检测 (要求 >= 2.28, 麒麟 V10 SP1 基线为 2.31) ==="
GLIBC_VER="$(ldd --version 2>/dev/null | head -n 1 | awk '{print $NF}')"
if [ -n "$GLIBC_VER" ]; then
  LOWEST="$(printf "%s\n" "2.28" "$GLIBC_VER" | sort -V | head -n 1)"
  if [ "$LOWEST" = "2.28" ]; then
    echo "[OK] glibc 版本: $GLIBC_VER"
  else
    echo "[过低] glibc 版本 $GLIBC_VER 低于最低要求 2.28"
    fail=1
  fi
else
  echo "[警告] 无法通过 ldd --version 检测 glibc 版本"
fi

echo ""
echo "=== 3. Git 命令行检测 ==="
if command -v git >/dev/null 2>&1; then
  echo "[OK] $(git --version)"
else
  echo "[缺失] git —— 请从内网源安装: sudo apt install git"
  fail=1
fi

echo ""
echo "=== 4. 系统图形与字体动态库检测 ==="
for lib in \
  libX11.so.6 \
  libXext.so.6 \
  libXi.so.6 \
  libXrender.so.1 \
  libXtst.so.6 \
  libfreetype.so.6 \
  libfontconfig.so.1 \
  libGL.so.1 \
  libasound.so.2 \
  libstdc++.so.6 \
  libgcc_s.so.1 \
  libz.so.1
do
  if ldconfig -p 2>/dev/null | grep -q "$lib"; then
    echo "[OK] $lib"
  else
    echo "[缺失] $lib —— 请从内网源安装: sudo apt install libx11-6 libxext6 libxi6 libxrender1 libxtst6 libfreetype6 fontconfig libgl1 libasound2"
    fail=1
  fi
done

echo ""
echo "=== 5. 内置 JBR 运行时自检 ==="
if [ -x "$SELF_DIR/jbr/bin/java" ]; then
  if "$SELF_DIR/jbr/bin/java" -version >/dev/null 2>&1; then
    JBR_INFO="$("$SELF_DIR/jbr/bin/java" -version 2>&1 | head -n 2 | tr '\n' ' ')"
    echo "[OK] 内置 JBR 可正常执行: $JBR_INFO"
  else
    echo "[异常] 内置 JBR ($SELF_DIR/jbr/bin/java) 执行失败，请检查文件权限或挂载分区是否有 noexec 限制"
    fail=1
  fi
else
  echo "[缺失] $SELF_DIR/jbr/bin/java 无可执行权限，请运行: chmod -R +x \"$SELF_DIR/jbr/bin\" \"$SELF_DIR/bin\""
  fail=1
fi

echo ""
echo "=== 6. 图形桌面会话检测 ==="
if [ -n "${DISPLAY:-}" ] || [ -n "${WAYLAND_DISPLAY:-}" ]; then
  echo "[OK] 图形会话已就绪 (DISPLAY=${DISPLAY:-}, WAYLAND_DISPLAY=${WAYLAND_DISPLAY:-})"
else
  echo "[警告] 未检测到图形会话环境变量 (DISPLAY / WAYLAND_DISPLAY)，请在麒麟桌面终端中运行"
  fail=1
fi

echo ""
echo "=== 7. 开始菜单快捷方式图标检测 ==="
DESKTOP_FILE="${XDG_DATA_HOME:-$HOME/.local/share}/applications/rebased.desktop"
if [ -f "$DESKTOP_FILE" ]; then
  ICON_VAL="$(grep '^Icon=' "$DESKTOP_FILE" | head -n 1 | cut -d= -f2-)"
  if [ -n "$ICON_VAL" ] && [ -f "$ICON_VAL" ]; then
    echo "[OK] 菜单图标存在: $ICON_VAL"
  else
    echo "[缺失] 菜单项 Icon 指向的文件不存在: ${ICON_VAL:-(未设置)} —— 请重新运行一次 ./rebased 修复"
    fail=1
  fi
else
  echo "[提示] 尚未生成 $DESKTOP_FILE（首次运行 ./rebased 后将自动生成）"
fi

echo ""
echo "=== 8. 中文语言包检测 ==="
BUNDLED_ZH_JAR="$SELF_DIR/extra-plugins/localization-zh/lib/localization-zh.jar"
INSTALLED_ZH_JAR="$PLUGINS_DIR/localization-zh/lib/localization-zh.jar"
if [ -f "$BUNDLED_ZH_JAR" ]; then
  if [ -f "$INSTALLED_ZH_JAR" ] && cmp -s "$BUNDLED_ZH_JAR" "$INSTALLED_ZH_JAR"; then
    echo "[OK] 中文语言包已同步: $INSTALLED_ZH_JAR"
  else
    echo "[未同步] 请运行一次 ./rebased"
  fi
else
  echo "[提示] 本包不含中文语言包"
fi

echo ""
if [ "$fail" -eq 0 ]; then
  echo ">>> 所有检测通过！可以直接运行 ./rebased 启动程序。"
else
  echo ">>> 存在未通过项，请根据上方提示排查后再启动。"
fi
exit "$fail"
EOF

python3 - "$STAGE_DIR/rebased" "$STAGE_DIR/check-deps.sh" "$PRODUCT_VENDOR" "$DATA_DIR_NAME" <<'PYEOF'
import sys
from pathlib import Path

vendor = sys.argv[3]
data_dir = sys.argv[4]
for file_path in sys.argv[1:3]:
    p = Path(file_path)
    text = p.read_text(encoding="utf-8")
    text = text.replace("__PRODUCT_VENDOR__", vendor).replace("__DATA_DIR_NAME__", data_dir)
    p.write_text(text, encoding="utf-8")
PYEOF

echo "Generating Chinese user guide (启动说明.txt)..."
cat > "$STAGE_DIR/启动说明.txt" <<EOF
========================================================================
  Rebased v${VERSION} (${SUFFIX}) 银河麒麟桌面版 (V10 SP1 / x86_64) 绿色便携包使用说明
========================================================================

本包为免安装绿色便携版，已内置 JetBrains Runtime (JBR 25) 运行环境，
全程零联网、无需管理员 (root/sudo) 密码即可运行与集成开始菜单。

一、首次使用步骤
------------------------------------------------------------------------
1. 校验完整性（可选，在压缩包所在目录终端执行）：
   sha256sum -c ${PORTABLE_NAME}.tar.gz.sha256

2. 解压便携包：
   tar -xzf ${PORTABLE_NAME}.tar.gz
   cd ${PORTABLE_NAME}

3. 环境与依赖自检：
   ./check-deps.sh
   （若提示无执行权限，请先执行：chmod +x check-deps.sh rebased）

4. 启动程序：
   ./rebased

二、开始菜单快捷方式与移动目录说明
------------------------------------------------------------------------
- 每次运行 ./rebased 时，程序会自动在当前用户下创建/更新麒麟「开始菜单」
  中的「Rebased」图标（使用绝对路径指向 rebased.png），无需管理员密码。
- 如果您将该文件夹移动到了其他位置，只需在新位置重新运行一次 ./rebased，
  开始菜单快捷方式会自动修复为新路径。

三、Compare with Branch or Tag… 按作者过滤与复制路径说明
------------------------------------------------------------------------
- 在项目目录右键 -> Git -> Compare with Branch or Tag… 选择目标分支后，
  弹出的 Changes 面板顶部工具栏提供：
  1. User（用户）过滤按钮：与 Log 面板交互一致，支持直接打字搜索作者、
     选择 me（当前 Git 用户）、查看 Recent（最近过滤记录）或点击 Select…
     多选作者；每次过滤变更后文件差异树会自动全部展开。
  2. Copy Paths（复制路径）按钮及右键菜单：将当前过滤后的文件相对路径按
     [新增]、[修改]、[重命名/移动]、[删除] 分组复制到剪贴板。

四、卸载与清理方式
------------------------------------------------------------------------
1. 移除开始菜单图标与中文语言包插件：
   在开始菜单点击「卸载 Rebased」，或在终端执行：
   ./rebased --uninstall
2. 彻底删除：
   直接删除本程序文件夹即可。
   （个人配置文件位于 ~/.config/${PRODUCT_VENDOR}/${DATA_DIR_NAME}，缓存位于 ~/.cache/${PRODUCT_VENDOR}/${DATA_DIR_NAME}）

五、中文界面
------------------------------------------------------------------------
1. 首次运行（当前用户尚无配置目录 ~/.config/${PRODUCT_VENDOR}/${DATA_DIR_NAME}）时，
   启动脚本会自动同步内置简体中文语言包并将界面语言默认设为简体中文。
2. 如已有历史配置目录，启动脚本不会覆盖您当前的语言设置。您可在程序内打开：
   Settings / 设置 -> Appearance & Behavior / 外观与行为 -> System Settings / 系统设置 -> Language and Region / 语言和地区，
   在 Language / 语言下拉框中切换「简体中文」或「English」。
3. 切换界面语言后，需按提示重启 Rebased 生效。
EOF

chmod +x "$STAGE_DIR/rebased" "$STAGE_DIR/check-deps.sh"

if command -v xattr >/dev/null 2>&1; then
  xattr -cr "$STAGE_DIR" 2>/dev/null || true
fi

echo "Building portable tar.gz archive: $OUT_TAR"
cd "$STAGE_ROOT"
if tar --version 2>/dev/null | grep -qi "bsdtar"; then
  COPYFILE_DISABLE=1 tar --no-xattrs --no-mac-metadata -czf "$OUT_TAR" "$PORTABLE_NAME"
else
  tar -czf "$OUT_TAR" "$PORTABLE_NAME"
fi

echo "Generating sha256 checksum file: $OUT_SHA256"
cd "$OUT_DIR"
if command -v sha256sum >/dev/null 2>&1; then
  sha256sum --binary "${PORTABLE_NAME}.tar.gz" > "$OUT_SHA256"
else
  shasum --algorithm 256 --binary "${PORTABLE_NAME}.tar.gz" > "$OUT_SHA256"
fi

echo "Done!"
ls -lh "$OUT_TAR" "$OUT_SHA256"
cat "$OUT_SHA256"
