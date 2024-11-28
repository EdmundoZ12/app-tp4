package com.example.app_topicos.GoogleMapService

data class DirectionsResponse(
    val routes: List<Route>
) {
    data class Route(
        val legs: List<Leg>?
    ) {
        data class Leg(
            val steps: List<Step>?,
            val distance: Distance?,
            val duration: Duration?
        ) {
            data class Step(
                val html_instructions: String?,
                val distance: Distance?,
                val duration: Duration?,
                val startLocation: Location?,
                val endLocation: Location?
            )

            data class Distance(
                val text: String?, // e.g., "0.1 km"
                val value: Int? // e.g., 100 (in meters)
            )

            data class Duration(
                val text: String?, // e.g., "1 min"
                val value: Int? // e.g., 60 (in seconds)
            )

            data class Location(
                val lat: Double,
                val lng: Double
            )
        }
    }
}

