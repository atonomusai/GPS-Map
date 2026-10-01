package com.autonoumus.ai.domain.model

import androidx.annotation.FontRes
import com.autonoumus.ai.R

enum class StampFontStyle(@FontRes val fontRes: Int, val displayTitle: String) {
    REGULAR(R.font.instrument_sans_regular, "Regular"),
    MEDIUM(R.font.instrument_sans_medium, "Medium"),
    BOLD(R.font.instrument_sans_bold, "Bold")
}
