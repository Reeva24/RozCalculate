package com.example.network

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

object GeminiManager {
    private const val TAG = "GeminiManager"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val requestAdapter = moshi.adapter(GeminiRequest::class.java)
    private val responseAdapter = moshi.adapter(GeminiResponse::class.java)
    private val resultAdapter = moshi.adapter(CalculatorResult::class.java)

    fun isApiKeyConfigured(): Boolean {
        val key = BuildConfig.GEMINI_API_KEY
        return key.isNotEmpty() && key != "MY_GEMINI_API_KEY"
    }

    suspend fun solveMath(
        prompt: String,
        bitmap: Bitmap? = null
    ): Result<CalculatorResult> = withContext(Dispatchers.IO) {
        if (!isApiKeyConfigured()) {
            return@withContext Result.failure(Exception("Gemini API Key is not configured. Please add your GEMINI_API_KEY in the Secrets panel in AI Studio."))
        }

        try {
            val systemInstructionText = """
                You are the backend engine for a digital replica of the Aristo CT-512 desktop calculator. Your job is to process user inputs and return the exact mathematical results, maintaining a simulated memory and history stack.

                You must support the following features explicitly:
                1. Standard math (+, -, *, /) and percentages (%).
                2. Square Root (√).
                3. Grand Total (GT): Stores the sum of all pressed '=' results until cleared.
                4. Mark Up (MU): Calculate selling price based on cost and margin. For MU, standard format is Cost / (1 - Margin/100).
                5. Check & Correct: Maintain a history array of the current calculation steps so the user can step backward or forward to review.
                6. Memory (M+, M-, MR, MC): Track a dedicated memory variable.

                Respond strictly in a structured JSON format so the application UI can parse it.
                Your response must ONLY contain the JSON object matching this structure:
                {
                  "display": "current_display_string",
                  "history": ["step1", "step2"],
                  "memory": 0,
                  "grand_total": 0
                }
                Do not wrap the JSON in markdown blocks like ```json ... ```. Just return the raw JSON text.
            """.trimIndent()

            val parts = mutableListOf<Part>()
            parts.add(Part(text = prompt))

            if (bitmap != null) {
                val outputStream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
                val base64Data = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
                parts.add(Part(inlineData = InlineData(mimeType = "image/jpeg", data = base64Data)))
            }

            val requestObj = GeminiRequest(
                contents = listOf(Content(parts = parts)),
                generationConfig = GenerationConfig(
                    responseMimeType = "application/json",
                    temperature = 0.2f
                ),
                systemInstruction = Content(parts = listOf(Part(text = systemInstructionText)))
            )

            val requestJson = requestAdapter.toJson(requestObj)
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = requestJson.toRequestBody(mediaType)

            val apiKey = BuildConfig.GEMINI_API_KEY
            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$apiKey"

            val httpRequest = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            client.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: ""
                    Log.e(TAG, "API failed: $errorBody")
                    return@withContext Result.failure(Exception("Gemini API Error: Code ${response.code}. $errorBody"))
                }

                val responseBody = response.body?.string()
                if (responseBody.isNullOrEmpty()) {
                    return@withContext Result.failure(Exception("Empty response from Gemini API."))
                }

                val geminiResponse = responseAdapter.fromJson(responseBody)
                val textResponse = geminiResponse?.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                if (textResponse.isNullOrEmpty()) {
                    return@withContext Result.failure(Exception("No content returned from Gemini model."))
                }

                val cleanedJson = textResponse.trim()
                    .removePrefix("```json")
                    .removePrefix("```")
                    .removeSuffix("```")
                    .trim()

                try {
                    val calcResult = resultAdapter.fromJson(cleanedJson)
                    if (calcResult != null) {
                        return@withContext Result.success(calcResult)
                    } else {
                        return@withContext Result.failure(Exception("Failed to map JSON schema to CalculatorResult."))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse math result JSON: $cleanedJson", e)
                    return@withContext Result.failure(Exception("JSON Parsing error: ${e.localizedMessage}. Raw response: $cleanedJson"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during Gemini API call", e)
            return@withContext Result.failure(e)
        }
    }
}
