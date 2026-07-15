package com.flux.payload.dumper.core

import com.flux.payload.dumper.data.Preferences
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Builds an OkHttpClient that injects the user's custom User-Agent and optional extra header. */
object Net {

    const val DEFAULT_USER_AGENT = "PayloadDumperFlux/2.0 (Android)"

    fun buildClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", currentUserAgent())
                .build()
            chain.proceed(request)
        }
        .build()

    private fun currentUserAgent(): String {
        val enabled = Preferences.getBoolean(Preferences.KEY_CUSTOM_UA_ENABLED, false)
        val custom = Preferences.getString(Preferences.KEY_CUSTOM_UA)
        return if (enabled && !custom.isNullOrBlank()) custom else DEFAULT_USER_AGENT
    }
}
