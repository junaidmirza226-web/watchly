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
private fun WatchlyBottomBar(navController: NavHostController) {
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
                    if (currentDestination?.route == WatchlyRoutes.PLAYER) {
                        PlaybackSessionStore.clear()
                    }
                    navController.navigate(destination.route) {
                        // Standard bottom-nav behaviour: single instance per
                        // tab, tab state saved/restored, no back-stack pile-up.
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
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
private fun WatchlyNavHost(
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

    NavHost(
        navController = navController,
        startDestination = WatchlyDestination.HOME.route,
        modifier = modifier
    ) {
        composable(WatchlyDestination.HOME.route) {
            HomeScreen(
                onTitleClick = openDetails,
                onSearchClick = { navController.navigate(WatchlyRoutes.SEARCH) }
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
                onTmdbClick = { navController.navigate(WatchlyRoutes.TMDB_KEY) },
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
                    onPlay = { playedTitle, files, episodeLabel ->
                        PlaybackSessionStore.select(
                            PlaybackRequest(
                                title = playedTitle,
                                files = files,
                                episodeLabel = episodeLabel
                            )
                        )
                        navController.navigate(WatchlyRoutes.PLAYER)
                    }
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
 * Temporary tab screen for destinations whose real screens are not built
 * yet. Deliberately on-brand (gold heading on black) rather than a blank
 * page, so the v1 shell already feels like the finished product.
 */
@Composable
private fun PlaceholderScreen(title: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "$title — coming soon",
            style = MaterialTheme.typography.headlineSmall,
            color = WatchlyGold
        )
    }
}
