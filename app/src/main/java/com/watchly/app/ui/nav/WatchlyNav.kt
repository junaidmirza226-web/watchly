/*
 * Watchly — app navigation shell.
 *
 * ASSUMED DEPENDENCIES:
 *  - androidx.navigation:navigation-compose:2.8.x (NavHost / rememberNavController)
 *  - androidx.compose.material3 + androidx.compose.foundation (Compose BOM)
 *  - androidx.compose.material:material-icons-extended
 *    (Home, Movie, Tv, Animation, VideoLibrary icons)
 *
 * Structure: Scaffold + gold-accented bottom NavigationBar with five tabs —
 * Home, Movies, Shows, Anime, My Library — all wired to their real
 * screens, plus pushed destinations for Search, Title Details, the
 * Player, and the TMDB key screen (reached from My Library).
 *
 * Wire-up: MainActivity calls WatchlyApp() inside WatchlyTheme { }.
 */
package com.watchly.app.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.watchly.app.data.model.Title
import com.watchly.app.ui.browse.BrowseKind
import com.watchly.app.ui.browse.BrowseScreen
import com.watchly.app.ui.details.TitleDetailsScreen
import com.watchly.app.ui.details.TitleDetailsStore
import com.watchly.app.ui.home.HomeScreen
import com.watchly.app.ui.library.LibraryScreen
import com.watchly.app.ui.lists.ListDetailScreen
import com.watchly.app.ui.lists.ListsScreen
import com.watchly.app.ui.lists.SelectedListStore
import com.watchly.app.ui.person.PersonScreen
import com.watchly.app.ui.person.PersonStore
import com.watchly.app.ui.player.PlaybackRequest
import com.watchly.app.ui.player.PlaybackSessionStore
import com.watchly.app.ui.player.PlayerScreen
import com.watchly.app.ui.search.SearchScreen
import com.watchly.app.ui.settings.TmdbKeyScreen
import com.watchly.app.ui.theme.WatchlyBlack
import com.watchly.app.ui.theme.WatchlyGold
import com.watchly.app.ui.theme.WatchlyGoldContainer
import com.watchly.app.ui.theme.WatchlyMuted
import com.watchly.app.ui.theme.WatchlySurface

/** Bottom-nav destinations. Route strings are stable — deep links reuse them. */
enum class WatchlyDestination(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    HOME("home", "Home", Icons.Filled.Home),
    MOVIES("movies", "Movies", Icons.Filled.Movie),
    SHOWS("shows", "Shows", Icons.Filled.Tv),
    ANIME("anime", "Anime", Icons.Filled.Animation),
    LIBRARY("library", "My Library", Icons.Filled.VideoLibrary)
}

/** Non-tab destinations (no bottom-nav entry of their own). */
object WatchlyRoutes {
    const val SEARCH = "search"
    const val DETAILS = "details"
    const val PLAYER = "player"
    const val TMDB_KEY = "tmdb_key"
    const val API_KEYS = "api_keys"
    const val PROVIDER_KEY = "provider_key"
    const val LISTS = "lists"
    const val LIST_DETAIL = "list_detail"
    const val BROWSER = "browser"
    const val PERSON = "person"
}

/** Root composable for the app. Place inside WatchlyTheme in MainActivity. */
@Composable
fun WatchlyApp(
    modifier: Modifier = Modifier
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    // The player is full-screen: no bottom bar while watching.
    val showBottomBar = navBackStackEntry?.destination?.route != WatchlyRoutes.PLAYER

    Scaffold(
        modifier = modifier,
        containerColor = WatchlyBlack,
        bottomBar = { if (showBottomBar) WatchlyBottomBar(navController) }
    ) { innerPadding ->
        WatchlyNavHost(
            navController = navController,
            modifier = Modifier.padding(innerPadding)
        )
    }
}

@Composable
internal fun WatchlyBottomBar(navController: NavHostController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    NavigationBar(containerColor = WatchlySurface) {
        WatchlyDestination.entries.forEach { destination ->
            val selected = currentDestination?.hierarchy
                ?.any { it.route == destination.route } == true

            NavigationBarItem(
                selected = selected,
                onClick = {
                    // Leaving a pushed screen via a tab tap: drop the
                    // handed-over objects, so a stale title/request can
                    // never resurface on a later argument-less entry
                    // into DETAILS / PLAYER.
                    if (currentDestination?.route == WatchlyRoutes.DETAILS) {
                        TitleDetailsStore.clear()
                    }
                    if (currentDestination?.route == WatchlyRoutes.PERSON) {
                        PersonStore.clear()
                    }
                    if (currentDestination?.route == WatchlyRoutes.PLAYER) {
                        PlaybackSessionStore.clear()
                    }
                    if (currentDestination?.route == WatchlyRoutes.LIST_DETAIL) {
                        SelectedListStore.clear()
                    }
                    val startDestinationId =
                        navController.graph.findStartDestination().id
                    navController.navigate(destination.route) {
                        // Standard bottom-nav behaviour: single instance per
                        // tab, tab state saved/restored, no back-stack pile-up.
                        popUpTo(startDestinationId) {
                            saveState = true
                        }
                        launchSingleTop = true
                        // restoreState must NOT be set when the target
                        // IS the start destination (cycle 7, reproduced
                        // in SearchHomeTabNavigationTest): the start
                        // entry is never popped, so there is no saved
                        // state of its own to restore — and on
                        // navigation-compose 2.8.5 the flag instead
                        // resurrects the just-popped entry, so a Home
                        // tap from Search (typed query, reached via
                        // Home's search icon) silently stayed on
                        // Search. Other tabs keep restoreState.
                        restoreState =
                            navController.graph.findStartDestination().route !=
                                destination.route
                    }
                },
                icon = {
                    Icon(
                        imageVector = destination.icon,
                        contentDescription = destination.label
                    )
                },
                label = { Text(destination.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = WatchlyGold,
                    selectedTextColor = WatchlyGold,
                    indicatorColor = WatchlyGoldContainer,
                    unselectedIconColor = WatchlyMuted,
                    unselectedTextColor = WatchlyMuted
                )
            )
        }
    }
}

@Composable
internal fun WatchlyNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    // SingleTop: a fast double-tap on a poster must not stack two
    // DETAILS entries (backing out of the top one used to leave the
    // second one showing a dead placeholder).
    val openDetails: (Title) -> Unit = { title ->
        TitleDetailsStore.select(title)
        navController.navigate(WatchlyRoutes.DETAILS) { launchSingleTop = true }
    }

    // Person pages (§3.3, cycle 11): the same select-then-navigate
    // hand-over, fed ONLY by cast members carrying a TMDB person
    // id — the details screen doesn't even make those cards
    // clickable without one, so this guard is a second lock.
    val openPerson: (com.watchly.app.data.model.CastMember) -> Unit = { member ->
        val personId = member.tmdbPersonId
        if (personId != null) {
            PersonStore.select(personId, member.personName)
            navController.navigate(WatchlyRoutes.PERSON) {
                launchSingleTop = true
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = WatchlyDestination.HOME.route,
        modifier = modifier
    ) {
        composable(WatchlyDestination.HOME.route) {
            HomeScreen(
                onTitleClick = openDetails,
                onSearchClick = { navController.navigate(WatchlyRoutes.SEARCH) },
                // Cycle 14: the Home no-key card goes straight to
                // the TMDB key screen (the same destination My
                // Library's TMDB card uses).
                onTmdbClick = { navController.navigate(WatchlyRoutes.TMDB_KEY) }
            )
        }
        composable(WatchlyDestination.MOVIES.route) {
            BrowseScreen(
                kind = BrowseKind.MOVIES,
                onTitleClick = openDetails
            )
        }
        composable(WatchlyDestination.SHOWS.route) {
            BrowseScreen(
                kind = BrowseKind.SHOWS,
                onTitleClick = openDetails
            )
        }
        composable(WatchlyDestination.ANIME.route) {
            BrowseScreen(
                kind = BrowseKind.ANIME,
                onTitleClick = openDetails
            )
        }
        composable(WatchlyDestination.LIBRARY.route) {
            LibraryScreen(
                onTitleClick = openDetails,
                onProfileSwitched = {
                    // Profile switch (cycle 12, §8): every open
                    // screen holds stores built for the OLD profile,
                    // so the whole stack resets to a fresh Home —
                    // the same stale-handoff discipline as the tab
                    // bar's store clears, applied to everything.
                    // Saved tab state is dropped on purpose: it
                    // belongs to the previous profile's session.
                    TitleDetailsStore.clear()
                    PersonStore.clear()
                    PlaybackSessionStore.clear()
                    SelectedListStore.clear()
                    navController.navigate(WatchlyDestination.HOME.route) {
                        popUpTo(navController.graph.id) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onTmdbClick = { navController.navigate(WatchlyRoutes.TMDB_KEY) },
                onApiKeysClick = { navController.navigate(WatchlyRoutes.API_KEYS) },
                onListsClick = { navController.navigate(WatchlyRoutes.LISTS) },
                onListClick = { list ->
                    SelectedListStore.select(list.id)
                    navController.navigate(WatchlyRoutes.LIST_DETAIL) {
                        launchSingleTop = true
                    }
                },
                onPlayDownload = { title, file ->
                    PlaybackSessionStore.select(
                        PlaybackRequest(
                            title = title,
                            files = listOf(file),
                            episodeLabel = null
                        )
                    )
                    navController.navigate(WatchlyRoutes.PLAYER)
                }
            )
        }
        composable(WatchlyRoutes.TMDB_KEY) {
            TmdbKeyScreen(onBack = { navController.popBackStack() })
        }
        composable(WatchlyRoutes.API_KEYS) {
            com.watchly.app.ui.settings.ApiKeysScreen(
                onBack = { navController.popBackStack() },
                onTmdbClick = { navController.navigate(WatchlyRoutes.TMDB_KEY) },
                onProviderClick = { provider ->
                    navController.navigate(
                        "${WatchlyRoutes.PROVIDER_KEY}/${provider.name}"
                    )
                }
            )
        }
        composable(
            route = "${WatchlyRoutes.PROVIDER_KEY}/{provider}",
            arguments = listOf(
                androidx.navigation.navArgument("provider") {
                    type = androidx.navigation.NavType.StringType
                }
            )
        ) { entry ->
            val providerName = entry.arguments?.getString("provider")
            val provider = com.watchly.app.data.settings.ApiProvider.entries
                .firstOrNull { it.name == providerName }
            if (provider != null) {
                com.watchly.app.ui.settings.ProviderKeyScreen(
                    provider = provider,
                    onBack = { navController.popBackStack() }
                )
            } else {
                LaunchedEffect(Unit) { navController.popBackStack() }
                PlaceholderScreen(title = "Unknown provider")
            }
        }
        composable(WatchlyRoutes.LISTS) {
            ListsScreen(
                onBack = { navController.popBackStack() },
                onListClick = { list ->
                    SelectedListStore.select(list.id)
                    navController.navigate(WatchlyRoutes.LIST_DETAIL) {
                        launchSingleTop = true
                    }
                }
            )
        }
        composable(WatchlyRoutes.LIST_DETAIL) {
            // Same remember-capture + pop-then-clear pattern as
            // DETAILS: the handed-over list id is read once per
            // back-stack entry and cleared only after the pop.
            val listId = remember { SelectedListStore.take() }
            if (listId != null) {
                ListDetailScreen(
                    listId = listId,
                    onBack = {
                        if (!navController.popBackStack()) {
                            navController.navigate(WatchlyDestination.LIBRARY.route) {
                                launchSingleTop = true
                            }
                        }
                        SelectedListStore.clear()
                    },
                    onTitleClick = openDetails,
                    // Cycle 5: play from a list (autoplay continues
                    // down the list inside the player).
                    onPlayRequest = { request ->
                        PlaybackSessionStore.select(request)
                        navController.navigate(WatchlyRoutes.PLAYER)
                    }
                )
            } else {
                LaunchedEffect(Unit) {
                    if (!navController.popBackStack()) {
                        navController.navigate(WatchlyDestination.LIBRARY.route) {
                            launchSingleTop = true
                        }
                    }
                }
                PlaceholderScreen(title = "Nothing selected")
            }
        }
        composable(WatchlyRoutes.SEARCH) {
            SearchScreen(
                onTitleClick = openDetails,
                onBack = { navController.popBackStack() }
            )
        }
        composable(WatchlyRoutes.DETAILS) {
            // Capture the handed-over title ONCE per back-stack entry.
            // Reading the store on every recomposition meant
            // clear()-before-pop blanked the still-visible page into
            // the button-less placeholder below while exiting — a big
            // part of why back from details felt broken.
            val title = remember { TitleDetailsStore.take() }
            if (title != null) {
                TitleDetailsScreen(
                    title = title,
                    onBack = {
                        // Pop first, then clear: the entry keeps its
                        // remembered title during the exit transition,
                        // and the store is empty for the next pick.
                        if (!navController.popBackStack()) {
                            navController.navigate(WatchlyDestination.HOME.route) {
                                launchSingleTop = true
                            }
                        }
                        TitleDetailsStore.clear()
                    },
                    onPlay = { playedTitle, files, episodeLabel, upNextQueue ->
                        PlaybackSessionStore.select(
                            PlaybackRequest(
                                title = playedTitle,
                                files = files,
                                episodeLabel = episodeLabel,
                                // Official-YouTube titles have no files:
                                // they play in the official embedded
                                // player instead of Media3.
                                youtubeVideoId = if (files.isEmpty()) {
                                    playedTitle.youtubeId
                                } else {
                                    null
                                },
                                // Cycle 5: autoplay continuation
                                // (YouTube series episodes).
                                upNext = upNextQueue
                            )
                        )
                        navController.navigate(WatchlyRoutes.PLAYER)
                    },
                    // Where-to-watch opens in-app (Phase D), not in
                    // the external browser.
                    onOpenWatchPage = { watchUrl ->
                        com.watchly.app.ui.browser.WatchPageStore.open(watchUrl)
                        navController.navigate(WatchlyRoutes.BROWSER)
                    },
                    // "More like this" (cycle 10): open the tapped
                    // title's page IN PLACE of this one — the current
                    // DETAILS entry is popped as part of the same
                    // navigation, so the new entry's remember-capture
                    // reads the new title and Back returns to whatever
                    // was under the first details page.
                    onTitleClick = { next ->
                        TitleDetailsStore.select(next)
                        navController.navigate(WatchlyRoutes.DETAILS) {
                            launchSingleTop = true
                            popUpTo(WatchlyRoutes.DETAILS) {
                                inclusive = true
                            }
                        }
                    },
                    // Person pages (§3.3): a TMDB cast tap pushes
                    // the person page over this details page; Back
                    // returns here.
                    onPersonClick = openPerson
                )
            } else {
                // No title was selected (e.g. process restart): never
                // a dead end — pop straight back out instead of
                // sitting on a page with no working back button.
                LaunchedEffect(Unit) {
                    if (!navController.popBackStack()) {
                        navController.navigate(WatchlyDestination.HOME.route) {
                            launchSingleTop = true
                        }
                    }
                }
                PlaceholderScreen(title = "Nothing selected")
            }
        }
        composable(WatchlyRoutes.PERSON) {
            // Same remember-capture + pop-then-clear as DETAILS:
            // the handed-over person is read once per back-stack
            // entry and cleared only after the pop.
            val selected = remember { PersonStore.take() }
            if (selected != null) {
                PersonScreen(
                    selected = selected,
                    onBack = {
                        if (!navController.popBackStack()) {
                            navController.navigate(WatchlyDestination.HOME.route) {
                                launchSingleTop = true
                            }
                        }
                        PersonStore.clear()
                    },
                    // "Known for" (§3.3): open the tapped title's
                    // details IN PLACE of the person page — the
                    // person entry is popped as part of the same
                    // navigation (the "More like this" pattern), so
                    // Back from that details page returns to
                    // whatever was under the person page.
                    onTitleClick = { next ->
                        TitleDetailsStore.select(next)
                        navController.navigate(WatchlyRoutes.DETAILS) {
                            launchSingleTop = true
                            popUpTo(WatchlyRoutes.PERSON) {
                                inclusive = true
                            }
                        }
                    }
                )
            } else {
                // Nothing selected (e.g. process restart): never a
                // dead end — pop straight back out.
                LaunchedEffect(Unit) {
                    if (!navController.popBackStack()) {
                        navController.navigate(WatchlyDestination.HOME.route) {
                            launchSingleTop = true
                        }
                    }
                }
                PlaceholderScreen(title = "Nothing selected")
            }
        }
        composable(WatchlyRoutes.BROWSER) {
            // Same remember-capture + pop-then-clear as DETAILS.
            val watchUrl = remember {
                com.watchly.app.ui.browser.WatchPageStore.take()
            }
            if (watchUrl != null &&
                com.watchly.app.ui.browser.isWebUrl(watchUrl)
            ) {
                com.watchly.app.ui.browser.InAppBrowserScreen(
                    url = watchUrl,
                    onBack = {
                        if (!navController.popBackStack()) {
                            navController.navigate(WatchlyDestination.HOME.route) {
                                launchSingleTop = true
                            }
                        }
                        com.watchly.app.ui.browser.WatchPageStore.clear()
                    }
                )
            } else {
                // Nothing valid to open — never a dead end.
                LaunchedEffect(Unit) {
                    if (!navController.popBackStack()) {
                        navController.navigate(WatchlyDestination.HOME.route) {
                            launchSingleTop = true
                        }
                    }
                }
                PlaceholderScreen(title = "Nothing to open")
            }
        }
        composable(WatchlyRoutes.PLAYER) {
            // Same remember-capture + pop-then-clear as DETAILS: the
            // old clear()-before-pop could blank the player into the
            // placeholder mid-exit.
            val request = remember { PlaybackSessionStore.take() }
            if (request != null) {
                PlayerScreen(
                    request = request,
                    onBack = {
                        if (!navController.popBackStack()) {
                            navController.navigate(WatchlyDestination.HOME.route) {
                                launchSingleTop = true
                            }
                        }
                        PlaybackSessionStore.clear()
                    }
                )
            } else {
                LaunchedEffect(Unit) {
                    if (!navController.popBackStack()) {
                        navController.navigate(WatchlyDestination.HOME.route) {
                            launchSingleTop = true
                        }
                    }
                }
                PlaceholderScreen(title = "Nothing selected")
            }
        }
    }
}

/**
 * Never-a-dead-end fallback for argument-less entries into a pushed
 * destination (e.g. after a process restart): the caller pops straight
 * back out, so this only flashes during the exit. The copy says what
 * happened and nothing more — it used to read "— coming soon" (cycle 13
 * copy audit: nothing is coming; every destination is built, and the
 * screen is a recovery path, not a teaser).
 */
@Composable
private fun PlaceholderScreen(title: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = WatchlyGold
        )
    }
}
