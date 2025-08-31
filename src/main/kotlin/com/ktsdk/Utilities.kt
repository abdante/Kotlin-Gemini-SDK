package com.ktsdk

import com.ktsdk.models.GeminiResponse
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.*

fun base64StringToPng(base64String: String, filePath: String) {
    try {
        // 1. Decode the Base64 string to a byte array.
        val imageBytes = Base64.getDecoder().decode(base64String)

        // 2. Create a File object for the output PNG file
        val outputFile = File(filePath)

        // 3. Use a FileOutputStream to write the byte array to the file.
        FileOutputStream(outputFile).use { fos ->
            fos.write(imageBytes)
            fos.flush()
        }

        println("PNG image created successfully at: $filePath")

    } catch (e: IOException) {
        println("Error writing image to file: ${e.message}")
        e.printStackTrace() // Print the full stack trace for debugging
    } catch (e: IllegalArgumentException) {
        println("Invalid Base64 string: ${e.message}")
        e.printStackTrace()
    }
}

fun handleMultipleImage(file: File, saveImagesFilePath: String) {
    // 1. Read the JSON string from a file
//    val file = File("your_json_file.json") // Replace with your file path
    val jsonString = file.readText().trimIndent()

    // 2. Create a Kotlin Json object for deserialization
    val json = Json {
        ignoreUnknownKeys = true // Ignore any unknown keys in the JSON
    }

    try {
        // 3. Deserialize the JSON string into a Response object
        val response = json.decodeFromString<GeminiResponse>(jsonString)

        // 4. Access the data and work with it
        println("Model Version: ${response.modelVersion}")
        println("Total Token Count: ${response.usageMetadata!!.totalTokenCount}")

        if (!File(saveImagesFilePath).exists()) {
            File(saveImagesFilePath).mkdirs()
        }
        response.candidates!!.forEachIndexed { index, candidate ->
            println("\ntool.Candidate #${index + 1}:")
            candidate.content!!.parts.forEach { part ->
                if (part.isTextPart()) {
                    println("  - Text: ${part.text}")
                } else if (part.isImagePart()) {
                    println("  - Image (MIME Type: ${part.inlineData?.mimeType}, Data Length: ${part.inlineData?.data?.length})")
                    base64StringToPng(
                        part.inline_data!!.data!!,
                        "$saveImagesFilePath${System.currentTimeMillis()}.png"
                    )
                    // You can save the image data to a file or display it using a library like JavaFX or Swing.
                }
            }
        }

        if (response.usageMetadata.promptTokensDetails != null) {
            println("\nPrompt Token Details:")
            response.usageMetadata.promptTokensDetails.forEach { detail ->
                println("  - Modality: ${detail.modality}, Token Count: ${detail.tokenCount}")
            }
        }

    } catch (e: SerializationException) {
        println("Error during deserialization: ${e.message}")
    }
}