package online.myesstu.app

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight

/** Golos Text, a typeface drawn for Cyrillic. One variable file serves every weight. */
@OptIn(ExperimentalTextApi::class)
val AppFont = FontFamily(
    listOf(
        FontWeight.Normal to 400,
        FontWeight.Medium to 500,
        FontWeight.SemiBold to 600,
        FontWeight.Bold to 700,
    ).map { (weight, value) ->
        Font(R.font.golos_text, weight, variationSettings = FontVariation.Settings(FontVariation.weight(value)))
    },
)
