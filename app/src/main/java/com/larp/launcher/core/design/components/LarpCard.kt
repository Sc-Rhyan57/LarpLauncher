package com.larp.launcher.core.design.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun LarpCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentColor: Color = contentColorFor(containerColor),
    cornerRadius: Int = 24,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(cornerRadius.dp),
        color = containerColor,
        contentColor = contentColor,
        tonalElevation = 2.dp,
        shadowElevation = 1.dp
    ) {
        Box(modifier = Modifier.padding(padding)) {
            content()
        }
    }
}
