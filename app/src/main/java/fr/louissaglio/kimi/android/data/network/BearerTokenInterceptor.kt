package fr.louissaglio.kimi.android.data.network

import fr.louissaglio.kimi.android.data.settings.ServerSettings
import okhttp3.Interceptor
import okhttp3.Response

class BearerTokenInterceptor(
    private val settingsProvider: () -> ServerSettings,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("Authorization") != null) return chain.proceed(request)
        val settings = settingsProvider()
        if (!settings.hasCredentials) return chain.proceed(request)
        return chain.proceed(
            request.newBuilder()
                .header("Authorization", "Bearer ${settings.token}")
                .build(),
        )
    }
}
