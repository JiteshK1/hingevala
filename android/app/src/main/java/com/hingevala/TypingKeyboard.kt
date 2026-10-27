package com.hingevala

import android.content.ComponentName
import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A keyboard with no keys: the bot types through it when Hinge's comment box won't take
 * text any other way. Only used if you turn it on in the phone's keyboard settings; the
 * bot switches to it for one comment and back to your keyboard straight after.
 */
class TypingKeyboard : InputMethodService() {
    companion object {
        @Volatile
        var instance: TypingKeyboard? = null

        fun id(ctx: Context): String = ComponentName(ctx, TypingKeyboard::class.java).flattenToShortString()

        fun isEnabled(ctx: Context): Boolean =
            ctx.getSystemService(InputMethodManager::class.java)
                .enabledInputMethodList.any { it.id == id(ctx) }
    }

    private val main = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onCreateInputView(): View = TextView(this).apply {
        text = "Hingevala is typing a comment…"
        gravity = Gravity.CENTER
        setPadding(24, 48, 24, 48)
    }

    /** Type `text` into the box the keyboard is attached to. False if it isn't attached. */
    fun commit(text: String): Boolean {
        var ok = false
        val done = CountDownLatch(1)
        main.post {
            ok = currentInputConnection?.commitText(text, 1) ?: false
            done.countDown()
        }
        done.await(3, TimeUnit.SECONDS)
        return ok
    }
}
