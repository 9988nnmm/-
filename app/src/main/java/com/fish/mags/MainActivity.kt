package com.fish.mags

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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

@Composable
fun GlassShowcase() {
    val backdrop = rememberLayerBackdrop()

    Box(Modifier.fillMaxSize()) {
        // ---------- 背景层：被折射的内容 ----------
        Box(
            Modifier.fillMaxSize().layerBackdrop(backdrop)
        ) {
            // 渐变底
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(
                        0f to Color(0xFF5B6CF9),
                        0.45f to Color(0xFF22B8F0),
                        1f to Color(0xFF0B1220)
                    )
                )
            )
            // 彩色光斑（折射的细节来源）
            Box(
                Modifier.size(260.dp).offset(30.dp, 120.dp)
                    .background(Color(0x88A855F7), RoundedCornerShape(130.dp))
            )
            Box(
                Modifier.size(200.dp).offset(220.dp, 380.dp)
                    .background(Color(0x7722D3EE), RoundedCornerShape(100.dp))
            )
            Box(
                Modifier.size(240.dp).offset(60.dp, 620.dp)
                    .background(Color(0x6699F6E4), RoundedCornerShape(120.dp))
            )
            // 细条纹：最能看出折射有没有生效
            Column(Modifier.fillMaxSize().padding(top = 40.dp)) {
                repeat(26) { i ->
                    Box(
                        Modifier.fillMaxWidth().height(14.dp)
                            .background(if (i % 2 == 0) Color(0x18FFFFFF) else Color.Transparent)
                    )
                }
            }
        }

        // ---------- 玻璃层 ----------
        Column(
            Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "液态玻璃 · 真折射验证",
                color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
            )
            Text(
                "下面两张卡片用同一个背景：上面只有模糊，下面加了 lens() 折射",
                color = Color(0xCCE2E8F0), fontSize = 12.5.sp,
                modifier = Modifier.padding(bottom = 6.dp)
            )

            GlassCard(backdrop, "① 只有模糊 blur(20dp)", blurOnly = true)
            GlassCard(backdrop, "② 模糊 + 折射 lens(12,24)", blurOnly = false)

            Spacer(Modifier.height(8.dp))

            // 胶囊按钮
            Box(
                Modifier.fillMaxWidth().height(58.dp)
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { RoundedCornerShape(29.dp) },
                        effects = {
                            vibrancy()
                            blur(2.dp.toPx())
                            lens(10.dp.toPx(), 20.dp.toPx())
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text("玻璃胶囊按钮", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(4.dp))

            // 半透明磨砂（对照组：无折射无厚感）
            Box(
                Modifier.fillMaxWidth().height(58.dp)
                    .clip(RoundedCornerShape(29.dp))
                    .background(Color(0x22FFFFFF)),
                contentAlignment = Alignment.Center
            ) {
                Text("① 普通半透明（网页 CSS 效果）", color = Color(0xCCFFFFFF), fontSize = 14.sp)
            }
        }
    }
}

@Composable
fun GlassCard(backdrop: LayerBackdrop, title: String, blurOnly: Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(120.dp)
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
            .padding(22.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Column {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                if (blurOnly) "背景被糊掉，边缘是平的" else "背景看得清，边缘被弯曲 —— 这才是液态玻璃",
                color = Color(0xDDE8EEF7), fontSize = 13.sp
            )
        }
    }
}
