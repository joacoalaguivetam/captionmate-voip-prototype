package com.tam.captionmate_voip_prototype

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.*
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.AudioManager
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.*
import android.widget.Chronometer
import android.widget.EditText
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.iid.FirebaseInstanceId
import com.tam.captionmate_voip_prototype.twilio.IncomingCallNotificationService
import com.tam.captionmate_voip_prototype.twilio.SoundPoolManager
import com.tam.captionmate_voip_prototype.util.Constants.ACTION_ACCEPT
import com.tam.captionmate_voip_prototype.util.Constants.ACTION_CANCEL_CALL
import com.tam.captionmate_voip_prototype.util.Constants.ACTION_FCM_TOKEN
import com.tam.captionmate_voip_prototype.util.Constants.ACTION_INCOMING_CALL
import com.tam.captionmate_voip_prototype.util.Constants.ACTION_INCOMING_CALL_NOTIFICATION
import com.tam.captionmate_voip_prototype.util.Constants.ACTION_REJECT
import com.tam.captionmate_voip_prototype.util.Constants.INCOMING_CALL_INVITE
import com.tam.captionmate_voip_prototype.util.Constants.INCOMING_CALL_NOTIFICATION_ID
import com.twilio.audioswitch.AudioDevice
import com.twilio.audioswitch.AudioSwitch
import com.twilio.voice.*
import com.twilio.voice.Call.CallQualityWarning
import java.util.*


class MainActivity : AppCompatActivity() {
    private val TAG = "MainActivity"
    private val MIC_PERMISSION_REQUEST_CODE = 1
    private val accessToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCIsImN0eSI6InR3aWxpby1mcGE7dj0xIn0.eyJqdGkiOiJTS2E0NjIxMWM0YzkzMzUzOWRiMTFiZDM4MDY3MzIxY2Q4LTE2MzM2Mjg1NzEiLCJncmFudHMiOnsiaWRlbnRpdHkiOiJhbGljZSIsInZvaWNlIjp7ImluY29taW5nIjp7ImFsbG93Ijp0cnVlfSwib3V0Z29pbmciOnsiYXBwbGljYXRpb25fc2lkIjoiQVA4MGFlZTJkMDFmYmJjNzRjODRjYTk4NmM0MTE0MjEyZCJ9fX0sImlhdCI6MTYzMzYyODU3MSwiZXhwIjoxNjMzNjMyMTcxLCJpc3MiOiJTS2E0NjIxMWM0YzkzMzUzOWRiMTFiZDM4MDY3MzIxY2Q4Iiwic3ViIjoiQUM4NmQ2NDZlYmRjY2IyMzc1MTBkZWM2NDYzYjRlYmI5YyJ9._vB7KdH0FZ7HiLj7MnepcQ_dJKyB7YD7XGuWtzIa6VA"

    /*
     * Audio device management
     */
    private var audioSwitch: AudioSwitch? = null
    private var savedVolumeControlStream = 0
    private var audioDeviceMenuItem: MenuItem? = null

    private var isReceiverRegistered = false
    private var voiceBroadcastReceiver: VoiceBroadcastReceiver? = null

    // Empty HashMap, never populated for the Quickstart
    var params = HashMap<String, String>()

    private var coordinatorLayout: CoordinatorLayout? = null
    private var callActionFab: FloatingActionButton? = null
    private var hangupActionFab: FloatingActionButton? = null
    private var holdActionFab: FloatingActionButton? = null
    private var muteActionFab: FloatingActionButton? = null
    private var chronometer: Chronometer? = null

    private var notificationManager: NotificationManager? = null
    private var alertDialog: AlertDialog? = null
    private var activeCallInvite: CallInvite? = null
    private var activeCall: Call? = null
    private var activeCallNotificationId = 0

    var registrationListener = registrationListener()
    var callListener = callListener()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice)

        // These flags ensure that the activity can be launched when the screen is locked.
        val window = window
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        coordinatorLayout = findViewById(R.id.coordinator_layout)
        callActionFab = findViewById(R.id.call_action_fab)
        hangupActionFab = findViewById(R.id.hangup_action_fab)
        holdActionFab = findViewById(R.id.hold_action_fab)
        muteActionFab = findViewById(R.id.mute_action_fab)
        chronometer = findViewById(R.id.chronometer)
        callActionFab?.setOnClickListener(callActionFabClickListener())
        hangupActionFab?.setOnClickListener(hangupActionFabClickListener())
        holdActionFab?.setOnClickListener(holdActionFabClickListener())
        muteActionFab?.setOnClickListener(muteActionFabClickListener())
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        /*
         * Setup the broadcast receiver to be notified of FCM Token updates
         * or incoming call invite in this Activity.
         */voiceBroadcastReceiver = VoiceBroadcastReceiver()
        registerReceiver()

        /*
         * Setup audio device management and set the volume control stream
         */audioSwitch = AudioSwitch(applicationContext)
        savedVolumeControlStream = volumeControlStream
        volumeControlStream = AudioManager.STREAM_VOICE_CALL

        /*
         * Setup the UI
         */resetUI()

        /*
         * Displays a call dialog if the intent contains a call invite
         */handleIncomingCallIntent(intent)

        /*
         * Ensure the microphone permission is enabled
         */if (!checkPermissionForMicrophone()) {
            requestPermissionForMicrophone()
        } else {
            registerForCallInvites()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIncomingCallIntent(intent)
    }

    private fun registrationListener(): RegistrationListener {
        return object : RegistrationListener {
            override fun onRegistered(accessToken: String, fcmToken: String) {
                Log.d(TAG, "Successfully registered FCM $fcmToken")
            }

            override fun onError(
                error: RegistrationException,
                accessToken: String,
                fcmToken: String
            ) {
                val message = String.format(
                    Locale.US,
                    "Registration Error: %d, %s",
                    error.errorCode,
                    error.message
                )
                Log.e(TAG, message)
                Snackbar.make((coordinatorLayout)!!, message, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun callListener(): Call.Listener {
        return object : Call.Listener {
            /*
             * This callback is emitted once before the Call.Listener.onConnected() callback when
             * the callee is being alerted of a Call. The behavior of this callback is determined by
             * the answerOnBridge flag provided in the Dial verb of your TwiML application
             * associated with this client. If the answerOnBridge flag is false, which is the
             * default, the Call.Listener.onConnected() callback will be emitted immediately after
             * Call.Listener.onRinging(). If the answerOnBridge flag is true, this will cause the
             * call to emit the onConnected callback only after the call is answered.
             * See answeronbridge for more details on how to use it with the Dial TwiML verb. If the
             * twiML response contains a Say verb, then the call will emit the
             * Call.Listener.onConnected callback immediately after Call.Listener.onRinging() is
             * raised, irrespective of the value of answerOnBridge being set to true or false
             */
            override fun onRinging(call: Call) {
                Log.d(TAG, "Ringing")
                /*
                 * When [answerOnBridge](https://www.twilio.com/docs/voice/twiml/dial#answeronbridge)
                 * is enabled in the <Dial> TwiML verb, the caller will not hear the ringback while
                 * the call is ringing and awaiting to be accepted on the callee's side. The application
                 * can use the `SoundPoolManager` to play custom audio files between the
                 * `Call.Listener.onRinging()` and the `Call.Listener.onConnected()` callbacks.
                 */if (BuildConfig.playCustomRingback) {
                    SoundPoolManager.getInstance(this@MainActivity)?.playRinging()
                }
            }

            override fun onConnectFailure(call: Call, error: CallException) {
                audioSwitch!!.deactivate()
                if (BuildConfig.playCustomRingback) {
                    SoundPoolManager.getInstance(this@MainActivity)?.stopRinging()
                }
                Log.d(TAG, "Connect failure")
                val message = String.format(
                    Locale.US,
                    "Call Error: %d, %s",
                    error.errorCode,
                    error.message
                )
                Log.e(TAG, message)
                Snackbar.make((coordinatorLayout)!!, message, Snackbar.LENGTH_LONG).show()
                resetUI()
            }

            override fun onConnected(call: Call) {
                audioSwitch!!.activate()
                if (BuildConfig.playCustomRingback) {
                    SoundPoolManager.getInstance(this@MainActivity)?.stopRinging()
                }
                Log.d(TAG, "Connected")
                activeCall = call
            }

            override fun onReconnecting(call: Call, callException: CallException) {
                Log.d(TAG, "onReconnecting")
            }

            override fun onReconnected(call: Call) {
                Log.d(TAG, "onReconnected")
            }

            override fun onDisconnected(call: Call, error: CallException?) {
                audioSwitch!!.deactivate()
                if (BuildConfig.playCustomRingback) {
                    SoundPoolManager.getInstance(this@MainActivity)?.stopRinging()
                }
                Log.d(TAG, "Disconnected")
                if (error != null) {
                    val message = String.format(
                        Locale.US,
                        "Call Error: %d, %s",
                        error.errorCode,
                        error.message
                    )
                    Log.e(TAG, message)
                    Snackbar.make((coordinatorLayout)!!, message, Snackbar.LENGTH_LONG).show()
                }
                resetUI()
            }

            /*
             * currentWarnings: existing quality warnings that have not been cleared yet
             * previousWarnings: last set of warnings prior to receiving this callback
             *
             * Example:
             *   - currentWarnings: { A, B }
             *   - previousWarnings: { B, C }
             *
             * Newly raised warnings = currentWarnings - intersection = { A }
             * Newly cleared warnings = previousWarnings - intersection = { C }
             */
            override fun onCallQualityWarningsChanged(
                call: Call,
                currentWarnings: MutableSet<CallQualityWarning>,
                previousWarnings: MutableSet<CallQualityWarning>
            ) {
                if (previousWarnings.size > 1) {
                    val intersection: MutableSet<CallQualityWarning> = HashSet(currentWarnings)
                    currentWarnings.removeAll(previousWarnings)
                    intersection.retainAll(previousWarnings)
                    previousWarnings.removeAll(intersection)
                }
                val message = String.format(
                    Locale.US,
                    "Newly raised warnings: $currentWarnings Clear warnings $previousWarnings"
                )
                Log.e(TAG, message)
                Snackbar.make((coordinatorLayout)!!, message, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    /*
     * The UI state when there is an active call
     */
    private fun setCallUI() {
        callActionFab!!.hide()
        hangupActionFab!!.show()
        holdActionFab!!.show()
        muteActionFab!!.show()
        chronometer!!.visibility = View.VISIBLE
        chronometer!!.base = SystemClock.elapsedRealtime()
        chronometer!!.start()
    }

    /*
     * Reset UI elements
     */
    private fun resetUI() {
        callActionFab!!.show()
        muteActionFab!!.setImageDrawable(
            ContextCompat.getDrawable(
                this@MainActivity,
                R.drawable.ic_mic_white_24dp
            )
        )
        holdActionFab!!.hide()
        holdActionFab!!.backgroundTintList = ColorStateList
            .valueOf(ContextCompat.getColor(this, R.color.colorAccent))
        muteActionFab!!.hide()
        hangupActionFab!!.hide()
        chronometer!!.visibility = View.INVISIBLE
        chronometer!!.stop()
    }

    override fun onResume() {
        super.onResume()
        registerReceiver()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver()
    }

    override fun onDestroy() {
        /*
         * Tear down audio device management and restore previous volume stream
         */
        audioSwitch!!.stop()
        volumeControlStream = savedVolumeControlStream
        SoundPoolManager.getInstance(this)?.release()
        super.onDestroy()
    }

    private fun handleIncomingCallIntent(intent: Intent?) {
        if (intent != null && intent.action != null) {
            val action = intent.action
            activeCallInvite = intent.getParcelableExtra(INCOMING_CALL_INVITE)
            activeCallNotificationId =
                intent.getIntExtra(INCOMING_CALL_NOTIFICATION_ID, 0)
            when (action) {
                ACTION_INCOMING_CALL -> handleIncomingCall()
                ACTION_INCOMING_CALL_NOTIFICATION -> showIncomingCallDialog()
                ACTION_CANCEL_CALL -> handleCancel()
                ACTION_FCM_TOKEN -> registerForCallInvites()
                ACTION_ACCEPT -> answer()
                else -> {
                }
            }
        }
    }

    private fun handleIncomingCall() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            showIncomingCallDialog()
        } else {
            if (isAppVisible()) {
                showIncomingCallDialog()
            }
        }
    }

    private fun handleCancel() {
        if (alertDialog != null && alertDialog!!.isShowing) {
            SoundPoolManager.getInstance(this)?.stopRinging()
            alertDialog!!.cancel()
        }
    }

    private fun registerReceiver() {
        if (!isReceiverRegistered) {
            val intentFilter = IntentFilter()
            intentFilter.addAction(ACTION_INCOMING_CALL)
            intentFilter.addAction(ACTION_CANCEL_CALL)
            intentFilter.addAction(ACTION_FCM_TOKEN)
            LocalBroadcastManager.getInstance(this).registerReceiver(
                (voiceBroadcastReceiver)!!, intentFilter
            )
            isReceiverRegistered = true
        }
    }

    private fun unregisterReceiver() {
        if (isReceiverRegistered) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver((voiceBroadcastReceiver)!!)
            isReceiverRegistered = false
        }
    }

    private class VoiceBroadcastReceiver() : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (action != null && ((action == ACTION_INCOMING_CALL) || (action == ACTION_CANCEL_CALL))) {
//                handleIncomingCallIntent(intent)
            }
        }
    }

    private fun answerCallClickListener(): DialogInterface.OnClickListener? {
        return DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int ->
            Log.d(TAG, "Clicked accept")
            val acceptIntent: Intent =
                Intent(applicationContext, IncomingCallNotificationService::class.java)
            acceptIntent.action = ACTION_ACCEPT
            acceptIntent.putExtra(INCOMING_CALL_INVITE, activeCallInvite)
            acceptIntent.putExtra(INCOMING_CALL_NOTIFICATION_ID, activeCallNotificationId)
            Log.d(TAG, "Clicked accept startService")
            startService(acceptIntent)
        }
    }

    private fun callClickListener(): DialogInterface.OnClickListener {
        return DialogInterface.OnClickListener { dialog: DialogInterface, which: Int ->
            // Place a call
            val contact: EditText =
                (dialog as AlertDialog).findViewById(R.id.contact)
            params.put("to", contact.text.toString())
            val connectOptions: ConnectOptions = ConnectOptions.Builder(accessToken)
                .params(params)
                .build()
            activeCall =
                Voice.connect(this@MainActivity, connectOptions, callListener)
            setCallUI()
            alertDialog!!.dismiss()
        }
    }

    private fun cancelCallClickListener(): DialogInterface.OnClickListener {
        return DialogInterface.OnClickListener { dialogInterface: DialogInterface?, i: Int ->
            SoundPoolManager.getInstance(this@MainActivity)?.stopRinging()
            if (activeCallInvite != null) {
                val intent: Intent =
                    Intent(this@MainActivity, IncomingCallNotificationService::class.java)
                intent.setAction(ACTION_REJECT)
                intent.putExtra(INCOMING_CALL_INVITE, activeCallInvite)
                startService(intent)
            }
            if (alertDialog != null && alertDialog!!.isShowing) {
                alertDialog!!.dismiss()
            }
        }
    }

    fun createIncomingCallDialog(
        context: Context?,
        callInvite: CallInvite,
        answerCallClickListener: DialogInterface.OnClickListener?,
        cancelClickListener: DialogInterface.OnClickListener?
    ): AlertDialog? {
        val alertDialogBuilder = AlertDialog.Builder(context)
        alertDialogBuilder.setIcon(R.drawable.ic_call_black_24dp)
        alertDialogBuilder.setTitle("Incoming Call")
        alertDialogBuilder.setPositiveButton("Accept", answerCallClickListener)
        alertDialogBuilder.setNegativeButton("Reject", cancelClickListener)
        alertDialogBuilder.setMessage(callInvite.from + " is calling with " + callInvite.callerInfo.isVerified + " status")
        return alertDialogBuilder.create()
    }

    /*
     * Register your FCM token with Twilio to receive incoming call invites
     */
    private fun registerForCallInvites() {
        FirebaseInstanceId.getInstance().instanceId
            .addOnSuccessListener(this) { instanceIdResult ->
                val fcmToken: String = instanceIdResult.token
                Log.i(TAG, "Registering with FCM")
                Voice.register(
                    accessToken,
                    Voice.RegistrationChannel.FCM,
                    fcmToken,
                    registrationListener
                )
            }
    }

    private fun callActionFabClickListener(): View.OnClickListener? {
        return View.OnClickListener {
            alertDialog =
                createCallDialog(callClickListener(), cancelCallClickListener(), this@MainActivity)
            alertDialog!!.show()
        }
    }

    private fun hangupActionFabClickListener(): View.OnClickListener? {
        return View.OnClickListener { v: View? ->
            SoundPoolManager.getInstance(this@MainActivity)?.playDisconnect()
            resetUI()
            disconnect()
        }
    }

    private fun holdActionFabClickListener(): View.OnClickListener? {
        return View.OnClickListener { v: View? -> hold() }
    }

    private fun muteActionFabClickListener(): View.OnClickListener? {
        return View.OnClickListener { v: View? -> mute() }
    }

    /*
     * Accept an incoming Call
     */
    private fun answer() {
        SoundPoolManager.getInstance(this)?.stopRinging()
        activeCallInvite!!.accept(this, callListener)
        notificationManager!!.cancel(activeCallNotificationId)
        setCallUI()
        if (alertDialog != null && alertDialog!!.isShowing) {
            alertDialog!!.dismiss()
        }
    }

    /*
     * Disconnect from Call
     */
    private fun disconnect() {
        if (activeCall != null) {
            activeCall!!.disconnect()
            activeCall = null
        }
    }

    private fun hold() {
        if (activeCall != null) {
            val hold = !activeCall!!.isOnHold
            activeCall!!.hold(hold)
            applyFabState(holdActionFab, hold)
        }
    }

    private fun mute() {
        if (activeCall != null) {
            val mute = !activeCall!!.isMuted
            activeCall!!.mute(mute)
            applyFabState(muteActionFab, mute)
        }
    }

    private fun applyFabState(button: FloatingActionButton?, enabled: Boolean) {
        // Set fab as pressed when call is on hold
        val colorStateList = if (enabled) ColorStateList.valueOf(
            ContextCompat.getColor(
                this,
                R.color.colorPrimaryDark
            )
        ) else ColorStateList.valueOf(
            ContextCompat.getColor(
                this,
                R.color.colorAccent
            )
        )
        button!!.backgroundTintList = colorStateList
    }

    private fun checkPermissionForMicrophone(): Boolean {
        val resultMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        return resultMic == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissionForMicrophone() {
        if (ActivityCompat.shouldShowRequestPermissionRationale(
                this,
                Manifest.permission.RECORD_AUDIO
            )
        ) {
            Snackbar.make(
                (coordinatorLayout)!!,
                "Microphone permissions needed. Please allow in your application settings.",
                Snackbar.LENGTH_LONG
            ).show()
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO),
                MIC_PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String?>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        /*
         * Check if microphone permissions is granted
         */
        if (requestCode == MIC_PERMISSION_REQUEST_CODE && permissions.size > 0) {
            if (grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Snackbar.make(
                    (coordinatorLayout)!!,
                    "Microphone permissions needed. Please allow in your application settings.",
                    Snackbar.LENGTH_LONG
                ).show()
            } else {
                registerForCallInvites()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val inflater = menuInflater
        inflater.inflate(R.menu.menu, menu)
        audioDeviceMenuItem = menu.findItem(R.id.menu_audio_device)

        /*
         * Start the audio device selector after the menu is created and update the icon when the
         * selected audio device changes.
         */audioSwitch!!.start { audioDevices: List<AudioDevice?>?, audioDevice: AudioDevice? ->
            updateAudioDeviceIcon(audioDevice)
            Unit
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_audio_device) {
            showAudioDevices()
            return true
        }
        return false
    }

    /*
     * Show the current available audio devices.
     */
    private fun showAudioDevices() {
        val selectedDevice = audioSwitch!!.selectedAudioDevice
        val availableAudioDevices = audioSwitch!!.availableAudioDevices
        if (selectedDevice != null) {
            val selectedDeviceIndex = availableAudioDevices.indexOf(selectedDevice)
            val audioDeviceNames = ArrayList<String>()
            for (a: AudioDevice in availableAudioDevices) {
                audioDeviceNames.add(a.name)
            }
            AlertDialog.Builder(this)
                .setTitle(R.string.select_device)
                .setSingleChoiceItems(
                    audioDeviceNames.toTypedArray<CharSequence>(),
                    selectedDeviceIndex
                ) { dialog: DialogInterface, index: Int ->
                    dialog.dismiss()
                    val selectedAudioDevice: AudioDevice =
                        availableAudioDevices.get(index)
                    updateAudioDeviceIcon(selectedAudioDevice)
                    audioSwitch!!.selectDevice(selectedAudioDevice)
                }.create().show()
        }
    }

    /*
     * Update the menu icon based on the currently selected audio device.
     */
    private fun updateAudioDeviceIcon(selectedAudioDevice: AudioDevice?) {
        var audioDeviceMenuIcon = R.drawable.ic_phonelink_ring_white_24dp
        if (selectedAudioDevice is AudioDevice.BluetoothHeadset) {
            audioDeviceMenuIcon = R.drawable.ic_bluetooth_white_24dp
        } else if (selectedAudioDevice is AudioDevice.WiredHeadset) {
            audioDeviceMenuIcon = R.drawable.ic_headset_mic_white_24dp
        } else if (selectedAudioDevice is AudioDevice.Earpiece) {
            audioDeviceMenuIcon = R.drawable.ic_phonelink_ring_white_24dp
        } else if (selectedAudioDevice is AudioDevice.Speakerphone) {
            audioDeviceMenuIcon = R.drawable.ic_volume_up_white_24dp
        }
        audioDeviceMenuItem!!.setIcon(audioDeviceMenuIcon)
    }

    private fun createCallDialog(
        callClickListener: DialogInterface.OnClickListener,
        cancelClickListener: DialogInterface.OnClickListener,
        activity: Activity
    ): AlertDialog? {
        val alertDialogBuilder = AlertDialog.Builder(activity)
        alertDialogBuilder.setIcon(R.drawable.ic_call_black_24dp)
        alertDialogBuilder.setTitle("Call")
        alertDialogBuilder.setPositiveButton("Call", callClickListener)
        alertDialogBuilder.setNegativeButton("Cancel", cancelClickListener)
        alertDialogBuilder.setCancelable(false)
        val li = LayoutInflater.from(activity)
        val dialogView: View = li.inflate(
            R.layout.dialog_call,
            activity.findViewById(android.R.id.content),
            false
        )
        val contact = dialogView.findViewById<EditText>(R.id.contact)
        contact.setHint(R.string.callee)
        alertDialogBuilder.setView(dialogView)
        return alertDialogBuilder.create()
    }

    private fun showIncomingCallDialog() {
        SoundPoolManager.getInstance(this)?.playRinging()
        if (activeCallInvite != null) {
            alertDialog = createIncomingCallDialog(
                this@MainActivity,
                activeCallInvite!!,
                answerCallClickListener(),
                cancelCallClickListener()
            )
            alertDialog!!.show()
        }
    }

    private fun isAppVisible(): Boolean {
        return ProcessLifecycleOwner
            .get()
            .lifecycle
            .currentState
            .isAtLeast(Lifecycle.State.STARTED)
    }
}