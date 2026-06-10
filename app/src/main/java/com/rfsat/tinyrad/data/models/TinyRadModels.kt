package com.rfsat.tinyrad.data.models

// ─── USB connection state ─────────────────────────────────────────────────────

enum class UsbConnectionState {
    DISCONNECTED,
    REQUESTING_PERMISSION,
    CONNECTING,
    CONNECTED,
    ERROR
}

// ─── Object classification ────────────────────────────────────────────────────

enum class ObjectClass(val displayName: String, val colorArgb: Long) {
    HUMAN           ("Human",           0xFF43C59E),
    ANIMAL          ("Animal",          0xFFFFB347),
    GROUND_VEHICLE  ("Ground Vehicle",  0xFF6FA8DC),
    AERIAL_VEHICLE  ("Aerial Vehicle",  0xFFCC99FF),
    UNKNOWN         ("Unknown",         0xFF888888)
}

// ─── Direction (azimuth sectors) ─────────────────────────────────────────────

enum class Direction(val displayName: String, val azimuthDeg: Float) {
    AHEAD       ("Ahead",        0f),
    AHEAD_RIGHT ("Ahead-Right",  45f),
    RIGHT       ("Right",        90f),
    BEHIND_RIGHT("Behind-Right", 135f),
    BEHIND      ("Behind",       180f),
    BEHIND_LEFT ("Behind-Left", -135f),
    LEFT        ("Left",        -90f),
    AHEAD_LEFT  ("Ahead-Left",  -45f)
}

// ─── Detected object ─────────────────────────────────────────────────────────
//
// A single tracked object returned by the TinyRAD DSP pipeline.
// All values are in SI units or degrees where stated.

data class DetectedObject(
    val trackId:        Int,            // Persistent track identifier
    val objectClass:    ObjectClass,
    val distanceM:      Float,          // Slant range, metres
    val azimuthDeg:     Float,          // Horizontal angle, degrees (0 = boresight)
    val elevationDeg:   Float,          // Vertical angle, degrees (0 = horizontal)
    val speedMps:       Float,          // Radial velocity, m/s (+ = approaching)
    val direction:      Direction,      // Quantised approach direction
    val snrDb:          Float,          // Signal-to-noise ratio, dB
    val confidence:     Float,          // Classifier confidence 0..1
    val timestampMs:    Long            // System.currentTimeMillis() of detection
) {
    /** Speed in km/h for display */
    val speedKmh: Float get() = speedMps * 3.6f

    /** True when object is approaching the radar */
    val isApproaching: Boolean get() = speedMps > 0.1f

    /** True when object is receding */
    val isReceding: Boolean get() = speedMps < -0.1f
}

// ─── Per-frame radar data ─────────────────────────────────────────────────────
//
// Raw ADC snapshot and processed results for one radar chirp frame.

data class RadarFrame(
    val frameIndex:     Long,
    val timestampMs:    Long,
    val detectedObjects: List<DetectedObject>,
    // Range-Doppler magnitude matrix (rows = range bins, cols = Doppler bins)
    // Stored as a flat array: index = row * dopplerBins + col
    val rangeDopplerMag: FloatArray = FloatArray(0),
    val rangeBins:      Int = 0,
    val dopplerBins:    Int = 0,
    val rangeResM:      Float = 0f,     // metres per range bin
    val dopplerResMs:   Float = 0f      // m/s per Doppler bin
) {
    // FloatArray in data class — override equals/hashCode manually
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RadarFrame) return false
        return frameIndex == other.frameIndex && timestampMs == other.timestampMs
    }
    override fun hashCode(): Int = frameIndex.hashCode() * 31 + timestampMs.hashCode()
}

// ─── TinyRAD configuration ────────────────────────────────────────────────────

data class TinyRadConfig(
    // RF
    val startFreqGHz:   Float = 24.0f,
    val bandwidthMHz:   Float = 250f,
    val txPowerDbm:     Int   = 0,
    // Timing — hardware parameters sent to board during init
    val chirpDurationUs:Int   = 512,
    val chirpRepUs:     Int   = 1000,
    val framesPerSec:   Int   = 10,
    // Chirp accumulation — how many chirps to collect per processed frame.
    // Fewer chirps = faster updates, coarser Doppler resolution.
    // More chirps = slower updates, finer Doppler resolution.
    //   16 chirps × 40ms = 640ms/frame ≈ 1.5 fps  (good for fast detection)
    //   32 chirps × 40ms = 1280ms/frame ≈ 0.8 fps
    //   80 chirps × 40ms = 3200ms/frame ≈ 0.3 fps  (original, best Doppler res)
    val chirpsPerFrame: Int   = 16,
    // Processing
    val rangeFftSize:   Int   = 256,
    val dopplerFftSize: Int   = 64,
    val cfar_guard:     Int   = 2,
    val cfar_training:  Int   = 8,
    val cfar_threshold: Float = 10f,    // lowered: 10 dB for wider detection coverage
    // Detection gate — wide defaults for full 180° coverage
    val maxRangeM:      Float = 100f,   // board spec: 100m max for RCS=1m²
    val maxSpeedMps:    Float = 50f,
    val minSnrDb:       Float = 8f      // lowered: 8 dB catches weaker returns
)

// ─── App-level UI state ───────────────────────────────────────────────────────

data class TinyRadUiState(
    val connectionState:    UsbConnectionState  = UsbConnectionState.DISCONNECTED,
    val deviceName:         String              = "TinyRAD (not connected)",
    val isStreaming:         Boolean             = false,
    val isRecording:         Boolean             = false,
    val currentFrame:        RadarFrame?         = null,
    val trackedObjects:      List<DetectedObject> = emptyList(),
    val frameRate:           Float               = 0f,
    val totalFrames:         Long                = 0L,
    val recordingPath:       String?             = null,
    val recordingRows:       Int                 = 0,
    val config:              TinyRadConfig       = TinyRadConfig(),
    val errorMessage:        String?             = null
)
