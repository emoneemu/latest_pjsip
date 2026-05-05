package com.example.sipcall

import android.util.Log
import org.pjsip.pjsua2.*

/**
 * PjsipManager wraps the PJSUA2 endpoint, account, and call.
 *
 * IMPORTANT — DIRECTOR OBJECT LIFETIME:
 * PJSUA2 uses SWIG "director" classes for LogWriter, Account, and Call.
 * The C++ side keeps only a weak JNI reference to the Kotlin subclass
 * instance. If the Kotlin side stops holding a strong reference, GC can
 * collect it, and the next callback from a PJSIP thread dereferences a
 * freed pointer → SIGSEGV inside pj::Endpoint::utilLogWrite / similar.
 *
 * INCOMING CALL HANDLING:
 * On an inbound INVITE, PJSUA2 fires Account.onIncomingCall() with a
 * native call id. We MUST construct a Call object that wraps that id
 * BEFORE the callback returns, otherwise the native side has no Java
 * peer for the call and the next pjsua_call_hangup() crashes inside
 * pjsip_inv_end_session() with "Invalid operation!" — which is exactly
 * the SIGABRT we saw at sip_inv.c:2688.
 *
 * Once we own the Call object, we either answer (200 OK) or hangup
 * cleanly through it.
 */
class PjsipManager(private val listener: Listener) {

    interface Listener {
        fun onRegState(code: Int, reason: String, registered: Boolean)
        fun onCallState(state: String, lastStatusCode: Int, lastReason: String)
        fun onLog(line: String)
        /**
         * Called on the PJSIP worker thread when an inbound INVITE arrives.
         * Default is auto-answer; override in MainActivity if you want to
         * present a UI prompt instead.
         */
        fun onIncomingCall(remoteUri: String) {}
    }

    companion object {
        private const val TAG = "PjsipManager"

        // PJSIP invite state ints — stable across PJSUA2 versions.
        internal const val INV_STATE_NULL = 0
        internal const val INV_STATE_CALLING = 1
        internal const val INV_STATE_INCOMING = 2
        internal const val INV_STATE_EARLY = 3
        internal const val INV_STATE_CONNECTING = 4
        internal const val INV_STATE_CONFIRMED = 5
        internal const val INV_STATE_DISCONNECTED = 6

        // PJSUA call media status ints.
        internal const val MEDIA_STATUS_ACTIVE = 1
        internal const val MEDIA_STATUS_LOCAL_HOLD = 2
        internal const val MEDIA_STATUS_REMOTE_HOLD = 3

        // PJMEDIA type audio.
        internal const val MEDIA_TYPE_AUDIO = 1

        init {
            try {
                System.loadLibrary("pjsua2")
                Log.d(TAG, "libpjsua2 loaded")
            } catch (t: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load pjsua2: ${t.message}")
                throw t
            }
        }
    }

    private var endpoint: Endpoint? = null
    private var account: SipAccount? = null
    private var currentCall: SipCall? = null

    // STRONG references — do NOT let these go out of scope.
    private var logWriter: PjsipLogWriter? = null
    private var epConfig: EpConfig? = null

    fun start() {
        if (endpoint != null) return
        try {
            val ep = Endpoint()
            ep.libCreate()

            val cfg = EpConfig()
            cfg.logConfig.level = 4
            cfg.logConfig.consoleLevel = 4

            val writer = PjsipLogWriter(listener)
            cfg.logConfig.writer = writer
            this.logWriter = writer
            this.epConfig = cfg

            cfg.uaConfig.userAgent = "SipCallAndroid/1.0 PJSUA2"

            // ---- STUN: critical for NAT traversal on outbound RTP. ----
            // For receive-only, this still helps because PJSIP advertises
            // the public address in the 200 OK SDP it sends back.
            cfg.uaConfig.stunServer.add("stun.l.google.com:19302")
            cfg.uaConfig.stunServer.add("stun1.l.google.com:19302")

            // Media config — important for Android audio.
            cfg.medConfig.clockRate = 32000
            cfg.medConfig.sndClockRate = 32000
            cfg.medConfig.channelCount = 1
            cfg.medConfig.audioFramePtime = 40
            cfg.medConfig.quality = 10      // max PJSIP signal processing quality
            cfg.medConfig.jbMax   = 100     // 300 ms ceiling absorbs WiFi burst losses
            // Disable PJSIP's software echo canceller — Android's
            // MODE_IN_COMMUNICATION already provides hardware AEC.
            cfg.medConfig.ecTailLen = 0

            ep.libInit(cfg)

            val tConfig = TransportConfig()
            tConfig.port = 0
            ep.transportCreate(
                pjsip_transport_type_e.PJSIP_TRANSPORT_UDP,
                tConfig
            )

            ep.libStart()
            Log.d(TAG, "PJSIP endpoint started")

            // Prefer Opus for low bandwidth (~1.5 MB/5 min at 24 kbps + 20 ms ptime + VAD).
            // PCMU kept as last-resort fallback if the FS internal profile lacks Opus.
            // NOTE: FreeSWITCH's internal SIP profile must list Opus in codec-prefs
            // (e.g. opus,PCMU,PCMA) or the negotiation will fall back to PCMU/PCMA.
            // In safeSetCodecPriority calls - change to:
            safeSetCodecPriority(ep, "opus/48000", 255)
            safeSetCodecPriority(ep, "PCMU/8000",  0)  // Disable PCMU entirely
            safeSetCodecPriority(ep, "PCMA/8000",  0)  // Disable PCMA entirely
            safeSetCodecPriority(ep, "G722/8000",  0)  // Disable G722

            configureOpus(ep)
            setupAudioDevice(ep)

            endpoint = ep
        } catch (e: Throwable) {
            Log.e(TAG, "start() failed", e)
            listener.onLog("PJSIP start failed: ${e.message}")
        }
    }

    fun register(
        username: String,
        password: String,
        serverIp: String,
        serverPort: Int = 5060
    ) {
        val ep = endpoint ?: run {
            listener.onLog("Endpoint not ready — did you call start()?")
            return
        }
        if (username.isBlank() || serverIp.isBlank()) {
            listener.onLog("Username / server IP cannot be blank")
            return
        }

        // Tear down any existing account first.
        try { account?.delete() } catch (_: Exception) {}
        account = null

        try {
            val acfg = AccountConfig()
            acfg.idUri = "sip:$username@$serverIp"
            acfg.regConfig.registrarUri = "sip:$serverIp:$serverPort"
            acfg.regConfig.registerOnAdd = true
            acfg.regConfig.timeoutSec = 300

            val cred = AuthCredInfo("digest", "*", username, 0, password)
            acfg.sipConfig.authCreds.add(cred)

            // ICE off — FreeSWITCH internal profile in your config is not
            // ICE-lite, and ICE handshake adds round-trips that have caused
            // INVITE timeouts on flaky carriers.
            acfg.natConfig.iceEnabled = false

            // SRTP — mandatory, signalled via SIP (SDES key exchange)
            acfg.mediaConfig.srtpUse = pjmedia_srtp_use.PJMEDIA_SRTP_MANDATORY
            acfg.mediaConfig.srtpSecureSignaling = 0  // 1 = SDES (no TLS required)

            // Outbound proxy with loose-routing — keeps signalling on the
            // same path FreeSWITCH expects.
            acfg.sipConfig.proxies.add("sip:$serverIp:$serverPort;lr")

            val acc = SipAccount(listener, this, ep)
            listener.onLog("Creating account sip:$username@$serverIp ...")
            acc.create(acfg)
            account = acc

            listener.onLog("Register request sent as $username → sip:$serverIp:$serverPort")
        } catch (e: Throwable) {
            Log.e(TAG, "register() failed", e)
            listener.onLog("Register failed: ${e.message}")
        }
    }

    private fun setupAudioDevice(ep: Endpoint) {
        try {
            val audDevMgr = ep.audDevManager()
            val devCount = audDevMgr.devCount
            listener.onLog("Audio device count: $devCount")
            Log.d(TAG, "Audio device count: $devCount")

            if (devCount <= 0) {
                listener.onLog(
                    "WARNING: no audio devices found. libpjsua2.so was built " +
                            "without OpenSL/Oboe — rebuild with " +
                            "PJMEDIA_AUDIO_DEV_HAS_OPENSL=1 or " +
                            "PJMEDIA_AUDIO_DEV_HAS_ANDROID_OBOE=1."
                )
                return
            }

            var nonNullDev: Long = -1
            for (i in 0 until devCount) {
                val info = audDevMgr.getDevInfo(i.toInt())
                val name = info.name ?: "?"
                val driver = info.driver ?: "?"
                listener.onLog("  AudDev[$i] $name drv=$driver in=${info.inputCount} out=${info.outputCount}")

                if (nonNullDev < 0 &&
                    !name.contains("Null", ignoreCase = true) &&
                    info.inputCount > 0 && info.outputCount > 0
                ) {
                    nonNullDev = i
                }
            }

            val dev = if (nonNullDev >= 0) nonNullDev else 0
            audDevMgr.setCaptureDev(dev.toInt())
            audDevMgr.setPlaybackDev(dev.toInt())
            listener.onLog("Audio device set to index $dev")
        } catch (e: Exception) {
            Log.e(TAG, "Audio dev setup failed", e)
            listener.onLog("Audio dev setup failed: ${e.message}")
        }
    }

    private fun safeSetCodecPriority(ep: Endpoint, codec: String, prio: Int) {
        try {
            ep.codecSetPriority(codec, prio.toShort())
        } catch (e: Exception) {
            Log.w(TAG, "Codec $codec not available: ${e.message}")
        }
    }

    private fun configureOpus(ep: Endpoint) {
        try {
            // 24 kbps wideband mono, 20 ms ptime.
            // 20 ms ptime: loses only 20 ms per dropped packet (vs 40 ms) and
            // activates Opus in-band FEC (packet_loss > 0 requires ptime <= 20 ms).
            val opusCfg = ep.codecOpusConfig
            opusCfg.bit_rate    = 32000
            opusCfg.frm_ptime   = 40
            opusCfg.channel_cnt = 1
            // Opus FEC: embeds a low-bitrate copy of the previous frame so the
            // receiver can reconstruct it if that packet was lost. Tells the
            // encoder to expect ~10 % packet loss on the path.
            opusCfg.packet_loss = 10
            ep.setCodecOpusConfig(opusCfg)

            val param = ep.codecGetParam("opus/48000")
            param.setting.vad  = true   // DTX — no RTP during silence
            param.setting.plc  = true   // interpolate lost frames
            param.setting.penh = true   // perceptual post-filter (clarity)
            ep.codecSetParam("opus/48000", param)

            listener.onLog("Opus: 32 kbps, 20 ms ptime, FEC on, VAD/PLC/PENH on")
        } catch (e: Exception) {
            Log.w(TAG, "Opus config failed: ${e.message}")
            listener.onLog("Opus config failed: ${e.message}")
        }
    }

    fun call(destNumber: String) {
        val acc = account ?: run {
            listener.onLog("Not registered yet")
            return
        }
        if (destNumber.isBlank()) {
            listener.onLog("Enter a number to dial")
            return
        }

        currentCall?.let {
            try { it.hangup(CallOpParam(true)) } catch (_: Exception) {}
            try { it.delete() } catch (_: Exception) {}
        }
        currentCall = null

        try {
            val dest = "sip:$destNumber@${extractHostFromAccount(acc)}"
            val call = SipCall(acc, listener, this, -1)
            val prm = CallOpParam(true)
            prm.opt.audioCount = 1
            prm.opt.videoCount = 0

            call.makeCall(dest, prm)
            currentCall = call
            listener.onLog("Dialing $dest")
        } catch (e: Throwable) {
            Log.e(TAG, "call() failed", e)
            listener.onLog("Call failed: ${e.message}")
        }
    }

    private fun extractHostFromAccount(acc: SipAccount): String {
        return try {
            val uri = acc.info.uri
            uri.substringAfter('@').substringBefore(';').substringBefore(':')
                .ifEmpty { "localhost" }
        } catch (_: Exception) {
            "localhost"
        }
    }

    /**
     * Called by SipAccount.onIncomingCall (PJSIP worker thread) after it
     * has constructed the Call object and claimed the native call id.
     * We hold a strong ref so GC can't collect the Kotlin side, then
     * auto-answer with 200 OK.
     */
    internal fun handleIncomingCall(call: SipCall) {
        // If there's already a call, reject the new one with 486 Busy Here.
        // Otherwise PJSIP gets confused tracking two simultaneous calls.
        if (currentCall != null) {
            try {
                val busy = CallOpParam()
                busy.statusCode = pjsip_status_code.PJSIP_SC_BUSY_HERE
                call.hangup(busy)
                listener.onLog("Rejected incoming call (busy)")
            } catch (_: Exception) {}
            try { call.delete() } catch (_: Exception) {}
            return
        }

        currentCall = call

        try {
            // Send 180 Ringing immediately so FreeSWITCH knows we got the
            // INVITE — this stops the 32-second CONSUME_MEDIA timeout we
            // saw in earlier logs (RECOVERY_ON_TIMER_EXPIRE).
            val ringing = CallOpParam()
            ringing.statusCode = pjsip_status_code.PJSIP_SC_RINGING
            call.answer(ringing)
            listener.onLog("Sent 180 Ringing")

            // Then auto-answer with 200 OK. If you later want to show a
            // ringing UI and let the user accept, move the 200 OK answer
            // into a separate fun answerCurrent() method called from UI.
            val ok = CallOpParam()
            ok.statusCode = pjsip_status_code.PJSIP_SC_OK
            call.answer(ok)
            listener.onLog("Sent 200 OK (auto-answer)")
        } catch (e: Throwable) {
            Log.e(TAG, "answer() failed", e)
            listener.onLog("Answer failed: ${e.message}")
            try { call.hangup(CallOpParam(true)) } catch (_: Exception) {}
            currentCall = null
        }
    }

    /**
     * Manual answer for the ringing call. Use this if you stop auto-answering
     * inside handleIncomingCall and want a UI "Accept" button instead.
     */
    fun answerCurrent() {
        val call = currentCall ?: run {
            listener.onLog("No call to answer")
            return
        }
        try {
            val ok = CallOpParam()
            ok.statusCode = pjsip_status_code.PJSIP_SC_OK
            call.answer(ok)
            listener.onLog("Sent 200 OK")
        } catch (e: Exception) {
            listener.onLog("answerCurrent failed: ${e.message}")
        }
    }

    fun hangup() {
        currentCall?.let {
            try { it.hangup(CallOpParam(true)) } catch (e: Exception) {
                Log.w(TAG, "hangup: ${e.message}")
            }
        }
    }

    internal fun clearCall(call: SipCall) {
        if (currentCall === call) currentCall = null
    }

    fun shutdown() {
        try {
            currentCall?.let {
                try { it.hangup(CallOpParam(true)) } catch (_: Exception) {}
                try { it.delete() } catch (_: Exception) {}
            }
            currentCall = null

            try { account?.delete() } catch (_: Exception) {}
            account = null

            endpoint?.let {
                try { it.libDestroy() } catch (_: Exception) {}
                try { it.delete() } catch (_: Exception) {}
            }
            endpoint = null

            logWriter = null
            epConfig = null
        } catch (e: Throwable) {
            Log.w(TAG, "shutdown: ${e.message}")
        }
    }
}

internal object PjsipThreads {
    fun ensureRegistered(ep: Endpoint?) {
        val e = ep ?: return
        try {
            if (!e.libIsThreadRegistered()) {
                e.libRegisterThread(Thread.currentThread().name)
            }
        } catch (_: Throwable) {
        }
    }
}

internal class PjsipLogWriter(
    private val listener: PjsipManager.Listener
) : LogWriter() {
    override fun write(entry: LogEntry) {
        val line = try { entry.msg.trimEnd() } catch (_: Exception) { "" }
        Log.d("pjsip", line)
        try { listener.onLog(line) } catch (_: Throwable) {}
    }
}

internal class SipAccount(
    private val listener: PjsipManager.Listener,
    private val manager: PjsipManager,
    private val ep: Endpoint
) : Account() {

    override fun onRegState(prm: OnRegStateParam) {
        PjsipThreads.ensureRegistered(ep)
        val active = try { info.regIsActive } catch (_: Exception) { false }
        val code = try { prm.code } catch (_: Exception) { 0 }
        val reason = try { prm.reason ?: "" } catch (_: Exception) { "" }
        listener.onRegState(code, reason, active)
    }

    /**
     * CRITICAL: We must construct a SipCall here using prm.callId so the
     * native call id has a Java/Kotlin peer object. Without this, PJSUA's
     * own cleanup path tries to hangup an "invalid" invite session and
     * SIGABRTs at sip_inv.c:2688.
     */
    override fun onIncomingCall(prm: OnIncomingCallParam) {
        PjsipThreads.ensureRegistered(ep)
        try {
            val call = SipCall(this, listener, manager, prm.callId)
            val remoteUri = try { call.info.remoteUri ?: "" } catch (_: Exception) { "" }
            listener.onLog("Incoming call from $remoteUri (callId=${prm.callId})")
            listener.onIncomingCall(remoteUri)
            manager.handleIncomingCall(call)
        } catch (e: Throwable) {
            Log.e("SipAccount", "onIncomingCall failed", e)
            listener.onLog("onIncomingCall failed: ${e.message}")
        }
    }
}

internal class SipCall(
    acc: SipAccount,
    private val listener: PjsipManager.Listener,
    private val manager: PjsipManager,
    callId: Int
) : Call(acc, callId) {

    override fun onCallState(prm: OnCallStateParam) {
        PjsipThreads.ensureRegistered(Endpoint.instance())
        try {
            val ci = info
            listener.onCallState(
                stateToString(ci.state),
                ci.lastStatusCode,
                ci.lastReason ?: ""
            )
            if (ci.state == PjsipManager.INV_STATE_DISCONNECTED) {
                manager.clearCall(this)
                try { delete() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            listener.onLog("onCallState err: ${e.message}")
        }
    }

    override fun onCallMediaState(prm: OnCallMediaStateParam) {
        PjsipThreads.ensureRegistered(Endpoint.instance())
        try {
            val ci = info
            for (i in 0 until ci.media.size.toInt()) {
                val mi = ci.media[i]
                val isAudio = (mi.type == PjsipManager.MEDIA_TYPE_AUDIO)
                val isActive = (mi.status == PjsipManager.MEDIA_STATUS_ACTIVE)

                if (isAudio && isActive) {
                    val aud = getAudioMedia(i)
                    val mgr = Endpoint.instance().audDevManager()
                    mgr.captureDevMedia.startTransmit(aud)
                    aud.startTransmit(mgr.playbackDevMedia)
                    listener.onLog("Audio bridged (media idx=$i)")
                }
            }
        } catch (e: Exception) {
            listener.onLog("onCallMediaState err: ${e.message}")
        }
    }

    private fun stateToString(s: Int): String = when (s) {
        0 -> "NULL"
        1 -> "CALLING"
        2 -> "INCOMING"
        3 -> "EARLY"
        4 -> "CONNECTING"
        5 -> "CONFIRMED"
        6 -> "DISCONNECTED"
        else -> "STATE_$s"
    }
}