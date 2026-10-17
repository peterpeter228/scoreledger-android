package com.example.scoreledger.backup

import org.junit.Test
import org.junit.Assert.*
import java.nio.file.Files
import java.io.File
class RollbackRetentionTest {
    @Test fun clockCorrectionCannotEvictJustCompletedSnapshot() {
        val dir=Files.createTempDirectory("rollback-retention").toFile()
        try {
            val now=1000000000L
            repeat(3){File(dir,"future-$it.json").apply{writeText("{}");setLastModified(now+100000+it)}}
            val fresh=File(dir,"fresh.json").apply{writeText("{}");setLastModified(now)}
            val partial=File(dir,"ignored.partial").apply{writeText("incomplete");setLastModified(now)}
            pruneRollbackFiles(dir,now,fresh)
            assertTrue(fresh.isFile);assertTrue(fresh.length()>0);assertEquals(3,dir.listFiles()!!.count{it.extension=="json"});assertTrue(partial.isFile)
        }finally{dir.deleteRecursively()}
    }
}
