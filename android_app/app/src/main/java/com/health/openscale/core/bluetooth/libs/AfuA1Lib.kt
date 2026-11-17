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
package com.health.openscale.core.bluetooth.libs

import kotlin.math.pow
import kotlin.math.round

/**
 * Pure-JVM wire protocol codec for the "蚂蚁阿福 × 沃莱" A1 body-fat scale
 * (advertised as AFU-WL-TZ-A1, model FG2508WB), built on the ElinkThings / SWAN
 * "Scale27" protocol over BLE service 0xFFB0 (write 0xFFB1, notify 0xFFB2).
 *
 * The scale sends measurement data as a sequence of 20-byte NOTIFY packets that
 * must be re-assembled into a single logical frame before decoding. Two frame
 * types matter for a live weigh-in:
 *   - PACKET_WEIGHT (0xD5): weight in grams plus a "stable/final" flag.
 *   - PACKET_ADC    (0xD6): raw impedance (ADC) channels used for body composition.
 *
 * No Android classes are referenced here so the codec can be unit-tested on the JVM.
 * The arithmetic is a faithful Kotlin port of the reference implementation in
 * https://github.com/Mrzhang2002/ant-afu-welland-scale (protocol.py / bodycomp.py),
 * whose behaviours around rounding and little/big-endian layout are load-bearing.
 */
object AfuA1Lib {

    const val PACKET_WEIGHT: Int = 213   // 0xD5
    const val PACKET_ADC: Int = 214      // 0xD6
    const val PACKET_HISTORY: Int = 216  // 0xD8
    const val PACKET_SETTING: Int = 223  // 0xDF

    /** Every NOTIFY packet is a fixed 20-byte frame. */
    const val FRAME_SIZE: Int = 20

    /** Upper bound for packets buffered while waiting for a multi-packet frame. */
    const val MAX_PENDING_PACKETS: Int = 8

    /** Result of feeding one notification packet into [Scale27Decoder.feed]. */
    enum class FeedStatus { IMPERFECT, PERFECT, VERIFY_ERROR, ERROR }

    data class FeedResult(
        val status: FeedStatus,
        val frame: ByteArray? = null,
        val events: List<ScaleEvent> = emptyList(),
    )

    /** A decoded, semantically meaningful record from a re-assembled frame. */
    sealed class ScaleEvent {
        data class Weight(
            val weightG: Int,
            val weightKg: Double,
            val weightLb: Double,
            val stable: Boolean,
            val batteryPercent: Int?,
            val charging: Boolean?,
            /**
             * mode 2 streams a 0..100 value that jumps around during a weigh-in; on real
             * hardware it moves 100 -> 10 -> 100 within one second, so it is the measuring
             * progress, not the battery level.
             */
            val progressPercent: Int?,
            val mode: Int,
            val algType: Int,
        ) : ScaleEvent()

        data class Impedance(
            /** Raw ADC channel values as reported by the scale. */
            val adcs: List<Double>,
            /** Normalised resistance values (ohms); the first usable one feeds the BIA formula. */
            val resistances: List<Double>,
            val weightKg: Double?,
            val algType: Int,
        ) : ScaleEvent()

        /**
         * A weigh-in the scale stored while no phone was connected (0xD8). The scale pushes
         * its pending records right after a session starts, which is the only way to recover
         * measurements taken without the app.
         */
        data class History(
            /** Epoch seconds as stamped by the scale. */
            val timeSeconds: Long,
            val weightG: Int,
            val weightKg: Double,
            val stable: Boolean,
            /** Raw ADC channels of that record, already normalised to ohms. */
            val resistances: List<Double>,
            val heartRate: Int?,
            val historyType: Int,
            val algType: Int,
        ) : ScaleEvent()

        data class Unknown(val kind: String, val payloadHex: String) : ScaleEvent()
    }

    // ----------------------------------------------------------------------------
    // Frame re-assembly
    // ----------------------------------------------------------------------------

    /**
     * Stateful accumulator for the 20-byte notification stream. Feed every NOTIFY
     * payload in order; when a full logical frame arrives, [FeedResult.events] is
     * populated. Call [reset] when (re)connecting.
     */
    class Scale27Decoder {
        private val packets = mutableListOf<ByteArray>()

        fun reset() = packets.clear()

        fun feed(data: ByteArray): FeedResult {
            // Every notification from this scale is a complete 20-byte frame. A short
            // notification can never start a frame, and keeping it at the head of the
            // queue would wedge the decoder for the rest of the session (no weight, no
            // impedance, never completed -> the scale would be held connected). Drop it.
            if (data.size < FRAME_SIZE) {
                return FeedResult(FeedStatus.IMPERFECT, null)
            }
            // Never let an unterminated multi-packet setting frame (5 packets) or a lost
            // packet grow the queue without bound.
            if (packets.size >= MAX_PENDING_PACKETS) packets.clear()
            packets += data.copyOf()
            val (status, frame) = assemble(packets)
            if (status == FeedStatus.PERFECT || status == FeedStatus.VERIFY_ERROR || status == FeedStatus.ERROR) {
                packets.clear()
            }
            if (status != FeedStatus.PERFECT || frame == null) {
                return FeedResult(status, frame)
            }
            return FeedResult(status, frame, decode(frame))
        }

        private fun assemble(list: List<ByteArray>): Pair<FeedStatus, ByteArray?> {
            if (list.isEmpty()) return FeedStatus.IMPERFECT to null
            val first = list[0]
            if (first.size < FRAME_SIZE) return FeedStatus.IMPERFECT to null

            // byte 1 holds a sequence/error flag; bit 7 set means the scale reported an error.
            val seqOrFlags = first[1].toInt() and 0xFF
            if (bit(seqOrFlags, 7) == 1) return FeedStatus.ERROR to null

            val body18 = first.copyOfRange(2, FRAME_SIZE)
            val checksum = (0 until 17).sumOf { body18[it].toInt() and 0xFF } and 0x1F
            val checksumByte = body18[17].toInt() and 0xFF
            val status = if (checksum == (checksumByte and 0x1F)) FeedStatus.PERFECT else FeedStatus.VERIFY_ERROR
            val packetType = body18[16].toInt() and 0xFF
            val groupIndex = (checksumByte and 0xE0) shr 5

            // Setting frames (0xDF with sub-command 6) span five packets and are not
            // needed for a live weigh-in; reassemble defensively but they decode to Unknown.
            if (packetType == PACKET_SETTING && (body18[0].toInt() and 0xFF) == 6) {
                if (list.size != 5) return FeedStatus.IMPERFECT to null
                return status to byteArrayOf(packetType.toByte(), seqOrFlags.toByte(), 67.toByte(), 0, 6, groupIndex.toByte())
            }

            // Standard frame: [type, seq, 0x00, 0x11, <16 body bytes>, groupIndex]
            val frame = ByteArray(21)
            frame[0] = packetType.toByte()
            frame[1] = seqOrFlags.toByte()
            frame[2] = 0
            frame[3] = 17
            for (i in 0 until 16) frame[4 + i] = body18[i]
            frame[20] = groupIndex.toByte()
            return status to frame
        }

        private fun decode(frame: ByteArray): List<ScaleEvent> {
            if (frame.size < 4) return emptyList()
            val packetType = frame[0].toInt() and 0xFF
            val deviceType = frame[1].toInt() and 0xFF
            // Clamp to what is actually present: setting frames (0xDF) are assembled
            // shorter than the declared length field and must not cause OOB reads.
            val length = kotlin.math.min(u16(frame, 2), frame.size - 4)
            val payload = ByteArray(1 + length)
            payload[0] = deviceType.toByte()
            for (i in 0 until length) payload[1 + i] = frame[4 + i]

            return when (packetType) {
                PACKET_WEIGHT -> listOfNotNull(decodeWeight(payload))
                PACKET_ADC -> decodeAdc(payload)
                PACKET_HISTORY -> decodeHistory(payload)
                else -> listOf(ScaleEvent.Unknown("type=0x${packetType.toString(16)}", payload.toHex()))
            }
        }
    }

    // ----------------------------------------------------------------------------
    // Payload decoding
    // ----------------------------------------------------------------------------

    private fun decodeWeight(p: ByteArray): ScaleEvent.Weight? {
        if (p.size < 8) return null
        val encoded = u32(p, 1)
        val mode = p[5].toInt() and 0xFF

        var battery: Int? = null
        var charging: Boolean? = null
        var progress: Int? = null
        if (mode == 1 && p.size >= 9) {
            charging = bit(p[7].toInt() and 0xFF, 7) == 1
            battery = p[7].toInt() and 0x7F
        } else if (mode == 2 && p.size >= 11) {
            // mode 2 does not carry the battery level: the byte that looks like it counts
            // 0..100 and swings back and forth while the load cell settles.
            charging = bit(p[10].toInt() and 0xFF, 7) == 1
            progress = p[10].toInt() and 0x7F
        }

        val wf = weightFields(encoded)
        return ScaleEvent.Weight(
            weightG = wf.weightG,
            weightKg = wf.weightKg,
            weightLb = wf.weightLb,
            stable = wf.state,
            batteryPercent = battery,
            charging = charging,
            progressPercent = progress,
            mode = mode,
            algType = if (p.size >= 2) p[p.size - 2].toInt() and 0xFF else 0,
        )
    }

    private fun decodeAdc(p: ByteArray): List<ScaleEvent> {
        if (p.size < 5) return emptyList()
        if (bit(p[0].toInt() and 0xFF, 7) == 1) return emptyList()

        val count = p[1].toInt() and 0xFF
        var offset = 3
        val adcs = mutableListOf<Double>()
        repeat(count) {
            if (offset + 2 > p.size) return@decodeAdc emptyList()
            adcs += u16(p, offset).toDouble()
            offset += 2
        }
        if (offset >= p.size) return emptyList()
        val mode = p[offset].toInt() and 0xFF
        offset += 1

        var weightKg: Double? = null
        if (mode == 1 && offset + 4 <= p.size) {
            weightKg = weightFields(u32(p, offset)).weightKg
        }
        val resistances = normalizeImpedances(adcs, weightKg)
        val algType = if (p.size >= 2) p[p.size - 2].toInt() and 0xFF else 0
        return listOf(ScaleEvent.Impedance(adcs, resistances, weightKg, algType))
    }

    /**
     * 0xD8 stores a weigh-in the scale took while no phone was connected.
     *
     * Layout (verified against a real A1 record `29 01 6A FB 35 CD 80 69 00 72 ...`, which
     * decodes to 2026-11-17 00:56:45 / 65.65 kg, and against the reference implementation):
     *   [0] device type, [1] history type, [2..5] epoch seconds, [6..9] encoded weight,
     *   then by type: 0 -> hr, adc count, adcs | 2 -> remaining count, adc count, adcs |
     *   other -> two adcs and two padding bytes, [n] alg type, [n+1] unit.
     *
     * Records are parsed defensively: a truncated tail only drops the optional fields, the
     * weight and timestamp are still returned.
     */
    private fun decodeHistory(p: ByteArray): List<ScaleEvent> {
        if (p.size < 10) return emptyList()
        val devType = p[0].toInt() and 0xFF
        var offset = 1
        var historyType = 0

        if (devType != 42) {
            if (offset + 9 > p.size) return emptyList()
            historyType = p[offset].toInt() and 0xFF
            offset++
        }

        val timeSeconds = u32(p, offset).toLong() and 0xFFFFFFFFL
        offset += 4
        if (offset + 4 > p.size) return emptyList()
        val wf = weightFields(u32(p, offset))
        offset += 4

        var heartRate: Int? = null
        var algType = 0
        val adcs = mutableListOf<Double>()

        if (devType == 42) {
            if (offset + 2 <= p.size) {
                adcs += u16(p, offset).toDouble()
                offset += 2
            }
            offset += 4
            if (offset < p.size) {
                heartRate = p[offset].toInt() and 0xFF
                offset++
            }
            if (offset < p.size) algType = p[offset].toInt() and 0xFF
        } else {
            when (historyType) {
                0 -> {
                    // hr, then channel count, then the channels.
                    offset += 2
                    if (offset < p.size) {
                        heartRate = p[offset].toInt() and 0xFF
                        offset++
                    }
                    if (offset < p.size) {
                        val count = p[offset].toInt() and 0xFF
                        offset++
                        repeat(count) {
                            if (offset + 2 <= p.size) {
                                adcs += u16(p, offset).toDouble()
                                offset += 2
                            }
                        }
                    }
                }

                2 -> {
                    // remaining-record count, then channel count, then the channels.
                    if (offset + 1 < p.size) {
                        val count = p[offset + 1].toInt() and 0xFF
                        offset += 2
                        repeat(count) {
                            if (offset + 2 <= p.size) {
                                adcs += u16(p, offset).toDouble()
                                offset += 2
                            }
                        }
                    }
                }

                else -> {
                    repeat(2) {
                        if (offset + 2 <= p.size) {
                            adcs += u16(p, offset).toDouble()
                            offset += 2
                        }
                    }
                    offset += 2
                }
            }
            if (offset < p.size) algType = p[offset].toInt() and 0xFF
        }

        return listOf(
            ScaleEvent.History(
                timeSeconds = timeSeconds,
                weightG = wf.weightG,
                weightKg = wf.weightKg,
                stable = wf.state,
                resistances = normalizeImpedances(adcs, wf.weightKg),
                heartRate = heartRate,
                historyType = historyType,
                algType = algType,
            )
        )
    }

    // ----------------------------------------------------------------------------
    // Weight field decoding
    // ----------------------------------------------------------------------------

    private data class WeightFields(
        val weightG: Int,
        val weightKg: Double,
        val weightLb: Double,
        val kgDivision: Int,
        val kgPrecision: Int,
        val state: Boolean,
    )

    private fun weightFields(encoded: Int): WeightFields {
        val grams = encoded and 0x3FFFF
        val kgDivision = (encoded and 0x1C0000) shr 18
        val lbDivision = (encoded and 0xE00000) shr 21
        val kgPrecision = if (kgDivision in 0..2) 2 else 1
        val lbPrecision = if (lbDivision in 0..2) 2 else 1
        return WeightFields(
            weightG = grams,
            weightKg = gramsToKg(grams, kgDivision, kgPrecision),
            weightLb = gramsToLb(grams, lbDivision, lbPrecision),
            kgDivision = kgDivision,
            kgPrecision = kgPrecision,
            state = bit(encoded, 31) == 1,
        )
    }

    private fun gramsToKg(grams: Int, division: Int, precision: Int): Double =
        gunitGeneral(grams / 1000.0, division, precision)

    private fun gramsToLb(grams: Int, division: Int, precision: Int): Double =
        gunitGeneral(grams * 0.0022046, division, precision)

    /** Scale-specific quantisation/rounding with per-division rules. */
    private fun gunitGeneral(value: Double, division: Int, precision: Int): Double {
        when (division) {
            0 -> {
                val scaled = ((getInt(value * 1000.0) + 5) / 10.0).toInt()
                return scaled / 100.0
            }
            1 -> {
                var raw = getInt(value * 1000.0)
                if (raw % 10 == 9) raw += 10
                var scaled = (raw / 10.0).toInt()
                if (scaled % 2 != 0) scaled += 1
                return scaled / 100.0
            }
            2 -> {
                val raw = ((getInt(value * 1000.0) + 20) / 10.0).toInt()
                val scaled = if (raw % 10 >= 5) (raw / 10) * 10 + 5 else (raw / 10) * 10
                return scaled / 100.0
            }
            3 -> {
                val scaled = ((getInt(value * 100.0) + 5) / 10.0).toInt()
                return scaled / 10.0
            }
            4 -> {
                var raw = getInt(value * 100.0)
                if (raw % 10 == 9) raw += 10
                var scaled = (raw / 10.0).toInt()
                if (scaled % 2 != 0) scaled += 1
                return scaled / 10.0
            }
            else -> {
                val f = 10.0.pow(precision.toDouble())
                return round(value * f) / f
            }
        }
    }

    private fun getInt(value: Double): Int =
        if ((value * 10.0).toInt() % 10 >= 9) (value + 1.0).toInt() else value.toInt()

    // ----------------------------------------------------------------------------
    // Impedance normalisation
    // ----------------------------------------------------------------------------

    /**
     * 5-channel scales reorder channels; for the 2-channel A1, high ADC values
     * (raw >= 1500) are de-offset using the current weight into an ohms estimate.
     */
    fun normalizeImpedances(adcs: List<Double>, weightKg: Double?): List<Double> {
        if (adcs.size == 5) {
            return listOf(adcs[4], adcs[0], adcs[1], adcs[2], adcs[3]).map { round2(it) }
        }
        val weight = weightKg ?: 0.0
        return adcs.map { raw ->
            var adjusted = raw
            if (adjusted >= 1500.0 && weight > 0.0) {
                adjusted = (((adjusted - 1000.0) + ((weight * 10.0) * -0.4)) / 0.6) / 10.0
            }
            round2(adjusted)
        }
    }

    private fun round2(v: Double): Double = round(v * 100.0) / 100.0

    // ----------------------------------------------------------------------------
    // Outgoing command: time + user info (the 0xAC frame that starts a session)
    // ----------------------------------------------------------------------------

    private data class UserProfile(
        val age: Int,
        val heightCm: Int,
        val sexValue: Int,   // 1 = male, 2 = female (scale convention)
        val unit: Int = 0,   // 0=kg, 1=lb, 2=st, 3=jin
    )

    /**
     * Build the 20-byte 0xAC "time sync + user info" write sent to characteristic 0xFFB1.
     * The scale only streams measurements once a valid profile frame has been written.
     *
     * @param deviceType protocol device type: subtype(7) | 0x20 = 0x27 for connect-mode scales.
     * @param utcQuarterOffset local UTC offset in 15-minute units (Android: rawOffsetMs / 900000).
     */
    fun buildTimeSyncUserFrame(
        epochSeconds: Long,
        deviceType: Int,
        age: Int,
        heightCm: Int,
        sexValue: Int,
        unit: Int = 0,
        utcQuarterOffset: Int = 0,
        userSlot: Int = 1,
    ): ByteArray {
        // 19-byte payload followed by a one-byte checksum over bytes [2..18].
        val p = ByteArray(20)
        p[0] = 0xAC.toByte()
        p[1] = (deviceType and 0xFF).toByte()
        p[2] = ((epochSeconds ushr 24) and 0xFF).toByte()
        p[3] = ((epochSeconds ushr 16) and 0xFF).toByte()
        p[4] = ((epochSeconds ushr 8) and 0xFF).toByte()
        p[5] = (epochSeconds and 0xFF).toByte()
        p[6] = (utcQuarterOffset and 0xFF).toByte()
        p[7] = (unit and 0xFF).toByte()
        p[8] = (userSlot and 0xFF).toByte()
        p[9] = (heightCm and 0xFF).toByte()
        // profile weight (2 bytes, big-endian, 0.01 kg units) - 0 = not supplied
        p[10] = 0
        p[11] = 0
        p[12] = (age and 0xFF).toByte()
        p[13] = (sexValue and 0xFF).toByte()
        // target weight (2 bytes) - 0 = not supplied
        p[14] = 0
        p[15] = 0
        p[16] = 0x03  // function flags: bits 0 and 1 (impedance measurement)
        p[17] = 0
        p[18] = 0xD0.toByte()
        var sum = 0
        for (i in 2..18) sum += p[i].toInt() and 0xFF
        p[19] = (sum and 0xFF).toByte()
        return p
    }

    // ----------------------------------------------------------------------------
    // Body composition from weight + impedance (Segal 3/4-component equations)
    // ----------------------------------------------------------------------------

    data class BodyComposition(
        val model: String,
        val resistanceOhm: Double?,
        val bodyFatPercent: Double,
        val fatMassKg: Double,
        val fatFreeMassKg: Double,
        val musclePercent: Double,
        val muscleMassKg: Double,
        val waterPercent: Double,
        val waterMassKg: Double,
        val proteinPercent: Double,
        val proteinMassKg: Double,
        val bonePercent: Double,
        val boneMassKg: Double,
        val skeletalMusclePercent: Double,
        val skeletalMuscleMassKg: Double,
        val subcutaneousFatPercent: Double,
    )

    /** Pick the first resistance in the physiologically plausible 100..1500 ohm window. */
    /** Plausible body impedance window in ohms; anything outside is not a measurement. */
    const val MIN_RESISTANCE_OHM: Double = 100.0
    const val MAX_RESISTANCE_OHM: Double = 1500.0

    fun firstResistance(resistances: List<Double>): Double? =
        resistances.firstOrNull { it >= MIN_RESISTANCE_OHM && it <= MAX_RESISTANCE_OHM }

    /**
     * Estimate body composition locally. These are model-derived estimates, NOT fields
     * transmitted by the scale - the A1 only reports weight + raw impedance over BLE.
     */
    fun estimateBodyComposition(
        weightKg: Double,
        heightCm: Double,
        age: Int,
        male: Boolean,
        resistances: List<Double>,
    ): BodyComposition? {
        if (weightKg <= 0.0 || heightCm <= 0.0 || age <= 0) return null
        val resistance = firstResistance(resistances)
        val (baseFat, model, _) = if (resistance != null) {
            val (f, m) = segalFfm(weightKg, heightCm, age, male, resistance)
            val clampedFfm = f.coerceIn(weightKg * 0.35, weightKg * 0.97)
            Triple(100.0 * (weightKg - clampedFfm) / weightKg, m, clampedFfm)
        } else {
            val bmi = weightKg / ((heightCm / 100.0) * (heightCm / 100.0))
            val bf = 1.2 * bmi + 0.23 * age - (if (male) 10.8 else 0.0) - 5.4
            Triple(bf, "deurenberg_bmi_fallback", weightKg * (1.0 - bf / 100.0))
        }

        val bf = baseFat.coerceIn(if (male) 3.0 else 8.0, 60.0)
        val fatMass = weightKg * bf / 100.0
        val lean = 100.0 - bf
        val bonePct = if (male) 4.5 else 4.0
        // Skeletal muscle mass — Janssen et al. 2000 (J Appl Physiol 89:81-88), the reference
        // openScale evaluates MUSCLE against. "Lean minus bone" would be ~85 % and always trip the
        // plausibility check, because openScale's muscle type means skeletal muscle, not fat-free mass.
        val skeletalMuscleKg = 0.244 * weightKg +
            7.8 * (heightCm / 100.0) -
            0.098 * age +
            (if (male) 6.6 else 0.0) - 3.3
        val musclePct = (skeletalMuscleKg / weightKg * 100.0).coerceIn(20.0, 60.0)
        val waterPct = (lean * 0.70).coerceIn(25.0, 80.0)
        val proteinPct = (lean * 0.238).coerceIn(5.0, 35.0)
        val skeletalPct = musclePct.coerceIn(10.0, 70.0)
        val subcutaneousPct = (bf * 0.72).coerceIn(1.0, bf)

        return BodyComposition(
            model = model,
            resistanceOhm = resistance,
            bodyFatPercent = round1(bf),
            fatMassKg = round1(fatMass),
            // Derive LBM from the clamped body fat, not from the raw Segal FFM: when bf is
            // clamped (e.g. <8 % for women or >60 % for anyone) the two would no longer add
            // up to 100 %, and the stored LBM would contradict the stored body-fat percentage.
            fatFreeMassKg = round1(weightKg - fatMass),
            musclePercent = round1(musclePct),
            muscleMassKg = round1(weightKg * musclePct / 100.0),
            waterPercent = round1(waterPct),
            waterMassKg = round1(weightKg * waterPct / 100.0),
            proteinPercent = round1(proteinPct),
            proteinMassKg = round1(weightKg * proteinPct / 100.0),
            bonePercent = round1(bonePct),
            boneMassKg = round1(weightKg * bonePct / 100.0),
            skeletalMusclePercent = round1(skeletalPct),
            skeletalMuscleMassKg = round1(weightKg * skeletalPct / 100.0),
            subcutaneousFatPercent = round1(subcutaneousPct),
        )
    }

    private fun segalFfm(
        weightKg: Double, heightCm: Double, age: Int, male: Boolean, r: Double,
    ): Pair<Double, String> {
        val h2 = heightCm * heightCm
        if (male) {
            var ffm = 9.33285 + 0.00066360 * h2 - 0.02117 * r + 0.62854 * weightKg - 0.12380 * age
            if (100.0 * (weightKg - ffm) / weightKg < 20.0) return ffm to "segal_3a_male_lt20"
            ffm = 14.52435 + 0.00088580 * h2 - 0.02999 * r + 0.42688 * weightKg - 0.07002 * age
            return ffm to "segal_3b_male_ge20"
        } else {
            var ffm = 10.43485 + 0.00064602 * h2 - 0.01397 * r + 0.42087 * weightKg
            if (100.0 * (weightKg - ffm) / weightKg < 30.0) return ffm to "segal_3c_female_lt30"
            ffm = 9.37938 + 0.00091186 * h2 - 0.01466 * r + 0.29990 * weightKg - 0.07012 * age
            return ffm to "segal_3d_female_ge30"
        }
    }

    private fun round1(v: Double): Double = round(v * 10.0) / 10.0

    // ----------------------------------------------------------------------------
    // Low-level helpers
    // ----------------------------------------------------------------------------

    private fun bit(value: Int, bit: Int): Int = if (value and (1 shl bit) != 0) 1 else 0

    private fun u16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun u32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }
}
