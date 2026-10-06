package io.github.escalatesnack.moremorecomic.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.escalatesnack.moremorecomic.AppViewModel
import io.github.escalatesnack.moremorecomic.data.Book
import io.github.escalatesnack.moremorecomic.data.CoverCache

/** 本棚: 取り込んだ本の表紙を並べる。タップで開く、長押しで本棚から外す */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfScreen(vm: AppViewModel, onPickFolder: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    var removeTarget by remember { mutableStateOf<Book?>(null) }

    LaunchedEffect(vm.message) {
        val text = vm.message ?: return@LaunchedEffect
        vm.message = null
        snackbar.showSnackbar(text)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("本棚") },
                actions = {
                    if (vm.scanning) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 16.dp).size(22.dp), strokeWidth = 2.5.dp)
                    } else {
                        IconButton(onClick = onPickFolder) {
                            Icon(Icons.Filled.CreateNewFolder, contentDescription = "フォルダから取り込む")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                vm.books.isEmpty() && vm.scanning -> ScanningMessage(vm.scanFound)
                vm.books.isEmpty() -> EmptyShelf(onPickFolder)
                else -> Column {
                    if (vm.scanning) {
                        Text(
                            "取り込み中… ${vm.scanFound}冊",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 108.dp),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(vm.books, key = { it.id }) { book ->
                            BookCell(
                                book = book,
                                lastPage = vm.positions[book.id],
                                onOpen = { vm.openBook = book },
                                onLongPress = { removeTarget = book },
                            )
                        }
                    }
                }
            }
        }
    }

    removeTarget?.let { book ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("本棚から外す") },
            text = { Text("「${book.title}」を本棚から外します。元のファイルは消えません。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeBook(book)
                    removeTarget = null
                }) { Text("外す") }
            },
            dismissButton = { TextButton(onClick = { removeTarget = null }) { Text("キャンセル") } },
        )
    }
}

@Composable
private fun EmptyShelf(onPickFolder: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("まだ本がありません", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "漫画のZIP/CBZや画像フォルダが入っているフォルダを選ぶと、中の本をまとめて取り込みます。",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onPickFolder, shape = RoundedCornerShape(16.dp)) { Text("フォルダを選ぶ") }
    }
}

@Composable
private fun ScanningMessage(found: Int) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text("取り込み中… ${found}冊")
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCell(book: Book, lastPage: Int?, onOpen: () -> Unit, onLongPress: () -> Unit) {
    val context = LocalContext.current
    val cover by produceState(CoverCache.cached(book), book.id) {
        if (value == null) value = CoverCache.load(context, book)
    }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.7f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            cover?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // 読みかけの本は、表紙の下の端にどこまで読んだかの線を出す
            if (lastPage != null && book.pageCount > 0) {
                val fraction = ((lastPage + 1).toFloat() / book.pageCount).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(fraction)
                        .height(4.dp)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
        Text(
            book.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 6.dp),
        )
    }
}
