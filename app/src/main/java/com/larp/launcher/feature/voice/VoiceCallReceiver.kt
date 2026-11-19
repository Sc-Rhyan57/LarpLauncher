package com.larp.launcher.feature.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class VoiceCallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_HANGUP -> VoiceCallService.stop(context)
        }
    }

    companion object {
        const val ACTION_HANGUP = "com.larp.launcher.action.HANGUP"

        fun hangupIntent(context: Context): Intent =
            Intent(context, VoiceCallReceiver::class.java).setAction(ACTION_HANGUP)
    }
}
