package com.dsh.petoverlay;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import java.io.File;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 设置界面。
 *
 * 视觉：背景是大鲸鱼插画（50% 透明度），所有内容都装在近白的圆角卡片里，
 * 保证文字在图上依然清晰。
 */
public class MainActivity extends Activity {

  // 配色
  private static final int C_TEXT = 0xFF1E2A3A;      // 主文字（深蓝灰）
  private static final int C_SUB = 0xFF52607A;       // 次要文字（加深，压得住透出的图）
  private static final int C_ACCENT = 0xFF4A7DFF;    // 主色（蓝）
  private static final int C_ACCENT_SOFT = 0xB8E8F0FF;
  private static final int C_CARD = 0x86FFFFFF;      // 卡片：~52% 白，看图清楚、字也稳
  private static final int C_LINE = 0x4DFFFFFF;      // 描边：极淡，毛边

  private TextView status;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    // ---------- 底层：背景图（50% 透明） ----------
    FrameLayout root = new FrameLayout(this);
    root.setBackgroundColor(0xFFF3F7FF);

    ImageView bg = new ImageView(this);
    bg.setImageResource(R.drawable.bg_whale);
    bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
    bg.setAlpha(1.0f);   // 完全不透明：背景插画完整呈现
    root.addView(
        bg,
        new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

    // 一层很淡的白纱，进一步保证文字对比度
    View veil = new View(this);
    veil.setBackgroundColor(0x00000000);
    root.addView(
        veil,
        new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

    // ---------- 上层：可滚动内容 ----------
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setVerticalScrollBarEnabled(false);
    LinearLayout page = new LinearLayout(this);
    page.setOrientation(LinearLayout.VERTICAL);
    page.setPadding(dp(16), dp(68), dp(16), dp(44));
    scroll.addView(page);
    root.addView(
        scroll,
        new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

    setContentView(root);

    // ---------- 标题 ----------
    // 标题放在半透明白胶囊里，压在插画上也清楚
    LinearLayout head = new LinearLayout(this);
    head.setOrientation(LinearLayout.VERTICAL);
    head.setPadding(dp(18), dp(12), dp(18), dp(12));
    GradientDrawable hbg = new GradientDrawable();
    hbg.setColor(0xC8FFFFFF);
    hbg.setCornerRadius(dp(22));
    head.setBackground(hbg);
    LinearLayout.LayoutParams hlp =
        new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    hlp.bottomMargin = dp(16);
    head.setLayoutParams(hlp);

    // 头像 + 标题 + 署名，横向排
    LinearLayout headRow = new LinearLayout(this);
    headRow.setOrientation(LinearLayout.HORIZONTAL);
    headRow.setGravity(Gravity.CENTER_VERTICAL);

    ImageView avatar = new ImageView(this);
    avatar.setImageResource(R.drawable.avatar);
    avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
    LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(52), dp(52));
    alp.rightMargin = dp(12);
    avatar.setLayoutParams(alp);
    headRow.addView(avatar);

    LinearLayout headText = new LinearLayout(this);
    headText.setOrientation(LinearLayout.VERTICAL);

    FancyText title = new FancyText(this);
    title.setText("鲸鱼娘");
    title.setTextColor(0xFF3D63D8);
    title.setTextSize(25f);
    title.stroke(0xFFFFFFFF, 0.08f).grad(true);
    headText.addView(title);

    TextView subtitle = new TextView(this);
    subtitle.setText("桌面小女仆 · 工作台联动");
    subtitle.setTextColor(C_SUB);
    subtitle.setTextSize(12f);
    subtitle.setPadding(0, dp(1), 0, dp(3));
    headText.addView(subtitle);

    FancyText maker = new FancyText(this);
    maker.setText("制作者：月见桜");
    maker.setTextColor(0xFF4A7DFF);
    maker.setTextSize(12f);
    maker.stroke(0xFFFFFFFF, 0.08f).grad(false);
    headText.addView(maker);

    headRow.addView(headText);
    head.addView(headRow);
    page.addView(head);

    // ---------- 卡片 1：启动 ----------
    LinearLayout c1 = newCard(page, "启动与权限");
    status = new TextView(this);
    status.setTextSize(13f);
    status.setTextColor(C_SUB);
    status.setLineSpacing(0f, 1.35f);
    status.setPadding(0, 0, 0, dp(10));
    c1.addView(status);

    c1.addView(action("① 授予悬浮窗权限", this::requestOverlay, false));
    c1.addView(action("② 通知权限（保持常驻）", this::requestNotifications, false));
    c1.addView(action("▶  启动桌宠", this::startPet, true));
    c1.addView(action("■  停止桌宠", this::stopPet, false));

    // ---------- 卡片 2：快捷输入 ----------
    LinearLayout c2 = newCard(page, "快捷输入");
    TextView h2 = hint(c2, "输入后点发送，她会用气泡说出来。");
    h2.setPadding(0, 0, 0, dp(8));

    final EditText input = styledInput(c2, "让她说点什么…");
    input.setSingleLine(true);
    c2.addView(
        action(
            "💬  发到气泡",
            () -> {
              String t = input.getText().toString().trim();
              if (t.isEmpty()) t = "你好呀，我是鲸鱼娘~";
              say(t);
              input.setText("");
            },
            true));
    c2.addView(action("🛠  工作指令", () -> openCommands("work"), false));
    c2.addView(action("💬  聊天指令", () -> openCommands("chat"), false));

    // ---------- 卡片 3：外观 ----------
    LinearLayout c3 = newCard(page, "外观");
    final SharedPreferences petPrefs = getSharedPreferences("pet", MODE_PRIVATE);

    final TextView sizeLabel = label(c3);
    final SeekBar sizeBar = new SeekBar(this);
    sizeBar.setMax(60);
    sizeBar.setProgress(Math.round((petPrefs.getFloat("scale", 1f) - 0.4f) / 0.6f * 60f));
    sizeBar.setPadding(dp(2), 0, dp(2), 0);
    c3.addView(sizeBar);

    final TextView alphaLabel = label(c3);
    alphaLabel.setPadding(0, dp(14), 0, dp(4));
    final SeekBar alphaBar = new SeekBar(this);
    alphaBar.setMax(75);
    alphaBar.setProgress(Math.round((petPrefs.getFloat("alpha", 1f) - 0.25f) / 0.75f * 75f));
    alphaBar.setPadding(dp(2), 0, dp(2), 0);
    c3.addView(alphaBar);

    final Runnable syncLabels =
        new Runnable() {
          @Override
          public void run() {
            float sc = 0.4f + sizeBar.getProgress() / 60f * 0.6f;
            float al = 0.25f + alphaBar.getProgress() / 75f * 0.75f;
            sizeLabel.setText("大小：" + Math.round(sc * 100) + "%（100% = 当前最大）");
            alphaLabel.setText("不透明度：" + Math.round(al * 100) + "%（100% = 完全不透明）");
          }
        };
    sizeBar.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          @Override
          public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
            syncLabels.run();
            if (!fromUser) return;
            petPrefs.edit().putFloat("scale", 0.4f + p / 60f * 0.6f).apply();
          }

          @Override
          public void onStartTrackingTouch(SeekBar sb) {}

          @Override
          public void onStopTrackingTouch(SeekBar sb) {}
        });
    alphaBar.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          @Override
          public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
            syncLabels.run();
            if (!fromUser) return;
            petPrefs.edit().putFloat("alpha", 0.25f + p / 75f * 0.75f).apply();
          }

          @Override
          public void onStartTrackingTouch(SeekBar sb) {}

          @Override
          public void onStopTrackingTouch(SeekBar sb) {}
        });
    syncLabels.run();

    // ---------- 卡片 4：连接设置 ----------
    LinearLayout c4 = newCard(page, "连接设置");
    hint(c4, "指向运行 pet-inbox 插件的地址。默认 127.0.0.1:3082（与插件默认端口一致），换一套 DSH 只改这里。");
    final SharedPreferences cp = getSharedPreferences("pet", MODE_PRIVATE);
    final EditText cHost = styledInput(c4, "工作台地址（127.0.0.1）");
    cHost.setText(cp.getString("inbox_host", "127.0.0.1"));
    final EditText cPort = styledInput(c4, "端口（3082）");
    cPort.setText(cp.getString("inbox_port", "3082"));
    cPort.setInputType(InputType.TYPE_CLASS_NUMBER);
    final EditText cPkg = styledInput(c4, "工作台应用包名（dev.dsh.android）");
    cPkg.setText(cp.getString("harness_pkg", "dev.dsh.android"));
    final EditText cAct = styledInput(c4, "启动 Activity（dev.dsh.android.MainActivity）");
    cAct.setText(cp.getString("harness_act", "dev.dsh.android.MainActivity"));
    c4.addView(
        action(
            "💾  保存连接设置",
            () -> {
              cp.edit()
                  .putString("inbox_host", cHost.getText().toString().trim())
                  .putString("inbox_port", cPort.getText().toString().trim())
                  .putString("harness_pkg", cPkg.getText().toString().trim())
                  .putString("harness_act", cAct.getText().toString().trim())
                  .apply();
              status.setText("✅ 连接设置已保存，重启桌宠后生效");
            },
            false));

    // ---------- 卡片 5：独立模式 ----------
    LinearLayout c5 = newCard(page, "独立模式");
    hint(c5, "不填也能用：默认走 DeepSeek 官方 API，只差一个 Key。"
        + "工作台不开时，聊天、碎碎念、查余额都靠自己直连。");
    final SharedPreferences p = getSharedPreferences("pet", MODE_PRIVATE);

    // Key 用密码框，密文存储，界面上只显示掩码
    final EditText dKey = styledInput(c5, "API Key（sk-...，仅本机加密保存）");
    dKey.setSingleLine(true);
    dKey.setInputType(
        InputType.TYPE_CLASS_TEXT
            | InputType.TYPE_TEXT_VARIATION_PASSWORD
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    final String savedKey = SecretBox.decrypt(this, p.getString("dkey", ""));
    final TextView keyTip = hint(c5, savedKey.isEmpty()
        ? "尚未填写 Key。去 platform.deepseek.com 申请一个 sk- 开头的即可。"
        : "已保存：" + SecretBox.mask(savedKey) + "（加密存储，留空则不修改）");

    // 直连地址：默认官方，改成别的就是第三方 API
    final EditText dUrl = styledInput(c5, "BaseURL（留空 = DeepSeek 官方）");
    String curUrl = p.getString("durl_override", "");
    if (curUrl.isEmpty()) curUrl = p.getString("durl", "");
    dUrl.setText("-".equals(curUrl) ? "" : curUrl);

    final EditText dModel = styledInput(c5, "模型名（留空 = deepseek-chat）");
    dModel.setText(p.getString("dmodel", ""));

    // 第三方 API 快捷预设
    LinearLayout presetRow = new LinearLayout(this);
    presetRow.setOrientation(LinearLayout.HORIZONTAL);
    presetRow.setPadding(0, 0, 0, dp(8));
    String[][] presets = {
      {"DeepSeek 官方", "https://api.deepseek.com", "deepseek-chat"},
      {"硅基流动", "https://api.siliconflow.cn/v1", "deepseek-ai/DeepSeek-V3"},
      {"OpenRouter", "https://openrouter.ai/api/v1", "deepseek/deepseek-chat"},
      {"月之暗面", "https://api.moonshot.cn/v1", "moonshot-v1-8k"},
      {"智谱", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"},
    };
    for (final String[] pr : presets) {
      Button b = new Button(this);
      b.setText(pr[0]);
      b.setAllCaps(false);
      b.setTextSize(11f);
      b.setPadding(dp(8), dp(4), dp(8), dp(4));
      LinearLayout.LayoutParams blp =
          new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
      blp.rightMargin = dp(4);
      b.setLayoutParams(blp);
      b.setOnClickListener(v -> {
        dUrl.setText(pr[1]);
        dModel.setText(pr[2]);
        keyTip.setText("已套用「" + pr[0] + "」的地址与模型，Key 需填该平台的。");
      });
      presetRow.addView(b);
    }
    hint(c5, "第三方 API 快捷预设（点一下自动填地址和模型）：");
    c5.addView(presetRow);

    final TextView testResult = label(c5);
    testResult.setTextSize(12f);
    testResult.setTextColor(C_SUB);

    // ---------- 一键筛选可用模型 ----------
    hint(c5, "一键筛选：先拉这个服务声称支持的模型，再逐个真发一次请求验证，"
        + "把「列表里有但实际跑不通」的筛掉（中转站常见的 503 就是这种）。");

    // 同类模型快捷标签：点一下把关键词填进搜索框，扫描时只试这些

    final EditText mSearch = styledInput(c5, "搜索模型名（如 deepseek / glm / qwen）");
    final TextView scanTip = label(c5);

    hint(c5, "按类别筛选（点一下只试这类模型，省钱）：");
    // 8 个类别排一行会被挤扁（中文标签直接折成两行互相压），改成两行每行 4 个
    final String[][] CATS = {
      {"DeepSeek", "deepseek"},
      {"通义千问", "qwen"},
      {"智谱 GLM", "glm"},
      {"月之暗面", "moonshot"},
      {"Llama", "llama"},
      {"Gemini", "gemini"},
      {"GPT", "gpt"},
      {"Claude", "claude"},
    };
    final int PER_ROW = 4;
    LinearLayout catRow = null;
    for (int i = 0; i < CATS.length; i++) {
      final String[] cat = CATS[i];
      if (i % PER_ROW == 0) {
        catRow = new LinearLayout(this);
        catRow.setOrientation(LinearLayout.HORIZONTAL);
        catRow.setPadding(0, 0, 0, dp(i == 0 ? 6 : 0));
        c5.addView(catRow);
      }
      Button cb = smallBtn(cat[0]);
      cb.setSingleLine(true);
      cb.setOnClickListener(
          v -> {
            mSearch.setText(cat[1]);
            status.setText("已筛选：" + cat[0] + "（关键词 " + cat[1] + "）");
          });
      LinearLayout.LayoutParams lp =
          new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
      lp.setMargins(dp(2), 0, dp(2), dp(4));
      catRow.addView(cb, lp);
    }
    scanTip.setTextSize(12f);
    scanTip.setTextColor(C_SUB);
    final LinearLayout okBox = new LinearLayout(this);
    okBox.setOrientation(LinearLayout.VERTICAL);
    final LinearLayout badBox = new LinearLayout(this);
    badBox.setOrientation(LinearLayout.VERTICAL);

    // 最近一次扫描结果，供搜索框过滤用
    final java.util.List<ModelScan.Result> okList = new ArrayList<>();
    final java.util.List<ModelScan.Result> badList = new ArrayList<>();
    final boolean[] showBad = {false};

    c5.addView(
        action(
            "🧪  一键筛选可用模型（会逐个试跑，有少量消耗）",
            () -> {
              String u = dUrl.getText().toString().trim();
              String k = dKey.getText().toString().trim();
              if (k.isEmpty()) k = SecretBox.decrypt(this, p.getString("dkey", ""));
              if (k.isEmpty()) {
                scanTip.setText("❌ 请先填写 API Key");
                return;
              }
              // 筛选会给每一个模型真发一条请求，包括按次计费的那种。
              // 先弹一次确认，说清代价，别让人手一滑就花掉钱。
              final String cu = u, ck = k;
              new android.app.AlertDialog.Builder(MainActivity.this)
                  .setTitle("筛选前请确认")
                  .setMessage(buildScanWarning(mSearch.getText().toString().trim()))
                  .setNegativeButton("取消", null)
                  .setPositiveButton("我知道，开始筛选", (d2, w2) -> runScan(cu, ck, okBox, badBox, okList, badList, mSearch, showBad, dModel, status, p, dKey, dUrl, testResult, scanTip))
                  .show();
            },
            false));

    c5.addView(
        action(
            "🧪  重扫一次",
            () -> {
              String u = dUrl.getText().toString().trim();
              String k = dKey.getText().toString().trim();
              if (k.isEmpty()) k = SecretBox.decrypt(this, p.getString("dkey", ""));
              if (k.isEmpty()) {
                scanTip.setText("❌ 请先填写 API Key");
                return;
              }
              final String cu2 = u, ck2 = k;
              new android.app.AlertDialog.Builder(MainActivity.this)
                  .setTitle("筛选前请确认")
                  .setMessage(buildScanWarning(mSearch.getText().toString().trim()))
                  .setNegativeButton("取消", null)
                  .setPositiveButton("我知道，开始筛选", (d2, w2) -> runScan(cu2, ck2, okBox, badBox, okList, badList, mSearch, showBad, dModel, status, p, dKey, dUrl, testResult, scanTip))
                  .show();
            },
            false));

    c5.addView(okBox);
    c5.addView(badBox);

    // 搜索框：边打字边过滤（结果为空时不动，避免误清）
    if (mSearch != null) {
      mSearch.addTextChangedListener(
          new TextWatcher() {
            public void beforeTextChanged(CharSequence s2, int a, int b, int c) {}

            public void onTextChanged(CharSequence s2, int a, int b, int c) {}

            public void afterTextChanged(Editable e) {
              if (okList.isEmpty() && badList.isEmpty()) return;
              renderModels(okBox, badBox, okList, badList, e.toString().trim(), showBad[0],
                  dModel, status, p, dKey, dUrl, testResult);
            }
          });
    }


    c5.addView(
        action(
            "💾  保存独立模式",
            () -> {
              String k = dKey.getText().toString().trim();
              String u = dUrl.getText().toString().trim();
              String m = dModel.getText().toString().trim();
              SharedPreferences.Editor e = p.edit();
              if (!k.isEmpty()) {
                String enc = SecretBox.encrypt(this, k);
                if (enc.isEmpty()) {
                  status.setText("❌ Key 加密失败，未保存（不会退回明文）");
                  return;
                }
                e.putString("dkey", enc);
              }
              // 空地址存成 "-"：明确表示「用官方默认」，而不是没配
              e.putString("durl_override", u.isEmpty() ? "-" : u);
              e.putString("dmodel", m);
              e.apply();
              dKey.setText("");
              String shown = SecretBox.decrypt(this, p.getString("dkey", ""));
              keyTip.setText(shown.isEmpty()
                  ? "尚未填写 Key。"
                  : "已保存：" + SecretBox.mask(shown) + "（加密存储，留空则不修改）");
              status.setText("✅ 独立模式配置已保存");
            },
            false));

    c5.addView(
        action(
            "🔍  检测模型可用性",
            () -> {
              String u = dUrl.getText().toString().trim();
              String k = dKey.getText().toString().trim();
              if (k.isEmpty()) k = SecretBox.decrypt(this, p.getString("dkey", ""));
              if (k.isEmpty()) {
                testResult.setText("❌ 请先填写 API Key");
                return;
              }
              String m = dModel.getText().toString().trim();
              if (m.isEmpty()) m = "deepseek-chat";
              testResult.setText("检测中…");
              final String fu = u, fk = k, fm = m;
              new Thread(() -> {
                String msg = probeModel(fu, fk, fm);
                runOnUiThread(() -> testResult.setText(msg));
              }).start();
            },
            true));

    // ---------- 插件（随 APK 自带，首次启动自动植入 DSH） ----------
    LinearLayout c6 = newCard(page, "工作台插件");
    hint(c6, "这个插件是鲸鱼娘自带的，已经打包进 App，不用另外下载或安装。"
        + "负责桌宠 ↔ 工作台之间的聊天与指令通道（本机 127.0.0.1:3082）。");
    final TextView plugState = label(c6);
    final TextView plugDetail = label(c6);
    Runnable refreshPlug = () -> {
      File home = PluginInjector.dshHome(this);
      if (home == null) {
        plugState.setText("❌ 找不到 DSH 目录");
        plugDetail.setText(PluginInjector.diagnose(this));
        return;
      }
      String bv = PluginInjector.bundledVersion(this);
      boolean installed = PluginInjector.isInstalled(this);
      String onDisk = PluginInjector.localVersion(new File(home, PluginInjector.REL_NODE_MODULES));
      if (installed) {
        plugState.setText("✅ 已装好，版本 " + bv);
      } else if (onDisk == null) {
        plugState.setText("⚪ 还没装（App 内置版本 " + bv + "）");
      } else {
        plugState.setText("🟡 已装 " + onDisk + "，App 内置 " + bv + "（可更新）");
      }
      plugDetail.setText("目录：" + home.getAbsolutePath());
    };
    refreshPlug.run();

    final java.util.function.Consumer<PluginInjector.Result> showResult = r -> {
      plugDetail.setText(
          r.message + (r.log.isEmpty() ? "" : "\n\n" + TextUtils.join("\n", r.log)));
      refreshPlug.run();
    };

    c6.addView(
        action(
            "🩺  安装 / 更新插件到工作台",
            () -> {
              new android.app.AlertDialog.Builder(this)
                  .setTitle("安装工作台插件")
                  .setMessage(PluginInjector.DISCLOSURE)
                  .setNegativeButton("取消", null)
                  .setPositiveButton(
                      "我了解，继续",
                      (d, w) -> {
                        // 乙方案：App 把插件文件解包到中转目录，再给用户一条沙箱命令去执行。
                        // App 是 Android 进程，够不到 PRoot 里的 /root/.dsh，
                        // 只能把文件放到双方都能碰的中转目录，由沙箱侧脚本读走安装。
                        final java.io.File staging =
                            PluginInjector.exportToStaging(MainActivity.this);
                        final String cmd = PluginInjector.installCommand();
                        String msg;
                        if (staging == null) {
                          msg = "解包失败：App 的外部私有目录不可用。\n\n"
                              + "请手动执行下面这条命令（需要在 DSH 里）：\n\n" + cmd;
                        } else {
                          plugDetail.setText(
                              "✅ 插件文件已解包到：\n" + staging.getAbsolutePath()
                                  + "\n\n下一步：复制下面的命令，到 DeepSeek Harness（DSH）窗口里贴一下执行。");
                          msg = "插件文件已准备好，共 4 个（约 62 KB）。\n\n"
                              + "因为 App 看不到工作台的内部目录，需要你在 DeepSeek Harness 里"
                              + "跑一条命令完成最后一步：\n\n" + cmd;
                        }
                        new android.app.AlertDialog.Builder(MainActivity.this)
                            .setTitle("最后一步：在 DSH 里执行")
                            .setMessage(msg)
                            .setNegativeButton("关闭", null)
                            .setPositiveButton(
                                "复制命令",
                                (d2, w2) -> {
                                  android.content.ClipboardManager cm =
                                      (android.content.ClipboardManager)
                                          getSystemService(CLIPBOARD_SERVICE);
                                  cm.setPrimaryClip(
                                      android.content.ClipData.newPlainText(
                                          "pet-inbox 安装命令", cmd));
                                  Toast.makeText(
                                          MainActivity.this,
                                          "已复制，去 DSH 窗口贴一下即可",
                                          Toast.LENGTH_LONG)
                                      .show();
                                })
                            .show();
                      })
                  .show();
            },
            true));

    c6.addView(
        action(
            "🧹  从工作台移除插件",
            () ->
                new android.app.AlertDialog.Builder(this)
                    .setTitle("移除插件")
                    .setMessage(
                        "会从 DSH 里删掉 pet-inbox 插件，并摘掉 package.json 里的登记。\n"
                            + "桌宠将连不上工作台，但动画、独立模式聊天都不受影响。\n"
                            + "改动前会自动备份为 .bak-petinject-remove。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton(
                        "确认移除",
                        (d, w) -> {
                          plugDetail.setText("正在移除…");
                          new Thread(
                                  () -> {
                                    PluginInjector.Result r = PluginInjector.remove(this);
                                    runOnUiThread(() -> showResult.accept(r));
                                  })
                              .start();
                        })
                    .show(),
            false));

    // plugState / plugDetail 由 label(c6) 创建，已经自己挂进 c6 了，这里不能再 addView

    // ---------- 页脚 ----------
    FancyText footer = new FancyText(this);
    footer.setText("长按鲸鱼娘 → 工作/聊天指令\n双击鲸鱼娘 → 功能与动作面板\n\n制作者：月见桜");
    footer.grad(false);
    footer.stroke(0xFFFFFFFF, 0.08f);
    footer.setTextColor(0xFF3D63D8);
    footer.setTextSize(12f);
    footer.setGravity(Gravity.CENTER);
    footer.setLineSpacing(0f, 1.4f);
    footer.setPadding(dp(14), dp(10), dp(14), dp(10));
    GradientDrawable fbg = new GradientDrawable();
    fbg.setColor(0xB8FFFFFF);
    fbg.setCornerRadius(dp(18));
    footer.setBackground(fbg);
    LinearLayout.LayoutParams flp =
        new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    flp.gravity = Gravity.CENTER_HORIZONTAL;
    flp.topMargin = dp(20);
    footer.setLayoutParams(flp);
    page.addView(footer);

    // 外部可用 am start ... --es petcmd "内容" --es petchan chat|work 直接下发指令
    String petcmd = getIntent() == null ? null : getIntent().getStringExtra("petcmd");
    if (petcmd != null && !petcmd.trim().isEmpty()) {
      Intent intent = new Intent(this, PetService.class);
      intent.putExtra("petcmd", petcmd);
      intent.putExtra("petchan", getIntent().getStringExtra("petchan"));
      startForegroundService(intent);
    }

    // 自检用：--es petarm "内容" 只武装无障碍服务（不跳转），配合外部 am start 测点击
    String petarm = getIntent() == null ? null : getIntent().getStringExtra("petarm");
    if (petarm != null && !petarm.trim().isEmpty()) {
      DeepSeekShare.arm(petarm);
      Toast.makeText(this, "已武装：" + DeepSeekShare.autoSendEnabled(this), Toast.LENGTH_SHORT).show();
      finish();
      return;
    }

    // 也可以用 --es petshare "内容" 直接把一句话交给 DeepSeek App（开了无障碍就自动发送）
    String petshare = getIntent() == null ? null : getIntent().getStringExtra("petshare");
    if (petshare != null && !petshare.trim().isEmpty()) {
      final String shareText = petshare;
      // 等本页窗口真正铺好再跳，否则新起的 DeepSeek 会被自己盖住
      getWindow().getDecorView().postDelayed(
              () -> {
                boolean auto = DeepSeekShare.autoSendEnabled(this);
                boolean ok = DeepSeekShare.send(this, shareText);
                Toast.makeText(
                        this,
                        ok
                            ? (auto ? "已发给 DeepSeek，正在自动发送…" : "已发到 DeepSeek（未开自动发送）")
                            : "没找到 DeepSeek App",
                        Toast.LENGTH_SHORT)
                    .show();
                if (ok) finish();
              },
              250);
    }
  }

  @Override
  protected void onResume() {
    super.onResume();
    refresh();
  }

  // ==================== 视图小工具 ====================

  /** 一张圆角卡片，带小标题 */
  private LinearLayout newCard(LinearLayout parent, String titleText) {
    LinearLayout card = new LinearLayout(this);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setPadding(dp(18), dp(16), dp(18), dp(16));
    GradientDrawable bg = new GradientDrawable();
    bg.setColor(C_CARD);
    bg.setCornerRadius(dp(20));
    bg.setStroke(dp(1), C_LINE);
    card.setBackground(bg);
    card.setElevation(dp(3));
    LinearLayout.LayoutParams lp =
        new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    lp.bottomMargin = dp(14);
    card.setLayoutParams(lp);

    FancyText t = new FancyText(this);
    t.setText(titleText);
    t.setTextColor(0xFF3D63D8);
    t.setTextSize(13f);
    t.stroke(0xFFFFFFFF, 0.08f).grad(false);
    t.setPadding(0, 0, 0, dp(6));
    card.addView(t);

    parent.addView(card);
    return card;
  }

  /** 说明文字 */
  private TextView hint(LinearLayout parent, String text) {
    TextView tv = new TextView(this);
    tv.setText(text);
    tv.setTextColor(C_SUB);
    tv.setTextSize(12f);
    tv.setLineSpacing(0f, 1.3f);
    tv.setPadding(0, 0, 0, dp(6));
    parent.addView(tv);
    return tv;
  }

  /** 标签行 */
  private TextView label(LinearLayout parent) {
    TextView tv = new TextView(this);
    tv.setTextColor(C_TEXT);
    tv.setTextSize(14f);
    tv.setPadding(0, dp(4), 0, dp(4));
    parent.addView(tv);
    return tv;
  }

  /** 统一样式的输入框 */
  private EditText styledInput(LinearLayout parent, String hintText) {
    EditText et = new EditText(this);
    et.setHint(hintText);
    et.setTextColor(C_TEXT);
    et.setHintTextColor(0xFF9AA9BC);
    et.setTextSize(14f);
    et.setSingleLine(true);
    et.setHorizontallyScrolling(true);
    et.setPadding(dp(14), dp(10), dp(14), dp(10));
    GradientDrawable bg = new GradientDrawable();
    bg.setColor(0xC8FFFFFF);
    bg.setCornerRadius(dp(12));
    bg.setStroke(dp(1), C_LINE);
    et.setBackground(bg);
    LinearLayout.LayoutParams lp =
        new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    lp.bottomMargin = dp(8);
    et.setLayoutParams(lp);
    parent.addView(et);
    return et;
  }

  /** 统一样式的按钮；primary=true 为主操作（蓝底白字） */
  private Button action(String labelText, Runnable run, boolean primary) {
    Button b = new Button(this);
    b.setText(labelText);
    b.setAllCaps(false);
    b.setTextSize(15f);
    b.setGravity(Gravity.CENTER);
    // 花体字比常规字宽，长文案在窄屏上会被挤成两行再压到一行里去（看起来像叠字）。
    // 三件事：允许换行 + 明确行距 + 去掉字间距。
    b.setSingleLine(false);
    b.setMaxLines(2);
    b.setEllipsize(null);
    b.setLineSpacing(dp(2), 1.15f);
    try {
      b.setLetterSpacing(0f);
    } catch (Throwable ignored) {
    }
    b.setIncludeFontPadding(false);
    b.setPadding(dp(10), dp(12), dp(10), dp(12));
    GradientDrawable bg = new GradientDrawable();
    bg.setCornerRadius(dp(14));
    if (primary) {
      bg.setColor(0xF04A7DFF);
      b.setTextColor(Color.WHITE);
    } else {
      bg.setColor(0xCCE8F0FF);
      b.setTextColor(0xFF2A4C8F);
    }
    b.setBackground(bg);
    b.setStateListAnimator(null);
    FancyText.font(b);
    b.setMinimumHeight(dp(42));
    LinearLayout.LayoutParams lp =
        new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    lp.topMargin = dp(8);
    b.setLayoutParams(lp);
    b.setOnClickListener(v -> run.run());
    return b;
  }

  private int dp(int v) {
    return Math.round(v * getResources().getDisplayMetrics().density);
  }

  // ==================== 逻辑 ====================

  private void refresh() {
    boolean overlay = Settings.canDrawOverlays(this);
    status.setText(
        (overlay ? "✅ 悬浮窗权限已就绪" : "⚠️ 还没有悬浮窗权限，先点下面的第 ① 步")
            + "\n启动后她会浮在任意 App 上方，可拖动、轻点说话、长按弹出指令框、双击打开功能面板。");
  }

  private void requestOverlay() {
    if (Settings.canDrawOverlays(this)) {
      status.setText("✅ 已经有悬浮窗权限了");
      return;
    }
    Intent intent =
        new Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
    startActivity(intent);
  }

  private void requestNotifications() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
          != PackageManager.PERMISSION_GRANTED) {
        requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 1001);
      }
    }
  }

  private void startPet() {
    if (!Settings.canDrawOverlays(this)) {
      requestOverlay();
      return;
    }
    Intent intent = new Intent(this, PetService.class);
    intent.putExtra("text", "我来啦~");
    startForegroundService(intent);
    status.setText("✅ 已启动，去别的 App 看看，她应该浮在上面");
  }

  private void stopPet() {
    stopService(new Intent(this, PetService.class));
    status.setText("已停止");
  }

  private void openCommands(String channel) {
    Intent intent = new Intent(this, CommandActivity.class);
    intent.putExtra(CommandActivity.EXTRA_CHANNEL, channel);
    startActivity(intent);
  }

  private void say(String text) {
    Intent intent = new Intent(this, PetService.class);
    intent.putExtra("text", text);
    startForegroundService(intent);
  }

  // ---------- 独立模式：模型可用性检测 ----------

  /**
   * 探一次 /chat/completions，确认「地址 + Key + 模型名」这一组能用。
   * 返回一句给人看的中文结论，不抛异常。
   *
   * 检查顺序故意打成一条链路，这样报错能直接指向是哪一环坏：
   *   HTTP 401/403 → Key 不对；HTTP 404 → 地址或模型名不对；
   *   200 但 content 空 → 模型名有效但输出异常。
   */
  // ================= 第三方 API 可用模型筛选（2026-10-28） =================
  //
  // 用户填了 BaseURL + Key 之后，一键做三件事：
  //   1. 拉 /v1/models 拿这个服务声称支持的模型；
  //   2. 逐个真发一次最小请求，把「声称支持但实际跑不通」的筛掉
  //      （中转站常见的 503 model_not_found 就是这种情况）；
  //   3. 结果分「可用 / 不可用」两栏列出，带搜索框，点一下即填入。

  /** 扫描进度回调：在主线程回调，参数是 (当前完成数, 总数, 最新一条结果) */
  interface ScanSink {
    void progress(int done, int total, ModelScan.Result latest);
  }

  /** 单个模型的探测结果 */
  static final class ModelScan {
    static final class Result {
      final String model;
      final boolean ok;
      final String note;   // 失败原因（成功时为耗时）
      Result(String model, boolean ok, String note) {
        this.model = model;
        this.ok = ok;
        this.note = note;
      }
    }
  }

  /** 从 /v1/models 或 /models 拉模型 id 列表；拉不到返回空表 */
  private java.util.List<String> listModels(String baseUrl, String key) {
    java.util.List<String> out = new java.util.ArrayList<>();
    String base = (baseUrl == null) ? "" : baseUrl.trim();
    if (base.isEmpty() || "-".equals(base)) base = PetService.DEFAULT_BASE;
    base = base.replaceAll("/+$", "");

    java.util.List<String> urls = new java.util.ArrayList<>();
    urls.add(base + "/models");
    if (!base.endsWith("/v1")) urls.add(base + "/v1/models");

    for (String url : urls) {
      java.net.HttpURLConnection c = null;
      try {
        c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setRequestMethod("GET");
        c.setRequestProperty("Authorization", "Bearer " + key);
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        int code = c.getResponseCode();
        String body = readStream(code == 200 ? c.getInputStream() : c.getErrorStream());
        if (code != 200) continue;
        // 只要 data[].id，别的字段不关心；用正则抠，省得引 JSON 库
        java.util.regex.Matcher m =
            java.util.regex.Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        Set<String> uniq = new LinkedHashSet<>();
        while (m.find()) uniq.add(m.group(1));
        out.addAll(uniq);
        if (!out.isEmpty()) return out;
      } catch (Throwable e) {
        // 换下一个路径
      } finally {
        if (c != null) c.disconnect();
      }
    }
    return out;
  }

  /**
   * 探测单个模型，返回 (可用?, 说明)。
   * 只看「要花的钱最少」这一点：max_tokens=1，内容无所谓，能通就行。
   */
  private ModelScan.Result probeOnce(String baseUrl, String key, String model) {
    String base = (baseUrl == null) ? "" : baseUrl.trim();
    if (base.isEmpty() || "-".equals(base)) base = PetService.DEFAULT_BASE;
    base = base.replaceAll("/+$", "");

    java.util.List<String> urls = new java.util.ArrayList<>();
    urls.add(base + "/chat/completions");
    if (!base.endsWith("/v1")) urls.add(base + "/v1/chat/completions");

    String lastErr = "";
    for (int i = 0; i < urls.size(); i++) {
      String url = urls.get(i);
      java.net.HttpURLConnection c = null;
      long t0 = System.currentTimeMillis();
      try {
        String req = "{\"model\":\"" + model.replace("\"", "")
            + "\",\"max_tokens\":1,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
        c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setRequestProperty("Authorization", "Bearer " + key);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setDoOutput(true);
        byte[] out = req.getBytes("UTF-8");
        c.setFixedLengthStreamingMode(out.length);
        java.io.OutputStream os = c.getOutputStream();
        os.write(out);
        os.close();

        int code = c.getResponseCode();
        String body = readStream(code == 200 ? c.getInputStream() : c.getErrorStream());
        long ms = System.currentTimeMillis() - t0;
        if (code == 404 && i < urls.size() - 1) continue;
        if (code == 200) {
          boolean hasContent = body.contains("\"content\"") || body.contains("\"delta\"");
          return hasContent
              ? new ModelScan.Result(model, true, ms + "ms")
              : new ModelScan.Result(model, false, "连通但无内容");
        }
        if (code == 401 || code == 403) {
          return new ModelScan.Result(model, false, "Key 无效");
        }
        if (code == 503) {
          return new ModelScan.Result(model, false, "无可用通道(503)");
        }
        if (code == 404) {
          return new ModelScan.Result(model, false, "模型不存在(404)");
        }
        if (code == 429) {
          return new ModelScan.Result(model, false, "限流(429)");
        }
        lastErr = "HTTP " + code;
      } catch (java.net.SocketTimeoutException e) {
        lastErr = "超时";
      } catch (Throwable e) {
        lastErr = "网络错误";
      } finally {
        if (c != null) c.disconnect();
      }
    }
    return new ModelScan.Result(model, false, lastErr.isEmpty() ? "未知错误" : lastErr);
  }

  /**
   * 并发扫全部模型。并发度 4，别把中转站打挂，也够快：
   * 60 个模型 15 个一批，大约十几秒扫完。
   */
  private void scanAll(
      String baseUrl, String key, java.util.List<String> models, ScanSink sink, Runnable done) {
    final int total = models.size();
    final AtomicInteger idx = new AtomicInteger(0);
    final AtomicInteger finished = new AtomicInteger(0);
    final int workers = Math.min(4, Math.max(1, total));
    final List<ModelScan.Result> results = Collections.synchronizedList(new ArrayList<>());
    for (int w = 0; w < workers; w++) {
      new Thread(
              () -> {
                while (true) {
                  int i = idx.getAndIncrement();
                  if (i >= total) break;
                  ModelScan.Result r = probeOnce(baseUrl, key, models.get(i));
                  results.add(r);
                  int n = finished.incrementAndGet();
                  runOnUiThread(() -> sink.progress(n, total, r));
                }
                if (finished.get() >= total) runOnUiThread(done);
              })
          .start();
    }
  }

  /** 扫描前的确认文案：按当前关键词说清楚这次要试多少、花多少 */
  private String buildScanWarning(String keyword) {
    String kw = keyword == null ? "" : keyword.trim();
    String scope = kw.isEmpty()
        ? "该 API 下的【全部模型】"
        : "名字含「" + kw + "」的模型（同类筛选）";
    return "这个操作会把 " + scope + "\n"
        + "逐个真发一次请求（每次最多 1 token）。\n\n"
        + "· 包含按次计费的模型，会产生少量费用\n"
        + "· 验证失败的请求通常不收费，但要通过验证的会算\n"
        + "· 每点一次都会重新扫一遍，别反复点\n"
        + "· 想省钱就先点上面的类别标签或填搜索框，只试一类\n\n"
        + "请先确认这里面有你真的想用的模型，再决定要不要扫。";
  }

  /** 真正执行一次筛选扫描（确认框点「开始筛选」后进来） */
  private void runScan(
      String u,
      String k,
      LinearLayout okBox,
      LinearLayout badBox,
      java.util.List<ModelScan.Result> okList,
      java.util.List<ModelScan.Result> badList,
      EditText mSearch,
      boolean[] showBad,
      EditText dModel,
      TextView status,
      SharedPreferences p,
      EditText dKey,
      EditText dUrl,
      TextView testResult,
      TextView scanTip) {
    okBox.removeAllViews();
    badBox.removeAllViews();
    okList.clear();
    badList.clear();
    scanTip.setText("拉取模型列表…");
    final String fu = u, fk = k;
    new Thread(
            () -> {
              java.util.List<String> models = listModels(fu, fk);
              // 搜索框/类别标签有内容时，只试名字匹配的那些 —— 省钱的关键在这
              final String kw0 = mSearch.getText().toString().trim().toLowerCase();
              int all = models.size();
              java.util.List<String> picked = new java.util.ArrayList<>();
              for (String mm : models) {
                if (kw0.isEmpty() || mm.toLowerCase().contains(kw0)) picked.add(mm);
              }
              final int allCount = all;
              runOnUiThread(
                  () -> {
                    if (allCount == 0) {
                      scanTip.setText("❌ 这个服务没提供 /v1/models，拿不到模型列表。"
                          + "请手动填模型名，或换服务商。");
                      return;
                    }
                    if (picked.isEmpty()) {
                      scanTip.setText("❌ 这个服务有 " + allCount + " 个模型，但没有名字含「"
                          + kw0 + "」的。换个关键词或清空搜索框。");
                      return;
                    }
                    scanTip.setText(kw0.isEmpty()
                        ? "共 " + allCount + " 个模型，正在逐个验证（每个都会真发一条请求）…"
                        : "共 " + allCount + " 个模型，只验证匹配「" + kw0 + "」的 "
                            + picked.size() + " 个…");
                  });
              models = picked;
              if (models.isEmpty()) return;
              scanAll(
                  fu,
                  fk,
                  models,
                  (done, total, latest) -> {
                    if (latest.ok) okList.add(latest);
                    else badList.add(latest);
                    scanTip.setText("验证中 " + done + "/" + total
                        + "　✅ " + okList.size() + " 可用／❌ " + badList.size() + " 不可用");
                  },
                  () -> {
                    renderModels(okBox, badBox, okList, badList,
                        mSearch.getText().toString().trim(), showBad[0], dModel, status, p,
                        dKey, dUrl, testResult);
                    scanTip.setText("✅ 完成：可用 " + okList.size() + " 个，不可用 "
                        + badList.size() + " 个。点模型名填入，点「检测」单独复验。");
                  });
            })
        .start();
  }

  /** 把扫描结果画出来；keyword 非空时只显示名字里含它的模型 */
  private void renderModels(
      LinearLayout okBox,
      LinearLayout badBox,
      java.util.List<ModelScan.Result> okList,
      java.util.List<ModelScan.Result> badList,
      String keyword,
      boolean showBad,
      EditText dModel,
      TextView status,
      SharedPreferences p,
      EditText dKey,
      EditText dUrl,
      TextView testResult) {
    okBox.removeAllViews();
    badBox.removeAllViews();
    String kw = keyword == null ? "" : keyword.toLowerCase();

    int shownOk = 0;
    for (ModelScan.Result r : new ArrayList<>(okList)) {
      if (!kw.isEmpty() && !r.model.toLowerCase().contains(kw)) continue;
      okBox.addView(modelRow(r, true, dModel, status, p, dKey, dUrl, testResult));
      shownOk++;
    }
    if (shownOk == 0 && !okList.isEmpty()) {
      okBox.addView(tiny("（没有名字含「" + keyword + "」的可用模型）"));
    }

    int shownBad = 0;
    if (showBad) {
      for (ModelScan.Result r : new ArrayList<>(badList)) {
        if (!kw.isEmpty() && !r.model.toLowerCase().contains(kw)) continue;
        badBox.addView(modelRow(r, false, dModel, status, p, dKey, dUrl, testResult));
        shownBad++;
      }
    }
    if (!showBad && !badList.isEmpty()) {
      badBox.addView(tiny("另有 " + badList.size() + " 个不可用（点上面按钮展开）"));
    }
    if (showBad && shownBad > 0) {
      // 已经展开，不用再提示
    }
  }

  /** 一行模型：左边点名字填入，右边「检测」单独复验 */
  private LinearLayout modelRow(
      ModelScan.Result r,
      boolean ok,
      EditText dModel,
      TextView status,
      SharedPreferences p,
      EditText dKey,
      EditText dUrl,
      TextView testResult) {
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setPadding(0, dp(1), 0, dp(1));

    Button pick = smallBtn((ok ? "✅ " : "❌ ") + r.model);
    if (!ok) pick.setTextColor(0xFF8A8F99);
    pick.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
    pick.setOnClickListener(
        v -> {
          dModel.setText(r.model);
          status.setText("已填入模型：" + r.model + "（记得点保存）");
        });
    row.addView(pick, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

    TextView note = tiny(r.note);
    note.setPadding(dp(6), 0, dp(6), 0);
    row.addView(note);

    Button chk = smallBtn("检测");
    chk.setOnClickListener(
        v -> {
          String kk = SecretBox.decrypt(MainActivity.this, p.getString("dkey", ""));
          if (kk.isEmpty()) kk = dKey.getText().toString().trim();
          if (kk.isEmpty()) {
            testResult.setText("❌ 请先填写 API Key");
            return;
          }
          testResult.setText("检测中…");
          final String uu = dUrl.getText().toString().trim();
          final String k2 = kk;
          new Thread(
                  () -> {
                    String msg = probeModel(uu, k2, r.model);
                    runOnUiThread(
                        () -> {
                          testResult.setText(msg);
                          chk.setText(msg.startsWith("✅") ? "✅" : "❌");
                        });
                  })
              .start();
        });
    row.addView(chk);
    return row;
  }

  /** 小号按钮 */
  private Button smallBtn(String text) {
    Button b = new Button(this);
    b.setText(text);
    b.setTextSize(11f);
    b.setAllCaps(false);
    b.setMinWidth(0);
    b.setMinimumWidth(0);
    b.setPadding(dp(4), dp(4), dp(4), dp(4));
    b.setMinimumHeight(dp(30));
    b.setIncludeFontPadding(false);
    b.setSingleLine(false);
    b.setMaxLines(2);
    b.setGravity(Gravity.CENTER);
    b.setTextSize(10.5f);
    GradientDrawable bg = new GradientDrawable();
    bg.setColor(0x14FFFFFF);
    bg.setCornerRadius(dp(8));
    b.setBackground(bg);
    b.setTextColor(0xFF3D63D8);
    return b;
  }

  /** 小号说明文字 */
  private TextView tiny(String text) {
    TextView t = new TextView(this);
    t.setText(text);
    t.setTextSize(10.5f);
    t.setTextColor(C_SUB);
    return t;
  }

  private String probeModel(String baseUrl, String key, String model) {
    String base = (baseUrl == null) ? "" : baseUrl.trim();
    if (base.isEmpty() || "-".equals(base)) base = PetService.DEFAULT_BASE;
    base = base.replaceAll("/+$", "");
    String m = (model == null || model.trim().isEmpty()) ? PetService.DEFAULT_MODEL : model.trim();

    java.util.List<String> urls = new java.util.ArrayList<>();
    urls.add(base + "/chat/completions");
    if (!base.endsWith("/v1")) urls.add(base + "/v1/chat/completions");

    String lastErr = "";
    for (int i = 0; i < urls.size(); i++) {
      String url = urls.get(i);
      java.net.HttpURLConnection c = null;
      try {
        String req = "{\"model\":\"" + m.replace("\"", "")
            + "\",\"max_tokens\":8,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
        c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setRequestProperty("Authorization", "Bearer " + key);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        c.setDoOutput(true);
        byte[] out = req.getBytes("UTF-8");
        c.setFixedLengthStreamingMode(out.length);
        java.io.OutputStream os = c.getOutputStream();
        os.write(out);
        os.close();

        int code = c.getResponseCode();
        String body = readStream(code == 200 ? c.getInputStream() : c.getErrorStream());
        if (code == 404 && i < urls.size() - 1) continue;   // 换 /v1 路径再试一次
        if (code == 200) {
          boolean hasContent = body.contains("\"content\"") || body.contains("\"delta\"");
          return hasContent
              ? "✅ 可用：" + m + "（" + hostOf(url) + "）"
              : "⚠️ 连通但无内容，模型名可能不被支持：" + m;
        }
        if (code == 401 || code == 403) {
          return "❌ Key 无效或无权限（HTTP " + code + "）。换一个 Key 再试。";
        }
        if (code == 404) {
          return "❌ 找不到模型或地址（HTTP 404）：" + m + "\n地址：" + url;
        }
        if (code == 429) {
          return "⚠️ 被限流（HTTP 429），Key 能用但当前额度紧张。";
        }
        lastErr = "HTTP " + code + (body.isEmpty() ? "" : "：" + brief(body));
      } catch (java.net.SocketTimeoutException e) {
        lastErr = "超时（20 秒无响应），检查网络或代理";
      } catch (Throwable e) {
        lastErr = String.valueOf(e);
      } finally {
        if (c != null) c.disconnect();
      }
    }
    return "❌ 检测失败：" + lastErr;
  }

  private static String readStream(java.io.InputStream in) {
    if (in == null) return "";
    try {
      java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
      byte[] buf = new byte[4096];
      int n;
      while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
      in.close();
      return new String(bo.toByteArray(), "UTF-8");
    } catch (Throwable e) {
      return "";
    }
  }

  private static String brief(String s) {
    String t = s.replaceAll("\\s+", " ").trim();
    return t.length() > 120 ? t.substring(0, 120) + "…" : t;
  }

  private static String hostOf(String url) {
    try {
      return new java.net.URL(url).getHost();
    } catch (Throwable e) {
      return url;
    }
  }
}
