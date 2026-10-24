package com.yayin.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/**
 * Güncelleme yardımcıları:
 *  • InstallResultReceiver: Android'in kurulum oturumundan gelen sonuç. Onay gerekiyorsa sistemin küçük onay
 *    penceresini açar; kurulum başarısızsa uygulama eski yönteme (tam ekran kurulum) döner.
 *  • UpdatedReceiver: uygulama yeni sürüme güncellenince kendiliğinden yeniden açar.
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                                       else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { ctx.startActivity(confirm) } catch (_: Exception) { Updater.fallback?.invoke() }
                } else Updater.fallback?.invoke()
            }
            PackageInstaller.STATUS_SUCCESS -> { /* uygulama değişti; UpdatedReceiver yeniden açar */ }
            PackageInstaller.STATUS_FAILURE_ABORTED -> Updater.aborted?.invoke()     // kullanıcı vazgeçti
            else -> Updater.fallback?.invoke()                                         // eski yöntemle dene
        }
    }
}

class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        try {
            ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        } catch (_: Exception) {}
    }
}
