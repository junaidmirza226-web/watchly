/*
 * Watchly — Application class (cycle 10).
 *
 * Exists for one job: installing the app-wide Coil image loader
 * with the Data Saver interceptor. Every AsyncImage in the app
 * resolves through Coil's singleton loader, so this is the single
 * point where image requests can be downscaled when Data Saver
 * (§14.1) is on — the smaller file is what actually downloads.
 * With Data Saver off the interceptor is a pure pass-through and
 * behaviour is exactly as before.
 */
package com.watchly.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.intercept.Interceptor
import coil.request.ImageResult
import com.watchly.app.data.settings.DataSaverImagePolicy
import com.watchly.app.data.settings.UiPreferencesRepository

/**
 * Rewrites image URLs to their smaller provider variant while
 * Data Saver is on. The preference is read per request from the
 * request's own context — SharedPreferences reads are in-memory
 * after first load, and reading live means the toggle takes
 * effect without an app restart.
 */
class DataSaverImageInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val data = request.data
        if (data is String &&
            UiPreferencesRepository(request.context).dataSaverEnabled()
        ) {
            val smaller = DataSaverImagePolicy.smallerUrl(data)
            if (smaller != data) {
                return chain.proceed(
                    request.newBuilder().data(smaller).build()
                )
            }
        }
        return chain.proceed(request)
    }
}

class WatchlyApplication : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        // Profiles (§8, cycle 12): first-run migration creates the
        // default "Profile 1" metadata. The data itself is not
        // touched — Profile 1's files are the legacy files, so the
        // migration cannot lose anything. Every store ALSO ensures
        // this lazily, so no entry point can observe a pre-profile
        // world; doing it here keeps the first frame consistent.
        com.watchly.app.data.library.ProfilesRepository(this).ensureMigrated()
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components { add(DataSaverImageInterceptor()) }
            .build()
}
