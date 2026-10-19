package com.dsh.petoverlay;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/**
 * 单向外发通道：把一段文字交给手机里的 DeepSeek App（com.deepseek.chat）。
 *
 * <p>DeepSeek App 导出了 {@code com.deepseek.chat.ShareProxyActivity}，收 ACTION_SEND +
 * text/plain，会带着文本进新会话并直接发送，所以在 App 侧不需要登录态、不需要 API Key，也不依赖
 * DSH 工作台。没装 App 时退回系统分享面板，交给用户自己选。
 */
public final class DeepSeekShare {

  /** 小米/OPPO 等厂商包名一致，DeepSeek 官方包名固定为这个 */
  public static final String PKG = "com.deepseek.chat";


  /** 自动发送的有效窗口：arm() 之后 15 秒内才管用 */
  public static final long ARM_MS = 15000;

  /** 交给无障碍服务去比对的文本前缀（输入框里出现它，才认为内容就位） */
  static volatile String armedPrefix = null;
  private static volatile long armedAt = 0L;

  /** 告诉无障碍服务：接下来 15 秒内，DeepSeek 界面里出现这段文字就点「发送」 */
  public static void arm(String text) {
    if (text == null) return;
    String t = text.trim();
    if (t.isEmpty()) return;
    armedPrefix = t.length() > 24 ? t.substring(0, 24) : t;
    armedAt = System.currentTimeMillis();
  }

  static boolean armedFresh() {
    return armedPrefix != null && System.currentTimeMillis() - armedAt <= ARM_MS;
  }

  static void disarm() {
    armedPrefix = null;
    armedAt = 0L;
  }

  /** 用户有没有在系统设置里给本 App 打开无障碍 */
  public static boolean autoSendEnabled(Context c) {
    try {
      String flat = new ComponentName(c, DeepSeekAutoSend.class).flattenToString();
      String s =
          Settings.Secure.getString(
              c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
      return s != null && s.contains(flat);
    } catch (Throwable e) {
      return false;
    }
  }

  public static void openAccessibilitySettings(Context c) {
    try {
      Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
      i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      c.startActivity(i);
    } catch (Throwable ignored) {
    }
  }

  private DeepSeekShare() {}

  public static boolean installed(Context c) {
    try {
      c.getPackageManager().getPackageInfo(PKG, 0);
      return true;
    } catch (Throwable e) {
      return false;
    }
  }

  /**
   * 把 text 发过去。返回 true 表示界面已经拉起来了（不代表对方一定发出了消息）。
   */
  public static boolean send(Context c, String text) {
    if (c == null || text == null) return false;
    String t = text.trim();
    if (t.isEmpty()) return false;
    // 开了无障碍就顺手让服务替用户点发送；没开则只是预填，用户手点一下
    if (autoSendEnabled(c)) arm(t);
    // 明确指向 DeepSeek：直接给它，不走分享面板
    try {
      Intent i = new Intent(Intent.ACTION_SEND);
      i.setType("text/plain");
      i.putExtra(Intent.EXTRA_TEXT, t);
      i.setPackage(PKG);
      i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      c.startActivity(i);
      return true;
    } catch (Throwable ignored) {
      // 没装 / 被系统挡住 → 退回系统分享面板，让用户自己挑
    }
    try {
      Intent s = new Intent(Intent.ACTION_SEND);
      s.setType("text/plain");
      s.putExtra(Intent.EXTRA_TEXT, t);
      Intent chooser = Intent.createChooser(s, "发到 DeepSeek");
      chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      c.startActivity(chooser);
      return true;
    } catch (Throwable e) {
      return false;
    }
  }
}
