package io.github.escalatesnack.moremorecomic.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import java.io.Closeable
import java.io.FileInputStream
import java.io.IOException

/** 1冊の本のページを、番号で取り出す口。ZIPでも画像フォルダでも同じ使い方にするためのもの */
interface PageSource : Closeable {
    val pageCount: Int

    /** そのページの画像ファイルの中身(まだ絵にしていない生のデータ) */
    fun readPage(index: Int): ByteArray
}

fun openPageSource(context: Context, book: Book): PageSource = when (book.type) {
    BookType.ZIP -> ZipPageSource.open(context, book.uri)
    BookType.FOLDER -> FolderPageSource(context, book.uri)
}

class ZipPageSource private constructor(private val archive: ZipArchive) : PageSource {
    private val pages = archive.entries.filter { isImageName(it.name) }.sortedWith { a, b -> compareNatural(a.name, b.name) }

    override val pageCount: Int get() = pages.size
    override fun readPage(index: Int): ByteArray = archive.read(pages[index])
    override fun close() = archive.close()

    companion object {
        fun open(context: Context, uri: Uri): ZipPageSource {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("ファイルを開けません")
            return try {
                ZipPageSource(ZipArchive(FileInputStream(pfd.fileDescriptor).channel, pfd))
            } catch (e: Exception) {
                pfd.close()
                throw e
            }
        }
    }
}

class FolderPageSource(private val context: Context, folderUri: Uri) : PageSource {
    private val pages: List<Uri> = listChildren(context, folderUri, DocumentsContract.getDocumentId(folderUri))
        .filter { !it.isDirectory && isImageName(it.name) }
        .sortedWith { a, b -> compareNatural(a.name, b.name) }
        .map { DocumentsContract.buildDocumentUriUsingTree(folderUri, it.documentId) }

    override val pageCount: Int get() = pages.size

    override fun readPage(index: Int): ByteArray =
        context.contentResolver.openInputStream(pages[index])?.use { it.readBytes() } ?: throw IOException("ファイルを開けません")

    override fun close() {}
}

/**
 * 画像データを絵にする。元の画像が大きすぎるとメモリを使い切るので、画素数が`maxPixels`以下に
 * なるまで、縦横を半分ずつに縮めて読む。
 */
fun decodeSampled(bytes: ByteArray, maxPixels: Long): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while ((bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > maxPixels) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}
