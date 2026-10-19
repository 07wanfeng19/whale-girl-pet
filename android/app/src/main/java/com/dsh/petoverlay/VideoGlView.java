package com.dsh.petoverlay;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * 用 OpenGL ES 渲染视频，并在片元着色器里把纯黑抠成透明。
 *
 * 这是 Android 上让视频真正透明的唯一可靠方式：
 *   - SurfaceView / TextureView 都没法做逐像素 alpha
 *   - GLSurfaceView 设 setZOrderOnTop(true) + EGL 配置带 ALPHA_SIZE，
 *     片元着色器直接输出 alpha=0 的像素，合成时就是透明的
 *
 * 抠黑算法：alpha = clamp(1.25 * 亮度 - 0.10, 0, 1)
 * 纯黑(亮度0) → alpha=0 全透明；亮部 → alpha=1 不透明。
 */
public class VideoGlView extends GLSurfaceView implements GLSurfaceView.Renderer {
  private static final String TAG = "VideoGlView";

  private static final String VS =
      "attribute vec4 aPos;\n"
    + "attribute vec2 aTex;\n"
    + "varying vec2 vTex;\n"
    + "uniform float uRot;\n"          // 侧倾角（弧度），绕可见区域中心转
    + "uniform vec2 uPivot;\n"         // 旋转中心（clip 空间）
    + "uniform float uAspect;\n"       // 视频宽高比，用来把 clip 空间换成等比空间
    + "uniform vec2 uOff;\n"           // 视线偏移（clip 空间）
    + "void main(){\n"
    + "  vec2 p = aPos.xy - uPivot;\n"
    + "  p.y /= uAspect;\n"             // 先等比，旋转才不会被拉斜
    + "  float c = cos(uRot), s = sin(uRot);\n"
    + "  vec2 r = vec2(c*p.x - s*p.y, s*p.x + c*p.y);\n"
    + "  r.y *= uAspect;\n"             // 转完换回 clip 空间
    + "  gl_Position = vec4(r + uPivot + uOff, aPos.zw);\n"
    + "  vTex = aTex;\n"
    + "}\n";

  // 核心：把黑色变透明，并削掉原画自带的深色描边（1px 腐蚀）
  private static final String FS =
      "#extension GL_OES_EGL_image_external : require\n"
    + "precision mediump float;\n"
    + "varying vec2 vTex;\n"
    + "uniform samplerExternalOES uTex;\n"
    + "uniform vec2 uTexel;\n"
    + "uniform float uAlpha;\n"                 // 整体不透明度
    + "float key(vec2 uv){\n"
    + "  vec4 c = texture2D(uTex, uv);\n"
    + "  float mx = max(max(c.r, c.g), c.b);\n"
    + "  return smoothstep(0.02, 0.09, mx);\n"  // 纯黑→0，人物→1
    + "}\n"
    + "void main(){\n"
    + "  vec4 c = texture2D(uTex, vTex);\n"
    + "  float a = key(vTex);\n"
    + "  // 1px 腐蚀：把外圈那层深色描边削掉，边缘才干净\n"
    + "  a = min(a, key(vTex + vec2(uTexel.x, 0.0)));\n"
    + "  a = min(a, key(vTex - vec2(uTexel.x, 0.0)));\n"
    + "  a = min(a, key(vTex + vec2(0.0, uTexel.y)));\n"
    + "  a = min(a, key(vTex - vec2(0.0, uTexel.y)));\n"
    + "  gl_FragColor = vec4(c.rgb, a * uAlpha);\n"
    + "}\n";

  private int program = 0;
  private int texId = 0;
  private int aPosLoc, aTexLoc, uTexLoc, uTexelLoc, uAlphaLoc;
  private int uRotLoc, uPivotLoc, uAspectLoc, uOffLoc;

  // ---- 侧倾 / 视线：目标值在主线程写，当前值在 GL 线程逐帧逼近（lerp） ----
  private volatile float leanTarget = 0f;                 // 弧度
  private volatile float gazeXTarget = 0f, gazeYTarget = 0f;   // clip 空间
  private float leanCur = 0f, gazeXCur = 0f, gazeYCur = 0f;
  /** 当前渲染模式。动画中切连续渲染（跟上手指），静止时退回按需渲染（省电） */
  private int renderModeNow = RENDERMODE_WHEN_DIRTY;
  private volatile float videoW = 640f, videoH = 360f;
  private volatile float pivotU = 0.5f, pivotV = 0.5f;
  private float aspect = 640f / 360f;
  /** 角色整体不透明度（用户可调，1=完全不透明） */
  private volatile float opacity = 1f;
  private FloatBuffer posBuf, texBuf;
  private SurfaceTexture surfaceTexture;
  private Surface surface;
  private volatile boolean frameAvailable = false;
  /** 最近一次消费掉的帧时间戳（GL 线程专用）。用来判断是否取到了新帧 */
  private long lastFrameTs = 0L;
  /** 最后一帧到达时间（elapsedRealtime）。看门狗用它判断画面是不是停了 */
  private volatile long lastFrameAt = 0L;
  private volatile boolean released = false;
  private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());

  // 看门狗在主线程读这几个字段，GL 线程写，所以都要 volatile
  private volatile MediaPlayer player;
  private String pendingClip;
  private boolean pendingLoop = true;
  private volatile String currentClip;
  private volatile boolean returnToIdle = true;
  private Context ctx;

  public interface OnClipEnd { void onEnd(); }
  private OnClipEnd onClipEnd;
  public void setOnClipEnd(OnClipEnd cb) { onClipEnd = cb; }
  public String currentClip() { return currentClip; }

  public VideoGlView(Context c) {
    super(c);
    this.ctx = c;
    setEGLContextClientVersion(2);
    // 关键：EGL 配置必须带 ALPHA_SIZE=8，否则窗口没有 alpha 通道
    setEGLConfigChooser(8, 8, 8, 8, 16, 0);
    setZOrderOnTop(true);
    getHolder().setFormat(android.graphics.PixelFormat.TRANSLUCENT);
    setRenderer(this);
    setRenderMode(RENDERMODE_WHEN_DIRTY);
    ui.postDelayed(watchdog, 4000);
  }

  @Override
  public void onSurfaceCreated(GL10 gl, EGLConfig config) {
    GLES20.glClearColor(0f, 0f, 0f, 0f);
    GLES20.glDisable(GLES20.GL_DEPTH_TEST);
    GLES20.glEnable(GLES20.GL_BLEND);
    GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

    program = build(VS, FS);
    aPosLoc = GLES20.glGetAttribLocation(program, "aPos");
    aTexLoc = GLES20.glGetAttribLocation(program, "aTex");
    uTexLoc = GLES20.glGetUniformLocation(program, "uTex");
    uTexelLoc = GLES20.glGetUniformLocation(program, "uTexel");
    uAlphaLoc = GLES20.glGetUniformLocation(program, "uAlpha");
    uRotLoc = GLES20.glGetUniformLocation(program, "uRot");
    uPivotLoc = GLES20.glGetUniformLocation(program, "uPivot");
    uAspectLoc = GLES20.glGetUniformLocation(program, "uAspect");
    uOffLoc = GLES20.glGetUniformLocation(program, "uOff");

    int[] t = new int[1];
    GLES20.glGenTextures(1, t, 0);
    texId = t[0];
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

    surfaceTexture = new SurfaceTexture(texId);
    lastFrameTs = 0L;   // 新队列的时间戳从头算
    surfaceTexture.setOnFrameAvailableListener(st -> {
      frameAvailable = true;
      lastFrameAt = android.os.SystemClock.elapsedRealtime();   // 有帧 = 还活着
      requestRender();
    });
    surface = new Surface(surfaceTexture);

    // 顶点：全屏方块
    float[] p = {-1f,-1f, 1f,-1f, -1f,1f, 1f,1f};
    posBuf = ByteBuffer.allocateDirect(p.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    posBuf.put(p).position(0);
    // 纹理坐标：视频上下和 GL 相反，翻转 y
    float[] t2 = {0f,1f, 1f,1f, 0f,0f, 1f,0f};
    texBuf = ByteBuffer.allocateDirect(t2.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    texBuf.put(t2).position(0);

    if (pendingClip != null) {
      String c = pendingClip;
      boolean l = pendingLoop;
      pendingClip = null;
      startPlay(c, l);
    } else if (currentClip != null && player != null) {
      // Surface 被系统重建了（息屏再亮、切后台回来、窗口重建都会走到这里）。
      // 旧 player 还绑着已经销毁的旧 Surface，新帧全进黑洞——画面永远停在最后一帧，
      // 而且不会有任何回调。必须在这里重开一次，把新 Surface 接上。
      Log.i(TAG, "Surface 重建，重新接上播放 " + currentClip);
      startPlay(currentClip, !returnToIdle);
    }
  }

  @Override public void onSurfaceChanged(GL10 gl, int w, int h) {
    Log.i(TAG, "onSurfaceChanged w=" + w + " h=" + h);
    GLES20.glViewport(0, 0, w, h);
  }

  @Override
  public void onDrawFrame(GL10 gl) {
    GLES20.glClearColor(0f, 0f, 0f, 0f);   // 全透明清屏，杜绝黑底
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
    if (surfaceTexture != null) {
      // 帧回调是布尔标志，回调连来三次也只记一次：队列里会压着没人认领的帧——
      // 时间戳还在推进，却不再有新回调（回调只在"新帧入队"时发）→ 没人排渲染
      // → 积压永远取不走 → 解码器被背压卡死，画面永久冻结，重开播放器也救不活。
      // 所以这里不看标志：每轮渲染必取一次；取到新帧（时间戳变了）就再排一轮，
      // 直到队列取干（时间戳不再变）为止。空队列的 updateTexImage 是无操作，不阻塞。
      long prevTs = lastFrameTs;
      try {
        surfaceTexture.updateTexImage();
        long ts = surfaceTexture.getTimestamp();
        if (ts != prevTs) {
          lastFrameTs = ts;
          lastFrameAt = android.os.SystemClock.elapsedRealtime();
          frameAvailable = false;
          if (renderModeNow == RENDERMODE_WHEN_DIRTY) requestRender();   // 可能还有积压，继续取
        }
      } catch (Throwable ignored) {
      }
    }
    GLES20.glUseProgram(program);
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
    GLES20.glUniform1i(uTexLoc, 0);
    // 视频是 640x360，腐蚀步长按它的纹素来
    GLES20.glUniform2f(uTexelLoc, 1.0f / 640.0f, 1.0f / 360.0f);
    GLES20.glEnableVertexAttribArray(aPosLoc);
    GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 0, posBuf);
    GLES20.glEnableVertexAttribArray(aTexLoc);
    GLES20.glVertexAttribPointer(aTexLoc, 2, GLES20.GL_FLOAT, false, 0, texBuf);
    GLES20.glUniform1f(uAlphaLoc, opacity);

    // ---- 侧倾 / 视线：逐帧逼近目标值，动作才是平滑的（原版 lerp 手感） ----
    leanCur += (leanTarget - leanCur) * 0.35f;
    gazeXCur += (gazeXTarget - gazeXCur) * 0.18f;
    gazeYCur += (gazeYTarget - gazeYCur) * 0.18f;
    boolean moving =
        Math.abs(leanTarget - leanCur) > 0.0004f
            || Math.abs(gazeXTarget - gazeXCur) > 0.0004f
            || Math.abs(gazeYTarget - gazeYCur) > 0.0004f;
    GLES20.glUniform1f(uRotLoc, leanCur);
    GLES20.glUniform2f(uPivotLoc, pivotU * 2f - 1f, 1f - pivotV * 2f);
    GLES20.glUniform1f(uAspectLoc, aspect);
    GLES20.glUniform2f(uOffLoc, gazeXCur, gazeYCur);

    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    GLES20.glDisableVertexAttribArray(aPosLoc);
    GLES20.glDisableVertexAttribArray(aTexLoc);

    // 侧倾/视线没到位：切换到连续渲染，别跟着 24fps 的视频帧走。
    // 拖拽时窗口位置每收到一个触摸事件就变（可达 120Hz），姿态却只有 24fps 才刷新
    // —— 两者不同步就是拖拽顿挫感的来源。连续渲染期间提高到屏幕刷新率，姿态才跟得上手。
    if (moving && renderModeNow != RENDERMODE_CONTINUOUSLY) {
      renderModeNow = RENDERMODE_CONTINUOUSLY;
      setRenderMode(RENDERMODE_CONTINUOUSLY);
    } else if (!moving && renderModeNow != RENDERMODE_WHEN_DIRTY) {
      // 动画停了立刻退回按需渲染：静止时一帧都不多画，省电
      renderModeNow = RENDERMODE_WHEN_DIRTY;
      setRenderMode(RENDERMODE_WHEN_DIRTY);
      requestRender();   // 补画最后一帧，收到最终值上
    } else if (moving) {
      requestRender();   // 连续模式下这行是空操作，留着兼容 WHEN_DIRTY 首帧
    }

  }


  /** 可见区域（裁切后的窗口）在视频里的位置，作为旋转中心；宽高比用于等比旋转 */
  public void setMetrics(final float w, final float h, final float pivotUv, final float pivotVv) {
    queueEvent(
        () -> {
          videoW = Math.max(1f, w);
          videoH = Math.max(1f, h);
          aspect = videoW / videoH;
          pivotU = pivotUv;
          pivotV = pivotVv;
          requestRender();
        });
  }

  /** 侧倾角（度）。拖拽时按水平位移给一点，松手归零 */
  public void setLeanDeg(final float deg) {
    queueEvent(
        () -> {
          leanTarget = (float) Math.toRadians(deg);
          requestRender();
        });
  }

  /** 视线偏移（像素，正右正下）。整只角色朝触点轻微偏移，模拟视线跟随 */
  public void setGazePx(final float dxPx, final float dyPx) {
    queueEvent(
        () -> {
          gazeXTarget = 2f * dxPx / videoW;
          gazeYTarget = -2f * dyPx / videoH;
          requestRender();
        });
  }

  /** 设置角色整体不透明度（0~1）。SurfaceView 不受窗口 alpha 影响，只能走着色器 */
  public void setOpacity(final float v) {
    queueEvent(() -> {
      opacity = Math.max(0f, Math.min(1f, v));
      requestRender();
    });
  }

  /** 播一段素材 */
  public void play(final String clip, final boolean loop) {
    Log.i(TAG, "play 请求 " + clip + " loop=" + loop + " surface=" + (surface != null));
    queueEvent(() -> startPlay(clip, loop));
  }

  // 每段素材都换一个全新的 player：旧的直接丢弃，绝不 reset 后复用，
  // 复用 + 在回调里重入 stop 会把播放器打成 state 0 永久死掉
  private void startPlay(String clip, boolean loop) {
    if (surface == null) { Log.w(TAG, "surface 还没好，挂起 " + clip); pendingClip = clip; pendingLoop = loop; return; }
    try {
      final MediaPlayer old = player;
      player = null;
      if (old != null) {
        try { old.setSurface(null); } catch (Throwable ignored) {}
        try { old.release(); } catch (Throwable ignored) {}   // 直接丢弃，不要 stop/reset
      }
      currentClip = clip;
      returnToIdle = !loop;
      lastFrameAt = android.os.SystemClock.elapsedRealtime();   // 换片了，看门狗重新计时

      final MediaPlayer mp = new MediaPlayer();
      player = mp;
      android.content.res.AssetFileDescriptor afd = ctx.getAssets().openFd("webm/" + clip + ".webm");
      mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
      afd.close();
      mp.setSurface(surface);
      mp.setLooping(loop);
      mp.setOnPreparedListener(p2 -> {
        // start 必须在 GL 线程外、且只对当前活着的 player 生效
        if (p2 != player) return;
        try { p2.start(); requestRender(); Log.i(TAG, "▶ 开始播放 " + clip); }
        catch (Throwable e) { Log.w(TAG, "start 失败 " + clip + " " + e); }
      });
      mp.setOnCompletionListener(p2 -> {
        if (p2 != player) return;
        final boolean toIdle = returnToIdle;
        // 回调在 MediaPlayer 线程：切回主线程再播下一段，避免在回调里重入播放器
        if (toIdle) post(() -> { if (onClipEnd != null) onClipEnd.onEnd(); });
      });
      mp.setOnErrorListener((p2, what, extra) -> {
        Log.w(TAG, "播放失败 " + clip + " what=" + what);
        return true;
      });
      mp.prepareAsync();
      requestRender();
    } catch (Throwable e) {
      Log.w(TAG, "播放异常 " + clip + ": " + e.getMessage());
    }
  }

  // ==================== 卡死看门狗 ====================
  // 播放器有几条能安静死掉的路子：Surface 被重建、解码器出错、prepare 回调丢了、
  // 一段片子播完但 onCompletion 因为 player 已换掉被丢弃。
  // 这些情况下画面永远停在最后一帧，没有任何回调能依赖。
  // 所以不猜原因，直接盯帧：该有帧却 4 秒没有，就重开同一段。
  private final Runnable watchdog =
      new Runnable() {
        @Override
        public void run() {
          if (released) return;
          try {
            final String c = currentClip;
            final long last = lastFrameAt;
            if (player != null
                && c != null
                && last != 0L
                && android.os.SystemClock.elapsedRealtime() - last > 4000L) {
              Log.w(TAG, "看门狗：4 秒没有新帧，重播 " + c);
              lastFrameAt = android.os.SystemClock.elapsedRealtime();   // 先顶住，别连环触发
              final boolean lp = !returnToIdle;
              queueEvent(() -> startPlay(c, lp));
            }
          } catch (Throwable ignored) {
          }
          ui.postDelayed(this, 2500);
        }
      };

  public void release() {
    released = true;
    ui.removeCallbacks(watchdog);
    try { if (player != null) { player.release(); player = null; } } catch (Throwable ignored) {}
    try { if (surface != null) { surface.release(); surface = null; } } catch (Throwable ignored) {}
    try { if (surfaceTexture != null) { surfaceTexture.release(); surfaceTexture = null; } } catch (Throwable ignored) {}
  }

  private int build(String vs, String fs) {
    int v = compile(GLES20.GL_VERTEX_SHADER, vs);
    int f = compile(GLES20.GL_FRAGMENT_SHADER, fs);
    int p = GLES20.glCreateProgram();
    GLES20.glAttachShader(p, v);
    GLES20.glAttachShader(p, f);
    GLES20.glLinkProgram(p);
    int[] ok = new int[1];
    GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
    if (ok[0] == 0) Log.e(TAG, "link fail: " + GLES20.glGetProgramInfoLog(p));
    return p;
  }

  private int compile(int type, String src) {
    int s = GLES20.glCreateShader(type);
    GLES20.glShaderSource(s, src);
    GLES20.glCompileShader(s);
    int[] ok = new int[1];
    GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
    if (ok[0] == 0) Log.e(TAG, "compile fail: " + GLES20.glGetShaderInfoLog(s));
    return s;
  }
}
