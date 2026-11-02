package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Platform

/** Small colored square with the platform's initials ("YT", "f", "IG", ...). */
@Composable
fun PlatformBadge(
    platform: Platform,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size / 4))
            .background(Color(platform.color)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = platform.badge,
            color = Color.White,
            fontWeight = FontWeight.Black,
            fontSize = (size.value * 0.38f).sp
        )
    }
}
