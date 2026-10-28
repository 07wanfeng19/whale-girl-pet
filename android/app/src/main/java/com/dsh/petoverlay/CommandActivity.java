package com.dsh.petoverlay;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 快捷指令窗口。
 *
 * 两个页签共用这一个界面，但**互不干扰**：
 *   工作指令 → 投到「工作会话」，她会真的动手干活；
 *   聊天指令 → 投到「聊天会话」，只陪聊。
 * 服务端（dsh-pet-inbox 插件）按 channel 分开绑定 session，所以两边的历史天然隔离。
 */
public class CommandActivity extends Activity {

  public static final String EXTRA_CHANNEL = "channel";
  /** 工作台地址从 prefs 读，换一套 DSH 只改配置 */
  /** 工作台基础地址（host:port） */
  private String inboxBase() {
    android.content.SharedPreferences p = getSharedPreferences("pet", MODE_PRIVATE);
    String host = p.getString("inbox_host", "127.0.0.1");
    String port = p.getString("inbox_port", "3082");
    if (host == null || host.isEmpty()) host = "127.0.0.1";
    if (port == null || port.isEmpty()) port = "3082";
    return "http://" + host + ":" + port;
  }

  private String inboxSendUrl() {
    android.content.SharedPreferences p = getSharedPreferences("pet", MODE_PRIVATE);
    String host = p.getString("inbox_host", "127.0.0.1");
    String port = p.getString("inbox_port", "3082");
    if (host == null || host.isEmpty()) host = "127.0.0.1";
    if (port == null || port.isEmpty()) port = "3082";
    return "http://" + host + ":" + port + "/send";
  }

  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());

  private String channel = "work";
  private TextView tabWork;
  private TextView tabChat;
  private EditText input;
  private TextView logView;
  private Button sendBtn;

  private final List<String> workHistory = new ArrayList<>();
  private final List<String> chatHistory = new ArrayList<>();

  /** 独立模式兜底状态：工作台不在时由 tryOffline 填充 */
  private volatile boolean offlineMode = false;
  private volatile String offlineReply = null;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    String fromIntent = getIntent().getStringExtra(EXTRA_CHANNEL);
    if ("chat".equals(fromIntent)) channel = "chat";

    // 背景：与大鲸鱼设置页同一张插画，完全不透明
    FrameLayout container = new FrameLayout(this);
    ImageView bgv = new ImageView(this);
    bgv.setImageResource(R.drawable.bg_whale);
    bgv.setScaleType(ImageView.ScaleType.CENTER_CROP);
    container.addView(
        bgv,
        new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(0x00000000);
    int pad = dp(19);
    root.setPadding(pad, dp(48), pad, pad);
    container.addView(
        root,
        new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

    // —— 标题
    LinearLayout headRow = new LinearLayout(this);
    headRow.setOrientation(LinearLayout.HORIZONTAL);
    headRow.setGravity(Gravity.CENTER_VERTICAL);
    headRow.setPadding(0, 0, 0, dp(18));

    FancyText title = new FancyText(this);
    title.setText("快捷指令");
    title.setTextColor(0xFF3D63D8);
    title.setTextSize(29f);
    title.stroke(0xFFFFFFFF, 0.08f).grad(true);
    title.setLetterSpacing(0.1f);
    headRow.addView(
        title,
        new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

    TextView clearBtn = new TextView(this);
    clearBtn.setText("🗑 清理");
    clearBtn.setTextSize(15f);
    clearBtn.setTextColor(0xFF8FC0FF);
    FancyText.decorate(clearBtn);
    clearBtn.setPadding(dp(14), dp(8), dp(14), dp(8));
    GradientDrawable cbg = new GradientDrawable();
    cbg.setColor(0x33FFFFFF);
    cbg.setCornerRadius(dp(14));
    cbg.setStroke(dp(1), 0x55FFFFFF);
    clearBtn.setBackground(cbg);
    clearBtn.setOnClickListener(v -> clearCurrent());
    headRow.addView(clearBtn);

    root.addView(headRow);

    // —— 页签
    LinearLayout tabs = new LinearLayout(this);
    tabs.setOrientation(LinearLayout.HORIZONTAL);
    LinearLayout.LayoutParams tabLp =
        new LinearLayout.LayoutParams(0, dp(54), 1f);
    tabLp.rightMargin = dp(10);

    tabWork = makeTab("🛠 工作指令", "work");
    tabChat = makeTab("💬 聊天指令", "chat");
    tabs.addView(tabWork, tabLp);
    LinearLayout.LayoutParams tabLp2 = new LinearLayout.LayoutParams(0, dp(44), 1f);
    tabs.addView(tabChat, tabLp2);
    root.addView(tabs);

    // —— 说明
    TextView hint = new TextView(this);
    hint.setTextSize(16f);
    hint.setTextColor(0xFF9BB8DE);
    FancyText.decorate(hint);
    hint.setPadding(0, dp(12), 0, dp(12));
    root.addView(hint);
    this.hint = hint;

    // —— 发送记录
    ScrollView scroll = new ScrollView(this);
    LinearLayout.LayoutParams scrollLp =
        new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
    logView = new FancyText(this);
    logView.setTextSize(18f);
    logView.setTextColor(0xFFA6CBFF);
    logView.setPadding(dp(17), dp(14), dp(17), dp(14));
    logView.setTextIsSelectable(true);
    logView.setLineSpacing(0f, 1.45f);
    ((FancyText) logView).grad(false);
    logView.setLetterSpacing(0.1f);
    ((FancyText) logView).stroke(0xFFFFFFFF, 0.08f);
    GradientDrawable logBg = new GradientDrawable();
    logBg.setColor(0xB0101828);      // 深色玻璃更实，浅蓝字对比更强
    logBg.setCornerRadius(dp(22));
    logBg.setStroke(dp(1), 0x55FFFFFF);
    logView.setBackground(logBg);
    scroll.addView(logView);
    root.addView(scroll, scrollLp);

    // —— 输入区
    input = new EditText(this);
    input.setHint("在这里说要做什么…");
    input.setHintTextColor(0xCC8FB0D9);
    input.setTextColor(0xFFB6D6FF);
    input.setTextSize(20f);
    input.setMinLines(2);
    input.setMaxLines(5);
    FancyText.decorate(input);      // 花体 + 白描边 + 字距 0.1
    GradientDrawable box = new GradientDrawable();
    box.setColor(0x7A10203A);
    box.setCornerRadius(dp(19));
    box.setStroke(dp(1), 0x66FFFFFF);
    input.setBackground(box);
    input.setPadding(dp(15), dp(13), dp(15), dp(13));
    LinearLayout.LayoutParams inputLp =
        new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    inputLp.topMargin = dp(12);
    root.addView(input, inputLp);

    sendBtn = new Button(this);
    sendBtn.setText("发送");
    sendBtn.setAllCaps(false);
    FancyText.decorate(sendBtn);
    sendBtn.setTextColor(0xFF8FC0FF);
    sendBtn.setOnClickListener(v -> submit());
    LinearLayout.LayoutParams btnLp =
        new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58));
    btnLp.topMargin = dp(14);
    root.addView(sendBtn, btnLp);

    // 单向外发：把输入框内容（空则最后一条回复）交给 DeepSeek App，不需要工作台/Key
    Button dsBtn = new Button(this);
    dsBtn.setText("📤 发到 DeepSeek App");
    dsBtn.setAllCaps(false);
    FancyText.decorate(dsBtn);
    dsBtn.setTextColor(0xFF8FC0FF);
    dsBtn.setOnClickListener(v -> shareToDeepSeek());
    LinearLayout.LayoutParams dsLp =
        new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
    dsLp.topMargin = dp(8);
    root.addView(dsBtn, dsLp);

    setContentView(container);
    applyChannel(channel);
    loadHistory();
  }

  private TextView hint;

  private TextView makeTab(String label, String tabChannel) {
    TextView tab = new TextView(this);
    FancyText.decorate(tab);
    tab.setText(label);
    tab.setGravity(Gravity.CENTER);
    tab.setTextSize(18f);
    tab.setOnClickListener(v -> applyChannel(tabChannel));
    return tab;
  }

  private void applyChannel(String next) {
    channel = next;
    boolean work = "work".equals(channel);

    tabWork.setTextColor(work ? 0xFF8FC0FF : 0xB39FB8C8);
    tabChat.setTextColor(work ? 0xB39FB8C8 : 0xFF8FC0FF);
    tabWork.setBackground(roundBg(work ? 0xFF3A7BD5 : 0xFF1E2530));
    tabChat.setBackground(roundBg(work ? 0xFF1E2530 : 0xFF2FA36B));

    String desc = work
        ? "投给「工作会话」—— 她会真的动手干活，结果显示在工作台。"
        : "投给「聊天会话」—— 只陪聊，不碰文件和命令。";
    hint.setText(desc);
    input.setHint(work ? "例如：帮我看看 Downloads 里有什么…" : "随便聊聊…");
    refreshLog();
  }

  private GradientDrawable roundBg(int color) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(10));
    return d;
  }

  private void loadHistory() {
    SharedPreferences sp = getSharedPreferences("pet_cmds", MODE_PRIVATE);
    workHistory.clear();
    chatHistory.clear();
    String w = sp.getString("work", "");
    String c = sp.getString("chat", "");
    for (String line : w.split("\n")) if (!line.isEmpty()) workHistory.add(line);
    for (String line : c.split("\n")) if (!line.isEmpty()) chatHistory.add(line);
    refreshLog();
  }

  private void saveHistory() {
    getSharedPreferences("pet_cmds", MODE_PRIVATE)
        .edit()
        .putString("work", TextUtils.join("\n", workHistory))
        .putString("chat", TextUtils.join("\n", chatHistory))
        .apply();
  }

  /** 清空当前通道的记录（另一通道不受影响） */
  private void clearCurrent() {
    List<String> list = "work".equals(channel) ? workHistory : chatHistory;
    list.clear();
    refreshLog();
    saveHistory();
  }

  private void refreshLog() {
    List<String> list = "work".equals(channel) ? workHistory : chatHistory;
    if (list.isEmpty()) {
      logView.setText("还没有记录。\n发一条试试 —— 她会在工作台那边响应你。");
      return;
    }
    StringBuilder sb = new StringBuilder();
    for (String s : list) sb.append(s).append('\n');
    logView.setText(sb.toString().trim());
  }

  private void submit() {
    String text = input.getText().toString().trim();
    if (text.isEmpty()) return;

    List<String> list = "work".equals(channel) ? workHistory : chatHistory;
    list.add("· " + text);
    refreshLog();
    saveHistory();
    input.setText("");
    sendBtn.setEnabled(false);
    sendBtn.setText("发送中…");

    final String ch = channel;
    io.execute(
        () -> {
          // 先记下当前最后一条回复，用来判断有没有新回复
          final String baseline = fetchLast(ch);
          String error = send(ch, text);
          main.post(
              () -> {
                sendBtn.setEnabled(true);
                sendBtn.setText("发送");
                if (error != null) {
                  List<String> cur = "work".equals(ch) ? workHistory : chatHistory;
                  cur.add("  ⚠ " + error);
                  refreshLog();
                  saveHistory();
                  return;
                }
                List<String> cur = "work".equals(ch) ? workHistory : chatHistory;
                cur.add("  … 等回复中");
                refreshLog();
                saveHistory();
              });
          if (error != null) return;

          // 工作台不在 → 独立模式直接答，跳过轮询
          if (offlineMode) {
            final String r = offlineReply;
            main.post(
                () -> {
                  List<String> cur = "work".equals(ch) ? workHistory : chatHistory;
                  if (!cur.isEmpty() && cur.get(cur.size() - 1).equals("  … 等回复中")) {
                    cur.remove(cur.size() - 1);
                  }
                  cur.add("鲸鱼娘：" + r);
                  refreshLog();
                  saveHistory();
                });
            return;
          }

          // 轮询工作台，取到新回复就写进记录
          String reply = null;
          for (int i = 0; i < 60; i++) {
            try {
              Thread.sleep(2000);
            } catch (InterruptedException e) {
              return;
            }
            String t = fetchLast(ch);
            if (t == null || t.isEmpty() || t.equals(baseline)) continue;
            reply = t;
            break;
          }
          final String r = reply;
          main.post(
              () -> {
                List<String> list2 = "work".equals(ch) ? workHistory : chatHistory;
                if (list2.size() > 0) {
                  int last = list2.size() - 1;
                  if (list2.get(last).equals("  … 等回复中")) list2.remove(last);
                }
                if (r == null) {
                  list2.add("  （等待回复超时，工作台可能还在忙）");
                } else {
                  list2.add("鲸鱼娘：" + r);
                }
                refreshLog();
                saveHistory();
              });
        });
  }

  /** 返回 null 表示成功，否则返回错误文案 */
  private String send(String ch, String text) {
    HttpURLConnection conn = null;
    try {
      URL url = new URL(inboxSendUrl());
      conn = (HttpURLConnection) url.openConnection();
      conn.setRequestMethod("POST");
      conn.setConnectTimeout(4000);
      conn.setReadTimeout(15000);
      conn.setDoOutput(true);
      String tk =
          getSharedPreferences("pet", MODE_PRIVATE).getString("token", "");
      if (tk != null && !tk.isEmpty()) conn.setRequestProperty("x-pet-token", tk);
      conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");

      String payload =
          "{\"channel\":\"" + ch + "\",\"text\":" + jsonEscape(text) + "}";
      try (OutputStream os = conn.getOutputStream()) {
        os.write(payload.getBytes(StandardCharsets.UTF_8));
      }
      int code = conn.getResponseCode();
      if (code == 200) return null;
      // 工作台在但报错 → 也走独立模式，别把错误甩给用户
      if (tryOffline(ch, text)) return null;
      return "工作台返回 " + code + "，独立模式也没配置（设置页填 BaseURL 和 Key）";
    } catch (IOException e) {
      if (tryOffline(ch, text)) return null;
      return "连不上工作台，独立模式也没配置（设置页填 BaseURL 和 Key）";
    } finally {
      if (conn != null) conn.disconnect();
    }
  }

  /** 取该通道最后一条回复；返回 null 表示拿不到 */
  private String fetchLast(String ch) {
    HttpURLConnection c = null;
    try {
      URL url = new URL(inboxBase() + "/last?channel=" + ch);
      c = (HttpURLConnection) url.openConnection();
      c.setRequestMethod("GET");
      c.setConnectTimeout(3000);
      c.setReadTimeout(6000);
      String tk = getSharedPreferences("pet", MODE_PRIVATE).getString("token", "");
      if (tk != null && !tk.isEmpty()) c.setRequestProperty("x-pet-token", tk);
      if (c.getResponseCode() != 200) return null;
      java.io.InputStream in = c.getInputStream();
      java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
      byte[] b = new byte[4096];
      int n;
      while ((n = in.read(b)) > 0) bo.write(b, 0, n);
      in.close();
      String body = new String(bo.toByteArray(), StandardCharsets.UTF_8);
      int i = body.indexOf("\"text\":\"");
      if (i < 0) return null;
      int k = i + 8;
      StringBuilder sb = new StringBuilder();
      while (k < body.length()) {
        char ch2 = body.charAt(k);
        if (ch2 == '"') break;
        if (ch2 == '\\' && k + 1 < body.length()) {
          char nx = body.charAt(++k);
          if (nx == 'n') sb.append('\n');
          else if (nx == 't') sb.append(' ');
          else sb.append(nx);
        } else {
          sb.append(ch2);
        }
        k++;
      }
      return sb.toString().trim();
    } catch (Throwable e) {
      return null;
    } finally {
      if (c != null) c.disconnect();
    }
  }

  /** 单向外发：输入框有字就发字，没字就发最后一条鲸鱼娘回复 */
  private void shareToDeepSeek() {
    String t = input.getText().toString().trim();
    if (t.isEmpty()) {
      List<String> cur = "work".equals(channel) ? workHistory : chatHistory;
      for (int i = cur.size() - 1; i >= 0; i--) {
        String line = cur.get(i);
        if (line != null && line.startsWith("鲸鱼娘：")) {
          t = line.substring(4).trim();
          break;
        }
      }
    }
    if (t.isEmpty()) {
      Toast.makeText(this, "没有可转发的内容", Toast.LENGTH_SHORT).show();
      return;
    }
    boolean autoSend = DeepSeekShare.autoSendEnabled(this);
    if (DeepSeekShare.send(this, t)) {
      Toast.makeText(
              this,
              autoSend ? "已发给 DeepSeek，正在自动发送…" : "已发到 DeepSeek（未开自动发送，需手点一下发送键）",
              Toast.LENGTH_LONG)
          .show();
    } else {
      Toast.makeText(this, "没找到 DeepSeek App", Toast.LENGTH_SHORT).show();
    }
  }

  // ---------- 独立模式：工作台不在时直接问服务商 ----------

  /** 工作台连不上/报错时调它。成功返回 true，并设好 offlineMode/offlineReply */
  private boolean tryOffline(String ch, String text) {
    offlineMode = false;
    offlineReply = null;
    SharedPreferences p = getSharedPreferences("pet", MODE_PRIVATE);
    String base = p.getString("durl", "");
    String key = p.getString("dkey", "");
    String model = p.getString("dmodel", "");
    if (base == null) base = "";
    if (key == null) key = "";
    if (model == null) model = "";
    base = base.trim();
    key = key.trim();
    model = model.trim();
    if (base.isEmpty() || key.isEmpty()) {
      offlineMode = true;
      offlineReply = offlineSetupHint(base);
      return true;
    }
    if (model.isEmpty()) model = "deepseek-chat";

    String persona =
        "你是主人手机桌面上的 Q 版蓝发小女仆，名字叫鲸鱼娘。"
            + "说话自然、简短、温柔带点俏皮，不要自称 AI，不要用 Markdown、标题、表格、代码块。"
            + ("work".equals(ch)
                ? "主人现在问的是正经问题，可以多说两句、条理清楚，但不许假装自己执行过命令。"
                : "像平常聊天那样，尽量 60 字以内。");
    String env =
        "你现在处在独立模式：桌宠没有连上主人的主机，所以你没有命令执行、文件读写、联网搜索的能力。"
            + "主人让你动手做事时，坦白说这条需要主机在线才能做，然后给出建议或代替方案，不要编造执行结果。";

    String ctx = p.getString("ctx_" + ch, "");
    StringBuilder msgs = new StringBuilder();
    msgs.append("{\"role\":\"system\",\"content\":").append(jsonEscape(persona + env)).append("}");
    if (ctx != null && !ctx.isEmpty()) {
      for (String line : ctx.split("\n")) {
        if (line.isEmpty()) continue;
        if (line.startsWith("U:")) {
          msgs.append(",{\"role\":\"user\",\"content\":")
              .append(jsonEscape(line.substring(2)))
              .append("}");
        } else if (line.startsWith("A:")) {
          msgs.append(",{\"role\":\"assistant\",\"content\":")
              .append(jsonEscape(line.substring(2)))
              .append("}");
        }
      }
    }
    msgs.append(",{\"role\":\"user\",\"content\":").append(jsonEscape(text)).append("}");

    String req =
        "{\"model\":"
            + jsonEscape(model)
            + ",\"temperature\":0.8,\"max_tokens\":800,\"messages\":["
            + msgs
            + "]}";

    try {
      String resp = postChatJson(base, key, req);
      if (resp == null) {
        if (isLoopbackBase(base)) {
          offlineMode = true;
          offlineReply = offlineSetupHint(base);
          return true;
        }
        return false;
      }
      String reply = jsonField(resp, "content");
      if (reply == null || reply.isEmpty()) return false;
      reply = reply.trim();
      offlineMode = true;
      offlineReply = reply;
      appendContext(ch, text, reply);
      return true;
    } catch (Throwable e) {
      return false;
    }
  }

  /** 地址是不是只在本机有效（127.0.0.1 等），离开 DSH 宿主就断了 */
  private boolean isLoopbackBase(String base) {
    String b = base == null ? "" : base.toLowerCase();
    return b.contains("127.0.0.1") || b.contains("localhost") || b.contains("0.0.0.0")
        || b.contains("[::1]") || b.contains("10.0.2.2");
  }

  /** 独立模式配置不可用时的引导语：告诉主人去哪填什么 */
  private String offlineSetupHint(String base) {
    String b = base == null ? "" : base.trim();
    if (b.isEmpty()) {
      return "主人，我跟主机走散啦，独立模式还没配接口。到设置页卡片 5 填上 BaseURL 和 Key"
          + "（比如 https://api.chenmoai.com/v1），我就能自己说话啦。";
    }
    return "主人，我现在连的是本机地址（" + b + "），离开 DSH 就用不了。"
        + "到设置页卡片 5 换成公网接口（比如 https://api.chenmoai.com/v1）就行啦。";
  }

  /** POST chat/completions；base 不带 /v1 时 404 自动补一次 /v1 */
  private String postChatJson(String base, String key, String req) {
    String b = base.replaceAll("/+$", "");
    java.util.List<String> urls = new java.util.ArrayList<String>();
    urls.add(b + "/chat/completions");
    if (!b.endsWith("/v1")) urls.add(b + "/v1/chat/completions");
    for (int i = 0; i < urls.size(); i++) {
      String url = urls.get(i);
      HttpURLConnection c = null;
      try {
        c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(8000);
        c.setReadTimeout(90000);
        c.setDoOutput(true);
        c.setRequestProperty("Authorization", "Bearer " + key);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] out = req.getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(out.length);
        OutputStream os = c.getOutputStream();
        os.write(out);
        os.close();
        int code = c.getResponseCode();
        if (code == 404 && i < urls.size() - 1) continue;
        if (code != 200) return null;
        java.io.InputStream in = c.getInputStream();
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
      } catch (Throwable e) {
        return null;
      } finally {
        if (c != null) c.disconnect();
      }
    }
    return null;
  }

  /** 记两轮上下文，够用就行；超长按字符截断并丢掉被切半的首行 */
  private void appendContext(String ch, String u, String a) {
    SharedPreferences p = getSharedPreferences("pet", MODE_PRIVATE);
    String old = p.getString("ctx_" + ch, "");
    if (old == null) old = "";
    String s = old + "U:" + oneLine(u) + "\nA:" + oneLine(a) + "\n";
    if (s.length() > 3000) s = s.substring(s.length() - 3000);
    int nl = s.indexOf('\n');
    if (nl >= 0) s = s.substring(nl + 1);
    p.edit().putString("ctx_" + ch, s).apply();
  }

  private static String oneLine(String s) {
    if (s == null) return "";
    String t = s.replace('\n', ' ').replace('\r', ' ');
    return t.length() > 600 ? t.substring(0, 600) : t;
  }

  /** 从 JSON 里取字符串字段（够应付 chat/completions 的 content） */
  private static String jsonField(String json, String key) {
    if (json == null) return null;
    int i = json.indexOf("\"" + key + "\":\"");
    if (i < 0) return null;
    int k = i + key.length() + 4;
    StringBuilder sb = new StringBuilder();
    while (k < json.length()) {
      char ch = json.charAt(k);
      if (ch == '"') break;
      if (ch == '\\' && k + 1 < json.length()) {
        char nx = json.charAt(++k);
        switch (nx) {
          case 'n': sb.append('\n'); break;
          case 't': sb.append('\t'); break;
          case 'r': sb.append('\r'); break;
          case 'u':
            if (k + 4 < json.length()) {
              try {
                sb.append((char) Integer.parseInt(json.substring(k + 1, k + 5), 16));
                k += 4;
              } catch (Throwable ignore) {
              }
            }
            break;
          default: sb.append(nx);
        }
      } else {
        sb.append(ch);
      }
      k++;
    }
    return sb.toString().trim();
  }

  private static String jsonEscape(String s) {
    StringBuilder sb = new StringBuilder("\"");
    for (char c : s.toCharArray()) {
      switch (c) {
        case '"': sb.append("\\\""); break;
        case '\\': sb.append("\\\\"); break;
        case '\n': sb.append("\\n"); break;
        case '\r': sb.append("\\r"); break;
        case '\t': sb.append("\\t"); break;
        default:
          if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
          else sb.append(c);
      }
    }
    return sb.append('"').toString();
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    io.shutdownNow();
  }
}
