package io.github.escalatesnack.moremorecomic

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import io.github.escalatesnack.moremorecomic.ui.BookshelfScreen
import io.github.escalatesnack.moremorecomic.ui.ViewerScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // Android標準のフォルダ選択画面。選んだフォルダの中だけ、読む許可をもらえる
                    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                        if (uri != null) vm.importFolder(uri)
                    }
                    val book = vm.openBook
                    if (book != null) {
                        ViewerScreen(vm = vm, book = book, onClose = { vm.openBook = null })
                    } else {
                        BookshelfScreen(vm = vm, onPickFolder = { folderPicker.launch(null) })
                    }
                }
            }
        }
    }
}
