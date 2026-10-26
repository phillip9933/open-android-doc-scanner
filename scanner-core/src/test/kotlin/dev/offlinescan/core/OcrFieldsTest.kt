package dev.offlinescan.core

import java.io.File
import java.util.concurrent.CancellationException
import kotlin.test.*

class OcrFieldsTest {
    private val region=OcrRegion(.1,.2,.8,.4)
    private fun field(id:String)=OcrFieldRequest(id,OcrConfig(region=region))

    @Test fun retainsCallerOrderIdsLeadingZerosAndFullImageCoordinates() {
        val seen=mutableListOf<OcrConfig>()
        val progress=mutableListOf<Pair<Int,Int>>()
        val recognizer=TextRecognizer { _,id,config,_ ->
            seen+=config
            OcrPage(id,1000,800,if(seen.size==1) "001234" else "山田 太郎",listOf(OcrWord("value",100,160,800,320,88f)))
        }
        val result=recognizer.recognizeFields(File("not-read"),"front",listOf(field("number"),field("name")),
            progress=Progress { done,total -> progress+=done to total })
        assertEquals(listOf("number","name"),result.map { it.id })
        assertEquals("001234",result[0].page.text)
        assertEquals("山田 太郎",result[1].page.text)
        assertTrue(result.all { it.page.pageId=="front" && it.page.width==1000 && it.page.words[0].left==100 })
        assertEquals(listOf(0 to 2,1 to 2,2 to 2),progress)
    }

    @Test fun rejectsAmbiguousAndUnboundedRequestsBeforeCallingEngine() {
        val recognizer=TextRecognizer { _,_,_,_ -> error("Must not run") }
        assertFailsWith<IllegalArgumentException> { field("") }
        assertFailsWith<IllegalArgumentException> { OcrFieldRequest("field",OcrConfig()) }
        for(fields in listOf(emptyList(),listOf(field("same"),field("same")),(1..33).map {field("$it")})) {
            assertFailsWith<IllegalArgumentException> { recognizer.recognizeFields(File("unused"),"page",fields) }
        }
    }

    @Test fun cancellationStopsBeforeNextFieldAndDoesNotReturnPartialBatch() {
        var cancelled=false; var calls=0
        val recognizer=TextRecognizer { _,id,_,_ -> calls++; OcrPage(id,1,1,"first",emptyList()) }
        assertFailsWith<CancellationException> {
            recognizer.recognizeFields(File("unused"),"page",listOf(field("one"),field("two")),
                Cancellation { if(cancelled) throw CancellationException() },Progress { done,_ -> if(done==1) cancelled=true })
        }
        assertEquals(1,calls)
    }
}
