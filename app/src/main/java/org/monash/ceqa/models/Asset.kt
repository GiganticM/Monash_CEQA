package org.monash.ceqa.models

/**
 * Represents a single scan event.
 *
 * @param assetNumber  The canonical asset number
 * @param department   The department/location (from source CSV or user-entered for NEW assets)
 * @param make         User-entered make      - NEW assets only
 * @param model        User-entered model     - NEW assets only
 * @param serial       User-entered serial    - NEW assets only
 * @param timestamp    HH:mm:ss captured at write time
 * @param isNew        True when the asset was not found in the source CSV
 */
data class Asset(
    val assetNumber:   String,
    val department:    String  = "",
    val make:          String  = "",
    val model:         String  = "",
    val serial:        String  = "",
    val timestamp:     String  = "",
    val isNew:         Boolean = false,
    val routinePeriod: Int     = 0    // 0 = blank/6mth (no sticker), 12, 24, or 60
)
