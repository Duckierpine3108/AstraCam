package com.astracam.dualdevice.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.astracam.dualdevice.processing.DslrTuningParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

object GeminiAiEnhancer {
    private const val TAG = "AstraCam_Gemini"
    private const val PREFS_NAME = "astracam_ai_prefs"
    private const val KEY_GEMINI_API_KEY = "gemini_api_key"

    // One master prompt compatible with all scenes
    private const val MASTER_PROMPT = """
You are an expert DSLR colorist and computational photography engine.
Analyze this photo: lighting, dynamic range, shadows, specular highlights, color temperature, and micro-contrast.
Whether this image is a portrait, landscape, low-light shot, or computer monitor screen, calculate the optimal tuning parameters to produce a natural, edge-clean, cinematic DSLR result.
Output ONLY a raw JSON object with these exact keys:
{
  "exposure": <Float between -30.0 and +30.0>,
  "shadows": <Float between 0.0 and 70.0>,
  "highlights": <Float between 0.0 and 65.0>,
  "contrast": <Float between -20.0 and +25.0>,
  "clarity": <Float between 0.0 and 50.0>,
  "saturation": <Float between -20.0 and +25.0>,
  "temperature": <Float between -25.0 and +25.0>,
  "sharpness": <Float between 15.0 and 55.0>
}
Output NO explanation, NO markdown code blocks, ONLY the raw JSON object.
"""

    fun getSavedApiKey(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_GEMINI_API_KEY, null)?.trim()
    }

    fun saveApiKey(context: Context, key: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_GEMINI_API_KEY, key.trim()).apply()
    }

    suspend fun analyzeAndOptimize(
        context: Context,
        bitmap: Bitmap
    ): DslrTuningParameters = withContext(Dispatchers.IO) {
        val apiKey = getSavedApiKey(context)
            ?: throw IllegalStateException("Gemini API key is missing. Please enter your API Key.")

        // 1. Resolve available models for this specific API key
        val targetModel = discoverWorkingModel(apiKey)
        Log.i(TAG, "Selected active model: $targetModel")

        // 2. Downscale thumbnail to 512px for low-latency transmission
        val scale = 512f / max(bitmap.width, bitmap.height)
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt(),
            (bitmap.height * scale).toInt(),
            true
        )

        val outputStream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
        val base64Image = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

        // 3. Make generateContent request
        val endpointUrl = "https://generativelanguage.googleapis.com/v1beta/$targetModel:generateContent?key=$apiKey"
        val conn = (URL(endpointUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = 15000
            readTimeout = 15000
        }

        // Compliant JSON using camelCase inlineData & mimeType
        val requestBody = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", MASTER_PROMPT.trim()) })
                        put(JSONObject().apply {
                            put("inlineData", JSONObject().apply {
                                put("mimeType", "image/jpeg")
                                put("data", base64Image)
                            })
                        })
                    })
                })
            })
        }

        OutputStreamWriter(conn.outputStream).use { it.write(requestBody.toString()) }

        val code = conn.responseCode
        if (code != 200) {
            val errorResponse = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            Log.e(TAG, "generateContent failed ($code): $errorResponse")
            val readableMessage = parseGoogleErrorMessage(errorResponse, code)
            throw IllegalStateException(readableMessage)
        }

        val responseText = conn.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(responseText)
        val rawText = json
            .getJSONArray("candidates")
            .getJSONObject(0)
            .getJSONObject("content")
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")

        val clean = rawText.substringAfter("{").substringBeforeLast("}")
        val recipe = JSONObject("{$clean}")

        return@withContext DslrTuningParameters(
            exposure = recipe.optDouble("exposure", 0.0).toFloat().coerceIn(-40f, 40f),
            shadows = recipe.optDouble("shadows", 35.0).toFloat().coerceIn(0f, 75f),
            highlights = recipe.optDouble("highlights", 25.0).toFloat().coerceIn(0f, 70f),
            contrast = recipe.optDouble("contrast", 10.0).toFloat().coerceIn(-25f, 25f),
            clarity = recipe.optDouble("clarity", 25.0).toFloat().coerceIn(0f, 55f),
            saturation = recipe.optDouble("saturation", 15.0).toFloat().coerceIn(-25f, 25f),
            temperature = recipe.optDouble("temperature", 0.0).toFloat().coerceIn(-25f, 25f),
            sharpness = recipe.optDouble("sharpness", 35.0).toFloat().coerceIn(0f, 60f)
        )
    }

    /**
     * Queries Google to find the active model enabled on your API key
     */
    private fun discoverWorkingModel(apiKey: String): String {
        val listUrl = "https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey"
        val conn = (URL(listUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
        }

        val code = conn.responseCode
        if (code != 200) {
            val errorResponse = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            val readableMessage = parseGoogleErrorMessage(errorResponse, code)
            throw IllegalStateException("API Key Error: $readableMessage")
        }

        val responseText = conn.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(responseText)
        val models = json.optJSONArray("models") ?: JSONArray()

        val supported = mutableListOf<String>()
        for (i in 0 until models.length()) {
            val m = models.getJSONObject(i)
            val name = m.getString("name") // e.g. "models/gemini-2.0-flash" or "models/gemini-1.5-flash"
            val methods = m.optJSONArray("supportedGenerationMethods") ?: JSONArray()

            var canGenerate = false
            for (j in 0 until methods.length()) {
                if (methods.getString(j) == "generateContent") canGenerate = true
            }

            if (canGenerate) supported.add(name)
        }

        // Pick optimal flash model, or first compatible model
        val chosen = supported.firstOrNull { it.contains("flash", ignoreCase = true) }
            ?: supported.firstOrNull()
            ?: "models/gemini-1.5-flash"

        return chosen
    }

    private fun parseGoogleErrorMessage(errorJson: String, httpCode: Int): String {
        return try {
            val obj = JSONObject(errorJson)
            val errorObj = obj.optJSONObject("error")
            val msg = errorObj?.optString("message")
            val status = errorObj?.optString("status")
            if (!msg.isNullOrEmpty()) "$status ($httpCode): $msg"
            else "HTTP $httpCode: $errorJson"
        } catch (e: Exception) {
            "HTTP $httpCode: Check API key permissions and internet connection."
        }
    }
}