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
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.LinearOutSlowInEasing
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

/**
 * 「投光鸭」落盘目录的客户端覆盖（编译期决定，运行期不可改）。
 *   self   版本 → 空串，用服务端 cloud.json 里的 path
 *   friend 版本 → "&path=/云盘"
 * 服务端 /api/add 与 /api/addall 认这个可选参数；不传就维持原行为。
 */
val ADD_PATH_Q: String =
    if (com.fish.mags.glass.BuildConfig.ADD_PATH.isBlank()) ""
    else "&path=" + java.net.URLEncoder.encode(com.fish.mags.glass.BuildConfig.ADD_PATH, "UTF-8")

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

// 供 SegRow 这类没有 accent 参数的组合函数读取。
// 必须是 Compose 状态：普通 var 改了不会触发重组，设置页会一直用首次组合时的颜色。
var ACCENT by androidx.compose.runtime.mutableStateOf(Color(0xFFD0BCFF))

// 关掉「跟随壁纸取色」时用的默认系统色（Material 3 基线 · 暗色主题）
val DEFAULT_ACCENT = Color(0xFFD0BCFF)          // primary

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
// 胶囊：两端连续圆弧（圆角 = 高度一半），不是圆角矩形
private val PillNormal by lazy { RoundedPolygon.pill(width = 1.0f, height = 0.62f) }
private val PillWide by lazy { RoundedPolygon.pill(width = 1.28f, height = 0.62f) }
private val PillMorph by lazy { Morph(PillNormal, PillWide) }

// 边缘高光：玻璃在花壁纸上要靠这一圈亮边才「立」得起来（FlowMix 的卡片也是这个感觉）
private val GlassHi: () -> Highlight = { Highlight(width = 1.dp, blurRadius = 2.dp, alpha = 0.85f) }

// 顶层常量：pointerInput 的 key 必须是稳定对象，否则每次重组都会重建手势
private val SORT_OPTS_UI = listOf(
    "seed" to "种子", "size" to "大小", "date" to "时间", "name" to "名称"
)

// 本地排序：换排序键时直接重排当前这批结果，不用等服务端
private fun sizeBytes(s: String): Long {
    val m = Regex("([0-9.]+)\\s*([KMGT]?)B", RegexOption.IGNORE_CASE).find(s) ?: return 0L
    val v = m.groupValues[1].toDoubleOrNull() ?: return 0L
    val mul = when (m.groupValues[2].uppercase()) {
        "K" -> 1024L
        "M" -> 1024L * 1024
        "G" -> 1024L * 1024 * 1024
        "T" -> 1024L * 1024 * 1024 * 1024
        else -> 1L
    }
    return (v * mul).toLong()
}

private fun sortItems(list: List<Item>, key: String): List<Item> = when (key) {
    "seed" -> list.sortedWith(compareByDescending<Item> { it.seed }.thenByDescending { it.date })
    "size" -> list.sortedWith(compareByDescending<Item> { sizeBytes(it.size) })
    "date" -> list.sortedWith(compareByDescending<Item> { it.date })
    "name" -> list.sortedWith(compareBy<Item> { it.title })
    else -> list
}

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
/**
 * 主题色 = 壁纸的「主导色」本身（Material Color Utilities 的 QuantizerCelebi + Score）。
 *
 * 这是拿 FlowMix 的截图反推验证过的：
 *   暖色壁纸  → MCU 主导色 #D0A199，他们的强调色 #D0A098
 *   蓝色动漫  → MCU 主导色 #6896F0，他们的强调色 #6898E8
 * 即两端基本一致，所以不要再自作主张去压饱和度。
 * 只有主导色过暗时（深色壁纸）才提亮到 tone 80，避免得到一团黑。
 */
/**
 * 从壁纸取强调色。
 *
 * 关键结论（对着 FlowMix 三张截图反推 + 用它的壁纸实测）：
 *   取「面积最大的颜色」会得到背景的灰紫（内置樱花图 → #C7BFFF 淡紫），
 *   而 FlowMix 显示的是粉（#F0C4CC 量级）—— 粉色对应的是画面里的**樱花亮部**。
 *   按「亮部 10% 像素、按色度加权求平均色相」算出来是 H≈344°，
 *   primary = #F9B1DB，和他们对得上；而且 64/96/160/256 四种采样密度结果一致（H=344~345）。
 *
 * 最后统一交给 M3 的 SchemeTonalSpot 归一化（tone 80 / chroma 36），
 * 这样同一张图在任何路径下都算出同一个颜色，不会再出现"选完壁纸一个色、重算另一个色"。
 */
/**
 * 从壁纸取强调色。
 *
 * 走过的两条弯路（都实测验证过）：
 *   1) 取「面积最大的颜色」→ 常拿到背景（樱花图的紫雾 → 淡紫）
 *   2) 取「亮部 10% 的平均色相」→ 壁纸一过曝就崩：小黄鸭那张 71% 是纯白，
 *      亮部全是近白像素，色相纯属噪声，平均后落到青色，和画面完全不搭。
 *
 * 现在的规则：**在图里找出「面积够大且最鲜艳」的那个色簇**。
 *   - 面积 < 1% 的丢掉（避免噪点/压缩边缘）
 *   - 色度 < 10 的丢掉（纯白纯黑没有色相）
 *   - 取剩下的里面色度最高的，用它的色相
 *   - 最后交给 M3 的 SchemeTonalSpot 归一化（tone 80 / chroma 36）
 *
 * 实测：小黄鸭 → 选中 #F2B032（16.7% 面积，C=51.7）→ #EFBF6D 琥珀黄，和 FlowMix 一致。
 */
private fun pickAccent(bitmap: android.graphics.Bitmap?): Color {
    val fallback = 0xFFD0A199.toInt()
    if (bitmap == null) return Color(fallback)
    return try {
        val n = 120
        val small = android.graphics.Bitmap.createScaledBitmap(bitmap, n, n, true)
        val px = IntArray(n * n)
        small.getPixels(px, 0, n, 0, 0, n, n)
        for (i in px.indices) px[i] = px[i] or 0xFF000000.toInt()

        val q = com.google.android.material.color.utilities.QuantizerCelebi.quantize(px, 128)
        val total = q.values.sum().toFloat()
        if (total <= 0f) return Color(fallback)

        var bestHue = -1f
        var bestChroma = -1f
        var bestTone = 70f
        var bestShare = 0f
        for ((color, count) in q) {
            val share = count / total
            if (share < 0.01f) continue                       // 太小，多半是噪点
            val hct = com.google.android.material.color.utilities.Hct.fromInt(color)
            if (hct.chroma < 10.0) continue                   // 近白/近黑，没有色相
            if (hct.chroma > bestChroma) {
                bestChroma = hct.chroma.toFloat()
                bestHue = hct.hue.toFloat()
                bestTone = hct.tone.toFloat()
                bestShare = share
            }
        }
        if (bestHue < 0f) return Color(fallback)

        // 直接沿用这个簇自己的色相/色度/明度，只做安全夹取：
        //   tone 太低在深色背景上看不见（下限 45），太高会发白（上限 85）
        //   chroma 太低会发灰（下限 16）
        // 之前强推成 M3 的 chroma 36 / tone 80，鲜艳的橙色壁纸会被洗成粉橘（#FF6A2A → #FFB59A）。
        val tone = bestTone.coerceIn(45f, 85f)
        val chroma = bestChroma.coerceIn(16f, 100f)
        Color(com.google.android.material.color.utilities.Hct.from(
            bestHue.toDouble(), chroma.toDouble(), tone.toDouble()).toInt())
    } catch (_: Throwable) {
        Color(fallback)
    }
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
    // 前景层：包含列表内容，悬浮条（翻页条）用它采样，才能真正"透"出后面的卡片
    val fgBackdrop = rememberLayerBackdrop()
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
    var accent by remember { mutableStateOf(Color(0xFFF2F4F7)) }   // 跟随壁纸的强调色
    SideEffect { ACCENT = accent }
    var hist by remember { mutableStateOf(listOf<String>()) }

    // 外观设置（客户端本地持久化）
    var look by remember { mutableStateOf(loadLook(ctx)) }
    LOOK = look
    var wp by remember { mutableStateOf<ImageBitmap?>(null) }
    // 换壁纸后让背景层整体重建一次：layerBackdrop 的纹理是异步记录的，
    // 只改 Image 的话设置页那层玻璃会有一半还在用旧壁纸（实测：下半屏仍是旧底色）。
    var wallEpoch by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }

    // 相册选图 → 压到 2160px 宽、存 filesDir，再拿它当壁纸和取色源
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            try {
                // 取色也在 IO 线程算完，和壁纸一起提交：
                // 否则会出现「壁纸已经换了、按钮还是上一个颜色」闪一下（实测约 150ms）
                val res = withContext(Dispatchers.IO) {
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
                        val acc = if (look.flowColor) pickAccent(scaled) else DEFAULT_ACCENT
                        scaled.asImageBitmap() to acc
                    }
                }
                if (res != null) {
                    accent = res.second
                    wp = res.first; wallEpoch++
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
                if (s != sort) return@launch        // 排序已变，丢弃过期响应
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
                val acc = withContext(Dispatchers.Default) {
                    if (loadLook(ctx).flowColor) pickAccent(wb) else DEFAULT_ACCENT
                }
                accent = acc
                wp = wb.asImageBitmap(); wallEpoch++
            } else {
                val bmp = withContext(Dispatchers.IO) {
                    BitmapFactory.decodeResource(ctx.resources, R.drawable.bg)
                }
                accent = if (loadLook(ctx).flowColor) pickAccent(bmp) else DEFAULT_ACCENT
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
        androidx.compose.runtime.key(wallEpoch) {
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

        }
        // ============ 前景 ============
        Column(Modifier.fillMaxSize().layerBackdrop(fgBackdrop)) {

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
                    // 左侧信息占剩余宽度并可收缩，右侧三个按钮固定宽度 —— 结果条数再多也挤不掉 ⚙
                    Row(
                        Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PillText("· $chan")
                        if (total > 0) {
                            Spacer(Modifier.width(8.dp))
                            PillText(
                                "$total 条 · 第 $page/$pages 页",
                                Modifier.weight(1f, fill = false)
                            )
                        }
                    }
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
                                if (loading) Color(0x66FFFFFF) else accent,
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
                    // ── 单一指示器 ──────────────────────────────────────────
                    // 只有一个位置真源 ind（档位坐标，0..last 连续值）：空闲时落在选中项，
                    // 拖动时跟手，松手吸附到目标项。不再有第二颗圆点补位。
                    val ind = remember { Animatable(idx.toFloat()) }
                    var dragging by remember { mutableStateOf(false) }
                    var dragPos by remember { mutableFloatStateOf(idx.toFloat()) }
                    var stretch by remember { mutableFloatStateOf(0f) }   // 0..0.7，速度驱动的横向拉伸
                    var moving by remember { mutableStateOf(false) }        // 动画中
                    var pendingTarget by remember { mutableFloatStateOf(idx.toFloat()) }
                    var barShift by remember { mutableFloatStateOf(0f) }    // 底栏受力位移（绘制层）


                    fun commit(i: Int) {
                        val key = opts[i.coerceIn(0, opts.size - 1)].first
                        if (key == sort) return
                        sort = key
                        // 只是换排序：对当前这批结果本地重排，立刻生效，不打服务端
                        if (items.isNotEmpty()) {
                            items = sortItems(items, key)
                            scope.launch { listState.scrollToItem(0) }
                        }
                    }


                    // 容器
                    Box(
                        Modifier.fillMaxSize()
                            .graphicsLayer { translationX = barShift }
                            .drawBackdrop(
                                backdrop = backdrop,
                                shape = { RoundedCornerShape(23.dp) },
                                effects = { glass(Glass(3.dp, 9.dp, 18.dp)) },
                                highlight = GlassHi
                            )
                            .clip(RoundedCornerShape(23.dp))
                            .background(Color(0x2E101828))          // 只压很薄一层，保持和其它玻璃一样透
                            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(23.dp))
                            // 一个手势区同时处理：原地松手=点击，拖动=跟手
                            .pointerInput(tabPx) {
                                awaitEachGesture {
                                  try {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val startX = down.position.x
                                    var totalDx = 0f
                                    var moved = false
                                    fun slotAt(px: Float) =
                                        (px / tabPx - 0.5f).coerceIn(0f, last)
                                    // 按住时滑块留在原位：不因为手指落在别的项上就先跳过去
                                    dragPos = ind.value
                                    dragging = false
                                    while (true) {
                                        val ev = awaitPointerEvent()
                                        val ch = ev.changes.firstOrNull() ?: break
                                        if (!ch.pressed) break
                                        val dx = ch.position.x - ch.previousPosition.x
                                        totalDx += dx
                                        // 超过横向阈值才进入拖动，之前一律当点按
                                        if (!moved && kotlin.math.abs(totalDx) > 6f) {
                                            moved = true
                                            dragging = true
                                        }
                                        if (moved) {
                                            ch.consume()
                                            // 已经到端头还在往外推：底栏跟着让一点（1~2dp）
                                            val rawSlot = ch.position.x / tabPx - 0.5f
                                            val over = rawSlot - rawSlot.coerceIn(0f, last)
                                            barShift = (over * tabPx * 0.10f)
                                                .coerceIn(-2.dp.toPx(), 2.dp.toPx())
                                            dragPos = slotAt(ch.position.x)
                                            // 跨项只留少量伸长（最多 +25%），不再拉成横跨两格
                                            stretch = (stretch * 0.55f +
                                                    kotlin.math.abs(dx) / tabPx * 1.0f)
                                                .coerceIn(0f, 0.25f)
                                        }
                                    }
                                    val target = (if (moved) dragPos
                                                  else startX / tabPx - 0.5f)
                                        .roundToInt().coerceIn(0, opts.size - 1)
                                    dragging = false
                                    // 吸附：150~220ms 减速，从当前画面位置接着走
                                    scope.launch {
                                        if (moved) ind.snapTo(dragPos)   // 从屏幕上画着的位置接着走
                                        moving = true
                                        pendingTarget = target.toFloat()
                                        try {
                                        val dist = kotlin.math.abs(target - ind.value)
                                        // 相邻 160ms，跨多项最多 220ms；decelerate 起步不突兀
                                        val dur = (150 + 70 * (dist / last.coerceAtLeast(1f)))
                                            .toInt().coerceIn(140, 220)
                                        ind.animateTo(
                                            target.toFloat(),
                                            tween(dur, easing = LinearOutSlowInEasing)
                                        )
                                        } finally { moving = false }
                                    }
                                    scope.launch {
                                        val b0 = barShift
                                        var kb = 0f
                                        while (kb < 1f) {
                                            delay(16); kb = (kb + 0.14f).coerceAtMost(1f)
                                            barShift = b0 * (1f - kb) * (1f - kb)   // 平方收敛，一次到位
                                        }
                                        barShift = 0f
                                    }
                                    scope.launch {
                                        val s0 = stretch
                                        var k = 0f
                                        while (k < 1f) {
                                            delay(16); k = (k + 0.10f).coerceAtMost(1f)
                                            stretch = s0 * (1f - k)
                                        }
                                        stretch = 0f
                                    }
                                    commit(target)      // 真正改排序只在这里发生一次
                                  } finally {
                                    // 手势被重组打断时也要收尾，否则 dragging 会卡住、色带一直挂着
                                    dragging = false
                                  }
                                }
                            }
                    ) {

                    // 唯一指示器：静止 = 一道折射软边；移动 = 同一轮廓上加主题色环带
                    // 色带强度：拖动时满值；吸附过程中按「离目标还剩多少」衰减，
                    // 于是衰减和位移减速在最后一段自然重叠，不会到位后再单独熄一段灯。
                    val remain = kotlin.math.abs(ind.value - pendingTarget)
                    val bandTarget = when {
                        dragging -> 1f
                        moving -> (remain / 0.55f).coerceIn(0f, 1f)
                        else -> 0f
                    }
                    val bandOn by animateFloatAsState(
                        targetValue = bandTarget,
                        animationSpec = tween(90),
                        label = "band"
                    )
                    Canvas(Modifier.fillMaxSize()) {
                        val slotW = size.width / opts.size
                        val cy = size.height / 2f
                        val posF = if (dragging) dragPos else ind.value

                        // 四项共用：槽宽 82%、栏高 80%，两端连续圆弧，中心 = 文字中心
                        val wPx = (slotW * 0.82f * (1f + stretch))
                            .coerceAtMost(slotW - 4.dp.toPx())
                        val hPx = size.height * 0.80f
                        // 中心 = 槽位中心 = 文字中心（不再做任何外移）
                        val cx = (slotW * (posF + 0.5f)).coerceIn(wPx / 2f, size.width - wPx / 2f)

                        val ap = android.graphics.Path()
                        PillMorph.toPath((stretch / 0.25f).coerceIn(0f, 1f), ap)
                        val mtx = android.graphics.Matrix()
                        mtx.postScale(wPx, hPx / 0.62f)
                        mtx.postTranslate(cx, cy)
                        ap.transform(mtx)
                        val cp = ap.asComposePath()

                        // 偏黑透底：中心比周围暗，底图仍透得出来；移动时让位给色带
                        drawPath(cp, Color.Black.copy(alpha = 0.26f * (1f - 0.70f * bandOn)))

                        // 移动时：同一轮廓外侧加一圈主题色带（accent 随壁纸走，不写死金黄）
                        // 色带贴在玻璃边本身：内缘清楚（1.3dp 同宽），外缘只留一点内羽化，
                        // 不再用 5.5dp 外扩描边，避免读成"胶囊外面套了一圈光"。
                        if (bandOn > 0.01f) {
                            drawPath(
                                cp,
                                accent.copy(alpha = 0.26f * bandOn),
                                style = Stroke(2.2.dp.toPx())
                            )
                            drawPath(
                                cp,
                                accent.copy(alpha = 0.72f * bandOn),
                                style = Stroke(1.3.dp.toPx())
                            )
                        }

                    }

                    // 文字层（不拦截手势）
                    Row(Modifier.fillMaxSize()) {
                        // 文字颜色跟着滑块走：离开就退黄，接近就变黄，停稳只亮当前项
                        val focusPos = if (dragging) dragPos else ind.value
                        opts.forEachIndexed { i, (k, label) ->
                            val gap = kotlin.math.abs(focusPos - i).coerceIn(0f, 1f)
                            val near = 1f - gap                       // 1 = 滑块正压在这一项
                            // 强调色现在是近白淡调，选中/未选靠「亮度差」区分：
                            // 未选压到 55% 白，接近 FlowMix 那种「高亮项白、其余灰」的读法
                            val tc = androidx.compose.ui.graphics.lerp(
                                Color(0x8CFFFFFF),
                                accent,   // 选中字用壁纸主题色
                                near * near
                            )
                            Box(
                                Modifier.weight(1f).fillMaxHeight(),
                                contentAlignment = Alignment.Center
                            ) {
                                BasicText(
                                    label,
                                    style = ts(
                                        13.5f, tc,
                                        if (near > 0.5f) FontWeight.Bold else FontWeight.Medium
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
                                        val o = api("/api/addall?" + ADD_PATH_Q.trimStart('&'))
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
                                                val o = api("/api/add?h=" + it.hash + ADD_PATH_Q)
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
                                            val o = api("/api/add?h=" + it.hash + ADD_PATH_Q)
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
                        backdrop = fgBackdrop,
                        shape = { RoundedCornerShape(22.dp) },
                        effects = { glass(Glass(2.dp, 8.dp, 16.dp, sat = 1.05f)) }
                    )
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color(0x1F000000))      // 极淡压一层，保证字可读又不挡住内容
                    .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(22.dp))
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PagBtn("← 上一页", page > 1, accent) { runSearch(query, sort, page - 1) }
                Spacer(Modifier.weight(1f))
                BasicText("$page / $pages", style = ts(13f, Color(0xCCFFFFFF), FontWeight.Bold))
                Spacer(Modifier.weight(1f))
                PagBtn("下一页 →", page < pages, accent) { runSearch(query, sort, page + 1) }
            }
        }

        // ---- 外观设置（覆盖层） ----
        if (showSettings) {
            SettingsPane(
                backdrop = backdrop,
                look = look,
                onChange = { l ->
                    look = l; saveLook(ctx, l)
                    // 开关「跟随壁纸取色」要立刻生效：关掉回默认系统色，打开重新取一次
                    scope.launch {
                        accent = if (l.flowColor) {
                            withContext(Dispatchers.IO) { loadWall(ctx)?.let { pickAccent(it) } } ?: DEFAULT_ACCENT
                        } else DEFAULT_ACCENT
                    }
                },
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
                    scope.launch {
                        val acc = try {
                            withContext(Dispatchers.IO) {
                                val bmp = BitmapFactory.decodeResource(ctx.resources, R.drawable.bg)
                                if (look.flowColor) pickAccent(bmp) else DEFAULT_ACCENT
                            }
                        } catch (_: Exception) { DEFAULT_ACCENT }
                        accent = acc
                        wp = null; wallEpoch++
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
            // 只透壁纸（backdrop = 背景层），不再采样列表内容，避免和设置项文字叠在一起
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RoundedCornerShape(0.dp) },
                effects = { blur(18.dp.toPx()) }
            )
            .background(Color(0x730A0D16))
            .clickable(interactionSource = scrimIr, indication = null) { }   // 吞掉穿透点击
    ) {
        Column(
            Modifier.fillMaxSize()
                .padding(horizontal = 10.dp)
                .padding(top = 42.dp, bottom = 14.dp)
                // 一整个大玻璃面板
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { RoundedCornerShape(30.dp) },
                    effects = { glass(Glass(6.dp, 10.dp, 18.dp)) },
                    highlight = GlassHi
                )
                .clip(RoundedCornerShape(30.dp))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp)
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
private fun PillText(txt: String, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(11.dp))
            .background(Color(0x33FFFFFF))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        BasicText(
            txt, style = ts(11.5f, Color(0xDDE0F2FE)),
            maxLines = 1, softWrap = false,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CenterHint(txt: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        BasicText(txt, style = ts(13.5f, Color(0x99FFFFFF)))
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

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val n = options.size
            val segW = maxWidth / n
            // 和排序条同一套手感：位置用 spring，选中块滑动过去
            val pos by animateFloatAsState(
                targetValue = index.toFloat(),
                animationSpec = spring(dampingRatio = 0.72f, stiffness = 420f),
                label = "segPos"
            )
            Box(
                Modifier.fillMaxWidth()
                    .height(42.dp)
                    // 暗槽：左右镂空，看得出是"挖进去"的一条
                    .clip(RoundedCornerShape(21.dp))
                    .background(Color(0x590B0E16))
            ) {
                Box(
                    Modifier
                        .offset(x = segW * pos)
                        .width(segW)
                        .fillMaxHeight()
                        .padding(4.dp)
                        .clip(RoundedCornerShape(17.dp))
                        .background(ACCENT)
                )
                Row(Modifier.fillMaxSize()) {
                    options.forEachIndexed { i, label ->
                        val on = i == index
                        val ir = remember { MutableInteractionSource() }
                        Box(
                            Modifier.weight(1f).fillMaxHeight()
                                .clickable(interactionSource = ir, indication = null) { onPick(i) },
                            contentAlignment = Alignment.Center
                        ) {
                            BasicText(
                                label,
                                style = ts(
                                    12.5f,
                                    if (on) {
                                        if (ACCENT.luminance() > 0.45f) Color(0xFF1C1B1F) else Color.White
                                    } else Color(0xB3FFFFFF),
                                    if (on) FontWeight.Bold else FontWeight.Medium
                                ),
                                maxLines = 1, softWrap = false
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PagBtn(label: String, enabled: Boolean, tint: Color, onClick: () -> Unit) {
    BasicText(
        label,
        style = ts(13.5f, if (enabled) tint else Color(0x44FFFFFF),
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
