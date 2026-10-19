package com.dsh.petoverlay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 外部 App（例如 DSH 工作台插件）可以通过广播让她说话。 */
public class PetActionReceiver extends BroadcastReceiver {
  public static final String ACTION_SAY = "com.dsh.petoverlay.SAY";
  public static final String ACTION_PLAY = "com.dsh.petoverlay.PLAY";

  @Override
  public void onReceive(Context context, Intent intent) {
    if (intent == null) return;
    String action = intent.getAction();
    Intent service = new Intent(context, PetService.class);
    if (ACTION_SAY.equals(action)) {
      service.putExtra("text", intent.getStringExtra("text"));
    } else if (ACTION_PLAY.equals(action)) {
      service.putExtra("action", intent.getStringExtra("action"));
    } else {
      return;
    }
    context.startForegroundService(service);
  }
}
