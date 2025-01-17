package com.example.app_topicos.GoogleMapService

data class PlacesResponse(
    val results: List<Place>
) {
    data class Place(
        val name: String,
        val geometry: Geometry
    )

    data class Geometry(
        val location: Location
    )

    data class Location(
        val lat: Double,
        val lng: Double
    )
}


