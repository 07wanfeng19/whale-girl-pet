package com.dsh.petoverlay;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * dsh-pet-inbox 插件植入器（2026-10-28）。
 *
 * <p>目的：让「鲸鱼娘」成为**一个 App**，而不是「APK + 另外装一个插件」。
 * 插件源码随 APK 一起发布（assets/pet-inbox/），首次启动 DSH 时自动放进它的 profile 目录。
 *
 * <p>三条铁律：
 *
 * <ol>
 *   <li><b>只动自己的东西</b>——只写 {@code @local/dsh-pet-inbox} 这一个包，别的一概不碰。
 *   <li><b>只加不改</b>——往 profile 的 package.json 里加一条 link: 依赖、往 bundles 里加一条，
 *       其余字段（包括别的插件的顺序）原样保留。已经有了就跳过，绝不重复插。
 *   <li><b>可回滚</b>——动 package.json 之前先存一份 .bak，出岔子能退回去。
 * </ol>
 *
 * <p>安全声明见 {@link #DISCLOSURE}，首次植入前会原文展示给用户。
 */
final class PluginInjector {

  static final String TAG = "PetInject";

  /** 插件在 assets 里的目录 */
  static final String ASSET_DIR = "pet-inbox";
  /** 随 APK 发布的插件文件（不含宿主 profile 侧的任何东西） */
  static final String[] FILES = {
    "index.js", "impl.js", "package.json", "cordis.patch.yml"
  };

  static final String PKG_NAME = "@local/dsh-pet-inbox";
  /** 宿主 profile 里这个包落在哪 */
  static final String REL_NODE_MODULES = "profiles/web/node_modules/@local/dsh-pet-inbox";
  /** profile 的 package.json（link 依赖与 bundles 清单都在这里） */
  static final String REL_PROFILE_PKG = "profiles/web/package.json";

  /** 首次植入前给用户看的安全声明 */
  static final String DISCLOSURE =
      "鲸鱼娘需要在你手机上的 DeepSeek Harness 里安装一个小插件（pet-inbox），"
          + "桌宠和工作台之间的聊天、指令通道靠它转发。\n\n"
          + "要动的地方只有三处：\n"
          + "1. 往 ~/.dsh/profiles/web/node_modules/@local/dsh-pet-inbox 写入插件文件（4 个，共约 62 KB）\n"
          + "2. 往 ~/.dsh/profiles/web/package.json 加一条依赖和一个 bundles 条目\n"
          + "3. 改动前会自动备份 package.json 为 package.json.bak-petinject\n\n"
          + "不会动的东西：\n"
          + "· 不读你的对话、记忆、人设、密钥\n"
          + "· 不联网，插件只监听本机回环地址 127.0.0.1:3082\n"
          + "· 不改你其它任何插件，也不会覆盖已有的 pet-inbox（版本号不同时只提示，不强制）\n\n"
          + "卸载 App 不会自动删插件；要清理可在设置页点「移除插件」。";

  private PluginInjector() {}

  // ---------------------------------------------------------------- 结果

  static final class Result {
    /** 是否已经处于「装好了」状态（新装成功 or 本来就一致） */
    boolean ok;
    /** 是否真的做了写入 */
    boolean changed;
    /** 给用户看的一句话结论 */
    String message;
    /** 详细日志，出错时给用户或开发者看 */
    final List<String> log = new ArrayList<>();

    Result(boolean ok, boolean changed, String message) {
      this.ok = ok;
      this.changed = changed;
      this.message = message;
    }

    Result line(String s) {
      log.add(s);
      return this;
    }
  }

  // ---------------------------------------------------------------- 对外入口

  /** 快速判断：插件是否已经装好且是最新版（不写任何东西） */
  static boolean isInstalled(Context ctx) {
    File home = dshHome(ctx);
    if (home == null) return false;
    File dst = new File(home, REL_NODE_MODULES);
    if (!dst.isDirectory()) return false;
    for (String f : FILES) {
      if (!new File(dst, f).isFile()) return false;
    }
    // 版本一致才算「装好」，否则要提示升级
    String bundled = bundledVersion(ctx);
    String onDisk = localVersion(dst);
    return bundled != null && bundled.equals(onDisk);
  }

  /** 路径信号文件名（放在 App 自己的外部私有目录，不需要任何存储权限） */
  static final String HINT_NAME = "dsh_home.txt";

  /** 中转目录里插件文件所在子目录名 */
  static final String STAGING_SUB = "pet-inbox";

  /**
   * 把 APK 内置的插件 4 文件解包到 App 外部私有目录（getExternalFilesDir），
   * 作为「App 与沙箱之间」的中转。App 是 Android 进程够不到 PRoot 的 /root/.dsh，
   * 只能把文件放到这个双方都能碰的地方，再由沙箱侧脚本（install-pet-inbox.sh）读走安装。
   *
   * @return 中转目录 File；失败返回 null
   */
  static File exportToStaging(Context ctx) {
    try {
      File ext = ctx.getExternalFilesDir(null);
      if (ext == null) return null;
      File dir = new File(ext, STAGING_SUB);
      if (!dir.isDirectory() && !dir.mkdirs()) return null;
      for (String f : FILES) {
        byte[] data = readAsset(ctx.getAssets(), ASSET_DIR + "/" + f);
        writeFile(new File(dir, f), data);
      }
      return dir;
    } catch (Throwable e) {
      Log.w(TAG, "exportToStaging 失败：" + e);
      return null;
    }
  }

  /** 沙箱侧安装命令（用户在 DSH 窗口里贴一下执行） */
  static String installCommand() {
    // 完整自包含：先把 App 解包到私有目录的文件，用 shiz 搬到沙箱能读的 Download 中转，
    // 再跑沙箱安装脚本。App 自己无存储权限，够不到 /sdcard，所以搬运只能靠 shiz（DSH 内可调）。
    return "shiz cp -r /storage/emulated/0/Android/data/com.dsh.petoverlay/files/pet-inbox"
        + " /sdcard/Download/pet-inbox-staging"
        + " && bash /root/dsh/install-pet-inbox.sh";
  }

  /**
   * 找 DSH 家目录。
   *
   * <p>难点：App 是 Android 进程，看不见 PRoot 里的 /root —— 直接 new File("/root/.dsh") 永远是
   * 不存在的。所以由 DSH 一侧把路径写进共享存储（/sdcard/Download/petoverlay-bridge/dsh_home.txt），
   * App 读那一行字就知道该去哪儿找。这样用户不用填任何路径。
   *
   * <p>顺序：手填 > DSH 信号文件 > 常规猜测。
   */
  static File dshHome(Context ctx) {
    // 1) 用户手动指定过（高级用法，平时用不到）
    SharedPreferences sp = ctx.getSharedPreferences("pet", Context.MODE_PRIVATE);
    String custom = sp.getString("dsh_home", "");
    if (custom != null && !custom.isEmpty()) {
      File f = new File(custom);
      if (f.isDirectory()) return f;
    }
    // 2) DSH 写下的信号文件 —— 正常路径
    for (File hf : hintFiles(ctx)) {
      if (!hf.isFile()) continue;
      try {
        String p = readText(hf).trim();
        if (p.isEmpty()) continue;
        File f = new File(p);
        sbLog("hint " + hf + " → " + p + "（" + (f.isDirectory() ? "存在" : "不存在") + "）");
        if (f.isDirectory()) return f;
      } catch (Throwable e) {
        sbLog("hint " + hf + " 读不出来：" + e);
      }
    }
    // 3) 常规猜测（同一进程树里能看到 /root 的场景，比如某些 ROM 或 root 环境）
    String[] guesses = {
      "/root/.dsh",
      "/data/data/dev.dsh.android/files/.dsh",
      "/data/user/0/dev.dsh.android/files/.dsh",
    };
    for (String g : guesses) {
      File f = new File(g);
      if (new File(f, REL_PROFILE_PKG).isFile()) return f;
    }
    return null;
  }

  /** 路径信号文件可能的位置：App 自己的外部私有目录优先（零权限），再到共享存储 */
  static java.util.List<File> hintFiles(Context ctx) {
    java.util.List<File> out = new ArrayList<>();
    try {
      File ext = ctx.getExternalFilesDir(null);
      if (ext != null) out.add(new File(ext, HINT_NAME));
    } catch (Throwable ignored) {
    }
    out.add(new File("/sdcard/Download/petoverlay-bridge/" + HINT_NAME));
    out.add(new File("/storage/emulated/0/Download/petoverlay-bridge/" + HINT_NAME));
    return out;
  }

  /** 最近一次探测过程的记录，给界面显示用 */
  private static final StringBuilder PROBE_LOG = new StringBuilder();

  private static void sbLog(String s) {
    Log.i(TAG, s);
    PROBE_LOG.append(s).append('\n');
  }

  static String lastProbe() {
    return PROBE_LOG.toString().trim();
  }

  /** 给用户看的「为什么找不到」——分开说清楚，比一句「找不到」有用 */
  static String diagnose(Context ctx) {
    StringBuilder sb = new StringBuilder();
    java.util.List<File> list = hintFiles(ctx);
    boolean anyHint = false;
    for (File hf : list) {
      sb.append(hf.isFile() ? "有" : "无").append(' ').append(hf.getAbsolutePath()).append('\n');
      if (hf.isFile()) {
        anyHint = true;
        try {
          String p = readText(hf).trim();
          sb.append("  内容：").append(p).append('\n');
          sb.append("  该目录").append(new File(p).isDirectory() ? "存在" : "不存在").append('\n');
        } catch (Throwable e) {
          sb.append("  读不出来：").append(e).append('\n');
        }
      }
    }
    if (!anyHint) {
      sb.append("\nDSH 还没写过路径信号。\n"
          + "让 DeepSeek Harness 运行一次 /root/dsh/pet-home-hint.sh，再点「刷新状态」。");
    }
    return sb.toString().trim();
  }

  /**
   * 植入插件。幂等：已经一致就返回 changed=false。
   *
   * <p>必须在后台线程调用（有文件 IO）。
   */
  static Result inject(Context ctx) {
    Result r = new Result(false, false, "");
    File home = dshHome(ctx);
    if (home == null) {
      return fail(r, "找不到 DSH 目录");
    }
    r.line("DSH 目录：" + home.getAbsolutePath());

    File dst = new File(home, REL_NODE_MODULES);
    String bundledVer = bundledVersion(ctx);
    String onDiskVer = localVersion(dst);
    r.line("APK 内置版本：" + bundledVer + "，磁盘现有版本：" + (onDiskVer == null ? "无" : onDiskVer));

    // 1) 拷文件
    boolean copied = false;
    try {
      if (!dst.isDirectory() && !dst.mkdirs()) {
        return fail(r, "建不了目录：" + dst.getAbsolutePath() + "（权限不足？）");
      }
      for (String f : FILES) {
        byte[] data = readAsset(ctx.getAssets(), ASSET_DIR + "/" + f);
        File out = new File(dst, f);
        if (out.isFile() && sameContent(out, data)) continue;
        writeFile(out, data);
        copied = true;
        r.line("写入 " + f + "（" + data.length + " 字节）");
      }
    } catch (Throwable e) {
      return fail(r, "拷贝插件文件失败：" + e);
    }

    // 2) 改 profile 的 package.json
    File pkg = new File(home, REL_PROFILE_PKG);
    String patched = null;
    if (pkg.isFile()) {
      try {
        String src = readText(pkg);
        String out = patchProfileJson(src);
        if (out.equals(src)) {
          r.line("package.json 已含本插件，无需改动");
        } else {
          File bak = new File(pkg.getAbsolutePath() + ".bak-petinject");
          if (!bak.isFile()) writeFile(bak, src.getBytes("UTF-8"));
          writeFile(pkg, out.getBytes("UTF-8"));
          patched = "package.json";
          r.line("package.json 已登记本插件（原文件备份为 .bak-petinject）");
        }
      } catch (Throwable e) {
        return fail(r, "改 package.json 失败：" + e + "（插件文件已就位，可手动加依赖）");
      }
    } else {
      r.line("警告：profile 的 package.json 不存在，只拷了文件");
    }

    boolean changed = copied || patched != null;
    // 3) 标记版本
    if (changed) {
      ctx.getSharedPreferences("pet", Context.MODE_PRIVATE)
          .edit()
          .putString("injected_version", bundledVer == null ? "" : bundledVer)
          .putLong("injected_at", System.currentTimeMillis())
          .apply();
    }
    r.ok = true;
    r.changed = changed;
    r.message =
        changed
            ? (onDiskVer == null ? "插件已装进 DSH ✅ 重启工作台后生效" : "插件已更新到 " + bundledVer + " ✅")
            : "插件已是最新（" + bundledVer + "），无需改动";
    return r;
  }

  /** 从 DSH 里移除本插件（只删自己的包 + 摘掉自己的两条登记） */
  static Result remove(Context ctx) {
    Result r = new Result(false, false, "");
    File home = dshHome(ctx);
    if (home == null) return fail(r, "找不到 DSH 目录");

    File pkg = new File(home, REL_PROFILE_PKG);
    try {
      if (pkg.isFile()) {
        String src = readText(pkg);
        String out = unpatchProfileJson(src);
        if (!out.equals(src)) {
          File bak = new File(pkg.getAbsolutePath() + ".bak-petinject-remove");
          if (!bak.isFile()) writeFile(bak, src.getBytes("UTF-8"));
          writeFile(pkg, out.getBytes("UTF-8"));
          r.line("已从 package.json 摘除登记");
        }
      }
      File dst = new File(home, REL_NODE_MODULES);
      if (dst.isDirectory()) {
        deleteTree(dst);
        r.line("已删除 " + dst.getAbsolutePath());
      }
      ctx.getSharedPreferences("pet", Context.MODE_PRIVATE)
          .edit()
          .remove("injected_version")
          .remove("injected_at")
          .apply();
      r.ok = true;
      r.changed = true;
      r.message = "插件已移除，重启工作台后生效";
      return r;
    } catch (Throwable e) {
      return fail(r, "移除失败：" + e);
    }
  }

  // ---------------------------------------------------------------- package.json 补丁

  /**
   * 往 profile 的 package.json 里加本插件。用最小侵入的字符串操作而不是完整 JSON 重写，
   * 免得把用户的格式、注释、字段顺序打乱。
   *
   * <p>要加两处：
   *
   * <ul>
   *   <li>dependencies 里：{@code "@local/dsh-pet-inbox": "link:/root/dsh/pet-inbox-plugin"} —— 不，
   *       实际用的是注入目录，link 指向 node_modules 里那份
   *   <li>dsh.profile.bundles 数组里：一条 {@code "@local/dsh-pet-inbox"}
   * </ul>
   */
  static String patchProfileJson(String src) {
    String out = src;

    // 1) dependencies
    if (!out.contains("\"" + PKG_NAME + "\"")) {
      String depLine = "\n    \"" + PKG_NAME + "\": \"link:" + pluginLinkTarget() + "\",";
      int depIdx = out.indexOf("\"dependencies\"");
      if (depIdx >= 0) {
        int brace = out.indexOf('{', depIdx);
        int next = nextNonSpace(out, brace + 1);
        if (next >= 0 && out.charAt(next) == '}') {
          // 空的 dependencies 对象
          out = out.substring(0, brace + 1) + depLine.substring(0, depLine.length() - 1)
              + "\n  " + out.substring(brace + 1);
        } else {
          out = out.substring(0, brace + 1) + depLine + out.substring(brace + 1);
        }
      }
    }

    // 2) dsh.profile.bundles
    if (!out.contains("\"" + PKG_NAME + "\"")) {
      // 上面没插成功（没有 dependencies 段）——那就只加 bundles
    }
    int bundlesIdx = out.indexOf("\"bundles\"");
    if (bundlesIdx >= 0 && !arrayHasString(out, bundlesIdx, PKG_NAME)) {
      int bracket = out.indexOf('[', bundlesIdx);
      int next = nextNonSpace(out, bracket + 1);
      if (next >= 0 && out.charAt(next) == ']') {
        out = out.substring(0, bracket + 1) + " \"" + PKG_NAME + "\" " + out.substring(bracket);
      } else {
        out = out.substring(0, bracket + 1) + "\n        \"" + PKG_NAME + "\"," + out.substring(bracket + 1);
      }
    }
    return out;
  }

  /** 从 package.json 摘掉本插件的两处登记 */
  static String unpatchProfileJson(String src) {
    String out = src;
    // dependencies 行
    out =
        out.replaceAll(
            "(?m)^\\s*\"" + java.util.regex.Pattern.quote(PKG_NAME) + "\"\\s*:\\s*\"[^\"]*\"\\s*,?\\s*\\n",
            "");
    // bundles 条目
    out =
        out.replaceAll(
            "(?m)^\\s*\"" + java.util.regex.Pattern.quote(PKG_NAME) + "\"\\s*,?\\s*\\n", "");
    out = out.replaceAll("(,)(\\s*[\\]}])", "$2"); // 修尾巴逗号
    return out;
  }

  /** 本插件注入后，link 指向哪里 */
  private static String pluginLinkTarget() {
    return "/root/.dsh/" + REL_NODE_MODULES;
  }

  private static boolean arrayHasString(String src, int fromIdx, String needle) {
    int end = src.indexOf(']', fromIdx);
    if (end < 0) return false;
    return src.substring(fromIdx, end).contains("\"" + needle + "\"");
  }

  private static int nextNonSpace(String s, int i) {
    while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    return i >= s.length() ? -1 : i;
  }

  // ---------------------------------------------------------------- 版本与文件工具

  /** APK 内置插件的版本号 */
  static String bundledVersion(Context ctx) {
    try {
      String json = new String(readAsset(ctx.getAssets(), ASSET_DIR + "/package.json"), "UTF-8");
      return jsonStr(json, "version");
    } catch (Throwable e) {
      return null;
    }
  }

  /** 磁盘上那份插件的版本号 */
  static String localVersion(File dir) {
    try {
      File f = new File(dir, "package.json");
      if (!f.isFile()) return null;
      return jsonStr(readText(f), "version");
    } catch (Throwable e) {
      return null;
    }
  }

  private static String jsonStr(String json, String key) {
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile("\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"")
            .matcher(json);
    return m.find() ? m.group(1) : null;
  }

  private static byte[] readAsset(AssetManager am, String name) throws IOException {
    InputStream in = am.open(name);
    try {
      ByteArrayOutputStream bo = new ByteArrayOutputStream();
      byte[] b = new byte[8192];
      int n;
      while ((n = in.read(b)) > 0) bo.write(b, 0, n);
      return bo.toByteArray();
    } finally {
      in.close();
    }
  }

  private static String readText(File f) throws IOException {
    java.io.FileInputStream in = new java.io.FileInputStream(f);
    try {
      ByteArrayOutputStream bo = new ByteArrayOutputStream();
      byte[] b = new byte[8192];
      int n;
      while ((n = in.read(b)) > 0) bo.write(b, 0, n);
      return new String(bo.toByteArray(), "UTF-8");
    } finally {
      in.close();
    }
  }

  private static void writeFile(File f, byte[] data) throws IOException {
    File parent = f.getParentFile();
    if (parent != null && !parent.isDirectory()) parent.mkdirs();
    OutputStream os = new FileOutputStream(f);
    try {
      os.write(data);
      os.flush();
    } finally {
      os.close();
    }
  }

  private static boolean sameContent(File f, byte[] data) {
    try {
      if (f.length() != data.length) return false;
      byte[] cur = readText(f).getBytes("UTF-8");
      return MessageDigest.isEqual(cur, data);
    } catch (Throwable e) {
      return false;
    }
  }

  private static void deleteTree(File f) {
    File[] kids = f.listFiles();
    if (kids != null) for (File k : kids) deleteTree(k);
    f.delete();
  }

  private static Result fail(Result r, String why) {
    Log.w(TAG, why);
    r.line(why);
    r.ok = false;
    r.message = why;
    return r;
  }
}
