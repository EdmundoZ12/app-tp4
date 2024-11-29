package com.example.app_topicos.GoogleMapService


import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.model.LatLng
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class GoogleMapsController(
    private val context: Context,
    private val textToSpeech: TextToSpeech,
    private val apiKey: String
) {

    lateinit var apiService: APIService
    private var isNavigationActive = false

    // Lista global para guardar los lugares encontrados
    private var lugaresEncontrados: List<PlacesResponse.Place> = emptyList()
    private var ultimaInstruccion: String? = null // Variable para guardar la última instrucción
    private var navigationHandler: android.os.Handler? = null
    private val locationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    fun buscarLugar(consulta: String) {
        obtenerUbicacionActual { ubicacion ->
            val ubicacionStr = "${ubicacion.latitude},${ubicacion.longitude}"
            val radioDeBusqueda = 20000
            val call = apiService.buscarLugares(ubicacionStr, radioDeBusqueda, consulta, apiKey)

            call.enqueue(object : Callback<PlacesResponse> {
                override fun onResponse(
                    call: Call<PlacesResponse>,
                    response: Response<PlacesResponse>
                ) {
                    if (response.isSuccessful) {
                        lugaresEncontrados = response.body()?.results ?: emptyList()

                        when {
                            lugaresEncontrados.isEmpty() -> {
                                textToSpeech.speak(
                                    "No se encontraron lugares cercanos para $consulta.",
                                    TextToSpeech.QUEUE_FLUSH,
                                    null,
                                    null
                                )
                            }

                            lugaresEncontrados.size == 1 -> {
                                val unicoLugar = lugaresEncontrados[0]
                                val destino = LatLng(
                                    unicoLugar.geometry.location.lat,
                                    unicoLugar.geometry.location.lng
                                )
                                iniciarNavegacion(destino, unicoLugar.name)
                            }

                            else -> {
                                val lugarExacto = lugaresEncontrados.firstOrNull {
                                    it.name.equals(consulta, ignoreCase = true)
                                }
                                if (lugarExacto != null) {
                                    val destino = LatLng(
                                        lugarExacto.geometry.location.lat,
                                        lugarExacto.geometry.location.lng
                                    )
                                    iniciarNavegacion(destino, lugarExacto.name)
                                } else {
                                    dictarLugares(lugaresEncontrados)
                                }
                            }
                        }
                    } else {
                        Toast.makeText(
                            context,
                            "Error en la respuesta de la API.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                override fun onFailure(call: Call<PlacesResponse>, t: Throwable) {
                    Toast.makeText(context, "Error al conectar con la API.", Toast.LENGTH_SHORT)
                        .show()
                }
            })
        }
    }


    private fun dictarLugares(lugares: List<PlacesResponse.Place>) {
        if (lugares.isEmpty()) {
            textToSpeech.speak(
                "No se encontraron lugares cercanos.",
                TextToSpeech.QUEUE_FLUSH,
                null,
                null
            )
        } else {
            val nombresLugares = lugares.joinToString(", ") { it.name }
            textToSpeech.speak(
                "Se encontraron varios lugares: $nombresLugares.",
                TextToSpeech.QUEUE_FLUSH,
                null,
                null
            )
        }
    }

    // Método para repetir los lugares encontrados
    fun repetirLugaresEncontrados() {
        if (lugaresEncontrados.isEmpty()) {
            textToSpeech.speak(
                "No hay lugares encontrados para repetir.",
                TextToSpeech.QUEUE_FLUSH,
                null,
                null
            )
        } else {
            val nombresLugares = lugaresEncontrados.joinToString(", ") { it.name }
            textToSpeech.speak(
                "Los lugares encontrados son: $nombresLugares.",
                TextToSpeech.QUEUE_FLUSH,
                null,
                null
            )
        }
    }

    private fun obtenerUbicacionActual(callback: (LatLng) -> Unit) {
        if (verificarPermisos()) {
            try {
                locationClient.lastLocation.addOnSuccessListener { location ->
                    if (location != null) {
                        callback(LatLng(location.latitude, location.longitude))
                    } else {
                        callback(LatLng(-17.7833, -63.1821)) // Ubicación fija como respaldo
                    }
                }.addOnFailureListener {
                    callback(LatLng(-17.7833, -63.1821)) // Ubicación fija como respaldo
                }
            } catch (e: SecurityException) {
                manejarPermisosDenegados()
                callback(LatLng(-17.7833, -63.1821))
            }
        } else {
            manejarPermisosDenegados()
            callback(LatLng(-17.7833, -63.1821))
        }
    }

    fun iniciarNavegacion(destino: LatLng, nombre: String) {
        obtenerUbicacionActual { ubicacion ->
            val fraseInicial = "Iniciando navegación hacia $nombre."

            // Configuramos el listener para rastrear el progreso del habla
            textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    // No hacer nada al inicio
                }

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == "iniciar_navegacion") {
                        // Continuar con la navegación después de que se termine de hablar
                        obtenerIndicacionesConMonitoreo(ubicacion, destino, nombre)
                    }
                }

                override fun onError(utteranceId: String?) {
                    // Manejar errores si ocurren
                    Log.e("TextToSpeech", "Error al pronunciar la frase con ID: $utteranceId")
                }
            })

            // Iniciar el texto a hablar con un ID único
            textToSpeech.speak(
                fraseInicial,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "iniciar_navegacion" // Identificador único
            )
        }
    }


    private fun obtenerIndicacionesConMonitoreo(
        origen: LatLng,
        destino: LatLng,
        nombreDestino: String
    ) {
        val url =
            "https://maps.googleapis.com/maps/api/directions/json?origin=${origen.latitude},${origen.longitude}" +
                    "&destination=${destino.latitude},${destino.longitude}&key=$apiKey"

        val call = apiService.obtenerIndicaciones(url)
        call.enqueue(object : Callback<DirectionsResponse> {
            override fun onResponse(
                call: Call<DirectionsResponse>,
                response: Response<DirectionsResponse>
            ) {
                if (response.isSuccessful) {
                    val routes = response.body()?.routes
                    if (!routes.isNullOrEmpty()) {
                        monitorearRuta(routes[0], destino, nombreDestino)
                    } else {
                        textToSpeech.speak(
                            "No se encontraron rutas hacia $nombreDestino.",
                            TextToSpeech.QUEUE_FLUSH,
                            null,
                            null
                        )
                    }
                } else {
                    Toast.makeText(
                        context,
                        "Error al obtener las indicaciones.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            override fun onFailure(call: Call<DirectionsResponse>, t: Throwable) {
                Toast.makeText(context, "Error al conectar con la API.", Toast.LENGTH_SHORT)
                    .show()
            }
        })
    }

    fun cancelarNavegacion() {
        // Detener la navegación
        isNavigationActive = false
        ultimaInstruccion = null
        textToSpeech.speak(
            "La navegación ha sido cancelada.",
            TextToSpeech.QUEUE_FLUSH,
            null,
            null
        )
        Log.d("GoogleMapsController", "Navegación cancelada.")
    }

    private fun monitorearRuta(
        ruta: DirectionsResponse.Route,
        destino: LatLng,
        nombreDestino: String
    ) {
        val pasos = ruta.legs?.firstOrNull()?.steps?.toMutableList() ?: return
        var pasoActual: DirectionsResponse.Route.Leg.Step? = pasos.firstOrNull()
        isNavigationActive = true

        if (verificarPermisos()) {
            try {
                // Decir la primera instrucción inmediatamente y guardarla
                pasoActual?.let { primerPaso ->
                    val instruccion = primerPaso.html_instructions ?: "Instrucción desconocida."
                    val distancia = primerPaso.distance?.text ?: "desconocida"
                    val duracion = primerPaso.duration?.text ?: "desconocida"
                    ultimaInstruccion = traducirInstruccion(instruccion, distancia, duracion)
                    textToSpeech.speak(
                        ultimaInstruccion,
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        null
                    )
                    Log.d("InstruccionesRuta", "Primera instrucción: $ultimaInstruccion")
                }

                // Monitorear el progreso hacia el siguiente paso
                locationClient.requestLocationUpdates(
                    LocationRequest.create().apply {
                        interval = 2000
                        fastestInterval = 1000
                        priority = LocationRequest.PRIORITY_HIGH_ACCURACY
                    },
                    object : LocationCallback() {
                        override fun onLocationResult(result: LocationResult) {
                            if (!isNavigationActive) {
                                // Si la navegación fue cancelada, detener las actualizaciones de ubicación
                                locationClient.removeLocationUpdates(this)
                                return
                            }

                            val ubicacionActual = result.lastLocation
                            if (ubicacionActual != null) {
                                val posicionActual =
                                    LatLng(ubicacionActual.latitude, ubicacionActual.longitude)
                                val distanciaAlDestino =
                                    calcularDistancia(posicionActual, destino)

                                // Si se alcanza el destino, finalizar
                                if (distanciaAlDestino < 50) {
                                    isNavigationActive = false
                                    textToSpeech.speak(
                                        "Has llegado a tu destino: $nombreDestino.",
                                        TextToSpeech.QUEUE_FLUSH,
                                        null,
                                        null
                                    )
                                    locationClient.removeLocationUpdates(this)
                                    return
                                }

                                // Verificar si se alcanzó el siguiente paso
                                pasoActual?.let { paso ->
                                    val inicioPaso = paso.startLocation?.let {
                                        LatLng(it.lat, it.lng)
                                    }
                                    if (inicioPaso != null) {
                                        val distanciaAlPaso =
                                            calcularDistancia(posicionActual, inicioPaso)
                                        if (distanciaAlPaso < 50) {
                                            pasos.removeAt(0)
                                            if (pasos.isNotEmpty()) {
                                                pasoActual = pasos.first()
                                                // Guardar la nueva instrucción como última
                                                val instruccion = pasoActual?.html_instructions
                                                    ?: "Instrucción desconocida."
                                                val distancia =
                                                    pasoActual?.distance?.text ?: "desconocida"
                                                val duracion =
                                                    pasoActual?.duration?.text ?: "desconocida"
                                                ultimaInstruccion =
                                                    traducirInstruccion(
                                                        instruccion,
                                                        distancia,
                                                        duracion
                                                    )
                                                textToSpeech.speak(
                                                    ultimaInstruccion,
                                                    TextToSpeech.QUEUE_FLUSH,
                                                    null,
                                                    null
                                                )
                                                Log.d(
                                                    "InstruccionesRuta",
                                                    "Siguiente instrucción: $ultimaInstruccion"
                                                )
                                            } else {
                                                isNavigationActive = false
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    },
                    context.mainLooper
                )
            } catch (e: SecurityException) {
                manejarPermisosDenegados()
            }
        } else {
            manejarPermisosDenegados()
        }
    }

    // Método para obtener la última instrucción
    fun obtenerUltimaInstruccion(): String {
        return ultimaInstruccion ?: "No hay instrucciones disponibles en este momento."
    }

    private fun traducirInstruccion(
        instruccionHtml: String,
        distancia: String,
        duracion: String
    ): String {
        val instruccionLimpia = instruccionHtml.replace(Regex("<[^>]*>"), "").trim()
        return when {
            instruccionLimpia.contains("Turn right", true) -> {
                val detalle = extraerLugar(instruccionLimpia)
                "Gira a la derecha${if (detalle.isNotEmpty()) " hacia $detalle" else ""} en $distancia. Tiempo estimado: $duracion."
            }

            instruccionLimpia.contains("Turn left", true) -> {
                val detalle = extraerLugar(instruccionLimpia)
                "Gira a la izquierda${if (detalle.isNotEmpty()) " hacia $detalle" else ""} en $distancia. Tiempo estimado: $duracion."
            }

            instruccionLimpia.contains("Head south", true) -> {
                "Dirígete hacia el sur por $distancia. Tiempo estimado: $duracion."
            }

            instruccionLimpia.contains("Head north", true) -> {
                "Dirígete hacia el norte por $distancia. Tiempo estimado: $duracion."
            }

            instruccionLimpia.contains("Continue", true) -> {
                "Continúa hacia adelante por $distancia. Tiempo estimado: $duracion."
            }

            instruccionLimpia.contains("Destination will be on the right", true) -> {
                "El destino estará a la derecha. Tiempo estimado: $duracion."
            }

            instruccionLimpia.contains("Destination will be on the left", true) -> {
                "El destino estará a la izquierda. Tiempo estimado: $duracion."
            }

            else -> instruccionLimpia
        }
    }

    private fun calcularDistancia(origen: LatLng, destino: LatLng): Float {
        val resultados = FloatArray(1)
        Location.distanceBetween(
            origen.latitude, origen.longitude,
            destino.latitude, destino.longitude,
            resultados
        )
        return resultados[0]
    }

    private fun extraerLugar(instruccion: String): String {
        val palabrasClave = listOf("onto", "toward", "on", "exit")
        palabrasClave.forEach { palabra ->
            if (instruccion.contains(palabra, true)) {
                return instruccion.substringAfter(palabra, "").trim()
            }
        }
        return ""
    }

    private fun verificarPermisos(): Boolean {
        val fineLocation = ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseLocation = ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fineLocation && coarseLocation
    }

    private fun manejarPermisosDenegados() {
        textToSpeech.speak(
            "Permisos de ubicación no concedidos. No se puede realizar la operación.",
            TextToSpeech.QUEUE_FLUSH,
            null,
            null
        )
        Toast.makeText(
            context,
            "No tienes permisos de ubicación para realizar esta operación.",
            Toast.LENGTH_SHORT
        ).show()
    }


}
