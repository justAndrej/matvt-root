package io.github.virresh.matvt.services;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import io.github.virresh.matvt.engine.impl.DensityRecovery;

public class DensityRecoveryReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
        PendingResult pending = goAsync();
        DensityRecovery.recoverIfNeeded(context, pending::finish);
    }
}
