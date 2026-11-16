/*
 * openScale
 * Copyright (C) 2026 openScale contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.bluetooth.scales

import com.health.openscale.R
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.AfuA1Lib
import com.health.openscale.core.bluetooth.libs.AfuA1Lib.ScaleEvent
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.Ohm
import com.health.openscale.core.data.Percent
import com.health.openscale.core.service.ScannedDeviceInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

/**
 * Handler for the "蚂蚁阿福 × 沃莱" A1 body-fat scale (AFU-WL-TZ-A1 / FG2508WB).
 *
 * Unlike broadcast scales, this device stays silent about measurements until a GATT
 * connection is established and a 0xAC "time sync + user profile" frame is written to
 * 0xFFB1. It then streams 20-byte NOTIFY packets on 0xFFB2 carrying weight (0xD5) and
 * impedance (0xD6). See [AfuA1Lib] for the wire codec.
 *
 * Session flow:
 *  1) Enable NOTIFY on 0xFFB2.
 *  2) Write the 0xAC profile frame (epoch time, UTC offset, sex, age, height, unit).
 *  3) Latch the latest weight (0xD5) and the latest impedance (0xD6). The scale sends
 *     weight continuously while the user is on it and finally a "stable" weight frame,
 *     followed by an impedance frame. Publish once a stable weight has been seen AND a
 *     usable impedance has arrived (or a short fallback after stable weight alone).
 */
class AfuA1Handler : ScaleDeviceHandler() {

    companion object {
        val SERVICE: UUID = uuid16Static(0xFFB0)
        val CHAR_WRITE: UUID = uuid16Static(0xFFB1)
        val CHAR_NOTIFY: UUID = uuid16Static(0xFFB2)

        // Scale "device type" for connect-mode boards: subtype(7) | 0x20.
        private const val DEVICE_TYPE = 0x27

        // After the weight is marked stable, the scale sends the impedance frame a moment
        // later. Wait this long before giving up and publishing weight-only.
        private const val IMPEDANCE_WAIT_MS = 4_000L

        private fun uuid16Static(short: Int): UUID =
            UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", short))
    }

    private val decoder = AfuA1Lib.Scale27Decoder()

    // Latched values for the current weigh-in. GATT callbacks and the delayed publish
    // coroutine may run on different threads, hence @Volatile.
    @Volatile private var lastWeightKg: Double? = null
    @Volatile private var lastWeightStable = false
    @Volatile private var lastResistances: List<Double> = emptyList()
    @Volatile private var published = false
    private var publishJob: Job? = null

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name.trim().uppercase(Locale.ROOT)
        val nameMatch = name.startsWith("AFU-WL")
        val serviceMatch = device.serviceUuids.any { it == SERVICE }

        // Require the distinctive name prefix; a bare 0xFFB0 service is shared by many
        // unrelated scales (MGB/Taylor/BIA) so we must not claim on service alone.
        if (!nameMatch) return null

        val caps = setOf(
            DeviceCapability.BODY_COMPOSITION,
            DeviceCapability.LIVE_WEIGHT_STREAM,
            DeviceCapability.TIME_SYNC,
            DeviceCapability.USER_SYNC,
        )
        return DeviceSupport(
            displayName = "蚂蚁阿福 沃莱 A1 (AFU-WL)",
            capabilities = caps,
            implemented = caps,
            linkMode = LinkMode.CONNECT_GATT,
        )
    }

    override fun onConnected(user: ScaleUser) {
        resetSession()
        decoder.reset()

        // 1) Subscribe to the measurement stream.
        setNotifyOn(SERVICE, CHAR_NOTIFY)

        // 2) Send the profile frame that unlocks measurement streaming.
        val sexValue = if (user.gender == GenderType.MALE) 1 else 2
        val utcQuarter = TimeZone.getDefault().rawOffset / 900_000
        // openScale stores weight in kg internally and the wire unit only changes the
        // scale's own display, so always ask the scale for kg (0).
        val frame = AfuA1Lib.buildTimeSyncUserFrame(
            epochSeconds = System.currentTimeMillis() / 1000L,
            deviceType = DEVICE_TYPE,
            age = user.age,
            heightCm = user.bodyHeight.toInt(),
            sexValue = sexValue,
            unit = 0,
            utcQuarterOffset = utcQuarter,
        )
        writeTo(SERVICE, CHAR_WRITE, frame, withResponse = false)
        logI("AfuA1: profile frame sent (type=0x${DEVICE_TYPE.toString(16)}, sex=$sexValue, age=${user.age}, h=${user.bodyHeight.toInt()}cm)")

        userInfo(R.string.bt_info_step_on_scale)
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic != CHAR_NOTIFY) return
        val result = decoder.feed(data)
        when (result.status) {
            AfuA1Lib.FeedStatus.VERIFY_ERROR -> logW("AfuA1: checksum mismatch, frame dropped")
            AfuA1Lib.FeedStatus.ERROR -> logW("AfuA1: scale reported error flag")
            else -> Unit
        }
        for (event in result.events) {
            when (event) {
                is ScaleEvent.Weight -> onWeight(event, user)
                is ScaleEvent.Impedance -> onImpedance(event, user)
                is ScaleEvent.Unknown -> logD("AfuA1: ${event.kind} ${event.payloadHex}")
            }
        }
    }

    private fun onWeight(w: ScaleEvent.Weight, user: ScaleUser) {
        if (w.weightKg <= 0.0) return
        lastWeightKg = w.weightKg
        if (w.stable) lastWeightStable = true
        logI(
            "AfuA1: weight ${String.format(Locale.US, "%.2f", w.weightKg)}kg " +
                "stable=${w.stable} mode=${w.mode} alg=0x${w.algType.toString(16)}" +
                (w.batteryPercent?.let { " batt=$it%" } ?: "")
        )
        // Impedance normally follows the stable weight; arm a short timeout so a bare
        // weight-only scale (or a failed impedance read) still produces a measurement.
        if (w.stable && publishJob == null) {
            publishJob = scope.launch {
                delay(IMPEDANCE_WAIT_MS.milliseconds)
                if (!published) {
                    logI("AfuA1: impedance wait timed out - publishing weight only")
                    doPublish(user)
                }
            }
        }
    }

    private fun onImpedance(imp: ScaleEvent.Impedance, user: ScaleUser) {
        if (imp.resistances.isEmpty()) return
        imp.weightKg?.let { if (it > 0.0) lastWeightKg = it }
        lastResistances = imp.resistances
        logI("AfuA1: impedance raw=${imp.adcs} normalized=${imp.resistances} alg=0x${imp.algType.toString(16)}")
        // Impedance is normally the last frame. Publish right away once we have BOTH a
        // stable weight and impedance; otherwise let the armed timeout be the fallback
        // (so we never publish an intermediate, non-final weight prematurely).
        if (lastWeightStable && lastWeightKg != null) {
            publishJob?.cancel()
            doPublish(user)
        }
    }

    private fun doPublish(user: ScaleUser) {
        if (published) return
        val weightKg = lastWeightKg ?: return
        val resistance = AfuA1Lib.firstResistance(lastResistances)
        published = true
        val measurement = ScaleMeasurement().apply {
            userId = user.id
            dateTime = Date()
            this[MeasurementType.WEIGHT] = Kg(weightKg.toFloat())
        }

        if (resistance != null) {
            measurement[MeasurementType.IMPEDANCE] = Ohm(resistance.toFloat())
            val comp = AfuA1Lib.estimateBodyComposition(
                weightKg = weightKg,
                heightCm = user.bodyHeight.toDouble(),
                age = user.age,
                male = user.gender == GenderType.MALE,
                resistances = lastResistances,
            )
            if (comp != null) {
                measurement[MeasurementType.BODY_FAT] = Percent(comp.bodyFatPercent.toFloat())
                measurement[MeasurementType.WATER] = Percent(comp.waterPercent.toFloat())
                measurement[MeasurementType.MUSCLE] = Percent(comp.musclePercent.toFloat())
                measurement[MeasurementType.BONE] = Kg(comp.boneMassKg.toFloat())
                measurement[MeasurementType.PROTEIN] = Percent(comp.proteinPercent.toFloat())
                measurement[MeasurementType.LBM] = Kg(comp.fatFreeMassKg.toFloat())
                // BMR / BMI are derived by openScale itself; do not write them here.
            }
        }
        logI("AfuA1: publishing ${String.format(Locale.US, "%.2f", weightKg)}kg" +
            (resistance?.let { ", R=${it}Ω" } ?: " (weight only)"))
        publish(measurement)
        requestDisconnect()
    }

    private fun resetSession() {
        publishJob?.cancel()
        publishJob = null
        lastWeightKg = null
        lastWeightStable = false
        lastResistances = emptyList()
        published = false
    }

    override fun onDisconnected() {
        resetSession()
        decoder.reset()
    }
}
