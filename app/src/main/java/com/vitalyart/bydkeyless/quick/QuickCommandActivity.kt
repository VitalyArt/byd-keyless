package com.vitalyart.bydkeyless.quick

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.vitalyart.bydkeyless.R
import com.vitalyart.bydkeyless.model.VehicleCommand
import com.vitalyart.bydkeyless.service.KeylessService

/** Public launcher entry: an external Intent is a request to confirm, not authority to control the car. */
class QuickCommandActivity : AppCompatActivity() {
    private var confirmation: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        confirm(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        confirmation?.dismiss()
        confirm(intent)
    }

    private fun confirm(source: Intent?) {
        val command = QuickCommandContract.commandForAction(source?.action) ?: return finish()
        val label = when (command) {
            VehicleCommand.UNLOCK -> R.string.action_unlock
            VehicleCommand.LOCK -> R.string.action_lock
            else -> R.string.widget_trunk
        }
        confirmation = AlertDialog.Builder(this)
            .setTitle(R.string.quick_confirm_title)
            .setMessage(getString(R.string.quick_confirm_message, getString(label)))
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setPositiveButton(label) { _, _ ->
                runCatching {
                    ContextCompat.startForegroundService(this, Intent(this, KeylessService::class.java)
                        .setAction(QuickCommandContract.actionFor(command)))
                }
                finish()
            }
            .setOnCancelListener { finish() }
            .show()
        confirmation?.getButton(AlertDialog.BUTTON_POSITIVE)?.filterTouchesWhenObscured = true
    }

    override fun onDestroy() {
        confirmation?.dismiss()
        super.onDestroy()
    }
}
