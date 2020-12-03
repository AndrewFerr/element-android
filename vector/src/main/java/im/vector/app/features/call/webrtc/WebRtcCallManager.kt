/*
 * Copyright (c) 2020 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.app.features.call.webrtc

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import im.vector.app.ActiveSessionDataSource
import im.vector.app.core.services.BluetoothHeadsetReceiver
import im.vector.app.core.services.CallService
import im.vector.app.core.services.WiredHeadsetStateReceiver
import im.vector.app.features.call.CallAudioManager
import im.vector.app.features.call.CameraType
import im.vector.app.features.call.CaptureFormat
import im.vector.app.features.call.VectorCallActivity
import im.vector.app.features.call.utils.EglUtils
import im.vector.app.push.fcm.FcmHelper
import kotlinx.coroutines.asCoroutineDispatcher
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.call.CallListener
import org.matrix.android.sdk.api.session.call.CallState
import org.matrix.android.sdk.api.session.call.MxCall
import org.matrix.android.sdk.api.session.room.model.call.CallAnswerContent
import org.matrix.android.sdk.api.session.room.model.call.CallCandidatesContent
import org.matrix.android.sdk.api.session.room.model.call.CallHangupContent
import org.matrix.android.sdk.api.session.room.model.call.CallInviteContent
import org.matrix.android.sdk.api.session.room.model.call.CallNegotiateContent
import org.matrix.android.sdk.api.session.room.model.call.CallRejectContent
import org.matrix.android.sdk.api.session.room.model.call.CallSelectAnswerContent
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.PeerConnectionFactory
import org.webrtc.SurfaceViewRenderer
import timber.log.Timber
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manage peerConnectionFactory & Peer connections outside of activity lifecycle to resist configuration changes
 * Use app context
 */
@Singleton
class WebRtcCallManager @Inject constructor(
        private val context: Context,
        private val activeSessionDataSource: ActiveSessionDataSource
) : CallListener, LifecycleObserver {

    private val currentSession: Session?
        get() = activeSessionDataSource.currentValue?.orNull()

    interface CurrentCallListener {
        fun onCurrentCallChange(call: WebRtcCall?)
        fun onAudioDevicesChange() {}
    }

    private val currentCallsListeners = emptyList<CurrentCallListener>().toMutableList()
    fun addCurrentCallListener(listener: CurrentCallListener) {
        currentCallsListeners.add(listener)
    }

    fun removeCurrentCallListener(listener: CurrentCallListener) {
        currentCallsListeners.remove(listener)
    }

    val callAudioManager = CallAudioManager(context) {
        currentCallsListeners.forEach {
            tryOrNull { it.onAudioDevicesChange() }
        }
    }

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()

    private val rootEglBase by lazy { EglUtils.rootEglBase }

    private var isInBackground: Boolean = true

    @OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
    fun entersForeground() {
        isInBackground = false
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    fun entersBackground() {
        isInBackground = true
    }

    var currentCall: WebRtcCall? = null
        set(value) {
            field = value
            currentCallsListeners.forEach {
                tryOrNull { it.onCurrentCallChange(value) }
            }
        }

    private val callsByCallId = HashMap<String, WebRtcCall>()
    private val callsByRoomId = HashMap<String, ArrayList<WebRtcCall>>()

    fun getCallById(callId: String): WebRtcCall? {
        return callsByCallId[callId]
    }

    fun getCallsByRoomId(roomId: String): List<WebRtcCall> {
        return callsByRoomId[roomId] ?: emptyList()
    }

    fun headSetButtonTapped() {
        Timber.v("## VOIP headSetButtonTapped")
        val call = currentCall ?: return
        if (call.mxCall.state is CallState.LocalRinging) {
            // accept call
            call.acceptIncomingCall()
        }
        if (call.mxCall.state is CallState.Connected) {
            // end call?
            call.endCall()
        }
    }

    private fun createPeerConnectionFactoryIfNeeded() {
        if (peerConnectionFactory != null) return
        Timber.v("## VOIP createPeerConnectionFactory")
        val eglBaseContext = rootEglBase?.eglBaseContext ?: return Unit.also {
            Timber.e("## VOIP No EGL BASE")
        }

        Timber.v("## VOIP PeerConnectionFactory.initialize")
        PeerConnectionFactory.initialize(PeerConnectionFactory
                .InitializationOptions.builder(context.applicationContext)
                .createInitializationOptions()
        )

        val options = PeerConnectionFactory.Options()
        val defaultVideoEncoderFactory = DefaultVideoEncoderFactory(
                eglBaseContext,
                /* enableIntelVp8Encoder */
                true,
                /* enableH264HighProfile */
                true)
        val defaultVideoDecoderFactory = DefaultVideoDecoderFactory(eglBaseContext)
        Timber.v("## VOIP PeerConnectionFactory.createPeerConnectionFactory ...")
        peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setVideoEncoderFactory(defaultVideoEncoderFactory)
                .setVideoDecoderFactory(defaultVideoDecoderFactory)
                .createPeerConnectionFactory()
    }

    private fun onCallEnded(call: WebRtcCall) {
        Timber.v("## VOIP WebRtcPeerConnectionManager onCall ended: ${call.mxCall.callId}")
        CallService.onNoActiveCall(context)
        callAudioManager.stop()
        currentCall = null
        callsByCallId.remove(call.mxCall.callId)
        callsByRoomId[call.mxCall.roomId]?.remove(call)
        // This must be done in this thread
        executor.execute {
            if (currentCall == null) {
                Timber.v("## VOIP Dispose peerConnectionFactory as there is no need to keep one")
                peerConnectionFactory?.dispose()
                peerConnectionFactory = null
            }
            Timber.v("## VOIP WebRtcPeerConnectionManager close() executor done")
        }
    }

    fun startOutgoingCall(signalingRoomId: String, otherUserId: String, isVideoCall: Boolean) {
        Timber.v("## VOIP startOutgoingCall in room $signalingRoomId to $otherUserId isVideo $isVideoCall")
        executor.execute {
            createPeerConnectionFactoryIfNeeded()
        }

        val mxCall = currentSession?.callSignalingService()?.createOutgoingCall(signalingRoomId, otherUserId, isVideoCall) ?: return
        createWebRtcCall(mxCall)
        callAudioManager.startForCall(mxCall)

        val name = currentSession?.getUser(mxCall.opponentUserId)?.getBestName()
                ?: mxCall.opponentUserId
        CallService.onOutgoingCallRinging(
                context = context.applicationContext,
                isVideo = mxCall.isVideoCall,
                roomName = name,
                roomId = mxCall.roomId,
                matrixId = currentSession?.myUserId ?: "",
                callId = mxCall.callId)

        // start the activity now
        context.startActivity(VectorCallActivity.newIntent(context, mxCall))
    }

    override fun onCallIceCandidateReceived(mxCall: MxCall, iceCandidatesContent: CallCandidatesContent) {
        Timber.v("## VOIP onCallIceCandidateReceived for call ${mxCall.callId}")
        if (currentCall?.mxCall?.callId != mxCall.callId) return Unit.also {
            Timber.w("## VOIP ignore ice candidates from other call")
        }
        currentCall?.onCallIceCandidateReceived(iceCandidatesContent)
    }

    private fun createWebRtcCall(mxCall: MxCall): WebRtcCall {
        val webRtcCall = WebRtcCall(
                mxCall = mxCall,
                callAudioManager = callAudioManager,
                rootEglBase = rootEglBase,
                context = context,
                dispatcher = dispatcher,
                peerConnectionFactoryProvider = {
                    createPeerConnectionFactoryIfNeeded()
                    peerConnectionFactory
                },
                sessionProvider = { currentSession },
                onCallEnded = this::onCallEnded
        )
        currentCall = webRtcCall
        callsByCallId[mxCall.callId] = webRtcCall
        callsByRoomId.getOrPut(mxCall.roomId, { ArrayList() }).add(webRtcCall)
        return webRtcCall
    }

    fun acceptIncomingCall() {
        currentCall?.acceptIncomingCall()
    }

    fun endCall(originatedByMe: Boolean = true) {
        currentCall?.endCall(originatedByMe)
    }

    fun onWiredDeviceEvent(event: WiredHeadsetStateReceiver.HeadsetPlugEvent) {
        Timber.v("## VOIP onWiredDeviceEvent $event")
        currentCall ?: return
        // sometimes we received un-wanted unplugged...
        callAudioManager.wiredStateChange(event)
    }

    fun onWirelessDeviceEvent(event: BluetoothHeadsetReceiver.BTHeadsetPlugEvent) {
        Timber.v("## VOIP onWirelessDeviceEvent $event")
        callAudioManager.bluetoothStateChange(event.plugged)
    }

    override fun onCallInviteReceived(mxCall: MxCall, callInviteContent: CallInviteContent) {
        Timber.v("## VOIP onCallInviteReceived callId ${mxCall.callId}")
        if (currentCall != null) {
            Timber.w("## VOIP receiving incoming call while already in call?")
            // Just ignore, maybe we could answer from other session?
            return
        }
        createWebRtcCall(mxCall).apply {
            offerSdp = callInviteContent.offer
        }
        callAudioManager.startForCall(mxCall)
        // Start background service with notification
        val name = currentSession?.getUser(mxCall.opponentUserId)?.getBestName()
                ?: mxCall.opponentUserId
        CallService.onIncomingCallRinging(
                context = context,
                isVideo = mxCall.isVideoCall,
                roomName = name,
                roomId = mxCall.roomId,
                matrixId = currentSession?.myUserId ?: "",
                callId = mxCall.callId
        )
        // If this is received while in background, the app will not sync,
        // and thus won't be able to received events. For example if the call is
        // accepted on an other session this device will continue ringing
        if (isInBackground) {
            if (FcmHelper.isPushSupported()) {
                // only for push version as fdroid version is already doing it?
                currentSession?.startAutomaticBackgroundSync(30, 0)
            } else {
                // Maybe increase sync freq? but how to set back to default values?
            }
        }
    }

    override fun onCallAnswerReceived(callAnswerContent: CallAnswerContent) {
        val call = callsByCallId[callAnswerContent.callId]
                ?: return Unit.also {
                    Timber.w("onCallAnswerReceived for non active call? ${callAnswerContent.callId}")
                }
        val mxCall = call.mxCall
        // Update service state
        val name = currentSession?.getUser(mxCall.opponentUserId)?.getBestName()
                ?: mxCall.opponentUserId
        CallService.onPendingCall(
                context = context,
                isVideo = mxCall.isVideoCall,
                roomName = name,
                roomId = mxCall.roomId,
                matrixId = currentSession?.myUserId ?: "",
                callId = mxCall.callId
        )
        call.onCallAnswerReceived(callAnswerContent)
    }

    override fun onCallHangupReceived(callHangupContent: CallHangupContent) {
        val call = callsByCallId[callHangupContent.callId]
                ?: return Unit.also {
                    Timber.w("onCallHangupReceived for non active call? ${callHangupContent.callId}")
                }
        call.endCall(false)
    }

    override fun onCallRejectReceived(callRejectContent: CallRejectContent) {
        val call = callsByCallId[callRejectContent.callId]
                ?: return Unit.also {
                    Timber.w("onCallRejectReceived for non active call? ${callRejectContent.callId}")
                }
        call.endCall(false)
    }

    override fun onCallSelectAnswerReceived(callSelectAnswerContent: CallSelectAnswerContent) {
        val call = callsByCallId[callSelectAnswerContent.callId]
                ?: return Unit.also {
                    Timber.w("onCallSelectAnswerReceived for non active call? ${callSelectAnswerContent.callId}")
                }
        val selectedPartyId = callSelectAnswerContent.selectedPartyId
        if (selectedPartyId != call.mxCall.ourPartyId) {
            Timber.i("Got select_answer for party ID ${selectedPartyId}: we are party ID ${call.mxCall.ourPartyId}.");
            // The other party has picked somebody else's answer
            call.endCall(false)
        }
    }

    override fun onCallNegotiateReceived(callNegotiateContent: CallNegotiateContent) {
        val call = callsByCallId[callNegotiateContent.callId]
                ?: return Unit.also {
                    Timber.w("onCallNegotiateReceived for non active call? ${callNegotiateContent.callId}")
                }
        call.onCallNegotiateReceived(callNegotiateContent)
    }

    override fun onCallManagedByOtherSession(callId: String) {
        Timber.v("## VOIP onCallManagedByOtherSession: $callId")
        currentCall = null
        val webRtcCall = callsByCallId.remove(callId)
        if (webRtcCall != null) {
            callsByRoomId[webRtcCall.mxCall.roomId]?.remove(webRtcCall)
        }
        CallService.onNoActiveCall(context)

        // did we start background sync? so we should stop it
        if (isInBackground) {
            if (FcmHelper.isPushSupported()) {
                currentSession?.stopAnyBackgroundSync()
            } else {
                // for fdroid we should not stop, it should continue syncing
                // maybe we should restore default timeout/delay though?
            }
        }
    }
}