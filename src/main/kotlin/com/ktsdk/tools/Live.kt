package com.ktsdk.tools

import com.ktsdk.MimeType
import com.ktsdk.Models
import com.ktsdk.models.AutomaticActivityDetection
import com.ktsdk.models.ClientTurn
import com.ktsdk.models.Content
import com.ktsdk.models.FunctionDescription
import com.ktsdk.models.FunctionResult
import com.ktsdk.models.GenerationConfig
import com.ktsdk.models.InlineData
import com.ktsdk.models.MediaChunk
import com.ktsdk.models.ModelFunctionCallInfo
import com.ktsdk.models.Part
import com.ktsdk.models.RealtimeInputConfig
import com.ktsdk.models.RealtimeMediaInput
import com.ktsdk.models.SystemInstruction
import com.ktsdk.models.Tool
import com.ktsdk.models.WebSocketClientTurnMessage
import com.ktsdk.models.WebSocketMediaChunkMessage
import com.ktsdk.models.WebSocketServerMessage
import com.ktsdk.models.WebSocketSetupDetails
import com.ktsdk.models.WebSocketSetupMessage
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.websocket.*
import io.ktor.websocket.Frame
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import java.awt.*
import java.awt.image.BufferedImage
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.plugins.jpeg.JPEGImageWriteParam
import javax.sound.sampled.*
import kotlin.math.max
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds
import java.util.Base64 as JavaBase64


// Configuration data class
data class GeminiLiveConfig(
    val apiKey: String,
    val model: String = "models/gemini-2.5-flash-live-preview",
    val systemInstruction: String = "You are a helpful AI assistant",
    val responseModality: String = "AUDIO", // Can be AUDIO or TEXT
    // inputAudioSampleRate and outputAudioSampleRate are removed as audio handling is externalized
    val functionCalls: List<FunctionDescription>? = null,
    val googleSearch: Boolean = false,
    val codeExecution: Boolean = false,
    val urlContext: Boolean = false,
    val maxImageDimension: Int = 1920,
    val jpegQuality: Int = 75,
    val imageSendIntervalMs: Long = 5000,
    val silenceTimeoutMs: Long = 750, // For AI speech silence detection (client-side concept)
    val activityDetectionSilenceMs: Int = 1500 // For user speech silence detection by Gemini
)

// Event callbacks interface
interface GeminiLiveCallbacks {
    fun onConnected() {}
    fun onDisconnected() {}
    fun onTextReceived(text: String) {}
    fun onAudioStarted() {} // User indicates they want to send audio
    fun onAudioStopped() {} // User indicates they stopped sending audio
    fun onAiSpeakingStarted() {}
    fun onAiSpeakingStopped() {}
    fun onScreenCaptureStarted() {}
    fun onScreenCaptureStopped() {}
    fun onError(message: String, exception: Exception?) {}
    fun onStatusUpdate(status: GeminiLiveStatus) {}
}

// Status data class
data class GeminiLiveStatus(
    val isConnected: Boolean,
    val isRecordingAudio: Boolean, // User's intent to send audio
    val isUserSpeaking: Boolean,   // Kept for consistency, same as isRecordingAudio now
    val isAiSpeaking: Boolean,
    val isCapturingScreen: Boolean,
    val captureResolution: String, // e.g., "800x600" or "N/A"
    val targetResolution: String   // e.g., "800x600" or "Full Screen"
)

class GeminiLiveClient(
    private val config: GeminiLiveConfig,
    private val callbacks: GeminiLiveCallbacks = object : GeminiLiveCallbacks {}, // Default no-op callbacks
    // Higher-order functions
    private val onAudioChunkReceived: (base64AudioChunk: String) -> Unit = {}, // Callback for receiving audio data
    private val beforeSendingInitialMessage: (session: DefaultClientWebSocketSession?) -> Unit = {},
    private val onReceiveTextFrame: (text: String, session: DefaultClientWebSocketSession?) -> Unit = { _, _ -> },
    private val onReceiveBinaryFrame: (data: String, session: DefaultClientWebSocketSession?) -> Unit = { _, _ -> },
    private val onFunctionCallReceived: (functionCalls: List<ModelFunctionCallInfo>, session: DefaultClientWebSocketSession?) -> Unit = { _, _ -> },
    private val onSendFunctionResponse: (session: DefaultClientWebSocketSession?, responseContent: String) -> Unit = { _, _ -> },
    private val afterFunctionResponseSent: (session: DefaultClientWebSocketSession?) -> Unit = {},
    private val onRequestReceivedCompletely: (session: DefaultClientWebSocketSession?) -> Unit = {}
) {

    val contents = mutableListOf<Content>()

    private data class CaptureParams(val w: Int, val h: Int, val x: Int, val y: Int)

    private val HOST = "generativelanguage.googleapis.com"
    private val URL =
        "wss://$HOST/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=${config.apiKey}"

    // Audio related members are removed.
    // INPUT_AUDIO_FORMAT, OUTPUT_AUDIO_FORMAT, audioInputLine, audioOutputTrack, audioPlaybackQueue, isRecording, audioRecordingJob, audioPlayingJob are GONE.

    private var isConnected = AtomicBoolean(false)
    private var isSpeaking = AtomicBoolean(false) // User's intent to send audio. Replaces 'isRecording'.
    private var isAiSpeaking = AtomicBoolean(false)
    private var isCapturingImages = AtomicBoolean(false)

    @Volatile
    private var captureRect: Rectangle? = null
    private var targetCaptureWidth = AtomicInteger(-1)
    private var targetCaptureHeight = AtomicInteger(-1)
    private var lastImageSendTime: Long = 0L

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = false }
    private val imageFileDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault())

    private val mainScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val client = HttpClient(CIO) {
        engine { requestTimeout = 0 }
        install(WebSockets) { pingInterval = 20_000.milliseconds }
        install(ContentNegotiation) { json(json) }
    }

    private var robot: Robot? = null
    private var fullScreenRect: Rectangle? = null

    private var imageCapturingJob: Job? = null
    private var webSocketSession: DefaultClientWebSocketSession? = null

    // --- PUBLIC API METHODS ---

    /**
     * Connects to the Gemini Live service.
     * This is a suspending function that completes only when the WebSocket connection is closed.
     */
    suspend fun connect(): Boolean {
        if (isConnected.get()) {
            return true
        }

        return try {
            client.webSocket(urlString = URL) { // This block suspends until the WebSocket is closed
                webSocketSession = this
                isConnected.set(true)
                callbacks.onConnected()
                callbacks.onStatusUpdate(getStatus())

                beforeSendingInitialMessage(webSocketSession)
                sendInitialSetupMessage()
                handleIncomingFrames() // This will loop until the socket closes or an error occurs
            }
            // If client.webSocket completes, the session is over. cleanupWebSocketResources has already been called.
            true
        } catch (e: Exception) {
            if (e.cause !is CancellationException) {
                callbacks.onError("Error establishing or during WebSocket session: ${e.message}", e)
            }
            cleanupWebSocketResources() // Ensure cleanup on failure
            false
        }
    }

    /**
     * Disconnects from the service.
     */
    suspend fun disconnect() {
        if (!isConnected.get() && webSocketSession == null) return
        try {
            // Stop active media streams
            stopAudioInput()
            stopImageCapture(forced = true)

            webSocketSession?.close(CloseReason(CloseReason.Codes.NORMAL, "User requested disconnect"))
            // Cleanup is handled by the finally block of handleIncomingFrames
        } catch (e: Exception) {
            callbacks.onError("Error during disconnect attempt: ${e.message}", e)
            cleanupWebSocketResources() // Force cleanup if close fails
        }
    }

    /**
     * Signals the intent to start sending audio.
     * This no longer handles actual recording, just state management.
     * The user is responsible for calling sendMediaChunk with audio data.
     * @return true if state was updated.
     */
    fun startAudioInput(): Boolean {
        if (isSpeaking.get()) return true // Already in "speaking" state
        isSpeaking.set(true)
        callbacks.onAudioStarted()
        callbacks.onStatusUpdate(getStatus())
        return true
    }

    /**
     * Signals the intent to stop sending audio.
     * The user should stop sending chunks via sendMediaChunk.
     * @return true if state was updated.
     */
    fun stopAudioInput(): Boolean {
        if (!isSpeaking.get()) return true // Already in "stopped" state
        isSpeaking.set(false)
        callbacks.onAudioStopped()
        callbacks.onStatusUpdate(getStatus())
        return true
    }

    // The screen capture methods remain unchanged as they are platform-specific (AWT) but not related to audio.
    fun startScreenCapture(): Boolean {
        if (GraphicsEnvironment.isHeadless()) {
            callbacks.onError("Cannot start screen capture in headless environment.", null)
            return false
        }
        return try {
            startImageCaptureInternal()
            println("image captured (request initiated)")
            true
        } catch (e: Exception) {
            callbacks.onError("Failed to initiate start screen capture: ${e.message}", e)
            callbacks.onStatusUpdate(getStatus())
            false
        }
    }

    fun stopScreenCapture(): Boolean {
        return try {
            stopImageCapture(forced = false)
            true
        } catch (e: Exception) {
            callbacks.onError("Failed to initiate stop screen capture: ${e.message}", e)
            callbacks.onStatusUpdate(getStatus())
            false
        }
    }

    fun setScreenCaptureResolution(width: Int, height: Int): Boolean {
        return try {
            setTargetCaptureResolutionInternal(width, height)
            true
        } catch (e: Exception) {
            callbacks.onError("Failed to set capture resolution: ${e.message}", e)
            callbacks.onStatusUpdate(getStatus())
            false
        }
    }

    suspend fun sendTextMessage(text: String): Boolean {
        if (!isConnected.get()) {
            callbacks.onError("Not connected. Cannot send text message.", null)
            return false
        }
        if (text.isBlank()) {
            callbacks.onError("Cannot send an empty text message.", null)
            return false
        }
        return try {
            contents.add(Content(role = "user", parts = listOf(Part(text = text))))
            sendUserTextTurn(text)
            true
        } catch (e: Exception) {
            callbacks.onError("Failed to send text message: ${e.message}", e)
            false
        }
    }

    fun getStatus(): GeminiLiveStatus {
        val currentRect = captureRect
        val currentRes = currentRect?.let { "${it.width}x${it.height}" } ?: "N/A"
        val targetW = targetCaptureWidth.get()
        val targetH = targetCaptureHeight.get()
        val targetRes = if (targetW <= 0 || targetH <= 0) "Full Screen" else "${targetW}x${targetH}"

        return GeminiLiveStatus(
            isConnected = isConnected.get(),
            isRecordingAudio = isSpeaking.get(), // The intent to send audio is now the "recording" status
            isUserSpeaking = isSpeaking.get(),
            isAiSpeaking = isAiSpeaking.get(),
            isCapturingScreen = isCapturingImages.get(),
            captureResolution = currentRes,
            targetResolution = targetRes
        )
    }

    fun isConnected(): Boolean = isConnected.get()
    fun isRecordingAudio(): Boolean = isSpeaking.get()
    fun isUserSpeakingIntent(): Boolean = isSpeaking.get()
    fun isAiCurrentlySpeaking(): Boolean = isAiSpeaking.get()
    fun isScreenCaptureActive(): Boolean = isCapturingImages.get()

    suspend fun shutdown() {
        try {
            disconnect()
        } catch (e: Exception) {
            callbacks.onError("Error during disconnect phase of shutdown: ${e.message}", e)
        } finally {
            mainScope.cancel("Client shutdown requested")
            client.close()
            // onDisconnected is called by cleanupWebSocketResources, which is triggered by disconnect()
        }
    }

    // --- WebSocket Connection Lifecycle ---
    private suspend fun DefaultClientWebSocketSession.handleIncomingFrames() {
        try {
            for (frame in incoming) {
                when (frame) {
                    is Frame.Text -> {
                        val text = frame.readText()
                        receiveMessage(text)
                        onReceiveTextFrame(text, webSocketSession)
                    }

                    is Frame.Binary -> {
                        val bytes = frame.readBytes()
                        try {
                            val text = String(bytes, Charsets.UTF_8)
                            receiveMessage(text)
                            onReceiveBinaryFrame(text, webSocketSession)
                        } catch (e: Exception) {
                            callbacks.onError("Error processing binary frame content: ${e.message}", e)
                        }
                    }

                    is Frame.Close -> break
                    is Frame.Ping -> println("ping received")
                    is Frame.Pong -> println("pong received")
                    else -> callbacks.onError("Received unexpected frame type: ${frame.frameType.name}", null)
                }
            }
        } catch (e: ClosedReceiveChannelException) {
            // Normal closure
        } catch (e: Exception) {
            if (e.cause !is CancellationException) {
                callbacks.onError("Error in WebSocket receive loop: ${e.message}", e)
            }
        } finally {
            cleanupWebSocketResources()
        }
    }

    private fun cleanupWebSocketResources() {
        if (isConnected.getAndSet(false)) {
            stopAudioInput()
            stopImageCapture(forced = true)

            webSocketSession = null

            callbacks.onDisconnected()
            callbacks.onStatusUpdate(getStatus())
        }
    }

    // **MODIFIED**: turnComplete is now false to keep the connection open for continuous conversation.
    suspend fun sendTurnMessage(isTurnComplete: Boolean, role: String = "user", message: List<Part>) {
        val turnMessage = WebSocketClientTurnMessage(ClientTurn(isTurnComplete, listOf(Content(role, message))))
        webSocketSession?.send(json.encodeToString(turnMessage))
    }

    private suspend fun sendInitialSetupMessage() {
        val setupMessage = WebSocketSetupMessage(
            WebSocketSetupDetails(
                config.model,
                GenerationConfig(
                    responseModalities = listOf(config.responseModality),
                ),
                systemInstruction = SystemInstruction(
                    listOf(Part(text = config.systemInstruction))
                ),
                realTimeInputConfig = RealtimeInputConfig(
                    AutomaticActivityDetection(silenceDurationMs = config.activityDetectionSilenceMs)
                ),
                tools = Tool(
                    functionDeclarations = config.functionCalls,
                    googleSearch = emptyMap(),
                )
            )
        )
        try {
            val jsonString = json.encodeToString(setupMessage)
            webSocketSession?.send(Frame.Text(jsonString))
        } catch (e: Exception) {
            callbacks.onError("Error sending initial setup message: ${e.message}", e)
        }
    }

    // --- WebSocket Communication (Sending & Receiving) ---
    /**
     * PUBLIC method for the user to send a Base64 encoded audio chunk.
     * The user is responsible for recording and encoding the audio.
     */
    suspend fun sendMediaChunk(b64Data: String, mimeType: String) {
        if (webSocketSession == null || !isConnected.get()) return

        val mediaChunkMessage = WebSocketMediaChunkMessage(RealtimeMediaInput(listOf(MediaChunk(mimeType, b64Data))))
        try {
            val jsonString = json.encodeToString(mediaChunkMessage)
            webSocketSession?.send(Frame.Text(jsonString))
        } catch (e: Exception) {
            callbacks.onError("Error sending media chunk (MIME: $mimeType): ${e.message}", e)
        }
    }

    // **MODIFIED**: turnComplete is now false.
    private suspend fun sendUserTextTurn(text: String) {
        if (webSocketSession == null || !isConnected.get()) {
            callbacks.onError("WebSocket not connected, cannot send text turn.", null)
            return
        }
        val turnMessage = WebSocketClientTurnMessage(
            ClientTurn(
                turnComplete = false, // Keep connection open
                turns = listOf(Content(role = "user", parts = listOf(Part(text = text))))
            )
        )
        try {
            val jsonString = json.encodeToString(turnMessage)
            webSocketSession?.send(Frame.Text(jsonString))
        } catch (e: Exception) {
            callbacks.onError("Error sending text turn: ${e.message}", e)
        }
    }

    private suspend fun receiveMessage(message: String?) {
        if (message.isNullOrBlank()) return
        try {
            val response = json.decodeFromString<WebSocketServerMessage>(message)

            // Handle AI speaking state based on presence of audio data
            val hasAudio =
                response.serverContent?.modelTurn?.parts?.any { it.inlineData?.mimeType?.startsWith("audio/") == true } == true
            if (hasAudio && isAiSpeaking.compareAndSet(false, true)) {
                callbacks.onAiSpeakingStarted()
                callbacks.onStatusUpdate(getStatus())
            }

            response.serverContent?.modelTurn?.parts?.forEach { part ->
                part.text?.takeIf { it.isNotBlank() }?.let {
                    callbacks.onTextReceived(it)
                }
                // **MODIFIED**: Instead of playing audio, pass it to the user via callback.
                part.inlineData?.let { inlineData ->
                    if (inlineData.mimeType?.startsWith("audio/pcm") == true && !inlineData.data.isNullOrBlank()) {
                        // Pass the Base64 data to the user's callback for playback.
                        onAudioChunkReceived(inlineData.data)
                    } else if (inlineData.mimeType != null && !inlineData.mimeType.startsWith("audio/pcm")) {
                        callbacks.onError("Received unsupported inline data type: ${inlineData.mimeType}", null)
                    }
                }
            }

            // Heuristic to detect when AI stops speaking. If a message arrives without audio,
            // assume the audio stream has ended for now.
            if (!hasAudio && isAiSpeaking.compareAndSet(true, false)) {
                callbacks.onAiSpeakingStopped()
                callbacks.onStatusUpdate(getStatus())
            }


            if (response.toolCall != null) {
                println("function received: ${response.toolCall}")
                val fc = response.toolCall.functionCalls.first()
                onFunctionCallReceived(response.toolCall.functionCalls, webSocketSession)

                val frame = listOf(
                    FunctionResult(
                        id = fc.id,
                        name = fc.name,
                        response = mapOf("output" to json.encodeToJsonElement(fc.args))
                    )
                )

                val content = json.encodeToString(mapOf("toolResponse" to mapOf("functionResponses" to frame)))
                onSendFunctionResponse(webSocketSession, content)
                webSocketSession!!.send(content)
                afterFunctionResponseSent(webSocketSession)
                onRequestReceivedCompletely(webSocketSession)
            }
        } catch (e: Exception) {
            callbacks.onError("Error parsing received message: ${e.message}. Snippet: ${message.take(200)}", e)
        }
    }

    // --- Audio Input Management (REMOVED) ---
    // All methods related to javax.sound.sampled for input are removed.
    // startAudioInputInternal, stopAndReleaseAudioInputLine, etc. are GONE.
    // The public start/stopAudioInput methods are now simple state managers.

    // --- Image Capture Management (Screen) - Unchanged ---
    @Synchronized
    private fun initializeScreenCaptureComponents(): Boolean {
        if (robot == null || fullScreenRect == null) {
            try {
                robot = Robot()
                val primaryScreenDevice = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
                fullScreenRect = primaryScreenDevice.defaultConfiguration.bounds
            } catch (e: AWTException) {
                callbacks.onError("Failed to initialize Robot for screen capture (AWTException): ${e.message}", e)
                return false
            } catch (e: Exception) {
                callbacks.onError("Failed to initialize screen capture components: ${e.message}", e)
                return false
            }
        }
        return updateCaptureRect()
    }

    @Synchronized
    private fun stopImageCapture(forced: Boolean = false) {
        val wasCapturing = isCapturingImages.getAndSet(false)
        imageCapturingJob?.cancel(CancellationException("Screen capture stopped"))
        imageCapturingJob = null
        if (wasCapturing) {
            callbacks.onScreenCaptureStopped()
        }
        callbacks.onStatusUpdate(getStatus())
    }

    @Synchronized
    private fun updateCaptureRect(): Boolean {
        val localFullScreenRect = fullScreenRect ?: return false
        val fullW = localFullScreenRect.width
        val fullH = localFullScreenRect.height
        if (fullW <= 0 || fullH <= 0) return false

        val targetW = targetCaptureWidth.get()
        val targetH = targetCaptureHeight.get()

        val params = if (targetW <= 0 || targetH <= 0) {
            CaptureParams(w = fullW, h = fullH, x = localFullScreenRect.x, y = localFullScreenRect.y)
        } else {
            CaptureParams(
                w = targetW.coerceAtMost(fullW),
                h = targetH.coerceAtMost(fullH),
                x = localFullScreenRect.x + max(0, (fullW - targetW.coerceAtMost(fullW)) / 2),
                y = localFullScreenRect.y + max(0, (fullH - targetH.coerceAtMost(fullH)) / 2)
            )
        }
        captureRect = Rectangle(params.x, params.y, params.w, params.h)
        return true
    }

    @Synchronized
    private fun setTargetCaptureResolutionInternal(width: Int, height: Int) {
        targetCaptureWidth.set(if (width <= 0) -1 else width)
        targetCaptureHeight.set(if (height <= 0) -1 else height)
        if (robot != null && fullScreenRect != null) {
            updateCaptureRect()
        }
        callbacks.onStatusUpdate(getStatus())
    }

    // **MODIFIED**: turnComplete is now false.
    private suspend fun processAndSendImage(originalImage: BufferedImage) {
        var scaledImageInstance: BufferedImage? = null
        try {
            val imageToCompress = withContext(Dispatchers.IO) {
                val si = scaleImageIfNeeded(originalImage, config.maxImageDimension)
                if (si !== originalImage) {
                    scaledImageInstance = si
                }
                si
            }

            val jpegBytes = withContext(Dispatchers.IO) {
                ByteArrayOutputStream().use { baos ->
                    ImageIO.createImageOutputStream(baos).use { ios ->
                        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
                        writer.output = ios
                        val jpegParams = JPEGImageWriteParam(null).apply {
                            compressionMode = JPEGImageWriteParam.MODE_EXPLICIT
                            compressionQuality = config.jpegQuality / 100.0f
                        }
                        writer.write(null, IIOImage(imageToCompress, null, null), jpegParams)
                        writer.dispose()
                    }
                    baos.toByteArray()
                }
            }

            // Optional: Save for debugging
            // ... (save logic unchanged)

            val b64Image = JavaBase64.getEncoder().encodeToString(jpegBytes)
            webSocketSession?.send(
                json.encodeToString(
                    WebSocketClientTurnMessage(
                        ClientTurn(
                            turnComplete = false, // Keep connection open
                            turns = listOf(
                                Content(
                                    role = "user",
                                    parts = listOf(
                                        Part(inlineData = InlineData(MimeType.JPEG.mime, data = b64Image))
                                    )
                                )
                            )
                        )
                    )
                )
            ) ?: println("websocket session is not connected")
        } catch (e: Exception) {
            callbacks.onError("Error processing/sending image: ${e.message}", e)
        } finally {
            withContext(Dispatchers.IO + NonCancellable) {
                scaledImageInstance?.flush()
            }
        }
    }

    @Synchronized
    private fun startImageCaptureInternal() {
        if (isCapturingImages.get() || !isConnected.get()) return
        if (!initializeScreenCaptureComponents()) return

        isCapturingImages.set(true)
        callbacks.onScreenCaptureStarted()
        callbacks.onStatusUpdate(getStatus())
        lastImageSendTime = 0L

        imageCapturingJob = mainScope.launch {
            val currentRobotInstance = robot ?: return@launch
            try {
                while (isActive && isCapturingImages.get() && isConnected.get()) {
                    val currentCaptureRect = captureRect
                    if (currentCaptureRect == null || currentCaptureRect.width <= 0 || currentCaptureRect.height <= 0) {
                        delay(config.imageSendIntervalMs)
                        continue
                    }

                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastImageSendTime >= config.imageSendIntervalMs) {
                        val screenImage: BufferedImage? = withContext(Dispatchers.IO) {
                            try {
                                currentRobotInstance.createScreenCapture(currentCaptureRect)
                            } catch (e: Exception) {
                                callbacks.onError("Error capturing screen: ${e.message}", e)
                                null
                            }
                        }

                        if (screenImage != null) {
                            try {
                                processAndSendImage(screenImage)
                                lastImageSendTime = System.currentTimeMillis()
                            } finally {
                                withContext(Dispatchers.IO + NonCancellable) { screenImage.flush() }
                            }
                        }
                    }
                    delay(max(100L, config.imageSendIntervalMs / 10).coerceAtMost(1000L))
                }
            } catch (e: CancellationException) {
                // Normal cancellation
            } catch (e: Exception) {
                callbacks.onError("Error in screen capture loop: ${e.message}", e)
            } finally {
                withContext(NonCancellable) {
                    if (isCapturingImages.getAndSet(false)) {
                        callbacks.onScreenCaptureStopped()
                    }
                    callbacks.onStatusUpdate(getStatus())
                }
            }
        }
    }


    // --- Audio Output Management (REMOVED) ---
    // All methods related to playback are removed.
    // ingestAudioChunkToPlay, startAudioPlayback, stopAudioPlayback, etc. are GONE.

    companion object {
        // --- Static Utility Functions ---
        fun scaleImageIfNeeded(image: BufferedImage, maxDimension: Int): BufferedImage {
            if (image.width <= maxDimension && image.height <= maxDimension) return image
            val ratio =
                if (image.width > image.height) maxDimension.toFloat() / image.width else maxDimension.toFloat() / image.height
            val newWidth = (image.width * ratio).toInt().coerceAtLeast(1)
            val newHeight = (image.height * ratio).toInt().coerceAtLeast(1)
            val scaledImage = BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB)
            val g = scaledImage.createGraphics()
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g.drawImage(image, 0, 0, newWidth, newHeight, null)
            } finally {
                g.dispose()
            }
            return scaledImage
        }
    }
} // --- End of GeminiLiveClient Class ---

// --- Main Application and other classes are left unchanged as per your request ---
// The main function will work with the refactored client, but you now need to handle audio playback
// by implementing the `onAudioChunkReceived` callback and manage audio recording externally,
// calling `sendMediaChunk` with the data.

fun main() {

    // Dummy function for schema generation example
    fun contactSaver(name: String, phone: String): String {
        return "Saved contact $name with phone $phone"
    }

    val functionDeclarations = listOf(
        FunctionSchemaGenerator.generateSchema(::contactSaver)
    )
    runBlocking(Dispatchers.IO) {
        println("=== Gemini Live Client Application ===")

        val apiKey = System.getenv("GEMINI_API_KEY")
        if (apiKey.isNullOrBlank()) {
            println("ERROR: GEMINI_API_KEY environment variable not set. Please set it and try again.")
            exitProcess(1)
        }

        val modelName = """models/${Models.GEMINI_2_5_FLASH_LIVE_PREVIEW.modelName}"""
        println("modelName = $modelName")

        val clientConfig = GeminiLiveConfig(
            apiKey = apiKey,
            model = modelName,
            imageSendIntervalMs = 10_000,
            activityDetectionSilenceMs = 2000,
            functionCalls = listOf()
        )

        val clientCallbacks = object : GeminiLiveCallbacks {
            override fun onConnected() {
                println("[APP CALLBACK] ==> Connected to Gemini Live service.")
            }

            override fun onDisconnected() {
                println("[APP CALLBACK] ==> Disconnected from Gemini Live service.")
            }

            override fun onTextReceived(text: String) {
                println("[APP CALLBACK] GEMINI SAID: $text")
            }

            override fun onAudioStarted() {
                println("[APP CALLBACK] ==> User audio input started (intent).")
            }

            override fun onAudioStopped() {
                println("[APP CALLBACK] ==> User audio input stopped (intent).")
            }

            override fun onAiSpeakingStarted() {
                println("[APP CALLBACK] ==> AI started speaking.")
            }

            override fun onAiSpeakingStopped() {
                println("[APP CALLBACK] ==> AI stopped speaking.")
            }

            override fun onScreenCaptureStarted() {
                println("[APP CALLBACK] ==> Screen capture started.")
            }

            override fun onScreenCaptureStopped() {
                println("[APP CALLBACK] ==> Screen capture stopped.")
            }

            override fun onError(message: String, exception: Exception?) {
                System.err.println("[APP CALLBACK] ERROR: $message")
                exception?.printStackTrace(System.err)
            }
        }

        // **NEW**: Implement the audio playback callback
        val audioPlaybackHandler: (String) -> Unit = { base64AudioChunk ->
            // This is where you would decode and play the audio.
            // For this console app, we'll just print a confirmation.
            // In Android, you would decode Base64 and write to an AudioTrack.
            println("[APP AUDIO HANDLER] Received audio chunk for playback (Base64 length: ${base64AudioChunk.length}).")
            // Example of decoding (but not playing):
            // val audioData = java.util.Base64.getDecoder().decode(base64AudioChunk)
        }

        val geminiClient = GeminiLiveClient(
            config = clientConfig,
            callbacks = clientCallbacks,
            onAudioChunkReceived = audioPlaybackHandler // Pass the new handler
        )
        var geminiConnectionJob: Job? = null

        val scanner = Scanner(System.`in`)
        val mainAppScope = this

        try {
            while (mainAppScope.isActive) {
                printStatusLine2(geminiClient.getStatus())
                val inputLine = scanner.nextLine()?.trim() ?: ""
                if (inputLine.isEmpty() && mainAppScope.isActive) continue

                val parts = inputLine.split("\\s+".toRegex(), limit = 2)
                val command = parts.getOrNull(0)?.lowercase() ?: ""
                val args = parts.getOrNull(1) ?: ""

                when (command) {
                    "connect", "c", "1" -> {
                        if (!geminiClient.isConnected()) {
                            geminiConnectionJob = mainAppScope.launch {
                                println("[APP] Attempting to connect...")
                                val success = geminiClient.connect()
                                if (!success && !geminiClient.isConnected()) {
                                    println("[APP] Initial connection attempt failed.")
                                }
                                println("[APP] Gemini client WebSocket session has ended.")
                            }
                        } else {
                            println("[APP] Already connected.")
                        }
                    }

                    "disconnect", "0" -> {
                        if (geminiClient.isConnected()) {
                            println("[APP] Attempting to disconnect...")
                            mainAppScope.launch { geminiClient.disconnect() }
                        } else {
                            println("[APP] Not currently connected.")
                        }
                    }

                    "start_audio", "2" -> {
                        if (geminiClient.isConnected()) {
                            println("[APP] Signaling start of audio input... (YOU must now call sendMediaChunk).")
                            geminiClient.startAudioInput()
                        } else {
                            println("[APP] Not connected. Cannot start audio.")
                        }
                    }

                    "stop_audio", "3" -> {
                        println("[APP] Signaling stop of audio input...")
                        geminiClient.stopAudioInput()
                    }

                    "send_audio_chunk" -> { // Example command to simulate sending audio
                        if (geminiClient.isConnected() && geminiClient.isRecordingAudio()) {
                            mainAppScope.launch {
                                // In a real app, this would be your actual recorded audio data.
                                // This is a placeholder Base64 string for a short, silent PCM chunk.
                                val fakeAudioChunkB64 = "AAAA//8A/w=="
                                println("[APP] Sending a fake audio chunk...")
                                geminiClient.sendMediaChunk(fakeAudioChunkB64, "audio/pcm;rate=16000")
                            }
                        } else {
                            println("[APP] Not connected or audio input not started. Use 'start_audio' first.")
                        }
                    }

                    "start_screen", "4" -> {
                        if (GraphicsEnvironment.isHeadless()) {
                            println("[APP] Cannot start screen capture in headless environment.")
                        } else if (geminiClient.isConnected()) {
                            println("[APP] Starting screen capture...")
                            geminiClient.startScreenCapture()
                        } else {
                            println("[APP] Not connected. Cannot start screen capture.")
                        }
                    }

                    "stop_screen", "5" -> {
                        println("[APP] Stopping screen capture...")
                        geminiClient.stopScreenCapture()
                    }

                    "set_res", "6" -> {
                        if (args.isBlank()) {
                            println("Usage: set_res <width>x<height> | full")
                        } else if (args.equals("full", ignoreCase = true)) {
                            geminiClient.setScreenCaptureResolution(-1, -1)
                            println("[APP] Target resolution set to Full Screen.")
                        } else {
                            val resParts = args.split("x", limit = 2)
                            if (resParts.size == 2) {
                                try {
                                    val w = resParts[0].toInt()
                                    val h = resParts[1].toInt()
                                    geminiClient.setScreenCaptureResolution(w, h)
                                    println("[APP] Target resolution set to ${w}x${h}.")
                                } catch (e: NumberFormatException) {
                                    println("[APP] Invalid resolution format: $args")
                                }
                            } else {
                                println("[APP] Invalid resolution format: $args. Use WxH or 'full'.")
                            }
                        }
                    }

                    "send_text", "7" -> {
                        if (geminiClient.isConnected()) {
                            println("Enter text to send to Gemini:")
                            val textToSend = scanner.nextLine()
                            if (textToSend.isNotBlank()) {
                                mainAppScope.launch {
                                    println("[APP] Sending text: \"$textToSend\"")
                                    geminiClient.sendTextMessage(textToSend)
                                }
                            } else {
                                println("[APP] No text entered.")
                            }
                        } else {
                            println("[APP] Not connected. Cannot send text.")
                        }
                    }

                    "status", "8" -> {
                        val currentStatus = geminiClient.getStatus()
                        println("--- Current Client Status ---")
                        println("  Connected: ${currentStatus.isConnected}")
                        println("  Sending Audio (Intent): ${currentStatus.isRecordingAudio}")
                        println("  AI Speaking: ${currentStatus.isAiSpeaking}")
                        println("  Capturing Screen: ${currentStatus.isCapturingScreen}")
                        println("  Target Capture Res: ${currentStatus.targetResolution}")
                        println("  Current Capture Res: ${currentStatus.captureResolution}")
                        println("-----------------------------")
                    }

                    "help", "h" -> printHelp4()

                    "quit", "exit", "q" -> {
                        println("[APP] Exiting application...")
                        mainAppScope.cancel()
                    }

                    else -> if (command.isNotEmpty()) println("[APP] Unknown command: '$command'. Type 'help' for commands.")
                }
            }
        } catch (e: CancellationException) {
            println("[APP] Main application loop cancelled.")
        } finally {
            println("[APP] Shutting down Gemini client...")
            withContext(NonCancellable) {
                geminiClient.shutdown()
                geminiConnectionJob?.cancelAndJoin()
            }
            scanner.close()
            println("[APP] Application finished.")
        }
    }
}

fun printHelp4() {
    println("\nAvailable Commands:")
    println("  connect c 1            - Connect to Gemini Live service")
    println("  disconnect 0           - Disconnect from the service")
    println("  start_audio 2          - Signal start of audio input (user must send chunks)")
    println("  stop_audio 3           - Signal stop of audio input")
    println("  send_audio_chunk       - (For demo) Sends a single fake audio chunk")
    println("  start_screen 4         - Start sending screen images")
    println("  stop_screen 5          - Stop sending screen images")
    println("  set_res WxH|full 6     - Set screen capture resolution")
    println("  send_text 7            - Send a text message to Gemini")
    println("  status 8               - Show detailed client status")
    println("  help, h                - Show this help message")
    println("  quit, exit, q          - Disconnect and exit the application")
    println()
}

fun printStatusLine2(status: GeminiLiveStatus) {
    val connStatus = if (status.isConnected) "CONNECTED" else "DISCONNECTED"
    // isRecordingAudio now reflects the user's intent to send audio
    val micStatus = if (status.isRecordingAudio) "SENDING" else "OFF"
    val aiStatus = if (status.isAiSpeaking) "SPEAKING" else "IDLE"
    val screenStatus =
        if (status.isCapturingScreen) "ON (${status.captureResolution})" else "OFF"

    print("[$connStatus | Mic: $micStatus | AI: $aiStatus | Screen: $screenStatus] > ")
}

// The UtilitiesFeatures class is unchanged as requested.
class UtilitiesFeatures {
    private var robot: Robot? = null // Single Robot instance for both screenshot and webcam

    // For audio recording
    private var audioTargetDataLine: TargetDataLine? = null
    private var audioRecordingJob: Job? = null
    private var audioStream: ByteArrayOutputStream? = null
    private val audioRecordingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var isAudioRecordingActive: Boolean = false
    private var currentAudioFilePath: String? = null

    // Common audio format (16kHz, 16-bit, mono, signed, little-endian by default for PCM_SIGNED)
    private val defaultAudioFormat = AudioFormat(16000f, 16, 1, true, false)

    // For "webcam" recording (sequence of images)
    @Volatile
    private var isWebcamRecordingActive: Boolean = false
    private var webcamRecordingJob: Job? = null
    private val webcamRecordingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var webcamSaveDir: File? = null

    // Using a fixed rectangle for webcam simulation. Could be configurable.
    private val webcamCaptureRect = Rectangle(0, 0, 640, 480) // Example: Top-left 640x480 area
    private var webcamFrameCounter = 0L // Use Long for potentially many frames

    private val fileTimestampFormat = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault())

    init {
        if (!GraphicsEnvironment.isHeadless()) {
            try {
                robot = Robot()
            } catch (e: AWTException) {
                System.err.println("UtilitiesFeatures: Failed to initialize Robot: ${e.message}")
                // Screenshot and webcam features will not work
            } catch (e: SecurityException) {
                System.err.println("UtilitiesFeatures: Security exception initializing Robot (permissions?): ${e.message}")
            }
        } else {
            System.err.println("UtilitiesFeatures: Headless environment. Screenshot and webcam features disabled.")
        }
    }

    /**
     * Takes a screenshot of the entire screen and saves it to the specified path.
     * The TODO mentioned "jpeg format", this function supports JPEG or PNG via MimeType.
     * @param format The image format (e.g., MimeType.JPEG, MimeType.PNG).
     * @param savePath The full path (including filename and extension) where the screenshot will be saved.
     * @param onSave Callback function invoked with the saved filename upon successful save.
     * @return True if the screenshot capture was successfully initiated and saved, false otherwise.
     */
    fun takeScreenShot(format: MimeType, savePath: String, onSave: (savedFileName: String) -> Unit): Boolean {
        val currentRobot = robot ?: run {
            System.err.println("takeScreenShot: Robot not initialized (possibly due to headless or AWT error).")
            return false
        }
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("takeScreenShot: Cannot capture screen in headless environment.")
            return false
        }

        return try {
            val screenRect = Rectangle(Toolkit.getDefaultToolkit().screenSize)
            val image = currentRobot.createScreenCapture(screenRect)

            val outputFile = File(savePath)
            outputFile.parentFile?.mkdirs() // Ensure directory exists

            // ImageIO.write expects "jpeg", "png", etc.
            val formatName = format.name.lowercase(Locale.ROOT)
            val success = ImageIO.write(image, formatName, outputFile)
            image.flush() // Release resources used by the image

            if (success) {
                onSave(outputFile.name)
            } else {
                System.err.println("takeScreenShot: Failed to write image to file: $savePath using format $formatName")
            }
            success
        } catch (e: Exception) {
            System.err.println("takeScreenShot: Error taking screenshot: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    /**
     * Starts audio recording from the default microphone and saves it to a WAV file.
     * @param filePath The full path (including filename, e.g., "recording.wav") to save the audio.
     * @param onStarted Callback when recording actually starts.
     * @param onStopped Callback with the file path when recording stops and file is saved successfully.
     * @param onError Callback for errors during recording initiation or process.
     * @return True if recording initiation was successful, false otherwise.
     */
    fun startAudioRecording(
        filePath: String,
        onStarted: () -> Unit = {},
        onStopped: (filePath: String) -> Unit = {},
        onError: (String) -> Unit = {}
    ): Boolean {
        if (isAudioRecordingActive) {
            onError("Audio recording is already active.")
            return false
        }
        if (AudioSystem.getMixerInfo().none { AudioSystem.getMixer(it).targetLineInfo.isNotEmpty() }) {
            onError("No audio input devices (mixers with target lines) found.")
            return false
        }


        val dataLineInfo = DataLine.Info(TargetDataLine::class.java, defaultAudioFormat)
        if (!AudioSystem.isLineSupported(dataLineInfo)) {
            onError("Default audio format ($defaultAudioFormat) not supported by any available line.")
            return false
        }

        try {
            audioTargetDataLine = AudioSystem.getLine(dataLineInfo) as TargetDataLine
            // Larger buffer for TargetDataLine to prevent overflows
            audioTargetDataLine?.open(
                defaultAudioFormat,
                defaultAudioFormat.sampleRate.toInt() * defaultAudioFormat.frameSize * 2
            ) // 2 sec buffer
            audioTargetDataLine?.start()

            audioStream = ByteArrayOutputStream()
            isAudioRecordingActive = true
            currentAudioFilePath = filePath

            audioRecordingJob = audioRecordingScope.launch {
                // Smaller buffer for reading chunks
                val buffer =
                    ByteArray(defaultAudioFormat.sampleRate.toInt() * defaultAudioFormat.frameSize / 10) // 100ms chunks
                try {
                    onStarted()
                    while (isActive && isAudioRecordingActive) { // Check isActive for coroutine cancellation
                        val bytesRead = audioTargetDataLine!!.read(buffer, 0, buffer.size)
                        if (bytesRead > 0) {
                            audioStream?.write(buffer, 0, bytesRead)
                        } else if (bytesRead < 0) { // End of stream or error
                            onError("Audio line reported end of stream or error (read $bytesRead bytes).")
                            break
                        }
                    }
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        onError("Error during audio recording loop: ${e.message}")
                    }
                } finally {
                    // This block executes when the coroutine is cancelled or finishes
                    val wasRecording = isAudioRecordingActive // Capture before modification
                    isAudioRecordingActive = false // Ensure state is updated regardless of how 'finally' is reached

                    audioTargetDataLine?.stop()
                    audioTargetDataLine?.flush() // Discard any unread data
                    audioTargetDataLine?.close()
                    audioTargetDataLine = null

                    val capturedAudioStream = audioStream
                    val savedFilePath = currentAudioFilePath
                    audioStream = null // Clear for next recording
                    currentAudioFilePath = null

                    if (wasRecording && capturedAudioStream != null && savedFilePath != null) {
                        try {
                            val audioBytes = capturedAudioStream.toByteArray()
                            capturedAudioStream.close()
                            if (audioBytes.isNotEmpty()) {
                                val bais = ByteArrayInputStream(audioBytes)
                                val audioInputStream = AudioInputStream(
                                    bais,
                                    defaultAudioFormat,
                                    audioBytes.size / defaultAudioFormat.frameSize.toLong()
                                )
                                val outputFile = File(savedFilePath)
                                outputFile.parentFile?.mkdirs() // Ensure directory exists
                                AudioSystem.write(audioInputStream, AudioFileFormat.Type.WAVE, outputFile)
                                audioInputStream.close()
                                onStopped(savedFilePath)
                            } else if (wasRecording) { // if it was recording but no bytes, could be an issue
                                onError("No audio data was captured.")
                            }
                        } catch (e: Exception) {
                            onError("Failed to save audio file '$savedFilePath': ${e.message}")
                        }
                    } else if (wasRecording) { // If it was recording but stream or path is null.
                        onError("Audio recording stopped but data or path was missing.")
                    }
                }
            }
            return true
        } catch (e: LineUnavailableException) {
            onError("Audio line unavailable: ${e.message}. Microphone might be in use, disconnected, or permissions denied.")
            isAudioRecordingActive = false
            return false
        } catch (e: SecurityException) {
            onError("Security error starting audio recording (check microphone permissions): ${e.message}")
            isAudioRecordingActive = false
            return false
        } catch (e: Exception) {
            onError("Failed to start audio recording: ${e.message}")
            isAudioRecordingActive = false
            return false
        }
    }

    /**
     * Stops the ongoing audio recording.
     * The onStopped callback provided to startAudioRecording will be invoked with the file path if saving is successful.
     * @return True if stopping was initiated successfully (or if not recording).
     */
    fun stopAudioRecording(): Boolean {
        if (!isAudioRecordingActive && audioRecordingJob == null) {
            // Not recording, nothing to do.
            return true
        }
        // Set flag first to allow recording loop to terminate gracefully
        isAudioRecordingActive = false
        // Cancel the job. The 'finally' block within the job handles cleanup and saving.
        audioRecordingJob?.cancel(CancellationException("Audio recording stopped by user request."))
        audioRecordingJob = null // The job is now being cancelled/completed
        return true
    }


    /**
     * Starts "webcam" recording by capturing a fixed region of the screen at intervals.
     * Saves frames as individual JPEG images in the specified directory.
     * @param savePathDir Directory to save the image frames.
     * @param frameIntervalMs Interval in milliseconds between frame captures.
     * @param onStarted Callback when webcam recording actually starts.
     * @param onStopped Callback with the directory path when recording stops.
     * @param onError Callback for errors.
     * @return True if recording initiation was successful, false otherwise.
     */
    fun startWebcamRecording(
        savePathDir: String,
        frameIntervalMs: Long = 100, // Default to 10 FPS
        onStarted: () -> Unit = {},
        onStopped: (dirPath: String) -> Unit = {},
        onError: (String) -> Unit = {}
    ): Boolean {
        val currentRobot = robot ?: run {
            onError("Webcam (Robot) not initialized (possibly due to headless or AWT error).")
            return false
        }
        if (GraphicsEnvironment.isHeadless()) {
            onError("Cannot start webcam (screen region) capture in headless environment.")
            return false
        }
        if (isWebcamRecordingActive) {
            onError("Webcam recording is already active.")
            return false
        }

        webcamSaveDir = File(savePathDir)
        if (!webcamSaveDir!!.exists()) {
            if (!webcamSaveDir!!.mkdirs()) {
                onError("Failed to create webcam save directory: $savePathDir")
                return false
            }
        }
        if (!webcamSaveDir!!.isDirectory) {
            onError("Webcam save path is not a directory: $savePathDir")
            return false
        }

        isWebcamRecordingActive = true
        webcamFrameCounter = 0

        webcamRecordingJob = webcamRecordingScope.launch {
            var actualSaveDir: String? = null
            try {
                actualSaveDir = webcamSaveDir!!.absolutePath // Store for onStopped
                onStarted()
                // Ensure the webcamCaptureRect is within screen bounds
                val screenSize = Toolkit.getDefaultToolkit().screenSize
                val actualCaptureRect =
                    webcamCaptureRect.intersection(Rectangle(0, 0, screenSize.width, screenSize.height))

                if (actualCaptureRect.width <= 0 || actualCaptureRect.height <= 0) {
                    val errorMsg =
                        "Webcam capture region (${webcamCaptureRect.width}x${webcamCaptureRect.height} at ${webcamCaptureRect.x},${webcamCaptureRect.y}) is invalid or outside screen bounds (${screenSize.width}x${screenSize.height})."
                    System.err.println(errorMsg) // Log detailed error
                    onError(errorMsg.take(100) + "...") // Provide summarized error
                    isWebcamRecordingActive = false // Ensure loop doesn't run / stops
                    return@launch // Exit coroutine, finally will run
                }

                while (isActive && isWebcamRecordingActive) {
                    val frameImage: BufferedImage? = try {
                        currentRobot.createScreenCapture(actualCaptureRect)
                    } catch (e: Exception) {
                        System.err.println("Webcam frame capture error: ${e.message}")
                        null // Allow loop to continue, maybe a transient issue
                    }

                    if (frameImage != null) {
                        val timestamp = fileTimestampFormat.format(Date())
                        val frameFile = File(webcamSaveDir, "webcam_frame_${timestamp}_${webcamFrameCounter++}.jpg")

                        try {
                            val success = ImageIO.write(frameImage, "jpeg", frameFile)
                            if (!success) {
                                System.err.println("Failed to write webcam frame (ImageIO.write returned false): ${frameFile.absolutePath}")
                            }
                        } catch (ioe: IOException) {
                            System.err.println("IOException writing webcam frame ${frameFile.absolutePath}: ${ioe.message}")
                            // Consider if this is fatal or if we can continue
                        } finally {
                            frameImage.flush() // Release resources for the captured image
                        }
                    }
                    delay(frameIntervalMs)
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    onError("Error during webcam recording loop: ${e.message}")
                    System.err.println("Webcam recording loop exception: ${e.message}")
                    e.printStackTrace()
                }
            } finally {
                val wasRecording = isWebcamRecordingActive
                isWebcamRecordingActive = false
                if (wasRecording && actualSaveDir != null) {
                    onStopped(actualSaveDir)
                }
                // webcamSaveDir = null // Keep it if you want to inspect directory path after stop outside of callback
            }
        }
        return true
    }

    /**
     * Stops the ongoing "webcam" recording.
     * The onStopped callback provided to startWebcamRecording will be invoked.
     * @return True if stopping was initiated successfully (or if not recording).
     */
    fun stopWebcamRecording(): Boolean {
        if (!isWebcamRecordingActive && webcamRecordingJob == null) {
            return true
        }
        isWebcamRecordingActive = false // Signal the loop to stop
        webcamRecordingJob?.cancel(CancellationException("Webcam recording stopped by user request."))
        webcamRecordingJob = null
        return true
    }

    /**
     * Call this method to clean up resources, especially when the application is closing.
     * Stops any active recordings and cancels coroutine scopes.
     */
    fun cleanup() {
        println("UtilitiesFeatures: Cleaning up resources...")
        stopAudioRecording()
        stopWebcamRecording()

        // Attempt to gracefully shutdown scopes, giving jobs a chance to finish cleanup
        // Note: Scopes will be cancelled, jobs within them will get CancellationException
        audioRecordingScope.cancel("UtilitiesFeatures cleanup: Audio scope cancelled")
        webcamRecordingScope.cancel("UtilitiesFeatures cleanup: Webcam scope cancelled")

        // Robot instance does not have an explicit close/dispose method.
        // It's typically managed by GC. Nullifying helps.
        robot = null
        println("UtilitiesFeatures: Cleanup complete.")
    }
}