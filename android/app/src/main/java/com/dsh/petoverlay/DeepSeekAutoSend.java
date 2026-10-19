package com.dsh.petoverlay;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * 无障碍自动发送：鲸鱼娘把一句话交给 DeepSeek App 之后，由这里替用户点「发送」。
 *
 * <p>权限模型：只在 {@link DeepSeekShare#arm(String)} 刚调用过、且 15 秒内生效；其余时间这个服务即使开着也
 * 什么都不做（监听范围还限制在 com.deepseek.chat 这个包，看不到别的应用）。触发条件同时满足才点：界面上出现了
 * 我们塞进去的那段文字 + 找到 content-desc="发送" 的节点。
 */
public class DeepSeekAutoSend extends AccessibilityService {

  private final Handler h = new Handler(Looper.getMainLooper());
  private boolean running = false;

  /** 自检日志：Android/data/com.dsh.petoverlay/files/a11y.log（shell 可读） */
  private void logLine(String msg) {
    Log.i("PetOverlay", msg);
    try {
      File dir = getExternalFilesDir(null);
      if (dir == null) return;
      FileWriter w = new FileWriter(new File(dir, "a11y.log"), true);
      w.write(
          new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date()) + " " + msg + "\n");
      w.close();
    } catch (Throwable ignored) {
    }
  }

  private final Runnable probe =
      new Runnable() {
        @Override
        public void run() {
          if (!DeepSeekShare.armedFresh()) {
            running = false;
            DeepSeekShare.disarm();
            return;
          }
          boolean done = clickSend();
          logLine("clickSend -> " + done);
          if (done) {
            DeepSeekShare.disarm();
            running = false;
            return;
          }
          h.postDelayed(this, 400);
        }
      };

  @Override
  protected void onServiceConnected() {
    super.onServiceConnected();
    logLine("service connected");
  }

  @Override
  public void onAccessibilityEvent(AccessibilityEvent e) {
    if (e == null) return;
    CharSequence p = e.getPackageName();
    if (p == null || !DeepSeekShare.PKG.contentEquals(p)) return;
    logLine("event " + p + " armed=" + DeepSeekShare.armedFresh());
    if (!DeepSeekShare.armedFresh()) return;
    if (!running) {
      running = true;
      h.removeCallbacks(probe);
      h.postDelayed(probe, 350);
    }
  }

  /** 树里既有我们塞的文字、又有「发送」按钮时，点它。 */
  private boolean clickSend() {
    AccessibilityNodeInfo root = getRootInActiveWindow();
    if (root == null) return false;
    String prefix = DeepSeekShare.armedPrefix;
    if (prefix == null || prefix.isEmpty()) return false;

    boolean textThere = false;
    AccessibilityNodeInfo send = null;
    ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
    q.add(root);
    int guard = 0;
    while (!q.isEmpty() && guard++ < 500) {
      AccessibilityNodeInfo n = q.poll();
      if (n == null) continue;
      CharSequence t = n.getText();
      CharSequence d = n.getContentDescription();
      if (t != null && t.length() >= prefix.length() && t.toString().startsWith(prefix)) {
        textThere = true;
      }
      if (d != null && "发送".contentEquals(d)) send = n;
      int c = n.getChildCount();
      for (int i = 0; i < c; i++) {
        AccessibilityNodeInfo ch = n.getChild(i);
        if (ch != null) q.add(ch);
      }
    }
    if (!textThere || send == null) {
      logLine("wait: text=" + textThere + " btn=" + (send != null));
      return false;
    }

    AccessibilityNodeInfo target = send;
    while (target != null && !target.isClickable()) target = target.getParent();
    if (target == null) target = send;
    return target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
  }

  @Override
  public void onInterrupt() {
    running = false;
    h.removeCallbacks(probe);
  }
}
