package com.example.largeindexblog.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.largeindexblog.ui.screen.AddBlogScreen
import com.example.largeindexblog.ui.screen.DetailScreen
import com.example.largeindexblog.ui.screen.HomeScreen
import com.example.largeindexblog.ui.screen.SettingsScreen

/**
 * Navigation routes.
 */
object Routes {
    const val HOME = "home"
    const val DETAIL = "detail/{blogId}"
    const val ADD = "add"
    const val EDIT = "edit/{blogId}"
    const val SETTINGS = "settings"

    fun detail(blogId: Long) = "detail/$blogId"
    fun edit(blogId: Long) = "edit/$blogId"
}

/**
 * Main navigation graph.
 */
@Composable
fun AppNavigation(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Routes.HOME
    ) {
        // Home screen
        composable(Routes.HOME) {
            HomeScreen(
                onBlogClick = { blogId ->
                    navController.navigate(Routes.detail(blogId))
                },
                onAddClick = {
                    navController.navigate(Routes.ADD)
                },
                onSettingsClick = {
                    navController.navigate(Routes.SETTINGS)
                }
            )
        }

        // Detail screen
        composable(
            route = Routes.DETAIL,
            arguments = listOf(
                navArgument("blogId") { type = NavType.LongType }
            )
        ) {
            DetailScreen(
                onNavigateBack = { navController.popBackStack() },
                onEditClick = { blogId ->
                    navController.navigate(Routes.edit(blogId))
                }
            )
        }

        // Add screen
        composable(Routes.ADD) {
            AddBlogScreen(
                blogId = null,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // Edit screen
        composable(
            route = Routes.EDIT,
            arguments = listOf(
                navArgument("blogId") { type = NavType.LongType }
            )
        ) { backStackEntry ->
            val blogId = backStackEntry.arguments?.getLong("blogId")
            AddBlogScreen(
                blogId = blogId,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // Settings screen
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
