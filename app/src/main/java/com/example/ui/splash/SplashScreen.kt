package com.example.ui.splash

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.repository.CampusRideRepository
import com.example.data.repository.StartupPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Singleton manager for the Campus Ride Cinematic Sonic Logo.
 * Ensures the sound plays exactly once per launch, prevents dual/overlapping audio,
 * and guarantees immediate cleanup when the splash screen is dismissed.
 */
object CampusSonicLogoPlayer {
    @Volatile
    private var currentAudioTrack: AudioTrack? = null
    private val isPlaying = AtomicBoolean(false)

    /**
     * Synthesizes and plays the original multi-layered Campus Ride brand sonic identity:
     * 1. Atmospheric opening texture (0.0s - 0.4s)
     * 2. Rising electric mobility acceleration swell (0.3s - 1.0s)
     * 3. Distinctive cinematic sub-bass impact (1.0s)
     * 4. Pristine crystal tech harmonic chord (1.0s - 2.4s)
     * 5. Smooth natural exponential reverb decay (completed by 2.5s)
     */
    fun playSonicLogo() {
        if (isPlaying.getAndSet(true)) {
            // Prevent duplicate or overlapping triggers
            return
        }

        Thread {
            var track: AudioTrack? = null
            try {
                val sampleRate = 44100
                val durationMs = 2500 // 2.5 seconds total
                val numSamples = sampleRate * durationMs / 1000
                val audioBuffer = ByteArray(2 * numSamples)

                for (i in 0 until numSamples) {
                    val t = i.toDouble() / sampleRate

                    // --- LAYER 1: Atmospheric Ambient Pad (t: 0.0s -> 1.2s) ---
                    val padEnv = when {
                        t < 0.35 -> (t / 0.35) * (t / 0.35)
                        t < 0.85 -> 1.0 - 0.4 * ((t - 0.35) / 0.5)
                        t < 1.2 -> 0.6 * (1.0 - (t - 0.85) / 0.35)
                        else -> 0.0
                    }
                    val padMod = sin(2.0 * PI * 1.5 * t) * 1.2
                    val padWave = (
                        sin(2.0 * PI * (220.0 + padMod) * t) * 0.40 +
                        sin(2.0 * PI * (329.63 - padMod) * t) * 0.35 +
                        sin(2.0 * PI * 440.0 * t) * 0.25
                    ) * padEnv

                    // --- LAYER 2: Rising Acceleration Swell (t: 0.25s -> 1.02s) ---
                    val riseEnv = when {
                        t in 0.25..1.02 -> {
                            val norm = (t - 0.25) / 0.77
                            norm * norm * norm
                        }
                        t in 1.02..1.18 -> {
                            (1.0 - (t - 1.02) / 0.16)
                        }
                        else -> 0.0
                    }
                    val riseProgress = ((t - 0.25) / 0.77).coerceIn(0.0, 1.0)
                    val riseFreq = 110.0 * (523.25 / 110.0).pow(riseProgress)
                    val riseWave = (
                        sin(2.0 * PI * riseFreq * t) * 0.70 +
                        sin(2.0 * PI * (riseFreq * 2.0) * t) * 0.30
                    ) * riseEnv

                    // --- LAYER 3: Distinctive Low-Frequency Impact (Hits at t = 0.98s) ---
                    val impactEnv = if (t >= 0.98) {
                        val dt = t - 0.98
                        exp(-4.8 * dt) * (1.0 - exp(-75.0 * dt))
                    } else 0.0
                    val impactFreq = if (t >= 0.98) {
                        val dt = t - 0.98
                        42.0 + 56.0 * exp(-12.0 * dt)
                    } else 0.0
                    val impactWave = if (t >= 0.98) {
                        sin(2.0 * PI * impactFreq * (t - 0.98)) * impactEnv
                    } else 0.0

                    // --- LAYER 4: Bright Crystal Tech Chime Chord (t: 1.0s -> 2.45s) ---
                    val chimeEnv = if (t >= 1.0) {
                        val dt = t - 1.0
                        exp(-2.1 * dt) * (1.0 - exp(-50.0 * dt))
                    } else 0.0
                    val chimeWave = if (t >= 1.0) {
                        val dt = t - 1.0
                        (
                            sin(2.0 * PI * 523.25 * dt) * 0.35 +  // C5
                            sin(2.0 * PI * 783.99 * dt) * 0.30 +  // G5
                            sin(2.0 * PI * 1046.50 * dt) * 0.22 + // C6
                            sin(2.0 * PI * 1318.51 * dt) * 0.13   // E6
                        ) * chimeEnv
                    } else 0.0

                    // --- MASTER SUMMING & WARM HEADROOM SATURATION ---
                    val rawMix = (padWave * 0.24 + riseWave * 0.28 + impactWave * 0.46 + chimeWave * 0.36)
                    val mastered = tanh(rawMix * 1.3) * 0.85
                    val sampleVal = (mastered * 32767.0).toInt().coerceIn(-32768, 32767).toShort()

                    audioBuffer[2 * i] = (sampleVal.toInt() and 0x00FF).toByte()
                    audioBuffer[2 * i + 1] = ((sampleVal.toInt() and 0xFF00) shr 8).toByte()
                }

                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(audioBuffer.size)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                currentAudioTrack = track
                track.write(audioBuffer, 0, audioBuffer.size)
                track.play()

                // Sleep safely during playback, checking for cancellation
                val sleepSteps = durationMs / 100
                for (s in 0 until sleepSteps) {
                    if (!isPlaying.get()) break
                    Thread.sleep(100)
                }
            } catch (e: Exception) {
                // Silently handle audio hardware limitations gracefully
            } finally {
                try {
                    track?.stop()
                    track?.release()
                } catch (ignored: Exception) {}
                currentAudioTrack = null
                isPlaying.set(false)
            }
        }.apply {
            name = "CampusSonicLogoThread"
            isDaemon = true
            start()
        }
    }

    /**
     * Immediately stops audio playback and releases resources upon screen exit.
     */
    fun stopAndRelease() {
        isPlaying.set(false)
        try {
            currentAudioTrack?.stop()
            currentAudioTrack?.release()
        } catch (ignored: Exception) {}
        currentAudioTrack = null
    }
}

/**
 * Ultra-Modern Premium Dark Splash & Loading Screen.
 *
 * Design Architecture:
 * 1. Deep obsidian backdrop (#030712) with subtle ethereal ambient radial glow orbs.
 * 2. Rotating concentric sacred geometry orbital rings behind the emblem.
 * 3. Elevated gold/amber sacred geometry Tesseract emblem with smooth scale/fade entry.
 * 4. Crisp, unobstructed typography for "CAMPUS RIDE" (no lines cutting across text).
 * 5. Modern glassmorphism status indicator with pulsing live mobility beacon.
 * 6. Sleek animated cyan-blue indeterminate loading progress bar.
 * 7. Clean, authoritative Tesseract Dynamics footer.
 */
@Composable
fun SplashScreen(
    repository: CampusRideRepository? = null,
    onNavigateNext: () -> Unit
) {
    val startupPhase by (repository?.startupPhase?.collectAsState()
        ?: remember { androidx.compose.runtime.mutableStateOf(StartupPhase.READY) })

    // Entry animation controllers
    val logoScale = remember { Animatable(0.75f) }
    val logoAlpha = remember { Animatable(0f) }
    val ambientGlowAlpha = remember { Animatable(0f) }
    val textAlpha = remember { Animatable(0f) }
    val letterSpacingAnim = remember { Animatable(3f) }
    val contentAlpha = remember { Animatable(0f) }
    val sonicPulseHalo = remember { Animatable(0f) }

    // Ambient background infinite loops
    val infiniteTransition = rememberInfiniteTransition(label = "splash_infinite")
    val slowRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(28000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orbital_rings_rotation"
    )
    val beaconPulse by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1f)),
            repeatMode = RepeatMode.Reverse
        ),
        label = "beacon_pulse"
    )
    val progressShimmer by infiniteTransition.animateFloat(
        initialValue = -0.3f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = CubicBezierEasing(0.2f, 0.0f, 0.2f, 1f)),
            repeatMode = RepeatMode.Restart
        ),
        label = "progress_shimmer"
    )

    // Ensure audio stops if splash screen is exited early
    DisposableEffect(Unit) {
        onDispose {
            CampusSonicLogoPlayer.stopAndRelease()
        }
    }

    LaunchedEffect(Unit) {
        // Play synchronized brand sonic logo
        launch(Dispatchers.IO) {
            CampusSonicLogoPlayer.playSonicLogo()
        }

        // Perform deterministic startup in background
        if (repository != null) {
            launch(Dispatchers.IO) {
                repository.performDeterministicStartup()
            }
        }

        // 1. Initial atmospheric fade-in (0.0s - 0.7s)
        launch {
            ambientGlowAlpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(700, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f))
            )
        }

        // 2. Emblem entrance with smooth scale & alpha (0.15s - 0.95s)
        launch {
            delay(150)
            logoAlpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(750, easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f))
            )
        }
        launch {
            delay(150)
            logoScale.animateTo(
                targetValue = 1.0f,
                animationSpec = tween(900, easing = CubicBezierEasing(0.1f, 0.9f, 0.2f, 1f))
            )
        }

        // 3. Sonic impact pulse ring (hits at t ≈ 0.98s)
        delay(950)
        launch {
            sonicPulseHalo.animateTo(
                targetValue = 1f,
                animationSpec = tween(650, easing = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1f))
            )
        }

        // 4. "CAMPUS RIDE" text entrance & letter-spacing expansion (0.95s - 1.6s)
        launch {
            textAlpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(600, easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f))
            )
        }
        launch {
            letterSpacingAnim.animateTo(
                targetValue = 7.5f,
                animationSpec = tween(1200, easing = CubicBezierEasing(0.1f, 0.8f, 0.2f, 1f))
            )
        }

        // 5. Subtitle & loading bar reveal (1.3s - 1.9s)
        delay(350)
        contentAlpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(550, easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f))
        )

        // 6. Hold until deterministic startup finishes and animations settle
        delay(900)
        val startTime = System.currentTimeMillis()
        while (repository != null && repository.startupPhase.value != StartupPhase.READY) {
            if (System.currentTimeMillis() - startTime > 3200L) {
                break
            }
            delay(50L)
        }

        CampusSonicLogoPlayer.stopAndRelease()
        onNavigateNext()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF020617), // Deep Obsidian Slate
                        Color(0xFF060B1E), // Dark Midnight Indigo
                        Color(0xFF02040A)  // Space Black
                    )
                )
            )
            .testTag("splash_screen_container"),
        contentAlignment = Alignment.Center
    ) {
        // --- LAYER 1: Ambient Ethereal Glow & Concentric Sacred Geometry Rings ---
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .alpha(ambientGlowAlpha.value)
        ) {
            val canvasW = size.width
            val canvasH = size.height
            val center = Offset(canvasW / 2f, canvasH / 2f)
            val emblemCenter = center.copy(y = center.y - 70.dp.toPx())

            // Ethereal sapphire ambient radial aura behind emblem
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0x350284C7), // Vibrant Sky Glow
                        Color(0x1838BDF8),
                        Color(0x0A0F172A),
                        Color.Transparent
                    ),
                    center = emblemCenter,
                    radius = 280.dp.toPx()
                ),
                radius = 280.dp.toPx(),
                center = emblemCenter
            )

            // Warm subtle gold core behind emblem
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0x2BD4AF37),
                        Color(0x0EAA8C2C),
                        Color.Transparent
                    ),
                    center = emblemCenter,
                    radius = 90.dp.toPx()
                ),
                radius = 90.dp.toPx(),
                center = emblemCenter
            )

            // Sonic impact radiant ring expansion
            if (sonicPulseHalo.value > 0f) {
                val haloRadius = 65.dp.toPx() + (130.dp.toPx() * sonicPulseHalo.value)
                val haloAlpha = (1f - sonicPulseHalo.value) * 0.40f
                drawCircle(
                    color = Color(0xFF38BDF8).copy(alpha = haloAlpha),
                    radius = haloRadius,
                    center = emblemCenter,
                    style = Stroke(width = 2.5.dp.toPx() * (1f - sonicPulseHalo.value))
                )
            }

            // Orbital Concentric Thin Geometry Rings (Non-intersecting, perfectly framing the emblem)
            val outerOrbitRadius = 88.dp.toPx()
            drawCircle(
                color = Color(0xFF38BDF8).copy(alpha = 0.15f),
                radius = outerOrbitRadius,
                center = emblemCenter,
                style = Stroke(
                    width = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 18f), slowRotation * 0.6f)
                )
            )

            val innerOrbitRadius = 68.dp.toPx()
            drawCircle(
                color = Color(0xFFD4AF37).copy(alpha = 0.18f),
                radius = innerOrbitRadius,
                center = emblemCenter,
                style = Stroke(
                    width = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 14f), -slowRotation * 0.8f)
                )
            )
        }

        // --- LAYER 2: Central Hero Brand & Typography Content ---
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 28.dp)
        ) {
            // Tesseract Dynamics Sacred Geometry Emblem with Specular Gold Rim
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .scale(logoScale.value)
                    .alpha(logoAlpha.value),
                contentAlignment = Alignment.Center
            ) {
                // Outer gold specular ring
                Box(
                    modifier = Modifier
                        .size(92.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.sweepGradient(
                                listOf(
                                    Color(0xFFE5C158),
                                    Color(0xFFD4AF37),
                                    Color(0xFF8A6C1B),
                                    Color(0xFFFDE68A),
                                    Color(0xFFD4AF37),
                                    Color(0xFFE5C158)
                                )
                            )
                        )
                        .padding(2.5.dp)
                ) {
                    // Deep dark glass core container
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(Color(0xFF060B18)),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = com.example.R.drawable.img_tesseract_icon),
                            contentDescription = "Tesseract Dynamics Emblem",
                            modifier = Modifier
                                .size(84.dp)
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(26.dp))

            // Main Brand Typography: "CAMPUS RIDE" (Completely clean - no intersecting lines)
            Text(
                text = "CAMPUS RIDE",
                fontSize = 25.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.SansSerif,
                letterSpacing = letterSpacingAnim.value.sp,
                color = Color(0xFFF8FAFC),
                modifier = Modifier
                    .alpha(textAlpha.value)
                    .scale(logoScale.value)
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Subtitle Tagline Pill: IIIT BHAGALPUR • SMART MOBILITY
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF0F172A).copy(alpha = 0.75f),
                border = androidx.compose.foundation.BorderStroke(
                    width = 1.dp,
                    brush = Brush.horizontalGradient(
                        listOf(
                            Color(0xFF1E293B),
                            Color(0xFF0284C7).copy(alpha = 0.5f),
                            Color(0xFF1E293B)
                        )
                    )
                ),
                modifier = Modifier.alpha(contentAlpha.value)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    // Pulsing emerald live telemetry beacon
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF10B981).copy(alpha = beaconPulse))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "IIIT BHAGALPUR • SMART MOBILITY",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.SansSerif,
                        letterSpacing = 1.4.sp,
                        color = Color(0xFFE2E8F0)
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Sleek Minimalist Loading / Initialization Progress Bar
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .alpha(contentAlpha.value)
                    .width(160.dp)
            ) {
                // 2dp Futuristic Shimmering Progress Capsule
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.5.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0xFF1E293B))
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val trackW = size.width
                        val shimmerW = trackW * 0.45f
                        val startX = (progressShimmer * (trackW + shimmerW)) - shimmerW

                        drawRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color(0xFF0284C7),
                                    Color(0xFF38BDF8),
                                    Color(0xFFF0F9FF),
                                    Color(0xFF38BDF8),
                                    Color.Transparent
                                ),
                                startX = startX,
                                endX = startX + shimmerW
                            ),
                            topLeft = Offset(0f, 0f),
                            size = size
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Dynamic System Initialization Phase Text
                val statusText = when (startupPhase) {
                    StartupPhase.STARTING -> "STARTING TELEMETRY..."
                    StartupPhase.AUTHENTICATING -> "AUTHENTICATING SECURE SESSION..."
                    StartupPhase.LOADING_DRIVER_STATE -> "SYNCHRONIZING FLEET DISPATCH..."
                    StartupPhase.READY -> "READY"
                }

                Text(
                    text = statusText,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.2.sp,
                    color = Color(0xFF64748B)
                )
            }
        }

        // --- LAYER 3: Minimalist Authoritative Product Footer ---
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 28.dp)
                .alpha(contentAlpha.value),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF070D1D).copy(alpha = 0.6f),
                border = androidx.compose.foundation.BorderStroke(0.8.dp, Color(0xFF1E293B).copy(alpha = 0.6f))
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "A PRODUCT BY TESSERACT DYNAMICS",
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif,
                        letterSpacing = 1.5.sp,
                        color = Color(0xFF94A3B8)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "IIIT BHAGALPUR AUTONOMOUS FLEET",
                fontSize = 8.5.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.2.sp,
                color = Color(0xFF475569)
            )
        }
    }
}
