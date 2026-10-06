package io.github.escalatesnack.moremorecomic.data

import io.github.escalatesnack.moremorecomic.ui.buildSpreads
import io.github.escalatesnack.moremorecomic.ui.spreadIndexOf
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.nio.charset.Charset
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipArchiveTest {
    private fun open(file: File) = ZipArchive(FileInputStream(file).channel)

    @Test
    fun readsDeflatedAndStoredEntries() {
        val file = File.createTempFile("test", ".zip")
        val big = ByteArray(200_000) { (it % 7).toByte() }
        val small = byteArrayOf(1, 2, 3, 4, 5)
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("本/10.jpg")); zip.write(big); zip.closeEntry()
            val stored = ZipEntry("本/2.jpg").apply {
                method = ZipEntry.STORED
                size = small.size.toLong()
                compressedSize = small.size.toLong()
                crc = CRC32().apply { update(small) }.value
            }
            zip.putNextEntry(stored); zip.write(small); zip.closeEntry()
            zip.setComment("comment at the end")
        }
        open(file).use { archive ->
            assertEquals(listOf("本/10.jpg", "本/2.jpg"), archive.entries.map { it.name })
            assertArrayEquals(big, archive.read(archive.entries[0]))
            assertArrayEquals(small, archive.read(archive.entries[1]))
        }
        file.delete()
    }

    @Test
    fun readsShiftJisNames() {
        val file = File.createTempFile("sjis", ".zip")
        ZipOutputStream(file.outputStream(), Charset.forName("windows-31j")).use { zip ->
            zip.putNextEntry(ZipEntry("漫画/第1話.png")); zip.write(byteArrayOf(9)); zip.closeEntry()
        }
        open(file).use { assertEquals("漫画/第1話.png", it.entries.single().name) }
        file.delete()
    }

    @Test
    fun naturalOrderSortsNumbersAsNumbers() {
        val names = listOf("p10.jpg", "p2.jpg", "P1.jpg", "p002b.jpg", "a/1.jpg")
        assertEquals(listOf("a/1.jpg", "P1.jpg", "p2.jpg", "p002b.jpg", "p10.jpg"), names.sortedWith(NaturalOrder))
    }

    @Test
    fun imageNamesAreFiltered() {
        assertEquals(true, isImageName("a/b/001.JPG"))
        assertEquals(false, isImageName("__MACOSX/a/._001.jpg"))
        assertEquals(false, isImageName("a/.hidden.png"))
        assertEquals(false, isImageName("a/info.txt"))
    }

    @Test
    fun spreadsKeepCoverAlone() {
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3, 4), listOf(5)), buildSpreads(6, double = true))
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), buildSpreads(3, double = false))
        assertEquals(2, spreadIndexOf(buildSpreads(6, double = true), 4))
    }
}
