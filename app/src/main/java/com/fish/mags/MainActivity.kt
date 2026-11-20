package com.fish.mags

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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

const val BASE = "http://192.168.1.14:8090"

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
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(
                        0f to Color(0xFF4F46E5),
                        0.4f to Color(0xFF0EA5E9),
                        0.75f to Color(0xFF0B1220),
                        1f to Color(0xFF1E1B4B)
                    )
                )
            )
            Box(Modifier.size(300.dp).offset((-40).dp, 90.dp).background(Color(0x7C7C3AED), RoundedCornerShape(150.dp)))
            Box(Modifier.size(220.dp).offset(210.dp, 420.dp).background(Color(0x6622D3EE), RoundedCornerShape(110.dp)))
            Box(Modifier.size(260.dp).offset(30.dp, 760.dp).background(Color(0x5C99F6E4), RoundedCornerShape(130.dp)))
            Box(Modifier.size(180.dp).offset(150.dp, 1180.dp).background(Color(0x4DBB6FF0), RoundedCornerShape(90.dp)))
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
                            .background(Color(0x1FFFFFFF))
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
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("seed" to "种子", "size" to "大小", "date" to "时间", "name" to "名称").forEach { (k, label) ->
                        val on = sort == k
                        Box(
                            Modifier
                                .then(
                                    if (on) Modifier.drawBackdrop(
                                        backdrop = backdrop,
                                        shape = { RoundedCornerShape(19.dp) },
                                        effects = { vibrancy(); blur(2.dp.toPx()); lens(7.dp.toPx(), 14.dp.toPx()) }
                                    ) else Modifier
                                )
                                .clip(RoundedCornerShape(19.dp))
                                .background(if (on) Color.Transparent else Color(0x14FFFFFF))
                                .clickable { sort = k; runSearch(query, k, 1) }
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            BasicText(label, style = ts(13f, if (on) Color.White else Color(0xAAFFFFFF),
                                if (on) FontWeight.Bold else FontWeight.Normal))
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
            .background(Color(0x1FFFFFFF))
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
