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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Probe() }
    }
}

@Composable
fun Probe() {
    Box(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(
                listOf(Color(0xFF6366F1), Color(0xFF0EA5E9), Color(0xFF0B1220))
            )
        ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.padding(24.dp).clip(RoundedCornerShape(24.dp))
                .background(Color(0x22FFFFFF)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Compose 构建链路验证", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("如果装上了这个页面，说明 GitHub Actions 能编译 Compose 项目",
                 color = Color(0xFFCBD5E1), fontSize = 13.sp)
        }
    }
}
