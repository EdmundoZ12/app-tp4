package com.example.app_topicos

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.*
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

class Funciones {

    companion object{
        private fun callChatGPT(prompt: String, onResponse: (String) -> Unit, onError: (String) -> Unit) {

            val apiKey = "tuapi"
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
                    onError(e.message ?: "Error desconocido")
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
                            onResponse(messageContent)
                        } catch (e: JSONException) {
                            onError("Error al parsear la respuesta de ChatGPT")
                        }
                    } else {
                        onError("Error en la solicitud: ${response.message}")
                    }
                }
            })
        }
    }
}