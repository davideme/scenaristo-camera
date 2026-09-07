package com.scenaristo.camera.capture

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.HandlerThread
import android.os.Handler
import android.util.Log
import com.scenaristo.camera.domain.mount.GravitySample
import com.scenaristo.camera.domain.mount.MountFilter
import com.scenaristo.camera.domain.mount.ScreenRotation
import com.scenaristo.camera.domain.protocol.MountAttitude
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The accelerometer, read while the phone is being set up (PRD 6.11, ADR-0027).
 *
 * The shell around [MountFilter], and it does as little as a shell can: it turns
 * the platform's callback into samples, hands them to the filter, and publishes
 * what comes back. Every threshold and every piece of geometry is in `:domain`,
 * where it is host-tested and where iOS will reach the same answers from
 * `CMDeviceMotion` (ADR-0013). The same split `ManualControls` and
 * `ManualKeyEcho` use, for the same reason: what needs a real phone is producing
 * the numbers, not judging them.
 *
 * **`TYPE_ACCELEROMETER` and not `TYPE_GRAVITY`.** The fused gravity sensor is
 * the obvious choice for an angle and the wrong one here, because it is a
 * low-pass filter: it removes linear acceleration, which is precisely the signal
 * the steadiness half of this is looking for. One raw stream answers both
 * questions; the fused one silently answers only the first, and reports a
 * shaking tripod as perfectly steady.
 *
 * **It runs during setup and not during a take** (ADR-0023, ADR-0025). Nothing
 * in here enforces that — the service does, on the recording edge — but
 * [unregister] is written so that the moment it is called there is nothing left
 * to publish, rather than a last angle that would sit on the remote looking
 * live for the length of the take.
 */
class MountSensor(context: Context) {

    private val sensors = context.getSystemService(SensorManager::class.java)
    private val accelerometer: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val filter = MountFilter()

    private val _attitude = MutableStateFlow(MountAttitude())

    /**
     * How the phone is sitting, sampled by the service's one-second tick.
     *
     * A `StateFlow` and not a callback for the same reason
     * [ExposureController.histogram] is one: the producer runs at 50 Hz and the
     * consumer wants one value a second, and a flow makes the down-sampling the
     * reader's business instead of inventing a cadence nobody asked for.
     */
    val attitude: StateFlow<MountAttitude> = _attitude.asStateFlow()

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var registered = false

    @Volatile
    private var rotation: ScreenRotation = ScreenRotation.DEGREES_90

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            // `event.timestamp` is nanoseconds since boot, and it is the
            // sample's own time rather than this callback's. That distinction is
            // the whole point of asking for batching: a batch arrives late and
            // all at once, and timing it by arrival would compress a second of
            // phone into a millisecond of maths.
            filter.onSample(
                GravitySample(
                    x = event.values[0].toDouble(),
                    y = event.values[1].toDouble(),
                    z = event.values[2].toDouble(),
                ),
                rotation,
                event.timestamp / NANOS_PER_MILLI,
            )
            _attitude.value = filter.reading()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** True when the device has an accelerometer at all. */
    val available: Boolean get() = accelerometer != null

    /**
     * Starts measuring, or updates the rotation if already measuring.
     *
     * Idempotent, because it is called from every edge that might have changed
     * the answer — the camera binding, a take ending, the display rotating —
     * and none of those wants to know whether one of the others got there first.
     */
    fun register(rotation: ScreenRotation) {
        this.rotation = rotation
        val sensor = accelerometer
        if (sensor == null) {
            // Nothing to say and nothing to log every time the camera binds: a
            // device with no accelerometer reports `measuring = false` forever,
            // which is exactly what a phone in a take reports, and the remote
            // already knows how to draw that.
            return
        }
        if (registered) return

        val thread = HandlerThread(THREAD_NAME).also { it.start() }
        this.thread = thread
        val handler = Handler(thread.looper)
        this.handler = handler

        registered = sensors?.registerListener(
            listener,
            sensor,
            SAMPLING_PERIOD_US,
            MAX_REPORT_LATENCY_US,
            handler,
        ) == true

        if (!registered) {
            Log.w(TAG, "the accelerometer refused the listener; the level overlay stays empty")
            stopThread()
            return
        }
        Log.i(TAG, "measuring the mount")
    }

    /**
     * Stops measuring and forgets what was measured.
     *
     * Both halves matter. Unregistering is what makes the ADR-0023 promise real
     * — during a take there is no listener, not merely an ignored one, and
     * `dumpsys sensorservice` will show the client count drop. Resetting is what
     * stops the last angle from before the take reappearing on the remote as
     * though it were still true.
     */
    fun unregister() {
        if (!registered) return
        sensors?.unregisterListener(listener)
        registered = false
        stopThread()
        filter.reset()
        _attitude.value = MountAttitude()
        Log.i(TAG, "stopped measuring the mount")
    }

    /** Releases everything. After this the instance is still usable via [register]. */
    fun release() = unregister()

    private fun stopThread() {
        thread?.quitSafely()
        thread = null
        handler = null
    }

    private companion object {
        const val TAG = "MountSensor"
        const val THREAD_NAME = "mount-sensor"
        const val NANOS_PER_MILLI = 1_000_000L

        /**
         * 50 Hz, which is `SENSOR_DELAY_GAME` written as what it means.
         *
         * Slower would not characterise vibration -- `SENSOR_DELAY_UI` is about
         * 16 Hz, which is close enough to the frequencies a tripod resonates at
         * to alias them into something else. Faster buys nothing: the reading
         * leaves here once a second.
         */
        const val SAMPLING_PERIOD_US = 20_000

        /**
         * Let the sensor hub collect a fifth of a second before waking the
         * application processor.
         *
         * The samples still arrive with their own timestamps, so nothing about
         * the measurement changes -- but the CPU is woken five times a second
         * instead of fifty, which is the difference between this being a
         * rounding error against the camera's power draw and being worth
         * arguing about (ADR-0025).
         */
        const val MAX_REPORT_LATENCY_US = 200_000
    }
}
