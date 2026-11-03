package com.zevan.soundparty.data.remote.dto

import com.google.gson.annotations.SerializedName

data class SubsonicResponseContainer(
    @SerializedName("subsonic-response") val subsonicResponse: SubsonicResponse
)

data class SubsonicResponse(
    val status: String,
    val version: String,
    val searchResult3: SearchResult3?
)

data class SearchResult3(
    val song: List<SongDto>?
)

data class SongDto(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Int,
    val streamUrl: String?
)