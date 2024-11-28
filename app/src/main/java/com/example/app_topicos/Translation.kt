package com.example.app_topicos

object Translations {
    val typeTranslations = mapOf(
        "establishment" to "establecimiento",
        "point_of_interest" to "punto de interés",
        "university" to "universidad",
        "restaurant" to "restaurante",
        "food" to "comida",
        "route" to "ruta",
        "locality" to "localidad",
        "political" to "político",
        "country" to "país",
        "street_address" to "dirección",
        "premise" to "local",
        "sublocality" to "subzona",
        "postal_code" to "código postal",
        "administrative_area_level_1" to "región administrativa nivel 1",
        "administrative_area_level_2" to "región administrativa nivel 2",
        "clothing_store" to "tienda de ropa",
        "store" to "tienda"
    )

    fun translateType(type: String): String {
        return typeTranslations[type] ?: type
    }

    fun translateTypes(types: List<String>): List<String> {
        return types.map { translateType(it) }
    }
}