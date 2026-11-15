/*
 * Watchly — Person page state holder (spec §3.3, cycle 11).
 *
 * Loads one TMDB person: photo, name, biography and department
 * from /person/:id, plus the "Known for" titles from
 * /person/:id/combined_credits. The two calls degrade
 * independently, following the details page's rules:
 *  - person details failing is a real failure — the page shows a
 *    plain couldn't-load state with a working Retry, never a
 *    fabricated person;
 *  - "Known for" failing (or TMDB listing nothing) simply leaves
 *    the section absent — absence is a normal answer, not an error.
 * Everything needs the user's TMDB key; without one the page says
 * exactly that instead of loading forever.
 */
package com.watchly.app.ui.person

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.watchly.app.data.model.Person
import com.watchly.app.data.model.Title
import com.watchly.app.data.remote.TmdbApi
import com.watchly.app.data.settings.TmdbKeyRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PersonUiState(
    /** The tapped cast member's name — shown while loading. */
    val name: String,
    val isLoading: Boolean = true,
    /** The loaded person; null until the details call lands. */
    val person: Person? = null,
    /** "Known for" titles, strongest first. Empty = no section. */
    val knownFor: List<Title> = emptyList(),
    /** Non-null when the person couldn't load; safe to show. */
    val errorMessage: String? = null
)

class PersonViewModel(
    private val personId: Int,
    seedName: String,
    private val tmdbKeys: TmdbKeyRepository? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        PersonUiState(name = seedName)
    )
    val uiState: StateFlow<PersonUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    /** (Re)loads the person. Safe from the error state's Retry. */
    fun load() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val apiKey = tmdbKeys?.getKey()
            if (apiKey == null) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Person pages come from TMDB. " +
                            "Add your TMDB key in My Library to see them."
                    )
                }
                return@launch
            }
            val details = async {
                runCatching { TmdbApi.fetchPerson(apiKey, personId) }
            }
            val knownFor = async {
                runCatching { TmdbApi.fetchPersonKnownFor(apiKey, personId) }
            }
            val person = details.await().getOrNull()
            if (person == null) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Couldn't load this person."
                    )
                }
                return@launch
            }
            _uiState.update {
                it.copy(
                    isLoading = false,
                    person = person,
                    name = person.name,
                    knownFor = knownFor.await().getOrNull().orEmpty()
                )
            }
        }
    }
}

/** Factory so the nav destination can create the ViewModel per person. */
class PersonViewModelFactory(
    private val selected: SelectedPerson,
    private val context: Context
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PersonViewModel::class.java)) {
            val app = context.applicationContext
            return PersonViewModel(
                personId = selected.tmdbId,
                seedName = selected.name,
                tmdbKeys = TmdbKeyRepository(app)
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: $modelClass")
    }
}
