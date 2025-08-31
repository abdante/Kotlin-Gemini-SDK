package com.ktsdk

import com.ktsdk.models.Content
import com.ktsdk.models.FileInfo
import com.ktsdk.models.FunctionDescription
import com.ktsdk.models.GeminiRequest
import com.ktsdk.models.GeminiResponse
import com.ktsdk.models.GenerationConfig
import com.ktsdk.models.InlineData
import com.ktsdk.models.Part
import com.ktsdk.models.SystemInstruction
import com.ktsdk.models.Tool
import com.ktsdk.tools.GeminiLiveCallbacks
import com.ktsdk.tools.GeminiLiveClient
import com.ktsdk.tools.GeminiLiveConfig
import com.ktsdk.tools.GeminiLiveStatus
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.sse.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.*
import kotlin.collections.emptyMap
import kotlin.system.exitProcess


// Abstraction for the Gemini API
interface GeminiApiService {
    suspend fun generateContent(request: GeminiRequest): String
    suspend fun generateContentWithImage(text: String, imagePath: String): String
    suspend fun streamGenerateContent(
        url: String? = null,
        systemInstruction: String?,
        prompt: String,
        response: (text: String) -> Unit
    )

    suspend fun streamGenerateContent(
        url: String? = null,
        systemInstruction: String?,
        prompt: String,
        functionCall: List<FunctionDescription>? = null,
        googleSearchTool: Boolean = false,
        response: (text: String) -> Unit
    )

    suspend fun multiTurnGenerateContent(contents: List<Content>): String
    suspend fun multiTurnStreamGenerateContent(
        systemInstruction: String?,
        contents: List<Content>,
        response: (text: String) -> Unit
    )

    suspend fun generateContentWithSystemInstruction(
        systemInstruction: SystemInstruction,
        contents: List<Content>
    ): String

    suspend fun generateImage(prompt: String, imagePath: String): String // Return Base64 encoded image
    suspend fun editImage(prompt: String, imagePath: String): String // Return Base64 encoded image
    suspend fun generateContentWithTool(
        systemInstruction: String?,
        text: String,
        googleSearchTool: Boolean,
        response: (String) -> Unit
    )

    suspend fun generateContentWithTool(
        systemInstruction: String?,
        prompts: List<Part>,
        googleSearchTool: Boolean,
        response: (String) -> Unit
    )

    suspend fun multiTurnStreamGenerateContent(
        systemInstruction: String?,
        googleSearchTool: Boolean = false,
        filesPath: List<String>,
        prompt: String,
        response: (String) -> Unit
    )

    suspend fun generateStreamChatConversation(
        systemInstruction: String? = null,
        googleSearchTool: Boolean = false,
        contents: MutableList<Content>,
        filesPath: List<String>? = null,
        filesInfo: List<FileInfo>? = null,
        prompt: String? = null,
        response: (String) -> Unit
    )
}

// Implementation using Ktor Client
class GeminiApi(
    private val httpClient: HttpClient? = null,
    private val apiKey: String,
    private val model: String
) :
    GeminiApiService {

    private val client = if (httpClient == null) HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 500_000
            connectTimeoutMillis = 500_000
            socketTimeoutMillis = 500_000
        }

        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
                ignoreUnknownKeys = true
            })
        }

        install(SSE)
    } else {
        httpClient
    }

    val defaultJson = Json { ignoreUnknownKeys = true }
    private val baseUrl = "https://generativelanguage.googleapis.com/v1beta/models/${this.model}"
    private val imageGenerationBaseUrl =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash-exp-image-generation" //for image

    override suspend fun generateContent(request: GeminiRequest): String {
        val response: HttpResponse = client.post("$baseUrl:generateContent?key=$apiKey") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        //return response.bodyAsText() //simple response
        val generateContentResponse = response.body<GeminiResponse>() // Use the data class
        return generateContentResponse.candidates?.joinToString("\n") { candidate ->
            candidate.content?.parts?.joinToString("") { part -> part.text ?: "" } ?: ""
        } ?: ""
    }

    override suspend fun generateContentWithImage(text: String, imagePath: String): String {
        val imageBytes = File(imagePath).readBytes()
        val encodedImage = Base64.getEncoder().encodeToString(imageBytes)

        val request = GeminiRequest(
            contents = listOf(
                Content(
                    parts = listOf(
                        Part(text = text),
                        Part(inlineData = InlineData(mime_type = MimeType.JPEG.mime, data = encodedImage))
                    )
                )
            )
        )

        val response: HttpResponse = client.post("$baseUrl:generateContent?key=$apiKey") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        // return response.bodyAsText()
        // Parse the response using the defined data classes.
        val generateContentResponse = response.body<GeminiResponse>()
        return generateContentResponse.candidates?.joinToString("\n") { candidate ->
            candidate.content?.parts?.joinToString("") { part -> part.text ?: "" } ?: ""
        } ?: ""
    }

    override suspend fun streamGenerateContent(
        url: String?,
        systemInstruction: String?,
        prompt: String,
        response: (text: String) -> Unit
    ) {

        val systemInstruction = if (systemInstruction != null) {
            SystemInstruction(parts = listOf(Part(text = systemInstruction)))
        } else null
        val request = GeminiRequest(
            systemInstruction = systemInstruction,
            contents = listOf(Content(parts = listOf(Part(text = prompt))))
        )
        client.sse(
            request = {
                method = HttpMethod.Post
                contentType(ContentType.Application.Json)
                setBody(request)
//            headers {
//                append("Connection", "keep-alive") //for stream
//            }
            }, urlString = url ?: "$baseUrl:streamGenerateContent?alt=sse&key=$apiKey"
        ) {
            this.incoming.collect { response -> }
            incoming.collect { event ->
                println(event.data)
                val generateContentResponse =
                    defaultJson.decodeFromString<GeminiResponse>(event.data ?: "null")
                val response = generateContentResponse.candidates?.joinToString("\n") { candidate ->
                    candidate.content?.parts?.joinToString("") { part -> part.text ?: "" } ?: ""
                } ?: "null"
                response(response)
            }
        }
    }

    override suspend fun streamGenerateContent(
        url: String?,
        systemInstruction: String?,
        prompt: String,
        functionCall: List<FunctionDescription>?,
        googleSearchTool: Boolean,
        response: (String) -> Unit
    ) {
        val systemInstruction = if (systemInstruction != null) {
            SystemInstruction(parts = listOf(Part(text = systemInstruction)))
        } else null
        val request = GeminiRequest(
            systemInstruction = systemInstruction,
            contents = listOf(Content(parts = listOf(Part(text = prompt)))),
            tools = listOf(
                Tool(
                    googleSearch = if (googleSearchTool) emptyMap() else null,
                    functionDeclarations = if (functionCall == null) {
                        null
                    } else {
                        functionCall
                    }
                )
            )
        )
        client.sse(
            request = {
                method = HttpMethod.Post
                contentType(ContentType.Application.Json)
                setBody(request)
//            headers {
//                append("Connection", "keep-alive") //for stream
//            }
            }, urlString = url ?: "$baseUrl:streamGenerateContent?alt=sse&key=$apiKey"
        ) {
            this.incoming.collect { response -> }
            incoming.collect { event ->
                println(event.data)
                val generateContentResponse =
                    defaultJson.decodeFromString<GeminiResponse>(event.data ?: "null")
                val response = generateContentResponse.candidates?.joinToString("\n") { candidate ->
                    candidate.content?.parts?.joinToString("") { part -> part.text ?: "" } ?: ""
                } ?: "null"
                response(response)
            }
        }
    }

    suspend fun tokenCount(
        systemInstruction: String?,
        prompt: String,
    ): GeminiResponse {
        val request = GeminiRequest(
            contents = listOf(
                Content(
                    role = "system",
                    parts = listOf(Part(text = systemInstruction))
                ),
                Content(parts = listOf(Part(text = prompt))),
            )
        )
        val response = client.post(
            "$baseUrl:countTokens?&key=$apiKey"
        ) {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        val body = response.body<GeminiResponse>()
//        println(body.totalTokens)
        return body
    }

    override suspend fun multiTurnGenerateContent(contents: List<Content>): String {
        val request = GeminiRequest(contents = contents)
        val response: HttpResponse = client.post("$baseUrl:generateContent?key=$apiKey") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        val generateContentResponse = response.body<GeminiResponse>()
        return generateContentResponse.candidates?.joinToString("\n") { candidate ->
            candidate.content?.parts?.joinToString("") { part -> part.text ?: "" } ?: ""
        } ?: ""
    }

    override suspend fun multiTurnStreamGenerateContent(
        systemInstruction: String?,
        contents: List<Content>,
        response: (String) -> Unit
    ) {
        val systemInstruction = if (systemInstruction != null) {
            SystemInstruction(listOf(Part(text = systemInstruction)))
        } else null
        val request = GeminiRequest(systemInstruction = systemInstruction, contents = contents)
        client.sse(
            request = {
                method = HttpMethod.Post
                contentType(ContentType.Application.Json)
                setBody(request)
                headers {
                    append("Connection", "keep-alive")
                }
            },
            urlString = "$baseUrl:streamGenerateContent?alt=sse&key=$apiKey"
        ) {
            incoming.collect { event ->
                val generateContentResponse =
                    defaultJson.decodeFromString<GeminiResponse>(event.data ?: "null")
                val response = generateContentResponse.candidates?.joinToString("\n") { candidate ->
                    candidate.content?.parts?.joinToString("") { part -> part.text ?: "" } ?: ""
                } ?: "null"
                response(response)
            }
        }
    }

    override suspend fun multiTurnStreamGenerateContent(
        systemInstruction: String?,
        googleSearchTool: Boolean,
        filesPath: List<String>,
        prompt: String,
        response: (String) -> Unit
    ) {
        val systemInstruction = if (systemInstruction != null) {
            SystemInstruction(listOf(Part(text = systemInstruction)))
        } else null

        val encodedFilesPathData = mutableMapOf<String, String>()
        filesPath.forEach { file ->
            // Step 1: Encode images to Base64
            val fileData = Base64.getEncoder().encodeToString(File(file).readBytes())
//        encodedFilesPathData.add(imageData)

            val fileExtension = file.substringAfterLast('.', "")
            encodedFilesPathData.put(fileData!!, convertExtensionToMimeType(fileExtension)!!.mime.toString())
        }

        // Build parts dynamically
        val parts = encodedFilesPathData.map { filePath ->
            Part(
                inlineData = InlineData(
                    mimeType = filePath.value,
                    data = filePath.key
                )
            )
        } + Part(text = prompt) // Add user text as the last part

        val tools = if (googleSearchTool) {
            listOf(
                Tool(
                    googleSearch = emptyMap()
                )
            )
        } else {
            null // Important:  Set to null if not used, don't send an empty list.
        }

        val request = GeminiRequest(
            systemInstruction = systemInstruction,
            contents = listOf(Content(parts = parts)),
            tools = tools,
        )
        client.sse(
            request = {
                method = HttpMethod.Post
                contentType(ContentType.Application.Json)
                setBody(request)
                headers {
                    append("Connection", "keep-alive")
                }
            },
            urlString = "$baseUrl:streamGenerateContent?alt=sse&key=$apiKey"
        ) {
            incoming.collect { event ->
                val generateContentResponse =
                    defaultJson.decodeFromString<GeminiResponse>(event.data ?: "null")
                val response = generateContentResponse.candidates?.joinToString("\n") { candidate ->
                    candidate.content?.parts?.joinToString("") { part -> part.text ?: "" } ?: ""
                } ?: "null"
                response(response)
            }
        }
    }

    override suspend fun generateStreamChatConversation(
        systemInstruction: String?,
        googleSearchTool: Boolean,
        contents: MutableList<Content>,
        filesPath: List<String>?,
        filesInfo: List<FileInfo>?,
        prompt: String?,
        response: (String) -> Unit
    ) {
        val systemInstruction = if (systemInstruction != null) {
            SystemInstruction(listOf(Part(text = systemInstruction)))
        } else null

        val encodedFilesPathData = mutableMapOf<String, String>()
        if (filesPath != null) {
            filesPath.forEach { file ->
                // Step 1: Encode images to Base64
                val fileData = Base64.getEncoder().encodeToString(File(file).readBytes())
                //        encodedFilesPathData.add(imageData)

                val fileExtension = file.substringAfterLast('.', "")
                encodedFilesPathData.put(fileData!!, convertExtensionToMimeType(fileExtension)!!.mime.toString())
            }
        }

        if (filesInfo != null) {
            filesInfo.forEach { file ->
                encodedFilesPathData[file.file.uri] = file.file.mimeType
            }
        }

        var parts: List<Part>? = null
        // Build parts dynamically
        parts = encodedFilesPathData.map { filePath ->
            Part(
                inlineData = InlineData(
                    mimeType = filePath.value,
                    data = filePath.key
                )
            )
        } + Part(text = prompt) // Add user text as the last part

        val tools = if (googleSearchTool) {
            listOf(
                Tool(
                    googleSearch = emptyMap()
                )
            )
        } else {
            null // Important:  Set to null if not used, don't send an empty list.
        }

        val request = GeminiRequest(
            systemInstruction = systemInstruction,
            contents = contents + Content(parts = parts),
            tools = tools,
        )
        client.sse(
            request = {
                method = HttpMethod.Post
                contentType(ContentType.Application.Json)
                setBody(request)
                headers {
                    append("Connection", "keep-alive")
                }
            },
            urlString = "$baseUrl:streamGenerateContent?alt=sse&key=$apiKey"
        ) {
            incoming.collect { event ->
                val generateContentResponse =
                    defaultJson.decodeFromString<GeminiResponse>(event.data ?: "null")
                val response = generateContentResponse.candidates?.joinToString("\n") { candidate ->
                    candidate.content?.parts?.joinToString("") { part -> part.text ?: "" } ?: ""
                } ?: "null"
                response(response)
            }
        }
    }


    suspend fun tokenCount(
        systemInstruction: String?,
        filesPath: List<String>,
        prompt: String
    ): GeminiResponse {

        val encodedFilesPathData = mutableMapOf<String, String>()
        filesPath.forEach { file ->
            // Step 1: Encode images to Base64
            val fileData = Base64.getEncoder().encodeToString(File(file).readBytes())

            val fileExtension = file.substringAfterLast('.', "")
            encodedFilesPathData.put(fileData!!, convertExtensionToMimeType(fileExtension)!!.mime.toString())
        }

        // Build parts dynamically
        val parts = encodedFilesPathData.map { filePath ->
            Part(
                inlineData = InlineData(
                    mimeType = filePath.value,
                    data = filePath.key
                )
            )
        } + Part(text = prompt) // Add user text as the last part

        val request = GeminiRequest(
            contents = listOf(
                //TODO("handle nullable state for system instruction and prompt")
                Content(
                    role = "system",
                    parts = listOf(Part(text = systemInstruction))
                ),
                Content(parts = parts)
            ),
        )
        val client = client.post(
            "$baseUrl:countTokens?&key=$apiKey"
        ) {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
//         println(client.bodyAsText())
        val response = client.body<GeminiResponse>()
//         println(response)
        return response
    }

    override suspend fun generateContentWithSystemInstruction(
        systemInstruction: SystemInstruction,
        contents: List<Content>
    ): String {
        val request = GeminiRequest(systemInstruction = systemInstruction, contents = contents)
        val response: HttpResponse = client.post("$baseUrl:generateContent?key=$apiKey") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        return response.bodyAsText()
    }

    override suspend fun generateImage(prompt: String, imagePath: String): String {
        val request = GeminiRequest(
            contents = listOf(Content(parts = listOf(Part(text = prompt)))),
            generationConfig = GenerationConfig(responseModalities = listOf("Text", "Image"))
        )

        val response: HttpResponse =
            client.post("$imageGenerationBaseUrl:generateContent?key=$apiKey") {
                contentType(ContentType.Application.Json)
                setBody(request)
                headers {
                    append("Connection", "keep-alive")
                }
            }

        val json = Json { ignoreUnknownKeys = true }

        File("test1.txt").writeText(response.bodyAsText())//FileWriter("test.txt").write(bytes.size)


        try {
            val element = json.parseToJsonElement(File("test1.txt").readText())

            val dataValue = element.jsonObject["candidates"]
                ?.jsonArray?.get(0)?.jsonObject?.get("content")
                ?.jsonObject?.get("parts")?.jsonArray?.get(0)
                ?.jsonObject?.get("inlineData")?.jsonObject?.get("data")
            val content = dataValue?.jsonPrimitive?.content

            base64StringToPng(content!!, imagePath)
        } catch (e: Exception) {
            println("e: $e")

            handleMultipleImage(
                File("D:\\D\\files\\projects\\ai\\gemini\\api\\ktor\\GeminiKtor1\\test1.txt"),
                imagePath
            )
        }
        return imagePath
//        return content!!
//        val imageResponse = response.body<ImageGenerationResponse>()
//        // Assuming you want the Base64 encoded image data.  Error handling is crucial here.
//        return imageResponse.candidates?.firstOrNull()?.generatedImage?.data
//            ?: throw Exception("No image generated")
    }

    override suspend fun editImage(prompt: String, imagePath: String): String {
        val imageBytes = File(imagePath).readBytes()
        val encodedImage = Base64.getEncoder().encodeToString(imageBytes)

        val request = GeminiRequest(
            contents = listOf(
                Content(
                    parts = listOf(
                        Part(text = prompt),
                        Part(inlineData = InlineData("image/jpeg", data = encodedImage))
                    )
                )
            ),
            generationConfig = GenerationConfig(responseModalities = listOf("Text", "Image"))
        )

        val response: HttpResponse =
            client.post("$imageGenerationBaseUrl:generateContent?key=$apiKey") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        //return response.bodyAsText() //for debugging
        val imageResponse = response.body<GeminiResponse>()
        return imageResponse.candidates?.firstOrNull()?.generatedImage?.data
            ?: throw Exception("No image generated")
    }

    override suspend fun generateContentWithTool(
        systemInstruction: String?,
        text: String,
        googleSearchTool: Boolean,
        response: (String) -> Unit
    ) {

        val systemInstruction = if (systemInstruction != null) {
            SystemInstruction(listOf(Part(text = systemInstruction)))
        } else null

        val tools = if (googleSearchTool) {
            listOf(
                Tool(
                    googleSearch = emptyMap()
                )
            )
        } else {
            null // Important:  Set to null if not used, don't send an empty list.
        }

        val requestBody = GeminiRequest(
            contents = listOf(Content(parts = listOf(Part(text = text)))),
            tools = tools,
            systemInstruction = systemInstruction
        )

        val url = "$baseUrl:streamGenerateContent?alt=sse&key=$apiKey"
        client.sse(request = {
            method = HttpMethod.Post
            parameter("key", apiKey)
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }, urlString = url) {
            incoming.collect { event ->
                val generateContentResponse =
                    defaultJson.decodeFromString<GeminiResponse>(event.data!!)
                val response = generateContentResponse.candidates?.joinToString("\n") { candidate ->
                    candidate.content?.parts?.joinToString("") { part -> part.text ?: "" } ?: ""
                } ?: "null"
                response(response)
            }
        }
    }

    override suspend fun generateContentWithTool(
        systemInstruction: String?,
        prompts: List<Part>,
        googleSearchTool: Boolean,
        response: (String) -> Unit
    ) {

        val systemInstruction = if (systemInstruction != null) {
            SystemInstruction(listOf(Part(text = systemInstruction)))
        } else null

        val tools = if (googleSearchTool) {
            listOf(
                Tool(
                    googleSearch = emptyMap()
                )
            )
        } else {
            null // Important:  Set to null if not used, don't send an empty list.
        }

        val requestBody = GeminiRequest(
            contents = listOf(Content(parts = prompts)),
            tools = tools,
            systemInstruction = systemInstruction
        )

        val url = "$baseUrl:streamGenerateContent?alt=sse&key=$apiKey"
        client.sse(request = {
            method = HttpMethod.Post
            parameter("key", apiKey)
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }, urlString = url) {
            incoming.collect { event ->
                val generateContentResponse =
                    defaultJson.decodeFromString<GeminiResponse>(event.data!!)
                val response = generateContentResponse.candidates?.joinToString("\n") { candidate ->
                    candidate.content?.parts?.joinToString("") { part -> part.text ?: "" } ?: ""
                } ?: "null"
                response(response)
            }
        }
    }

    /**
     * Creates and starts a new Gemini Live session.
     *
     * This function correctly handles the asynchronous nature of the connection. It creates a client,
     * launches a new coroutine to handle the entire connection lifecycle, and immediately returns
     * a LiveSession object containing the client and the connection job.
     *
     * The `client.connect()` call is a long-running suspending function that only returns when the
     * WebSocket session is terminated.
     *
     * @param coroutineScope The scope in which the connection lifecycle will be launched.
     *                       It's recommended that the caller provides a scope they control.
     * @param geminiLiveConfig The configuration for the Gemini Live client.
     * @param callbacks The implementation of callbacks to handle events like connection, text reception, etc.
     *                  **IMPORTANT**: Your logic for what to do *after* a successful connection
     *                  should be placed in the `onConnected` method of your `callbacks` implementation.
     * @return A [LiveSession] object containing the created client and the background job managing the connection.
     */
    fun createLiveSession(
        coroutineScope: CoroutineScope,
        geminiLiveConfig: GeminiLiveConfig,
        callbacks: GeminiLiveCallbacks
    ): LiveSession {
        println("=== Initializing Gemini Live Session ===")

        // 1. Create the client instance with the provided configuration and callbacks.
        val geminiClient = GeminiLiveClient(
            config = geminiLiveConfig,
            callbacks = callbacks
            // You can add other higher-order functions here if needed.
        )

        // 2. Launch a new coroutine in the provided scope for the connection.
        //    This job will run in the background and manage the entire session lifecycle.
        val connectionJob = coroutineScope.launch {
            try {
                println("[APP] Attempting to connect...")
                // The connect() function suspends until the WebSocket connection is closed,
                // either by the server, the client calling disconnect(), or an error.
                val success = geminiClient.connect()

                if (success) {
                    println("[APP] Gemini client WebSocket session ended normally.")
                } else {
                    // The onError callback would have already been triggered inside the client
                    // if the initial connection failed.
                    println("[APP] Gemini client WebSocket session ended due to an error or failure to connect.")
                }
            } catch (e: CancellationException) {
                println("[APP] Connection job was cancelled.")
            } catch (e: Exception) {
                // This will catch any unexpected errors during the connection process not handled inside the client.
                println("[APP] An unexpected error occurred in the connection job: ${e.message}")
                callbacks.onError("Connection job failed unexpectedly", e)
            } finally {
                // Ensure client resources are cleaned up if the job is cancelled or fails unexpectedly.
                // Using withContext(NonCancellable) ensures this cleanup code runs even if the coroutine is cancelled.
                withContext(NonCancellable) {
                    if (geminiClient.isConnected()) {
                        geminiClient.shutdown()
                    }
                }
            }
        }

        // 3. Return the client and the job to the caller immediately.
        //    The caller can now use the client and has a reference to the job to cancel it if needed.
        return LiveSession(geminiClient, connectionJob)
    }
}


/**
 * A data class to hold the client instance and its connection job.
 * This allows the caller to interact with the client and manage the job's lifecycle (e.g., cancel it).
 */
data class LiveSession(
    val client: GeminiLiveClient,
    val job: Job
)
