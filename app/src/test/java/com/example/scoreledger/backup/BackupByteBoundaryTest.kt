package com.example.scoreledger.backup

import com.example.scoreledger.data.DataSnapshot
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.util.Collections
import java.util.UUID

/** Size boundary independent of text/entity/token maxima. Whitespace is spread over legal tokens. */
class BackupByteBoundaryTest {
    private fun padded(bytes:Long):InputStream {
        val source=requireNotNull(javaClass.classLoader?.getResourceAsStream("backup-valid.json")).use(BackupCodec::read)
        val child=source.children.first().copy(nickname="Synthetic",gradeLabel="G")
        val subject=source.subjects.first().copy(name="Math")
        val exams=(0 until 1000).map { source.exams.first().copy(id=UUID.nameUUIDFromBytes("exam-$it".toByteArray()).toString(),childId=child.id,title="Exam",note="",term="T",gradeSnapshot="G") }
        val scores=exams.map { source.scores.first().copy(examId=it.id,subjectId=subject.id,subjectNameSnapshot="Math") }
        val out=ByteArrayOutputStream();BackupCodec.write(DataSnapshot(listOf(child),listOf(subject),exams,scores),out)
        val raw=out.toString("UTF-8");val pieces=raw.split(',');val gaps=pieces.size-1
        val extra=bytes-out.size();require(extra>=0)
        val each=extra/gaps;val remainder=extra%gaps
        require(each<8000) // Under TOKEN_CHARS even with Gson's lookahead and the field itself.
        val streams=object:java.util.Enumeration<InputStream> {
            var index=0
            override fun hasMoreElements()=index<pieces.size
            override fun nextElement():InputStream {
                val current=index++
                val suffix=if(current<gaps) ","+" ".repeat((each+if(current.toLong()<remainder)1 else 0).toInt()) else ""
                return (pieces[current]+suffix).byteInputStream()
            }
        }
        return SequenceInputStream(streams)
    }
    @Test fun legalStructureExactly96MiBAcceptedAndOneByteOverRejected() {
        padded(BackupCodec.MAX_BYTES).use { assertEquals(1000,BackupCodec.read(it).exams.size) }
        try {padded(BackupCodec.MAX_BYTES+1).use(BackupCodec::read);fail("Byte limit bypassed")}
        catch(e:BackupException){assertEquals(BackupFailure.TOO_LARGE,e.reason)}
    }
}
