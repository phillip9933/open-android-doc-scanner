package dev.offlinescan.core

import kotlin.test.*
import java.io.File
import java.nio.file.Files

class CoreTest {
    @Test fun validatesConvexNonIntersectingGeometry() {
        assertTrue(Geometry.valid(Quad.FULL))
        assertFalse(Geometry.valid(Quad(Point(0.0,0.0),Point(1.0,1.0),Point(1.0,0.0),Point(0.0,1.0))))
        assertFalse(Geometry.valid(Quad(Point(-0.1,0.0),Point(1.0,0.0),Point(1.0,1.0),Point(0.0,1.0))))
        assertFalse(Geometry.valid(Quad(Point(0.0,0.0),Point(0.01,0.0),Point(0.01,0.01),Point(0.0,0.01))))
        assertFalse(Geometry.valid(Quad(Point(Double.NaN,0.0),Point(1.0,0.0),Point(1.0,1.0),Point(0.0,1.0))))
        assertFailsWith<ScanException> { Geometry.requireValid(Quad(Point(0.0,0.0),Point(0.0,1.0),Point(1.0,1.0),Point(1.0,0.0))) }
    }
    @Test fun previewRoundTripAndRotation() {
        for (turn in -4..4) { val p=Point(0.2,0.7); val restored=Geometry.rotate(Geometry.rotate(p,turn),-turn); assertEquals(p.x,restored.x,1e-10); assertEquals(p.y,restored.y,1e-10) }
        for ((iw,ih) in listOf(4000.0 to 3000.0,3000.0 to 4000.0)) {
            for(p in listOf(Point(0.0,0.0),Point(0.23,0.87),Point(1.0,1.0))) {
                val back=Geometry.fromPreview(Geometry.preview(p,iw,ih,1080.0,1920.0),iw,ih,1080.0,1920.0)
                assertEquals(p.x,back.x,1e-10); assertEquals(p.y,back.y,1e-10)
            }
        }
    }
    private val processor=object: ImageProcessor {
        override fun inspect(source:File,config:ScanConfig)=100 to 200
        override fun detect(source:File,config:ScanConfig,cancellation:Cancellation)=Detection(null,0.0,0.5,0.0,emptyList())
        override fun render(source:File,edits:Edits,destination:File,format:ExportFormat,config:ScanConfig,cancellation:Cancellation):ExportedPage=error("unused")
    }
    @Test fun sessionPreservesUserSourcesAndOwnsOnlyItsDirectory() {
        val root=Files.createTempDirectory("scanner-test").toFile()
        try {
            val input=File(root,"host-original.jpg").apply { writeText("fixture") }
            val hostOutput=File(root,"completed.jpg").apply { writeText("kept") }
            val session=ScanSession(ScanConfig(maxPages=2),root,processor)
            val first=session.`import`(input); val second=session.`import`(input)
            assertNotEquals(input.canonicalPath,first.original.canonicalPath)
            session.move(second.id,0); assertEquals(second.id,session.pages.first().id)
            session.update(first.id,Edits(rotationQuarterTurns=1)); assertEquals(1,session.pages.last().edits.rotationQuarterTurns)
            assertFailsWith<ScanException> { session.`import`(input) }
            val replacement=session.replace(first.id,input); assertFalse(first.original.exists()); assertTrue(replacement.original.exists())
            session.remove(second.id); assertFalse(second.original.exists())
            session.close(); session.close(); assertFalse(session.directory.exists()); assertTrue(input.exists()); assertTrue(hostOutput.exists())
            assertFailsWith<IllegalStateException> { session.`import`(input) }
        } finally { root.deleteRecursively() }
    }
    @Test fun failedImportLeavesNoOwnedFiles() {
        val root=Files.createTempDirectory("scanner-failure").toFile()
        try {
            val rejecting=object:ImageProcessor by processor { override fun inspect(source:File,config:ScanConfig):Pair<Int,Int> = throw ScanException(ScanError(ErrorCode.INVALID_IMAGE,"bad")) }
            ScanSession(ScanConfig(),root,rejecting).use { session ->
                val input=File(root,"bad.jpg").apply { writeText("bad") }
                assertFailsWith<ScanException> { session.`import`(input) }; assertTrue(session.directory.listFiles()!!.isEmpty()); assertTrue(input.exists())
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun modesAndLimitsAreExplicit() {
        assertEquals(Preset.PHOTO,ScanConfig(mode=ScanMode.PHOTO).defaultPreset)
        assertEquals(2,ScanConfig(mode=ScanMode.CARD,cardFrontBack=true).pageLimit)
        assertFailsWith<IllegalArgumentException> { ScanConfig(maxOutputDimension=100_000) }
    }
}
