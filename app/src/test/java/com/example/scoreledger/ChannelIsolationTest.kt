package com.example.scoreledger
import org.junit.Test
import org.junit.Assert.assertTrue
class ChannelIsolationTest {
    @Test fun internalPackageMustBeVisiblySeparate() {
        if (BuildConfig.INTERNAL_BETA) assertTrue(BuildConfig.APPLICATION_ID.contains(".beta"))
    }
}
