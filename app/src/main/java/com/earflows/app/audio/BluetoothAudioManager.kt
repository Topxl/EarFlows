package com.earflows.app.audio

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Monitors Bluetooth audio device connections and provides
 * routing info for the playback manager.
 */
class BluetoothAudioManager(private val context: Context) {

    companion object {
        private const val TAG = "BluetoothAudio"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var onDeviceChanged: ((Boolean) -> Unit)? = null
    private var isBluetoothConnected = false

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            checkBluetoothOutput()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            checkBluetoothOutput()
        }
    }

    fun initialize(onDeviceChanged: (Boolean) -> Unit) {
        this.onDeviceChanged = onDeviceChanged
        audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
        checkBluetoothOutput()
    }

    fun isBluetoothOutputAvailable(): Boolean = isBluetoothConnected

    /**
     * Returns the preferred Bluetooth output AudioDeviceInfo, if available.
     */
    fun getBluetoothOutputDevice(): AudioDeviceInfo? {
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { device ->
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                            device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER)
        }
    }

    private fun checkBluetoothOutput() {
        val wasConnected = isBluetoothConnected
        isBluetoothConnected = getBluetoothOutputDevice() != null

        if (wasConnected != isBluetoothConnected) {
            Log.i(TAG, "Bluetooth output ${if (isBluetoothConnected) "connected" else "disconnected"}")
            onDeviceChanged?.invoke(isBluetoothConnected)
        }
    }

    fun release() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        onDeviceChanged = null
        Log.i(TAG, "BluetoothAudioManager released")
    }
}
