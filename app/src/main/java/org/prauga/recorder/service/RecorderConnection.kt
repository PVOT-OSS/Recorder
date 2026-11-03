/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.prauga.recorder.models.UiStatus

/**
 * Binds to [SoundRecorderService] for the owner's lifetime and relays its status, amplitude,
 * elapsed time and live transcript to [listener]; also sends the start/stop/pause commands.
 */
class RecorderConnection(private val context: Context, private val listener: Listener) :
    DefaultLifecycleObserver {
    interface Listener {
        fun onStatus(status: UiStatus)

        fun onAmplitude(amplitude: Int) {}

        fun onElapsedTime(seconds: Long) {}

        fun onTranscript(text: String) {}
    }

    var status = UiStatus.READY
        private set

    val isConnected
        get() = service != null

    private var service: Messenger? = null
    private var isBound = false

    private val messenger =
        Messenger(
            object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(msg: Message) {
                    when (msg.what) {
                        SoundRecorderService.MSG_UI_STATUS -> {
                            status = msg.obj as UiStatus
                            listener.onStatus(status)
                        }
                        SoundRecorderService.MSG_SOUND_AMPLITUDE ->
                            listener.onAmplitude(msg.obj as Int)
                        SoundRecorderService.MSG_TIME_ELAPSED ->
                            listener.onElapsedTime(msg.obj as Long)
                        SoundRecorderService.MSG_TRANSCRIPT ->
                            listener.onTranscript(msg.obj as String)
                        else -> super.handleMessage(msg)
                    }
                }
            }
        )

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val messengerService = Messenger(binder)
                service = messengerService
                try {
                    val msg = Message.obtain(null, SoundRecorderService.MSG_REGISTER_CLIENT)
                    msg.replyTo = messenger
                    messengerService.send(msg)
                } catch (e: RemoteException) {
                    // The service crashed before we could use it; we'll be reconnected
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                service = null
            }
        }

    override fun onCreate(owner: LifecycleOwner) {
        context.bindService(
            Intent(context, SoundRecorderService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        isBound = true
    }

    override fun onDestroy(owner: LifecycleOwner) {
        service?.let {
            try {
                val msg = Message.obtain(null, SoundRecorderService.MSG_UNREGISTER_CLIENT)
                msg.replyTo = messenger
                it.send(msg)
            } catch (e: RemoteException) {
                // Nothing to do if the service has crashed
            }
        }
        if (isBound) {
            context.unbindService(connection)
            isBound = false
        }
        service = null
    }

    fun start(fileName: String) =
        send(
            Intent(context, SoundRecorderService::class.java)
                .setAction(SoundRecorderService.ACTION_START)
                .putExtra(SoundRecorderService.EXTRA_FILE_NAME, fileName)
        )

    fun stop() = send(SoundRecorderService.ACTION_STOP)

    fun togglePause() =
        when (status) {
            UiStatus.RECORDING -> send(SoundRecorderService.ACTION_PAUSE)
            UiStatus.PAUSED -> send(SoundRecorderService.ACTION_RESUME)
            UiStatus.READY -> Unit
        }

    private fun send(action: String) =
        send(Intent(context, SoundRecorderService::class.java).setAction(action))

    private fun send(intent: Intent) {
        context.startService(intent)
    }
}
