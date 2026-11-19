package com.larp.launcher.core.design.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

@Composable
fun LarpAvatar(
    url: String?,
    fallbackInitial: String,
    modifier: Modifier = Modifier,
    size: Int = 40,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer
) {
    if (url.isNullOrBlank()) {
        Box(
            modifier = modifier
                .size(size.dp)
                .clip(RoundedCornerShape(50))
        ) {
            androidx.compose.material3.Surface(
                color = containerColor,
                contentColor = contentColor,
                shape = RoundedCornerShape(50),
                modifier = Modifier.size(size.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size.dp)) {
                    Text(
                        text = fallbackInitial.firstOrNull()?.uppercase() ?: "?",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    } else {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size.dp)
                .clip(RoundedCornerShape(50))
        )
    }
}
