package com.example.sipcall

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), PjsipManager.Listener {

    private lateinit var audioManager: AudioManager

    private lateinit var serverIpInput: EditText
    private lateinit var usernameInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var destNumberInput: EditText
    private lateinit var tlsCheckBox: CheckBox
    private lateinit var srtpCheckBox: CheckBox
    private lateinit var registerBtn: Button
    private lateinit var callBtn: Button
    private lateinit var hangupBtn: Button
    private lateinit var statusView: TextView
    private lateinit var logView: TextView

    private var savedAudioMode: Int = AudioManager.MODE_NORMAL
    private var savedSpeakerphone: Boolean = false
    private var savedMicMute: Boolean = false
    private var voiceModeActive: Boolean = false

    // PJSIP now lives inside SipCallService (required for Samsung Knox to
    // allow outbound UDP). The activity reads it through this getter.
    private val pjsip: PjsipManager?
        get() = SipCallService.pjsipInstance

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        serverIpInput = findViewById(R.id.server_ip)
        usernameInput = findViewById(R.id.username)
        passwordInput = findViewById(R.id.password)
        destNumberInput = findViewById(R.id.dest_number)
        tlsCheckBox  = findViewById(R.id.chk_tls)
        srtpCheckBox = findViewById(R.id.chk_srtp)
        registerBtn  = findViewById(R.id.btn_register)
        callBtn      = findViewById(R.id.btn_call)
        hangupBtn    = findViewById(R.id.btn_hangup)
        statusView = findViewById(R.id.status)
        logView = findViewById(R.id.log)

        serverIpInput.setText("esports.hobenaki.com")
        usernameInput.setText("09638917841")
        passwordInput.setText("1234")
        destNumberInput.setText("01673779266")

        ensurePermissions()

        // Register THIS activity as the UI listener for service callbacks.
        SipCallService.uiListener = this

        // Start the phoneCall foreground service. It owns PJSIP.
        // Without this service, Samsung's Knox layer blocks outbound UDP.
        SipCallService.start(this)

        registerBtn.setOnClickListener {
            pjsip?.register(
                username = usernameInput.text.toString().trim(),
                password = passwordInput.text.toString(),
                serverIp = serverIpInput.text.toString().trim(),
                serverPort = if (tlsCheckBox.isChecked) 5061 else 5060,
                useTls  = tlsCheckBox.isChecked,
                useSrtp = srtpCheckBox.isChecked
            ) ?: appendLog("Service not ready yet — try again in a moment")
        }
        callBtn.setOnClickListener {
            // Enter voice mode BEFORE PJSIP opens the audio device. Outbound only.
            enterVoiceCallMode()
            pjsip?.call(destNumberInput.text.toString().trim())
                ?: appendLog("Service not ready")
        }
        hangupBtn.setOnClickListener {
            pjsip?.hangup()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (voiceModeActive) restoreAudioMode()
        // Don't shut down PJSIP / stop the service here — it must keep
        // running to receive incoming calls when the activity is gone.
        SipCallService.uiListener = null
    }

    private fun ensurePermissions() {
        val list = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.INTERNET,
            Manifest.permission.MODIFY_AUDIO_SETTINGS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val needed = list.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed, 1001)
        }
    }

    private fun enterVoiceCallMode() {
        if (voiceModeActive) return
        savedAudioMode = audioManager.mode
        savedSpeakerphone = audioManager.isSpeakerphoneOn
        @Suppress("DEPRECATION")
        savedMicMute = audioManager.isMicrophoneMute

        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = false
        @Suppress("DEPRECATION")
        audioManager.isMicrophoneMute = false

        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVol, 0)
        val maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic, 0)

        voiceModeActive = true
        appendLog("Audio: IN_COMMUNICATION, speaker=ON, vol=$maxVol/$maxVol")
    }

    private fun restoreAudioMode() {
        try {
            audioManager.mode = savedAudioMode
            audioManager.isSpeakerphoneOn = savedSpeakerphone
            @Suppress("DEPRECATION")
            audioManager.isMicrophoneMute = savedMicMute
            voiceModeActive = false
            appendLog("Audio mode restored")
        } catch (_: Exception) {}
    }

    override fun onRegState(code: Int, reason: String, registered: Boolean) {
        runOnUiThread {
            val tag = if (registered) "REGISTERED" else "UNREGISTERED"
            statusView.text = "SIP: $tag ($code $reason)"
            appendLog("RegState: $code $reason  registered=$registered")
        }
    }

    /**
     * Fired on PJSIP's worker thread the moment an inbound INVITE arrives.
     * We need to flip the AudioManager into MODE_IN_COMMUNICATION BEFORE
     * the media transport starts pumping audio frames, otherwise the
     * capture device opens on the wrong route and the remote side hears
     * nothing.
     */
    override fun onIncomingCall(remoteUri: String) {
        runOnUiThread {
            appendLog("Incoming call from $remoteUri")
            enterVoiceCallMode()
        }
    }

    override fun onCallState(state: String, lastStatusCode: Int, lastReason: String) {
        runOnUiThread {
            statusView.text = "Call: $state ($lastStatusCode $lastReason)"
            appendLog("CallState: $state  $lastStatusCode $lastReason")

            if (state == "DISCONNECTED" && voiceModeActive) {
                restoreAudioMode()
            }
        }
    }

    override fun onLog(line: String) {
        runOnUiThread { appendLog(line) }
    }

    private fun appendLog(line: String) {
        val cur = logView.text.toString()
        val next = (line + "\n" + cur).take(8000)
        logView.text = next
    }
}