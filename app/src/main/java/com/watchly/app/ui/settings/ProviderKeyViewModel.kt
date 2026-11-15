/*
 * Watchly — one key screen state holder for the cycle-4 providers
 * (OMDb, Trakt, OpenSubtitles, Fanart).
 *
 * Same contract as the TMDB key screen: the pasted key is tested
 * against its own provider BEFORE it is saved, so a typo can never
 * leave the app looking "connected" while the feature silently
 * fails. A key the provider rejects (or that can't be reached) is
 * not stored. Keys live on this phone only (ProviderKeysRepository).
 */
package com.watchly.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.watchly.app.data.model.MediaType
import com.watchly.app.data.remote.FanartApi
import com.watchly.app.data.remote.OmdbApi
import com.watchly.app.data.remote.OpenSubtitlesApi
import com.watchly.app.data.remote.TraktApi
import com.watchly.app.data.settings.ApiProvider
import com.watchly.app.data.settings.ProviderKeysRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProviderKeyUiState(
    val hasSavedKey: Boolean = false,
    val input: String = "",
    val isTesting: Boolean = false,
    val message: String? = null,
    val errorMessage: String? = null
)

/** Plain-language explanation + the "what turns on" line per provider. */
data class ProviderKeyCopy(
    val screenTitle: String,
    val fieldLabel: String,
    val introConnected: String,
    val introDisconnected: String,
    val savedMessage: String,
    val removedMessage: String
)

fun providerKeyCopy(provider: ApiProvider): ProviderKeyCopy = when (provider) {
    ApiProvider.OMDB -> ProviderKeyCopy(
        screenTitle = "OMDb Key",
        fieldLabel = "OMDb API key",
        introConnected = "OMDb is connected ✓ — IMDb, Rotten Tomatoes and " +
            "Metacritic ratings appear on title pages.",
        introDisconnected = "OMDb adds the ratings everyone trusts — IMDb, " +
            "Rotten Tomatoes and Metacritic — to every title page that " +
            "has an IMDb page.",
        savedMessage = "Saved — IMDb, Rotten Tomatoes and Metacritic " +
            "ratings now appear on title pages.",
        removedMessage = "Key removed. Title pages show the standard ratings only."
    )
    ApiProvider.TRAKT -> ProviderKeyCopy(
        screenTitle = "Trakt Client ID",
        fieldLabel = "Trakt Client ID",
        introConnected = "Trakt is connected ✓ — Trending on Trakt is on " +
            "the Home screen and Trakt's community rating appears on " +
            "title pages.",
        introDisconnected = "Trakt tracks what the world is watching. " +
            "Paste your free app's Client ID for the Trending on Trakt " +
            "row and Trakt community ratings.",
        savedMessage = "Saved — Trending on Trakt is on Home, and Trakt " +
            "ratings appear on title pages.",
        removedMessage = "Key removed. The Trakt row and ratings are off."
    )
    ApiProvider.OPENSUBTITLES -> ProviderKeyCopy(
        screenTitle = "OpenSubtitles Key",
        fieldLabel = "OpenSubtitles API key",
        introConnected = "OpenSubtitles is connected ✓ — open the " +
            "Subtitles button in the player to find subtitles for " +
            "what you're watching.",
        introDisconnected = "OpenSubtitles is the big free subtitle " +
            "library. Paste your free API key and the player can find " +
            "and attach subtitles for films and episodes.",
        savedMessage = "Saved — subtitles are available from the " +
            "player's Subtitles button.",
        removedMessage = "Key removed. The player's subtitle search is off."
    )
    ApiProvider.FANART -> ProviderKeyCopy(
        screenTitle = "Fanart.tv Key",
        fieldLabel = "Fanart.tv API key",
        introConnected = "Fanart.tv is connected ✓ — HD clear logos and " +
            "extra backdrops enhance title pages where available.",
        introDisconnected = "Fanart.tv is a fan-art gallery with beautiful " +
            "HD logos and backdrops. Paste your free key and title " +
            "pages get the artwork upgrade where it exists.",
        savedMessage = "Saved — HD logos and extra artwork now enhance " +
            "title pages.",
        removedMessage = "Key removed. Title pages use the standard artwork only."
    )
}

class ProviderKeyViewModel(
    val provider: ApiProvider,
    private val keys: ProviderKeysRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ProviderKeyUiState(hasSavedKey = keys.hasKey(provider))
    )
    val uiState: StateFlow<ProviderKeyUiState> = _uiState.asStateFlow()

    fun onInputChange(value: String) {
        _uiState.update { it.copy(input = value, message = null, errorMessage = null) }
    }

    /** Tests the typed key against its provider, then saves it. */
    fun testAndSave() {
        val candidate = _uiState.value.input.trim()
        if (candidate.isEmpty()) {
            _uiState.update {
                it.copy(errorMessage = "Paste your ${provider.displayName} key first.")
            }
            return
        }
        _uiState.update { it.copy(isTesting = true, message = null, errorMessage = null) }
        viewModelScope.launch {
            val accepted = runCatching { validateKey(provider, candidate) }
                .getOrDefault(false)
            if (accepted) {
                keys.setKey(provider, candidate)
                _uiState.update {
                    it.copy(
                        isTesting = false,
                        hasSavedKey = true,
                        input = "",
                        message = providerKeyCopy(provider).savedMessage
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isTesting = false,
                        errorMessage = "${provider.displayName} didn't accept that " +
                            "key, or couldn't be reached. Check the key and your " +
                            "— nothing was saved."
                    )
                }
            }
        }
    }

    /** Removes the saved key; the feature hides gracefully. */
    fun removeKey() {
        keys.clearKey(provider)
        _uiState.update {
            it.copy(
                hasSavedKey = false,
                message = providerKeyCopy(provider).removedMessage,
                errorMessage = null
            )
        }
    }

    companion object {
        /**
         * One cheap, real call per provider: a lookup the provider
         * answers only for a valid key. (The Dark Knight — IMDb
         * tt0468569 / TMDB 155 — is the probe title; its ratings and
         * artwork were verified live for all four providers.)
         */
        suspend fun validateKey(provider: ApiProvider, key: String): Boolean =
            when (provider) {
                ApiProvider.OMDB ->
                    OmdbApi.fetchRatings(key, "tt0468569") != null
                ApiProvider.TRAKT ->
                    TraktApi.fetchTrendingMovies(key, limit = 1).isNotEmpty()
                ApiProvider.OPENSUBTITLES ->
                    OpenSubtitlesApi.ping(key)
                ApiProvider.FANART ->
                    FanartApi.fetchArtwork(key, 155, MediaType.MOVIE) != null
            }
    }
}

/** Factory so the nav destination can create the ViewModel with a Context. */
class ProviderKeyViewModelFactory(
    private val provider: ApiProvider,
    private val context: android.content.Context
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ProviderKeyViewModel::class.java)) {
            return ProviderKeyViewModel(
                provider,
                ProviderKeysRepository(context.applicationContext)
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: $modelClass")
    }
}
