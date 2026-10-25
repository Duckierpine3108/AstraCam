package com.astracam.dualdevice

import com.astracam.dualdevice.device.DeviceProfile
import com.astracam.dualdevice.device.DeviceProfileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfileTest {

    @Test
    fun testH18WatchProfileAttributes() {
        val profile = DeviceProfile.h18WatchProfile()
        assertEquals(DeviceProfileType.H18_WATCH, profile.type)
        assertTrue("H18 must have round screen flag enabled", profile.isRoundScreen)
        assertFalse("H18 must disable multi-frame HDR to preserve resources", profile.enableMultiFrameHdr)
        assertEquals(1280, profile.maxProcessingDimension)
        assertEquals(48, profile.memoryBudgetMb)
    }

    @Test
    fun testRedmiNote9SProfileAttributes() {
        val profile = DeviceProfile.redmiNote9SProfile()
        assertEquals(DeviceProfileType.REDMI_NOTE_9S, profile.type)
        assertFalse("Redmi Note 9S is not a round screen device", profile.isRoundScreen)
        assertTrue("Redmi Note 9S must support multi-frame exposure HDR", profile.enableMultiFrameHdr)
        assertEquals(4000, profile.maxProcessingDimension)
        assertEquals(256, profile.memoryBudgetMb)
    }
}