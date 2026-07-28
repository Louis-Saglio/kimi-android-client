package fr.louissaglio.kimi.android.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.louissaglio.kimi.android.data.attachments.AndroidAttachmentStore
import fr.louissaglio.kimi.android.data.attachments.AttachmentStore
import fr.louissaglio.kimi.android.data.network.BearerTokenInterceptor
import fr.louissaglio.kimi.android.data.settings.SettingsRepository
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/** Coalescing window for chat streaming deltas (see ChatViewModel.startSocket). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class StreamCoalesceWindowMillis

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @StreamCoalesceWindowMillis
    fun provideStreamCoalesceWindowMillis(): Long = 100L

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            produceFile = { context.preferencesDataStoreFile("server_settings") },
        )

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideJson(): Json = Json { ignoreUnknownKeys = true }

    @Provides
    @Singleton
    fun provideAttachmentStore(impl: AndroidAttachmentStore): AttachmentStore = impl

    @Provides
    @Singleton
    fun provideOkHttpClient(settingsRepository: SettingsRepository): OkHttpClient =
        OkHttpClient.Builder()
            // Read per request so saved-settings changes apply without rebuilding the client.
            .addInterceptor(BearerTokenInterceptor { settingsRepository.settings.value })
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
}
