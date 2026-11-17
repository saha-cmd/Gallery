package com.gallery.app.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.gallery.feature.editor.ui.EditorScreen
import com.gallery.feature.gallery.ui.GalleryScreen
import com.gallery.feature.gallery.ui.RecycleBinScreen
import com.gallery.feature.gallery.ui.SettingsScreen
import com.gallery.feature.vault.ui.VaultAuthScreen
import com.gallery.feature.vault.ui.VaultScreen
import com.gallery.feature.viewer.ui.MediaViewerScreen

sealed class Screen(val route: String) {
    object Gallery : Screen("gallery")
    object Album : Screen("album/{albumId}") {
        fun createRoute(albumId: Long) = "album/$albumId"
    }
    object Viewer : Screen("viewer/{mediaId}/{isVideo}?albumId={albumId}") {
        fun createRoute(id: Long, isVideo: Boolean, albumId: Long = -1L) = "viewer/$id/$isVideo?albumId=$albumId"
    }
    object VaultAuth : Screen("vault_auth")
    object Vault : Screen("vault")
    object Settings : Screen("settings")
    object RecycleBin : Screen("recycle_bin")
    object Editor : Screen("editor/{mediaUri}") {
        fun createRoute(uri: String) = "editor/${java.net.URLEncoder.encode(uri, "UTF-8")}"
    }
}

@Composable
fun GalleryNavGraph(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Screen.Gallery.route,
        enterTransition = { fadeIn(animationSpec = tween(280)) },
        exitTransition = { fadeOut(animationSpec = tween(250)) },
        popEnterTransition = { fadeIn(animationSpec = tween(250)) },
        popExitTransition = { fadeOut(animationSpec = tween(220)) }
    ) {
        composable(
            route = Screen.Gallery.route,
            enterTransition = { fadeIn(animationSpec = tween(280)) },
            exitTransition = { fadeOut(animationSpec = tween(250)) },
            popEnterTransition = { fadeIn(animationSpec = tween(280)) },
            popExitTransition = { fadeOut(animationSpec = tween(220)) }
        ) {
            GalleryScreen(
                onMediaClick = { mediaId, isVideo, albumId ->
                    navController.navigate(Screen.Viewer.createRoute(mediaId, isVideo, albumId))
                },
                onVaultClick = {
                    navController.navigate(Screen.VaultAuth.route)
                },
                onSettingsClick = {
                    navController.navigate(Screen.Settings.route)
                }
            )
        }

        composable(
            route = Screen.Viewer.route,
            arguments = listOf(
                navArgument("mediaId") { type = NavType.LongType },
                navArgument("isVideo") { type = NavType.BoolType },
                navArgument("albumId") {
                    type = NavType.LongType
                    defaultValue = -1L
                }
            ),
            enterTransition = {
                fadeIn(animationSpec = tween(280)) +
                    scaleIn(initialScale = 0.92f, animationSpec = tween(280))
            },
            exitTransition = {
                fadeOut(animationSpec = tween(220))
            },
            popEnterTransition = {
                fadeIn(animationSpec = tween(250))
            },
            popExitTransition = {
                fadeOut(animationSpec = tween(220)) +
                    scaleOut(targetScale = 0.92f, animationSpec = tween(220))
            }
        ) { backStackEntry ->
            MediaViewerScreen(
                mediaId = backStackEntry.arguments?.getLong("mediaId") ?: 0L,
                isVideo = backStackEntry.arguments?.getBoolean("isVideo") ?: false,
                albumId = backStackEntry.arguments?.getLong("albumId") ?: -1L,
                onBack = { navController.popBackStack() },
                onEdit = { uri ->
                    navController.navigate(Screen.Editor.createRoute(uri))
                }
            )
        }

        composable(
            route = Screen.VaultAuth.route,
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                ) + fadeIn(animationSpec = tween(300))
            },
            exitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { -it / 3 },
                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                ) + fadeOut(animationSpec = tween(200))
            },
            popEnterTransition = {
                slideInHorizontally(
                    initialOffsetX = { -it / 3 },
                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                ) + fadeIn(animationSpec = tween(300))
            },
            popExitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                ) + fadeOut(animationSpec = tween(200))
            }
        ) {
            VaultAuthScreen(
                onAuthSuccess = {
                    navController.navigate(Screen.Vault.route) {
                        popUpTo(Screen.VaultAuth.route) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.Vault.route,
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                ) + fadeIn(animationSpec = tween(300))
            },
            exitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { -it / 3 },
                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                ) + fadeOut(animationSpec = tween(200))
            },
            popEnterTransition = {
                slideInHorizontally(
                    initialOffsetX = { -it / 3 },
                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                ) + fadeIn(animationSpec = tween(300))
            },
            popExitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                ) + fadeOut(animationSpec = tween(200))
            }
        ) {
            VaultScreen(
                onBack = { navController.popBackStack() },
                onEditItem = { uri ->
                    navController.navigate(Screen.Editor.createRoute(uri))
                }
            )
        }

        composable(
            route = Screen.Settings.route,
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                ) + fadeIn(animationSpec = tween(300))
            },
            exitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { -it / 3 },
                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                ) + fadeOut(animationSpec = tween(200))
            },
            popEnterTransition = {
                slideInHorizontally(
                    initialOffsetX = { -it / 3 },
                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                ) + fadeIn(animationSpec = tween(300))
            },
            popExitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                ) + fadeOut(animationSpec = tween(200))
            }
        ) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onNavigateToVault = { navController.navigate(Screen.VaultAuth.route) },
                onNavigateToRecycleBin = { navController.navigate(Screen.RecycleBin.route) }
            )
        }

        composable(
            route = Screen.RecycleBin.route,
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                ) + fadeIn(animationSpec = tween(300))
            },
            exitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { -it / 3 },
                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                ) + fadeOut(animationSpec = tween(200))
            },
            popEnterTransition = {
                slideInHorizontally(
                    initialOffsetX = { -it / 3 },
                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                ) + fadeIn(animationSpec = tween(300))
            },
            popExitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                ) + fadeOut(animationSpec = tween(200))
            }
        ) {
            RecycleBinScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.Editor.route,
            arguments = listOf(
                navArgument("mediaUri") { type = NavType.StringType }
            ),
            enterTransition = {
                slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(320, easing = FastOutSlowInEasing)
                ) + fadeIn(animationSpec = tween(320))
            },
            exitTransition = {
                fadeOut(animationSpec = tween(200))
            },
            popEnterTransition = {
                fadeIn(animationSpec = tween(250))
            },
            popExitTransition = {
                slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(280, easing = FastOutSlowInEasing)
                ) + fadeOut(animationSpec = tween(200))
            }
        ) { backStackEntry ->
            val encodedUri = backStackEntry.arguments?.getString("mediaUri") ?: ""
            val uri = java.net.URLDecoder.decode(encodedUri, "UTF-8")
            EditorScreen(
                mediaUriString = uri,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
