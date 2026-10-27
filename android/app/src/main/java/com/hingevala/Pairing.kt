package com.hingevala

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Pairing with Wireless debugging, once. Its "Pair device with pairing code" dialog closes
 * if you switch apps, so the code is typed into a notification instead (as Shizuku does):
 * open the dialog, pull down the notification, type the 6 digits.
 */
object Pairing {
    private const val CHANNEL = "pairing"
    private const val NOTIFICATION_ID = 2
    const val KEY_CODE = "code"

    fun isPaired(ctx: Context) = prefs(ctx).getBoolean("paired", false)

    /** Post the notification that takes the pairing code. */
    fun prompt(ctx: Context) = post(
        ctx,
        "Hingevala: enter the pairing code",
        "In Wireless debugging, tap \"Pair device with pairing code\", then pull down this notification and tap Enter pairing code.",
        codeAction(ctx),
    )

    /** Find the open pairing dialog on the network and pair with its code. */
    fun pair(ctx: Context, code: String) {
        post(ctx, "Hingevala: pairing…", "Keep the pairing dialog open.")
        val adb = Adb.get(ctx)
        val port = adb.findPairingPort(ctx)
        if (port == null) {
            fail(ctx, "Couldn't find the pairing dialog. Keep \"Pair device with pairing code\" open and try again.")
            return
        }
        val ok = try {
            adb.pair(port, code.trim()) // to 127.0.0.1, this phone
        } catch (e: Exception) {
            BotLog.trace(e)
            false
        }
        if (!ok) {
            fail(ctx, "Pairing failed. Check the code and try again (a new code appears each time you open the dialog).")
            return
        }
        prefs(ctx).edit().putBoolean("paired", true).apply()
        BotLog.log("Paired with Wireless debugging.")
        post(ctx, "Hingevala: paired ✓", "You can close Settings. Open Hingevala and press Start.")
    }

    private fun fail(ctx: Context, message: String) {
        BotLog.log(message)
        post(ctx, "Hingevala: pairing didn't work", message, codeAction(ctx))
    }

    private fun codeAction(ctx: Context): Notification.Action {
        val reply = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, PairingReceiver::class.java),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val input = RemoteInput.Builder(KEY_CODE).setLabel("6-digit pairing code").build()
        return Notification.Action.Builder(null, "Enter pairing code", reply).addRemoteInput(input).build()
    }

    private fun post(ctx: Context, title: String, text: String, action: Notification.Action? = null) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Pairing", NotificationManager.IMPORTANCE_HIGH))
        val builder = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
        if (action != null) builder.addAction(action)
        nm.notify(NOTIFICATION_ID, builder.build())
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("hingevala", Context.MODE_PRIVATE)
}

/** Receives the code typed into the pairing notification. */
class PairingReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Pairing.KEY_CODE)?.toString()
            ?: intent.getStringExtra(Pairing.KEY_CODE) // debug builds: adb shell am broadcast … --es code 123456
            ?: return
        val pending = goAsync()
        Thread({
            try {
                Pairing.pair(ctx.applicationContext, code)
            } finally {
                pending.finish()
            }
        }, "hingevala-pair").start()
    }
}
