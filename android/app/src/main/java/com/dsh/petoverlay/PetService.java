package com.dsh.petoverlay;

import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Random;

/**
 * 鲸鱼娘桌宠悬浮窗。
 *
 * 渲染方式和 dsh-pet 插件一致：播 VP9 webm 视频（106 段，640x360），
 * 按状态切换不同动画，而不是 Live2D 骨骼模型。
 *
 * 关键技术点：VideoView 读不了 asset 里的 webm（它不是真实文件路径），
 * 必须用 SurfaceView + MediaPlayer + AssetFileDescriptor 直接喂文件描述符。
 */
public class PetService extends Service {
  private static final String TAG = "PetOverlay";
  private static final String CHANNEL_ID = "pet_overlay";
  private static final int NOTICE_ID = 4101;

  /** 角色最大缩放（1.5 就是当前这个大小，即上限） */
  static final float MAX_SCALE = 1.5f;
  /** 视频原始 640x360，铺满后的基准尺寸 */
  static final int BASE_VIDEO_W = 1040;
  static final int BASE_VIDEO_H = 585;
  static final int BASE_WIN_W = 460;
  static final int BASE_WIN_H = 520;
  static final int BASE_CROP_X = 300;
  static final int BASE_CROP_Y = 60;
  /** 用户可调范围 */
  static final float MIN_SCALE = 0.40f;   // 最大值的 40%
  static final float MIN_ALPHA = 0.25f;

  // ===== 以下随用户设置实时重算（默认 = 最大值 / 完全不透明） =====
  private float userScale = 1f;      // 0.40 ~ 1.00
  private float userAlpha = 1f;      // 0.25 ~ 1.00
  private int VIDEO_W_PX, VIDEO_H_PX, WIN_W_PX, WIN_H_PX, CROP_X, CROP_Y;
  private float BBOX_SCALE;
  // 气泡独立成另一个窗口（不参与触摸），宽度上限 = 原版 size*0.5
  static final int BUBBLE_MAX_W = 620;   // 放宽：长回复要能装下（约 300 字）

  private WindowManager wm;
  private WindowManager.LayoutParams params;
  private FrameLayout rootView;
  /** 本体窗口左上角的逻辑位置（拖拽以它为准，窗口撑大时用它反算实际位置） */
  private int homeX, homeY;
  /** 当前窗口对应的视频裁切偏移（动态窗口会临时变化） */
  private int curCropX, curCropY;
  private VideoGlView glView;

  private MediaPlayer player;
  private LinearLayout bubbleRow;
  private TextView bubble;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Random rng = new Random();

  private float downX, downY;
  private int startX, startY;
  private long downAt;
  private long lastTapAt;
  private float lastTapX, lastTapY;
  private final Runnable singleTapTask =
      new Runnable() {
        @Override
        public void run() {
          play(pick(clickPool), false, 0);
          say(pick(TAP_LINES));
        }
      };
  private boolean dragging;
  private ValueAnimator moveAnimator;

  private String currentClip = null;
  private boolean returnToIdle = true;

  // ---- 动画池（来自 dsh-pet 的 config.jsonc） ----
  private static final String[] IDLE = {"待机呼吸休闲"};
  private static final String[] DRAG = {"被鼠标拖拽悬空反馈"};
  private static final String[] CLICKS = {
    "点击回应-开心跃动", "点击回应-害羞惊讶", "点击回应-傲娇生气",
    "点击回应-挠痒咯咯笑", "点击回应-元气挥手"
  };

  /** 随机小动作池 */
  private static final String[] CASUAL = {
    "悠闲哼歌", "超大伸懒腰", "原地敲击桌面互动", "原地重力下蹲压缩", "哈欠连天",
    "原地小憩沉眠", "女仆屈膝礼仪", "被吓一跳", "小幅度原地360度旋转展示",
    "偷吃零食被抓住", "用鲸鱼尾巴拍打地面", "打瞌睡被惊醒", "照镜子", "整体换装试色",
    "轻快记录", "写代码", "摇扇纳凉", "晨间刷牙",
    "原地专心玩魔方", "原地蹲下玩玩具汽车", "鲸鱼吐泡泡特效", "原地跳跃抓碎头顶物品",
    "玩游戏气急败坏", "玩水枪", "小提琴演奏", "蓝鲸现世", "优雅女仆舞", "轻快摇摆舞",
    "可爱宅舞", "吹气球", "动物环绕", "放风筝", "拆礼物", "变鸽子", "扑克魔术",
    "抽陀螺", "吹笛子", "蝴蝶蜜蜂环绕头顶开花", "撸猫", "凭空生花", "骑木马",
    "三球抛接", "踢毽子", "下五子棋", "荡秋千",
    "吃白饭", "大口吃零食", "吃Token", "吃早餐", "吃午餐", "吃晚餐", "吃冰淇淋融化",
    "吃大闸蟹", "吃糖葫芦", "吃长寿面", "吃西瓜", "涮火锅"
  };

  // 兜底台词（config.jsonc 读不到时才用）
  private static final String[] TAP_LINES = {
    "戳我干嘛~", "好痒呀", "在的在的", "嘿嘿", "要不要说说今天的事？", "我在听呢"
  };
  private static final String[] DRAG_LINES = {
    "别把我甩出去！", "晕啦晕啦", "要抱抱才回来", "轻一点嘛"
  };
  /** 工作状态档位 → 动画（顺序与 config 的 events.workStatus 一致） */
  private static final String[] WORK_CLIPS = {
    "工作状态-思考冒泡",     // thinking
    "工作状态-忙碌点按",     // working
    "工作状态-清点归档",     // result
    "工作状态-原地踱步张望", // waiting
    "工作状态-雀跃庆祝",     // success
    "工作状态-垂头叹气冒汗", // error
  };

  /** 碎碎念台词（对应插件菜单里的「碎碎念」） */
  private static final String[] WHISPER_LINES = {
    "主人今天也要加油哦…",
    "桌子好像有点乱了呢",
    "喝口水吧，别一直盯着屏幕",
    "我在这儿陪着你就好",
    "偷偷说一句：你最棒了",
  };

  private static final String[] LONG_PRESS_LINES = {"困了…", "让我靠一下", "再摸就要睡着了"};

  private PetConfig cfg;
  private final List<String> memePool = new ArrayList<>();   // 已载入的表情包 bitmap 名
  private final Map<String, Bitmap> memeCache = new HashMap<>();
  private ImageView memeView;
  private String[] idlePool = IDLE, dragPool = DRAG, clickPool = CLICKS, casualPool = CASUAL;

  private static String[] pool(List<String> from, String[] fallback) {
    return (from == null || from.isEmpty()) ? fallback : from.toArray(new String[0]);
  }

  /** 把所有 categories 的动作合并成一个池（插件里按权重分，这里简化成等概率） */
  /** 把 assets/webm 下的全部动画并进池子，保证每一段动作都能播到 */
  private String[] withAllClips(String[] base) {
    java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
    if (base != null) {
      for (String b : base) if (b != null && !b.isEmpty()) set.add(b);
    }
    try {
      String[] files = getAssets().list("webm");
      if (files != null) {
        java.util.Arrays.sort(files);
        for (String f : files) {
          if (f.endsWith(".webm")) set.add(f.substring(0, f.length() - 5));
        }
      }
    } catch (Throwable e) {
      Log.w(TAG, "列出 webm 素材失败", e);
    }
    return set.toArray(new String[0]);
  }

  private static String[] casualFromCfg(PetConfig c) {
    if (c == null || c.categories.isEmpty()) return CASUAL;
    List<String> all = new ArrayList<>();
    for (List<String> g : c.categories) all.addAll(g);
    return all.isEmpty() ? CASUAL : all.toArray(new String[0]);
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  @Override
  public void onCreate() {
    super.onCreate();
    startForegroundWithNotification();
    wm = (WindowManager) getSystemService(WINDOW_SERVICE);
    try {
      cfg = PetConfig.load(this);
    // 动作池以 config.jsonc 为准；读不到才退回硬编码
    idlePool = pool(cfg.idleClips, IDLE);
    dragPool = pool(cfg.dragClips, DRAG);
    clickPool = pool(cfg.clickClips, CLICKS);
    casualPool = casualFromCfg(cfg);
    // 106 段动作全部纳入日常轮播，避免有素材却永远播不到
    casualPool = withAllClips(casualPool);   // 日常轮播用全量，保证 106 段都能出现
    loadMemePool();
    loadAndApplyPrefs();   // 先按用户设置算好尺寸，再建窗
    bindInbox();           // 与工作台建立绑定（令牌存 prefs）
    main.postDelayed(this::syncProviderFromDsh, 2500);   // 缓存服务商配置（独立模式用）
    startWorkStatusPolling();   // 工作状态联动
    buildOverlay();
    watchPrefs();
    } catch (Throwable error) {
      Log.w(TAG, "悬浮窗创建失败", error);
      rootView = null;
      stopSelf();
    }
  }

  /** 按用户设置重算所有尺寸 */
  private void recomputeScale() {
    float s = MAX_SCALE * userScale;
    VIDEO_W_PX = Math.round(BASE_VIDEO_W * s);
    VIDEO_H_PX = Math.round(BASE_VIDEO_H * s);
    WIN_W_PX = Math.round(BASE_WIN_W * s);
    WIN_H_PX = Math.round(BASE_WIN_H * s);
    CROP_X = Math.round(BASE_CROP_X * s);
    CROP_Y = Math.round(BASE_CROP_Y * s);
    BBOX_SCALE = (BASE_VIDEO_W / 640f) * s;
  }

  /** 读用户设置（大小 / 透明度），有窗口时立刻应用 */
  private void loadAndApplyPrefs() {
    android.content.SharedPreferences sp = getSharedPreferences("pet", MODE_PRIVATE);
    float newScale = Math.max(MIN_SCALE, Math.min(1f, sp.getFloat("scale", 1f)));
    userAlpha = Math.max(MIN_ALPHA, Math.min(1f, sp.getFloat("alpha", 1f)));
    boolean scaleChanged = Math.abs(newScale - userScale) > 0.0001f;
    // 旧的本体窗尺寸（缩放前），用它算屏幕上的本体参考点
    int oldHomeX = homeX, oldHomeY = homeY;
    int oldWinW = WIN_W_PX, oldWinH = WIN_H_PX;
    userScale = newScale;
    recomputeScale();

    if (rootView == null) {
      curCropX = CROP_X;
      curCropY = CROP_Y;
      return;
    }

    // 锚点只用地基本体参考系：homeX/homeY + 固定的本体窗尺寸，
    // 不读 params.width/height —— 那玩意在动画时会被 applyWindowFor 临时撑大。
    if (scaleChanged && oldWinW > 0 && oldWinH > 0) {
      // 缩放前本体中心在屏幕上的真实位置：直接用窗口的屏幕坐标反推，
      // 避开“窗口坐标系 vs 屏幕坐标系差一个状态栏(141px)”的坑。
      int[] loc = new int[2];
      rootView.getLocationOnScreen(loc);
      // 窗口左上角屏幕坐标 = loc；当时 crop 偏移是 (curCropX, curCropY)
      int cropOffX = curCropX - CROP_X;
      int cropOffY = curCropY - CROP_Y;
      // homeX/homeY 是「本体窗左上角」在 params 坐标系里的位置：
      // 保持本体中心不动 => 新 home = 旧 home + (旧尺寸 - 新尺寸)/2
      homeX = params.x - cropOffX + (oldWinW - WIN_W_PX) / 2;
      homeY = params.y - cropOffY + (oldWinH - WIN_H_PX) / 2;
    }

    curCropX = CROP_X;
    curCropY = CROP_Y;

    // 夹回屏幕内，避免缩放后跑出边界
    try {
      android.graphics.Point full = new android.graphics.Point();
      wm.getDefaultDisplay().getRealSize(full);
      int keep = dp(56);
      int maxX = Math.max(0, full.x - keep);
      int maxY = Math.max(0, full.y - keep);
      homeX = Math.max(-(WIN_W_PX - keep), Math.min(maxX, homeX));
      homeY = Math.max(-(WIN_H_PX - keep), Math.min(maxY, homeY));
    } catch (Throwable ignored) {
    }

    try {
      FrameLayout.LayoutParams vp = (FrameLayout.LayoutParams) glView.getLayoutParams();
      vp.width = VIDEO_W_PX;
      vp.height = VIDEO_H_PX;
      vp.leftMargin = -CROP_X;
      vp.topMargin = -CROP_Y;
      glView.setLayoutParams(vp);
      params.width = WIN_W_PX;
      params.height = WIN_H_PX;
      params.x = homeX;
      params.y = homeY;
      wm.updateViewLayout(rootView, params);
      curCropX = CROP_X;
      curCropY = CROP_Y;
    } catch (Throwable ignored) {
    }
    // 角色透明度走 GL 着色器（SurfaceView 不受窗口 alpha 影响）
    if (glView != null) glView.setOpacity(userAlpha);
    try {
      if (bParams != null) {
        bParams.alpha = userAlpha;
        if (bubbleShown) wm.updateViewLayout(bubbleRoot, bParams);
      }
      if (mParams != null) {
        mParams.alpha = userAlpha;
        if (memeShown) wm.updateViewLayout(memeRoot, mParams);
      }
    } catch (Throwable ignored) {
    }
    followBubble();
    // 位置存回去，重启后仍停在原地（缩放/透明度调整后的位置也要记住）
    try {
      getSharedPreferences("pet", MODE_PRIVATE)
          .edit().putInt("x", homeX).putInt("y", homeY).apply();
    } catch (Throwable ignored) {
    }
    dumpGeom("scale");
  }

  /** 设置页改完立刻生效 */
  private android.content.SharedPreferences.OnSharedPreferenceChangeListener prefListener;

  private void watchPrefs() {
    if (prefListener != null) return;
    prefListener =
        new android.content.SharedPreferences.OnSharedPreferenceChangeListener() {
          @Override
          public void onSharedPreferenceChanged(
              android.content.SharedPreferences sp, String key) {
            if ("scale".equals(key) || "alpha".equals(key)) {
              main.post(new Runnable() {
                @Override public void run() { loadAndApplyPrefs(); }
              });
            }
          }
        };
    getSharedPreferences("pet", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(prefListener);
  }

  /** 外部（设置页/快捷输入）传一句话过来让她说 */
  @Override
  public int onStartCommand(android.content.Intent intent, int flags, int startId) {
    try {
      if (intent != null) {
        String text = intent.getStringExtra("text");
        if (text != null && !text.trim().isEmpty()) {
          final String t = text.trim();
          // 服务刚创建时窗口还没建好，等 rootView 就绪
          main.postDelayed(
              new Runnable() {
                @Override
                public void run() {
                  if (rootView != null) say(t);
                }
              },
              600);
        }
        String petcmd = intent.getStringExtra("petcmd");
        if (petcmd != null && !petcmd.trim().isEmpty()) {
          final String t = petcmd.trim();
          final String ch = "work".equals(intent.getStringExtra("petchan")) ? "work" : "chat";
          main.postDelayed(
              new Runnable() {
                @Override
                public void run() {
                  if (rootView != null) runCommand(t, ch);
                }
              },
              800);
        }
        if (intent.getBooleanExtra("stop", false)) {
          stopSelf();
          return START_NOT_STICKY;
        }
      }
    } catch (Throwable ignored) {
    }
    return START_STICKY;
  }

  private void startForegroundWithNotification() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      NotificationChannel ch =
          new NotificationChannel(CHANNEL_ID, "桌宠", NotificationManager.IMPORTANCE_LOW);
      ch.setShowBadge(false);
      NotificationManager nm = getSystemService(NotificationManager.class);
      if (nm != null) nm.createNotificationChannel(ch);
    }
    Notification.Builder b =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this);
    b.setContentTitle("鲸鱼娘在桌面上")
        .setContentText("点住她可以拖动")
        .setSmallIcon(android.R.drawable.ic_menu_gallery)
        .setOngoing(true);
    startForeground(NOTICE_ID, b.build());
  }

  private void buildOverlay() {
    rootView = new FrameLayout(this);
    rootView.setBackgroundColor(0x00000000);   // 根布局透明，否则整块画不透明底色

    // 用 OpenGL 渲染：片元着色器把纯黑抠成透明（TextureView/SurfaceView 都做不到逐像素 alpha）
    glView = new VideoGlView(this);
    // 视频视图按 PET_SCALE 放大，整体向左上偏移到窗口外，
    // 窗口只留下角色本体那一块（覆盖窗口的透明边被系统裁掉）
    FrameLayout.LayoutParams vp =
        new FrameLayout.LayoutParams(VIDEO_W_PX, VIDEO_H_PX);
    vp.gravity = Gravity.START | Gravity.TOP;
    vp.leftMargin = -CROP_X;
    vp.topMargin = -CROP_Y;
    glView.setLayoutParams(vp);
    // 一次性动画（casual/点击/拖拽）播完，自动回待机循环，避免播完就停住
    glView.setOnClipEnd(() -> play(idleClip(), true, 0));
    rootView.addView(glView);

    buildBubble();   // 气泡是独立窗口，不占主窗口、不参与触摸

    params = new WindowManager.LayoutParams();
    params.type =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;
    params.format = PixelFormat.TRANSLUCENT;
    params.flags =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
    params.gravity = Gravity.TOP | Gravity.START;
    params.alpha = 1f;   // 整窗不透明；透明只靠 GL 逐像素 alpha
    // 窗口只包角色本体（视频的透明边被裁掉）
    params.width = WIN_W_PX;
    params.height = WIN_H_PX;
    // 恢复上次拖到的位置（松手即保存），重启后仍停在原处
    android.content.SharedPreferences sp = getSharedPreferences("pet", MODE_PRIVATE);
    homeX = sp.getInt("x", 0);
    homeY = sp.getInt("y", 600);
    params.x = homeX;
    params.y = homeY;

    // 上次保存的位置是给旧窗口尺寸的，启动时先夹回屏幕内，避免她一半在屏幕外
    try {
      int[] scr = screenSize();
      int maxX = Math.max(0, scr[0] - WIN_W_PX);
      int maxY = Math.max(0, scr[1] - WIN_H_PX);
      homeX = Math.max(0, Math.min(maxX, homeX));
      homeY = Math.max(0, Math.min(maxY, homeY));
      params.x = homeX;
      params.y = homeY;
    } catch (Throwable ignored) {
    }

    // 起始裁切 = 基准裁切，供侧倾/视线换算旋转中心用
    curCropX = CROP_X;
    curCropY = CROP_Y;

    wm.addView(rootView, params);
    rootView.setOnTouchListener(this::onPetTouch);
    glView.setOnTouchListener(this::onPetTouch);
    updateMetrics();   // 告诉渲染器旋转中心在哪

    buildCommandBox();

    // 立刻起播待机动画，并用 loop 真循环（不再每段重建 MediaPlayer，消除卡顿）
    play(idleClip(), true, 0);

    scheduleCasual();
    scheduleIdleMeme();
  }

  // ============ 文字气泡窗 + 配图窗（各自独立，位置固定在她旁边） ============

  private FrameLayout bubbleRoot;                       // 文字气泡窗
  private WindowManager.LayoutParams bParams;
  private boolean bubbleShown;
  private boolean bubbleAttached;   // 窗口是否已 addView（复用，不再反复增删）

  private FrameLayout memeRoot;                         // 配图窗（正上方）
  private WindowManager.LayoutParams mParams;
  private boolean memeShown;
  private boolean memeAttached;     // 同上

  /** 两个小窗共用的窗口参数。刻意不加 FLAG_NOT_TOUCHABLE：ColorOS 会给
   *  不可触摸的悬浮窗强制 0.8 alpha，导致气泡和图片发灰半透明。 */
  private WindowManager.LayoutParams overlayParams() {
    WindowManager.LayoutParams p = new WindowManager.LayoutParams();
    p.type =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;
    p.format = PixelFormat.TRANSLUCENT;
    p.flags =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
    p.gravity = Gravity.TOP | Gravity.START;
    p.alpha = 1f;
    p.width = WindowManager.LayoutParams.WRAP_CONTENT;
    p.height = WindowManager.LayoutParams.WRAP_CONTENT;
    return p;
  }

  private void buildBubble() {
    // ---------- 文字气泡 ----------
    bubbleRoot = new FrameLayout(this);
    bubbleRoot.setBackgroundColor(0x00000000);

    bubbleRow = new LinearLayout(this);
    bubbleRow.setOrientation(LinearLayout.VERTICAL);
    bubbleRow.setGravity(Gravity.CENTER);
    bubbleRow.setLayoutParams(
        new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));

    bubble = new TextView(this);
    bubble.setTextColor(0xFF2B2B3A);                     // 深字配浅底，更清晰
    bubble.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 36f);
    bubble.setMaxLines(20);  // 长回复多行展示
    bubble.setLineSpacing(0f, 1.45f);
    bubble.setPadding(38, 24, 38, 24);
    bubble.setBackgroundResource(R.drawable.bubble_bg);
    bubble.setMaxWidth(BUBBLE_MAX_W);
    bubble.setEllipsize(android.text.TextUtils.TruncateAt.END);
    try {                                                // 原版同款圆润字体
      bubble.setTypeface(
          android.graphics.Typeface.createFromAsset(getAssets(), "fonts/上首软糖体.ttf"));
    } catch (Throwable ignored) {
    }
    bubbleRow.addView(bubble);
    bubbleRoot.addView(bubbleRow);
    // 点击气泡即收起（这类小窗为了不被系统压暗必须可触摸，顺手给它一个动作）
    bubbleRoot.setOnClickListener(v -> hideBubbleNow());
    bubbleRow.setOnClickListener(v -> hideBubbleNow());
    bubble.setOnClickListener(v -> hideBubbleNow());
    bParams = overlayParams();

    // ---------- 配图 ----------
    memeRoot = new FrameLayout(this);
    memeRoot.setBackgroundColor(0x00000000);
    memeView = new ImageView(this);
    memeView.setLayoutParams(
        new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
    memeView.setAlpha(1f);
    memeRoot.addView(memeView);
    memeRoot.setOnClickListener(v -> hideBubbleNow());
    memeView.setOnClickListener(v -> hideBubbleNow());
    mParams = overlayParams();
  }

  // ---------------- 文字气泡：她本体左上角 ----------------

  private void showBubbleWindow() {
    if (bubbleRoot == null) return;
    if (!bubbleAttached) {
      // 只在第一次真正 addView；之后只切可见性，省掉每次的窗口事务和 ViewRootImpl 重建
      try {
        bParams.x = Math.max(0, params.x - dp(150));
        bParams.y = Math.max(0, params.y);
        wm.addView(bubbleRoot, bParams);
        bubbleAttached = true;
      } catch (Throwable e) {
        Log.w(TAG, "气泡窗添加失败", e);
        return;
      }
    }
    bubbleRoot.setVisibility(android.view.View.VISIBLE);
    bubbleShown = true;
    bubbleRoot.post(new Runnable() { @Override public void run() { placeBubble(0); } });
  }

  private void placeBubble(final int attempt) {
    if (!bubbleShown || bubbleRoot == null) return;
    try {
      int bw = bubbleRow.getWidth();
      int bh = bubbleRow.getHeight();
      if (bw <= 0 || bh <= 0) {
        if (attempt < 10) {
          bubbleRoot.postDelayed(new Runnable() {
            @Override public void run() { placeBubble(attempt + 1); }
          }, 50);
        }
        return;
      }
      int[] scr = screenSize();
      int[] loc = new int[2];
      rootView.getLocationOnScreen(loc);   // 主窗口真实屏幕坐标（小窗是屏幕坐标系）
      int gap = dp(6);
      int x = loc[0] + dp(10) - bw;   // 气泡右缘贴她本体左缘 -> 左上角
      int y = loc[1] + dp(4);         // 顶边与她头顶齐平
      if (x < gap) x = gap;             // 左边放不下就贴屏幕左边
      if (x + bw > scr[0] - gap) x = scr[0] - bw - gap;
      if (y + bh > scr[1] - gap) y = scr[1] - bh - gap;
      if (y < gap) y = gap;
      bParams.x = x;
      bParams.y = y;
      wm.updateViewLayout(bubbleRoot, bParams);
    } catch (Throwable ignored) {
    }
  }

  private void hideBubbleWindow() {
    if (bubbleRoot == null) return;
    bubbleRoot.setVisibility(android.view.View.GONE);   // 只隐藏，窗口保留复用
    bubbleShown = false;
  }

  // ---------------- 配图：她正上方，紧贴呆毛 ----------------

  private void showMemeWindow() {
    if (memeRoot == null || memeView == null || memeView.getDrawable() == null) return;
    if (!memeAttached) {
      try {
        mParams.x = Math.max(0, params.x);
        mParams.y = Math.max(0, params.y - dp(150));
        wm.addView(memeRoot, mParams);
        memeAttached = true;
      } catch (Throwable e) {
        Log.w(TAG, "配图窗添加失败", e);
        return;
      }
    }
    memeRoot.setVisibility(android.view.View.VISIBLE);
    memeShown = true;
    memeRoot.post(new Runnable() { @Override public void run() { placeMeme(0); } });
  }

  private void placeMeme(final int attempt) {
    if (!memeShown || memeRoot == null) return;
    try {
      int mw = memeView.getWidth();
      int mh = memeView.getHeight();
      if (mw <= 0 || mh <= 0) {
        if (attempt < 10) {
          memeRoot.postDelayed(new Runnable() {
            @Override public void run() { placeMeme(attempt + 1); }
          }, 50);
        }
        return;
      }
      int[] scr = screenSize();
      int[] loc = new int[2];
      rootView.getLocationOnScreen(loc);        // 主窗口真实屏幕坐标
      int gap = dp(4);
      int x = loc[0] + (params.width - mw) / 2;  // 与她水平居中
      int y = loc[1] - mh + dp(3);               // 底边正好压在她呆毛上方
      if (x < gap) x = gap;
      if (x + mw > scr[0] - gap) x = scr[0] - mw - gap;
      if (y < gap) y = gap;
      mParams.x = x;
      mParams.y = y;
      wm.updateViewLayout(memeRoot, mParams);
    } catch (Throwable ignored) {
    }
  }

  private void hideMemeWindow() {
    if (memeRoot == null) return;
    memeRoot.setVisibility(android.view.View.GONE);   // 只隐藏，窗口保留复用
    memeShown = false;
  }

  /** params 变了以后，反算本体窗口的逻辑位置 */
  private void syncHomeFromParams() {
    homeX = params.x - (curCropX - CROP_X);
    homeY = params.y - (curCropY - CROP_Y);
  }

  /**
   * 按正在播放的动画调整窗口：空闲=只包本体；特效动画（蝴蝶/气球/烟花等）
   * 按实测包围盒临时撑开，播完回到本体大小。角色在屏幕上的位置不变。
   */
  private void applyWindowFor(String clip) {
    syncHomeFromParams();
    // 基准 = 本体窗（固定大小，保证角色本体一定完整）
    int cropX = CROP_X, cropY = CROP_Y;
    int right = CROP_X + WIN_W_PX, bottom = CROP_Y + WIN_H_PX;
    // 再并上这段动画的真实包围盒（特效会跑到本体外面，不撑开就会被窗口裁掉）
    int[] bb = ClipBounds.get(clip);
    if (bb != null) {
      int m = 16;
      int bx0 = Math.round(bb[0] * BBOX_SCALE), by0 = Math.round(bb[1] * BBOX_SCALE);
      int bx1 = Math.round(bb[2] * BBOX_SCALE), by1 = Math.round(bb[3] * BBOX_SCALE);
      cropX = Math.min(cropX, Math.max(0, bx0 - m));
      cropY = Math.min(cropY, Math.max(0, by0 - m));
      right = Math.max(right, Math.min(VIDEO_W_PX, bx1 + m));
      bottom = Math.max(bottom, Math.min(VIDEO_H_PX, by1 + m));
    }
    int w = right - cropX, h = bottom - cropY;
    Log.i(TAG, "窗口适配 " + clip + " -> " + w + "x" + h + " crop=(" + cropX + "," + cropY + ")");
    if (cropX == curCropX && cropY == curCropY && w == params.width && h == params.height) {
      return;   // 没变化，不折腾
    }
    curCropX = cropX;
    curCropY = cropY;
    params.width = w;
    params.height = h;
    // 让「角色本体」在屏幕上的位置保持不动：窗口左上角随裁切偏移反向移动
    // 窗口左移/上移同样的量，本体在屏幕上的位置才会保持不动
    params.x = homeX + (cropX - CROP_X);
    params.y = homeY + (cropY - CROP_Y);
    try {
      FrameLayout.LayoutParams vp = (FrameLayout.LayoutParams) glView.getLayoutParams();
      vp.leftMargin = -cropX;
      vp.topMargin = -cropY;
      glView.setLayoutParams(vp);
      wm.updateViewLayout(rootView, params);
    } catch (Throwable ignored) {
    }
    updateMetrics();   // 裁切变了，旋转中心跟着变，否则侧倾会绕错点
    followBubble();
  }

  /** 她挪动后，跟着把气泡和配图重新贴到她旁边 */
  private void followBubble() {
    if (bubbleShown) placeBubble(0);
    if (memeShown) placeMeme(0);
  }

  /** 闲暇时自动随机来一句 + 随机表情包（不靠点击触发） */
  private boolean idleChainRunning;

  private void scheduleIdleMeme() {
    if (idleChainRunning) return;   // 只允许一条链，避免刷新间隔被压缩
    idleChainRunning = true;
    main.postDelayed(
        new Runnable() {
          @Override
          public void run() {
            if (rootView != null && !dragging && !bubbleShown && !inWorkState()) {
              sayWithMeme(pick(TAP_LINES), true);
            }
            scheduleIdleMeme();
          }
        },
        9000 + rng.nextInt(2000));   // 约 10s 刷新一张表情包+气泡
  }

  // ==================== 动画调度 ====================

  private String idleClip() {
    return idlePool[rng.nextInt(idlePool.length)];
  }

  private String pick(String[] pool) {
    return pool[rng.nextInt(pool.length)];
  }

  /** 对外：播一段，播完回待机 */
  private void play(final String clip, final boolean loop, long delayMs) {
    Runnable r =
        new Runnable() {
          @Override
          public void run() {
            applyWindowFor(clip);
            startPlay(clip, loop);
          }
        };
    if (delayMs > 0) main.postDelayed(r, delayMs);
    else main.post(r);
  }

  /** 真正启动播放（委托给 GL 渲染器） */
  private void startPlay(String clip, boolean loop) {
    if (glView != null) glView.play(clip, loop);
    currentClip = clip;
    returnToIdle = !loop;
  }

  private void scheduleCasual() {
    main.postDelayed(
        new Runnable() {
          @Override
          public void run() {
            // 工作状态期间不打断档位动画
            if (rootView != null && !dragging && !inWorkState()) {
              play(pick(casualPool), false, 0);
            }
            scheduleCasual();
          }
        },
        8000 + rng.nextInt(10000));
  }

  // ==================== 触摸 ====================

  private boolean onPetTouch(View v, MotionEvent event) {
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        downX = event.getRawX();
        downY = event.getRawY();
        startX = params.x;
        startY = params.y;
        downAt = System.currentTimeMillis();
        dragging = false;
        main.removeCallbacks(gazeReset);
        updateGaze(event.getRawX(), event.getRawY());
        return true;
      case MotionEvent.ACTION_MOVE:
        float dx = event.getRawX() - downX;
        float dy = event.getRawY() - downY;
        if (!dragging && (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6))) {
          dragging = true;
          play(pick(dragPool), true, 0);
        }
        if (dragging) {
          params.x = startX + (int) dx;
          params.y = startY + (int) dy;
          try {
            wm.updateViewLayout(rootView, params);
          } catch (Throwable ignored) {
          }
          // 拖拽侧倾：按水平位移给最多 ±7°，松手归零（原版 leanX 的手感）
          if (glView != null) {
            float lean = Math.max(-7f, Math.min(7f, dx / dp(45) * 7f));
            glView.setLeanDeg(lean);
          }
        }
        updateGaze(event.getRawX(), event.getRawY());
        return true;
      case MotionEvent.ACTION_UP:
        long held = System.currentTimeMillis() - downAt;
        if (glView != null) glView.setLeanDeg(0f);   // 松手立刻扶正
        main.removeCallbacks(gazeReset);
        main.postDelayed(gazeReset, 2200);           // 视线回正：2.2s 后慢慢收回
        if (dragging) {
          play(pick(clickPool), false, 0);
          say(pick(DRAG_LINES));
          settleHome();
        } else if (held < 400) {
          long now = System.currentTimeMillis();
          if (now - lastTapAt < 320 && Math.abs(event.getRawX() - lastTapX) < dp(80)
              && Math.abs(event.getRawY() - lastTapY) < dp(80)) {
            // 双击：打开动作选择器
            lastTapAt = 0;
            main.removeCallbacks(singleTapTask);
            showPanel();
          } else {
            lastTapAt = now;
            lastTapX = event.getRawX();
            lastTapY = event.getRawY();
            main.removeCallbacks(singleTapTask);
            main.postDelayed(singleTapTask, 330);   // 等一下看有没有第二下
          }
        } else if (held > 900) {
          play("原地小憩沉眠", true, 0);
          showCommandBox();   // 长按：弹出「工作/聊天」输入框
        }
        dragging = false;
        return true;
      default:
        return false;
    }
  }


  /** 把关键控件的真实坐标写到文件，用于精确核对布局（不依赖截图猜像素） */
  private void dumpGeom(String tag) {
    main.postDelayed(() -> {
      try {
        StringBuilder sb = new StringBuilder();
        sb.append("=== ").append(tag).append(" ===\n");
        sb.append("窗口 params: x=").append(params.x).append(" y=").append(params.y)
          .append(" w=").append(params.width).append(" h=").append(params.height).append("\n");
        sb.append("rootView: ").append(bounds(rootView)).append("\n");
        sb.append("glView  : ").append(bounds(glView)).append("\n");
        sb.append("bubbleRow: ").append(bounds(bubbleRow)).append("\n");
        sb.append("bubble   : ").append(bounds(bubble)).append("\n");
        if (memeView != null) sb.append("meme     : ").append(bounds(memeView))
            .append(" vis=").append(memeView.getVisibility()).append("\n");
        sb.append("px 密度: ").append(getResources().getDisplayMetrics().density).append("\n");
        java.io.FileWriter fw = new java.io.FileWriter("/sdcard/Download/pet_geom.txt", false);
        fw.write(sb.toString());
        fw.close();
      } catch (Throwable ignored) {}
    }, 400);
  }

  private static String bounds(android.view.View v) {
    if (v == null) return "null";
    int[] loc = new int[2];
    v.getLocationOnScreen(loc);
    return "screen(" + loc[0] + "," + loc[1] + ") size " + v.getWidth() + "x" + v.getHeight()
        + " vis=" + v.getVisibility();
  }

  /** 屏幕真实像素尺寸（Service 里 getDisplayMetrics 可能不准，走 WindowManager 取） */
  private int[] screenSize() {
    try {
      android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
      wm.getDefaultDisplay().getRealMetrics(dm);
      if (dm.widthPixels >= 400 && dm.heightPixels >= 800) {
        return new int[] {dm.widthPixels, dm.heightPixels};
      }
    } catch (Throwable ignored) {
    }
    return new int[] {1272, 2800};
  }

  /**
   * 松手后**自由停留**在放手的位置，不做左右贴边吸附。
   * 鲸鱼娘可以待在屏幕任意位置（中间、偏左、偏右都行），只保证不彻底拖丢。
   * 想让她回初始位置用面板里的「回到初始位置」。
   */
  private void settleHome() {
    try {
      int[] s = screenSize();
      syncHomeFromParams();
      // 只夹「至少留一块在屏幕内」，防止拖出屏幕找不回来
      int keep = dp(56);
      int minX = -(WIN_W_PX - keep), maxX = s[0] - keep;
      int minY = -(WIN_H_PX - keep), maxY = s[1] - keep;
      homeX = Math.max(minX, Math.min(maxX, homeX));
      homeY = Math.max(minY, Math.min(maxY, homeY));

      params.x = homeX + (curCropX - CROP_X);
      params.y = homeY + (curCropY - CROP_Y);
      wm.updateViewLayout(rootView, params);
      getSharedPreferences("pet", MODE_PRIVATE)
          .edit().putInt("x", homeX).putInt("y", homeY).apply();
      followBubble();
      dumpGeom("dragEnd");
    } catch (Throwable ignored) {
    }
  }

  /** 触点相对角色中心的偏移，换算成轻微的视线位移（最多 9px，不打扰阅读） */
  private void updateGaze(float rawX, float rawY) {
    if (glView == null) return;
    float cx = params.x + params.width / 2f;
    float cy = params.y + params.height / 2f;
    float gx = Math.max(-9f, Math.min(9f, (rawX - cx) * 0.05f));
    float gy = Math.max(-7f, Math.min(7f, (rawY - cy) * 0.04f));
    glView.setGazePx(gx, gy);
  }

  /** 视线回正（手指离开一会儿后慢慢收回中间） */
  private final Runnable gazeReset =
      new Runnable() {
        @Override
        public void run() {
          if (glView != null) glView.setGazePx(0f, 0f);
        }
      };

  /** 把「可见区域在视频里的中心 + 宽高比」告诉渲染器，旋转才不会绕错点 */
  private void updateMetrics() {
    if (glView == null || params == null) return;
    float u = (curCropX + params.width / 2f) / (float) Math.max(1, VIDEO_W_PX);
    float v = (curCropY + params.height / 2f) / (float) Math.max(1, VIDEO_H_PX);
    glView.setMetrics(VIDEO_W_PX, VIDEO_H_PX, u, v);
  }

  private void animateTo(final int targetX, final int targetY) {
    final int fromX = params.x, fromY = params.y;
    if (fromX == targetX && fromY == targetY) return;
    if (moveAnimator != null) moveAnimator.cancel();
    moveAnimator = ValueAnimator.ofFloat(0f, 1f);
    moveAnimator.setDuration(240);
    moveAnimator.addUpdateListener(
        new ValueAnimator.AnimatorUpdateListener() {
          @Override
          public void onAnimationUpdate(ValueAnimator a) {
            float f = (float) a.getAnimatedValue();
            float e = 1f - (1f - f) * (1f - f) * (1f - f);
            params.x = fromX + (int) ((targetX - fromX) * e);
            params.y = fromY + (int) ((targetY - fromY) * e);
            try {
              if (rootView != null) wm.updateViewLayout(rootView, params);
            } catch (Throwable ignored) {
            }
          }
        });
    moveAnimator.start();
  }

  // ==================== 气泡 ====================

  /** 扫描 assets/memes 下的表情包（以 config.jsonc 的键为准） */
  private void loadMemePool() {
    memePool.clear();
    try {
      if (cfg != null && !cfg.memes.isEmpty()) {
        String[] files = getAssets().list("memes");
        Set<String> have = new HashSet<>();
        if (files != null) for (String f : files) {
          int dot = f.lastIndexOf('.');
          have.add(dot > 0 ? f.substring(0, dot) : f);
        }
        for (String k : cfg.memes) if (have.contains(k)) memePool.add(k);
      }
      Log.i("PetService", "表情包可用: " + memePool.size() + " 张");
    } catch (Throwable e) {
      Log.w("PetService", "表情包扫描失败: " + e);
    }
  }

  /** 后台线程：表情包解码和预缩放都在这儿做，绝不占主线程 */
  private final java.util.concurrent.ExecutorService memeExecutor =
      java.util.concurrent.Executors.newSingleThreadExecutor();
  /** 缩放后的缓存：同一个表情包只缩放一次，之后直接复用 */
  private final Map<String, Bitmap> memeScaledCache = new HashMap<>();
  private static final int MEME_MAX_SIDE = 530;

  /** 后台解好 + 缩放好，再回主线程上屏 */
  private void showMemeAsync(final String name, final String text) {
    memeExecutor.execute(
        () -> {
          Bitmap scaled = memeScaledCache.get(name);
          if (scaled == null) {
            Bitmap raw = loadMeme(name);
            if (raw != null) {
              float sc =
                  Math.min(
                      (float) MEME_MAX_SIDE / raw.getWidth(),
                      (float) MEME_MAX_SIDE / raw.getHeight());
              int w = Math.max(1, (int) (raw.getWidth() * sc));
              int h = Math.max(1, (int) (raw.getHeight() * sc));
              scaled = Bitmap.createScaledBitmap(raw, w, h, true);
              memeScaledCache.put(name, scaled);
            }
          }
          final Bitmap ready = scaled;
          main.post(
              () -> {
                if (bubble == null) return;
                if (text != null) bubble.setText(text);
                if (ready != null) {
                  memeView.setImageDrawable(new BitmapDrawable(getResources(), ready));
                  memeView.setAlpha(1f);
                  showMemeWindow();
                } else {
                  hideMemeWindow();
                }
              });
        });
  }

  private Bitmap loadMeme(String name) {
    Bitmap b = memeCache.get(name);
    if (b != null) return b;
    try (InputStream in = getAssets().open("memes/" + name + ".png")) {
      BitmapFactory.Options o = new BitmapFactory.Options();
      o.inSampleSize = 1;
      b = BitmapFactory.decodeStream(in, null, o);
      if (b != null) memeCache.put(name, b);
    } catch (Throwable e) {
      Log.w("PetService", "表情包载入失败 " + name + ": " + e);
    }
    return b;
  }

  // ==================== 功能面板（双击鲸鱼娘弹出） ====================
  // 对齐 npm:dsh-pet 的右键菜单：工具项 + 「动作 → 分类 → 动画」，只去掉「对话」
  //（对话在本 APK 里由长按的指令框承担）。

  private FrameLayout panelRoot;
  private WindowManager.LayoutParams panelParams;
  private String[] allClips;

  /** 全部动作名（assets/webm 下文件名去扩展名） */
  private String[] allClips() {
    if (allClips == null) allClips = withAllClips(null);
    return allClips;
  }

  /** 「分类 → 该分类的动作」，顺序与插件菜单一致 */
  private java.util.List<String[]> clipSections() {
    java.util.List<String[]> out = new java.util.ArrayList<>();
    java.util.Set<String> used = new java.util.HashSet<>();
    java.util.function.BiConsumer<String, java.util.List<String>> add =
        (label, list) -> {
          if (list == null || list.isEmpty()) return;
          java.util.List<String> keep = new java.util.ArrayList<>();
          for (String n : list) {
            if (n != null && !n.isEmpty() && used.add(n)) keep.add(n);
          }
          if (keep.isEmpty()) return;
          String[] sec = new String[keep.size() + 1];
          sec[0] = label;
          for (int i = 0; i < keep.size(); i++) sec[i + 1] = keep.get(i);
          out.add(sec);
        };

    if (cfg != null) {
      add.accept("待机", cfg.idleClips);
      add.accept("转向", cfg.turnClips);
      add.accept("拖拽", cfg.dragClips);
      add.accept("点击回应", cfg.clickClips);
      for (int i = 0; i < cfg.categories.size(); i++) {
        String id = i < cfg.categoryIds.size() ? cfg.categoryIds.get(i) : ("分类" + (i + 1));
        add.accept(id, cfg.categories.get(i));
      }
    }
    // 事件池按名字前缀归组（插件的 events 分组同义）
    java.util.List<String> balance = new java.util.ArrayList<>();
    java.util.List<String> whisper = new java.util.ArrayList<>();
    java.util.List<String> work = new java.util.ArrayList<>();
    java.util.List<String> rest = new java.util.ArrayList<>();
    for (String n : allClips()) {
      if (n.startsWith("余额-")) balance.add(n);
      else if (n.startsWith("碎碎念-")) whisper.add(n);
      else if (n.startsWith("工作状态-")) work.add(n);
      else rest.add(n);
    }
    add.accept("余额档位", balance);
    add.accept("碎碎念", whisper);
    add.accept("工作状态", work);
    add.accept("其他动作", rest);
    return out;
  }

  private TextView panelItem(String text, boolean header, Runnable onClick) {
    TextView tv = new TextView(this);
    tv.setText(text);
    if (!header) {
      // 每一项都做成"半透明 + 极淡描边"的柔边胶囊，选择区域一眼可见
      GradientDrawable chip = new GradientDrawable();
      chip.setColor(0x26FFFFFF);
      chip.setCornerRadius(dp(12));
      chip.setStroke(dp(1), 0x40FFFFFF);
      tv.setBackground(chip);
      FancyText.font(tv);
      LinearLayout.LayoutParams clp =
          new LinearLayout.LayoutParams(
              LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
      clp.bottomMargin = dp(5);
      tv.setLayoutParams(clp);
    }
    if (header) {
      tv.setTextColor(0xFF8A8AA0);
      tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 28f);
      tv.setPadding(dp(10), dp(16), dp(10), dp(6));
    } else {
      tv.setTextColor(0xFF3A3A4C);
      tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 34f);
      tv.setPadding(dp(10), dp(13), dp(10), dp(13));
      if (onClick != null) tv.setOnClickListener(v -> onClick.run());
    }
    return tv;
  }

  private void buildPanel() {
    if (panelRoot != null) return;
    panelRoot = new FrameLayout(this);
    panelRoot.setBackgroundColor(0x00000000);

    LinearLayout card = new LinearLayout(this);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setPadding(dp(18), dp(14), dp(18), dp(14));
    android.graphics.drawable.GradientDrawable bg =
        new android.graphics.drawable.GradientDrawable();
    bg.setColor(0xA0FBFCFF);          // 更透：背后内容清晰可见
    bg.setCornerRadius(dp(24));
    bg.setStroke(dp(1), 0x66FFFFFF);
    card.setBackground(bg);
    card.setElevation(dp(6));
    card.setLayoutParams(new FrameLayout.LayoutParams(dp(300), dp(452)));

    LinearLayout head = new LinearLayout(this);
    head.setOrientation(LinearLayout.HORIZONTAL);
    head.setGravity(Gravity.CENTER_VERTICAL);
    FancyText title = new FancyText(this);
    title.setText("鲸鱼娘");
    title.setTextColor(0xFF3D63D8);
    title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 44f);
    title.stroke(0xFFFFFFFF, 0.08f).grad(false);
    head.addView(
        title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    TextView close = new TextView(this);
    close.setText("✕");
    close.setTextColor(0xFF7A7A8C);
    close.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 42f);
    close.setPadding(dp(14), dp(2), dp(2), dp(10));
    close.setOnClickListener(v -> hidePanel());
    head.addView(close);
    card.addView(head);

    android.widget.ScrollView sv = new android.widget.ScrollView(this);
    LinearLayout list = new LinearLayout(this);
    list.setOrientation(LinearLayout.VERTICAL);
    sv.addView(
        list,
        new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT));

    // ---- 工具项（除「对话」外的全部）----
    list.addView(panelItem("功能", true, null));
    list.addView(panelItem("打开 DeepSeek Harness", false, this::actOpenDsh));
    list.addView(panelItem("查看余额", false, this::actBalance));
    list.addView(panelItem("把最后一句发到 DeepSeek", false, this::actSendDeepSeek));
    list.addView(panelItem("碎碎念", false, this::actWhisper));
    list.addView(panelItem("回到初始位置", false, this::actHome));
    list.addView(panelItem("重载配置", false, this::actReload));
    boolean bound = petToken() != null && !petToken().isEmpty();
    list.addView(
        panelItem(bound ? "已绑定工作台（点击重绑）" : "绑定工作台", false, this::actBind));
    if (bound) list.addView(panelItem("解绑工作台", false, this::actUnbind));

    // ---- 动作（按分类分组）----
    for (String[] sec : clipSections()) {
      list.addView(panelItem(sec[0], true, null));
      for (int i = 1; i < sec.length; i++) {
        final String clip = sec[i];
        list.addView(panelItem(clip, false, () -> { hidePanel(); play(clip, false, 0); }));
      }
    }

    card.addView(
        sv,
        new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
    panelRoot.addView(card);

    panelParams = new WindowManager.LayoutParams();
    panelParams.type =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;
    panelParams.format = PixelFormat.TRANSLUCENT;
    panelParams.flags =
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
    panelParams.gravity = Gravity.TOP | Gravity.START;
    panelParams.alpha = 1f;
    panelParams.width = dp(316);
    panelParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
  }

  public void showPanel() {
    try {
      panelRoot = null;   // 每次重建，绑定状态/配置变更才反映得出来
      buildPanel();
      if (panelRoot.getParent() == null) {
        int[] loc = new int[2];
        rootView.getLocationOnScreen(loc);
        int x = loc[0] - dp(340);
        int y = loc[1];
        int[] scr = screenSize();
        if (x < dp(8)) x = loc[0] + params.width + dp(8);
        x = Math.max(dp(8), Math.min(scr[0] - dp(316) - dp(8), x));
        y = Math.max(dp(8), Math.min(scr[1] - dp(492) - dp(8), y));
        panelParams.x = x;
        panelParams.y = y;
        wm.addView(panelRoot, panelParams);
      }
    } catch (Throwable e) {
      Log.w(TAG, "功能面板打开失败", e);
    }
  }

  public void hidePanel() {
    if (panelRoot == null) return;
    try {
      if (panelRoot.getParent() != null) wm.removeView(panelRoot);
    } catch (Throwable ignored) {
    }
  }

  // ---------- 工具项实现 ----------

  /** 打开 DeepSeek Harness（桌面端菜单里的「打开 DeepSeek Harness」） */
  private void actOpenDsh() {
    hidePanel();
    // 直接用显式组件，绕开 Android 11+ 的包可见性限制
    try {
      android.content.Intent i = new android.content.Intent();
      i.setComponent(new android.content.ComponentName(harnessPkg(), harnessAct()));
      i.addFlags(
          android.content.Intent.FLAG_ACTIVITY_NEW_TASK
              | android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
      startActivity(i);
      return;
    } catch (Throwable ignored) {
    }
    try {
      android.content.Intent i = getPackageManager().getLaunchIntentForPackage(harnessPkg());
      if (i != null) {
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        return;
      }
    } catch (Throwable ignored) {
    }
    say("找不到工作台…");
  }

  /** 把最近一句话交给 DeepSeek App（单向外发，不依赖工作台，也不需要 API Key） */
  private void actSendDeepSeek() {
    hidePanel();
    String t = lastSay;
    if (t == null || t.isEmpty()) {
      say("还没有可以转发的，先长按我聊一句吧");
      return;
    }
    if (DeepSeekShare.send(this, t)) {
      say("发给 DeepSeek 啦，去那边接着聊～");
    } else {
      say("没找到 DeepSeek App，也没法分享…");
    }
  }

  /** 查看余额：先播一档余额动画，同时向工作台要真实余额 */
  private void actBalance() {
    hidePanel();
    java.util.List<String> pool = clipsWithPrefix("余额-");
    if (!pool.isEmpty()) play(pool.get(rng.nextInt(pool.size())), false, 0);
    say("让我看看钱包…");
    new Thread(
            () -> {
              String body = httpGet(inboxUrl("/balance"), 15000);
              String shown = null;
              if (body != null && body.contains("\"ok\":true")) {
                String t = jsonStr(body, "text");
                shown = (t == null || t.isEmpty()) ? "余额拿到了，但没数字" : t;
              }
              if (shown == null) {
                // 工作台不可用/不支持 → 独立模式直接查
                shown = standaloneBalance();
              }
              if (shown == null) {
                shown = "连不上工作台…";
              }
              final String result = shown;
              main.post(() -> say(result));
            })
        .start();
  }

  /** 碎碎念：让工作台用当前对话模型现场生成一句，连配图一起显示 */
  private void actWhisper() {
    hidePanel();
    java.util.List<String> pool = clipsWithPrefix("碎碎念-");
    if (!pool.isEmpty()) play(pool.get(rng.nextInt(pool.size())), false, 0);
    say("唔…想点什么呢");
    new Thread(
            () -> {
              String body = httpGet(inboxUrl("/whisper"), 70000);
              String text = body == null ? null : jsonStr(body, "text");
              final String img = body == null ? null : jsonStr(body, "image");
              if (text == null || text.isEmpty()) {
                // 工作台不可用 → 独立模式直接问服务商
                text = standaloneWhisper();
              }
              if (text == null || text.isEmpty()) {
                // 还是不行：退回内置台词，至少不说空话
                main.post(() -> say(pick(WHISPER_LINES)));
              } else {
                final String t = text;
                main.post(() -> sayWithNamedMeme(t, img));
              }
            })
        .start();
  }

  /** 回到初始位置 */
  private void actHome() {
    hidePanel();
    try {
      int[] scr = screenSize();
      homeX = Math.max(0, (scr[0] - WIN_W_PX) / 2);
      homeY = Math.max(0, scr[1] - WIN_H_PX - dp(80));
      curCropX = CROP_X;
      curCropY = CROP_Y;
      params.width = WIN_W_PX;
      params.height = WIN_H_PX;
      params.x = homeX;
      params.y = homeY;
      FrameLayout.LayoutParams vp = (FrameLayout.LayoutParams) glView.getLayoutParams();
      vp.leftMargin = -CROP_X;
      vp.topMargin = -CROP_Y;
      glView.setLayoutParams(vp);
      wm.updateViewLayout(rootView, params);
      getSharedPreferences("pet", MODE_PRIVATE)
          .edit().putInt("x", homeX).putInt("y", homeY).apply();
      followBubble();
      say("回来啦~");
    } catch (Throwable e) {
      Log.w(TAG, "回到初始位置失败", e);
    }
  }

  /** 重载配置：重新读 config.jsonc 并重建动作池 */
  private void actReload() {
    hidePanel();
    try {
      cfg = PetConfig.load(this);
      idlePool = pool(cfg.idleClips, IDLE);
      dragPool = pool(cfg.dragClips, DRAG);
      clickPool = pool(cfg.clickClips, CLICKS);
      casualPool = withAllClips(casualFromCfg(cfg));
      loadMemePool();
      allClips = null;
      // 面板内容依赖配置，重建一次
      panelRoot = null;
      say("配置已重载");
    } catch (Throwable e) {
      say("重载失败了…");
    }
  }

  // ---------- 工作状态联动 ----------

  private volatile boolean alive = true;
  private volatile String lastWorkState = "\u0000";   // 哨兵：启动时先同步一次
  private static final String[] WORK_STATES = {
    "thinking", "working", "result", "waiting", "success", "error"
  };

  /** 轮询工作台的工作状态，变化时播对应档位动画 + 说一句 */
  private void startWorkStatusPolling() {
    new Thread(
            () -> {
              while (alive) {
                try {
                  Thread.sleep(2500);
                } catch (InterruptedException e) {
                  return;
                }
                if (!alive) return;
                String body = httpGet(inboxUrl("/workstatus"), 6000);
                if (body == null) continue;
                if (body.contains("\"ok\":false")) continue;
                String st = jsonStr(body, "state");
                if (st == null) st = "";                 // null → 空闲
                if (st.equals(lastWorkState)) continue;  // 没变就不折腾
                lastWorkState = st;
                final String state = st;
                main.post(() -> onWorkStateChanged(state));
              }
            })
        .start();
  }

  /** 当前是否处于工作台的工作态 */
  private boolean inWorkState() {
    String st = lastWorkState;
    return st != null && !st.isEmpty() && !"\u0000".equals(st);
  }

  private void onWorkStateChanged(String state) {
    if (rootView == null) return;
    if (state.isEmpty()) {
      // 回空闲：切回待机循环，不吵她
      main.removeCallbacks(singleTapTask);
      play(idleClip(), true, 0);
      return;
    }
    int idx = -1;
    for (int i = 0; i < WORK_STATES.length; i++) {
      if (WORK_STATES[i].equals(state)) {
        idx = i;
        break;
      }
    }
    if (idx < 0) return;
    play(WORK_CLIPS[idx], true, 0);   // 该档位持续期间循环
    // 如果已有气泡在显示（例如聊天回复），不覆盖它
    // 工作汇报每 2.5 秒轮询一次，与聊天气泡（3.5~18 秒）容易撞车
    // 让聊天气泡优先，等它淡出后再播汇报
    if (bubbleShown) return;
    // 文案：config.workStatusTexts 第 idx 档随机一句（读不到就不说）
    try {
      if (cfg != null && idx < cfg.workStatusTexts.size()) {
        java.util.List<String> lines = cfg.workStatusTexts.get(idx);
        if (lines != null && !lines.isEmpty()) {
          say(lines.get(rng.nextInt(lines.size())));
        }
      }
    } catch (Throwable ignored) {
    }
  }

  /** 绑定工作台 */
  private void actBind() {
    hidePanel();
    bindInbox();
    say("已绑定工作台");
  }

  /** 解绑工作台：清本地令牌并通知服务端 */
  private void actUnbind() {
    hidePanel();
    new Thread(
            () -> {
              try {
                HttpURLConnection c =
                    (HttpURLConnection)
                        new java.net.URL(inboxUrl("/unbind")).openConnection();
                c.setRequestMethod("POST");
                applyAuth(c);
                c.setConnectTimeout(3000);
                c.setReadTimeout(5000);
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(2);
                java.io.OutputStream os = c.getOutputStream();
                os.write("{}".getBytes("UTF-8"));
                os.close();
                c.getResponseCode();
                c.disconnect();
              } catch (Throwable ignored) {
              }
              getSharedPreferences("pet", MODE_PRIVATE).edit().remove("token").apply();
              main.post(() -> say("已解绑工作台"));
            })
        .start();
  }

  private java.util.List<String> clipsWithPrefix(String prefix) {
    java.util.List<String> out = new java.util.ArrayList<>();
    for (String n : allClips()) if (n.startsWith(prefix)) out.add(n);
    return out;
  }

  // ==================== 工作/聊天 指令输入框（长按鲸鱼娘弹出） ====================

  private FrameLayout cmdRoot;
  private WindowManager.LayoutParams cmdParams;
  private boolean cmdShown;
  private String cmdChannel = "chat";          // chat / work
  private TextView cmdTitle;
  private android.widget.EditText cmdInput;
  private TextView cmdStatus;
  private Button cmdWorkBtn, cmdChatBtn;
  private android.os.Handler cmdMain = new android.os.Handler(android.os.Looper.getMainLooper());


  /** 建一次，之后反复显示/隐藏 */
  private void buildCommandBox() {
    if (cmdRoot != null) return;
    cmdRoot = new FrameLayout(this);
    cmdRoot.setBackgroundColor(0x00000000);

    final LinearLayout card = new LinearLayout(this);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setPadding(dp(28), dp(20), dp(28), dp(20));
    android.graphics.drawable.GradientDrawable bg =
        new android.graphics.drawable.GradientDrawable();
    bg.setColor(0xA0FBFCFF);
    bg.setCornerRadius(dp(24));
    bg.setStroke(dp(1), 0x66FFFFFF);
    card.setBackground(bg);
    card.setElevation(dp(6));
    card.setLayoutParams(
        new FrameLayout.LayoutParams(dp(316), FrameLayout.LayoutParams.WRAP_CONTENT));
    cmdRoot.addView(card);

    // 标题 + 关闭
    LinearLayout head = new LinearLayout(this);
    head.setOrientation(LinearLayout.HORIZONTAL);
    head.setGravity(Gravity.CENTER_VERTICAL);
    cmdTitle = new FancyText(this);
    cmdTitle.setTextColor(0xFF3D63D8);
    cmdTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 42f);
    ((FancyText) cmdTitle).stroke(0xFFFFFFFF, 0.08f).grad(false);
    cmdTitle.setText("和鲸鱼娘聊天");
    head.addView(
        cmdTitle,
        new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    TextView close = new TextView(this);
    close.setText("✕");
    close.setTextColor(0xFF7A7A8C);
    close.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 44f);
    close.setPadding(dp(16), dp(4), dp(4), dp(12));
    close.setOnClickListener(v -> hideCommandBox());
    head.addView(close);
    card.addView(head);

    // 工作 / 聊天 切换
    LinearLayout tabs = new LinearLayout(this);
    tabs.setOrientation(LinearLayout.HORIZONTAL);
    tabs.setPadding(0, dp(12), 0, dp(12));
    cmdWorkBtn = new Button(this);
    cmdWorkBtn.setText("工作");
    FancyText.font(cmdWorkBtn);
    cmdWorkBtn.setAllCaps(false);
    cmdWorkBtn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 38f);
    cmdChatBtn = new Button(this);
    cmdChatBtn.setText("聊天");
    FancyText.font(cmdChatBtn);
    cmdChatBtn.setAllCaps(false);
    cmdChatBtn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 38f);
    LinearLayout.LayoutParams tp =
        new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    tabs.addView(cmdWorkBtn, tp);
    tabs.addView(cmdChatBtn, new LinearLayout.LayoutParams(tp));
    card.addView(tabs);
    cmdWorkBtn.setOnClickListener(v -> setCommandChannel("work"));
    cmdChatBtn.setOnClickListener(v -> setCommandChannel("chat"));

    // 输入框
    cmdInput = new android.widget.EditText(this);
    cmdInput.setHint("说点什么…");
    cmdInput.setTextColor(0xFF2B2B3A);
    cmdInput.setHintTextColor(0xFF9A9AAE);
    cmdInput.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 40f);
    cmdInput.setSingleLine(false);
    cmdInput.setFocusable(true);
    cmdInput.setFocusableInTouchMode(true);
    cmdInput.setMaxLines(4);
    cmdInput.setPadding(dp(20), dp(14), dp(20), dp(14));
    android.graphics.drawable.GradientDrawable ibg =
        new android.graphics.drawable.GradientDrawable();
    ibg.setColor(0xFFF2F4FA);
    ibg.setCornerRadius(dp(16));
    cmdInput.setBackground(ibg);
    cmdInput.setOnClickListener(
        v -> {
          try {
            android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager)
                    getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(cmdInput, 0);
          } catch (Throwable ignored) {
          }
        });
    card.addView(cmdInput);

    // 状态行
    cmdStatus = new TextView(this);
    cmdStatus.setTextColor(0xFF7A7A8C);
    cmdStatus.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 32f);
    cmdStatus.setPadding(0, dp(8), 0, 0);
    cmdStatus.setText("发到「聊天」会话");
    card.addView(cmdStatus);

    // 发送
    Button send = new Button(this);
    send.setText("发送");
    send.setTextColor(0xFF2A4C8F);
    FancyText.font(send);
    send.setAllCaps(false);
    send.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 40f);
    send.setOnClickListener(v -> submitCommand());
    card.addView(send);

    // 单向外发：把输入框内容（空则用最近一句话）交给 DeepSeek App
    Button share = new Button(this);
    share.setText("📤 发到 DeepSeek");
    share.setTextColor(0xFF2A4C8F);
    FancyText.font(share);
    share.setAllCaps(false);
    share.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 34f);
    share.setOnClickListener(
        v -> {
          String t = cmdInput == null ? "" : cmdInput.getText().toString().trim();
          if (t.isEmpty() && lastSay != null) t = lastSay;
          if (t.isEmpty()) {
            if (cmdStatus != null) cmdStatus.setText("还没有可以转发的内容");
            return;
          }
          boolean autoSend = DeepSeekShare.autoSendEnabled(PetService.this);
          boolean ok = DeepSeekShare.send(PetService.this, t);
          if (cmdStatus != null)
            cmdStatus.setText(
                ok
                    ? (autoSend ? "已发给 DeepSeek，自动发送中…" : "已发到 DeepSeek（需手点发送）")
                    : "没找到 DeepSeek App");
          if (ok) hideCommandBox();
        });
    card.addView(share);

    cmdParams = new WindowManager.LayoutParams();
    cmdParams.type =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;
    cmdParams.format = PixelFormat.TRANSLUCENT;
    // 必须可获取焦点，否则输入框拿不到软键盘
    cmdParams.flags =
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
    cmdParams.gravity = Gravity.TOP | Gravity.START;
    cmdParams.alpha = 1f;
    cmdParams.width = dp(332);
    cmdParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
    cmdParams.softInputMode =
        android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE;
    setCommandChannel(cmdChannel);
  }

  private void setCommandChannel(String ch) {
    cmdChannel = ch;
    boolean work = "work".equals(ch);
    if (cmdTitle != null) cmdTitle.setText(work ? "安排她干活" : "和鲸鱼娘聊天");
    if (cmdStatus != null) cmdStatus.setText(work ? "发到「工作」会话" : "发到「聊天」会话");
    if (cmdInput != null) cmdInput.setHint(work ? "要我做什么？" : "说点什么…");
    if (cmdWorkBtn != null) cmdWorkBtn.setAlpha(work ? 1f : 0.45f);
    if (cmdChatBtn != null) cmdChatBtn.setAlpha(work ? 0.45f : 1f);
  }

  /** 长按鲸鱼娘时弹出 */
  public void showCommandBox() {
    try {
      buildCommandBox();
      if (cmdRoot.getParent() == null) {   // 以实际挂载状态为准，避免标志位不同步
        // 贴着鲸鱼娘上方显示，屏幕放不下就压回可见区域
        int[] loc = new int[2];
        rootView.getLocationOnScreen(loc);
        int x = loc[0] + (params.width - dp(332)) / 2;
        int y = loc[1] - dp(300);
        int[] scr = screenSize();
        x = Math.max(dp(8), Math.min(scr[0] - dp(332) - dp(8), x));
        if (y < dp(60)) y = loc[1] + params.height - dp(20);
        cmdParams.x = x;
        cmdParams.y = y;
        wm.addView(cmdRoot, cmdParams);
      }
      cmdShown = true;
      cmdInput.requestFocus();
      cmdInput.postDelayed(
          () -> {
            try {
              android.view.inputmethod.InputMethodManager imm =
                  (android.view.inputmethod.InputMethodManager)
                      getSystemService(INPUT_METHOD_SERVICE);
              if (imm != null) {
                imm.showSoftInput(cmdInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
              }
            } catch (Throwable ignored) {
            }
          },
          250);
    } catch (Throwable e) {
      Log.w(TAG, "指令框打开失败", e);
    }
  }

  public void hideCommandBox() {
    if (cmdRoot == null) return;
    try {
      android.view.inputmethod.InputMethodManager imm =
          (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
      if (imm != null) imm.hideSoftInputFromWindow(cmdRoot.getWindowToken(), 0);
    } catch (Throwable ignored) {
    }
    try { if (cmdRoot.getParent() != null) wm.removeView(cmdRoot); } catch (Throwable ignored) {
    }
    cmdShown = false;
  }

  /** 工作指令统一回这句（内容长、气泡装不下，也不需要她去念） */
  private static final String WORK_ACK = "已了解工作内容，正在执行";

  private void submitCommand() {
    final String text = cmdInput == null ? "" : cmdInput.getText().toString().trim();
    if (text.isEmpty()) return;
    final String ch = cmdChannel;
    cmdInput.setText("");
    runCommand(text, ch);
  }

  /** 真正执行一条指令：投递工作台 + 用气泡回报（弹窗和外部 intent 共用） */
  private void runCommand(final String text, final String ch) {
    Log.i(TAG, "runCommand ch=" + ch + " text=" + text);
    if (cmdStatus != null) cmdStatus.setText("发送中…");
    new Thread(
            () -> {
              // 先记下当前最后一条回复，发送后靠它判断有没有新回复。
              // baseline == null 表示工作台没有 /last 接口（旧版 pet-inbox），此时不做轮询。
              final String baseline = "chat".equals(ch) ? fetchLastReply(ch) : null;
              final boolean canPoll = "chat".equals(ch) && baseline != null;
              final String err = postToInbox(ch, text);
              Log.i(TAG, "postToInbox err=" + err);
              if (err != null) {
                // 工作台不在 → 直接问服务商，别把错误甩到气泡上
                final String fb = standaloneAsk(ch, text);
                if (fb != null) {
                  cmdMain.post(
                      () -> {
                        if (cmdStatus != null) cmdStatus.setText("已回复（独立模式）");
                        String plain = stripMarkdown(fb);
                        say(plain.length() > 300 ? plain.substring(0, 300) + "…" : plain);
                      });
                  return;
                }
                cmdMain.post(
                    () -> {
                      if (cmdStatus != null) cmdStatus.setText("发送失败：" + err);
                      say("没有主机，独立模式也没配置…");
                    });
                return;
              }
              if ("work".equals(ch)) {
                // 工作指令：只回一句固定的话
                cmdMain.post(
                    () -> {
                      if (cmdStatus != null) cmdStatus.setText(WORK_ACK);
                      say(WORK_ACK);
                    });
                return;
              }
              if (!canPoll) {
                // 工作台没提供回复接口：只确认已送达
                cmdMain.post(
                    () -> {
                      if (cmdStatus != null) cmdStatus.setText("已发到「聊天」");
                      say("已发到工作台");
                    });
                return;
              }
              // 聊天指令：把工作台的回复念出来
              cmdMain.post(
                  () -> {
                    if (cmdStatus != null) cmdStatus.setText("已发送，等回复…");
                  });
              for (int i = 0; i < 30; i++) {
                try {
                  Thread.sleep(2000);
                } catch (InterruptedException e) {
                  return;
                }
                final String t = fetchLastReply(ch);
                if (t == null || t.isEmpty() || t.equals(baseline)) continue;
                String plain = stripMarkdown(t);
                final String show =
                    plain.length() > 300 ? plain.substring(0, 300) + "…" : plain;
                cmdMain.post(
                    () -> {
                      if (cmdStatus != null) cmdStatus.setText("已回复");
                      say(show);
                    });
                return;
              }
              cmdMain.post(
                  () -> {
                    if (cmdStatus != null) cmdStatus.setText("已发送（等回复超时）");
                  });
            })
        .start();
  }

  /** 把 Markdown 痕迹去掉，让气泡里读起来像人话 */
  private static String stripMarkdown(String in) {
    if (in == null) return "";
    String t = in;
    t = t.replaceAll("```[\\s\\S]*?```", " ");      // 代码块
    t = t.replaceAll("`([^`]*)`", "$1");               // 行内代码
    t = t.replaceAll("\\*\\*([^*]*)\\*\\*", "$1");        // 加粗
    t = t.replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "");      // 标题
    t = t.replaceAll("(?m)^\\s*[-*+]\\s+", "· ");       // 列表
    t = t.replaceAll("(?m)^\\s*\\|?\\s*:?-{2,}.*$", ""); // 表格分隔行
    t = t.replaceAll("\\|", " ");                     // 表格竖线
    t = t.replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1");  // 链接
    t = t.replaceAll("\\n{3,}", "\\n\\n");
    return t.trim();
  }

  // ---------- 与工作台的绑定 ----------

  private void bindInbox() {
    new Thread(
            () -> {
              try {
                String token = getSharedPreferences("pet", MODE_PRIVATE).getString("token", "");
                HttpURLConnection c =
                    (HttpURLConnection)
                        new java.net.URL(inboxUrl("/bind")).openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(3000);
                c.setReadTimeout(6000);
                c.setDoOutput(true);
                c.setRequestProperty("content-type", "application/json");
                if (!token.isEmpty()) c.setRequestProperty("x-pet-token", token);
                c.setRequestProperty("x-pet-device", android.os.Build.MODEL == null ? "phone" : android.os.Build.MODEL);
                byte[] out = "{}".getBytes("UTF-8");
                c.setFixedLengthStreamingMode(out.length);
                java.io.OutputStream os = c.getOutputStream();
                os.write(out);
                os.close();
                if (c.getResponseCode() == 200) {
                  java.io.InputStream in = c.getInputStream();
                  java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                  byte[] b = new byte[2048];
                  int n;
                  while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                  in.close();
                  String body = new String(bo.toByteArray(), "UTF-8");
                  String t = jsonStr(body, "token");
                  if (t != null && !t.isEmpty()) {
                    getSharedPreferences("pet", MODE_PRIVATE).edit().putString("token", t).apply();
                    Log.i(TAG, "已绑定工作台");
                  }
                }
                c.disconnect();
              } catch (Throwable e) {
                Log.w(TAG, "绑定工作台失败", e);
              }
            })
        .start();
  }

  private String petToken() {
    return getSharedPreferences("pet", MODE_PRIVATE).getString("token", "");
  }

  /** 带绑定令牌的请求头 */
  private void applyAuth(HttpURLConnection c) {
    String t = petToken();
    if (t != null && !t.isEmpty()) c.setRequestProperty("x-pet-token", t);
  }

  /** 简单 GET，返回响应体；失败返回 null */
  private String httpGet(String url, int readTimeoutMs) {
    HttpURLConnection c = null;
    try {
      c = (HttpURLConnection) new java.net.URL(url).openConnection();
      c.setRequestMethod("GET");
      applyAuth(c);
      c.setConnectTimeout(3000);
      c.setReadTimeout(readTimeoutMs);
      int code = c.getResponseCode();
      if (code != 200) {
        Log.w(TAG, "standaloneAsk HTTP " + code);
        try { java.io.InputStream es = c.getErrorStream(); if (es != null) { java.io.ByteArrayOutputStream eb = new java.io.ByteArrayOutputStream(); byte[] tb = new byte[4096]; int tn; while ((tn = es.read(tb)) > 0) eb.write(tb, 0, tn); es.close(); Log.w(TAG, "err=" + new String(eb.toByteArray(), "UTF-8")); } } catch (Throwable ignored) {}
        return null;
      }
      java.io.InputStream in = c.getInputStream();
      java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
      byte[] b = new byte[4096];
      int n;
      while ((n = in.read(b)) > 0) bo.write(b, 0, n);
      in.close();
      return new String(bo.toByteArray(), "UTF-8");
    } catch (Throwable e) {
      return null;
    } finally {
      if (c != null) c.disconnect();
    }
  }

  /** 从 JSON 里抠一个字符串字段（够用即可，不引 JSON 库） */
  private static String jsonStr(String json, String key) {
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
        if (nx == 'n') sb.append('\n');
        else if (nx == 't') sb.append(' ');
        else sb.append(nx);
      } else {
        sb.append(ch);
      }
      k++;
    }
    return sb.toString().trim();
  }

  // ---------- 独立模式：不依赖 DSH，直接请求服务商 ----------

  /** 工作台连接地址（可在设置页改，换一套 DSH 只改配置不用重编） */
  private String inboxBase() {
    String host = pref("inbox_host", "127.0.0.1");
    String port = pref("inbox_port", "3081");
    if (host.isEmpty()) host = "127.0.0.1";
    if (port.isEmpty()) port = "3081";
    return "http://" + host + ":" + port;
  }

  private String inboxUrl(String path) {
    return inboxBase() + path;
  }

  /** 要唤起的工作台应用（包名/Activity 可配） */
  private String harnessPkg() {
    String v = pref("harness_pkg", "dev.dsh.android");
    return v.isEmpty() ? "dev.dsh.android" : v;
  }

  private String harnessAct() {
    String v = pref("harness_act", "dev.dsh.android.MainActivity");
    if (v.isEmpty()) return harnessPkg() + ".MainActivity";
    return v;
  }

  private String pref(String k, String def) {
    String v = getSharedPreferences("pet", MODE_PRIVATE).getString(k, def);
    return v == null ? def : v;
  }

  /** 从工作台拉一次服务商配置并缓存（之后脱离 DSH 也能用） */
  private void syncProviderFromDsh() {
    new Thread(
            () -> {
              String body = httpGet(inboxUrl("/provider"), 10000);
              if (body == null || !body.contains("\"ok\":true")) return;
              String base = jsonStr(body, "baseURL");
              String key = jsonStr(body, "key");
              String model = jsonStr(body, "model");
              android.content.SharedPreferences.Editor e =
                  getSharedPreferences("pet", MODE_PRIVATE).edit();
              if (base != null && !base.isEmpty()) e.putString("durl", base);
              if (key != null && !key.isEmpty()) e.putString("dkey", key);
              if (model != null && !model.isEmpty()) e.putString("dmodel", model);
              e.apply();
              Log.i(TAG, "已缓存服务商配置（独立模式可用）");
            })
        .start();
  }

  /** 独立模式：直接调 OpenAI 兼容 chat 接口生成一句碎碎念 */
  private String standaloneWhisper() {
    String base = pref("durl", "");
    String key = pref("dkey", "");
    String model = pref("dmodel", "");
    if (base.isEmpty() || key.isEmpty()) return null;
    String sys = "你是主人桌面上的Q版蓝发小女仆，会时不时碎碎念一句。说话要自然随意、短短一句（20字以内），温柔乖巧带点俏皮，说人话不啰嗦，不要解释你自己，不要提你是AI。";
    String req =
        "{\"model\":\""
            + model.replace("\"", "")
            + "\",\"temperature\":1,"
            + "\"messages\":[{\"role\":\"system\",\"content\":\""
            + esc(sys)
            + "\"},"
            + "{\"role\":\"user\",\"content\":\"（随口碎碎念一句，20 字以内，不要解释）\"}]}";
    try {
      String resp = postChatJson(base, key, req);
      if (resp == null) {
        // 直连失败：若配的是本机中转地址，多半是离开 DSH 了，给引导而不是闷声失败
        if (isLoopbackBase(base)) {
          Log.w(TAG, "独立模式地址是本机 " + base + "，给引导语");
          return offlineSetupHint(base);
        }
        return null;
      }
      String text = jsonStr(resp, "content");
      return (text == null || text.isEmpty()) ? null : text;
    } catch (Throwable e) {
      return null;
    }
  }

  /**
   * 独立模式：工作台不在时直接问服务商，返回回复文本（失败返回 null）。
   * ch=chat 走闲聊口吻，ch=work 走正经口吻；上下文存在 prefs 的 ctx_<ch> 里。
   */
  private String standaloneAsk(String ch, String text) {
    String base = pref("durl", "");
    String key = pref("dkey", "");
    String model = pref("dmodel", "");
    Log.i(TAG, "standaloneAsk base=" + (base.isEmpty() ? "<空>" : base)
        + " key=" + (key.isEmpty() ? "<空>" : key.length() + "字符")
        + " model=" + model + " ctxLen=" + pref("ctx_" + ch, "").length());
    if (base.isEmpty() || key.isEmpty()) {
      Log.w(TAG, "独立模式未配置，给引导语");
      return offlineSetupHint(base);
    }
    if (model.isEmpty()) model = "deepseek-chat";
    String persona =
        "你是主人手机桌面上的 Q 版蓝发小女仆，名字叫鲸鱼娘。"
            + "说话自然、简短、温柔带点俏皮，不要自称 AI，不要用 Markdown、标题、表格、代码块。"
            + ("work".equals(ch)
                ? "主人问的是正经问题，可以说得详细些、条理清楚，但不许假装自己执行过命令。"
                : "像平常聊天那样，尽量 60 字以内。");
    String env =
        "你现在处在独立模式：桌宠没有连上主人的主机，所以没有命令执行、文件读写、联网搜索能力。"
            + "主人让你动手做事时，坦白说这条需要主机在线才能做，再给出建议或代替方案，不要编造执行结果。";
    String ctx = pref("ctx_" + ch, "");
    StringBuilder msgs = new StringBuilder();
    msgs.append("{\"role\":\"system\",\"content\":\"").append(esc(persona + env)).append("\"}");
    if (!ctx.isEmpty()) {
      for (String line : ctx.split("\n")) {
        if (line.startsWith("U:")) {
          msgs.append(",{\"role\":\"user\",\"content\":\"").append(esc(line.substring(2))).append("\"}");
        } else if (line.startsWith("A:")) {
          msgs.append(",{\"role\":\"assistant\",\"content\":\"").append(esc(line.substring(2))).append("\"}");
        }
      }
    }
    msgs.append(",{\"role\":\"user\",\"content\":\"").append(esc(text)).append("\"}");
    String req =
        "{\"model\":\""
            + model.replace("\"", "")
            + "\",\"temperature\":0.8,\"max_tokens\":800,\"messages\":["
            + msgs
            + "]}";
    try {
      String resp = postChatJson(base, key, req);
      if (resp == null) {
        // 直连失败：若配的是本机中转地址，多半是离开 DSH 了，给引导而不是闷声失败
        if (isLoopbackBase(base)) {
          Log.w(TAG, "独立模式地址是本机 " + base + "，给引导语");
          return offlineSetupHint(base);
        }
        return null;
      }
      String reply = jsonStr(resp, "content");
      if (reply == null || reply.isEmpty()) return null;
      reply = reply.trim();
      // 记上下文，供下一轮用
      String u = text.replace('\n', ' ').replace('\r', ' ');
      String a = reply.replace('\n', ' ').replace('\r', ' ');
      if (u.length() > 600) u = u.substring(0, 600);
      if (a.length() > 600) a = a.substring(0, 600);
      String s = ctx + "U:" + u + "\nA:" + a + "\n";
      if (s.length() > 3000) s = s.substring(s.length() - 3000);
      int nl2 = s.indexOf('\n');
      if (nl2 >= 0) s = s.substring(nl2 + 1);
      getSharedPreferences("pet", MODE_PRIVATE).edit().putString("ctx_" + ch, s).apply();
      Log.i(TAG, "standaloneAsk 成功，回复 " + reply.length() + " 字");
      return reply;
    } catch (Throwable e) {
      Log.w(TAG, "standaloneAsk 异常: " + e);
      return null;
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

  /**
   * POST 一个 chat/completions 请求。base 可能带 /v1 也可能不带（例如 https://api.deepseek.com），
   * 先试 base 原样，404 再补 /v1 重试一次。返回响应体，失败 null。
   */
  private String postChatJson(String base, String key, String req) {
    String b = base.replaceAll("/+$", "");
    java.util.List<String> urls = new java.util.ArrayList<String>();
    urls.add(b + "/chat/completions");
    if (!b.endsWith("/v1")) urls.add(b + "/v1/chat/completions");
    for (int i = 0; i < urls.size(); i++) {
      String url = urls.get(i);
      HttpURLConnection c = null;
      try {
        c = (HttpURLConnection) new java.net.URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(8000);
        c.setReadTimeout(90000);
        c.setDoOutput(true);
        c.setRequestProperty("Authorization", "Bearer " + key);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] out = req.getBytes("UTF-8");
        c.setFixedLengthStreamingMode(out.length);
        java.io.OutputStream os = c.getOutputStream();
        os.write(out);
        os.close();
        int code = c.getResponseCode();
        Log.i(TAG, "POST " + url + " -> " + code);
        if (code == 404 && i < urls.size() - 1) continue;
        if (code != 200) {
          try {
            java.io.InputStream es = c.getErrorStream();
            if (es != null) {
              java.io.ByteArrayOutputStream eb = new java.io.ByteArrayOutputStream();
              byte[] tb = new byte[4096];
              int tn;
              while ((tn = es.read(tb)) > 0) eb.write(tb, 0, tn);
              es.close();
              Log.w(TAG, "err=" + new String(eb.toByteArray(), "UTF-8"));
            }
          } catch (Throwable ignored) {
          }
          return null;
        }
        java.io.InputStream in = c.getInputStream();
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return new String(bo.toByteArray(), "UTF-8");
      } catch (Throwable e) {
        Log.w(TAG, "POST " + url + " 异常: " + e);
        return null;
      } finally {
        if (c != null) c.disconnect();
      }
    }
    return null;
  }

  /** 独立模式：直接查服务商余额。DeepSeek 优先官方 /user/balance（真·剩余），挂了回落 usage（注意其 total_usage 对 DeepSeek 是人民币分） */
  private String standaloneBalance() {
    String base = pref("durl", "");
    String key = pref("dkey", "");
    if (base.isEmpty() || key.isEmpty()) return null;
    String root = base.replaceAll("/+$", "").replaceAll("/v1$", "");
    boolean isDeepseek = root.toLowerCase().contains("deepseek.com");
    if (isDeepseek) {
      String official = fetchBalanceOfficial(root + "/user/balance", key);
      if (official != null) return official;
    }
    try {
      HttpURLConnection c =
          (HttpURLConnection)
              new java.net.URL(root + "/v1/dashboard/billing/usage?start_date=2026-01-01&end_date=2030-01-01")
                  .openConnection();
      c.setRequestMethod("GET");
      c.setRequestProperty("Authorization", "Bearer " + key);
      c.setConnectTimeout(5000);
      c.setReadTimeout(12000);
      if (c.getResponseCode() != 200) {
        c.disconnect();
        return null;
      }
      java.io.InputStream in = c.getInputStream();
      java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
      byte[] b = new byte[4096];
      int n;
      while ((n = in.read(b)) > 0) bo.write(b, 0, n);
      in.close();
      c.disconnect();
      String resp = new String(bo.toByteArray(), "UTF-8");
      double used = jsonNum(resp, "total_usage") / 100.0;
      // DeepSeek 的 usage 是人民币口径，别再标成 $（2026-10-19 修正）
      String sym = isDeepseek ? "¥" : "$";
      return "本期已用 " + sym + String.format(java.util.Locale.US, "%.2f", used);
    } catch (Throwable e) {
      return null;
    }
  }

  /** 查官方 /user/balance，挑条规则同 peak-chip：优先有余额的条目，同有余额优先 CNY（多币种返回顺序不稳定）。失败返回 null */
  private String fetchBalanceOfficial(String url, String key) {
    HttpURLConnection c = null;
    try {
      c = (HttpURLConnection) new java.net.URL(url).openConnection();
      c.setRequestMethod("GET");
      c.setRequestProperty("Authorization", "Bearer " + key);
      c.setConnectTimeout(5000);
      c.setReadTimeout(12000);
      int code = c.getResponseCode();
      if (code != 200) {
        Log.w(TAG, "standaloneAsk HTTP " + code);
        try { java.io.InputStream es = c.getErrorStream(); if (es != null) { java.io.ByteArrayOutputStream eb = new java.io.ByteArrayOutputStream(); byte[] tb = new byte[4096]; int tn; while ((tn = es.read(tb)) > 0) eb.write(tb, 0, tn); es.close(); Log.w(TAG, "err=" + new String(eb.toByteArray(), "UTF-8")); } } catch (Throwable ignored) {}
        return null;
      }
      java.io.InputStream in = c.getInputStream();
      java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
      byte[] buf = new byte[4096];
      int n;
      while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
      in.close();
      String resp = new String(bo.toByteArray(), "UTF-8");
      // balance_infos 数组里逐条抠 total_balance + currency，按挑条规则选
      double best = -1;
      String bestCur = null;
      int i = 0;
      while ((i = resp.indexOf("total_balance", i)) >= 0) {
        double v = jsonNum(resp.substring(i), "total_balance");
        // 币种只在本条目花括号内找，避免跨条目错配
        int lo = resp.lastIndexOf('{', i), hi = resp.indexOf('}', i);
        String cur = lo >= 0 && hi > lo ? jsonStr(resp.substring(lo, hi), "currency") : null;
        boolean okEntry = v > 0, isCny = cur != null && cur.toUpperCase().contains("CNY");
        if (okEntry && (best < 0 || (isCny && !(bestCur != null && bestCur.toUpperCase().contains("CNY"))))) {
          best = v;
          bestCur = cur;
        }
        i += 13;
      }
      if (best < 0) return null; // 全 0 或没抠到 → 让上层走 billing 兜底
      String cur = bestCur == null || bestCur.isEmpty() ? "CNY" : bestCur;
      String sym = cur.toUpperCase().contains("USD") ? "$" : cur.toUpperCase().contains("EUR") ? "€" : "¥";
      return "还剩 " + sym + String.format(java.util.Locale.US, "%.2f", best);
    } catch (Throwable e) {
      return null;
    } finally {
      if (c != null) c.disconnect();
    }
  }

  /** JSON 字符串转义 */
  private static String esc(String s) {
    if (s == null) return "";
    return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
  }

  /** 从 JSON 里抠数字字段 */
  private static double jsonNum(String json, String key) {
    if (json == null) return 0;
    int i = json.indexOf("\"" + key + "\":");
    if (i < 0) return 0;
    int k = i + key.length() + 3;
    StringBuilder sb = new StringBuilder();
    while (k < json.length()) {
      char ch = json.charAt(k);
      if ((ch >= '0' && ch <= '9') || ch == '.' || ch == '-' || ch == 'e' || ch == 'E') {
        sb.append(ch);
      } else if (sb.length() > 0) {
        break;
      }
      k++;
    }
    try {
      return Double.parseDouble(sb.toString());
    } catch (Throwable e) {
      return 0;
    }
  }

  /** 说一句并配指定表情包（名字来自工作台返回的 image 字段） */
  private void sayWithNamedMeme(final String text, final String memeName) {
    if (memeName != null && !memeName.isEmpty()) {
      // 解码 + 缩放全在后台线程做，主线程只负责上屏
      showMemeAsync(memeName, text);
      return;
    }
    // 没有配图：只说文字
    main.post(
        () -> {
          if (bubble == null) return;
          bubble.setText(text);
          hideMemeWindow();
          showBubbleWindow();
          main.removeCallbacks(hideBubble);
          int ms = 3500 + (text == null ? 0 : text.length()) * 45;
          main.postDelayed(hideBubble, Math.min(ms, 18000));
        });
  }

  /** 取工作台里某通道最后一条回复（需要 pet-inbox 的 /last 接口） */
  private String fetchLastReply(String channel) {
    HttpURLConnection c = null;
    try {
      c = (HttpURLConnection)
              new java.net.URL(inboxUrl("/last?channel=" + channel)).openConnection();
      c.setRequestMethod("GET");
      applyAuth(c);
      c.setConnectTimeout(2500);
      c.setReadTimeout(4000);
      int code = c.getResponseCode();
      if (code != 200) return null;
      java.io.InputStream in = c.getInputStream();
      java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
      byte[] b = new byte[4096];
      int n;
      while ((n = in.read(b)) > 0) bo.write(b, 0, n);
      in.close();
      String json = new String(bo.toByteArray(), "UTF-8");
      int i = json.indexOf("\"text\":\"");
      if (i < 0) return null;
      int startIdx = i + 8;
      StringBuilder sb = new StringBuilder();
      for (int k = startIdx; k < json.length(); k++) {
        char cch = json.charAt(k);
        if (cch == '"') break;
        if (cch == '\\' && k + 1 < json.length()) {
          char nx = json.charAt(++k);
          if (nx == 'n') sb.append('\n');
          else if (nx == 't') sb.append(' ');
          else sb.append(nx);
        } else {
          sb.append(cch);
        }
      }
      return sb.toString().trim();
    } catch (Throwable e) {
      return null;
    } finally {
      if (c != null) c.disconnect();
    }
  }

  /** 投递到 DSH 的宠物收件口（和 CommandActivity 同一接口） */
  private String postToInbox(String channel, String text) {
    HttpURLConnection c = null;
    try {
      String body =
          "{\"channel\":\""
              + channel
              + "\",\"text\":\""
              + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
              + "\"}";
      c = (HttpURLConnection) new java.net.URL(inboxUrl("/send")).openConnection();
      c.setRequestMethod("POST");
      c.setConnectTimeout(4000);
      c.setReadTimeout(8000);
      c.setDoOutput(true);
      applyAuth(c);
      c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
      byte[] out = body.getBytes("UTF-8");
      c.setFixedLengthStreamingMode(out.length);
      java.io.OutputStream os = c.getOutputStream();
      os.write(out);
      os.close();
      int code = c.getResponseCode();
      // 读响应体：服务端失败也走 HTTP 200（{ok:false}），不能只看状态码
      java.io.InputStream in =
          code >= 400 ? c.getErrorStream() : c.getInputStream();
      String resp = "";
      if (in != null) {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[2048];
        int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        in.close();
        resp = new String(bo.toByteArray(), "UTF-8");
      }
      if (code >= 200 && code < 300 && !resp.contains("\"ok\":false")) return null;
      String reason = jsonStr(resp, "reason");
      if (reason == null) reason = jsonStr(resp, "error");
      return "HTTP " + code + (reason == null ? "" : " " + reason);
    } catch (Throwable e) {
      return e.getMessage() == null ? e.toString() : e.getMessage();
    } finally {
      if (c != null) c.disconnect();
    }
  }

  /** 立刻收起气泡和配图（点一下就消失） */
  private void hideBubbleNow() {
    main.removeCallbacks(hideBubble);
    try { hideBubbleWindow(); } catch (Throwable ignored) {
    }
    try { hideMemeWindow(); } catch (Throwable ignored) {
    }
    bubbleShown = false;
    memeShown = false;
  }

  /** 说一句话，并带一张表情包配图：文字在她左上角，图片在她正上方 */
  private void sayWithMeme(final String text, final boolean withImage) {
    if (!withImage || memePool.isEmpty()) {
      say(text);
      return;
    }
    final String name = memePool.get(rng.nextInt(memePool.size()));
    // 解码 + 缩放放到后台，主线程只上屏（原来在主线程做，每 10 秒卡一下）
    showMemeAsync(name, text);
    main.removeCallbacks(hideBubble);
    int ms = 3500 + (text == null ? 0 : text.length()) * 45;
    main.postDelayed(hideBubble, Math.min(ms, 18000));
  }

  /** 最近一次说出口的话，供「发到 DeepSeek」转发用 */
  private volatile String lastSay = null;

  public void say(final String text) {
    if (text != null && !text.trim().isEmpty()) lastSay = text.trim();
    main.post(
        new Runnable() {
          @Override
          public void run() {
            if (bubble == null) return;
            bubble.setText(text);
            hideMemeWindow();          // 单纯说话不带图
            showBubbleWindow();
            main.removeCallbacks(hideBubble);
            // 字多就多留一会儿，最长 18 秒
            int ms = 3500 + (text == null ? 0 : text.length()) * 45;
            main.postDelayed(hideBubble, Math.min(ms, 18000));
          }
        });
  }

  private final Runnable hideBubble =
      new Runnable() {
        @Override
        public void run() {
          main.post(
              new Runnable() {
                @Override
                public void run() {
                  hideBubbleWindow();
                  hideMemeWindow();
                }
              });
        }
      };

  public void playClip(String name) {
    play(name, false, 0);
  }

  private int dp(int v) {
    return Math.round(v * getResources().getDisplayMetrics().density);
  }

  @Override
  public void onDestroy() {
    alive = false;
    main.removeCallbacks(hideBubble);
    if (prefListener != null) {
      try {
        getSharedPreferences("pet", MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefListener);
      } catch (Throwable ignored) {
      }
      prefListener = null;
    }
    if (moveAnimator != null) {
      moveAnimator.cancel();
      moveAnimator = null;
    }
    try {
      if (glView != null) glView.release();
      if (rootView != null && wm != null) wm.removeView(rootView);
    } catch (Throwable ignored) {
    }
    super.onDestroy();
  }
}
