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
            ?: throw IllegalStateException("Gemini API key not configured. Tap 'Set Gemini API Key'.")

        // Downscale to 512px for low-latency transmission
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

        // Gemini 1.5 Flash REST endpoint
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = 12000
            readTimeout = 12000
        }

        val prompt = "You are a master DSLR camera colorist and computational photography algorithm. " +
                "Analyze this photo's lighting, shadow depth, highlight clipping, white balance, and contrast. " +
                "Return ONLY a raw JSON object with the best tuning numbers. No markdown, no quotes around JSON, just the JSON:\n" +
                "{\"exposure\": 0, \"shadows\": 40, \"highlights\": 30, \"contrast\": 15, \"clarity\": 30, \"saturation\": 20, \"temperature\": 5, \"sharpness\": 45}"

        val requestBody = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", prompt) })
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

        val responseCode = conn.responseCode
        if (responseCode != 200) {
            val errorText = conn.errorStream?.bufferedReader()?.use { it.readText() }
            Log.e(TAG, "Gemini API failed: $responseCode - $errorText")
            throw IllegalStateException("Gemini API returned error $responseCode: Check your API Key.")
        }

        val responseText = conn.inputStream.bufferedReader().use { it.readText() }
        val jsonResponse = JSONObject(responseText)
        val candidateText = jsonResponse
            .getJSONArray("candidates")
            .getJSONObject(0)
            .getJSONObject("content")
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")

        // Parse extracted JSON values
        val cleanJson = candidateText.substringAfter("{").substringBeforeLast("}")
        val recipe = JSONObject("{$cleanJson}")

        return@withContext DslrTuningParameters(
            exposure = recipe.optDouble("exposure", 0.0).toFloat().coerceIn(-50f, 50f),
            shadows = recipe.optDouble("shadows", 35.0).toFloat().coerceIn(0f, 100f),
            highlights = recipe.optDouble("highlights", 25.0).toFloat().coerceIn(0f, 100f),
            contrast = recipe.optDouble("contrast", 10.0).toFloat().coerceIn(-50f, 50f),
            clarity = recipe.optDouble("clarity", 25.0).toFloat().coerceIn(0f, 100f),
            saturation = recipe.optDouble("saturation", 15.0).toFloat().coerceIn(-50f, 50f),
            temperature = recipe.optDouble("temperature", 0.0).toFloat().coerceIn(-50f, 50f),
            sharpness = recipe.optDouble("sharpness", 40.0).toFloat().coerceIn(0f, 100f)
        )
    }
}