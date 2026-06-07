package com.bobassist.phase0.herotier

/**
 * OCR a captured frame into recognized lines. Boxes are in CAPTURE-bitmap pixels (the impl maps
 * any model-input-space boxes back to capture px before returning). Engine-agnostic seam: the
 * shipped impl (ML Kit baseline vs PP-OCRv5) is chosen by the Spike-A bake-off (spec §6.1).
 */
interface HeroOcr {
    fun recognize(frame: Frame): List<OcrLine>

    /** False when the engine/model can't initialize → the coordinator stays inert (spec §8.2). */
    fun isAvailable(): Boolean = true
}
