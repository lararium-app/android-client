package app.lararium.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Unit tests verifying Vosk model archive zip-slip defense (finding L-18).
 *
 * Enforces the zip-slip law:
 * - Reject entries escaping the destination directory ("../evil")
 * - Reject absolute path entries ("/abs")
 * - Allow safe, benign relative entries
 * - Verify full archive extraction with benign entries and abort on zip-slip
 */
class VoskZipExtractionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun validateZipEntry_rejectsParentTraversal() {
        val destDir = tempFolder.newFolder("dest")
        val ex = runCatching {
            VoskTranscriber.validateZipEntry(destDir, "../evil")
        }.exceptionOrNull()
        assertNotNull("Entry with ../evil must be rejected", ex)
        assertTrue(ex is IOException)
    }

    @Test
    fun validateZipEntry_rejectsAbsolutePath() {
        val destDir = tempFolder.newFolder("dest")
        val ex = runCatching {
            VoskTranscriber.validateZipEntry(destDir, "/abs")
        }.exceptionOrNull()
        assertNotNull("Absolute path entry /abs must be rejected", ex)
        assertTrue(ex is IOException)
    }

    @Test
    fun validateZipEntry_rejectsBackslashAbsolutePath() {
        val destDir = tempFolder.newFolder("dest")
        val ex = runCatching {
            VoskTranscriber.validateZipEntry(destDir, "\\abs")
        }.exceptionOrNull()
        assertNotNull("Windows-style absolute path entry must be rejected", ex)
        assertTrue(ex is IOException)
    }

    @Test
    fun validateZipEntry_allowsBenignRelativeEntry() {
        val destDir = tempFolder.newFolder("dest")
        val target = VoskTranscriber.validateZipEntry(destDir, "model/am/final.mdl")
        assertEquals(File(destDir, "model/am/final.mdl").canonicalPath, target.canonicalPath)
    }

    @Test
    fun benignExtractionWorks() {
        val destDir = tempFolder.newFolder("extract_dest")
        val zipFile = tempFolder.newFile("benign.zip")

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry("model-dir/"))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("model-dir/test.txt"))
            zos.write("hello vosk".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }

        var reportedProgress = 0
        VoskTranscriber.extractZipWithProgress(zipFile, destDir) { progress ->
            reportedProgress = progress
        }

        val extracted = File(destDir, "model-dir/test.txt")
        assertTrue("Extracted file should exist", extracted.exists())
        assertEquals("hello vosk", extracted.readText())
        assertTrue("Progress should have been reported", reportedProgress > 0)
    }

    @Test
    fun maliciousZipSlipExtractionRefused() {
        val destDir = tempFolder.newFolder("extract_slip")
        val zipFile = tempFolder.newFile("malicious.zip")

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry("../evil.txt"))
            zos.write("payload".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }

        val ex = runCatching {
            VoskTranscriber.extractZipWithProgress(zipFile, destDir)
        }.exceptionOrNull()

        assertNotNull("Zip slip extraction must throw IOException", ex)
        assertTrue(ex is IOException)
        assertFalse("Escaped file must not be written", File(destDir.parentFile, "evil.txt").exists())
    }
}
