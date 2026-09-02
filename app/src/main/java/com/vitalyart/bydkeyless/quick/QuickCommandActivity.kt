package com.vitalyart.bydkeyless.quick

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.vitalyart.bydkeyless.service.KeylessService

class QuickCommandActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dispatch(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        dispatch(intent)
        finish()
    }

    private fun dispatch(source: Intent?) {
        val command = QuickCommandContract.commandForAction(source?.action) ?: return
        ContextCompat.startForegroundService(
            this,
            Intent(this, KeylessService::class.java).setAction(QuickCommandContract.actionFor(command)),
        )
    }
}
