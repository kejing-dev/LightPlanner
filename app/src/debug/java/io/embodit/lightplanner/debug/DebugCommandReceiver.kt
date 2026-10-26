package io.embodit.lightplanner.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.embodit.lightplanner.platform.LpLog

/**
 * Debug builds only. Forwards adb broadcasts to the running Stage (action
 * `io.embodit.lightplanner.DEBUG`); see [DebugCommands] for the command list.
 *
 *   adb -s <serial> shell "am broadcast -a io.embodit.lightplanner.DEBUG -p io.embodit.lightplanner --es cmd state --es s S04"
 *
 * Extras: `cmd` = command name, `s` = text argument, `a` = comma-separated numbers, and the float
 * extras `x`, `z` (design `verification.debugHooks`).
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: return
        val s = intent.getStringExtra("s")
        val a = intent.getStringExtra("a")
        val floats = HashMap<String, Float>()
        for (k in listOf("x", "y", "z")) if (intent.hasExtra(k)) floats[k] = intent.getFloatExtra(k, 0f)
        LpLog.i("CMD recv '$cmd' s='$s' a='$a' f=$floats")
        // broadcasts arrive on the main thread, which is where the ViewModel and the scene live
        DebugCommands.run(context.applicationContext, cmd, s, a, floats)
    }
}
