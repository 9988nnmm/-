package com.fish.mags

import android.graphics.BitmapFactory
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.pill
import androidx.graphics.shapes.toPath
import androidx.palette.graphics.Palette
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.fish.mags.glass.R
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

// 两个地址：家里走局域网（快），出门走 Tailscale（慢但能连）
// 探测成功后记住，后续请求直接用，不再每次试错
data class Endpoint(val url: String, val connectMs: Int)

val ENDPOINTS = listOf(
    Endpoint("http://192.168.1.14:8090", 2500),      // 同一 Wi-Fi
    Endpoint("http://100.91.248.109:8090", 9000)     // Tailscale
)

@Volatile var activeBase: String? = null

// 每台设备一个稳定的身份，首次启动生成后写进 SharedPreferences。
// 服务端按它分桶存 history / fav / seen / added，这样不同设备的搜索记录互不干扰。
@Volatile var DEV_UID: String = ""

// ---------------- 液态玻璃参数 ----------------
// 逆向 FlowMix(cn.ykload.flowmix) 得到：它用的是同一个作者的
// Kyant0/AndroidLiquidGlass（backdrop 的血缘），差别只是把 lens() 的两个开关
// —— depthEffect / chromaticAberration —— 打开了，另外用 colorControls 调对比度和饱和度。
// 我们这边原来一直是 lens(h, amt) 的默认值（两个都是 false），所以只有「折射」没有「色散/厚度」。
data class Glass(
    val blur: Dp,
    val height: Dp,
    val amount: Dp,
    val depth: Boolean = true,     // 深度效果：芯部按径向再位移一点，像块厚玻璃
    val chromatic: Boolean = true, // 边缘色散：RGB 分七段采样，出彩虹边
    val sat: Float = 1.5f,         // 和原来的 vibrancy() 等价
    val con: Float = 1.03f
)

fun BackdropEffectScope.glass(g: Glass) {
    val l = LOOK
    colorControls(brightness = 0f, contrast = l.con, saturation = l.sat)
    blur(g.blur.toPx())
    val k = l.scale
    if (k <= 0f) return
    lens(g.height.toPx() * k, g.amount.toPx() * k,
        depthEffect = l.depth, chromaticAberration = g.chromatic && l.chromatic)
}

// ---------------- 外观设置（持久化在 SharedPreferences，纯客户端） ----------------
data class Look(
    val refr: Int = 1,              // 0 关闭 / 1 默认 / 2 增强
    val chromatic: Boolean = true,  // 边缘色散
    val depth: Boolean = true,      // 深度效果
    val contrast: Int = 1,          // 0 较低 / 1 默认 / 2 较高
    val saturation: Int = 1,
    val flowColor: Boolean = true,  // 跟随壁纸取强调色
    val dim: Int = 1                // 背景压暗：0 轻 / 1 中 / 2 重
) {
    // 压暗系数：原来背景上是四层叠加（85% 黑 + 40% 黑 + 靛蓝紫），壁纸被压成藏青，
    // 玻璃后面等于一片黑，透出来当然没有颜色。这里收成一层，强度可调。
    val dimA: Float get() = when (dim) { 0 -> 0.30f; 1 -> 0.55f; else -> 0.85f }
    val scale: Float get() = when (refr) { 0 -> 0f; 1 -> 1f; else -> 1.75f }
    val con: Float get() = when (contrast) { 0 -> 0.93f; 1 -> 1.03f; else -> 1.16f }
    val sat: Float get() = when (saturation) { 0 -> 1.15f; 1 -> 1.5f; else -> 1.95f }
}

// 绘制线程要读，用 @Volatile 而不进 Compose 状态；界面侧改了 look 会触发重组再同步过来
@Volatile var LOOK = Look()

fun loadLook(ctx: Context): Look {
    val sp = ctx.getSharedPreferences("mags", Context.MODE_PRIVATE)
    return Look(
        refr = sp.getInt("lk_refr", 1),
        chromatic = sp.getBoolean("lk_chr", true),
        depth = sp.getBoolean("lk_depth", true),
        contrast = sp.getInt("lk_con", 1),
        saturation = sp.getInt("lk_sat", 1),
        flowColor = sp.getBoolean("lk_flow", true),
        dim = sp.getInt("lk_dim", 1)
    )
}

fun saveLook(ctx: Context, l: Look) {
    ctx.getSharedPreferences("mags", Context.MODE_PRIVATE).edit()
        .putInt("lk_refr", l.refr)
        .putBoolean("lk_chr", l.chromatic)
        .putBoolean("lk_depth", l.depth)
        .putInt("lk_con", l.contrast)
        .putInt("lk_sat", l.saturation)
        .putBoolean("lk_flow", l.flowColor)
        .putInt("lk_dim", l.dim)
        .apply()
}

private fun wallFile(ctx: Context) = java.io.File(ctx.filesDir, "wallpaper.jpg")

private fun loadWall(ctx: Context): android.graphics.Bitmap? {
    val f = wallFile(ctx)
    if (!f.exists()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.absolutePath, bounds)
    var sample = 1
    while (bounds.outWidth / sample > 2160) sample *= 2
    return BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
}

// 指示器形状：FlowMix 用的是 androidx.graphics.shapes 的 RoundedPolygon + Morph
// （反编译里能看到它把 List<RoundedPolygon> 两两配成 Morph，再 toPath(progress)）。
// 这里照做：常态一个胶囊，拉长态一个更宽的胶囊，用 stretch 当 morph 进度。
private val PillNormal by lazy { RoundedPolygon.pill(width = 1.0f, height = 0.62f) }
private val PillWide by lazy { RoundedPolygon.pill(width = 1.34f, height = 0.62f) }
private val PillMorph by lazy { Morph(PillNormal, PillWide) }

// 边缘高光：玻璃在花壁纸上要靠这一圈亮边才「立」得起来（FlowMix 的卡片也是这个感觉）
private val GlassHi: () -> Highlight = { Highlight(width = 1.dp, blurRadius = 2.dp, alpha = 0.85f) }

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
    // 顺序：上次成功的那个优先，其余按原顺序补
    val order = buildList {
        activeBase?.let { add(ENDPOINTS.first { e -> e.url == it }) }
        ENDPOINTS.forEach { e -> if (e.url != activeBase) add(e) }
    }
    var last: Exception? = null
    for (ep in order) {
        try {
            val conn = (URL(ep.url + path).openConnection() as HttpURLConnection).apply {
                connectTimeout = ep.connectMs
                readTimeout = 100000
                // 关掉自动重定向之外的隐式行为，失败要快
                useCaches = false
                if (DEV_UID.isNotEmpty()) setRequestProperty("X-Mags-Uid", DEV_UID)
            }
            try {
                val txt = conn.inputStream.bufferedReader().use { it.readText() }
                if (activeBase != ep.url) activeBase = ep.url   // 记住能用的
                return@withContext JSONObject(txt)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            last = e
            if (activeBase == ep.url) activeBase = null      // 这个挂了，下次重新选
        }
    }
    throw last ?: Exception("两个地址都连不上")
}

suspend fun doSearch(
    q: String, sort: String, page: Int, force: Boolean = false
): Pair<List<Item>, JSONObject> {
    val enc = URLEncoder.encode(q, "UTF-8")
    val extra = if (force) "&retry=1" else ""
    val o = api("/api/search?q=$enc&sort=$sort&page=$page$extra")
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

// 得意黑（Smiley Sans）—— 免费可商用，倾斜异形
val Smiley = FontFamily(Font(R.font.smiley))

/**
 * 按下时轻微缩小 + 松开弹簧回弹。带一点过冲（dampingRatio < 1），
 * 手感就是"软"的，不是生硬地弹回 1。
 */
@Composable
private fun Modifier.pressBounce(ir: MutableInteractionSource, scale: Float = 0.94f): Modifier {
    val pressed by ir.collectIsPressedAsState()
    val sc by animateFloatAsState(
        targetValue = if (pressed) scale else 1f,
        animationSpec = spring(dampingRatio = 0.38f, stiffness = 1400f),
        label = "pressBounce"
    )
    return this.graphicsLayer {
        scaleX = sc
        scaleY = sc
    }
}

private fun ts(size: Float, color: Color = Color.White, weight: FontWeight = FontWeight.Normal) =
    TextStyle(color = color, fontSize = size.sp, fontWeight = weight)

class MainActivity : ComponentActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        // 设备身份：只在本机生成一次，之后一直复用（卸载重装会换新的，相当于一台新设备）
        val sp = getSharedPreferences("mags", MODE_PRIVATE)
        DEV_UID = sp.getString("uid", null) ?: java.util.UUID.randomUUID().toString().also {
            sp.edit().putString("uid", it).apply()
        }
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
    var hist by remember { mutableStateOf(listOf<String>()) }

    // 外观设置（客户端本地持久化）
    var look by remember { mutableStateOf(loadLook(ctx)) }
    LOOK = look
    var wp by remember { mutableStateOf<ImageBitmap?>(null) }
    var showSettings by remember { mutableStateOf(false) }

    // 相册选图 → 压到 2160px 宽、存 filesDir，再拿它当壁纸和取色源
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            try {
                val bmp = withContext(Dispatchers.IO) {
                    val ins = ctx.contentResolver.openInputStream(uri)
                    val raw = ins?.use { BitmapFactory.decodeStream(it) }
                    if (raw == null) null else {
                        val maxW = 2160
                        val scaled = if (raw.width > maxW)
                            android.graphics.Bitmap.createScaledBitmap(raw, maxW, raw.height * maxW / raw.width, true)
                        else raw
                        java.io.FileOutputStream(wallFile(ctx)).use {
                            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it)
                        }
                        scaled
                    }
                }
                if (bmp != null) {
                    wp = bmp.asImageBitmap()
                    if (look.flowColor) accent = pickAccent(bmp)
                }
            } catch (_: Exception) { }
        }
    }

    // 列表滚动状态必须显式持有：不传 state 时 Compose 用的是内部默认实例，
    // 换页后内容变了但滚动位置会保留，导致「滑到底部翻页，新页直接停在底部」。
    val listState = rememberLazyListState()
    val favListState = rememberLazyListState()

    var clearArm by remember { mutableStateOf(false) }   // 清空的二次确认状态

    fun runSearch(q: String, s: String, p: Int, force: Boolean = false) {
        if (q.isBlank()) return
        loading = true; err = null
        scope.launch {
            try {
                val (list, o) = doSearch(q, s, p, force)
                items = list
                total = o.optInt("total")
                pages = o.optInt("pages", 1)
                page = o.optInt("page", 1)
                try {
                    val h = api("/api/hist")
                    val ha = h.optJSONArray("items")
                    if (ha != null) hist = (0 until ha.length())
                        .map { ha.getString(it) }
                        .filter { it.isNotBlank() && it != "*" }
                } catch (_: Exception) { }
                val ea = o.optJSONArray("errors")
                errors = if (ea == null) emptyList() else (0 until ea.length()).map { ea.getString(it) }
                listState.scrollToItem(0)      // 换页/刷新后回到顶部
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
        // 壁纸 + 强调色：优先用用户自己选的图，没有就用内置樱花图
        try {
            val wb = withContext(Dispatchers.IO) { loadWall(ctx) }
            if (wb != null) {
                wp = wb.asImageBitmap()
                if (loadLook(ctx).flowColor) accent = pickAccent(wb)
            } else {
                val bmp = withContext(Dispatchers.IO) {
                    BitmapFactory.decodeResource(ctx.resources, R.drawable.bg)
                }
                accent = pickAccent(bmp)
            }
        } catch (_: Exception) { }
        try {
            val h = api("/api/hist")
            val arr = h.optJSONArray("items")
            if (arr != null) hist = (0 until arr.length())
                .map { arr.getString(it) }
                .filter { it.isNotBlank() && it != "*" }
        } catch (_: Exception) { }
        try {
            val o = api("/api/chan")
            chan = if (o.optBoolean("proxy")) "代理 " + o.optString("addr").substringAfterLast(":")
            else "直连"
        } catch (_: Exception) {
            chan = "离线"
        }
        // 启动时不自动搜索：早期调试玻璃效果时这里硬编码了「孤独摇滚」，
        // 导致每次冷启动搜索框都被填满并自动搜一次。留空让用户自己输入。
    }

    Box(Modifier.fillMaxSize()) {

        // ============ 背景层（玻璃折射的内容） ============
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            // 背景：用户选的壁纸优先，否则内置樱花图（都铺满 + 裁切）
            val curWp = wp
            if (curWp != null) {
                Image(
                    bitmap = curWp,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Image(
                    painter = painterResource(R.drawable.bg),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            // 压暗层：只留一层，强度跟随「背景压暗」设置。
            // 原来这里是四层叠加（0xD9 深蓝 + 0x66 黑 + 靛蓝→紫渐变），
            // 结果是壁纸被压成藏青、玻璃背后一片黑 —— 玻璃再好也透不出颜色。
            val dimA = look.dimA
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color(0xFF0A0E1A).copy(alpha = 0.92f * dimA),
                        0.18f to Color(0xFF0A0E1A).copy(alpha = 0.62f * dimA),
                        0.45f to Color(0xFF101A2E).copy(alpha = 0.42f * dimA),
                        0.80f to Color(0xFF0A0E1A).copy(alpha = 0.55f * dimA),
                        1f to Color(0xFF0A0E1A).copy(alpha = 0.85f * dimA)
                    )
                )
            )
            // 极淡的冷暖分层，保留一点色彩层次，但不再染成藏青
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(
                        0f to Color(0xFF4F46E5).copy(alpha = 0.07f * dimA),
                        1f to Color(0xFFB45309).copy(alpha = 0.06f * dimA)
                    )
                )
            )
        }

        // ============ 前景 ============
        Column(Modifier.fillMaxSize()) {

            // ---- 顶栏 ----
            Column(
                Modifier.fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 44.dp)
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { RoundedCornerShape(26.dp) },
                        effects = { glass(Glass(8.dp, 8.dp, 16.dp)) },
                        highlight = GlassHi
                    )
                    .clip(RoundedCornerShape(26.dp))
                    .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp)
            ) {
                // 搜索框
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.weight(1f).height(46.dp)
                            .clip(RoundedCornerShape(23.dp))
                            .background(Color(0x4D101828))
                            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(23.dp))
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
                    val searchIr = remember { MutableInteractionSource() }
                    Box(
                        Modifier.height(46.dp).width(70.dp)
                            .pressBounce(searchIr, 0.92f)
                            .drawBackdrop(
                                backdrop = backdrop,
                                shape = { RoundedCornerShape(23.dp) },
                                effects = { glass(Glass(2.dp, 9.dp, 18.dp)) }
                            )
                            .clickable(interactionSource = searchIr, indication = null) {
                                kb?.hide(); query = input; page = 1
                                runSearch(input, sort, 1)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        BasicText("搜索", style = ts(15f, weight = FontWeight.Bold))
                    }
                }

                // 搜索历史（点文字搜索，点 × 删单条，末尾「清空」全删）
                if (hist.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        hist.take(3).forEach { hq ->
                            // 胶囊 = 左半「搜索」 + 右半「×」，两个并排独立可点，不嵌套
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(13.dp))
                                    .background(Color(0x26FFFFFF)),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier
                                        .clickable {
                                            input = hq
                                            query = hq
                                            runSearch(hq, sort, 1)
                                        }
                                        .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp)
                                ) {
                                    BasicText(hq.take(8), style = ts(12f, Color(0xD9E8EEFF)))
                                }
                                Box(
                                    Modifier
                                        .clickable {
                                            scope.launch {
                                                try {
                                                    val o = api(
                                                        "/api/delhist?q=" +
                                                        URLEncoder.encode(hq, "UTF-8")
                                                    )
                                                    val a = o.optJSONArray("items")
                                                    hist = if (a == null) emptyList()
                                                           else (0 until a.length())
                                                               .map { a.getString(it) }
                                                               .filter { it.isNotBlank() && it != "*" }
                                                } catch (e: Exception) {
                                                }
                                            }
                                        }
                                        .padding(start = 4.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    BasicText("×", style = ts(14f, Color(0xCCFFFFFF), FontWeight.Medium))
                                }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        BasicText(
                            if (clearArm) "确定清空?" else "清空",
                            style = ts(11.5f, if (clearArm) Color(0xFFFF9DB4) else Color(0x99FFFFFF),
                                if (clearArm) FontWeight.Bold else FontWeight.Normal),
                            modifier = Modifier
                                .clip(RoundedCornerShape(11.dp))
                                .background(if (clearArm) Color(0x33FF4D6D) else Color.Transparent)
                                .clickable {
                                    if (!clearArm) {
                                        clearArm = true
                                        scope.launch {
                                            delay(3000)
                                            clearArm = false
                                        }
                                    } else {
                                        clearArm = false
                                        scope.launch {
                                            try {
                                                api("/api/delhist?q=*")
                                                hist = emptyList()
                                            } catch (e: Exception) {
                                            }
                                        }
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    PillText("· $chan")
                    Spacer(Modifier.width(8.dp))
                    if (total > 0) PillText("$total 条 · 第 $page/$pages 页")
                    Spacer(Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    // 刷新：强制清缓存 + 清源冷却 + 重探代理，再重搜当前页
                    val refreshIr = remember { MutableInteractionSource() }
                    Box(
                        Modifier.pressBounce(refreshIr, 0.93f)
                            .drawBackdrop(
                                backdrop = backdrop,
                                shape = { RoundedCornerShape(13.dp) },
                                effects = { glass(Glass(2.dp, 6.dp, 12.dp, chromatic = false)) }
                            )
                            .clip(RoundedCornerShape(13.dp))
                            .clickable(interactionSource = refreshIr, indication = null) {
                                if (query.isNotBlank() && !loading) {
                                    runSearch(query, sort, page, force = true)
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        BasicText(
                            if (loading) "…" else "刷新",
                            style = ts(12.5f,
                                if (loading) Color(0x66FFFFFF) else Color(0xFFBAE6FD),
                                FontWeight.SemiBold),
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    Spacer(Modifier.width(8.dp))

                    val favIr = remember { MutableInteractionSource() }
                    Box(
                        Modifier.pressBounce(favIr, 0.93f)
                            .drawBackdrop(
                                backdrop = backdrop,
                                shape = { RoundedCornerShape(13.dp) },
                                effects = { glass(Glass(2.dp, 6.dp, 12.dp, chromatic = false)) }
                                ).clip(RoundedCornerShape(13.dp))
                            .clickable(interactionSource = favIr, indication = null) {
                                if (screen == "favs") screen = "list"
                                else { screen = "favs"; loadFavs() }
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        BasicText(
                            if (screen == "favs") "← 返回" else "★ 收藏夹",
                            style = ts(12.5f, Color.White, FontWeight.SemiBold),
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    val gearIr = remember { MutableInteractionSource() }
                    Box(
                        Modifier.pressBounce(gearIr, 0.93f)
                            .drawBackdrop(
                                backdrop = backdrop,
                                shape = { RoundedCornerShape(13.dp) },
                                effects = { glass(Glass(2.dp, 6.dp, 12.dp, chromatic = false)) }
                            )
                            .clip(RoundedCornerShape(13.dp))
                            .clickable(interactionSource = gearIr, indication = null) {
                                showSettings = true
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        BasicText("⚙", style = ts(12.5f, Color.White, FontWeight.SemiBold),
                            maxLines = 1, softWrap = false)
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
                    var dragPos by remember { mutableFloatStateOf(idx.toFloat()) }

                    // 照 FlowMix 逐帧实测重写：高亮环「中心连续跟手」，只按拖动速度横向拉伸，
                    // 高度不变。静止 210×150、快速拖动最大 350×150（≈1.7 倍），松手回落到档位中心。
                    var stretch by remember { mutableFloatStateOf(0f) }   // 0 = 原始宽度，0.7 = 1.7 倍
                    // 1:1 复刻 FlowMix 的两层结构：
                    //   灰色实心圆 = 当前选中（固定在该档中心，走 spring）
                    //   黄色描边环 = 触摸指示器（跟手、morph 变形、松手淡出）
                    var touchX by remember { mutableFloatStateOf(0f) }
                    var touchOn by remember { mutableStateOf(false) }

                    fun commit(i: Int) {
                        val key = opts[i.coerceIn(0, opts.size - 1)].first
                        if (key != sort) {
                            sort = key
                            runSearch(query, key, 1)
                        }
                    }


                    // 容器
                    Box(
                        Modifier.fillMaxSize()
                            .drawBackdrop(
                                backdrop = backdrop,
                                shape = { RoundedCornerShape(23.dp) },
                                effects = { glass(Glass(3.dp, 9.dp, 18.dp)) },
                                highlight = GlassHi
                            )
                            .clip(RoundedCornerShape(23.dp))
                            .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(23.dp))
                            // 一个手势区同时处理：原地松手=点击，拖动=跟手
                            .pointerInput(tabPx) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(
                                        requireUnconsumed = false
                                    )
                                    val startX = down.position.x
                                    val startPos = slide.value
                                    var totalDx = 0f
                                    var moved = false
                                    dragPos = startPos     // 跟手从当前位置开始，避免跳
                                    dragging = true
                                    touchX = down.position.x
                                    touchOn = true
                                    while (true) {
                                        val ev = awaitPointerEvent()
                                        val ch = ev.changes.firstOrNull() ?: break
                                        if (!ch.pressed) break
                                        val dx = ch.position.x - ch.previousPosition.x
                                        if (!moved && kotlin.math.abs(totalDx + dx) > 6f) moved = true
                                        totalDx += dx
                                        if (moved) {
                                            ch.consume()
                                            // 普通 state 赋值：每帧只触发一次重组，不起协程
                                            dragPos = (startPos + totalDx / tabPx)
                                                .coerceIn(0f, last)
                                            touchX = ch.position.x
                                            // 速度越快拉得越长，天花板 1.7 倍（实测值）
                                            stretch = (stretch * 0.55f +
                                                    kotlin.math.abs(dx) / tabPx * 2.4f)
                                                .coerceIn(0f, 0.7f)
                                        }
                                    }
                                    if (!moved) {
                                        // 点选：归到最近档位，滑过去
                                        dragging = false
                                        val target = (startX / tabPx - 0.5f)
                                            .roundToInt().coerceIn(0, opts.size - 1)
                                        scope.launch {
                                            slide.animateTo(
                                                target.toFloat(),
                                                spring(dampingRatio = 0.72f, stiffness = 420f)
                                            )
                                        }
                                        touchOn = false
                                        commit(target)
                                        scope.launch {
                                            val s0 = stretch
                                            var k = 0f
                                            while (k < 1f) {
                                                delay(16); k = (k + 0.11f).coerceAtMost(1f)
                                                stretch = s0 * (1f - k)
                                            }
                                            stretch = 0f
                                        }
                                    } else {
                                        scope.launch {
                                            val s0 = stretch
                                            var k = 0f
                                            while (k < 1f) {
                                                delay(16); k = (k + 0.11f).coerceAtMost(1f)
                                                stretch = s0 * (1f - k)
                                            }
                                            stretch = 0f
                                        }
                                        // 拖动松手：吸附到最近档位（限位）
                                        val target = dragPos.roundToInt()
                                            .coerceIn(0, opts.size - 1)
                                        scope.launch {
                                            slide.snapTo(dragPos)   // 从松手处接着走，不跳
                                            dragging = false        // snap 之后再交还控制权
                                            slide.animateTo(
                                                target.toFloat(),
                                                spring(dampingRatio = 0.66f, stiffness = 340f)
                                            )
                                        }
                                        touchOn = false
                                        commit(target)
                                    }
                                }
                            }
                    ) {

                    // ── 第一层：灰色实心圆（当前选中），跟 slide 走 spring ──
                    Canvas(Modifier.fillMaxSize()) {
                        val slotW = size.width / opts.size
                        val cy = size.height / 2f
                        val selCx = slotW * (slide.value + 0.5f)
                        val r = size.height / 2f - 5.dp.toPx()
                        drawCircle(
                            color = Color(0xE6BFC5CF),
                            radius = r,
                            center = Offset(selCx, cy)
                        )
                    }

                    // ── 第二层：黄色环（触摸），跟手 + morph，松手淡出 ──
                    val ringA by animateFloatAsState(
                        targetValue = if (touchOn || dragging) 1f else 0f,
                        animationSpec = tween(if (touchOn || dragging) 60 else 220),
                        label = "ringAlpha"
                    )
                    if (ringA > 0.01f) {
                        Canvas(Modifier.fillMaxSize()) {
                            val cy = size.height / 2f
                            val ringW = size.height * 1.42f      // 实测 210×150 的比例
                            val ringH = size.height - 8.dp.toPx()
                            val prog = (stretch / 0.7f).coerceIn(0f, 1f)

                            val ap = android.graphics.Path()
                            PillMorph.toPath(prog, ap)
                            val mtx = android.graphics.Matrix()
                            mtx.postScale(ringW, ringH / 0.62f)
                            mtx.postTranslate(touchX, cy)
                            ap.transform(mtx)

                            val cp = ap.asComposePath()
                            drawPath(cp, accent.copy(alpha = 0.16f * ringA))
                            drawPath(
                                cp,
                                accent.copy(alpha = 0.85f * ringA),
                                style = Stroke(1.6.dp.toPx())
                            )
                        }
                    }

                    // 文字层（不拦截手势）
                    Row(Modifier.fillMaxSize()) {
                        opts.forEach { (k, label) ->
                            val on = sort == k
                            val tc by animateColorAsState(
                                // 选中项压在灰色实心圆上，用深色字才看得清（和它一致）
                                if (on) Color(0xFF15181E) else Color(0xE6E8EEFF),
                                tween(240),
                                label = "sortText_$k"
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
                                effects = { glass(Glass(2.dp, 8.dp, 16.dp)) }
                            )
                            .clip(RoundedCornerShape(21.dp))
                            .clickable(enabled = favs.isNotEmpty()) {
                                scope.launch {
                                    try {
                                        val o = api("/api/addall")
                                        loadFavs()
                                    } catch (e: Exception) {
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
                            state = favListState,
                            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 104.dp),
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
                                    },
                                    onFav = {
                                        scope.launch {
                                            try {
                                                api("/api/fav?h=" + it.hash)
                                                loadFavs()
                                            } catch (e: Exception) {
                                            }
                                        }
                                    },
                                    onSub = {
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
                                        scope.launch {
                                            try {
                                                val o = api("/api/add?h=" + it.hash)
                                                val ok = o.optBoolean("ok")
                                                val msg = o.optString("msg")
                                                favs = favs.toMutableList().also { l ->
                                                    l[idx] = l[idx].copy(added = ok, note = if (ok) msg else "投递失败")
                                                }
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
                        state = listState,
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 104.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        itemsIndexed(items) { idx, it ->
                            ResultCard(
                                backdrop, it, idx,
                                onCopy = {
                                    clip.setText(AnnotatedString(it.magnet))
                                },
                                onFav = {
                                    scope.launch {
                                        try {
                                            val o = api("/api/fav?h=" + it.hash)
                                            val on = o.optBoolean("fav")
                                            items = items.toMutableList().also { l ->
                                                l[idx] = l[idx].copy(fav = on)
                                            }
                                        } catch (e: Exception) {
                                        }
                                    }
                                },
                                onSub = {
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
                                        }
                                    }
                                },
                                onAdd = {
                                    scope.launch {
                                        try {
                                            val o = api("/api/add?h=" + it.hash)
                                            val ok = o.optBoolean("ok")
                                            val msg = o.optString("msg")
                                            items = items.toMutableList().also { l ->
                                                l[idx] = l[idx].copy(added = ok, note = if (ok) msg else "投递失败")
                                            }
                                        } catch (e: Exception) {
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
                    .padding(horizontal = 46.dp, vertical = 14.dp)
                    .navigationBarsPadding()
                    .height(44.dp)
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { RoundedCornerShape(22.dp) },
                        effects = { glass(Glass(3.dp, 8.dp, 16.dp)) }
                    )
                    .clip(RoundedCornerShape(22.dp))
                    .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(22.dp))
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PagBtn("← 上一页", page > 1) { runSearch(query, sort, page - 1) }
                Spacer(Modifier.weight(1f))
                BasicText("$page / $pages", style = ts(13f, Color(0xCCFFFFFF), FontWeight.Bold))
                Spacer(Modifier.weight(1f))
                PagBtn("下一页 →", page < pages) { runSearch(query, sort, page + 1) }
            }
        }

        // ---- 外观设置（覆盖层） ----
        if (showSettings) {
            SettingsPane(
                backdrop = backdrop,
                look = look,
                onChange = { l -> look = l; saveLook(ctx, l) },
                hasWall = wp != null,
                onPickWall = {
                    try {
                        picker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    } catch (_: Exception) { }
                },
                onResetWall = {
                    try { wallFile(ctx).delete() } catch (_: Exception) { }
                    wp = null
                    scope.launch {
                        try {
                            val bmp = withContext(Dispatchers.IO) {
                                BitmapFactory.decodeResource(ctx.resources, R.drawable.bg)
                            }
                            if (look.flowColor) accent = pickAccent(bmp)
                        } catch (_: Exception) { }
                    }
                },
                onClose = { showSettings = false }
            )
        }
    }
}

@Composable
private fun SettingsPane(
    backdrop: LayerBackdrop,
    look: Look,
    onChange: (Look) -> Unit,
    hasWall: Boolean,
    onPickWall: () -> Unit,
    onResetWall: () -> Unit,
    onClose: () -> Unit
) {
    val scrimIr = remember { MutableInteractionSource() }
    Box(
        Modifier.fillMaxSize()
            .background(Color(0xF2090C14))
            .clickable(interactionSource = scrimIr, indication = null) { }   // 吞掉穿透点击
    ) {
        Column(
            Modifier.fillMaxSize()
                .padding(horizontal = 14.dp)
                .padding(top = 46.dp, bottom = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BasicText("外观", style = ts(23f, Color.White, FontWeight.Bold))
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { RoundedCornerShape(15.dp) },
                            effects = { glass(Glass(3.dp, 8.dp, 16.dp, chromatic = false)) }
                        )
                        .clip(RoundedCornerShape(15.dp))
                        .clickable { onClose() }
                        .padding(horizontal = 18.dp, vertical = 9.dp)
                ) {
                    BasicText("完成", style = ts(13f, Color(0xFFBAE6FD), FontWeight.SemiBold))
                }
            }
            Spacer(Modifier.height(18.dp))

            SectionTitle("液态玻璃")
            SegRow(backdrop, "边缘折射", "玻璃边缘的弯折强度", listOf("关闭", "默认", "增强"), look.refr) { v ->
                onChange(look.copy(refr = v))
            }
            SegRow(backdrop, "边缘色散", "玻璃边缘的彩虹色边", listOf("关闭", "开启"), if (look.chromatic) 1 else 0) { v ->
                onChange(look.copy(chromatic = v == 1))
            }
            SegRow(backdrop, "深度效果", "玻璃芯部再加一层径向位移", listOf("关闭", "开启"), if (look.depth) 1 else 0) { v ->
                onChange(look.copy(depth = v == 1))
            }
            SegRow(backdrop, "对比度", "对玻璃后面的内容做调整", listOf("较低", "默认", "较高"), look.contrast) { v ->
                onChange(look.copy(contrast = v))
            }
            SegRow(backdrop, "饱和度", "同上，偏鲜艳或偏灰", listOf("较低", "默认", "较高"), look.saturation) { v ->
                onChange(look.copy(saturation = v))
            }
            SegRow(backdrop, "背景压暗", "压太狠壁纸就没颜色了，玻璃也就透不出东西", listOf("轻", "中", "重"), look.dim) { v ->
                onChange(look.copy(dim = v))
            }

            Spacer(Modifier.height(18.dp))
            SectionTitle("壁纸与配色")
            SegRow(backdrop, "跟随壁纸取色", "从壁纸里取强调色，按钮/标签跟着变", listOf("关闭", "开启"), if (look.flowColor) 1 else 0) { v ->
                onChange(look.copy(flowColor = v == 1))
            }
            Spacer(Modifier.height(12.dp))
            ActionRow(backdrop, if (hasWall) "换一张壁纸" else "从相册选一张壁纸", onPickWall)
            if (hasWall) {
                Spacer(Modifier.height(10.dp))
                ActionRow(backdrop, "恢复内置壁纸", onResetWall, faint = true)
            }
            Spacer(Modifier.height(24.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                BasicText("设置保存在本机，不同设备互不影响",
                    style = ts(11f, Color(0x66FFFFFF)))
            }
        }
    }
}

@Composable
private fun SectionTitle(t: String) {
    Box(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp)) {
        BasicText(t, style = ts(12f, Color(0x99FFFFFF), FontWeight.SemiBold))
    }
}

@Composable
private fun SegRow(
    backdrop: LayerBackdrop,
    title: String,
    hint: String,
    options: List<String>,
    index: Int,
    onPick: (Int) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        BasicText(title, style = ts(14f, Color(0xFFE6F3FF), FontWeight.SemiBold))
        Spacer(Modifier.height(2.dp))
        BasicText(hint, style = ts(11f, Color(0x80FFFFFF)))
        Spacer(Modifier.height(7.dp))
        Row(
            Modifier.fillMaxWidth()
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { RoundedCornerShape(19.dp) },
                    effects = { glass(Glass(2.dp, 7.dp, 14.dp, chromatic = false)) }
                )
                .clip(RoundedCornerShape(19.dp))
                .padding(4.dp)
        ) {
            options.forEachIndexed { i, label ->
                val on = i == index
                Box(
                    Modifier.weight(1f)
                        .clip(RoundedCornerShape(15.dp))
                        .background(if (on) Color(0x33FFFFFF) else Color.Transparent)
                        .clickable { onPick(i) }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    BasicText(
                        label,
                        style = ts(12.5f, if (on) Color.White else Color(0x99FFFFFF),
                            if (on) FontWeight.SemiBold else FontWeight.Normal),
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionRow(
    backdrop: LayerBackdrop,
    label: String,
    onClick: () -> Unit,
    faint: Boolean = false
) {
    Box(
        Modifier.fillMaxWidth()
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RoundedCornerShape(19.dp) },
                effects = { glass(Glass(2.dp, 8.dp, 16.dp, chromatic = false)) }
            )
            .clip(RoundedCornerShape(19.dp))
            .clickable { onClick() }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            label,
            style = ts(13.5f, if (faint) Color(0x99FFFFFF) else Color(0xFFBAE6FD), FontWeight.SemiBold),
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun PillText(txt: String) {
    Box(
        Modifier.clip(RoundedCornerShape(11.dp))
            .background(Color(0x33FFFFFF))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        BasicText(txt, style = ts(11.5f, Color(0xDDE0F2FE)), maxLines = 1, softWrap = false)
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
            .pressBounce(remember { MutableInteractionSource() }, 0.9f)
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
                effects = { glass(Glass(2.dp, 11.dp, 20.dp)) },
                highlight = GlassHi
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
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ActionBtn(backdrop, "磁力链接", true, Modifier.weight(1f), onCopy)
            ActionBtn(backdrop, "查字幕", false, Modifier.weight(1f), onSub)
            ActionBtn(backdrop, if (item.fav) "已收藏" else "收藏", false, Modifier.weight(1f), onFav)
            ActionBtn(backdrop, if (item.added) "已投光鸭" else "投光鸭", false, Modifier.weight(1f), onAdd)
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
private fun ActionBtn(
    backdrop: LayerBackdrop, label: String, primary: Boolean,
    modifier: Modifier = Modifier, onClick: () -> Unit
) {
    val ir = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressBounce(ir)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RoundedCornerShape(13.dp) },
                effects = {
                    glass(
                        Glass(
                            (if (primary) 2f else 5f).dp,
                            (if (primary) 6f else 6f).dp,
                            (if (primary) 12f else 9f).dp,
                            depth = primary,
                            chromatic = false
                        )
                    )
                }
            )
            .clip(RoundedCornerShape(13.dp))
            .clickable(interactionSource = ir, indication = null) { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        // 文字切换（收藏 → 已收藏）走淡入淡出，不要"啪"一下换掉
        Crossfade(
            targetState = label,
            animationSpec = tween(durationMillis = 200),
            label = "actionLabel"
        ) { txt ->
            // 固定占满按钮宽度并居中：两段文字宽度不同也不会让内容跳
            BasicText(
                txt,
                style = ts(
                    12.5f,
                    if (primary) Color.White else Color(0xF2E4ECFF),
                    if (primary) FontWeight.Bold else FontWeight.Medium
                ).copy(
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
