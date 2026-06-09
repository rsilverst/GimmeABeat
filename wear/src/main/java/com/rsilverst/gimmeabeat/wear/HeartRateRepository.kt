package com.rsilverst.gimmeabeat.wear

import android.content.Context
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DataTypeAvailability
import androidx.health.services.client.data.DeltaDataType
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.guava.await

sealed interface HeartRateUpdate {
    data class Measured(val bpm: Int) : HeartRateUpdate
    data class Unavailable(val reason: DataTypeAvailability) : HeartRateUpdate
    data object NotSupported : HeartRateUpdate
}

class HeartRateRepository(context: Context) {

    private val measureClient = HealthServices.getClient(context).measureClient

    fun heartRateUpdates(): Flow<HeartRateUpdate> = callbackFlow {
        val capabilities = measureClient.getCapabilitiesAsync().await()
        if (DataType.HEART_RATE_BPM !in capabilities.supportedDataTypesMeasure) {
            trySend(HeartRateUpdate.NotSupported)
            close()
            return@callbackFlow
        }

        val callback = object : MeasureCallback {
            override fun onAvailabilityChanged(
                dataType: DeltaDataType<*, *>,
                availability: Availability,
            ) {
                if (availability is DataTypeAvailability &&
                    availability != DataTypeAvailability.AVAILABLE
                ) {
                    trySend(HeartRateUpdate.Unavailable(availability))
                }
            }

            override fun onDataReceived(data: DataPointContainer) {
                data.getData(DataType.HEART_RATE_BPM).lastOrNull()?.let { point ->
                    trySend(HeartRateUpdate.Measured(point.value.toInt()))
                }
            }
        }

        measureClient.registerMeasureCallback(DataType.HEART_RATE_BPM, callback)

        awaitClose {
            measureClient.unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, callback)
        }
    }
}
