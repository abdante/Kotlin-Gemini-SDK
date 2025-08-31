package com.ktsdk.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

//region ========= Core Data Models =========

@Serializable
data class InlineData(
    @SerialName("mime_type") val mime_type: String? = null, // Changed from mimeType to mime_type for JSON consistency
    @SerialName("mimeType") val mimeType: String? = null, // Changed from mimeType to mime_type for JSON consistency
    @SerialName("data") val data: String? = null, // Base64 encoded image/media data
)

@Serializable
data class SafetyRating(
    val category: String? = null,
    val probability: String? = null
)

@Serializable
data class CodeExecutionResult(
    val outcome: String? = null,
    val output: String? = null
)

@Serializable
data class FunctionResult( // Represents the result of a function call, to be sent back to the model
    val id: String? = null,
    @SerialName("name") val name: String, // The name of the function that was called
    @SerialName("response") val response: Map<String, JsonElement>? = null,
//    @SerialName("response") val response: Map<String, JsonElement>? = null,
//    @SerialName("response") val response: JsonElement? = null,
    @SerialName("args") val args: Map<String, String>? = null
)

@Serializable
data class FunctionResponseContent(
    // The actual content of the function's result
    // The API often expects a structured object. 'content' or 'output' are common.
    // Using JsonElement for flexibility, or define a more specific structure if known.
    // Example: val output: String? = null
//    val content: JsonElement? = null
//    val content: Map<String,String>? = null
//    val content: String? = null,
    val result: String? = null,
)

@Serializable
data class FileDataContent(
    @SerialName("mimeType") val mimeType: String="video/*",
    @SerialName("fileUri") val fileUri: String
)

@Serializable
data class Part(
    val text: String? = null,
    @SerialName("inline_data") val inline_data: InlineData? = null,
    @SerialName("inlineData") val inlineData: InlineData? = null,
    @SerialName("function_call") val functionCall: FunctionCall? = null, // Model's request to call a function
    @SerialName("function_response") val function_response: FunctionResult? = null, // Client's response after executing a function
    @SerialName("functionResponse") val functionResponse: FunctionResult? = null, // Client's response after executing a function
    @SerialName("code_execution_result") val codeExecutionResult: CodeExecutionResult? = null,
    @SerialName("fileData") val fileData: FileDataContent?=null,
) {
    //    val isTextPart: Boolean = inlineData!!.data == "text"
//    val isImagePart: Boolean = inlineData!!.data.contains("image")
//    val isVideoPart: Boolean = inlineData!!.data.contains("video")
    fun isTextPart(): Boolean = inlineData!!.data!!.contains("text")
    fun isImagePart(): Boolean = inlineData!!.data!!.contains("image")
    fun isVideoPart(): Boolean = inlineData!!.data!!.contains("video")
}

@Serializable
data class Content(
    val role: String? = null, // e.g., "user", "model"
    val parts: List<Part>
)

@Serializable
data class Candidate(
    val content: Content? = null,
    val contents: List<Content>? = null,
    @SerialName("url_context_metadata")
    val urlContextMetadata: UrlContextMetadata? = null,
    val finishReason: String? = null,
    val index: Int? = null,
    val safetyRatings: List<SafetyRating>? = null,
    @SerialName("generated_image") val generatedImage: InlineData? = null // For specific image generation responses if image is not in content.parts
)

@Serializable
data class TokenDetail( // Previously PromptTokensDetail, TokenDetails
    val modality: String,
    @SerialName("token_count") val tokenCount: Int
)

@Serializable
data class UsageMetadata(
    @SerialName("prompt_token_count") val promptTokenCount: Int? = null,
    @SerialName("candidates_token_count") val candidatesTokenCount: Int? = null,
    @SerialName("total_token_count") val totalTokenCount: Int? = null,
    @SerialName("prompt_tokens_details") val promptTokensDetails: List<TokenDetail>? = null,
    @SerialName("response_token_count") val responseTokenCount: Int? = null, // Added from various versions
    @SerialName("response_tokens_details") val responseTokensDetails: List<TokenDetail>? = null // Added from various versions
)

@Serializable
data class GenerationConfig(
    @SerialName("response_modalities") val responseModalities: List<String>? = null,
    @SerialName("media_resolution") val mediaResolution: String? = null,
    @SerialName("speech_config") val speechConfig: SpeechConfig? = null,
    @SerialName("output_audio_transcription") val outputAudioTranscription: Map<String, String>? = null,
    // Common configurations, uncomment and use as needed
    // val temperature: Float? = null,
    // @SerialName("top_k") val topK: Int? = null,
    // @SerialName("top_p") val topP: Float? = null,
    // @SerialName("candidate_count") val candidateCount: Int? = null,
    // @SerialName("max_output_tokens") val maxOutputTokens: Int? = null,
    // @SerialName("stop_sequences") val stopSequences: List<String>? = null
)

@Serializable
data class SystemInstruction(
    val parts: List<Part> // System instructions can also have multiple parts
)

//endregion

//region ========= Function Calling & Tool Models =========

@Serializable
data class FunctionCall( // Represents the model's request for a function to be called
    val name: String,
    val args: Map<String, JsonElement>, // Arguments are typically a JSON object
    val id: String? = null
)

@Serializable
data class ItemsDetail( // Was ItemsDetailX
    val type: String
)

@Serializable
data class PropertySchema(
    val type: String,
    val description: String? = null,
    val items: ItemsDetail? = null,
    val enum: List<String>? = null
)

@Serializable
data class FunctionParameters( // Was FunctionParametersX
    val type: String = "OBJECT", // Typically "OBJECT"
    val properties: Map<String, PropertySchema>,
    val required: List<String>? = null
)

@Serializable
data class FunctionDescription(
    val name: String,
    val description: String,
    val behavior: Behavior?= Behavior.BLOCKING,
    val parameters: FunctionParameters
)

@Serializable
enum class Behavior {
    @SerialName("UNSPECIFIED")
    UNSPECIFIED,

    @SerialName("BLOCKING")
    BLOCKING,

    @SerialName("NON_BLOCKING")
    NON_BLOCKING,

}

@Serializable
data class DynamicRetrievalConfig(
    val mode: String,
    @SerialName("dynamic_threshold") val dynamicThreshold: Int
)

@Serializable
data class GoogleSearchRetrieval(
    @SerialName("dynamic_retrieval") val dynamicRetrieval: DynamicRetrievalConfig? = null
    // Add other google_search_retrieval parameters if any
)

@Serializable
data class Tool(
    @SerialName("google_search_retrieval") val googleSearchRetrieval: GoogleSearchRetrieval? = null,
    @SerialName("function_declarations") val functionDeclarations: List<FunctionDescription>? = null,
    // @SerialName("code_execution_config") val codeExecutionConfig: CodeExecutionConfig? = null, // If needed
    @SerialName("google_search") val googleSearch: Map<String, String>? = null,
    @SerialName("url_context") val urlContext: Map<String, String>? = null,
    @SerialName("code_execution") val codeExecution: Map<String, String>? = null,
)

@Serializable
data class FunctionCallingConfigDetails( // For tool_config
    val mode: String // e.g., "AUTO", "ANY", "NONE"
)

@Serializable
data class ToolConfig( // To be included in the .main request
    @SerialName("function_calling_config") val functionCallingConfig: FunctionCallingConfigDetails
)

//endregion

//region ========= General Gemini API Request & Response Models =========

@Serializable
data class GeminiRequest(
    val contents: List<Content>,
    @SerialName("generation_config") val generationConfig: GenerationConfig? = null,
    val tools: List<Tool>? = null,
    @SerialName("system_instruction") val systemInstruction: SystemInstruction? = null,
    @SerialName("tool_config") val toolConfig: ToolConfig? = null // For controlling tool execution mode
)

@Serializable
data class PromptFeedback(
    @SerialName("safety_ratings") val safetyRatings: List<SafetyRating>? = null
    // Other feedback fields like block_reason, etc.
)

@Serializable
data class GroundingMetadata(
    @SerialName("search_entry_point") val searchEntryPoint: SearchEntryPoint? = null,
    @SerialName("web_search_queries") val webSearchQueries: List<String>? = null
)

@Serializable
data class SearchEntryPoint(
    @SerialName("rendered_content") val renderedContent: String? = null
)

// This ToolCall structure is sometimes found at the root or within WebSocket responses
// when the model requests tool execution.
@Serializable
data class ModelToolCall( // Was 'ToolCall' in live1 Root, renamed to avoid conflict with 'Tool'
    @SerialName("function_calls") val functionCalls: List<ModelFunctionCallInfo>
)

@Serializable
data class ModelToolCallResponse( // Was 'ToolCall' in live1 Root, renamed to avoid conflict with 'Tool'
    @SerialName("functionCalls") val functionCalls: List<ModelFunctionCallInfo>
)

@Serializable
data class ModelFunctionCallInfo( // Was 'FunctionCallResponse' in live1 ToolCall
    val name: String,
    val args: Map<String, JsonElement>? = null, // Note: API might send args as string map here
    val id: String? = null // Optional ID for the call, useful for asynchronous calls
)

@Serializable
data class GeminiResponse(
    val candidates: List<Candidate>? = null,
    @SerialName("usage_metadata") val usageMetadata: UsageMetadata? = null,
    @SerialName("model_version") val modelVersion: String? = null, // Not always present
    @SerialName("prompt_feedback") val promptFeedback: PromptFeedback? = null,
    @SerialName("grounding_metadata") val groundingMetadata: GroundingMetadata? = null, // From live1
    // If the model directly issues a tool call at the top level of the response:
    @SerialName("tool_call") val toolCall: ModelToolCall? = null
)

// Specific response for image generation (if structure significantly differs from GeminiResponse)
// For now, assuming GeminiResponse with Candidate.generatedImage or image in Candidate.content.parts is sufficient.
// @Serializable
// data class ImageGenerationResponse(
//    val candidates: List<ImageCandidate>, // Potentially a more specialized ImageCandidate
//    // ... other image specific fields
// )

//endregion

//region ========= File API Models =========

@Serializable
data class FileDetails(
    val name: String,
    val uri: String,
    @SerialName("mime_type") val mimeType: String,
    @SerialName("create_time") val createTime: String,
    @SerialName("expiration_time") val expireTime: String
)

@Serializable
data class FileInfo( // Response for file upload or file metadata
    val file: FileDetails
)

//endregion

//region ========= Token Count API Models =========

@Serializable
data class TokenCountResponse( // Was TokenResponse
    @SerialName("total_tokens") val totalTokens: Int,
    @SerialName("prompt_tokens_details") val promptTokensDetails: List<TokenDetail>? = null // Optional, may not always be returned
)

//endregion

//region ========= WebSocket / Live Interaction Models =========

// --- WebSocket Setup Phase ---
@Serializable
data class WebSocketSetupDetails(
    // Was Setup
    val model: String,
    @SerialName("generation_config") val generationConfig: GenerationConfig? = null,
    @SerialName("system_instruction") val systemInstruction: SystemInstruction? = null,
    val tools: Tool? = null, // Unified Tool
    @SerialName("realtime_input_config") val realTimeInputConfig: RealtimeInputConfig? = null,
//    val inputAudioTranscription: AudioTranscriptionConfig? = null,
    val inputAudioTranscription: Map<String, String>? = null,
//    val outputAudioTranscription: AudioTranscriptionConfig? = null,
    val outputAudioTranscription: Map<String, String>? = null,

    )

@Serializable
data class WebSocketSetupMessage(
    val setup: WebSocketSetupDetails,
)

// --- Client to Server Messages (WebSocket) ---
@Serializable
data class MediaChunk(
    @SerialName("mime_type") val mimeType: String,
    val data: String // Base64 encoded media data
)

@Serializable
data class RealtimeMediaInput( // Was RealtimeInput
    @SerialName("media_chunks") val mediaChunks: List<MediaChunk>?=null,
    @SerialName("video") val video: MediaChunk? = null
    // @SerialName("media_chunks_single") val mediaChunksSingle: MediaChunk? = null // If API supports single chunk alternatively
)

@Serializable
data class WebSocketMediaChunkMessage(
    @SerialName("realtime_input") val realtimeInput: RealtimeMediaInput
)

@Serializable
data class ClientTurn( // Was Turn
    @SerialName("turn_complete") val turnComplete: Boolean? = null,
    val turns: List<Content> // List of user/tool turns using unified .Content
)

@Serializable
data class WebSocketClientTurnMessage(
    @SerialName("client_content") val clientContent: ClientTurn
)

// --- Server to Client Messages (WebSocket) ---
@Serializable
data class ServerModelTurn( // Was ModelTurn
    val parts: List<Part> // Using unified .Part
)

@Serializable
data class ServerSideContent( // Was ServerContent
    @SerialName("model_turn") val modelTurn: ServerModelTurn? = null,
    @SerialName("turn_complete") val turnComplete: Boolean? = null,
    @SerialName("generation_complete") val generationComplete: Boolean? = null // from live1
)

@Serializable
data class ServerSideContentResponse( // Was ServerContent
    @SerialName("modelTurn") val modelTurn: ServerModelTurn? = null,
    @SerialName("turnComplete") val turnComplete: Boolean? = null,
    @SerialName("generationComplete") val generationComplete: Boolean? = null, // from live1
    val outputTranscription: OutputTranscriptionLive? = null
)

@Serializable
data class OutputTranscriptionLive(val text: String? = null)

@Serializable
data class WebSocketServerMessage( // Was WebSocketResponse, ServerContentResponse
    @SerialName("server_content") val server_content: ServerSideContent? = null,
    @SerialName("serverContent") val serverContent: ServerSideContentResponse? = null,
    @SerialName("usage_metadata") val usageMetadata: UsageMetadata? = null,
    @SerialName("grounding_metadata") val groundingMetadata: GroundingMetadata? = null,
    // If the model issues a tool call within the WebSocket message structure:
    @SerialName("tool_call") val tool_Call: ModelToolCall? = null,
    @SerialName("toolCall") val toolCall: ModelToolCallResponse? = null
)

// --- Client to Server: Function Response Message (If sent as a separate WebSocket message type) ---
// This is an alternative to sending FunctionResult as a .Part in WebSocketClientTurnMessage
// Some APIs might require a dedicated message type for function responses.

@Serializable
data class FunctionExecutionDetails( // Was FunctionExecutionResult
    // Gemini typically expects the result within a 'content' field or specific structure.
    val content: String // The string result from the executed function
)

@Serializable
data class FunctionResponseToRelay( // Was FunctionResponsePart
    // 'name' might be more appropriate here if it refers to the function's name.
    // 'status' could imply success/failure. Clarify based on API.
    val name: String, // The name of the function that was executed (matches the 'id' or 'name' from FunctionCall)
//    val response: FunctionExecutionDetails? = null, // Contains the actual result
    val response: Map<String, String>? = null
)

@Serializable
data class FunctionResponseToReplay( // Was FunctionResponsePart
    // 'name' might be more appropriate here if it refers to the function's name.
    // 'status' could imply success/failure. Clarify based on API.
    val status: String, // The name of the function that was executed (matches the 'id' or 'name' from FunctionCall)
    val message: String // Contains the actual result
)

@Serializable
data class WebSocketFunctionResponseMessage(
    @SerialName("function_response") val functionResponse: FunctionResponseToRelay
)

//endregion

//_______________________________new live model for audio configuration

//@Serializable
enum class StartSensitivity {
    START_SENSITIVITY_LOW,
    START_SENSITIVITY_HIGH,
    // Add other sensitivity levels if they exist in your `types.StartSensitivity`
}

//@Serializable
enum class EndSensitivity {
    END_SENSITIVITY_LOW,
    END_SENSITIVITY_HIGH,
    // Add other sensitivity levels if they exist in your `types.EndSensitivity`
}

@Serializable
data class AutomaticActivityDetection(
    val disabled: Boolean = false, // Default value
//    val startOf_speech_sensitivity: String? = StartSensitivity.START_SENSITIVITY_LOW.name,
//    val end_of_speech_sensitivity: String? = EndSensitivity.END_SENSITIVITY_LOW.name,
    val prefix_padding_ms: Int? = 20,
//    val silence_duration_ms: Int? = 100
    @SerialName("silence_duration_ms")
    val silenceDurationMs: Int? = 100
)

@Serializable
data class RealtimeInputConfig(
//    @SerialName("automatic_activity_detection")
    val automaticActivityDetection: AutomaticActivityDetection
)

@Serializable
data class VoiceConfigWrapper(
    val voiceConfig: VoiceConfig
)

@Serializable
data class VoiceConfig(
    val prebuiltVoiceConfig: PrebuiltVoiceConfig
)

@Serializable
data class PrebuiltVoiceConfig(
    val voiceName: String = LiveVoiceNameEnum.KORE.voiceName
)

@Serializable
enum class LiveVoiceNameEnum(val voiceName: String) {
    PUCK("Puck"),
    CHARON("Charon"),
    KORE("Kore"),
    FENRIR("Fenrir"),
    AOEDE("Aoede"),
    LEDA("Leda"),
    ORUS("Orus"),
    ZEPHYR("Zephyr");

    companion object {
        fun fromString(voiceName: String): LiveVoiceNameEnum? {
            return entries.find { it.voiceName.equals(voiceName, ignoreCase = true) }
        }
    }
}


@Serializable
data class UrlContextMetadata(
    // Map "url_metadata" from JSON to urlMetadata in Kotlin
    @SerialName("url_metadata")
    val urlMetadata: List<UrlMetadata>
)

@Serializable
data class UrlMetadata(
    // Map "retrieved_url" from JSON to retrievedUrl in Kotlin
    @SerialName("retrieved_url")
    val retrievedUrl: String,
    // Map "url_retrieval_status" from JSON to urlRetrievalStatus in Kotlin
    @SerialName("url_retrieval_status")
    val urlRetrievalStatus: UrlRetrievalStatus
)

// For the enum, the JSON shows <UrlRetrievalStatus.URL_RETRIEVAL_STATUS_SUCCESS: "URL_RETRIEVAL_STATUS_SUCCESS">.
// This typically means the actual JSON value will just be "URL_RETRIEVAL_STATUS_SUCCESS".
// We use @SerialName to map the enum values to their string representations in JSON.
@Serializable
enum class UrlRetrievalStatus {
    @SerialName("URL_RETRIEVAL_STATUS_SUCCESS")
    URL_RETRIEVAL_STATUS_SUCCESS,

    // Add other possible status values if you know them from the API documentation
    // For example:
    // @SerialName("URL_RETRIEVAL_STATUS_FAILURE")
    // URL_RETRIEVAL_STATUS_FAILURE,
    // It's good practice to have an UNSPECIFIED or UNKNOWN to handle future additions
    @SerialName("URL_RETRIEVAL_STATUS_UNSPECIFIED")
    URL_RETRIEVAL_STATUS_UNSPECIFIED,

    @SerialName("URL_RETRIEVAL_STATUS_ERROR")
    URL_RETRIEVAL_STATUS_ERROR
}

@Serializable
enum class MediaResolution {
    @SerialName("MEDIA_RESOLUTION_LOW")
    MEDIA_RESOLUTION_LOW,

    @SerialName("MEDIA_RESOLUTION_MEDIUM")
    MEDIA_RESOLUTION_MEDIUM,

    @SerialName("MEDIA_RESOLUTION_HIGH")
    MEDIA_RESOLUTION_HIGH,

    @SerialName("MEDIA_RESOLUTION_UNKNOWN")
    MEDIA_RESOLUTION_UNSPECIFIED
}

@Serializable
data class SpeechConfig(
    val voiceConfig: VoiceConfig? = null,
    @SerialName("multi_speaker_voice_config") // Maps JSON "multi_speaker_voice_config" to multiSpeakerVoiceConfig
    val multiSpeakerVoiceConfig: MultiSpeakerVoiceConfig? = null
)

@Serializable
data class MultiSpeakerVoiceConfig(
    @SerialName("speaker_voice_configs") // Maps JSON "speaker_voice_configs" to speakerVoiceConfigs
    val speakerVoiceConfigs: List<SpeakerVoiceConfigEntry>
)

@Serializable
data class SpeakerVoiceConfigEntry(
    val speaker: String,
    @SerialName("voice_config") // Maps JSON "voice_config" to voiceConfig
    val voiceConfig: VoiceConfig
)

@Serializable
data class FunctionResponses(
    @SerialName("functionResponses") val functionResponses: List<FunctionResult>? = null, // Client's response after executing a function
)