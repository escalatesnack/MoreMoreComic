package io.github.escalatesnack.moremorecomic.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * 本棚に出す表紙(1ページ目を小さくした絵)。毎回ZIPを開くと遅いので、いちど作ったものを
 * アプリの一時保存場所(cacheDir/covers)に置き、表示中のものはメモリにも持つ。
 */
object CoverCache {
    private const val COVER_MAX_PIXELS = 300_000L

    private val memory = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    // 同時にたくさんのZIPを開かないようにする
    private val gate = Semaphore(3)

    fun cached(book: Book): Bitmap? = memory.get(book.id)

    suspend fun load(context: Context, book: Book): Bitmap? {
        memory.get(book.id)?.let { return it }
        return withContext(Dispatchers.IO) {
            gate.withPermit {
                val file = fileFor(context, book)
                val bitmap = try {
                    if (file.exists()) {
                        BitmapFactory.decodeFile(file.path)
                    } else {
                        openPageSource(context, book).use { source ->
                            if (source.pageCount == 0) null else decodeSampled(source.readPage(0), COVER_MAX_PIXELS)
                        }?.also { made ->
                            file.parentFile?.mkdirs()
                            file.outputStream().use { made.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                        }
                    }
                } catch (_: Exception) {
                    null
                }
                bitmap?.also { memory.put(book.id, it) }
            }
        }
    }

    fun remove(context: Context, book: Book) {
        memory.remove(book.id)
        fileFor(context, book).delete()
    }

    private fun fileFor(context: Context, book: Book): File {
        val digest = MessageDigest.getInstance("MD5").digest(book.id.toByteArray())
        val name = digest.joinToString("") { "%02x".format(it) }
        return File(File(context.cacheDir, "covers"), "$name.jpg")
    }
}
