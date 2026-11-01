package nl.fotolijst.panel;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Fires at the end of the night window: restores the screen timeout and switches the screen on. */
public final class NightWakeReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context context, Intent intent) {
    NightMode.wake(context);
  }
}
