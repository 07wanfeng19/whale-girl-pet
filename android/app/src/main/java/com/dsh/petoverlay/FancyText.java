package com.dsh.petoverlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.widget.TextView;

/**
 * 花体标题：内置圆润手写体 + 蓝色渐变填充 + 柔和白色描边（用阴影层实现，
 * 比"两次绘制"稳，GPU 上不会糊成色块）。
 */
public class FancyText extends TextView {

  private static Typeface cached;

  private int haloColor = 0xFFFFFFFF;
  private float haloRatio = 0.22f;
  private boolean gradient = true;
  private int[] gradientColors = {0xFF7FA9FF, 0xFF3D63D8, 0xFF24408F};

  public FancyText(Context c) {
    this(c, null);
  }

  public FancyText(Context c, AttributeSet a) {
    super(c, a);
    if (cached == null) {
      try {
        cached = Typeface.createFromAsset(c.getAssets(), "fonts/上首软糖体.ttf");
      } catch (Throwable e) {
        cached = Typeface.DEFAULT_BOLD;
      }
    }
    setTypeface(Typeface.create(cached, Typeface.NORMAL), Typeface.NORMAL);
    setPaintFlags(getPaintFlags() & ~Paint.FAKE_BOLD_TEXT_FLAG);
    setIncludeFontPadding(false);
  }

  /** 取花体 Typeface（全局缓存） */
  public static Typeface tf(Context c) {
    if (cached == null) {
      try {
        cached = Typeface.createFromAsset(c.getAssets(), "fonts/上首软糖体.ttf");
      } catch (Throwable e) {
        cached = Typeface.DEFAULT_BOLD;
      }
    }
    return cached;
  }

  /** 只换花体（常规字重，不加粗），不加阴影 */
  public static void font(android.widget.TextView tv) {
    tv.setTypeface(tf(tv.getContext()), Typeface.NORMAL);
    try {
      tv.setPaintFlags(tv.getPaintFlags() & ~Paint.FAKE_BOLD_TEXT_FLAG);
    } catch (Throwable ignored) {
    }
    try {
      tv.setShadowLayer(0f, 0f, 0f, 0x00000000);
    } catch (Throwable ignored) {
    }
  }

  /** 花体 + 常规字重 + 白色描边 + 字间距 0.1em */
  public static void decorate(android.widget.TextView tv) {
    tv.setTypeface(tf(tv.getContext()), Typeface.NORMAL);
    try {
      tv.setPaintFlags(tv.getPaintFlags() & ~Paint.FAKE_BOLD_TEXT_FLAG);
    } catch (Throwable ignored) {
    }
    try {
      tv.setLetterSpacing(0.1f);
    } catch (Throwable ignored) {
    }
    try {
      tv.setShadowLayer(Math.max(1f, tv.getTextSize() * 0.07f), 0f, 0f, 0xFFFFFFFF);
    } catch (Throwable ignored) {
    }
  }

  /** 给任意 TextView/Button 套花体 + 柔光描边 */
  public static void apply(android.widget.TextView tv, int haloColor, float ratio) {
    tv.setTypeface(tf(tv.getContext()), Typeface.NORMAL);
    try {
      tv.setPaintFlags(tv.getPaintFlags() & ~Paint.FAKE_BOLD_TEXT_FLAG);
    } catch (Throwable ignored) {
    }
    try {
      tv.setShadowLayer(Math.max(1f, tv.getTextSize() * ratio), 0f, 0f, haloColor);
    } catch (Throwable ignored) {
    }
  }

  public FancyText stroke(int color, float ratio) {
    this.haloColor = color;
    this.haloRatio = ratio;
    applyHalo();
    return this;
  }

  public FancyText grad(boolean on) {
    this.gradient = on;
    return this;
  }

  private void applyHalo() {
    // 柔和的白色外发光，读作"描边"，边缘是毛的（不硬）
    setShadowLayer(Math.max(1f, getTextSize() * haloRatio), 0f, 0f, haloColor);
  }

  @Override
  protected void onSizeChanged(int w, int h, int ow, int oh) {
    super.onSizeChanged(w, h, ow, oh);
    applyHalo();
    if (gradient) {
      Paint p = getPaint();
      p.setShader(
          new LinearGradient(
              0,
              0,
              0,
              Math.max(1, h),
              gradientColors,
              new float[] {0f, 0.55f, 1f},
              Shader.TileMode.CLAMP));
    }
  }

  @Override
  protected void onDraw(Canvas canvas) {
    if (!gradient) getPaint().setShader(null);
    super.onDraw(canvas);
  }
}
