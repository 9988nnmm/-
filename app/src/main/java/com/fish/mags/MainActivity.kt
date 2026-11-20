package com.fish.mags

import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.palette.graphics.Palette
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.fish.mags.glass.R
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

const val BASE = "http://192.168.1.14:8090"

// 顶层常量：pointerInput 的 key 必须是稳定对象，否则每次重组都会重建手势
private val SORT_OPTS_UI = listOf(
    "seed" to "种子", "size" to "大小", "date" to "时间", "name" to "名称"
)

// ---------------- 数据层 ----------------

data class Item(
    val hash: String, val title: String, val size: String, val seed: Int,
    val leech: Int, val date: String, val site: String, val magnet: String,
    val sub: String, val fav: Boolean, val added: Boolean,
    val note: String = "", val busy: Boolean = false
)

suspend fun api(path: String): JSONObject = withContext(Dispatchers.IO) {
    val conn = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
        connectTimeout = 12000
        readTimeout = 100000
    }
    try {
        val txt = conn.inputStream.bufferedReader().use { it.readText() }
        JSONObject(txt)
    } finally {
        conn.disconnect()
    }
}

suspend fun doSearch(q: String, sort: String, page: Int): Pair<List<Item>, JSONObject> {
    val enc = URLEncoder.encode(q, "UTF-8")
    val o = api("/api/search?q=$enc&sort=$sort&page=$page")
    val arr = o.optJSONArray("items") ?: return emptyList<Item>() to o
    val list = ArrayList<Item>(arr.length())
    for (i in 0 until arr.length()) {
        val it = arr.getJSONObject(i)
        list.add(
            Item(
                hash = it.optString("hash"),
                title = it.optString("title"),
                size = it.optString("size"),
                seed = it.optInt("seed"),
                leech = it.optInt("leech"),
                date = it.optString("date"),
                site = it.optString("site"),
                magnet = it.optString("magnet"),
                sub = it.optString("sub"),
                fav = it.optBoolean("fav"),
                added = it.optBoolean("added")
            )
        )
    }
    return list to o
}

// ---------------- UI ----------------

/**
 * 从背景图里取出强调色 —— 和 Flowmix 一样"跟随壁纸"。
 * 优先鲜艳色，取不到就退回亮色/主色；再兜底保证够亮、够饱和，
 * 免得在一张暗色壁纸上发光发不出来。
 */
private fun pickAccent(bitmap: android.graphics.Bitmap?): Color {
    val fallback = 0xFFFFD60A.toInt()
    if (bitmap == null) return Color(fallback)
    val p = Palette.from(bitmap).clearFilters().generate()
    val raw = p.vibrantSwatch?.rgb
        ?: p.lightVibrantSwatch?.rgb
        ?: p.dominantSwatch?.rgb
        ?: fallback
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(raw, hsv)
    hsv[1] = maxOf(hsv[1], 0.52f)   // 别太灰
    hsv[2] = maxOf(hsv[2], 0.88f)   // 别太暗，要能当发光色
    return Color(android.graphics.Color.HSVToColor(hsv))
}

private fun ts(size: Float, color: Color = Color.White, weight: FontWeight = FontWeight.Normal) =
    TextStyle(color = color, fontSize = size.sp, fontWeight = weight)

class MainActivity : ComponentActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContent { MagsApp() }
    }
}

@Composable
fun MagsApp() {
    val backdrop = rememberLayerBackdrop()
    val scope = rememberCoroutineScope()
    val kb = LocalSoftwareKeyboardController.current
    val clip = LocalClipboardManager.current
    val ctx = LocalContext.current

    var input by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf("seed") }
    var page by remember { mutableStateOf(1) }
    var loading by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf(listOf<Item>()) }
    var total by remember { mutableStateOf(0) }
    var pages by remember { mutableStateOf(1) }
    var errors by remember { mutableStateOf(listOf<String>()) }
    var chan by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var screen by remember { mutableStateOf("list") }   // list | favs
    var favs by remember { mutableStateOf(listOf<Item>()) }
    var favBusy by remember { mutableStateOf(false) }
    var favMsg by remember { mutableStateOf("") }
    var accent by remember { mutableStateOf(Color(0xFFFFD60A)) }   // 跟随壁纸的强调色

    fun runSearch(q: String, s: String, p: Int) {
        if (q.isBlank()) return
        loading = true; err = null
        scope.launch {
            try {
                val (list, o) = doSearch(q, s, p)
                items = list
                total = o.optInt("total")
                pages = o.optInt("pages", 1)
                page = o.optInt("page", 1)
                val ea = o.optJSONArray("errors")
                errors = if (ea == null) emptyList() else (0 until ea.length()).map { ea.getString(it) }
            } catch (e: Exception) {
                err = e.message ?: e.javaClass.simpleName
                items = emptyList()
            } finally {
                loading = false
            }
        }
    }

    fun loadFavs() {
        favBusy = true
        scope.launch {
            try {
                val o = api("/api/favs")
                val arr = o.optJSONArray("items")
                val l = ArrayList<Item>()
                if (arr != null) for (i in 0 until arr.length()) {
                    val j = arr.getJSONObject(i)
                    l.add(Item(j.optString("hash"), j.optString("title"), j.optString("size"),
                        0, 0, "", j.optString("site"), j.optString("magnet"), "",
                        true, j.optBoolean("added")))
                }
                favs = l
            } catch (e: Exception) {
                favMsg = "读取收藏失败：" + (e.message ?: "")
            } finally {
                favBusy = false
            }
        }
    }

    LaunchedEffect(Unit) {
        // 从背景图提取强调色（Flowmix 那种"跟随壁纸"）
        try {
            val bmp = withContext(Dispatchers.IO) {
                BitmapFactory.decodeResource(ctx.resources, R.drawable.bg)
            }
            accent = pickAccent(bmp)
        } catch (_: Exception) { }
        try {
            val o = api("/api/chan")
            chan = if (o.optBoolean("proxy")) "代理 " + o.optString("addr").substringAfterLast(":")
            else "直连"
        } catch (_: Exception) {
            chan = "离线"
        }
        runSearch("孤独摇滚", "seed", 1)
        input = "孤独摇滚"
    }

    Box(Modifier.fillMaxSize()) {

        // ============ 背景层（玻璃折射的内容） ============
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            // 樱花背景图（铺满，裁切）
            Image(
                painter = painterResource(R.drawable.bg),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            // 顶/底压暗，保证白字和玻璃有对比
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color(0xD90A0E1A),
                        0.22f to Color(0xA60A0E1A),
                        0.55f to Color(0x8C101A2E),
                        0.82f to Color(0xB30A0E1A),
                        1f to Color(0xE60A0E1A)
                    )
                )
            )
            // 顶部额外压暗（樱花最亮的地方压住，保证搜索栏可读）
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color(0x66000000),
                        0.30f to Color(0x22000000),
                        1f to Color(0x00000000)
                    )
                )
            )
            // 左侧冷色 + 右侧暖色的极淡叠加，让玻璃折射有色彩层次
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(
                        0f to Color(0x1A4F46E5),
                        1f to Color(0x147C3AED)
                    )
                )
            )
        }

        // ============ 前景 ============
        Column(Modifier.fillMaxSize()) {

            // ---- 顶栏 ----
            Column(
                Modifier.fillMaxWidth()
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { RoundedCornerShape(0.dp, 0.dp, 28.dp, 28.dp) },
                        effects = { vibrancy(); blur(2.dp.toPx()); lens(10.dp.toPx(), 18.dp.toPx()) }
                    )
                    .padding(start = 16.dp, end = 16.dp, top = 34.dp, bottom = 14.dp)
            ) {
                // 搜索框
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.weight(1f).height(50.dp)
                            .clip(RoundedCornerShape(25.dp))
                            .background(Color(0x3DFFFFFF))
                            .padding(horizontal = 18.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (input.isEmpty()) {
                            BasicText("片名 / 番号 / 关键词", style = ts(15f, Color(0x88FFFFFF)))
                        }
                        BasicTextField(
                            value = input,
                            onValueChange = { input = it },
                            singleLine = true,
                            textStyle = ts(15.5f),
                            cursorBrush = SolidColor(Color(0xFF7DD3FC)),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {
                                kb?.hide(); query = input; page = 1
                                runSearch(input, sort, 1)
                            }),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier.height(50.dp).width(74.dp)
                            .drawBackdrop(
                                backdrop = backdrop,
                                shape = { RoundedCornerShape(25.dp) },
                                effects = { vibrancy(); blur(2.dp.toPx()); lens(9.dp.toPx(), 18.dp.toPx()) }
                            )
                            .clickable {
                                kb?.hide(); query = input; page = 1
                                runSearch(input, sort, 1)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        BasicText("搜索", style = ts(15f, weight = FontWeight.Bold))
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    PillText("· $chan")
                    Spacer(Modifier.width(8.dp))
                    if (total > 0) PillText("$total 条 · 第 $page/$pages 页")
                    Spacer(Modifier.weight(1f))
                    if (loading) BasicText("搜索中…", style = ts(12.5f, Color(0xCCBFDBFE)))
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { RoundedCornerShape(13.dp) },
                            effects = { vibrancy(); blur(2.dp.toPx()); lens(6.dp.toPx(), 12.dp.toPx()) }
                        ).clip(RoundedCornerShape(13.dp))
                            .clickable {
                                if (screen == "favs") screen = "list"
                                else { screen = "favs"; loadFavs() }
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        BasicText(
                            if (screen == "favs") "← 返回" else "★ 收藏夹",
                            style = ts(12.5f, Color.White, FontWeight.SemiBold)
                        )
                    }
                }
            }

            // ---- 排序栏 / 收藏夹的一键投光鸭 ----
            if (screen == "list") {
                // 整体玻璃胶囊 + 可拖动/可点击的滑动指示器
                BoxWithConstraints(
                    Modifier.fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .height(46.dp)
                ) {
                    val opts = SORT_OPTS_UI
                    val last = (opts.size - 1).toFloat()
                    val idx = opts.indexOfFirst { it.first == sort }.coerceAtLeast(0)
                    val tabW = maxWidth / opts.size
                    val tabPx = with(androidx.compose.ui.platform.LocalDensity.current) {
                        tabW.toPx()
                    }
                    val slide = remember { Animatable(idx.toFloat()) }
                    var dragging by remember { mutableStateOf(false) }

                    fun commit(i: Int) {
                        val key = opts[i.coerceIn(0, opts.size - 1)].first
                        if (key != sort) {
                            sort = key
                            runSearch(query, key, 1)
                        }
                    }

                    // 外部改变（比如点别的）时平滑滑过去；拖动中不干扰
                    LaunchedEffect(idx) {
                        if (!dragging) slide.animateTo(
                            idx.toFloat(),
                            spring(dampingRatio = 0.72f, stiffness = 420f)
                        )
                    }

                    // 容器
                    Box(
                        Modifier.fillMaxSize()
                            .drawBackdrop(
                                backdrop = backdrop,
                                shape = { RoundedCornerShape(23.dp) },
                                effects = {
                                    vibrancy()
                                    blur(3.dp.toPx())
                                    lens(9.dp.toPx(), 18.dp.toPx())
                                }
                            )
                            .clip(RoundedCornerShape(23.dp))
                            .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(23.dp))
                            // 点击：按落点算第几个
                            .pointerInput(tabPx) {
                                androidx.compose.foundation.gestures.detectTapGestures { off ->
                                    commit((off.x / tabPx).toInt())
                                }
                            }
                            // 拖动：指示器跟手，松手吸附
                            .pointerInput(tabPx) {
                                androidx.compose.foundation.gestures.detectHorizontalDragGestures(
                                    onDragStart = { dragging = true },
                                    onDragCancel = { dragging = false },
                                    onDragEnd = {
                                        dragging = false
                                        val target = slide.value.roundToInt().coerceIn(0, opts.size - 1)
                                        commit(target)
                                        scope.launch {
                                            slide.animateTo(
                                                target.toFloat(),
                                                spring(dampingRatio = 0.68f, stiffness = 360f)
                                            )
                                        }
                                    },
                                    onHorizontalDrag = { change, dx ->
                                        change.consume()
                                        scope.launch {
                                            slide.snapTo(
                                                (slide.value + dx / tabPx).coerceIn(0f, last)
                                            )
                                        }
                                    }
                                )
                            }
                    )

                    // 指示器
                    Box(
                        Modifier
                            .offset(x = tabW * slide.value + 4.dp, y = 4.dp)
                            .width(tabW - 8.dp)
                            .fillMaxHeight()
                            .padding(vertical = 4.dp)
                            .drawBehind {
                                drawRect(
                                    brush = Brush.radialGradient(
                                        colors = listOf(
                                            accent.copy(alpha = 0.42f),
                                            accent.copy(alpha = 0.15f),
                                            accent.copy(alpha = 0f)
                                        ),
                                        center = center,
                                        radius = size.maxDimension * 1.0f
                                    )
                                )
                            }
                            .clip(RoundedCornerShape(19.dp))
                            .border(1.dp, accent.copy(alpha = 0.60f), RoundedCornerShape(19.dp))
                    )

                    // 文字层（不拦截手势）
                    Row(Modifier.fillMaxSize()) {
                        opts.forEach { (k, label) ->
                            val on = sort == k
                            val tc by animateColorAsState(
                                if (on) accent.copy(alpha = 0.35f).compositeOver(Color.White)
                                else Color(0xE6E8EEFF),
                                tween(240),
                                label = "sortText"
                            )
                            Box(
                                Modifier.weight(1f).fillMaxHeight(),
                                contentAlignment = Alignment.Center
                            ) {
                                BasicText(
                                    label,
                                    style = ts(
                                        13.5f, tc,
                                        if (on) FontWeight.Bold else FontWeight.Medium
                                    )
                                )
                            }
                        }
                    }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Box(
                        Modifier.fillMaxWidth().height(42.dp)
                            .drawBackdrop(
                                backdrop = backdrop,
                                shape = { RoundedCornerShape(21.dp) },
                                effects = { vibrancy(); blur(2.dp.toPx()); lens(8.dp.toPx(), 16.dp.toPx()) }
                            )
                            .clip(RoundedCornerShape(21.dp))
                            .clickable(enabled = favs.isNotEmpty()) {
                                Toast.makeText(ctx, "正在批量投递…", Toast.LENGTH_SHORT).show()
                                scope.launch {
                                    try {
                                        val o = api("/api/addall")
                                        Toast.makeText(ctx, o.optString("msg"), Toast.LENGTH_LONG).show()
                                        loadFavs()
                                    } catch (e: Exception) {
                                        Toast.makeText(ctx, "批量投递失败", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        BasicText(
                            if (favs.isEmpty()) "暂无收藏可投递" else "一键投光鸭（${favs.size} 条）",
                            style = ts(14f, if (favs.isEmpty()) Color(0x88FFFFFF) else Color.White,
                                if (favs.isEmpty()) FontWeight.Normal else FontWeight.Bold)
                        )
                    }
                }
            }

            // ---- 错误提示 ----
            if (errors.isNotEmpty()) {
                Box(
                    Modifier.padding(horizontal = 14.dp).fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0x33F472B6))
                        .padding(10.dp)
                ) {
                    BasicText("部分源失败：" + errors.joinToString("，").take(60),
                        style = ts(11.5f, Color(0xFFFBCFE8)))
                }
                Spacer(Modifier.height(8.dp))
            }

            // ---- 列表区（搜索 / 收藏夹） ----
            Box(Modifier.weight(1f)) {
                if (screen == "favs") {
                    when {
                        favBusy && favs.isEmpty() -> CenterHint("读取收藏…")
                        favs.isEmpty() -> CenterHint("还没有收藏\n在搜索结果里点「☆ 收藏」就会出现在这里")
                        else -> LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 90.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            item {
                                if (favMsg.isNotEmpty()) BasicText(favMsg, style = ts(12f, Color(0xFFFBCFE8)))
                            }
                            itemsIndexed(favs) { idx, it ->
                                ResultCard(
                                    backdrop, it, idx,
                                    onCopy = {
                                        clip.setText(AnnotatedString(it.magnet))
                                        Toast.makeText(ctx, "磁力链接已复制", Toast.LENGTH_SHORT).show()
                                    },
                                    onFav = {
                                        scope.launch {
                                            try {
                                                api("/api/fav?h=" + it.hash)
                                                Toast.makeText(ctx, "已取消收藏", Toast.LENGTH_SHORT).show()
                                                loadFavs()
                                            } catch (e: Exception) {
                                                Toast.makeText(ctx, "操作失败", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                    onSub = {
                                        Toast.makeText(ctx, "正在检查字幕…", Toast.LENGTH_SHORT).show()
                                        scope.launch {
                                            try {
                                                val o = api("/api/sub?h=" + it.hash)
                                                val msg = if (o.optBoolean("ok")) o.optString("msg")
                                                          else "失败：" + o.optString("error")
                                                favs = favs.toMutableList().also { l -> l[idx] = l[idx].copy(note = msg) }
                                            } catch (e: Exception) { }
                                        }
                                    },
                                    onAdd = {
                                        Toast.makeText(ctx, "正在投递光鸭…", Toast.LENGTH_SHORT).show()
                                        scope.launch {
                                            try {
                                                val o = api("/api/add?h=" + it.hash)
                                                val ok = o.optBoolean("ok")
                                                val msg = o.optString("msg")
                                                favs = favs.toMutableList().also { l ->
                                                    l[idx] = l[idx].copy(added = ok, note = if (ok) msg else "投递失败")
                                                }
                                                Toast.makeText(ctx, if (ok) "已投递光鸭" else msg, Toast.LENGTH_LONG).show()
                                            } catch (e: Exception) { }
                                        }
                                    }
                                )
                            }
                        }
                    }
                } else when {
                    err != null -> CenterHint("连不上服务\n$err\n\n检查常开机上的 mags 是否在跑")
                    loading && items.isEmpty() -> CenterHint("搜索中…")
                    items.isEmpty() -> CenterHint("没结果，换个关键词试试")
                    else -> LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 90.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        itemsIndexed(items) { idx, it ->
                            ResultCard(
                                backdrop, it, idx,
                                onCopy = {
                                    clip.setText(AnnotatedString(it.magnet))
                                    Toast.makeText(ctx, "磁力链接已复制", Toast.LENGTH_SHORT).show()
                                },
                                onFav = {
                                    scope.launch {
                                        try {
                                            val o = api("/api/fav?h=" + it.hash)
                                            val on = o.optBoolean("fav")
                                            items = items.toMutableList().also { l ->
                                                l[idx] = l[idx].copy(fav = on)
                                            }
                                            Toast.makeText(ctx, if (on) "已收藏" else "已取消收藏", Toast.LENGTH_SHORT).show()
                                        } catch (e: Exception) {
                                            Toast.makeText(ctx, "操作失败", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                onSub = {
                                    Toast.makeText(ctx, "正在检查字幕…", Toast.LENGTH_SHORT).show()
                                    scope.launch {
                                        try {
                                            val o = api("/api/sub?h=" + it.hash)
                                            val msg = if (o.optBoolean("ok"))
                                                o.optString("msg") + (o.optJSONArray("subs")?.let { a ->
                                                    if (a.length() > 0) "：" + a.getString(0).take(40) else "" } ?: "")
                                            else "失败：" + o.optString("error")
                                            items = items.toMutableList().also { l ->
                                                l[idx] = l[idx].copy(note = msg)
                                            }
                                        } catch (e: Exception) {
                                            Toast.makeText(ctx, "查字幕失败", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                onAdd = {
                                    Toast.makeText(ctx, "正在投递光鸭…", Toast.LENGTH_SHORT).show()
                                    scope.launch {
                                        try {
                                            val o = api("/api/add?h=" + it.hash)
                                            val ok = o.optBoolean("ok")
                                            val msg = o.optString("msg")
                                            items = items.toMutableList().also { l ->
                                                l[idx] = l[idx].copy(added = ok, note = if (ok) msg else "投递失败")
                                            }
                                            Toast.makeText(ctx, if (ok) "已投递光鸭" else msg, Toast.LENGTH_LONG).show()
                                        } catch (e: Exception) {
                                            Toast.makeText(ctx, "投递失败", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        // ---- 底部分页（悬浮玻璃条） ----
        if (items.isNotEmpty() && screen == "list") {
            Row(
                Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { RoundedCornerShape(26.dp, 26.dp, 0.dp, 0.dp) },
                        effects = { vibrancy(); blur(2.dp.toPx()); lens(10.dp.toPx(), 18.dp.toPx()) }
                    )
                    .padding(horizontal = 18.dp, vertical = 12.dp)
                    .navigationBarsPadding(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PagBtn("← 上一页", page > 1) { runSearch(query, sort, page - 1) }
                Spacer(Modifier.weight(1f))
                BasicText("$page / $pages", style = ts(13f, Color(0xCCFFFFFF), FontWeight.Bold))
                Spacer(Modifier.weight(1f))
                PagBtn("下一页 →", page < pages) { runSearch(query, sort, page + 1) }
            }
        }
    }
}

@Composable
private fun PillText(txt: String) {
    Box(
        Modifier.clip(RoundedCornerShape(11.dp))
            .background(Color(0x33FFFFFF))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        BasicText(txt, style = ts(11.5f, Color(0xDDE0F2FE)))
    }
}

@Composable
private fun CenterHint(txt: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        BasicText(txt, style = ts(13.5f, Color(0x99FFFFFF)))
    }
}

@Composable
private fun PagBtn(label: String, enabled: Boolean, onClick: () -> Unit) {
    BasicText(
        label,
        style = ts(13.5f, if (enabled) Color(0xFFBAE6FD) else Color(0x44FFFFFF),
            if (enabled) FontWeight.SemiBold else FontWeight.Normal),
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

@Composable
private fun ResultCard(
    backdrop: LayerBackdrop, item: Item, idx: Int,
    onCopy: () -> Unit, onFav: () -> Unit, onSub: () -> Unit, onAdd: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RoundedCornerShape(22.dp) },
                effects = { vibrancy(); blur(2.dp.toPx()); lens(11.dp.toPx(), 20.dp.toPx()) }
            )
            .padding(15.dp)
    ) {
        BasicText(item.title, style = ts(14.5f, weight = FontWeight.SemiBold), maxLines = 3)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            MiniTag(item.site, Color(0x33A5B4FC), Color(0xFFC7D2FE))
            Spacer(Modifier.width(8.dp))
            BasicText(item.size, style = ts(11.5f, Color(0xB3E0E7FF)))
            if (item.seed > 0) {
                Spacer(Modifier.width(8.dp))
                BasicText("🌱${item.seed}", style = ts(11.5f, Color(0xB3A7F3D0)))
            }
            if (item.date.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                BasicText(item.date, style = ts(11f, Color(0x80E0E7FF)))
            }
            if (item.sub == "zh" || item.sub == "sub") {
                Spacer(Modifier.width(6.dp))
                MiniTag(if (item.sub == "zh") "中字" else "字幕", Color(0x334ADE80), Color(0xFFBBF7D0))
            }
            Spacer(Modifier.weight(1f))
            if (item.added) MiniTag("已投", Color(0x334ADE80), Color(0xFFBBF7D0))
            else if (item.fav) MiniTag("★", Color(0x33FDBA74), Color(0xFFFED7AA))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionBtn(backdrop, "磁力链接", true, onCopy)
            ActionBtn(backdrop, "查字幕", false, onSub)
            ActionBtn(backdrop, if (item.fav) "已收藏" else "☆ 收藏", false, onFav)
            ActionBtn(backdrop, if (item.added) "已投光鸭" else "＋光鸭", false, onAdd)
        }
        if (item.note.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            BasicText(item.note, style = ts(11f, Color(0xCCBFDBFE)), maxLines = 3)
        }
    }
}

@Composable
private fun MiniTag(txt: String, bg: Color, fg: Color) {
    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 6.dp, vertical = 2.dp)) {
        BasicText(txt, style = ts(10.5f, fg, FontWeight.SemiBold))
    }
}

@Composable
private fun ActionBtn(backdrop: LayerBackdrop, label: String, primary: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RoundedCornerShape(13.dp) },
                effects = {
                    vibrancy()
                    blur((if (primary) 2f else 5f).dp.toPx())
                    lens(6.dp.toPx(), (if (primary) 12f else 9f).dp.toPx())
                }
            )
            .clip(RoundedCornerShape(13.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        BasicText(
            label,
            style = ts(
                12.5f,
                if (primary) Color.White else Color(0xF2E4ECFF),
                if (primary) FontWeight.Bold else FontWeight.Medium
            )
        )
    }
}
