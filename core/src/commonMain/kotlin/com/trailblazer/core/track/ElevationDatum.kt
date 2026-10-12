package com.trailblazer.core.track

/**
 * The vertical datum an elevation is measured from. A point whose datum is null (a track recorded before the datum
 * was stored) has an unknown one: it is never assumed to be [Msl].
 */
enum class ElevationDatum {
    /** Height above mean sea level (the geoid), the datum maps, passes and GPX consumers expect. */
    Msl,

    /** Height above the WGS-84 ellipsoid, which can differ from sea level by tens of metres. */
    Ellipsoid,
}

/**
 * The elevation to write into an exported file: only a height known to be above sea level. `<ele>` is read as sea level,
 * so an ellipsoid height, or one of unknown datum, is left out rather than written under a label it may not have.
 * The core has no geoid model, so there is nothing to convert it with.
 */
fun TrackPoint.exportElevationM(): Double? = elevationM?.takeIf { it.isFinite() && datum == ElevationDatum.Msl }
