package com.autonoumus.ai.domain.repository

import com.autonoumus.ai.domain.model.SavedLocationTemplate
import kotlinx.coroutines.flow.Flow

interface SavedLocationTemplateRepository {
    fun getSavedTemplates(): Flow<List<SavedLocationTemplate>>
    suspend fun saveTemplate(template: SavedLocationTemplate)
    suspend fun deleteTemplate(templateId: String)
    suspend fun getTemplateById(templateId: String): SavedLocationTemplate?
}
