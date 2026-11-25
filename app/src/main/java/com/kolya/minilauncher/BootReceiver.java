package com.kolya.minilauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** После включения телефона и после обновления — сразу включает фильтр и защиту. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        Net.ensureAccessibility(c);
        Net.enforcePrivateDns(c);
        Guard.enforceGrayscale(c, new Prefs(c));
        FilterVpn.start(c);
    }
}
