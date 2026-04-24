package com.bshsqa.dodochronicle.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.bshsqa.dodochronicle.presentation.detail.EventDetailScreen
import com.bshsqa.dodochronicle.presentation.init.InitScreen
import com.bshsqa.dodochronicle.presentation.init.InitViewModel
import com.bshsqa.dodochronicle.presentation.timeline.TimelineScreen

@Composable
fun AppNavigation(startDestination: String) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Screen.Init.route) {
            val vm: InitViewModel = hiltViewModel()
            InitScreen(
                viewModel = vm,
                onInitComplete = {
                    navController.navigate(Screen.Timeline.route) {
                        popUpTo(Screen.Init.route) { inclusive = true }
                    }
                }
            )
        }
        composable(Screen.Timeline.route) {
            TimelineScreen(
                onEventClick = { eventId ->
                    navController.navigate(Screen.EventDetail.createRoute(eventId))
                }
            )
        }
        composable(Screen.EventDetail.route) { backStack ->
            val eventId = backStack.arguments?.getString("eventId") ?: return@composable
            EventDetailScreen(
                eventId = eventId,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
