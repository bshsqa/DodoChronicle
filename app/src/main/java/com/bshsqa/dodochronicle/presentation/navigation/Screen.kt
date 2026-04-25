package com.bshsqa.dodochronicle.presentation.navigation

sealed class Screen(val route: String) {
    object Init : Screen("init")
    object Timeline : Screen("timeline")
    object Settings : Screen("settings")
    object EventDetail : Screen("event_detail/{eventId}") {
        fun createRoute(eventId: String) = "event_detail/$eventId"
    }
}
