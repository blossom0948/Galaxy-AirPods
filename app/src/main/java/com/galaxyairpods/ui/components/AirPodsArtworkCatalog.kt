package com.galaxyairpods.ui.components

import com.galaxyairpods.domain.model.AirPodsModel

/**
 * Describes which artwork pipeline is safe for a model.
 *
 * The bitmap layer set currently has a verified visual reference only for the
 * AirPods Pro (2nd generation USB-C) silhouette. Other models intentionally
 * keep the existing model-specific vector fallback until their own layers are
 * reviewed; a visually similar product must not be presented as an exact
 * generation.
 */
internal enum class AirPodsArtworkTechnology {
    BITMAP_LAYERS,
    VECTOR_FALLBACK,
}

internal fun AirPodsModel.artworkTechnology(): AirPodsArtworkTechnology = when (this) {
    AirPodsModel.AIRPODS_PRO2_USBC -> AirPodsArtworkTechnology.BITMAP_LAYERS
    else -> AirPodsArtworkTechnology.VECTOR_FALLBACK
}
