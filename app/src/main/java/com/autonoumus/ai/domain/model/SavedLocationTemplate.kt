package com.autonoumus.ai.domain.model

data class SavedLocationTemplate(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",
    val country: String = "",
    val address: String = "",
    val latitude: String = "",
    val longitude: String = "",
    val templateName: String = StampTemplate.GPS_FULL_CARD.name,
    val createdAt: Long = System.currentTimeMillis()
)
