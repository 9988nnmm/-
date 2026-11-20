package com.fish.mags

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GlassShowcase() }
    }
}

private fun t(
    text: String,
    size: Float,
    color: Color = Color.White,
    weight: FontWeight = FontWeight.Normal
) = TextStyle(color = color, fontSize = size.sp, fontWeight = weight)

@Composable
fun GlassShowcase() {
    val backdrop = rememberLayerBackdrop()

    Box(Modifier.fillMaxSize()) {

        // ---------------- 背景层：玻璃要折射的内容 ----------------
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(
                        0f to Color(0xFF5B6CF9),
                        0.45f to Color(0xFF22B8F0),
                        1f to Color(0xFF0B1220)
                    )
                )
            )
            // 彩色光斑
            Box(Modifier.size(260.dp).offset(20.dp, 130.dp)
                .background(Color(0x88A855F7), RoundedCornerShape(130.dp)))
            Box(Modifier.size(210.dp).offset(215.dp, 400.dp)
                .background(Color(0x7722D3EE), RoundedCornerShape(105.dp)))
            Box(Modifier.size(240.dp).offset(50.dp, 640.dp)
                .background(Color(0x6699F6E4), RoundedCornerShape(120.dp)))
            // 细条纹 —— 最能看出边缘有没有被弯曲
            Column(Modifier.fillMaxSize().padding(top = 30.dp)) {
                repeat(30) { i ->
                    Box(Modifier.fillMaxWidth().height(13.dp)
                        .background(if (i % 2 == 0) Color(0x1AFFFFFF) else Color.Transparent))
                }
            }
        }

        // ---------------- 玻璃层 ----------------
        Column(
            Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.height(28.dp))
            BasicText("液态玻璃 · 真折射", style = t("", 23f, weight = FontWeight.Bold))
            BasicText(
                "同一张背景下：卡片①只有模糊，卡片②加了 lens() 折射",
                style = t("", 12.5f, Color(0xCCE2E8F0))
            )
            Spacer(Modifier.height(4.dp))

            GlassCard(backdrop, "① 只有模糊 blur(20dp)", "背景被糊掉，边缘是平的", true)
            GlassCard(backdrop, "② 模糊 + 折射 lens(12,24)", "背景看得清，边缘被弯曲 —— 这才是液态玻璃", false)

            Spacer(Modifier.height(6.dp))

            // 玻璃胶囊
            Box(
                Modifier.fillMaxWidth().height(56.dp).drawBackdrop(
                    backdrop = backdrop,
                    shape = { RoundedCornerShape(28.dp) },
                    effects = {
                        vibrancy()
                        blur(2.dp.toPx())
                        lens(10.dp.toPx(), 20.dp.toPx())
                    }
                ),
                contentAlignment = Alignment.Center
            ) {
                BasicText("玻璃胶囊按钮（真折射）", style = t("", 15f, weight = FontWeight.SemiBold))
            }

            // 对照组：网页 CSS 能做到的样子
            Box(
                Modifier.fillMaxWidth().height(56.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color(0x26FFFFFF)),
                contentAlignment = Alignment.Center
            ) {
                BasicText("② 普通半透明（现在网页版的效果）", style = t("", 14f, Color(0xCCFFFFFF)))
            }
        }
    }
}

@Composable
fun GlassCard(backdrop: LayerBackdrop, title: String, desc: String, blurOnly: Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(118.dp)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RoundedCornerShape(26.dp) },
                effects = {
                    vibrancy()
                    if (blurOnly) {
                        blur(20.dp.toPx())
                    } else {
                        blur(2.dp.toPx())
                        lens(12.dp.toPx(), 24.dp.toPx())
                    }
                }
            )
            .padding(20.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Column {
            BasicText(title, style = t("", 16f, weight = FontWeight.Bold))
            Spacer(Modifier.height(6.dp))
            BasicText(desc, style = t("", 13f, Color(0xDDE8EEF7)))
        }
    }
}
