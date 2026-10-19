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
import android.provider.Settings;
import android.text.InputType;
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
    hint(c4, "指向运行 pet-inbox 插件的地址。默认 127.0.0.1:3081，换一套 DSH 只改这里。");
    final SharedPreferences cp = getSharedPreferences("pet", MODE_PRIVATE);
    final EditText cHost = styledInput(c4, "工作台地址（127.0.0.1）");
    cHost.setText(cp.getString("inbox_host", "127.0.0.1"));
    final EditText cPort = styledInput(c4, "端口（3081）");
    cPort.setText(cp.getString("inbox_port", "3081"));
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
    hint(c5, "填好后即使工作台没开，也能 AI 碎碎念和查余额。启动时若工作台在线会自动同步。");
    final SharedPreferences p = getSharedPreferences("pet", MODE_PRIVATE);
    final EditText dUrl = styledInput(c5, "BaseURL，例如 https://api.chenmoai.com/v1");
    dUrl.setText(p.getString("durl", ""));
    final EditText dKey = styledInput(c5, "API Key（sk-...）");
    dKey.setText(p.getString("dkey", ""));
    final EditText dModel = styledInput(c5, "模型名，例如 deepseek-chat");
    dModel.setText(p.getString("dmodel", ""));
    c5.addView(
        action(
            "💾  保存独立模式",
            () -> {
              p.edit()
                  .putString("durl", dUrl.getText().toString().trim())
                  .putString("dkey", dKey.getText().toString().trim())
                  .putString("dmodel", dModel.getText().toString().trim())
                  .apply();
              status.setText("✅ 独立模式配置已保存");
            },
            false));

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
    b.setPadding(dp(8), dp(12), dp(8), dp(12));
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
}
