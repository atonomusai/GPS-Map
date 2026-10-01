package com.autonoumus.ai.domain.repository

import android.net.Uri
import com.autonoumus.ai.domain.model.RecentCaptureItem

interface MediaCaptureRepository {
    suspend fun getRecentCaptures(limit: Int): List<RecentCaptureItem>
    suspend fun getAllCaptures(): List<RecentCaptureItem>
    suspend fun deleteCaptures(uris: List<Uri>): Boolean
    suspend fun getLatestCaptureUri(): Pair<Uri, Boolean>?
}

