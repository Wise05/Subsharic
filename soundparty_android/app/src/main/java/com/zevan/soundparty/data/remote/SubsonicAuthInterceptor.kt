package com.zevan.soundparty.data.remote

import okhttp3.Interceptor
import okhttp3.Response
import java.security.MessageDigest
import java.util.UUID

class SubsonicAuthInterceptor(
    private val usernameProvider: () -> String,
    private val passwordProvider: () -> String
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val originalUrl = originalRequest.url

        val salt = UUID.randomUUID().toString().substring(0, 8)
        val token = md5(passwordProvider() + salt)

        val newUrl = originalUrl.newBuilder()
            .addQueryParameter("u", usernameProvider())
            .addQueryParameter("t", token)
            .addQueryParameter("s", salt)
            .addQueryParameter("v", "1.16.1")
            .addQueryParameter("c", "SoundPartyAndroid")
            .addQueryParameter("f", "json")
            .build()

        val newRequest = originalRequest.newBuilder()
            .url(newUrl)
            .build()

        return chain.proceed(newRequest)
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}