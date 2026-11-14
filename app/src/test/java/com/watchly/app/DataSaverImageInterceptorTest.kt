package com.watchly.app

import android.app.Application
import android.content.Context
import android.graphics.drawable.ColorDrawable
import androidx.test.core.app.ApplicationProvider
import coil.decode.DataSource
import coil.intercept.Interceptor
import coil.request.ImageRequest
import coil.request.ImageResult
import coil.request.SuccessResult
import coil.size.Size
import com.watchly.app.data.settings.UiPreferencesRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Data Saver at the image layer (cycle 10, §14.1): with the
 * toggle on, the interceptor must hand Coil the SMALLER URL (so
 * the smaller file is what downloads); with it off, requests pass
 * through untouched. A fake chain captures what would be fetched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DataSaverImageInterceptorTest {

    private class FakeChain(
        override val request: ImageRequest
    ) : Interceptor.Chain {
        var proceededWith: ImageRequest? = null
        override val size: Size = Size.ORIGINAL
        override fun withRequest(request: ImageRequest): Interceptor.Chain =
            FakeChain(request)
        override fun withSize(size: Size): Interceptor.Chain = this
        override suspend fun proceed(request: ImageRequest): ImageResult {
            proceededWith = request
            return SuccessResult(
                drawable = ColorDrawable(),
                request = request,
                dataSource = DataSource.MEMORY
            )
        }
    }

    private lateinit var context: Application
    private lateinit var prefs: UiPreferencesRepository
    private val interceptor = DataSaverImageInterceptor()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(
            UiPreferencesRepository.PREFS_NAME, Context.MODE_PRIVATE
        ).edit().clear().commit()
        prefs = UiPreferencesRepository(context)
    }

    private fun requestFor(data: Any): ImageRequest =
        ImageRequest.Builder(context).data(data).build()

    @Test
    fun `saver on rewrites a tmdb poster to the smaller size`() = runBlocking {
        prefs.setDataSaverEnabled(true)
        val chain = FakeChain(
            requestFor("https://image.tmdb.org/t/p/w500/poster.jpg")
        )
        interceptor.intercept(chain)
        assertEquals(
            "https://image.tmdb.org/t/p/w342/poster.jpg",
            chain.proceededWith?.data
        )
    }

    @Test
    fun `saver off passes the request through untouched`() = runBlocking {
        val url = "https://image.tmdb.org/t/p/w500/poster.jpg"
        val chain = FakeChain(requestFor(url))
        interceptor.intercept(chain)
        assertEquals(url, chain.proceededWith?.data)
    }

    @Test
    fun `saver on leaves other providers and non string data alone`() = runBlocking {
        prefs.setDataSaverEnabled(true)
        val tvmaze = FakeChain(
            requestFor("https://static.tvmaze.com/uploads/images/original_untouched/1/2.jpg")
        )
        interceptor.intercept(tvmaze)
        assertEquals(
            "https://static.tvmaze.com/uploads/images/original_untouched/1/2.jpg",
            tvmaze.proceededWith?.data
        )

        val resId = FakeChain(requestFor(android.R.drawable.ic_menu_gallery))
        interceptor.intercept(resId)
        assertEquals(android.R.drawable.ic_menu_gallery, resId.proceededWith?.data)
        assertNull(resId.proceededWith?.target)
    }
}
