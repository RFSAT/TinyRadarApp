package com.rfsat.tinyrad.ui

sealed class Screen(val route: String) {
    object Home       : Screen("home")
    object Connect    : Screen("connect")   // manual USB device picker
    object Radar      : Screen("radar")
    object Recordings : Screen("recordings")
    object CsvViewer  : Screen("csv_viewer/{path}") {
        fun route(path: String) = "csv_viewer/${java.net.URLEncoder.encode(path, "UTF-8")}"
    }
    object Log        : Screen("log")
    object Settings   : Screen("settings")
    object About      : Screen("about")
}
