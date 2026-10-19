package com.dsh.petoverlay;

import android.content.Context;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 读取 dsh-pet 的 config.jsonc（JSONC：带 // 和 注释）。
 * 气泡文案、表情包池、动作池全部以插件配置为准，不在代码里硬编码。
 */
public class PetConfig {
  private static final String TAG = "PetConfig";
  public static PetConfig I;

  public String whisperPrompt = "";
  public final List<String> memes = new ArrayList<>();          // memes/*.png 文件名（不含扩展名）
  public final Map<String, String> memeDesc = new LinkedHashMap<>();
  // 注意：workStatusTexts 是 dsh-pet 插件在 DSHA 里汇报「AI 工作到哪一步」用的，
  // 本 App 是独立桌面宠物、没有 AI 任务流，所以**不消费**这一段（保留解析仅为完整性）。
  public final List<List<String>> workStatusTexts = new ArrayList<>();
  public final List<String> idleClips = new ArrayList<>();
  public final List<String> turnClips = new ArrayList<>();
  public final List<String> dragClips = new ArrayList<>();
  public final List<String> clickClips = new ArrayList<>();
  public final List<List<String>> categories = new ArrayList<>();
  public final List<String> categoryIds = new ArrayList<>();

  public static synchronized PetConfig load(Context ctx) {
    if (I != null) return I;
    PetConfig c = new PetConfig();
    try (InputStream in = ctx.getAssets().open("config.jsonc")) {
      byte[] b = new byte[in.available()];
      int n = 0, r;
      while ((r = in.read(b, n, b.length - n)) > 0) n += r;
      String json = stripJsonc(new String(b, 0, n, StandardCharsets.UTF_8));
      c.parse(new JSONObject(json));
      Log.i(TAG, "配置载入: memes=" + c.memes.size() + " categories="
          + c.categories.size() + " clicks=" + c.clickClips.size());
    } catch (Throwable e) {
      Log.w(TAG, "配置读取失败，用兜底: " + e);
    }
    I = c;
    return c;
  }

  /** 去掉 // 与 注释，但不碰字符串内部的 // */
  private static String stripJsonc(String s) {
    StringBuilder out = new StringBuilder(s.length());
    boolean inStr = false, inLine = false, inBlock = false;
    for (int i = 0; i < s.length(); i++) {
      char ch = s.charAt(i);
      char nx = i + 1 < s.length() ? s.charAt(i + 1) : '\0';
      if (inLine) { if (ch == '\n') { inLine = false; out.append(ch); } continue; }
      if (inBlock) { if (ch == '*' && nx == '/') { inBlock = false; i++; } continue; }
      if (inStr) {
        out.append(ch);
        if (ch == '\\' && i + 1 < s.length()) { out.append(s.charAt(++i)); continue; }
        if (ch == '"') inStr = false;
        continue;
      }
      if (ch == '"') { inStr = true; out.append(ch); continue; }
      if (ch == '/' && nx == '/') { inLine = true; i++; continue; }
      if (ch == '/' && nx == '*') { inBlock = true; i++; continue; }
      out.append(ch);
    }
    return out.toString();
  }

  private void parse(JSONObject o) {
    whisperPrompt = o.optString("whisperPrompt", "");
    JSONObject m = o.optJSONObject("memes");
    if (m != null) {
      for (Iterator<String> it = m.keys(); it.hasNext(); ) {
        String k = it.next();
        memes.add(k);
        memeDesc.put(k, m.optString(k, ""));
      }
    }
    JSONArray ws = o.optJSONArray("workStatusTexts");
    if (ws != null) for (int i = 0; i < ws.length(); i++) {
      JSONArray g = ws.optJSONArray(i);
      if (g == null) continue;
      List<String> l = new ArrayList<>();
      for (int j = 0; j < g.length(); j++) l.add(g.optString(j, ""));
      workStatusTexts.add(l);
    }
    JSONObject a = o.optJSONObject("animations");
    if (a != null) {
      fill(a.optJSONArray("idle"), idleClips);
      fill(a.optJSONArray("turn"), turnClips);
      fill(a.optJSONArray("drag"), dragClips);
      fill(a.optJSONArray("clicks"), clickClips);
      JSONArray cats = a.optJSONArray("categories");
      if (cats != null) for (int i = 0; i < cats.length(); i++) {
        JSONObject cat = cats.optJSONObject(i);
        if (cat == null) continue;
        categoryIds.add(cat.optString("id", ""));
        List<String> acts = new ArrayList<>();
        fill(cat.optJSONArray("actions"), acts);
        categories.add(acts);
      }
    }
  }

  private static void fill(JSONArray arr, List<String> to) {
    if (arr == null) return;
    for (int i = 0; i < arr.length(); i++) {
      String v = arr.optString(i, "");
      if (!v.isEmpty()) to.add(v);
    }
  }
}
