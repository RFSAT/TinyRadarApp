package com.rfsat.tinyrad.ui

sealed class Screen(val route: String) {
    object Home       : Screen("home")
    object Connect    : Screen("connect")
    object Radar      : Screen("radar")       // Live radar display & object detection
    object Recordings : Screen("recordings")  // Saved session files
    object Log        : Screen("log")
    object Settings   : Screen("settings")
    object About      : Screen("about")
}
