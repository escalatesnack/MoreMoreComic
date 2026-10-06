package io.github.escalatesnack.moremorecomic.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class BookType { ZIP, FOLDER }

/**
 * 本棚の1冊。`id`は、フォルダ選択画面で許可をもらったフォルダの中での、そのファイル(またはフォルダ)の
 * 場所を表す文字列(document URI)。同じ本を二重に取り込まないための目印にも使う。
 */
data class Book(
    val id: String,
    val title: String,
    val type: BookType,
    val pageCount: Int,
    val addedAt: Long,
) {
    val uri: Uri get() = Uri.parse(id)
}

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heic", "heif")
private val ZIP_EXTENSIONS = setOf("zip", "cbz")

private fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

fun isImageName(name: String): Boolean {
    val base = name.substringAfterLast('/')
    // Macで作ったZIPに混ざる、画像ではない付属ファイルは除く
    if (base.startsWith(".") || name.startsWith("__MACOSX/")) return false
    return extensionOf(base) in IMAGE_EXTENSIONS
}

fun isZipName(name: String): Boolean = extensionOf(name) in ZIP_EXTENSIONS

/** 「2.jpg」が「10.jpg」より前に来るように、数字の部分を数として比べる並べ方 */
fun compareNatural(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca in '0'..'9' && cb in '0'..'9') {
            val si = i
            while (i < a.length && a[i] in '0'..'9') i++
            val sj = j
            while (j < b.length && b[j] in '0'..'9') j++
            val na = a.substring(si, i).trimStart('0')
            val nb = b.substring(sj, j).trimStart('0')
            if (na.length != nb.length) return na.length - nb.length
            val c = na.compareTo(nb)
            if (c != 0) return c
        } else {
            val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (c != 0) return c
            i++
            j++
        }
    }
    return (a.length - i) - (b.length - j)
}

val NaturalOrder: Comparator<String> = Comparator { a, b -> compareNatural(a, b) }

/** 本棚の中身を、アプリ専用の場所にあるファイル(library.json)へ保存・読み込みする */
class LibraryStore(context: Context) {
    private val file = File(context.filesDir, "library.json")

    fun load(): List<Book> {
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { index ->
                val o = array.getJSONObject(index)
                Book(
                    id = o.getString("id"),
                    title = o.getString("title"),
                    type = BookType.valueOf(o.getString("type")),
                    pageCount = o.getInt("pageCount"),
                    addedAt = o.optLong("addedAt"),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(books: List<Book>) {
        val array = JSONArray()
        for (book in books) {
            array.put(
                JSONObject()
                    .put("id", book.id)
                    .put("title", book.title)
                    .put("type", book.type.name)
                    .put("pageCount", book.pageCount)
                    .put("addedAt", book.addedAt)
            )
        }
        // 書いている途中でアプリが止まっても壊れないよう、別名で書いてから入れ替える
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(array.toString())
        tmp.renameTo(file)
    }
}

/** 選んだフォルダの中(下の階層も含む)から、本として読めるものを探す */
object LibraryScanner {
    private const val MAX_DEPTH = 8

    /**
     * ZIP/CBZは1ファイルで1冊。画像が直接入っているフォルダは、そのフォルダで1冊。
     * 見つかるたびに`onFound`を呼ぶ(途中経過を画面に出すため)。
     */
    fun scan(context: Context, treeUri: Uri, onFound: (Book) -> Unit) {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootName = queryName(context, DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)) ?: "フォルダ"
        walk(context, treeUri, rootId, rootName, 0, onFound)
    }

    private fun walk(context: Context, treeUri: Uri, docId: String, name: String, depth: Int, onFound: (Book) -> Unit) {
        val now = System.currentTimeMillis()
        var imageCount = 0
        val folders = ArrayList<Pair<String, String>>()
        for (child in listChildren(context, treeUri, docId)) {
            when {
                child.isDirectory -> folders += child.documentId to child.name
                isImageName(child.name) -> imageCount++
                isZipName(child.name) -> {
                    val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.documentId)
                    val pages = try {
                        ZipPageSource.open(context, uri).use { it.pageCount }
                    } catch (_: Exception) {
                        0
                    }
                    if (pages > 0) {
                        onFound(Book(uri.toString(), child.name.substringBeforeLast('.'), BookType.ZIP, pages, now))
                    }
                }
            }
        }
        if (imageCount > 0) {
            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            onFound(Book(uri.toString(), name, BookType.FOLDER, imageCount, now))
        }
        if (depth < MAX_DEPTH) {
            for ((childId, childName) in folders) walk(context, treeUri, childId, childName, depth + 1, onFound)
        }
    }

    private fun queryName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (_: Exception) {
        null
    }
}

class ChildDocument(val documentId: String, val name: String, val isDirectory: Boolean)

/** フォルダの直下にあるものの一覧 */
fun listChildren(context: Context, treeUri: Uri, parentDocumentId: String): List<ChildDocument> {
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
    val columns = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
    )
    val result = ArrayList<ChildDocument>()
    try {
        context.contentResolver.query(childrenUri, columns, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                result += ChildDocument(id, name, c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR)
            }
        }
    } catch (_: Exception) {
        // 読めないフォルダは空として扱う
    }
    return result
}
