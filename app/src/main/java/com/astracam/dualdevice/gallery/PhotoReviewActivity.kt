private fun executeGeminiEnhance() {
        val base = baseUprightBitmap ?: return

        binding.aiScanOverlay.startScanning()
        binding.aiStatusBadge.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                when (val result = GeminiAiEnhancer.analyzeAndOptimize(this@PhotoReviewActivity, base)) {
                    is GeminiEnhanceResult.DirectImage -> {
                        // Nano Banana 2 generated the edited image directly
                        currentEnhancedBitmap = result.bitmap
                        binding.imagePreview.setImageBitmap(result.bitmap)
                        binding.rbEnhanced.isChecked = true
                        Toast.makeText(this@PhotoReviewActivity, "✨ Nano Banana 2 AI Image Applied!", Toast.LENGTH_SHORT).show()
                    }
                    is GeminiEnhanceResult.TuningRecipe -> {
                        // Nano Banana 2 generated optimal parameters
                        val aiParams = result.params
                        tuningParams.exposure = aiParams.exposure
                        tuningParams.shadows = aiParams.shadows
                        tuningParams.highlights = aiParams.highlights
                        tuningParams.contrast = aiParams.contrast
                        tuningParams.clarity = aiParams.clarity
                        tuningParams.saturation = aiParams.saturation
                        tuningParams.temperature = aiParams.temperature
                        tuningParams.sharpness = aiParams.sharpness

                        binding.sbShadows.progress = aiParams.shadows.toInt()
                        binding.sbHighlights.progress = aiParams.highlights.toInt()
                        binding.sbClarity.progress = aiParams.clarity.toInt()
                        binding.sbWarmth.progress = (aiParams.temperature + 50).toInt()
                        binding.sbSharpness.progress = aiParams.sharpness.toInt()

                        triggerRender()
                        Toast.makeText(this@PhotoReviewActivity, "✨ Nano Banana 2 Tuning Applied!", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                AlertDialog.Builder(this@PhotoReviewActivity)
                    .setTitle("Gemini AI Diagnostic")
                    .setMessage(e.message ?: "Unknown communication error.")
                    .setPositiveButton("Change Key") { _, _ -> showApiKeyInputDialog() }
                    .setNegativeButton("OK", null)
                    .show()
            } finally {
                binding.aiScanOverlay.stopScanning()
                binding.aiStatusBadge.visibility = View.GONE
            }
        }
    }