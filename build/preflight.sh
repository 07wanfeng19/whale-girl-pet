#!/usr/local/bin/bash
# 打包前自检（参考 dsh-peak-chip 反向验证思路）：
# 1) 关键修复代码必须在位（每条断言都对应一次真实故障，删掉就会复发）
# 2) 素材完整性（106 段 webm 含 6 段余额动画，缺了运行时会静默回退）
# 3) 打包脚本版本推导与产物命名
# 用法：bash /root/dsh/preflight.sh   （只读，不修改任何文件）
# 产物：stdout 每行一条 ✓/✗，末尾总结；全过退出 0，有 ✗ 退出 1

SRC=/root/build/PetOverlay/app/src/main/java/com/dsh/petoverlay
ASSETS=/root/build/PetOverlay/app/src/main/assets
pass=0; fail=0

ck() {  # ck <标签> <命令…>  —— 命令成功=✓
  local label="$1"; shift
  if "$@" >/dev/null 2>&1; then
    echo "  ✓ $label"; pass=$((pass+1))
  else
    echo "  ✗ $label"; fail=$((fail+1))
  fi
}
grepq() {  # grepq <标签> <文件> <pattern>
  ck "$1" grep -q -- "$3" "$2"
}

echo "── 1. 关键修复在位（VideoGlView.java）──"
V=$SRC/VideoGlView.java
grepq "Surface 重建接续在播片段（案A修复）"        $V "Surface 重建，重新接上播放"
grepq "看门狗存在且 4 秒阈值"                      $V "看门狗：4 秒没有新帧"
grepq "看门狗重播后有帧时间重置"                    $V "换片了，看门狗重新计时"
grepq "时间戳驱动取帧（案B修复 v124）"              $V "lastFrameTs"
grepq "每轮渲染必取帧的注释在位"                    $V "直到队列取干"
grepq "跨线程字段 volatile（player）"              $V "private volatile MediaPlayer player"
grepq "onCompletion 不吞 completion"               $V "onClipEnd"

echo "── 2. 关键修复在位（PetService.java）──"
P=$SRC/PetService.java
grepq "有气泡时不播工作汇报（v122修复）"            $P "if (bubbleShown) return;"

echo "── 3. 素材完整性 ──"
total=$(find $ASSETS -name '*.webm' 2>/dev/null | wc -l)
bal=$(find $ASSETS -name '余额*.webm' 2>/dev/null | wc -l)
echo "  webm 总数 = $total（应 106）"; [ "$total" -eq 106 ] && pass=$((pass+1)) || { echo "  ✗ webm 数量异常"; fail=$((fail+1)); }
echo "  余额动画 = $bal（应 6，功能已完成、必须保留）"; [ "$bal" -eq 6 ] && pass=$((pass+1)) || { echo "  ✗ 余额动画缺失"; fail=$((fail+1)); }

echo "── 4. 打包脚本一致性 ──"
grepq "NAME_VER 固定 1.1 正式版"  /root/build/build_apk.sh 'NAME_VER="1.1"'
grepq "产物复制到 /sdcard/Download"        /root/build/build_apk.sh '/sdcard/Download'

echo "── 5. 代码地图一致性 ──"
out=$(bash /root/dsh/check-map.sh 2>&1)
if echo "$out" | grep -q '^⚠️'; then
  echo "  ✗ 代码地图过期："
  echo "$out" | grep '^⚠️' | head -5
  fail=$((fail+1))
else
  echo "  ✓ 无 ⚠️，地图是准的"
  pass=$((pass+1))
fi

echo
if [ "$fail" -eq 0 ]; then
  echo "自检通过：$pass 项全绿，可以打包"
  exit 0
else
  echo "自检失败：$pass 过 / $fail 不过 —— 先修复再打包"
  exit 1
fi
