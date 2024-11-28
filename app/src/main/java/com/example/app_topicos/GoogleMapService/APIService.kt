package com.example.app_topicos.GoogleMapService

import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Url

interface APIService {

    @GET("place/nearbysearch/json")
    fun buscarLugares(
        @Query("location") location: String,
        @Query("radius") radius: Int,
        @Query("keyword") keyword: String,
        @Query("key") key: String
    ): Call<PlacesResponse>

    @GET
    fun obtenerIndicaciones(@Url url: String): Call<DirectionsResponse>
}

