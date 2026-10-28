#!/bin/bash
# 鲸鱼娘桌宠 APK 打包脚本（手动链）
# 关键点：
#  1) aapt2 link 不带 -A，assets 由 python 直接按目录打包，
#     避免 aapt2 写中文名时缺 UTF-8 标志位导致 python 按 cp437 读成乱码。
#  2) .webm/.png/.ttf/.arsc 必须 ZIP_STORED，否则 AssetFileDescriptor/openFd 打不开。
#  3) 所有 ZipInfo 打 0x800 标志，中文名按 UTF-8 写入。
set -e
VER="${1:?用法: build_apk.sh <版本号，如 54>}"
# 打包前自检（2026-10-19 加，参考 dsh-peak-chip 反向验证思路）：
# 关键修复在位 + 素材完整 + 地图准确，不过就不打
if [ -f /root/dsh/preflight.sh ]; then
  bash /root/dsh/preflight.sh || { echo "preflight 未通过，终止打包"; exit 1; }
fi
# 版本名规则（2026-10-28 用户定版，2026-10-28 升级）：鲸鱼娘1.1正式版。versionName 固定 1.1，
# versionCode 仍按传入参数递增（149→150→…→157→…），保证系统升级检测不倒退。
NAME_VER="1.1"

# 应用显示名（2026-10-28 正式更名，升级 1.1）：桌面图标名与产物文件名统一为「鲸鱼娘1.1正式版」
STRINGS=/root/build/PetOverlay/app/src/main/res/values/strings.xml
if [ -f "$STRINGS" ]; then
  sed -i -E "s|(<string name=\"app_name\">)[^<]*(</string>)|\1鲸鱼娘1.1正式版\2|" "$STRINGS"
  echo "app_name → 鲸鱼娘1.1正式版"
fi

export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64
export PATH=$JAVA_HOME/bin:$PATH
BT=/opt/android-sdk/build-tools/36.0.0
PLATFORM=/opt/android-sdk/platforms/android-37.0/android.jar

cd /root/build/PetOverlay
B=$PWD/build-manual
rm -rf "$B/compiled" "$B/gen" "$B/classes" "$B/dex" "$B/unsigned.apk" "$B/aligned.apk"
mkdir -p "$B/compiled" "$B/gen" "$B/classes" "$B/dex"

echo "[1/6] aapt2 compile"
"$BT/aapt2" compile --dir app/src/main/res -o "$B/compiled/res.zip"

echo "[2/6] aapt2 link（不含 assets）"
"$BT/aapt2" link -o "$B/base.apk" -I "$PLATFORM" \
  --manifest app/src/main/AndroidManifest.xml \
  --java "$B/gen" \
  --min-sdk-version 30 --target-sdk-version 35 \
  --version-code "$VER" --version-name "$NAME_VER" --replace-version \
  "$B/compiled/res.zip"

echo "[3/6] javac"
javac -source 17 -target 17 -encoding UTF-8 -classpath "$PLATFORM" \
  -d "$B/classes" $(find app/src/main/java "$B/gen" -name "*.java")
[ -f "$B/classes/com/dsh/petoverlay/PetService.class" ]

echo "[4/6] d8"
"$BT/d8" --lib "$PLATFORM" --min-api 30 --output "$B/dex" $(find "$B/classes" -name "*.class")

echo "[5/6] 打包（assets 从源码目录读，文件名正确）"
python3 - "$B" <<'PYEOF'
import zipfile, os, sys
B = sys.argv[1]
ASSETS = 'app/src/main/assets'
STORED = {'.arsc', '.webm', '.png', '.ttf', '.ogg', '.mp3', '.wav'}
out = os.path.join(B, 'unsigned.apk')

def zi(name):
    z = zipfile.ZipInfo(name, date_time=(2024, 1, 1, 0, 0, 0))
    z.flag_bits |= 0x800          # UTF-8 文件名
    return z

with zipfile.ZipFile(out, 'w') as zout:
    # aapt2 产物：manifest / resources.arsc / res/*
    with zipfile.ZipFile(os.path.join(B, 'base.apk')) as zin:
        for it in zin.infolist():
            data = zin.read(it.filename)
            ext = os.path.splitext(it.filename)[1].lower()
            z = zi(it.filename)
            z.compress_type = zipfile.ZIP_STORED if (it.filename == 'resources.arsc' or ext in STORED) else zipfile.ZIP_DEFLATED
            zout.writestr(z, data)

    # dex
    for d in sorted(os.listdir(os.path.join(B, 'dex'))):
        if d.endswith('.dex'):
            z = zi(d)
            z.compress_type = zipfile.ZIP_DEFLATED
            with open(os.path.join(B, 'dex', d), 'rb') as f:
                zout.writestr(z, f.read())

    # assets：按目录递归，文件名保持原始 Unicode
    n = 0
    for root, _, files in os.walk(ASSETS):
        for fn in sorted(files):
            full = os.path.join(root, fn)
            rel = os.path.relpath(full, ASSETS).replace(os.sep, '/')
            name = 'assets/' + rel
            ext = os.path.splitext(fn)[1].lower()
            z = zi(name)
            z.compress_type = zipfile.ZIP_STORED if ext in STORED else zipfile.ZIP_DEFLATED
            with open(full, 'rb') as f:
                zout.writestr(z, f.read())
            n += 1
    print('  assets 写入:', n, '个')

# 自检：名字必须全部能按 UTF-8 正确表示
with zipfile.ZipFile(out) as z:
    bad = [i.filename for i in z.infolist()
           if any(0x80 <= ord(c) <= 0xFF for c in i.filename)]
    if bad:
        raise SystemExit('文件名仍含 Latin-1 字符: ' + repr(bad[:3]))
    webm = [i for i in z.infolist() if i.filename.startswith('assets/webm/')]
    assert len(webm) == 106, 'webm 数量不对: %d' % len(webm)
    assert all(i.compress_type == zipfile.ZIP_STORED for i in webm), 'webm 必须 STORED'
    print('  自检通过: webm', len(webm), '个，全部 STORED，文件名正常')
    sample = [i.filename for i in webm if not i.filename.isascii()][:2]
    print('  中文名样例:', sample)
PYEOF

echo "[6/6] zipalign + 签名"
"$BT/zipalign" -p -f 4 "$B/unsigned.apk" "$B/aligned.apk"
"$BT/apksigner" sign --ks /root/build/debug.keystore --ks-pass pass:android \
  --key-pass pass:android --ks-key-alias androiddebugkey \
  --out "$B/petv$VER.apk" "$B/aligned.apk"

cp "$B/petv$VER.apk" "/sdcard/Download/鲸鱼娘1.1正式版.apk"

# 只保留当前版本的中文封包：Download 里不再落 petv$VER.apk，
# 旧中文包（含历史「实验版」「1.0正式版」）、以及残留的 petv*.apk / .idsig 一律清掉。
for f in /sdcard/Download/petv*.apk /sdcard/Download/鲸鱼娘*实验版.apk /sdcard/Download/鲸鱼娘*.apk; do
  [ -e "$f" ] || continue
  case "$f" in
    */鲸鱼娘1.1正式版.apk) continue ;;
  esac
  rm -f "$f" "$f.idsig"
done
rm -f /sdcard/Download/petv*.apk.idsig
echo "已清理旧包，只保留 /sdcard/Download/鲸鱼娘1.1正式版.apk"

shiz cp "/sdcard/Download/鲸鱼娘1.1正式版.apk" "/data/local/tmp/petv$VER.apk"
shiz chmod 644 "/data/local/tmp/petv$VER.apk"
echo "--- 版本校验 ---"
"$BT/aapt2" dump badging "$B/petv$VER.apk" | head -1
echo "--- 安装 ---"
shiz pm install -r "/data/local/tmp/petv$VER.apk" 2>&1 | head -3
shiz dumpsys package com.dsh.petoverlay 2>/dev/null | grep -E 'versionCode|versionName|lastUpdateTime'

# 打完包顺手刷新代码地图（省得忘记，检查用 bash /root/dsh/check-map.sh）
if [ -f /root/dsh/gen-map.sh ]; then
  bash /root/dsh/gen-map.sh
fi
