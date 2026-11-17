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
 * Handler for the "蚂蚁阿福 × 沃莱" A1 body-fat scale (AFU-WL-TZ-A1 / FG2313UWB-D).
 *
 * Verified on real hardware: manufacturer "ICOMON", model FG2313UWB-D, firmware 1.2.2,
 * GATT service 0xFFB0 with 0xFFB1 (write) / 0xFFB2 (notify).
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

        // After the weight is marked stable the scale emits its first 0xD6 impedance frame
        // after ~4 s, but that frame still carries adc=[0,0]; the BIA channels only fill in
        // on a later frame. So this is not just "wait for the first 0xD6" - we keep the
        // session open until a frame with a usable resistance shows up, and only give up
        // (weight-only) after this long.
        private const val IMPEDANCE_WAIT_MS = 20_000L

        // This scale stops advertising while a BLE link is open. If a session never
        // completes (the user never steps on, or steps off before the weight stabilises)
        // the link would otherwise be held forever and every later scan would find
        // nothing. Always release the scale after this long.
        private const val CONNECT_TIMEOUT_MS = 60_000L

        // 0xD8 records the scale pushes on connect. The same record is repeated a few times
        // per session, and it is pushed again on every later session, so imported keys are
        // remembered across sessions (driver settings) and skipped when seen again.
        private const val HISTORY_KEY_PREF = "importedHistory"
        private const val HISTORY_KEY_LIMIT = 40

        // A record stamped inside this window is the weigh-in being streamed live right now;
        // importing it as well would create a second row for the same event.
        private const val HISTORY_LIVE_WINDOW_S = 180L

        // Anything before 2020 is a decoding artefact, not a stored measurement.
        private const val MIN_PLAUSIBLE_EPOCH = 1_600_000_000L

        // Fallback recognition. The firmware normally advertises as "AFU-WL-TZ-A1", but a
        // renamed advertisement or a packet that carries no name yet would make the scale
        // invisible to the strict prefix check. These are the extra nets, in decreasing
        // order of confidence. The MAC is this unit's static-random address.
        private val KNOWN_ADDRESSES = setOf("D0:5C:00:3D:B6:D3")
        private val BRAND_TOKENS = listOf("AFU", "WL", "TZ")

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
    private var watchdogJob: Job? = null

    /** Epoch seconds of the current session, used to separate live data from stored records. */
    private var sessionStartEpochSec = 0L

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name.trim().uppercase(Locale.ROOT)
        val serviceMatch = device.serviceUuids.any { it == SERVICE }

        // Three independent ways to recognise the scale, strongest first:
        //  1. broadcast name prefix - the firmware's normal advertising name
        //  2. exact MAC             - survives a firmware rename, or an advertisement that
        //                             arrives without a name yet
        //  3. 0xFFB0 service plus a brand token in the name
        // A bare 0xFFB0 service must never be enough on its own: MGB / Taylor / generic BIA
        // boards share that service, and claiming them would break scales that already work.
        val nameMatch = name.startsWith("AFU-WL")
        val addressMatch = device.address.uppercase(Locale.ROOT) in KNOWN_ADDRESSES
        val serviceAndBrand = serviceMatch && BRAND_TOKENS.any { name.contains(it) }

        if (!nameMatch && !addressMatch && !serviceAndBrand) return null

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
        sessionStartEpochSec = System.currentTimeMillis() / 1000L

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

        // Safety net: the scale only advertises while it is not connected, so a link that
        // is never closed makes the device invisible to all future scans. If this session
        // produces no measurement, drop the link ourselves.
        watchdogJob = scope.launch {
            delay(CONNECT_TIMEOUT_MS.milliseconds)
            if (!published) {
                logW("AfuA1: no measurement within ${CONNECT_TIMEOUT_MS / 1000}s - releasing the scale")
                requestDisconnect()
            }
        }

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
                is ScaleEvent.History -> onHistory(event, user)
                is ScaleEvent.Unknown -> logI("AfuA1: unhandled ${event.kind} ${event.payloadHex}")
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
                (w.batteryPercent?.let { " batt=$it%" } ?: "") +
                (w.progressPercent?.let { " progress=$it%" } ?: "")
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
        if (imp.adcs.isEmpty()) return
        imp.weightKg?.let { if (it > 0.0) lastWeightKg = it }

        // The 0xD6 frame only carries a weight field when mode == 1. When it does not, the
        // >=1500 ADC channels cannot be converted to ohms by the decoder (it has no weight
        // to de-offset with), so the raw values would be filtered out and the measurement
        // would end up weight-only. Re-normalise using the last stable weight from the 0xD5
        // frame so body composition still works.
        val effectiveWeight = lastWeightKg ?: imp.weightKg
        val normalized = AfuA1Lib.normalizeImpedances(imp.adcs, effectiveWeight)
        val usable = normalized.filter { it in AfuA1Lib.MIN_RESISTANCE_OHM..AfuA1Lib.MAX_RESISTANCE_OHM }
        logI(
            "AfuA1: impedance adc=${imp.adcs} norm=$normalized usable=$usable " +
                "w=${effectiveWeight ?: "null"} alg=0x${imp.algType.toString(16)}"
        )

        // The scale sends its first impedance frame right after the weight stabilises, but
        // the BIA channels are still zero in that frame - the real values arrive in a later
        // one. Publishing on the first frame therefore always produced weight-only records
        // (a complete 0xD6 with adc=[0.0, 0.0] was captured on real hardware, followed by
        // our immediate disconnect). Keep the session open and wait for a frame that
        // actually carries a resistance; the armed timeout below is the fallback.
        if (usable.isEmpty()) {
            logW("AfuA1: impedance frame without usable channels (adc=${imp.adcs}) - waiting for the next one")
            return
        }
        lastResistances = normalized

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
            applyBodyComposition(measurement, weightKg, resistance, lastResistances, user)
        } else if (lastResistances.isNotEmpty()) {
            logW("AfuA1: impedance received but no value in 100..1500 ohm window: $lastResistances")
        }
        logI("AfuA1: publishing ${String.format(Locale.US, "%.2f", weightKg)}kg" +
            (resistance?.let { ", R=${it}Ω" } ?: " (weight only)"))
        publish(measurement)
        // Release the scale immediately: while a link is open it stops advertising, so
        // holding it would break the next scan.
        watchdogJob?.cancel()
        watchdogJob = null
        requestDisconnect()
    }

    /**
     * A weigh-in the scale kept while no phone was connected. The protocol only stores the
     * raw weight plus (sometimes) the impedance channels, so body composition is rebuilt
     * here exactly like a live measurement - what is missing is the vendor cloud's extra
     * metrics (visceral fat, body age), which the scale never transmits.
     */
    private fun onHistory(h: ScaleEvent.History, user: ScaleUser) {
        if (h.weightKg <= 0.0 || h.weightG <= 0) return

        if (h.timeSeconds < MIN_PLAUSIBLE_EPOCH) {
            logW("AfuA1: history with implausible timestamp ${h.timeSeconds} - ignored")
            return
        }
        // The record currently being weighed belongs to the live stream.
        if (h.timeSeconds >= sessionStartEpochSec - HISTORY_LIVE_WINDOW_S) {
            logI("AfuA1: history ${h.timeSeconds} is the current session - live stream covers it")
            return
        }

        val key = "${h.timeSeconds}:${h.weightG}"
        if (isHistoryImported(key)) {
            logI("AfuA1: history $key already imported - skipped")
            return
        }

        val resistance = AfuA1Lib.firstResistance(h.resistances)
        logI(
            "AfuA1: history ${Date(h.timeSeconds * 1000)} " +
                "${String.format(Locale.US, "%.2f", h.weightKg)}kg " +
                "R=${resistance ?: "none"} adcs=${h.resistances} " +
                "hr=${h.heartRate ?: "-"} type=${h.historyType}"
        )

        val measurement = ScaleMeasurement().apply {
            userId = user.id
            dateTime = Date(h.timeSeconds * 1000)
            this[MeasurementType.WEIGHT] = Kg(h.weightKg.toFloat())
        }
        if (resistance != null) {
            applyBodyComposition(measurement, h.weightKg, resistance, h.resistances, user)
        }

        rememberHistory(key)
        publish(measurement)
    }

    /** Writes impedance plus every body-composition value this scale's data supports. */
    private fun applyBodyComposition(
        measurement: ScaleMeasurement,
        weightKg: Double,
        resistance: Double,
        resistances: List<Double>,
        user: ScaleUser,
    ) {
        measurement[MeasurementType.IMPEDANCE] = Ohm(resistance.toFloat())
        val comp = AfuA1Lib.estimateBodyComposition(
            weightKg = weightKg,
            heightCm = user.bodyHeight.toDouble(),
            age = user.age,
            male = user.gender == GenderType.MALE,
            resistances = resistances,
        ) ?: return
        measurement[MeasurementType.BODY_FAT] = Percent(comp.bodyFatPercent.toFloat())
        measurement[MeasurementType.WATER] = Percent(comp.waterPercent.toFloat())
        measurement[MeasurementType.MUSCLE] = Percent(comp.musclePercent.toFloat())
        measurement[MeasurementType.BONE] = Kg(comp.boneMassKg.toFloat())
        measurement[MeasurementType.PROTEIN] = Percent(comp.proteinPercent.toFloat())
        measurement[MeasurementType.LBM] = Kg(comp.fatFreeMassKg.toFloat())
        // BMR / BMI are derived by openScale itself; do not write them here.
    }

    private fun importedHistoryKeys(): List<String> =
        runCatching { settingsGetString(HISTORY_KEY_PREF) }.getOrNull()
            .orEmpty()
            .split(';')
            .filter { it.isNotBlank() }

    private fun isHistoryImported(key: String): Boolean = key in importedHistoryKeys()

    private fun rememberHistory(key: String) {
        val keys = importedHistoryKeys().toMutableList()
        keys.remove(key)
        keys.add(key)
        while (keys.size > HISTORY_KEY_LIMIT) keys.removeAt(0)
        runCatching { settingsPutString(HISTORY_KEY_PREF, keys.joinToString(";")) }
    }

    private fun resetSession() {
        publishJob?.cancel()
        publishJob = null
        watchdogJob?.cancel()
        watchdogJob = null
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
