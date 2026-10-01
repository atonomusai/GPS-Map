package com.autonoumus.ai.domain.model

import androidx.annotation.DrawableRes
import com.autonoumus.ai.R

enum class StampTemplate(val displayTitle: String, @DrawableRes val previewImageRes: Int) {
    PARIS_GLASS("Paris Glass", R.drawable.img_template_paris),
    LONDON_CATHEDRAL("London Cathedral", R.drawable.img_template_cathedral),
    PISA_CAPSULE("Pisa Capsule", R.drawable.img_template_pisa),
    BIG_BEN_MINIMAL("Monument Minimal", R.drawable.img_template_monument),
    GPS_FULL_CARD("GPS Full Card", R.drawable.img_street_view_mountain),
    PISA_PILL("Pisa Pill Minimal", R.drawable.img_template_pisa),
    NOTRE_DAME_ALARM("Cathedral Alarm Time", R.drawable.img_template_cathedral),
    CATHEDRAL_LEFT_MAP("Cathedral Left Map", R.drawable.img_template_cathedral),
    PISA_PIN_CARD("Pisa Map Pin", R.drawable.img_template_pisa),
    CYBER_HUD("Cyber HUD Stamp", R.drawable.img_theme_hud),
    RETRO_CAM("Retro Digital Cam", R.drawable.img_theme_vintage_red),
    FROSTED_BLUR("Frosted Blur Card", R.drawable.img_template_paris);

    companion object {
        fun fromNameOrDefault(name: String?): StampTemplate {
            if (name == null) return GPS_FULL_CARD
            return try {
                valueOf(name)
            } catch (e: Exception) {
                when (name) {
                    "MAP_GLASS_CARD" -> GPS_FULL_CARD
                    "MINIMAL_BAR" -> BIG_BEN_MINIMAL
                    "FULL_WIDTH_BAR" -> PARIS_GLASS
                    "CORNER_BADGE" -> LONDON_CATHEDRAL
                    "CLASSIC_STRIP" -> PISA_CAPSULE
                    "PISA_PILL_MINIMAL" -> PISA_PILL
                    "NOTRE_DAME" -> NOTRE_DAME_ALARM
                    "CATHEDRAL_MAP_LEFT" -> CATHEDRAL_LEFT_MAP
                    "PISA_PIN" -> PISA_PIN_CARD
                    "HUD" -> CYBER_HUD
                    "RETRO" -> RETRO_CAM
                    "FROSTED_GLASS", "BLUR", "BLUR_CARD", "FROSTED" -> FROSTED_BLUR
                    else -> GPS_FULL_CARD
                }
            }
        }
    }
}
