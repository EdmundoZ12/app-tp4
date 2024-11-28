package com.example.app_topicos

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.app_topicos.AppService.AppOpeningService
import com.google.api.gax.core.FixedCredentialsProvider
import com.google.auth.oauth2.ServiceAccountCredentials
import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.dialogflow.v2.*
import java.util.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.*
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Task
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.content.Context
import android.os.Looper
import com.google.android.gms.location.*
import com.example.app_topicos.AreasConfig
import com.example.app_topicos.DialogFlowService.UbicacionDato
import com.example.app_topicos.GoogleMapService.APIService
import com.example.app_topicos.GoogleMapService.GoogleMapsController
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory


class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private val BASE_URL = "https://maps.googleapis.com/maps/api/"
    private val API_KEY = "tuapi"
    private lateinit var googleMapsHelper: GoogleMapsController
    private var isNavigationActive = false

    private lateinit var speechRecognizer: SpeechRecognizer
    private val uuid = UUID.randomUUID().toString()
    private lateinit var recognizerIntent: Intent
    private lateinit var textToSpeech: TextToSpeech
    private lateinit var sessionsClient: SessionsClient
    private lateinit var session: SessionName
    private val TAG = "MainActivity"
    private var showDialog = false

    //private val captureInterval = 3000L // Intervalo de 3 segundos
    private var cameraIntent: Intent? = null
    private var isCapturing = false
    private val handler = Handler()
    private lateinit var imageCapture: ImageCapture
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var previewView: PreviewView
    private var isCameraInitialized = false


    //
    private lateinit var ubicacionActual: Puntos
    private lateinit var ubucacionDatos: UbicacionDato
    private var roundedLatitude: String? = null
    private var roundedLongitude: String? = null

    private var bandera: Boolean = false

    private var responseList = mutableListOf<Pair<Double, String>>()
    private var photoCount = 0
    private val maxPhotos = 3
    private val captureInterval = 1000L // Intervalo de 2 segundos
    private val billetesConBuenaConfianza = mutableListOf<String>()
    private val umbralConfianza = 50.0 // Puedes ajustar este valor según tus necesidades

    //private val urlGro = "https://4c91-110-238-90-20.ngrok-free.app/"
    private val clientN = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS) // Tiempo para conectar
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)   // Tiempo para escribir
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)    // Tiempo para leer la respuesta
        .build()


    private lateinit var fusedLocationClient: FusedLocationProviderClient


    companion object {
        private const val PERMISSIONS_REQUEST_CODE = 1
        private const val CAMERA_REQUEST_CODE = 1001 // Puedes usar cualquier número
        private const val MIN_TIME_BW_UPDATES: Long = 1000 * 60 // 1 minuto
        private const val MIN_DISTANCE_CHANGE_FOR_UPDATES: Float = 10f // 10 metros
        private const val PERMISSION_REQUEST_LOCATION = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        checkAccessibilityPermission()
        checkPermissions()  // Verifica permisos de audio y cámara

        initializeDialogflow()
        initializeTextToSpeech()
        initializeSpeechRecognizer()
        initializeShakeService()
        // Inicia CameraX
        //startCamera()
        // Ejecutores para manejar hilos de CameraX
        cameraExecutor = Executors.newSingleThreadExecutor()

        googleMapsHelper = GoogleMapsController(this, textToSpeech, API_KEY)

        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        googleMapsHelper.apiService = retrofit.create(APIService::class.java)

        // inicializamos el proveedor de gps
        Log.d(TAG, "Inicializando proveedor de GPS")
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)


        // Inicia la captura periódica de fotos
        startPeriodicCapture()

        if (showDialog) {
            showAccessibilityDialog()
        }

        val btnSpeak: Button = findViewById(R.id.btnSpeak)
        btnSpeak.visibility = View.VISIBLE
        btnSpeak.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> startListening()
                android.view.MotionEvent.ACTION_UP -> speechRecognizer.stopListening()
            }
            true
        }
    }

    private fun guardarBilleteConBuenaConfianza(confianza: Double, valorBillete: String) {
        if (confianza >= umbralConfianza) {
            billetesConBuenaConfianza.add(valorBillete)
            Log.d(TAG, "Billete guardado: $valorBillete con confianza $confianza%")
        } else {
            Log.d(TAG, "Billete descartado: $valorBillete con confianza $confianza%")
        }
    }

    private fun obtenerUltimoBilleteAnalizado(): String {
        return if (billetesConBuenaConfianza.isNotEmpty()) {
            "El último billete analizado es de ${billetesConBuenaConfianza.last()}"
        } else {
            "No se ha analizado ningún billete con buena confianza aún."
        }
    }

    private fun sumarBilletesAnalizados(): Double {
        return billetesConBuenaConfianza.sumOf { it.toDoubleOrNull() ?: 0.0 }
    }

    private fun checkPermissions() {
        val permissionsNeeded = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsNeeded.add(Manifest.permission.RECORD_AUDIO)
        }

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsNeeded.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsNeeded.add(Manifest.permission.CAMERA)
        }

        if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsNeeded.toTypedArray(),
                PERMISSIONS_REQUEST_CODE
            )
        }
    }


    private fun initializeDialogflow() {
        try {
            val stream = resources.openRawResource(R.raw.credenciales)
            val credentials = GoogleCredentials.fromStream(stream)
            val serviceAccountCredentials = credentials as? ServiceAccountCredentials
                ?: throw IllegalArgumentException("Credenciales no son de tipo ServiceAccount")

            val projectId = serviceAccountCredentials.projectId
            val settings = SessionsSettings.newBuilder()
                .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                .build()

            sessionsClient = SessionsClient.create(settings)
            session = SessionName.of(projectId, uuid)

            Log.d(TAG, "Dialogflow inicializado correctamente")
        } catch (e: Exception) {
            Log.e(TAG, "Error al inicializar Dialogflow: ${e.message}")
        }
    }

    private fun initializeTextToSpeech() {
        textToSpeech = TextToSpeech(this, this)
    }

    private fun initializeSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                "es-ES"
            ) // Configura el reconocimiento en español
        }
    }

    private fun initializeShakeService() {
        val shakeServiceIntent = Intent(this, ShakeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(shakeServiceIntent)
        } else {
            startService(shakeServiceIntent)
        }
    }

    private fun startListening() {
        var respuesta: String
        if (isNavigationActive) {
            isNavigationActive = !isNavigationActive
            googleMapsHelper.cancelarNavegacion()
        }
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Toast.makeText(this@MainActivity, "Habla ahora", Toast.LENGTH_SHORT).show()
            }


            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                var spokenText = matches?.get(0) ?: ""
                Log.d(TAG, "Texto reconocido: $spokenText")

                if (!bandera) {
                    respuesta = sendToDialogflowBack(spokenText)
                    Log.d(TAG, "Respuesta de Dialogflow: $respuesta")

                    // Separar la respuesta en la primera palabra y el resto
                    val splitRespuesta = respuesta.split(" ", limit = 2)
                    val comando = splitRespuesta[0].lowercase() // La primera palabra
                    val restoTexto =
                        if (splitRespuesta.size > 1) splitRespuesta[1] else "" // El resto del texto

                    when (comando) {
                        "saludo" -> {
                            speak(
                                "¡Bienvenido a tu asistente de detección y autenticación de Billetes!\n" +
                                        "¿Qué deseas hacer hoy? \n" +
                                        "¿Te gustaría verificar el valor de un billete?"
                            )
                        }

                        "verifica.ultimo" -> {
                            val resultado = obtenerUltimoBilleteAnalizado()
                            speak(resultado)
                        }

                        "suma.total" -> {
                            val total = sumarBilletesAnalizados()
                            val message = "La suma total de los billetes analizados es de ${
                                "%.2f".format(total)
                            } bolivianos"
                            speak(message)
                        }

                        "verifica.corte" -> {
                            startPhotoCaptureSequence()
                        }

                        "ubicacion.actual" -> {
                            Log.d(TAG, "Proveedor de GPS inicializado")
                            getLastKnownLocation()
                        }

                        "ubicacion.detalle" -> {
                            if (!::ubucacionDatos.isInitialized) {
                                speak("Aún no obtuve tu ubicación actual")
                            } else {
                                speak("Donde te encuentras se puede categorizar como ${ubucacionDatos.types}")
                            }
                        }

                        "verificar.operacion" -> {
                            if (billetesConBuenaConfianza.count() > 0) {
                                speak("¿Qué deseas hacer con los billetes analizados?")
                                bandera = true
                            } else {
                                speak("No hay billetes analizados aún")
                            }
                        }

                        "repetir.ubicacion" -> {
                            googleMapsHelper.repetirLugaresEncontrados()
                        }

                        "navegacion" -> {
                            if (restoTexto.isNotEmpty()) {
                                googleMapsHelper.buscarLugar(restoTexto)
                                isNavigationActive = true
//                                speak("Navegando hacia $restoTexto")
                                // Aquí puedes llamar a tu lógica de navegación con el resto del texto
//                                iniciarNavegacionEnGoogleMaps(restoTexto)
                            } else {
                                speak("Por favor, indícame el destino para la navegación.")
                            }
                        }

                        else -> {
                            speak("No entendí el comando. Intenta nuevamente.")
                        }
                    }
                } else {
                    spokenText += "aquí están los datos de los billetes que tengo por el momento, ${billetesConBuenaConfianza.toString()}"
                    callChatGPT(spokenText)
                    bandera = false
                }
            }

            override fun onError(error: Int) {
                val errorMessage = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "No se reconoció ninguna coincidencia"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "El reconocimiento de voz está ocupado"
                    SpeechRecognizer.ERROR_CLIENT -> "Error del cliente de reconocimiento de voz"
                    else -> "Error en SpeechRecognizer: $error"
                }
                Log.e(TAG, errorMessage)
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
        })
        speechRecognizer.startListening(recognizerIntent)
    }


    private fun testApiConnection() {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS) // Tiempo para conectar
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)   // Tiempo para escribir
            .readTimeout(
                30,
                java.util.concurrent.TimeUnit.SECONDS
            )    // Tiempo para leer la respuesta
            .build()
        val request = Request.Builder()
            .url("https://53fb-181-115-215-42.ngrok-free.app/test")
            .get()
            .build()

        // Realizar la solicitud en un hilo separado
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // En caso de fallo, loguea el error
                Log.e(TAG, "Error en la solicitud GET: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                // En caso de éxito, loguea la respuesta
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    Log.d(TAG, "Respuesta de la API: $responseBody")
                } else {
                    Log.e(TAG, "Error en la respuesta de la API: ${response.message}")
                }
            }
        })
    }


    private fun openCamera() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            startActivity(cameraIntent)
            startPeriodicCapture()
        } else {
            checkPermissions()
        }
    }

    private fun sendToDialogflow(text: String) {
        try {
            val textInput = TextInput.newBuilder().setText(text).setLanguageCode("es").build()
            val queryInput = QueryInput.newBuilder().setText(textInput).build()

            val request = DetectIntentRequest.newBuilder()
                .setSession(session.toString())
                .setQueryInput(queryInput)
                .build()

            val response = sessionsClient.detectIntent(request)
            val fulfillmentText = response.queryResult.fulfillmentText

            Log.d(TAG, "Respuesta de Dialogflow: $fulfillmentText")
            speak(fulfillmentText)
        } catch (e: Exception) {
            Log.e(TAG, "Error al enviar mensaje a Dialogflow: ${e.message}")
        }
    }

    private fun sendToDialogflowBack(text: String): String {
        try {
            val textInput = TextInput.newBuilder().setText(text).setLanguageCode("es").build()
            val queryInput = QueryInput.newBuilder().setText(textInput).build()

            val request = DetectIntentRequest.newBuilder()
                .setSession(session.toString())
                .setQueryInput(queryInput)
                .build()

            val response = sessionsClient.detectIntent(request)
            val fulfillmentText = response.queryResult.fulfillmentText

            Log.d(TAG, "Respuesta de Dialogflow: $fulfillmentText")
//            speak(fulfillmentText)
            return fulfillmentText;
        } catch (e: Exception) {
            Log.e(TAG, "Error al enviar mensaje a Dialogflow: ${e.message}")
        }
        return "error";
    }


    private fun speak(text: String) {
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech.language = Locale("es", "ES")
        } else {
            Log.e(TAG, "Error al inicializar TextToSpeech")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            permissions.forEachIndexed { index, permission ->
                val isGranted = grantResults[index] == PackageManager.PERMISSION_GRANTED
                val message = if (isGranted) "concedido" else "denegado"
                Toast.makeText(this, "Permiso de $permission $message", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkAccessibilityPermission() {
        if (!isAccessibilityServiceEnabled(AppOpeningService::class.java)) {
            showDialog = true
        }
    }

    private fun isAccessibilityServiceEnabled(service: Class<out AccessibilityService>): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return enabledServices?.contains(service.name) ?: false
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }

    private fun showAccessibilityDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permiso de Accesibilidad Requerido")
            .setMessage("Esta aplicación requiere acceso a los servicios de accesibilidad para funcionar correctamente. ¿Quieres activarlos ahora?")
            .setPositiveButton("Ir a Configuración") { _, _ ->
                openAccessibilitySettings()
            }
            .setNegativeButton("Cancelar") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    //?------------------
    private val captureRunnable = object : Runnable {
        override fun run() {
            if (isCapturing) {
                takePhotoAndSendToApi()
                handler.postDelayed(this, captureInterval) // Repetir cada 3 segundos
            }
        }
    }

    private fun startPeriodicCapture() {
        handler.post(object : Runnable {
            override fun run() {
                if (isCameraInitialized) {
                    takePhotoAndSendToApi()
                    handler.postDelayed(this, captureInterval)
                } else {
                    Log.d("CameraX", "La cámara no está inicializada aún")
                }
            }
        })
    }

    private fun stopPeriodicCapture() {
        isCapturing = false
        handler.removeCallbacks(captureRunnable)
    }

    private fun takePhotoAndSendToApi() {
        // Tomar una foto con CameraX
        val outputOptions = ImageCapture.OutputFileOptions.Builder(createTempFile()).build()

        imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val bitmap = BitmapFactory.decodeFile(outputFileResults.savedUri?.path)
                    bitmap?.let { sendPhotoToApi(it) }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("CameraX", "Error al capturar la imagen: ${exception.message}", exception)
                }
            })
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CAMERA_REQUEST_CODE && resultCode == RESULT_OK) {
            val photo = data?.extras?.get("data") as Bitmap
            sendPhotoToApi(photo)
        }
    }

    //?---------------------------------------------
    //************************************************************
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        Log.e(TAG, "camacar activa")

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            imageCapture = ImageCapture.Builder().build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, imageCapture)

                // Indica que la cámara ha sido inicializada
                isCameraInitialized = true
                startPeriodicCapture()
            } catch (exc: Exception) {
                Log.e("CameraX", "Error al inicializar la cámara", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun startCamera2() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        Log.e(TAG, "Activando la cámara")

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            imageCapture = ImageCapture.Builder().build()
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Desvincula cualquier uso previo de la cámara
                cameraProvider.unbindAll()

                // Vincula la cámara al ciclo de vida de la actividad
                cameraProvider.bindToLifecycle(this, cameraSelector, imageCapture)
                Log.d(TAG, "Cámara inicializada correctamente")

                // Marca la cámara como inicializada
                isCameraInitialized = true

                // Inicia la captura de fotos solo después de que la cámara esté inicializada
                captureAndSendPhoto()

            } catch (exc: Exception) {
                Log.e("CameraX", "Error al inicializar la cámara", exc)
                isCameraInitialized = false
            }

        }, ContextCompat.getMainExecutor(this))
    }

    //************************************************************
    private fun stopCamera() {
        if (isCapturing) {
            isCapturing = false
            handler.removeCallbacks(captureRunnable) // Detiene la captura periódica
        }

        // Libera los recursos de CameraX si es necesario
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            cameraProvider.unbindAll() // Detiene todos los casos de uso de CameraX
            isCameraInitialized =
                false // Cambia el estado para indicar que la cámara está desactivada
            Log.d("CameraX", "CameraX detenido y recursos liberados")
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        textToSpeech.shutdown()
        speechRecognizer.destroy()
    }
    //---------------


    private fun startPhotoCaptureSequence() {
        photoCount = 0
        responseList.clear()
        speak("mantenga la camara fija por 3 segundos por favor")
        startCamera2()
        //captureAndSendPhoto()
    }

    private fun captureAndSendPhoto() {
        if (photoCount < maxPhotos) {
            val photoFile = File(externalMediaDirs.firstOrNull(), "photo.jpg")
            val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

            imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        val bitmap = BitmapFactory.decodeFile(photoFile.absolutePath)
                        bitmap?.let { sendPhotoToApi(it) }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.e(TAG, "Error al capturar la imagen: ${exception.message}", exception)
                    }
                })
        } else {
            processAndSpeakHighestConfidence()
            stopCamera()
        }
    }

    private fun processAndSpeakHighestConfidence() {
        if (responseList.isNotEmpty()) {
            // Encuentra el resultado con la mayor confianza
            val bestResult = responseList.maxByOrNull { it.first }
            bestResult?.let {
                guardarBilleteConBuenaConfianza(it.first, it.second)
                val message =
                    "billete de ${it.second} con una confianza de ${"%.1f".format(it.first)}% "
                speak(message) // Usa TextToSpeech para decir el resultado

            }
        }
    }

    private fun sendPhotoToApi(photo: Bitmap) {
        val outputStream = ByteArrayOutputStream()
        photo.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
        val photoData = outputStream.toByteArray()

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "image",
                "photo.jpg",
                RequestBody.create("image/jpeg".toMediaTypeOrNull(), photoData)
            )
            .build()

        val request = Request.Builder()
            .url("https://be97-181-115-215-42.ngrok-free.app/predict") // Cambia la URL a la de tu API
            .post(requestBody)
            .build()

        //val client = OkHttpClient()
        clientN.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Error al enviar la imagen: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    Log.d(TAG, "Respuesta de la API: $responseBody")

                    try {
                        val jsonObject = JSONObject(responseBody)
                        val confidence = jsonObject.getDouble("confidence")
                        val predictedLabel = jsonObject.getString("predicted_label")

                        // Agrega la respuesta a la lista
                        responseList.add(Pair(confidence, predictedLabel))
                        photoCount++ // Incrementa el contador de fotos

                        // Inicia la siguiente captura después de un intervalo
                        handler.postDelayed({ captureAndSendPhoto() }, captureInterval)

                    } catch (e: JSONException) {
                        Log.e(TAG, "Error al parsear la respuesta de la API", e)
                    }
                } else {
                    Log.e(TAG, "Error en la respuesta de la API: ${response.message}")
                }
            }
        })
    }


    private fun callChatGPT(prompt: String) {

        val apiKey =
            "tuapi"
        val url = "https://api.openai.com/v1/chat/completions"
        val client = OkHttpClient()

        // Formato ajustado del cuerpo de la solicitud
        val jsonBody = """
                                {
                                    "model": "gpt-4",
                                    "messages": [
                                        {
                                            "role": "user",
                                            "content": "$prompt"
                                        }
                                    ]
                                }
                            """.trimIndent()

        val requestBody = RequestBody.create("application/json".toMediaTypeOrNull(), jsonBody)
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("Authorization", "Bearer $apiKey")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                //onError(e.message ?: "Error desconocido")
                Log.e(TAG, "Error desconocido")
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    try {
                        val jsonObject = JSONObject(responseBody ?: "")
                        val choices = jsonObject.getJSONArray("choices")
                        val messageContent = choices.getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content")
                            .trim()
                        Log.d("GPT", messageContent)

                        speak(messageContent)
                    } catch (e: JSONException) {
                        //onError("Error al parsear la respuesta de ChatGPT")
                        Log.e(TAG, "Error al parsear la respuesta de ChatGPT", e)
                    }
                } else {
                    //onError("Error en la solicitud: ${response.message}")
                    Log.e(TAG, "Error en la solicitud: ${response.message}")
                }
            }
        })
    }

    private fun getLastKnownLocation() {


        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            fusedLocationClient.getCurrentLocation(
                LocationRequest.PRIORITY_HIGH_ACCURACY,  // Nivel de precisión requerido
                null // No requiere token de cancelación
            ).addOnSuccessListener { location ->
                if (location != null) {
                    val latitude = location.latitude
                    val longitude = location.longitude
                    val roundedLatitude = String.format("%.6f", latitude)
                    val roundedLongitude = String.format("%.6f", longitude)
                    Log.d(TAG, "Ubicación obtenida: $roundedLatitude, $roundedLongitude")

                    // Crear el objeto de ubicación
                    ubicacionActual =
                        Puntos(roundedLatitude.toDouble(), roundedLongitude.toDouble())

                    // Llamar a la API de Google
                    sendToApiGoogle(ubicacionActual) { ubicacionDato ->
                        if (ubicacionDato != null) {
                            // Verificar si está en un área conocida
                            val areaName = getAreaNameForLocation(
                                ubicacionActual.latitud,
                                ubicacionActual.longitud
                            )

                            if (areaName.isNotEmpty()) {
                                // Actualizar con el nombre del área conocida
                                println(areaName)
                                ubucacionDatos = UbicacionDato(areaName, ubicacionDato.types)
                                //speak("Tu te encuentras en $areaName")
                            } else {
                                ubucacionDatos = ubicacionDato
                            }
                            speak("te encuentras en ${ubucacionDatos.longName}")
                            // Imprimir datos finales
                            println("Ubicación obtenida y guardada: $ubucacionDatos")
                        } else {
                            // Manejo de error al obtener datos de la API
                            println("No se pudo obtener la ubicación de la API de Google.")
                            speak("No se pudo determinar tu ubicación.")
                        }
                    }
                } else {
                    Log.e(TAG, "No se pudo obtener la ubicación del dispositivo.")
                    Toast.makeText(this, "No se pudo obtener la ubicación", Toast.LENGTH_SHORT)
                        .show()
                }
            }
        } else {
            requestLocationPermission() // Solicitar permisos si no están otorgados
        }

    }

    private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            PERMISSIONS_REQUEST_CODE
        )
    }


    fun getAreaNameForLocation(latitude: Double, longitude: Double): String {
        for (area in AreasConfig.areas) {
            if (isPointInsideArea(latitude, longitude, area.puntos)) {
                return area.name // Devuelve el nombre del área si el punto está dentro
            }
        }
        return "" // Devuelve vacío si no coincide con ninguna área
    }

    fun isPointInsideArea(lat: Double, lng: Double, points: List<Pair<Double, Double>>): Boolean {
        // Asegúrate de que hay exactamente 4 puntos
        if (points.size != 4) return false

        // Extrae los puntos
        val (p1, p2, p3, p4) = points

        // Comprueba si el punto está dentro del área
        val minLat = listOf(p1.first, p2.first, p3.first, p4.first).minOrNull() ?: return false
        val maxLat = listOf(p1.first, p2.first, p3.first, p4.first).maxOrNull() ?: return false
        val minLng = listOf(p1.second, p2.second, p3.second, p4.second).minOrNull() ?: return false
        val maxLng = listOf(p1.second, p2.second, p3.second, p4.second).maxOrNull() ?: return false

        return lat in minLat..maxLat && lng in minLng..maxLng
    }

    fun sendToApiGoogle(ubicacion: Puntos, callback: (UbicacionDato?) -> Unit) {
        val apiGeoGoogle = "tuapi"
        val urlGeo =
            "https://maps.googleapis.com/maps/api/geocode/json?latlng=${ubicacion.latitud},${ubicacion.longitud}&key=$apiGeoGoogle"

        val client = OkHttpClient()

        val request = Request.Builder()
            .url(urlGeo)
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                println("Error en la solicitud: ${e.message}")
                callback(null) // Devolvemos un resultado nulo en caso de error
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    response.body?.let { responseBody ->
                        try {
                            val responseData = responseBody.string()
                            val jsonObject = JSONObject(responseData)
                            val results = jsonObject.getJSONArray("results")

                            // Extraer datos directamente
                            val firstResult = results.getJSONObject(0)
                            val placeId = firstResult.getString("place_id")

                            val typesArray = firstResult.getJSONArray("types")
                            val typesList = mutableListOf<String>()
                            for (i in 0 until typesArray.length()) {
                                typesList.add(typesArray.getString(i))
                            }

                            val addressComponent =
                                firstResult.getJSONArray("address_components").getJSONObject(0)
                            val longName = addressComponent.getString("long_name")

                            val translatedTypes = Translations.translateTypes(typesList)


                            callback(UbicacionDato(longName, translatedTypes))
                        } catch (e: Exception) {
                            println("Error al procesar la respuesta: ${e.message}")
                            callback(null) // En caso de error al procesar JSON, devolvemos nulo
                        }
                    } ?: run {
                        println("El cuerpo de la respuesta es nulo")
                        callback(null)
                    }
                } else {
                    println("Error en la respuesta: Código ${response.code}")
                    callback(null)
                }
            }
        })
    }


}