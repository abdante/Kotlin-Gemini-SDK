package com.ktsdk

import com.ktsdk.models.Content
import com.ktsdk.models.FileDetails
import com.ktsdk.models.FileInfo
import com.ktsdk.models.GeminiRequest
import com.ktsdk.models.InlineData
import com.ktsdk.models.Part
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import java.util.*

fun sendFileVIAFileAPI(imagePath: String, mimeType: String) = runBlocking {
    val baseUrl = "https://generativelanguage.googleapis.com/upload/v1beta/files"
    val geminiApiKey = System.getenv("GEMINI_API_KEY") // Replace with your actual API key
    val imgPath = imagePath // Replace with the path to your image
    val displayName = "TEXT"

    // Step 1: Get MIME type and file size
    val file = File(imgPath)
    val mimeType = mimeType // Adjust as needed
    val numBytes = file.length()

    // Step 2: Initial resumable request defining metadata
    val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
            })
        }
    }
    val response = client.post("$baseUrl?key=$geminiApiKey") {
        headers {
            append("X-Goog-Upload-Protocol", "resumable")
            append("X-Goog-Upload-Command", "start")
            append("X-Goog-Upload-Header-Content-Length", numBytes.toString())
            append("X-Goog-Upload-Header-Content-Type", mimeType)
            append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        }
        setBody("""{"file": {"display_name": "$displayName"}}""")
    }

    val uploadUrl = response.headers["X-Goog-Upload-Url"] ?: error("Upload URL not found")

    // Step 3: Upload the actual bytes
    val put = client.put(uploadUrl) {
        headers {
            append(HttpHeaders.ContentLength, numBytes.toString())
            append("X-Goog-Upload-Offset", "0")
            append("X-Goog-Upload-Command", "upload, finalize")
        }
        setBody(file.readBytes())
    }

    println("put file: ${put.bodyAsText()}")

    client.close()

    return@runBlocking put.body<FileInfo>()
}

fun getFile(fileName: String) = runBlocking {
    val baseUrl = "https://generativelanguage.googleapis.com/v1beta/files"
    val geminiApiKey = System.getenv("GEMINI_API_KEY") // Replace with your actual API key
//    val fileName = "name_of_file_from_previous_step" // Replace with the name retrieved earlier

    val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    // Step 1: Get the file information
    val fileInfoJson = client.get("$baseUrl/$fileName?key=$geminiApiKey") {
        headers {
            append("Content-Type", "application/json")
        }
    }
    println(fileInfoJson.bodyAsText())
    // Step 2: Parse the file information
    val fileInfo = fileInfoJson.body<FileDetails>()
    println("File Name: ${fileInfo.name}")
    println("File URI: ${fileInfo.uri}")

    client.close()
    return@runBlocking fileInfo
}

fun generateContent(fileUri: String, mimeType: String, text: String, geminiApiKey: String): String = runBlocking {
    val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"
    val client = HttpClient()

    // Step 1: Make POST request to generate content
    val response: HttpResponse = client.post("$url?key=$geminiApiKey") {
        headers {
            append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        }
        setBody(
            """
        {
          "contents": [{
            "parts": [
              {"text": "$text"},
              {"file_data": {
                "mime_type": "$mimeType", 
                "file_uri": "$fileUri"
              }}
            ]
          }]
        }
        """.trimIndent()
        )
    }

    // Step 2: Return the response as a string
    val responseBody = response.bodyAsText()

    client.close()

    responseBody // This is the JSON response
}

enum class MimeType(val mime: String) {
    // AUDIO MIME TYPES
    WAV("audio/wav"),
    MP3("audio/mp3"),
    AIFF("audio/aiff"),
    AAC("audio/aac"),
    OGG_VORBIS("audio/ogg"),
    FLAC("audio/flac"),

    // VIDEO MIME TYPES
    VIDEOS("video/*"),
    VIDEO_MP4("video/mp4"),
    VIDEO_MPEG("video/mpeg"),
    VIDEO_MOV("video/mov"),
    VIDEO_AVI("video/avi"),
    VIDEO_X_FLV("video/x-flv"),
    VIDEO_MPG("video/mpg"),
    VIDEO_WEBM("video/webm"),
    VIDEO_WMV("video/wmv"),
    VIDEO_3GPP("video/3gpp"),

    // IMAGE MIME TYPES
    PNG("image/png"),
    JPEG("image/jpeg"),
    WEBP("image/webp"),
    HEIC("image/heic"),
    HEIF("image/heif"),

    TEXT("text/plain"),
    PDF("application/pdf"),

}

fun generateContentWithImages(
    imagePath1: String,
    imagePath2: String,
    googleApiKey: String
): String = runBlocking {
    val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"
    val client = HttpClient(CIO)

    // Step 1: Encode images to Base64
    val image1Data = Base64.getEncoder().encodeToString(File(imagePath1).readBytes())
    val image2Data = Base64.getEncoder().encodeToString(File(imagePath2).readBytes())

    // Step 2: Make POST request to generate content
    val response: HttpResponse = client.post("$url?key=$googleApiKey") {
        headers {
            append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        }
        setBody(
            """
        {
          "contents": [{
            "parts": [
              {
                "inline_data": {
                  "mime_type": "image/jpeg",
                  "data": "$image1Data"
                }
              },
              {
                "inline_data": {
                  "mime_type": "image/png",
                  "data": "$image2Data"
                }
              },
              {
                "text": "Generate a list of all the objects contained in both images."
              }
            ]
          }]
        }
        """.trimIndent()
        )
    }

    // Step 3: Return response
    val responseBody = response.bodyAsText()
    client.close()
    println(responseBody)
    responseBody
}
fun generateContentWithLocalFileDirectly(
    files:List<String>,
    prompt:String,
    googleApiKey: String
): String = runBlocking {
    val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"
    val client = HttpClient(CIO)

    val encodedFilesPathData = mutableMapOf<String, String>()
    files.forEach { file ->
        // Step 1: Encode images to Base64
        val fileData = Base64.getEncoder().encodeToString(File(file).readBytes())
//        encodedFilesPathData.add(imageData)

        val fileExtension = file.substringAfterLast('.', "")
        encodedFilesPathData.put(fileData!!,convertExtensionToMimeType(fileExtension)!!.mime.toString())
    }


    // Step 2: Make POST request to generate content
    val response: HttpResponse = client.post("$url?key=$googleApiKey") {
        headers {
            append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        }
        setBody(createDynamicJson(encodedFilesPathData,prompt).trimIndent()
        )
    }

    // Step 3: Return response
    val responseBody = response.bodyAsText()
    client.close()
    println(responseBody)
    responseBody
}

fun convertExtensionToMimeType(extension: String): MimeType? {
    println("org.example.convertExtensionToMimeType: $extension")
    return when (extension) {
        "wav" ->  MimeType.WAV
        "mp3" ->  MimeType.MP3
        "aiff" ->  MimeType.AIFF
        "flac" ->  MimeType.FLAC
        "aac" ->  MimeType.AAC
        "ogg" ->  MimeType.OGG_VORBIS

        "mp4" ->  MimeType.VIDEO_MP4

        "png" ->  MimeType.PNG
        "jpeg" ->  MimeType.JPEG
        "webm" ->  MimeType.WEBP

        else -> null
    }
}
//@Serializable
//data class InlineData(
//    val mime_type: String,
//    val data: String
//)
//
//@Serializable
//data class tool.Part(
//    val inline_data: InlineData? = null,
//    val text: String? = null
//)
//
//@Serializable
//data class tool.Content(
//    val parts: List<tool.Part>
//)
//
//@Serializable
//data class RequestPayload(
//    val contents: List<Content>
//)

fun createDynamicJson(
    contentPaths: Map<String, String>,
    userText: String
): String {

    // Build parts dynamically
    val parts = contentPaths.map { filePath ->
        Part(
            inlineData = InlineData(
                mimeType = filePath.value,
                data = filePath.key
            )
        )
    } + Part(text = userText) // Add user text as the last part

    // Create the payload
    val payload = GeminiRequest(
        contents = listOf(Content(parts = parts))
    )

    // Serialize to JSON
    return Json.encodeToString(payload)
}