package com.autonoumus.ai.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.autonoumus.ai.domain.model.SavedLocationTemplate
import com.autonoumus.ai.domain.repository.SavedLocationTemplateRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SavedLocationTemplateRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : SavedLocationTemplateRepository {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private val templatesState = MutableStateFlow<List<SavedLocationTemplate>>(loadTemplatesFromStorage())
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()

    companion object {
        private const val PREF_NAME = "gsm_saved_location_templates_prefs"
        private const val KEY_SAVED_TEMPLATES_JSON = "key_saved_templates_json"
    }

    override fun getSavedTemplates(): Flow<List<SavedLocationTemplate>> = templatesState.asStateFlow()

    override suspend fun saveTemplate(template: SavedLocationTemplate) {
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                val current = loadTemplatesFromStorage().toMutableList()
                val index = current.indexOfFirst { it.id == template.id }
                if (index != -1) {
                    current[index] = template
                } else {
                    current.add(0, template) // Add new item to top
                }
                persistTemplates(current)
                templatesState.value = current
            }
        }
    }

    override suspend fun deleteTemplate(templateId: String) {
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                val current = loadTemplatesFromStorage().toMutableList()
                val removed = current.removeAll { it.id == templateId }
                if (removed) {
                    persistTemplates(current)
                    templatesState.value = current
                }
            }
        }
    }

    override suspend fun getTemplateById(templateId: String): SavedLocationTemplate? {
        return templatesState.value.firstOrNull { it.id == templateId }
            ?: withContext(Dispatchers.IO) {
                loadTemplatesFromStorage().firstOrNull { it.id == templateId }
            }
    }

    private fun loadTemplatesFromStorage(): List<SavedLocationTemplate> {
        val rawJson = prefs.getString(KEY_SAVED_TEMPLATES_JSON, null) ?: return emptyList()
        val list = mutableListOf<SavedLocationTemplate>()
        try {
            val array = JSONArray(rawJson)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val template = SavedLocationTemplate(
                    id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                    name = obj.optString("name", ""),
                    country = obj.optString("country", ""),
                    address = obj.optString("address", ""),
                    latitude = obj.optString("latitude", ""),
                    longitude = obj.optString("longitude", ""),
                    templateName = obj.optString("templateName", "GPS_FULL_CARD"),
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                )
                list.add(template)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    private fun persistTemplates(list: List<SavedLocationTemplate>) {
        try {
            val array = JSONArray()
            for (item in list) {
                val obj = JSONObject().apply {
                    put("id", item.id)
                    put("name", item.name)
                    put("country", item.country)
                    put("address", item.address)
                    put("latitude", item.latitude)
                    put("longitude", item.longitude)
                    put("templateName", item.templateName)
                    put("createdAt", item.createdAt)
                }
                array.put(obj)
            }
            prefs.edit().putString(KEY_SAVED_TEMPLATES_JSON, array.toString()).commit()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
