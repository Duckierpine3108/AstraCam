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
    private const val KEY_WORKING_MODEL = "cached_working_model"

    // Multi-model auto-fallback cascade
    private val CANDIDATE_MODELS = listOf(
        "gemini-2.0-flash",
        "gemini-2.5-flash",
        "gemini-1.5-flash-latest",
        "gemini-1.5-flash",
        "gemini-flash-latest"
    )

    // Master Universal Prompt for all photos
    private const val UNIVERSAL_MASTER_PROMPT = """
You are an expert computational photography director and DSLR post-processing engine.
Analyze this photograph comprehensively: lighting balance, dynamic range, shadow depth, specular highlights, color temperature, and micro-contrast.
Whether this image is a portrait, landscape, low-light shot, or computer monitor screen, calculate the optimal tuning parameters to produce a natural, edge-clean, cinematic DSLR result.
Output ONLY a raw, valid JSON object with these exact keys:
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
            ?: throw IllegalStateException("Gemini API key not configured. Tap '✨ Gemini AI'.")

        // Downscale image to 512px for low-latency transmission
        val scale = 512f / max(bitmap.width, bitmap.height)
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt(),
            (bitmap.height * scale).toInt(),
            true
        )

        val outputStream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        val base64Image = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cachedModel = prefs.getString(KEY_WORKING_MODEL, null)

        val modelsToTry = mutableListOf<String>()
        if (!cachedModel.isNullOrEmpty()) modelsToTry.add(cachedModel)
        for (m in CANDIDATE_MODELS) {
            if (!modelsToTry.contains(m)) modelsToTry.add(m)
        }

        var lastError = ""

        for (model in modelsToTry) {
            try {
                val result = callGeminiEndpoint(model, apiKey, base64Image)
                // Cache successful model
                prefs.edit().putString(KEY_WORKING_MODEL, model).apply()
                return@withContext result
            } catch (e: Exception) {
                lastError = e.message ?: "Unknown error"
                Log.w(TAG, "Model $model failed: $lastError. Trying next candidate...")
            }
        }

        throw IllegalStateException("AI Analysis failed: $lastError")
    }

    private fun callGeminiEndpoint(model: String, apiKey: String, base64Image: String): DslrTuningParameters {
        val endpointUrl = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val conn = (URL(endpointUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = 14000
            readTimeout = 14000
        }

        val requestBody = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", UNIVERSAL_MASTER_PROMPT.trim()) })
                        put(JSONObject().apply {
                            put("inline_data", JSONObject().apply {
                                put("mime_type", "image/jpeg")
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
            throw IllegalStateException("HTTP $code: $errorResponse")
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

        return DslrTuningParameters(
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
}