package com.astracam.dualdevice.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

sealed class GeminiEnhanceResult {
    data class DirectImage(val bitmap: Bitmap) : GeminiEnhanceResult()
    data class TuningRecipe(val params: DslrTuningParameters) : GeminiEnhanceResult()
}

object GeminiAiEnhancer {
    private const val TAG = "AstraCam_Gemini"
    private const val PREFS_NAME = "astracam_ai_prefs"
    private const val KEY_GEMINI_API_KEY = "gemini_api_key"

    // Primary Nano Banana 2 target models
    private const val PRIMARY_MODEL = "gemini-3.1-flash-image"
    private const val FALLBACK_MODEL = "gemini-3.1-flash-image-preview"

    private const val MASTER_PROMPT = """
You are Nano Banana 2 (Gemini 3.1 Flash Image), specialized in conversational image editing and DSLR enhancement.
Enhance this photograph with professional DSLR characteristics: lift deep shadows cleanly, compress clipped highlights, correct white balance, and refine edge clarity.
Output either the edited image directly or output ONLY a raw JSON object with tuning parameters:
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
    ): GeminiEnhanceResult = withContext(Dispatchers.IO) {
        val apiKey = getSavedApiKey(context)
            ?: throw IllegalStateException("Gemini API key is missing. Please enter your API Key.")

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

        return@withContext try {
            callNanoBananaEndpoint(PRIMARY_MODEL, apiKey, base64Image)
        } catch (e: Exception) {
            Log.w(TAG, "Primary $PRIMARY_MODEL returned: ${e.message}. Trying $FALLBACK_MODEL...")
            callNanoBananaEndpoint(FALLBACK_MODEL, apiKey, base64Image)
        }
    }

    private fun callNanoBananaEndpoint(
        modelName: String,
        apiKey: String,
        base64Image: String
    ): GeminiEnhanceResult {
        val endpointUrl = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"
        val conn = (URL(endpointUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = 16000
            readTimeout = 16000
        }

        val requestBody = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
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
            put("generationConfig", JSONObject().apply {
                put("responseModalities", JSONArray().apply {
                    put("IMAGE")
                    put("TEXT")
                })
            })
        }

        OutputStreamWriter(conn.outputStream).use { it.write(requestBody.toString()) }

        val code = conn.responseCode
        if (code != 200) {
            val errorResponse = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            Log.e(TAG, "Nano Banana 2 Error $code: $errorResponse")
            throw IllegalStateException(parseGoogleErrorMessage(errorResponse, code))
        }

        val responseText = conn.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(responseText)
        val parts = json
            .getJSONArray("candidates")
            .getJSONObject(0)
            .getJSONObject("content")
            .getJSONArray("parts")

        // Check if an enhanced image was returned directly
        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            if (part.has("inlineData")) {
                val inline = part.getJSONObject("inlineData")
                val imgData = inline.getString("data")
                val decodedBytes = Base64.decode(imgData, Base64.DEFAULT)
                val directBmp = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                if (directBmp != null) {
                    return GeminiEnhanceResult.DirectImage(directBmp)
                }
            }
        }

        // Fallback: parse tuning JSON parameters
        val rawText = parts.getJSONObject(0).optString("text", "{}")
        val clean = rawText.substringAfter("{").substringBeforeLast("}")
        val recipe = JSONObject("{$clean}")

        val params = DslrTuningParameters(
            exposure = recipe.optDouble("exposure", 0.0).toFloat().coerceIn(-40f, 40f),
            shadows = recipe.optDouble("shadows", 35.0).toFloat().coerceIn(0f, 75f),
            highlights = recipe.optDouble("highlights", 25.0).toFloat().coerceIn(0f, 70f),
            contrast = recipe.optDouble("contrast", 10.0).toFloat().coerceIn(-25f, 25f),
            clarity = recipe.optDouble("clarity", 25.0).toFloat().coerceIn(0f, 55f),
            saturation = recipe.optDouble("saturation", 15.0).toFloat().coerceIn(-25f, 25f),
            temperature = recipe.optDouble("temperature", 0.0).toFloat().coerceIn(-25f, 25f),
            sharpness = recipe.optDouble("sharpness", 35.0).toFloat().coerceIn(0f, 60f)
        )

        return GeminiEnhanceResult.TuningRecipe(params)
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