package io.github.escalatesnack.moremorecomic.ui

/**
 * ページ番号(0始まり)を、1画面に出すまとまりに分ける。
 * 単ページなら1枚ずつ。見開きなら、表紙(最初のページ)だけ1枚で、あとは2枚ずつ。
 */
fun buildSpreads(pageCount: Int, double: Boolean): List<List<Int>> {
    if (pageCount <= 0) return emptyList()
    if (!double) return List(pageCount) { listOf(it) }
    val result = ArrayList<List<Int>>()
    result += listOf(0)
    var page = 1
    while (page < pageCount) {
        result += if (page + 1 < pageCount) listOf(page, page + 1) else listOf(page)
        page += 2
    }
    return result
}

/** そのページが入っているまとまりの番号 */
fun spreadIndexOf(spreads: List<List<Int>>, page: Int): Int =
    spreads.indexOfFirst { page in it }.coerceAtLeast(0)
