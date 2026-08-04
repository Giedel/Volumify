package com.example.volumify.model

import android.media.AudioManager

data class AudioStreamInfo(
    val name: String,
    val streamType: Int,
    val iconName: String,
    var currentVolume: Int,
    val maxVolume: Int,
    var isMuted: Boolean = false
) {
    val percentage: Int
        get() = if (maxVolume > 0) ((currentVolume.toFloat() / maxVolume) * 100).toInt() else 0
}

object AudioStreamDefaults {
    fun getStreams(audioManager: AudioManager): List<AudioStreamInfo> {
        val streams = mutableListOf<AudioStreamInfo>()

        fun addStream(name: String, streamType: Int, iconName: String) {
            try {
                val max = audioManager.getStreamMaxVolume(streamType)
                if (max > 0) {
                    val current = audioManager.getStreamVolume(streamType)
                    streams.add(
                        AudioStreamInfo(
                            name = name,
                            streamType = streamType,
                            iconName = iconName,
                            currentVolume = current,
                            maxVolume = max,
                            isMuted = current == 0
                        )
                    )
                }
            } catch (e: Throwable) {
                // Ignore unsupported stream types on specific OEM devices
            }
        }

        // Only include Media, Ringtone, Notification, and Alarm per user specification
        addStream("Media", AudioManager.STREAM_MUSIC, "music")
        addStream("Ringtone", AudioManager.STREAM_RING, "ring")
        addStream("Notification", AudioManager.STREAM_NOTIFICATION, "notification")
        addStream("Alarm", AudioManager.STREAM_ALARM, "alarm")

        return streams
    }
}
