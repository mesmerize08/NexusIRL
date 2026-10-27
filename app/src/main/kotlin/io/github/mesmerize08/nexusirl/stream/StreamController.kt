package io.github.mesmerize08.nexusirl.stream

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Surface
import io.github.mesmerize08.nexusirl.chat.ChatController
import io.github.mesmerize08.nexusirl.core.secrets.KeystoreSecretStore
import io.github.mesmerize08.nexusirl.core.secrets.MissingSecretException
import io.github.mesmerize08.nexusirl.effects.TextVariables
import io.github.mesmerize08.nexusirl.location.LocationController
import io.github.mesmerize08.nexusirl.media.pipeline.CameraFactors
import io.github.mesmerize08.nexusirl.media.pipeline.CameraInfo
import io.github.mesmerize08.nexusirl.media.pipeline.CameraSource
import io.github.mesmerize08.nexusirl.media.pipeline.EffectSettings
import io.github.mesmerize08.nexusirl.media.pipeline.EncoderCapabilities
import io.github.mesmerize08.nexusirl.media.pipeline.Mp4Recorder
import io.github.mesmerize08.nexusirl.media.pipeline.SceneSwitchTransitionKind
import io.github.mesmerize08.nexusirl.media.pipeline.VideoEffect
import io.github.mesmerize08.nexusirl.media.pipeline.VideoEncoder
import io.github.mesmerize08.nexusirl.media.pipeline.VideoEncoderSettings
import io.github.mesmerize08.nexusirl.media.pipeline.VideoPipeline
import io.github.mesmerize08.nexusirl.moblink.MoblinkController
import io.github.mesmerize08.nexusirl.moblink.MoblinkStreamerController
import io.github.mesmerize08.nexusirl.scene.BrowserWidgets
import io.github.mesmerize08.nexusirl.scene.LutLoader
import io.github.mesmerize08.nexusirl.scene.MapWidgets
import io.github.mesmerize08.nexusirl.scene.SceneEngine
import io.github.mesmerize08.nexusirl.settings.QuickButtonType
import io.github.mesmerize08.nexusirl.settings.SceneCamera
import io.github.mesmerize08.nexusirl.settings.SceneSwitchTransition
import io.github.mesmerize08.nexusirl.settings.SettingsArchive
import io.github.mesmerize08.nexusirl.settings.SettingsBitratePreset
import io.github.mesmerize08.nexusirl.settings.SettingsColor
import io.github.mesmerize08.nexusirl.settings.SettingsColorLut
import io.github.mesmerize08.nexusirl.settings.SettingsScene
import io.github.mesmerize08.nexusirl.settings.SettingsStore
import io.github.mesmerize08.nexusirl.settings.StreamCodec
import io.github.mesmerize08.nexusirl.settings.StreamUrlSecrets
import io.github.mesmerize08.nexusirl.settings.VideoStabilizationMode
import io.github.mesmerize08.nexusirl.settings.WidgetType
import io.github.mesmerize08.nexusirl.settings.addMissingQuickButtons
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** What the camera controls show (upstream: the zoom, torch and camera picker state). */
data class CameraUiState(
    val cameras: List<CameraInfo> = emptyList(),
    val current: CameraInfo? = null,
    val zoom: Float = 1f,
    val torch: Boolean = false,
    /** Exposure bias in EV. */
    val exposureBias: Float = 0f,
    /** Manual values as upstream's 0..1 slider factors; null means automatic. */
    val manualFocus: Float? = null,
    val manualExposure: Float? = null,
    val manualIso: Float? = null,
    val manualWhiteBalance: Float? = null,
    val stabilization: Boolean = false,
) {
    /** Resets the per-camera manual controls (upstream re-applies auto after a camera change). */
    fun withAutoControls(): CameraUiState =
        copy(exposureBias = 0f, manualFocus = null, manualExposure = null, manualIso = null, manualWhiteBalance = null)
}

/** A recording in progress (upstream `currentRecording`). */
data class RecordingState(val fileName: String, val startedAtElapsedMillis: Long)

/**
 * Process-wide entry point the UI talks to (upstream: the Model's stream, recording and camera
 * controls).
 *
 * Owns the shared capture: the camera pipeline runs while the app is visible (preview), live or
 * recording, like upstream where the camera is always attached; the microphone runs while live or
 * recording. Going live and recording each attach their own video encoder to the pipeline and
 * share the audio. The foreground service keeps all of it alive in the background.
 *
 * Call from the main thread.
 */
object StreamController {
    private const val TAG = "StreamController"
    private const val CAMERA_ERROR_PREFIX = "Camera: "
    private val mutableStatus = MutableStateFlow(StreamStatus())
    val status: StateFlow<StreamStatus> = mutableStatus

    @Volatile
    @SuppressLint("StaticFieldLeak") // Holds only the application context.
    internal var session: StreamSession? = null
    private var previewSurface: Surface? = null

    @Volatile
    private var settingsStore: SettingsStore? = null

    /** The app's settings (created on first use). */
    fun settings(context: Context): SettingsStore = settingsStore ?: synchronized(this) {
        settingsStore ?: SettingsStore(
            File(context.applicationContext.filesDir, SettingsArchive.SETTINGS_JSON),
            KeystoreSecretStore.create(context.applicationContext),
        ).also { settingsStore = it }
    }

    private var sharedAudio: SharedAudio? = null

    // Scenes (upstream ModelScene.swift)

    private var sceneEngine: SceneEngine? = null

    @SuppressLint("StaticFieldLeak") // Holds only the application context.
    private var browserWidgets: BrowserWidgets? = null

    private fun browsers(context: Context): BrowserWidgets = browserWidgets ?: BrowserWidgets(context).also { widgets ->
        browserWidgets = widgets
        ChatController.addPostListener(widgets::sendChat)
    }

    @SuppressLint("StaticFieldLeak") // Holds only the application context.
    private var mapWidgets: MapWidgets? = null

    private fun maps(context: Context): MapWidgets = mapWidgets ?: MapWidgets(context).also { maps ->
        mapWidgets = maps
        scenes(context).setMapBitmaps(maps::bitmap)
    }
    private val mutableSelectedSceneId = MutableStateFlow<UUID?>(null)

    // Quick buttons (upstream QuickButtonsView actions and ModelQuickButtons.swift)

    /** Global effects each quick button turns on (upstream order is applied by the pipeline). */
    private val effectButtons = mapOf(
        QuickButtonType.GRAY_SCALE to VideoEffect.GRAY_SCALE,
        QuickButtonType.SEPIA to VideoEffect.SEPIA,
        QuickButtonType.PIXELLATE to VideoEffect.PIXELLATE,
        QuickButtonType.FOUR_THREE to VideoEffect.FOUR_THREE,
        QuickButtonType.CRT to VideoEffect.CRT,
        QuickButtonType.WHIRLPOOL to VideoEffect.WHIRLPOOL,
        QuickButtonType.PINCH to VideoEffect.PINCH,
    )

    /**
     * Upstream `addMissingQuickButtons` and its start-up reset, once per process: every button
     * exists and all start off.
     */
    fun ensureQuickButtons(context: Context) {
        if (quickButtonsLoaded) {
            return
        }
        quickButtonsLoaded = true
        val store = settings(context)
        val buttons = addMissingQuickButtons(store.current.quickButtons)
        if (buttons != store.current.quickButtons) {
            store.update { it.copy(quickButtons = buttons) }
        }
    }

    private var quickButtonsLoaded = false

    /** Upstream: the default bitrate presets when there are none (fresh install, or an old import). */
    fun ensureBitratePresets(context: Context) {
        val store = settings(context)
        if (store.current.bitratePresets.isEmpty()) {
            store.update { it.copy(bitratePresets = SettingsBitratePreset.defaults()) }
        }
    }

    /**
     * Upstream `setBitrate`: saves [bitrate] as the selected stream's bitrate and applies it now if
     * live (as the adaptive bitrate's ceiling, or directly).
     */
    fun setBitrate(context: Context, bitrate: Long) {
        settings(context).update { database ->
            val stream = database.selectedStream ?: return@update database
            database.updateStream(stream.copy(bitrate = bitrate))
        }
        session?.setBitrate(bitrate)
    }

    fun isQuickButtonOn(context: Context, type: QuickButtonType): Boolean =
        settings(context).current.quickButtons.firstOrNull { it.type == type }?.isOn == true

    /** Upstream `setGlobalButtonState`: saves the button state and applies what it controls. */
    fun setQuickButton(context: Context, type: QuickButtonType, on: Boolean) {
        settings(context).update { database ->
            database.copy(quickButtons = database.quickButtons.map { if (it.type == type) it.copy(isOn = on) else it })
        }
        when (type) {
            QuickButtonType.TORCH -> setTorch(on)
            // Upstream `setDisplayPortrait`: the UI turns portrait, the stream keeps its shape.
            QuickButtonType.PORTRAIT -> settings(context).update { it.copy(portrait = on) }
            else -> applyQuickButtons(context)
        }
    }

    /**
     * After settings were replaced by an import: the defaults a fresh install has, and the camera,
     * microphone and effects following the (now all off) quick buttons and the new scenes.
     */
    fun settingsImported(context: Context) {
        ensureDefaultScenes(context)
        ensureBitratePresets(context)
        ensureBundledLuts(context)
        applyQuickButtons(context)
        setTorch(false)
        refreshScene(context)
        refreshLuts(context)
    }

    /** Applies effect and mute buttons (also after imports and when capture starts). */
    fun applyQuickButtons(context: Context) {
        applyEffects(context)
        sharedAudio?.muted = isQuickButtonOn(context, QuickButtonType.MUTE)
    }

    private fun applyEffects(context: Context) {
        val database = settings(context).current
        pipeline?.sceneSwitchTransition = when (database.sceneSwitchTransition) {
            SceneSwitchTransition.BLUR -> SceneSwitchTransitionKind.BLUR
            SceneSwitchTransition.FREEZE -> SceneSwitchTransitionKind.FREEZE
            SceneSwitchTransition.BLUR_AND_ZOOM -> SceneSwitchTransitionKind.BLUR_AND_ZOOM
        }
        pipeline?.effectSettings = EffectSettings(database.pixellateStrength, database.whirlpoolAngle, database.pinchScale)
        pipeline?.effects = database.quickButtons.filter { it.isOn }.mapNotNull { effectButtons[it.type] }.toSet()
    }

    /** The scene being shown; runtime state like upstream's `sceneSelector`. */
    val selectedSceneId: StateFlow<UUID?> = mutableSelectedSceneId

    private fun scenes(context: Context): SceneEngine = sceneEngine ?: SceneEngine(context).also { engine ->
        sceneEngine = engine
        engine.setVariables(::textVariables)
        engine.setChatPosts { ChatController.posts.value }
        ChatController.addPostListener { engine.invalidate() }
    }

    /**
     * Upstream `createDefault()` scenes: "Back" and "Front". Added when the settings have no scenes
     * (a fresh install, or an import without them).
     */
    fun ensureDefaultScenes(context: Context) {
        val store = settings(context)
        if (store.current.scenes.isEmpty()) {
            store.update {
                it.copy(
                    scenes = listOf(
                        SettingsScene(name = "Back", cameraPosition = "Back"),
                        SettingsScene(name = "Front", cameraPosition = "Front"),
                    ),
                )
            }
        }
    }

    /** Upstream `addMissingBundledLuts`: one entry per bundled LUT, keeping existing ones by name. */
    fun ensureBundledLuts(context: Context) {
        val store = settings(context)
        val existing = store.current.color.bundledLuts
        val wanted = SettingsColor.BUNDLED_LUT_NAMES.map { name ->
            existing.firstOrNull {
                it.name == name
            } ?: SettingsColorLut(type = "bundled", name = name)
        }
        if (wanted != existing) {
            store.update { it.copy(color = it.color.copy(bundledLuts = wanted)) }
        }
    }

    private val lutExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** Loads the enabled LUTs (off the main thread) and applies them to the camera image. */
    fun refreshLuts(context: Context) {
        val video = pipeline ?: return
        val enabled = settings(context).current.color.let { it.allLuts + it.diskLuts }.filter { it.enabled }
        val appContext = context.applicationContext
        lutExecutor.execute {
            video.setLuts(enabled.mapNotNull { LutLoader.load(appContext, it) })
        }
    }

    /** Selects a scene (upstream `selectScene`): its widgets are shown and its camera is used. */
    fun selectScene(context: Context, id: UUID) {
        mutableSelectedSceneId.value = id
        val scene = refreshScene(context) ?: return
        val cameras = mutableCamera.value.cameras
        val wanted = when (scene.camera) {
            SceneCamera.FRONT -> cameras.firstOrNull { it.isFront }
            SceneCamera.BACK -> cameras.firstOrNull { !it.isFront }
            SceneCamera.NONE -> null
        }
        val force = settings(context).current.forceSceneSwitchTransition
        if (wanted != null && (force || wanted.id != mutableCamera.value.current?.id)) {
            switchCamera(wanted.id, force)
        }
    }

    /** Pushes the selected scene (or the first enabled one) and its widgets to the compositor. */
    fun refreshScene(context: Context): SettingsScene? {
        val database = settings(context).current
        val enabled = database.scenes.filter { it.enabled }
        val scene = enabled.firstOrNull { it.id == mutableSelectedSceneId.value } ?: enabled.firstOrNull()
        mutableSelectedSceneId.value = scene?.id
        scenes(context).setScene(scene, database.widgets)
        val browserWidgetIds = scene?.widgets.orEmpty().map { it.widgetId }.toSet()
        browsers(
            context,
        ).update(pipeline, database.widgets.filter { it.enabled && it.type == WidgetType.BROWSER && it.id in browserWidgetIds })
        // Map images are square: upstream uses the layout size % of the canvas's shorter side.
        val canvas = pipeline?.settings
        val maps = scene?.widgets.orEmpty().mapNotNull { sceneWidget ->
            val widget = database.widgets.firstOrNull { it.id == sceneWidget.widgetId && it.enabled && it.type == WidgetType.MAP }
            if (widget == null || canvas == null) null else widget to (sceneWidget.size / 100 * minOf(canvas.width, canvas.height)).toInt()
        }
        if (maps.isNotEmpty() || mapWidgets != null) {
            val engine = scenes(context)
            maps(context).update(maps) { engine.invalidate() }
        }
        return scene
    }

    /** Values for text widgets, from the live status (upstream `Variables`). */
    private fun textVariables(): TextVariables {
        val status = mutableStatus.value
        val canvas = pipeline?.settings
        val location = LocationController.values()
        return TextVariables(
            date = Date(),
            nowNanos = System.nanoTime(),
            bitrateMbps = if (status.live) "%.1f".format(Locale.US, status.videoBitrateKbps / 1000.0) else "",
            resolution = canvas?.let { "${it.width}x${it.height}" },
            fps = pipeline?.outputFps,
            muted = sharedAudio?.muted == true,
            speed = location?.speed,
            averageSpeed = location?.averageSpeed,
            altitude = location?.altitude,
            distance = location?.totals?.distance,
            splitDistance = location?.totals?.splitDistance,
            altitudeAscent = location?.totals?.altitudeAscent,
            altitudeDescent = location?.totals?.altitudeDescent,
            splitAltitudeAscent = location?.totals?.splitAltitudeAscent,
            splitAltitudeDescent = location?.totals?.splitAltitudeDescent,
        )
    }

    /** Audio channels being captured, for the remote control status. */
    val audioChannels: Int
        get() = sharedAudio?.channels ?: 1

    private fun audio(context: Context): SharedAudio = sharedAudio ?: SharedAudio(context) {
        settings(context).current.audio.preferStereoMic
    }.also {
        sharedAudio = it
        it.muted = isQuickButtonOn(context, QuickButtonType.MUTE)
    }

    // Streaming

    /** Goes live with the selected stream. Returns an error message, or null when starting. */
    fun goLive(context: Context): String? {
        val store = settings(context)
        val stream = store.current.selectedStream ?: return "Add a stream first"
        val url = try {
            store.resolveUrl(stream)
        } catch (e: MissingSecretException) {
            return "The stream key is missing. Edit the stream and enter it again."
        }
        if (!StreamUrlSecrets.isValid(url)) {
            return "Edit the stream and enter an srt://, srtla://, rtmp:// or rtmps:// URL"
        }
        return goLive(context, StreamConfig.from(stream, url))
    }

    /** Goes live with [requested]. Returns an error message, or null when starting. */
    fun goLive(context: Context, requested: StreamConfig): String? {
        if (session != null) {
            return null
        }
        // No HEVC encoder (common on low-end SoCs): fall back to H.264 (docs/PORT_PLAN.md §6).
        val config = if (requested.video.hevc && !EncoderCapabilities.supportsHevc()) {
            requested.copy(video = requested.video.copy(hevc = false))
        } else {
            requested
        }
        if (activeRecording != null && !pipelineMatches(config.video)) {
            return "Stop recording first: the stream uses a different resolution or frame rate"
        }
        val video = ensurePipeline(context, config.video)
        val newSession = StreamSession(context.applicationContext, config, video, audio(context)) { mutableStatus.value = it }
        session = newSession
        newSession.start()
        LocationController.onGoLive()
        // Upstream starts looking up the YouTube live video when going live.
        ChatController.fetchYouTubeVideoId()
        updateService(context)
        return null
    }

    fun stop(context: Context) {
        // Not live before the session's last status arrives: listeners of [status] read [isLive].
        val stopping = session
        session = null
        stopping?.stop()
        MoblinkStreamerController.stopTunnels()
        afterCaptureEnded(context)
    }

    // Recording (upstream ModelRecording.swift)

    private class ActiveRecording(
        val uri: Uri,
        val descriptor: ParcelFileDescriptor,
        val recorder: Mp4Recorder,
        val encoder: VideoEncoder,
    )

    private var activeRecording: ActiveRecording? = null
    private val mutableRecording = MutableStateFlow<RecordingState?>(null)
    val recording: StateFlow<RecordingState?> = mutableRecording

    /**
     * Starts recording the camera and microphone to an MP4 in `Movies/NexusIRL` (shared storage,
     * so it shows in the gallery and Files). Uses the running canvas; returns an error message, or
     * null when started.
     */
    fun startRecording(context: Context): String? {
        if (activeRecording != null) {
            return null
        }
        val stream = settings(context).current.selectedStream
        val canvas = pipeline?.settings ?: previewSettings(context)
        val video = ensurePipeline(context, canvas)
        val recording = stream?.recording
        val hevc = (recording?.videoCodec ?: StreamCodec.H265_HEVC) == StreamCodec.H265_HEVC && EncoderCapabilities.supportsHevc()
        val encoderSettings = canvas.copy(
            bitrate = recording?.videoBitrate?.takeIf { it > 0 } ?: autoRecordingBitrate(canvas, hevc),
            hevc = hevc,
            keyFrameIntervalSeconds = recording?.maxKeyFrameInterval?.takeIf { it > 0 } ?: 2,
            // Like upstream's AVAssetWriter recordings: better quality for the same file size.
            bFrames = true,
        )
        val name = "NexusIRL_${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())}.mp4"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/NexusIRL")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return "Failed to start recording"
        val descriptor = try {
            resolver.openFileDescriptor(uri, "w")
        } catch (e: Exception) {
            null
        }
        if (descriptor == null) {
            resolver.delete(uri, null, null)
            return "Failed to start recording"
        }
        val recorder = Mp4Recorder(descriptor.fileDescriptor, canvas.width, canvas.height, reorderedVideo = encoderSettings.bFrames)
        val encoder = video.startEncoder(encoderSettings, recorder)
        if (encoder == null) {
            recorder.stop()
            descriptor.close()
            resolver.delete(uri, null, null)
            return "Failed to start recording"
        }
        audio(context).add(recorder)
        activeRecording = ActiveRecording(uri, descriptor, recorder, encoder)
        mutableRecording.value = RecordingState(name, SystemClock.elapsedRealtime())
        updateService(context)
        return null
    }

    /** Stops recording and publishes the file. Returns false when nothing playable was recorded. */
    fun stopRecording(context: Context): Boolean {
        val active = activeRecording ?: return false
        activeRecording = null
        mutableRecording.value = null
        // Encoder first (its release waits for the last frame), then audio, then finalise the file.
        pipeline?.stopEncoder(active.encoder)
        sharedAudio?.remove(active.recorder)
        val playable = active.recorder.stop()
        active.descriptor.close()
        val resolver = context.contentResolver
        if (playable) {
            resolver.update(active.uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        } else {
            resolver.delete(active.uri, null, null)
        }
        afterCaptureEnded(context)
        return playable
    }

    /**
     * Upstream lets AVAssetWriter pick the bitrate when it is 0 ("Auto"). MediaCodec needs a
     * number: about 0.15 bits per pixel for H.264 (1080p30 is about 9 Mbit/s), 40% less for H.265.
     */
    fun autoRecordingBitrate(canvas: VideoEncoderSettings, hevc: Boolean): Long {
        val bitsPerPixel = if (hevc) 0.09 else 0.15
        return (canvas.width.toLong() * canvas.height * canvas.fps * bitsPerPixel).toLong().coerceIn(1_000_000, 100_000_000)
    }

    // Snapshots (upstream ModelSnapshot.swift)

    /** The current composed frame at canvas size, or null (remote control previews). Any thread callback. */
    fun currentFrame(maxSide: Int = 0, onDone: (Bitmap?) -> Unit) {
        val video = pipeline ?: return onDone(null)
        video.takeSnapshot(maxSide, onDone)
    }

    /**
     * Saves the current frame as a JPEG (quality 0.9, as upstream) to `Pictures/NexusIRL`.
     * [onDone] runs on the main thread with true when saved.
     */
    fun takeSnapshot(context: Context, onDone: (Boolean) -> Unit) {
        val video = pipeline
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        if (video == null) {
            onDone(false)
            return
        }
        val resolver = context.applicationContext.contentResolver
        video.takeSnapshot { bitmap ->
            val saved = bitmap != null && try {
                val name = "NexusIRL_${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())}.jpg"
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/NexusIRL")
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                val output = uri?.let { resolver.openOutputStream(it) }
                output?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) } == true
            } catch (e: Exception) {
                false
            } finally {
                bitmap?.recycle()
            }
            main.post { onDone(saved) }
        }
    }

    // Capture lifetime

    private fun afterCaptureEnded(context: Context) {
        updateService(context)
        if (session != null || activeRecording != null) {
            return
        }
        if (uiVisible) {
            // The stream may have used a different canvas than the selected stream's preview.
            refreshPreview(context)
        } else {
            releasePipeline()
        }
    }

    /** The foreground service runs while live or recording. */
    private fun updateService(context: Context) = updateForegroundService(context)

    /** Whether the camera and microphone are in use for a stream or recording. */
    val isCapturing: Boolean
        get() = session != null || activeRecording != null

    /**
     * Upstream `isLive`: from going live until stopping, including while connecting or
     * reconnecting, and already before the session reports its first state. Any thread.
     */
    val isLive: Boolean
        get() = session != null

    /**
     * Service starts that have not reached [StreamingService.onStartCommand] yet. Android ends the
     * app if a service started with startForegroundService is stopped before it calls
     * startForeground, so it is not stopped then; it stops itself when nothing is capturing.
     * Main thread.
     */
    internal var pendingServiceStarts = 0

    /**
     * Runs the foreground service while capturing or relaying (Moblink), restarting it so its
     * declared types match what is in use.
     */
    fun updateForegroundService(context: Context) {
        val intent = Intent(context, StreamingService::class.java)
        if (isCapturing || MoblinkController.isRunning) {
            context.startForegroundService(intent)
            pendingServiceStarts += 1
        } else if (pendingServiceStarts == 0) {
            context.stopService(intent)
        }
    }

    // Camera pipeline and controls

    @Volatile
    private var displayRotationDegrees = 0

    @SuppressLint("StaticFieldLeak") // Holds only the application context.
    private var pipeline: VideoPipeline? = null
    private var uiVisible = false
    private var cameraId: String? = null
    private val mutableCamera = MutableStateFlow(CameraUiState())
    val camera: StateFlow<CameraUiState> = mutableCamera

    /**
     * Display rotation (0/90/180/270) so camera content stays upright as the phone is held. With a
     * landscape stream and a portrait UI (the Portrait quick button) the picture stays upright too
     * and the landscape stream shows a crop of it, like upstream (`isLandscapeStreamAndPortraitUi`
     * sets the capture orientation to portrait).
     */
    fun setDisplayRotation(degrees: Int) {
        displayRotationDegrees = degrees
        pipeline?.displayRotationDegrees = degrees
    }

    fun setPreviewSurface(surface: Surface?) {
        previewSurface = surface
        pipeline?.setPreviewSurface(surface)
    }

    /** The app came to the foreground (true) or left it (false): start or stop the preview camera. */
    fun setUiVisible(context: Context, visible: Boolean) {
        android.util.Log.d(TAG, "UI visible $visible, live ${session != null}, recording ${activeRecording != null}")
        uiVisible = visible
        if (visible) {
            refreshPreview(context)
        } else if (session == null && activeRecording == null) {
            releasePipeline()
        }
    }

    /** Restarts the preview when the selected stream's canvas changed (not while live or recording). */
    fun refreshPreview(context: Context) {
        if (session != null || activeRecording != null || !uiVisible) {
            return
        }
        ensurePipeline(context, previewSettings(context))
    }

    private fun previewSettings(context: Context): VideoEncoderSettings {
        val stream = settings(context).current.selectedStream
            ?: return VideoEncoderSettings(width = 1280, height = 720, fps = 30, bitrate = 0)
        return VideoEncoderSettings(width = stream.canvasWidth, height = stream.canvasHeight, fps = stream.fps, bitrate = 0)
    }

    private fun pipelineMatches(canvas: VideoEncoderSettings): Boolean {
        val s = pipeline?.settings ?: return false
        return s.width == canvas.width && s.height == canvas.height && s.fps == canvas.fps
    }

    private fun ensurePipeline(context: Context, canvas: VideoEncoderSettings): VideoPipeline {
        pipeline?.let { current ->
            if (pipelineMatches(canvas)) {
                return current
            }
            releasePipeline()
        }
        android.util.Log.d(TAG, "Starting camera pipeline ${canvas.width}x${canvas.height}@${canvas.fps}")
        val created = VideoPipeline(
            context.applicationContext,
            canvas,
            onError = { message -> mutableStatus.value = mutableStatus.value.copy(detail = "$CAMERA_ERROR_PREFIX$message") },
            onCameraRunning = {
                // A camera works again: an old camera error no longer applies.
                mutableStatus.update { if (it.detail.startsWith(CAMERA_ERROR_PREFIX)) it.copy(detail = "") else it }
            },
        )
        created.displayRotationDegrees = displayRotationDegrees
        created.stabilization = settings(context).current.videoStabilizationMode != VideoStabilizationMode.OFF
        created.start(cameraId)
        previewSurface?.let { created.setPreviewSurface(it) }
        pipeline = created
        scenes(context).setPipeline(created)
        refreshScene(context)
        applyEffects(context)
        refreshLuts(context)
        if (mutableCamera.value.cameras.isEmpty()) {
            val probe = CameraSource(context.applicationContext)
            val cameras = try {
                probe.cameras()
            } finally {
                probe.release()
            }
            mutableCamera.value = CameraUiState(cameras = cameras)
        }
        val cameras = mutableCamera.value.cameras
        mutableCamera.value = mutableCamera.value.withAutoControls().copy(
            current = cameras.firstOrNull { it.id == cameraId } ?: cameras.firstOrNull(),
            zoom = 1f,
            torch = false,
            stabilization = created.stabilization,
        )
        return created
    }

    private fun releasePipeline() {
        android.util.Log.d(TAG, "Releasing camera pipeline ${pipeline != null}")
        sceneEngine?.setPipeline(null)
        browserWidgets?.update(null, emptyList())
        pipeline?.stop()
        pipeline = null
    }

    /** The camera permission was just granted: the preview camera could not open before. */
    fun cameraPermissionGranted() {
        mutableStatus.value = mutableStatus.value.copy(detail = "")
        pipeline?.restartCamera()
    }

    /** Switches camera (upstream camera picker); remembered while the app runs. */
    fun switchCamera(id: String, force: Boolean = false) {
        cameraId = id
        pipeline?.switchCamera(id, force)
        val info = mutableCamera.value.cameras.firstOrNull { it.id == id }
        mutableCamera.value = mutableCamera.value.withAutoControls().copy(current = info, zoom = 1f, torch = false)
    }

    fun setZoom(factor: Float) {
        val info = mutableCamera.value.current ?: return
        val zoom = factor.coerceIn(info.minZoom, info.maxZoom)
        pipeline?.setZoom(zoom)
        mutableCamera.value = mutableCamera.value.copy(zoom = zoom)
    }

    fun setTorch(on: Boolean) {
        if (mutableCamera.value.current?.hasTorch != true) {
            return
        }
        pipeline?.setTorch(on)
        mutableCamera.value = mutableCamera.value.copy(torch = on)
    }

    /** Tap-to-focus at a normalized point of the preview (upstream tap gesture). */
    fun focusAt(x: Float, y: Float, previewAspect: Float) {
        pipeline?.focusAtPreviewPoint(x, y, previewAspect)
    }

    /** Back to continuous auto focus (upstream long press). */
    fun setAutoFocus() {
        pipeline?.setAutoFocus()
        mutableCamera.value = mutableCamera.value.copy(manualFocus = null)
    }

    // Manual camera controls (upstream ModelCamera.swift; sliders use 0..1 factors)

    fun setExposureBias(bias: Float) {
        pipeline?.setExposureBias(bias)
        mutableCamera.value = mutableCamera.value.copy(exposureBias = bias)
    }

    /** Manual focus at a lens position (0 near, 1 far), or null for auto focus. */
    fun setManualFocus(position: Float?) {
        if (position == null) {
            setAutoFocus()
            return
        }
        pipeline?.setManualFocus(position)
        mutableCamera.value = mutableCamera.value.copy(manualFocus = position)
    }

    /**
     * Manual shutter and/or ISO as factors (upstream `setManualExposureAndIso`). A null value is
     * locked at what the camera uses now; both null returns to auto exposure.
     */
    fun setManualExposureAndIso(exposureFactor: Float?, isoFactor: Float?) {
        val video = pipeline ?: return
        val info = mutableCamera.value.current ?: return
        if (exposureFactor == null && isoFactor == null) {
            video.setAutoExposure()
        } else {
            val exposure = exposureFactor?.let { CameraFactors.exposureFromFactor(exposures(info, video), it) }
            val iso = isoFactor?.let { CameraFactors.isoFromFactor(info.minIso, info.maxIso, it) }
            video.setManualExposure(exposure, iso)
        }
        mutableCamera.value = mutableCamera.value.copy(manualExposure = exposureFactor, manualIso = isoFactor)
    }

    /** Manual white balance as a factor of 2200..10000 K, or null for auto. */
    fun setManualWhiteBalance(factor: Float?) {
        val video = pipeline ?: return
        if (factor == null) {
            video.setAutoWhiteBalance()
        } else {
            video.setManualWhiteBalance(CameraFactors.kelvinFromFactor(factor))
        }
        mutableCamera.value = mutableCamera.value.copy(manualWhiteBalance = factor)
    }

    /** Video stabilization on or off, saved like upstream's global `videoStabilizationMode`. */
    fun setStabilization(context: Context, on: Boolean) {
        settings(
            context,
        ).update { it.copy(videoStabilizationMode = if (on) VideoStabilizationMode.STANDARD else VideoStabilizationMode.OFF) }
        pipeline?.stabilization = on
        mutableCamera.value = mutableCamera.value.copy(stabilization = on)
    }

    /** The camera's current auto values as slider factors, so a control switched to manual starts there. */
    fun currentCameraFactors(): Triple<Float, Float, Float>? {
        val video = pipeline ?: return null
        val info = mutableCamera.value.current ?: return null
        return Triple(
            CameraFactors.factorFromExposure(exposures(info, video), video.currentExposureNs),
            CameraFactors.factorFromIso(info.minIso, info.maxIso, video.currentIso),
            CameraFactors.positionFromFocusDistance(video.currentFocusDistance, info.minFocusDistance),
        )
    }

    /** Shutter speed for an exposure factor, for the slider label. */
    fun exposureLabel(factor: Float): String {
        val video = pipeline ?: return ""
        val info = mutableCamera.value.current ?: return ""
        return CameraFactors.formatExposure(CameraFactors.exposureFromFactor(exposures(info, video), factor))
    }

    private fun exposures(info: CameraInfo, video: VideoPipeline): List<Long> =
        CameraFactors.exposures(info.minExposureNs, info.maxExposureNs, 1_000_000_000L / video.settings.fps)
}
