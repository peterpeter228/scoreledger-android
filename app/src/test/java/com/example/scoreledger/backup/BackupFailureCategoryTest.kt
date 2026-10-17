package com.example.scoreledger.backup

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InputStream

class BackupFailureCategoryTest {
    private fun fixture()=requireNotNull(javaClass.classLoader?.getResourceAsStream("backup-valid.json")).use{it.readBytes()}.toString(Charsets.UTF_8)
    private fun reason(input:InputStream):BackupFailure {
        try {BackupCodec.read(input);fail("Expected rejection")}catch(e:BackupException){return e.reason}
        error("unreachable")
    }
    @Test fun unsupportedVersionIsDistinctFromCorruptionAndProviderFailure() {
        assertEquals(BackupFailure.VERSION,reason(fixture().replace("\"schemaVersion\": 1","\"schemaVersion\": 2").byteInputStream()))
        assertEquals(BackupFailure.INVALID,reason("{".byteInputStream()))
        assertEquals(BackupFailure.INVALID,reason(byteArrayOf(0xc3.toByte(),0x28).inputStream()))
        assertEquals(BackupFailure.ACCESS,reason(object:InputStream(){override fun read():Int=throw IOException("synthetic provider failure")}))
    }
}
