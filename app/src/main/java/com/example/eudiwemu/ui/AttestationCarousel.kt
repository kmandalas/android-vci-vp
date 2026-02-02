package com.example.eudiwemu.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.carousel.HorizontalUncontainedCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A carousel displaying WIA and WUA attestation cards.
 * Users can swipe horizontally to switch between cards.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttestationCarousel(
    wiaInfo: Map<String, Any>?,
    wuaInfo: Map<String, Any>?
) {
    // Build list of available attestations
    val items = buildList {
        wiaInfo?.let { add("WIA" to it) }
        wuaInfo?.let { add("WUA" to it) }
    }

    // Don't render if no attestations available
    if (items.isEmpty()) return

    val carouselState = rememberCarouselState { items.size }

    HorizontalUncontainedCarousel(
        state = carouselState,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(200.dp),
        itemWidth = 300.dp,
        itemSpacing = 12.dp
    ) { index ->
        val (type, info) = items[index]
        when (type) {
            "WIA" -> WiaStatusCard(info)
            "WUA" -> WuaStatusCard(info)
        }
    }
}
