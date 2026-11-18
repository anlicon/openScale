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

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AfuA1LibTest {

    private fun hex(s: String) = s.removePrefix("0x").filterNot { it.isWhitespace() }
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun buildTimeSyncUserFrame_hasCorrectHeaderAndChecksum() {
        val frame = AfuA1Lib.buildTimeSyncUserFrame(
            epochSeconds = 1_700_000_000L, // 0x6553F100
            deviceType = 0x27,
            age = 30,
            heightCm = 170,
            sexValue = 1,
            unit = 0,
            utcQuarterOffset = 32, // +8h / 15min
        )
        assertThat(frame).hasLength(20)
        assertThat(frame[0]).isEqualTo(0xAC.toByte())
        assertThat(frame[1]).isEqualTo(0x27.toByte())
        // timestamp big-endian
        assertThat(frame[2]).isEqualTo(0x65.toByte())
        assertThat(frame[3]).isEqualTo(0x53.toByte())
        assertThat(frame[4]).isEqualTo(0xF1.toByte())
        assertThat(frame[5]).isEqualTo(0x00.toByte())
        assertThat(frame[6]).isEqualTo(32.toByte()) // utc quarter
        assertThat(frame[7]).isEqualTo(0)           // kg
        assertThat(frame[8]).isEqualTo(1)           // user slot
        assertThat(frame[9]).isEqualTo(170.toByte()) // height
        assertThat(frame[12]).isEqualTo(30.toByte()) // age
        assertThat(frame[13]).isEqualTo(1.toByte())  // male
        assertThat(frame[16]).isEqualTo(0x03.toByte()) // function flags
        assertThat(frame[18]).isEqualTo(0xD0.toByte())
        // checksum = sum(frame[2..18]) & 0xFF
        val sum = (2..18).sumOf { frame[it].toInt() and 0xFF } and 0xFF
        assertThat(frame[19].toInt() and 0xFF).isEqualTo(sum)
    }

    @Test
    fun decodeWeight_stableFrame_yieldsKgAndStableFlag() {
        // Synthesise a 20-byte NOTIFY packet for a stable 70.00 kg reading.
        // Wire 0xD5 packet layout (first[0]=type, first[1]=seq):
        //   [0]=0xD5 [1]=0x00 [2..]=body18  where body18[16]=type, body18[17]=checksum|group
        val encoded = 70_000 // grams, kg_division=0 (0.01kg), state bit not set yet
        val stableEncoded = encoded or (1 shl 31)
        val body18 = ByteArray(18)
        // body18[0..3] = encoded big-endian; payload[1..4] maps from body18[0..3]
        body18[0] = ((stableEncoded ushr 24) and 0xFF).toByte()
        body18[1] = ((stableEncoded ushr 16) and 0xFF).toByte()
        body18[2] = ((stableEncoded ushr 8) and 0xFF).toByte()
        body18[3] = (stableEncoded and 0xFF).toByte()
        body18[4] = 2                    // payload[5] = mode
        // payload[10] (charging flag in bit 7, charge level in bits 0..6) maps from body18[9]
        body18[9] = 40                   // battery 40%, not charging
        // payload[16]=alg_type, payload[17]=unit -> body18[15], body18[16]
        body18[15] = 41
        body18[16] = AfuA1Lib.PACKET_WEIGHT.toByte()
        val checksum = (0 until 17).sumOf { body18[it].toInt() and 0xFF } and 0x1F
        body18[17] = checksum.toByte()   // group index 0

        val packet = ByteArray(20)
        packet[0] = AfuA1Lib.PACKET_WEIGHT.toByte()
        packet[1] = 0
        System.arraycopy(body18, 0, packet, 2, 18)

        val decoder = AfuA1Lib.Scale27Decoder()
        val result = decoder.feed(packet)
        assertThat(result.status).isEqualTo(AfuA1Lib.FeedStatus.PERFECT)
        assertThat(result.events).hasSize(1)
        val w = result.events[0] as AfuA1Lib.ScaleEvent.Weight
        assertThat(w.weightKg).isWithin(0.05).of(70.0)
        assertThat(w.stable).isTrue()
        assertThat(w.batteryPercent).isEqualTo(40)
    }

    @Test
    fun normalizeImpedances_deOffsetsHighRawValues() {
        val out = AfuA1Lib.normalizeImpedances(listOf(506.0, 465.0), weightKg = 58.1)
        // values below 1500 pass through unchanged
        assertThat(out[0]).isEqualTo(506.0)
        assertThat(out[1]).isEqualTo(465.0)

        val high = AfuA1Lib.normalizeImpedances(listOf(2000.0), weightKg = 60.0)
        val expected = (((2000.0 - 1000.0) + ((60.0 * 10.0) * -0.4)) / 0.6) / 10.0
        assertThat(high[0]).isWithin(0.01).of(kotlin.math.round(expected * 100) / 100.0)
    }

    @Test
    fun estimateBodyComposition_returnsPlausibleRanges() {
        val comp = AfuA1Lib.estimateBodyComposition(
            weightKg = 58.1, heightCm = 170.0, age = 23, male = true,
            resistances = listOf(506.0, 465.0),
        )
        assertThat(comp).isNotNull()
        assertThat(comp!!.bodyFatPercent).isAtLeast(3.0)
        assertThat(comp.bodyFatPercent).isAtMost(60.0)
        assertThat(comp.waterPercent).isAtLeast(25.0)
        assertThat(comp.waterPercent).isAtMost(80.0)
        assertThat(comp.musclePercent).isAtLeast(20.0)
        assertThat(comp.musclePercent).isAtMost(95.0)
        assertThat(comp.fatFreeMassKg).isLessThan(58.1)
        assertThat(comp.model).contains("male")
    }

    @Test
    fun estimateBodyComposition_withoutResistance_fallsBackToBmi() {
        val comp = AfuA1Lib.estimateBodyComposition(
            weightKg = 70.0, heightCm = 175.0, age = 40, male = false,
            resistances = emptyList(),
        )
        assertThat(comp).isNotNull()
        assertThat(comp!!.model).isEqualTo("deurenberg_bmi_fallback")
        assertThat(comp.resistanceOhm).isNull()
    }
}
