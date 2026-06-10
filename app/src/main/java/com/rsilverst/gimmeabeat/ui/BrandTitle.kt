package com.rsilverst.gimmeabeat.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** "Gimme[ⒶHeart]Beat" wordmark — heart icon with "A" cut out of its center. */
@Composable
fun BrandTitle(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WordPiece("Gimme")
        AHeart(
            size = 22.dp,
            modifier = Modifier.padding(horizontal = 1.dp),
        )
        WordPiece("Beat")
    }
}

/**
 * A glyph that reads as both an "A" and a heart: a filled heart in primary,
 * with a bold letter "A" carved out (background-colored) sitting in its center.
 */
@Composable
fun AHeart(size: Dp, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val background = MaterialTheme.colorScheme.background
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Favorite,
            contentDescription = null,
            tint = primary,
            modifier = Modifier.fillMaxSize(),
        )
        Text(
            text = "A",
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Black,
            fontSize = (size.value * 0.62f).sp,
            color = background,
            modifier = Modifier.offset(y = 1.dp),
        )
    }
}

@Composable
private fun WordPiece(text: String) {
    Text(
        text = text,
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 22.sp,
        letterSpacing = (-0.4).sp,
        color = MaterialTheme.colorScheme.onBackground,
    )
}
