package io.github.escalatesnack.moremorecomic

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.escalatesnack.moremorecomic.data.Book
import io.github.escalatesnack.moremorecomic.data.CoverCache
import io.github.escalatesnack.moremorecomic.data.LibraryScanner
import io.github.escalatesnack.moremorecomic.data.LibraryStore
import io.github.escalatesnack.moremorecomic.data.compareNatural
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** ページの並べ方: 自動(画面の幅で決める)/いつも1ページ/いつも見開き */
enum class SpreadMode(val label: String) {
    AUTO("自動"),
    SINGLE("単ページ"),
    DOUBLE("見開き"),
}

/**
 * アプリ全体の状態(本棚の中身、開いている本、設定)。画面の開閉や回転で作り直されないよう、
 * 画面(Activity)とは別に持つ。
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = LibraryStore(app)
    private val settings = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val positionPrefs = app.getSharedPreferences("positions", Context.MODE_PRIVATE)

    var books by mutableStateOf(emptyList<Book>())
        private set

    /** 最後に読んでいたページ(0始まり)。本棚の進み具合の表示にも使う */
    val positions = mutableStateMapOf<String, Int>()

    var openBook by mutableStateOf<Book?>(null)

    var scanning by mutableStateOf(false)
        private set
    var scanFound by mutableIntStateOf(0)
        private set

    /** 画面の下に一度だけ出すお知らせ */
    var message by mutableStateOf<String?>(null)

    /** 右開き(日本の漫画。右から左へ読み進める)ならtrue */
    var rightToLeft by mutableStateOf(settings.getBoolean("rightToLeft", true))
        private set

    var spreadMode by mutableStateOf(
        runCatching { SpreadMode.valueOf(settings.getString("spreadMode", null) ?: "") }.getOrDefault(SpreadMode.AUTO)
    )
        private set

    init {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { store.load() }
            for (book in loaded) {
                val page = positionPrefs.getInt(book.id, -1)
                if (page >= 0) positions[book.id] = page
            }
            books = sorted(loaded)
        }
    }

    fun updateRightToLeft(value: Boolean) {
        rightToLeft = value
        settings.edit().putBoolean("rightToLeft", value).apply()
    }

    fun updateSpreadMode(value: SpreadMode) {
        spreadMode = value
        settings.edit().putString("spreadMode", value.name).apply()
    }

    /** フォルダ選択画面で選んだフォルダから、まだ本棚に無い本を取り込む */
    fun importFolder(treeUri: Uri) {
        if (scanning) return
        val context = getApplication<Application>()
        // アプリを閉じたあとも読めるよう、このフォルダへの読み取りの許可を覚えておいてもらう
        try {
            context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            message = "このフォルダは読み取りの許可を保存できませんでした"
            return
        }
        scanning = true
        scanFound = 0
        viewModelScope.launch {
            val known = books.map { it.id }.toSet()
            val found = ArrayList<Book>()
            try {
                withContext(Dispatchers.IO) {
                    LibraryScanner.scan(context, treeUri) { book ->
                        if (book.id !in known && found.none { it.id == book.id }) {
                            found += book
                            scanFound = found.size
                        }
                    }
                }
            } catch (_: Exception) {
                // 途中で読めなくなっても、そこまでに見つけた分は取り込む
            }
            if (found.isNotEmpty()) {
                books = sorted(books + found)
                persist()
            }
            scanning = false
            message = if (found.isEmpty()) "新しい本は見つかりませんでした" else "${found.size}冊を追加しました"
        }
    }

    /** 本棚から外す(元のファイルは消さない) */
    fun removeBook(book: Book) {
        books = books.filterNot { it.id == book.id }
        positions.remove(book.id)
        positionPrefs.edit().remove(book.id).apply()
        CoverCache.remove(getApplication(), book)
        persist()
    }

    fun lastPage(book: Book): Int = positions[book.id] ?: 0

    fun saveLastPage(book: Book, page: Int) {
        if (positions[book.id] == page) return
        positions[book.id] = page
        positionPrefs.edit().putInt(book.id, page).apply()
    }

    private fun sorted(list: List<Book>): List<Book> = list.sortedWith { a, b -> compareNatural(a.title, b.title) }

    private fun persist() {
        val snapshot = books
        viewModelScope.launch(Dispatchers.IO) { store.save(snapshot) }
    }
}
