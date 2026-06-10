package com.rsilverst.gimmeabeat

/** Which physiological signal drives song-BPM picking. */
enum class SignalSource(val key: String, val label: String) {
    HeartRate(key = "hr", label = "Heart rate"),
    Cadence(key = "cadence", label = "Step cadence");

    companion object {
        fun fromKey(key: String?): SignalSource = entries.firstOrNull { it.key == key } ?: HeartRate
    }
}
