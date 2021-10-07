package com.tam.captionmate_voip_prototype.twilio

import android.R
import android.annotation.TargetApi
import android.app.*
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.tam.captionmate_voip_prototype.MainActivity
import com.tam.captionmate_voip_prototype.util.Constants.ACTION_ACCEPT
import com.tam.captionmate_voip_prototype.util.Constants.ACTION_CANCEL_CALL
import com.tam.captionmate_voip_prototype.util.Constants.ACTION_INCOMING_CALL
import com.tam.captionmate_voip_prototype.util.Constants.ACTION_INCOMING_CALL_NOTIFICATION
import com.tam.captionmate_voip_prototype.util.Constants.ACTION_REJECT
import com.tam.captionmate_voip_prototype.util.Constants.CALL_SID_KEY
import com.tam.captionmate_voip_prototype.util.Constants.INCOMING_CALL_INVITE
import com.tam.captionmate_voip_prototype.util.Constants.INCOMING_CALL_NOTIFICATION_ID
import com.tam.captionmate_voip_prototype.util.Constants.VOICE_CHANNEL_HIGH_IMPORTANCE
import com.tam.captionmate_voip_prototype.util.Constants.VOICE_CHANNEL_LOW_IMPORTANCE
import com.twilio.voice.CallInvite

class IncomingCallNotificationService : Service() {
    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        val action: String? = intent.action
        val callInvite: CallInvite? = intent.getParcelableExtra(INCOMING_CALL_INVITE)
        val notificationId: Int = intent.getIntExtra(INCOMING_CALL_NOTIFICATION_ID, 0)
        when (action) {
            ACTION_INCOMING_CALL -> callInvite?.let { handleIncomingCall(it, notificationId) }
            ACTION_ACCEPT -> callInvite?.let { accept(it, notificationId) }
            ACTION_REJECT -> callInvite?.let { reject(it) }
            ACTION_CANCEL_CALL -> handleCancelledCall(intent)
            else -> {
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    private fun createNotification(
        callInvite: CallInvite,
        notificationId: Int,
        channelImportance: Int
    ): Notification {
        val intent = Intent(this, MainActivity::class.java)
        intent.setAction(ACTION_INCOMING_CALL_NOTIFICATION)
        intent.putExtra(INCOMING_CALL_NOTIFICATION_ID, notificationId)
        intent.putExtra(INCOMING_CALL_INVITE, callInvite)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            this,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT
        )
        /*
         * Pass the notification id and call sid to use as an identifier to cancel the
         * notification later
         */
        val extras = Bundle()
        extras.putString(CALL_SID_KEY, callInvite.callSid)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            buildNotification(
                callInvite.getFrom() + " is calling.",
                pendingIntent,
                extras,
                callInvite,
                notificationId,
                createChannel(channelImportance)
            )
        } else {
            NotificationCompat.Builder(this)
                .setSmallIcon(R.drawable.ic_menu_call)
                .setContentTitle("CaptionmateVoipPrototype")
                .setContentText(callInvite.from + " is calling.")
                .setAutoCancel(true)
                .setExtras(extras)
                .setContentIntent(pendingIntent)
                .setGroup("test_app_notification")
                .setColor(Color.rgb(214, 10, 37)).build()
        }
    }

    /**
     * Build a notification.
     *
     * @param text          the text of the notification
     * @param pendingIntent the body, pending intent for the notification
     * @param extras        extras passed with the notification
     * @return the builder
     */
    @TargetApi(Build.VERSION_CODES.O)
    private fun buildNotification(
        text: String, pendingIntent: PendingIntent, extras: Bundle,
        callInvite: CallInvite,
        notificationId: Int,
        channelId: String
    ): Notification {
        val rejectIntent = Intent(applicationContext, IncomingCallNotificationService::class.java)
        rejectIntent.setAction(ACTION_REJECT)
        rejectIntent.putExtra(INCOMING_CALL_INVITE, callInvite)
        rejectIntent.putExtra(INCOMING_CALL_NOTIFICATION_ID, notificationId)
        val piRejectIntent: PendingIntent = PendingIntent.getService(
            applicationContext,
            0,
            rejectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT
        )
        val acceptIntent = Intent(applicationContext, IncomingCallNotificationService::class.java)
        acceptIntent.setAction(ACTION_ACCEPT)
        acceptIntent.putExtra(INCOMING_CALL_INVITE, callInvite)
        acceptIntent.putExtra(INCOMING_CALL_NOTIFICATION_ID, notificationId)
        val piAcceptIntent: PendingIntent = PendingIntent.getService(
            applicationContext,
            0,
            acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder: Notification.Builder = Notification.Builder(
            applicationContext, channelId
        )
            .setSmallIcon(R.drawable.ic_menu_call)
            .setContentTitle("CaptionmateVoipPrototype")
            .setContentText(text)
            .setCategory(Notification.CATEGORY_CALL)
            .setExtras(extras)
            .setAutoCancel(true)
            .addAction(R.drawable.ic_menu_delete, "Decline", piRejectIntent)
            .addAction(R.drawable.ic_menu_call,"Answer", piAcceptIntent)
            .setFullScreenIntent(pendingIntent, true)
        return builder.build()
    }

    @TargetApi(Build.VERSION_CODES.O)
    private fun createChannel(channelImportance: Int): String {
        var callInviteChannel = NotificationChannel(
            VOICE_CHANNEL_HIGH_IMPORTANCE,
            "Primary Voice Channel", NotificationManager.IMPORTANCE_HIGH
        )
        var channelId: String = VOICE_CHANNEL_HIGH_IMPORTANCE
        if (channelImportance == NotificationManager.IMPORTANCE_LOW) {
            callInviteChannel = NotificationChannel(
                VOICE_CHANNEL_LOW_IMPORTANCE,
                "Primary Voice Channel", NotificationManager.IMPORTANCE_LOW
            )
            channelId = VOICE_CHANNEL_LOW_IMPORTANCE
        }
        callInviteChannel.lightColor = Color.GREEN
        callInviteChannel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        val notificationManager: NotificationManager =
            getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(callInviteChannel)
        return channelId
    }

    private fun accept(callInvite: CallInvite, notificationId: Int) {
        endForeground()
        val activeCallIntent = Intent(this, MainActivity::class.java)
        activeCallIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        activeCallIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activeCallIntent.putExtra(INCOMING_CALL_INVITE, callInvite)
        activeCallIntent.putExtra(INCOMING_CALL_NOTIFICATION_ID, notificationId)
        activeCallIntent.action = ACTION_ACCEPT
        startActivity(activeCallIntent)
    }

    private fun reject(callInvite: CallInvite) {
        endForeground()
        callInvite.reject(applicationContext)
    }

    private fun handleCancelledCall(intent: Intent) {
        endForeground()
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun handleIncomingCall(callInvite: CallInvite, notificationId: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setCallInProgressNotification(callInvite, notificationId)
        }
        sendCallInviteToActivity(callInvite, notificationId)
    }

    private fun endForeground() {
        stopForeground(true)
    }

    @TargetApi(Build.VERSION_CODES.O)
    private fun setCallInProgressNotification(callInvite: CallInvite, notificationId: Int) {
        if (isAppVisible) {
            Log.i(TAG, "setCallInProgressNotification - app is visible.")
            startForeground(
                notificationId,
                createNotification(callInvite, notificationId, NotificationManager.IMPORTANCE_LOW)
            )
        } else {
            Log.i(TAG, "setCallInProgressNotification - app is NOT visible.")
            startForeground(
                notificationId,
                createNotification(callInvite, notificationId, NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    /*
     * Send the CallInvite to the MainActivity. Start the activity if it is not running already.
     */
    private fun sendCallInviteToActivity(callInvite: CallInvite, notificationId: Int) {
        if (Build.VERSION.SDK_INT >= 29 && !isAppVisible) {
            return
        }
        val intent = Intent(this, MainActivity::class.java)
        intent.action = ACTION_INCOMING_CALL
        intent.putExtra(INCOMING_CALL_NOTIFICATION_ID, notificationId)
        intent.putExtra(INCOMING_CALL_INVITE, callInvite)
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        this.startActivity(intent)
    }

    private val isAppVisible: Boolean
        private get() = ProcessLifecycleOwner
            .get()
            .lifecycle
            .currentState
            .isAtLeast(Lifecycle.State.STARTED)

    companion object {
        private val TAG = IncomingCallNotificationService::class.java.simpleName
    }
}