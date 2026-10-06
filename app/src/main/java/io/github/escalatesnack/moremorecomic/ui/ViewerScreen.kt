package io.github.escalatesnack.moremorecomic.ui

import android.app.Activity
import android.graphics.Bitmap
import android.util.LruCache
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.escalatesnack.moremorecomic.AppViewModel
import io.github.escalatesnack.moremorecomic.SpreadMode
import io.github.escalatesnack.moremorecomic.data.Book
import io.github.escalatesnack.moremorecomic.data.PageSource
import io.github.escalatesnack.moremorecomic.data.decodeSampled
import io.github.escalatesnack.moremorecomic.data.openPageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** 1ページを絵にするときの画素数の上限(これより大きい画像は縮めて読む) */
private const val PAGE_MAX_PIXELS = 8_000_000L
private const val MAX_ZOOM = 5f

/** この幅(dp)以上の画面では、「自動」のとき見開きにする(Foldを開いた画面が当てはまる) */
private const val DOUBLE_PAGE_MIN_WIDTH_DP = 600

/** タップでページをめくる範囲(画面の左右それぞれ、幅のこの割合) */
private const val TAP_TURN_FRACTION = 0.3f

/** 開いている本のページを絵にして、近くのページの分だけメモリに持っておく */
private class PageLoader(val source: PageSource) {
    private val cache = object : LruCache<Int, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 3).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    ) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
    }

    fun cached(index: Int): Bitmap? = cache.get(index)

    suspend fun load(index: Int): Bitmap? = cache.get(index) ?: withContext(Dispatchers.IO) {
        try {
            decodeSampled(source.readPage(index), PAGE_MAX_PIXELS)?.also { cache.put(index, it) }
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }
}

private class PageState(val bitmap: Bitmap?, val loading: Boolean)

/** ビューア: 本を1冊開いて読む画面 */
@Composable
fun ViewerScreen(vm: AppViewModel, book: Book, onClose: () -> Unit) {
    val context = LocalContext.current
    var loader by remember(book.id) { mutableStateOf<PageLoader?>(null) }
    var failed by remember(book.id) { mutableStateOf(false) }
    var showOverlay by remember(book.id) { mutableStateOf(false) }

    LaunchedEffect(book.id) {
        try {
            loader = withContext(Dispatchers.IO) { PageLoader(openPageSource(context, book)) }
        } catch (_: Exception) {
            failed = true
        }
    }
    DisposableEffect(book.id) {
        onDispose { loader?.source?.close() }
    }

    BackHandler(onBack = onClose)
    ViewerSystemBars(visible = showOverlay)

    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        val current = loader
        when {
            failed || (current != null && current.source.pageCount == 0) -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("この本を開けませんでした", color = Color.White)
                Text(
                    "ファイルが移動・削除されたか、対応していない形式です。",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
                )
                Button(onClick = onClose, shape = RoundedCornerShape(16.dp)) { Text("本棚に戻る") }
            }
            current == null -> CircularProgressIndicator(color = Color.White)
            else -> ViewerContent(
                vm = vm,
                book = book,
                loader = current,
                showOverlay = showOverlay,
                onToggleOverlay = { showOverlay = !showOverlay },
                onClose = onClose,
            )
        }
    }
}

@Composable
private fun ViewerContent(
    vm: AppViewModel,
    book: Book,
    loader: PageLoader,
    showOverlay: Boolean,
    onToggleOverlay: () -> Unit,
    onClose: () -> Unit,
) {
    val pageCount = loader.source.pageCount
    val double = when (vm.spreadMode) {
        SpreadMode.AUTO -> LocalConfiguration.current.screenWidthDp >= DOUBLE_PAGE_MIN_WIDTH_DP
        SpreadMode.SINGLE -> false
        SpreadMode.DOUBLE -> true
    }
    val spreads = remember(pageCount, double) { buildSpreads(pageCount, double) }
    var currentPage by rememberSaveable(book.id) { mutableIntStateOf(vm.lastPage(book).coerceIn(0, pageCount - 1)) }
    val rtl = vm.rightToLeft

    // 単ページ⇔見開きが切り替わったら、今のページが入っているまとまりから作り直す
    key(double) {
        val pagerState = rememberPagerState(initialPage = spreadIndexOf(spreads, currentPage)) { spreads.size }
        val scope = rememberCoroutineScope()
        var zoomedSpread by remember { mutableStateOf<Int?>(null) }

        LaunchedEffect(pagerState, spreads) {
            snapshotFlow { pagerState.settledPage }.collect { index ->
                val spread = spreads.getOrNull(index) ?: return@collect
                // 見開きの左右どちらを読んでいたかは変えない(開閉のたびに1ページ戻らないように)
                if (currentPage !in spread) currentPage = spread.first()
                vm.saveLastPage(book, currentPage)
            }
        }

        fun turn(delta: Int) {
            val target = (pagerState.currentPage + delta).coerceIn(0, spreads.size - 1)
            if (target != pagerState.currentPage) scope.launch { pagerState.animateScrollToPage(target) }
        }

        val latestOverlay by rememberUpdatedState(showOverlay)
        val latestRtl by rememberUpdatedState(rtl)
        val latestToggle by rememberUpdatedState(onToggleOverlay)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(pagerState) {
                    detectTapGestures { position ->
                        val edge = size.width * TAP_TURN_FRACTION
                        when {
                            latestOverlay -> latestToggle()
                            // 右開きは左へ読み進めるので、左側のタップが「次のページ」
                            position.x < edge -> turn(if (latestRtl) 1 else -1)
                            position.x > size.width - edge -> turn(if (latestRtl) -1 else 1)
                            else -> latestToggle()
                        }
                    }
                }
        ) {
            HorizontalPager(
                state = pagerState,
                reverseLayout = rtl,
                userScrollEnabled = zoomedSpread != pagerState.currentPage,
                beyondViewportPageCount = 1,
                key = { spreads[it].first() },
                modifier = Modifier.fillMaxSize(),
            ) { index ->
                SpreadPage(
                    pages = spreads[index],
                    loader = loader,
                    rtl = rtl,
                    isCurrent = pagerState.currentPage == index,
                    onZoomChanged = { zoomed ->
                        if (zoomed) zoomedSpread = index else if (zoomedSpread == index) zoomedSpread = null
                    },
                )
            }

            if (showOverlay) {
                ViewerOverlay(
                    title = book.title,
                    pageCount = pageCount,
                    spreads = spreads,
                    currentSpread = pagerState.currentPage,
                    rtl = rtl,
                    spreadMode = vm.spreadMode,
                    onSeek = { target -> scope.launch { pagerState.scrollToPage(target) } },
                    onToggleDirection = { vm.updateRightToLeft(!rtl) },
                    onCycleSpreadMode = {
                        val modes = SpreadMode.entries
                        vm.updateSpreadMode(modes[(vm.spreadMode.ordinal + 1) % modes.size])
                    },
                    onClose = onClose,
                )
            }
        }
    }
}

/** 1画面分(1枚、または見開きの2枚)。ピンチでの拡大と、拡大中の移動もここで受ける */
@Composable
private fun SpreadPage(
    pages: List<Int>,
    loader: PageLoader,
    rtl: Boolean,
    isCurrent: Boolean,
    onZoomChanged: (Boolean) -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val latestZoomChanged by rememberUpdatedState(onZoomChanged)

    // ページをめくったら、拡大は元に戻す
    LaunchedEffect(isCurrent) {
        if (!isCurrent && scale != 1f) {
            scale = 1f
            offset = Offset.Zero
            latestZoomChanged(false)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        // 拡大していない1本指の動きは触らない(ページめくりのスワイプに任せる)
                        if (fingers >= 2 || scale > 1f) {
                            val zoom = if (fingers >= 2) event.calculateZoom() else 1f
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = false)
                            val newScale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                            if (centroid.isSpecified) {
                                // 指の間の点が、拡大しても同じ場所に残るようにずらす
                                val moved = centroid - (centroid - offset) * (newScale / scale) + pan
                                offset = Offset(
                                    moved.x.coerceIn(size.width * (1f - newScale), 0f),
                                    moved.y.coerceIn(size.height * (1f - newScale), 0f),
                                )
                            }
                            val wasZoomed = scale > 1f
                            scale = newScale
                            if (wasZoomed != (newScale > 1f)) latestZoomChanged(newScale > 1f)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    // ほんの少しだけ拡大が残ると、スワイプでめくれなくなるので等倍に戻す
                    if (scale > 1f && scale < 1.05f) {
                        scale = 1f
                        offset = Offset.Zero
                        latestZoomChanged(false)
                    }
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
                transformOrigin = TransformOrigin(0f, 0f)
            }
    ) {
        if (pages.size == 1) {
            PageImage(pages[0], loader, Alignment.Center, Modifier.fillMaxSize())
        } else {
            // 右開きは、若いページが右側
            val left = if (rtl) pages[1] else pages[0]
            val right = if (rtl) pages[0] else pages[1]
            Row(modifier = Modifier.fillMaxSize()) {
                // 2枚がまん中でぴったり付くように、それぞれまん中側へ寄せる
                PageImage(left, loader, Alignment.CenterEnd, Modifier.weight(1f).fillMaxSize())
                PageImage(right, loader, Alignment.CenterStart, Modifier.weight(1f).fillMaxSize())
            }
        }
    }
}

@Composable
private fun PageImage(index: Int, loader: PageLoader, alignment: Alignment, modifier: Modifier) {
    val state by produceState(PageState(loader.cached(index), loading = true), index, loader) {
        value = PageState(loader.load(index), loading = false)
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        val bitmap = state.bitmap
        when {
            bitmap != null -> Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                alignment = alignment,
                filterQuality = FilterQuality.Medium,
                modifier = Modifier.fillMaxSize(),
            )
            state.loading -> CircularProgressIndicator(color = Color.White.copy(alpha = 0.6f), strokeWidth = 2.5.dp)
            else -> Text("${index + 1}ページを読み込めませんでした", color = Color.White.copy(alpha = 0.7f))
        }
    }
}

/** 画面のまん中をタップすると出る、上下の操作バー */
@Composable
private fun ViewerOverlay(
    title: String,
    pageCount: Int,
    spreads: List<List<Int>>,
    currentSpread: Int,
    rtl: Boolean,
    spreadMode: SpreadMode,
    onSeek: (Int) -> Unit,
    onToggleDirection: () -> Unit,
    onCycleSpreadMode: () -> Unit,
    onClose: () -> Unit,
) {
    val barColor = Color.Black.copy(alpha = 0.72f)
    // バーの上のタップが、下のページ(めくる・バーを消す)まで届かないようにする
    val swallowTaps = Modifier.pointerInput(Unit) { detectTapGestures { } }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shownSpread = (dragging?.roundToInt() ?: currentSpread).coerceIn(0, spreads.size - 1)
    val shownPages = spreads[shownSpread]
    val pageLabel = if (shownPages.size == 1) "${shownPages[0] + 1}" else "${shownPages.first() + 1}-${shownPages.last() + 1}"

    Box(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(barColor)
                .then(swallowTaps)
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "本棚に戻る", tint = Color.White)
            }
            Text(title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 16.dp))
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(barColor)
                .then(swallowTaps)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$pageLabel / $pageCount", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(12.dp))
                if (spreads.size > 1) {
                    // 右開きの本は、つまみも右から左へ進むようにする
                    CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                        Slider(
                            value = dragging ?: currentSpread.toFloat(),
                            onValueChange = { dragging = it },
                            onValueChangeFinished = {
                                dragging?.let { onSeek(it.roundToInt().coerceIn(0, spreads.size - 1)) }
                                dragging = null
                            },
                            valueRange = 0f..(spreads.size - 1).toFloat(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = Color.White.copy(alpha = 0.16f),
                    contentColor = Color.White,
                )
                FilledTonalButton(onClick = onToggleDirection, colors = colors, shape = RoundedCornerShape(14.dp)) {
                    Text(if (rtl) "とじ方: 右開き" else "とじ方: 左開き")
                }
                FilledTonalButton(onClick = onCycleSpreadMode, colors = colors, shape = RoundedCornerShape(14.dp)) {
                    Text("表示: ${spreadMode.label}")
                }
            }
        }
    }
}

/** 読んでいる間は、ステータスバーとナビゲーションバーを隠し、画面が自動で消えないようにする */
@Composable
private fun ViewerSystemBars(visible: Boolean) {
    val view = LocalView.current
    val window = (LocalContext.current as? Activity)?.window ?: return
    val controller = remember(window, view) { WindowCompat.getInsetsController(window, view) }

    DisposableEffect(controller) {
        val wasLightStatus = controller.isAppearanceLightStatusBars
        val wasLightNavigation = controller.isAppearanceLightNavigationBars
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        // バーの背景が黒なので、時計などのアイコンは白にする
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        onDispose {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller.isAppearanceLightStatusBars = wasLightStatus
            controller.isAppearanceLightNavigationBars = wasLightNavigation
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    LaunchedEffect(visible) {
        if (visible) controller.show(WindowInsetsCompat.Type.systemBars())
        else controller.hide(WindowInsetsCompat.Type.systemBars())
    }
}
