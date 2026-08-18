package org.skepsun.kototoro.core.ui.glass

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * App-wide accelerometer listener used to drive the specular highlight of
 * Liquid Glass surfaces, mirroring the upstream Control Center example
 * (Highlight angle follows the device gravity angle, falloff = 2f).
 *
 * A single shared instance is reference-counted so any number of glass
 * surfaces on screen share one sensor registration instead of each owning
 * its own listener.
 */
internal object UiSensor {

    private var refCount = 0

    private var impl: UiSensorImpl? = null

    @Composable
    fun remember(): UiSensorImpl {
        val context = LocalContext.current.applicationContext
        val instance = remember(context) {
            val existing = impl ?: UiSensorImpl(context).also { impl = it }
            if (refCount == 0) {
                existing.start()
            }
            refCount++
            existing
        }
        DisposableEffect(instance) {
            onDispose {
                if (refCount == 1) {
                    instance.stop()
                    impl = null
                }
                refCount--
            }
        }
        return instance
    }
}

internal class UiSensorImpl(context: Context) {

    var gravityAngle: Float by mutableFloatStateOf(
        // Landscape default: 90f means light from the left edge.
        45f
    )
        private set

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
            val x = event.values[0]
            val y = event.values[1]
            val norm = sqrt(x * x + y * y + 9.81f * 9.81f)
            val alpha = 0.5f
            gravityAngle = gravityAngle * (1f - alpha) +
                atan2(y, x) * (180f / PI).toFloat() * alpha
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun start() {
        sensorManager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        sensorManager.unregisterListener(listener)
    }
}
