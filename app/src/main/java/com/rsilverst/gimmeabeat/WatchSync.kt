package com.rsilverst.gimmeabeat

import android.content.Context

object WatchSync {

    private const val PATH_SIGNAL_SOURCE = "/signal_source"

    /**
     * Tells the watch which signal the phone is currently using for matching.
     * Sent via [WearMessenger] so it retries with backoff and records dropouts.
     */
    suspend fun sendSignalSource(context: Context, source: SignalSource) {
        WearMessenger.send(context, PATH_SIGNAL_SOURCE, source.key.toByteArray())
    }
}
