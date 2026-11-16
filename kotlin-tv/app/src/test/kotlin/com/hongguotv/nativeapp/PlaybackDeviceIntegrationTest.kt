package com.hongguotv.nativeapp

import com.hongguotv.core.PlaybackDeviceRegistrationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], qualifiers = "w960dp-h540dp-land-mdpi")
class PlaybackDeviceIntegrationTest {
    @Test
    fun registrationFailureHasItsOwnSafeCategory() {
        val error =
            PlaybackDeviceRegistrationException(java.io.IOException("test-only private response"))
        assertEquals("播放设备注册失败", playbackFailureSummary(error))
        assertFalse(playbackFailureSummary(error).contains("private response"))
    }
}
