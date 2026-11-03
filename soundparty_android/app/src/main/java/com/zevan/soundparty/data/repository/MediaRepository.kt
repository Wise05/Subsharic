package com.zevan.soundparty.data.repository

import com.zevan.soundparty.data.remote.NavidromeApi
import com.zevan.soundparty.data.remote.dto.SongDto

class MediaRepository(
    private val api: NavidromeApi,
    private val baseUrl: String
) {
    suspend fun testConnection(): Boolean {
        return try {
            val response = api.ping()
            response.subsonicResponse.status == "ok"
        } catch (e: Exception) {
            false
        }
    }

    suspend fun searchTracks(query: String): List<SongDto> {
        return try {
            val response = api.searchSongs(query)
            response.subsonicResponse.searchResult3?.song ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getDirectStreamUrl(trackId: String): String {
        // Constructs backend proxy or direct Subsonic stream endpoint
        return "${baseUrl}api/stream/$trackId"
    }
}