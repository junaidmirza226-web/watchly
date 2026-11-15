/*
 * Watchly — TMDB key screen state holder.
 *
 * The user pastes their own free TMDB key. The key is tested against
 * TMDB before it is saved: a key TMDB rejects is never stored, so a
 * typo can't silently leave the app looking "connected" while every
 * TMDB row fails. The key stays on this phone only (see
 * TmdbKeyRepository).
 */
package com.watchly.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.watchly.app.data.remote.TmdbApi
import com.watchly.app.data.settings.TmdbKeyRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TmdbKeyUiState(
    /** A key is already saved on this phone. */
    val hasSavedKey: Boolean = false,
    /** The text currently in the input field. */
    val input: String = "",
    /** A validation request is in flight. */
    val isTesting: Boolean = false,
    /** Positive, user-safe message (saved, removed). */
    val message: String? = null,
    /** Problem message, user-safe (bad key, no connection). */
    val errorMessage: String? = null
)

class TmdbKeyViewModel(
    private val keys: TmdbKeyRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        TmdbKeyUiState(hasSavedKey = keys.hasKey())
    )
    val uiState: StateFlow<TmdbKeyUiState> = _uiState.asStateFlow()

    fun onInputChange(value: String) {
        _uiState.update { it.copy(input = value, message = null, errorMessage = null) }
    }

    /** Tests the typed key against TMDB, then saves it if accepted. */
    fun testAndSave() {
        val candidate = _uiState.value.input.trim()
        if (candidate.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Paste your TMDB key first.") }
            return
        }
        _uiState.update { it.copy(isTesting = true, message = null, errorMessage = null) }
        viewModelScope.launch {
            val accepted = runCatching { TmdbApi.validateKey(candidate) }
            accepted.fold(
                onSuccess = { valid ->
                    if (valid) {
                        keys.setKey(candidate)
                        _uiState.update {
                            it.copy(
                                isTesting = false,
                                hasSavedKey = true,
                                input = "",
                                message = "Saved — TMDB is on. The full movie catalogue " +
                                    "and industry rows are now unlocked."
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                isTesting = false,
                                errorMessage = "TMDB didn't accept that key. Check for " +
                                    "missing characters and try again."
                            )
                        }
                    }
                },
                onFailure = {
                    _uiState.update {
                        it.copy(
                            isTesting = false,
                            errorMessage = "Couldn't reach TMDB. Key not saved."
                        )
                    }
                }
            )
        }
    }

    /** Removes the saved key; TMDB features hide gracefully. */
    fun removeKey() {
        keys.clearKey()
        _uiState.update {
            it.copy(
                hasSavedKey = false,
                message = "Key removed. Watchly is back to the free " +
                    "public-domain catalogue.",
                errorMessage = null
            )
        }
    }
}

/** Factory so the nav destination can create the ViewModel with a Context. */
class TmdbKeyViewModelFactory(
    private val context: android.content.Context
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TmdbKeyViewModel::class.java)) {
            return TmdbKeyViewModel(
                TmdbKeyRepository(context.applicationContext)
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: $modelClass")
    }
}
