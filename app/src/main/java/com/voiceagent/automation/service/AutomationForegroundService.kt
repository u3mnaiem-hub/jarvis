package com.voiceagent.automation.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.voiceagent.automation.MainActivity
import com.voiceagent.automation.R
import com.voiceagent.automation.engine.AutomationExecutionEngine

class AutomationForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            AutomationExecutionEngine.requestStop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val automationTitle = intent?.getStringExtra(EXTRA_AUTOMATION_TITLE) ?: "Automation"
        val stepTitle = intent?.getStringExtra(EXTRA_STEP_TITLE) ?: "Executing..."
        val currentStep = intent?.getIntExtra(EXTRA_CURRENT_STEP, 1) ?: 1
        val totalSteps = intent?.getIntExtra(EXTRA_TOTAL_STEPS, 1) ?: 1

        val notification = buildNotification(automationTitle, stepTitle, currentStep, totalSteps)
        startForeground(NOTIFICATION_ID, notification)

        return START_STICKY
    }

    private fun buildNotification(
        automationTitle: String,
        stepTitle: String,
        currentStep: Int,
        totalSteps: Int
    ): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, AutomationForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("⚡ $automationTitle ($currentStep/$totalSteps)")
            .setContentText(stepTitle)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setProgress(totalSteps, currentStep, false)
            .addAction(android.R.drawable.ic_delete, getString(R.string.action_stop), stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoiceAgent:AutomationWakeLock").apply {
                acquire(10 * 60 * 1000L) // 10 minutes max timeout
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "voice_agent_automation_channel"
        const val NOTIFICATION_ID = 2026
        const val ACTION_STOP = "com.voiceagent.automation.ACTION_STOP"
        const val EXTRA_AUTOMATION_TITLE = "extra_automation_title"
        const val EXTRA_STEP_TITLE = "extra_step_title"
        const val EXTRA_CURRENT_STEP = "extra_current_step"
        const val EXTRA_TOTAL_STEPS = "extra_total_steps"

        fun start(context: Context, automationTitle: String, stepTitle: String, currentStep: Int, totalSteps: Int) {
            val intent = Intent(context, AutomationForegroundService::class.java).apply {
                putExtra(EXTRA_AUTOMATION_TITLE, automationTitle)
                putExtra(EXTRA_STEP_TITLE, stepTitle)
                putExtra(EXTRA_CURRENT_STEP, currentStep)
                putExtra(EXTRA_TOTAL_STEPS, totalSteps)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AutomationForegroundService::class.java)
            context.stopService(intent)
        }
    }
}
