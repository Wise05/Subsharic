package com.zevan.soundparty

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.zevan.soundparty.data.remote.NavidromeApi
import com.zevan.soundparty.data.remote.SubsonicAuthInterceptor
import com.zevan.soundparty.data.repository.MediaRepository
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Optional: set layout view here if using XML layouts
        // setContentView(R.layout.activity_main)

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(SubsonicAuthInterceptor({ "zevan" }, { "(!z@kG0ose3)" }))
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl("http://100.65.130.65:4533/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        val api = retrofit.create(NavidromeApi::class.java)
        val repository = MediaRepository(api, "http://100.65.130.65:4533/")

        lifecycleScope.launch {
            try {
                val isConnected = repository.testConnection()
                Log.d("NavidromeTest", "Connection successful: $isConnected")

                if (isConnected) {
                    val tracks = repository.searchTracks("Rock")
                    Log.d("NavidromeTest", "Found ${tracks.size} tracks")
                } else {
                    Log.w("NavidromeTest", "Skipping search: Could not connect to server.")
                }
            } catch (e: Exception) {
                Log.e("NavidromeTest", "Error during Navidrome call", e)
            }
        }
    }
}