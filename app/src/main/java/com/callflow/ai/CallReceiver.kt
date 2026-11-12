package com.callflow.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat

/** Watches the phone state. When a call that CallFlow started ends, it opens the app on the remark screen. */
class CallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (i.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val st = i.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        Store.load(ctx)
        if (!Store.pending) return
        if (st == TelephonyManager.EXTRA_STATE_OFFHOOK) {
            Store.sawOff = true
            Store.save(ctx)
            return
        }
        if (st == TelephonyManager.EXTRA_STATE_IDLE && Store.sawOff) {
            Store.pending = false
            Store.sawOff = false
            Store.sess?.let { if (it.status == "active") it.stage = "remark" }
            Store.save(ctx)
            val open = Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("ended", true)
            try { ctx.startActivity(open) } catch (e: Exception) { }
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(NotificationChannel("calls", "Call ended", NotificationManager.IMPORTANCE_HIGH))
            }
            val pi = PendingIntent.getActivity(ctx, 1, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(ctx, "calls")
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle("Call ended")
                .setContentText("Tap to save the remark")
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            try { nm.notify(7, n) } catch (e: SecurityException) { }
        }
    }
}
