package com.android.castappx.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.nio.ByteBuffer

@RunWith(JUnit4::class)
class AvcAccessUnitTest {
    private val sps = byteArrayOf(0x67, 66, 0, 30)
    private val pps = byteArrayOf(0x68, 1, 2)
    private val idr = byteArrayOf(0x65, 3, 4, 5)
    private val start = byteArrayOf(0, 0, 0, 1)

    @Test
    fun separatesSetupAndPictureWithMixedAnnexBStartCodes() {
        val source = start + sps + byteArrayOf(0, 0, 1) + pps + start + idr
        val setup = AvcAccessUnit.configuration(source)!!
        assertArrayEquals(start + sps, setup.sps)
        assertArrayEquals(start + pps, setup.pps)
        assertArrayEquals(start + idr, AvcAccessUnit.picture(source))
    }

    @Test
    fun normalizesLengthPrefixedAccessUnits() {
        val source = ByteBuffer.allocate(12 + sps.size + pps.size + idr.size)
            .putInt(sps.size).put(sps).putInt(pps.size).put(pps).putInt(idr.size).put(idr).array()
        assertArrayEquals(start + sps, AvcAccessUnit.configuration(source)!!.sps)
        assertArrayEquals(start + idr, AvcAccessUnit.picture(source))
    }

    @Test
    fun usesLatestParameterSetsAndPreservesEmulationPreventionBytes() {
        val latest = byteArrayOf(0x67, 100, 0, 31)
        val picture = byteArrayOf(0x65, 0, 0, 3, 1, 4)
        val source = start + sps + start + latest + start + pps + start + picture
        assertArrayEquals(start + latest, AvcAccessUnit.configuration(source)!!.sps)
        assertArrayEquals(start + picture, AvcAccessUnit.picture(source))
    }

    @Test
    fun rejectsMissingSetupAndMalformedLengths() {
        assertNull(AvcAccessUnit.configuration(start + idr))
        assertNull(AvcAccessUnit.configuration(start + sps))
        assertNull(AvcAccessUnit.picture(start + sps + start + pps))
        assertNull(AvcAccessUnit.picture(byteArrayOf(0, 0, 0, 100, 0x65)))
        assertNull(AvcAccessUnit.picture(byteArrayOf(-1, -1, -1, -1)))
        assertNull(AvcAccessUnit.picture(start))
    }

    @Test
    fun acceptsNonKeyPicturesWithoutSetup() {
        val picture = byteArrayOf(0x41, 1, 2, 3)
        assertArrayEquals(start + picture, AvcAccessUnit.picture(start + picture))
    }
}
