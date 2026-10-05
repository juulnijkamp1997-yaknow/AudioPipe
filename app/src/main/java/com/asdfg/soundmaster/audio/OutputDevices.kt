package com.asdfg.soundmaster.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.asdfg.soundmaster.R

/**
 * Which output devices AudioPipe offers, and what they are called in the UI.
 * Shared by the activity (the picker) and the service (finding the chosen device).
 */
object OutputDevices {

    // Media outputs a person can plug in or pair. Left out on purpose: the
    // earpiece, Bluetooth SCO (call audio only), telephony, remote submix and
    // other internal routes.
    private val ROUTABLE_TYPES = setOf(
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL,
        AudioDeviceInfo.TYPE_AUX_LINE,
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC,
        AudioDeviceInfo.TYPE_HDMI_EARC,
        AudioDeviceInfo.TYPE_DOCK,
        AudioDeviceInfo.TYPE_DOCK_ANALOG,
    )

    fun isRoutable(device: AudioDeviceInfo): Boolean = device.type in ROUTABLE_TYPES

    /** Connected outputs that can be chosen, phone speaker first, as in Android's own output picker. */
    fun list(audioManager: AudioManager): List<AudioDeviceInfo> =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { isRoutable(it) }
            .distinctBy { it.id }
            .sortedBy { if (it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) 0 else 1 }

    /**
     * The device to pick when the user has not chosen one: the phone speaker. While a Bluetooth
     * speaker plays, the usual wish is to keep one app on the phone.
     */
    fun defaultIndex(devices: List<AudioDeviceInfo>): Int =
        devices.indexOfFirst { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            .takeIf { it >= 0 } ?: 0

    fun label(context: Context, device: AudioDeviceInfo): String {
        if (device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
            // Android names the built-in speaker after the phone model, which reads
            // as if the whole phone were the output.
            return context.getString(R.string.output_phone_speaker)
        }
        val name = device.productName?.toString()?.trim()
        return if (name.isNullOrEmpty() || name == Build.MODEL) typeName(context, device.type) else name
    }

    /** Labels for a list; devices that would get the same name also get their type, then a number. */
    fun labels(context: Context, devices: List<AudioDeviceInfo>): List<String> {
        val base = devices.map { label(context, it) }
        val withType = devices.mapIndexed { i, device ->
            if (base.count { it == base[i] } > 1 && device.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                "${base[i]} (${typeName(context, device.type)})"
            } else {
                base[i]
            }
        }
        return withType.mapIndexed { i, label ->
            if (withType.count { it == label } > 1) {
                "$label ${withType.take(i + 1).count { it == label }}"
            } else {
                label
            }
        }
    }

    private fun typeName(context: Context, type: Int): String = context.getString(
        when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> R.string.output_phone_speaker
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> R.string.output_type_bluetooth
            AudioDeviceInfo.TYPE_BLE_HEADSET -> R.string.output_type_ble_headset
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> R.string.output_type_ble_speaker
            AudioDeviceInfo.TYPE_HEARING_AID -> R.string.output_type_hearing_aid
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> R.string.output_type_wired_headphones
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> R.string.output_type_wired_headset
            AudioDeviceInfo.TYPE_USB_HEADSET -> R.string.output_type_usb_headset
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> R.string.output_type_usb
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_AUX_LINE -> R.string.output_type_line
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_HDMI_EARC -> R.string.output_type_hdmi
            AudioDeviceInfo.TYPE_DOCK,
            AudioDeviceInfo.TYPE_DOCK_ANALOG -> R.string.output_type_dock
            else -> R.string.output_type_other
        }
    )
}
