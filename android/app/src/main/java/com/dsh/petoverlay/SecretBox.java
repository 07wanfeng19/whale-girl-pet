package com.dsh.petoverlay;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * API Key 的静态加密存储。
 *
 * 为什么不直接明文放 SharedPreferences：APK 装在手机上，SharedPreferences 是
 * 明文 XML，任何人拿到 root 或做备份都能直接读出 key。这里用 AES-256-GCM 加密，
 * 密钥本身由随机数生成后单独存一个键，至少让「拷走 prefs 文件」不再等于「拿到 key」。
 *
 * 格式：base64( iv(12字节) || ciphertext+tag )
 *
 * 注意这是设备本地保护，不是抵御逆向的强方案（密钥终究在同一个 App 沙箱里）。
 * 真要更强得用 Android Keystore，但它在 PRoot/部分 ROM 上不稳，这里取稳。
 */
final class SecretBox {

  private static final String SP = "pet";
  private static final String KEY_STORE = "secret_box_key_v1";
  private static final String PREFIX = "enc:v1:";
  private static final int IV_LEN = 12;
  private static final int TAG_BITS = 128;

  private SecretBox() {}

  /** 取（或首次生成）本机主密钥 */
  private static byte[] masterKey(Context ctx) {
    SharedPreferences sp = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE);
    String stored = sp.getString(KEY_STORE, null);
    if (stored != null && stored.length() >= 16) {
      try {
        return Base64.decode(stored, Base64.NO_WRAP);
      } catch (Throwable ignored) {
        // 损坏就重建
      }
    }
    byte[] k = new byte[32];
    new SecureRandom().nextBytes(k);
    sp.edit().putString(KEY_STORE, Base64.encodeToString(k, Base64.NO_WRAP)).apply();
    return k;
  }

  /** 加密；失败时返回空串（调用方据此跳过写入，绝不落明文） */
  static String encrypt(Context ctx, String plain) {
    if (plain == null || plain.isEmpty()) return "";
    try {
      byte[] iv = new byte[IV_LEN];
      new SecureRandom().nextBytes(iv);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(masterKey(ctx), "AES"),
          new GCMParameterSpec(TAG_BITS, iv));
      byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
      byte[] out = new byte[iv.length + ct.length];
      System.arraycopy(iv, 0, out, 0, iv.length);
      System.arraycopy(ct, 0, out, iv.length, ct.length);
      return PREFIX + Base64.encodeToString(out, Base64.NO_WRAP);
    } catch (Throwable e) {
      return "";
    }
  }

  /** 解密；不是加密格式（老版本遗留的明文）就原样返回，保证向后兼容 */
  static String decrypt(Context ctx, String stored) {
    if (stored == null || stored.isEmpty()) return "";
    if (!stored.startsWith(PREFIX)) return stored;   // 旧明文，原样用
    try {
      byte[] all = Base64.decode(stored.substring(PREFIX.length()), Base64.NO_WRAP);
      if (all.length <= IV_LEN) return "";
      byte[] iv = new byte[IV_LEN];
      System.arraycopy(all, 0, iv, 0, IV_LEN);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(masterKey(ctx), "AES"),
          new GCMParameterSpec(TAG_BITS, iv));
      byte[] pt = c.doFinal(all, IV_LEN, all.length - IV_LEN);
      return new String(pt, StandardCharsets.UTF_8);
    } catch (Throwable e) {
      return "";   // 换机 / 密钥丢失：当作没配，让主人重填，别静默用错 key
    }
  }

  /** 是不是已经加密过的值 */
  static boolean isEncrypted(String stored) {
    return stored != null && stored.startsWith(PREFIX);
  }

  /** 展示用脱敏：sk-abcd...wxyz，太短就全遮 */
  static String mask(String key) {
    if (key == null || key.isEmpty()) return "";
    if (key.length() <= 8) return "••••••••";
    return key.substring(0, 4) + "••••••••" + key.substring(key.length() - 4);
  }
}
