package com.zevan.soundparty.data.remote

import com.zevan.soundparty.data.remote.dto.SubsonicResponseContainer
import retrofit2.http.GET
import retrofit2.http.Query

interface NavidromeApi {
    @GET("rest/ping.view")
    suspend fun ping(): SubsonicResponseContainer

    @GET("rest/search3.view")
    suspend fun searchSongs(
        @Query("query") query: String
    ): SubsonicResponseContainer
}