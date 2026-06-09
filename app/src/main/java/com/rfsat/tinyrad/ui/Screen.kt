package com.rfsat.tinyrad.ui

sealed class Screen(val route: String) {
    object Home       : Screen("home")
    object Connect    : Screen("connect")   // manual USB device picker
    object Radar      : Screen("radar")
    object Recordings : Screen("recordings")
    object Log        : Screen("log")
    object Settings   : Screen("settings")
    object About      : Screen("about")
}
