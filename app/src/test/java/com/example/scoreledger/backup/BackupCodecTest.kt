package com.example.scoreledger.backup

import org.junit.Test
import org.junit.Assert.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

class BackupCodecTest {
    private fun fixture(name:String="backup-valid.json")=requireNotNull(javaClass.classLoader?.getResourceAsStream(name)).use { it.readBytes() }
    private fun parse(text:String)=BackupCodec.read(text.byteInputStream())
    private fun rejected(text:String) { try { parse(text);fail("Accepted hostile input") } catch(_:BackupException){} }
    @Test fun sharedFixturesAndExactRoundtrip() {
        listOf("backup-empty.json","backup-valid.json").forEach { name ->
            val original=BackupCodec.read(ByteArrayInputStream(fixture(name)));val bytes=ByteArrayOutputStream();BackupCodec.write(original,bytes)
            assertEquals(original,BackupCodec.read(ByteArrayInputStream(bytes.toByteArray())))
        }
    }
    @Test fun strictLexicalHostileCorpus() {
        val text=fixture().toString(Charsets.UTF_8)
        listOf("true","\"8000\"","8e3","8000.0","9223372036854775808","-0","+1","01","NaN","Infinity").forEach { rejected(text.replaceFirst("8000",it)) }
        listOf("TRUE","FALSE","True","1","\"false\"").forEach { rejected(text.replaceFirst("false",it)) }
        rejected(text.replaceFirst("\"format\":", "\"format\":\"scoreledger-backup\",\"f\\u006frmat\":"))
        rejected(text.replaceFirst("\"note\": \"\"","\"note\": \"\\uD800\""))
        rejected(text.replaceFirst("\"note\": \"\"","\"note\": \"\\uDC00\""))
        rejected(text.replaceFirst("\"note\": \"\"","\"note\": \"\\x20\""))
        rejected(text.replaceFirst("\"note\": \"\"","\"note\": \"raw\ncontrol\""))
        rejected(text+"{}")
        rejected(text.replaceFirst("\"format\"", "\"unknown\""))
        rejected(text.replaceFirst("\"schemaVersion\": 1", "\"schemaVersion\": 2"))
        rejected("[".repeat(10000)+"]".repeat(10000))
        rejected("{\"format\":\""+"a".repeat(1000000)+"\"}")
        rejected("{\""+"a".repeat(1000000)+"\":1}")
        rejected(" ".repeat(1000000)+text)
    }
    @Test fun invalidUtf8AndEarlyScalarBudget() {
        try { BackupCodec.read(ByteArrayInputStream(byteArrayOf(0xC3.toByte(),0x28)));fail() }catch(_:BackupException){}
        var consumed=0
        val huge=object:InputStream(){val prefix="{\"format\":\"".toByteArray();override fun read():Int {val n=consumed++;return if(n<prefix.size)prefix[n].toInt() else 'a'.code}}
        try { BackupCodec.read(huge);fail() }catch(_:BackupException){}
        assertTrue("Must reject far before 96MiB",consumed<65536)
    }
    @Test fun maximumUnicodeNoteFutureDateWhitespaceAndDuplicateNamesPreserved() {
        val text=fixture().toString(Charsets.UTF_8).replaceFirst("\"note\": \"\"","\"note\": \""+"\\uD83D\\uDE00".repeat(500)+"\"")
            .replaceFirst("2026-09-15","2099-12-31").replace("孩子A（示例）","  孩子A  ").replace("数学","语文")
        val parsed=parse(text);assertEquals(1000,parsed.exams.first().note.length);assertEquals("  孩子A  ",parsed.children.first().nickname)
        val out=ByteArrayOutputStream();BackupCodec.write(parsed,out);assertEquals(parsed,BackupCodec.read(ByteArrayInputStream(out.toByteArray())))
        parse("\uFEFF"+fixture("backup-empty.json").toString(Charsets.UTF_8))
        rejected(text.replace("  孩子A  ","\\u0085\\u00a0"))
    }
    @Test fun byteCapAndFailedCloseReported() {
        assertEquals(100663296L,BackupCodec.MAX_BYTES)
        val valid=BackupCodec.read(ByteArrayInputStream(fixture("backup-empty.json")))
        val output=object:ByteArrayOutputStream(){override fun close(){throw java.io.IOException("synthetic")}}
        try { BackupCodec.write(valid,output);fail() }catch(_:java.io.IOException){}
    }
}
