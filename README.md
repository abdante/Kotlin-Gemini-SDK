# Kotlin Gemini SDK

[![Release](https://img.shields.io/github/v/release/your-username/your-repo)](https://github.com/your-username/your-repo/releases)
[![Build Status](https://img.shields.io/github/actions/workflow/status/your-username/your-repo/build.yml)](https://github.com/your-username/your-repo/actions)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

A powerful, user-friendly, and idiomatic Kotlin client for the Google Gemini API. This SDK simplifies interactions with Gemini models, offering support for streaming, multi-modal inputs, tool usage, and declarative function calling.

## ✨ Features

- **Type-Safe & Idiomatic Kotlin**: Designed with coroutines for clean, asynchronous code.
- **Streaming & Non-Streaming**: Choose between receiving responses in chunks or all at once.
- **Multi-modal Support**: Easily send text, images, and files in a single prompt.
- **Built-in Tools**: Effortlessly enable tools like Google Search.
- **Declarative Function Calling**: Define native Kotlin functions with annotations, and the SDK handles generating the schema for the API.
- **Helper Utilities**: Includes convenient functions like `tokenCount` for prompt validation.

## 🚀 Installation

### Gradle (Kotlin DSL)

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.ktsdk:gemini-api:0.1.0")
}
```

### Gradle (Groovy DSL)

```groovy
// build.gradle
dependencies {
    implementation 'com.ktsdk:gemini-api:0.1.0'
}
```

## 🛠️ Getting Started

### 1. Authentication & Initialization

First, ensure you have your Gemini API key set as an environment variable.

```kotlin
import com.ktsdk.GeminiApi
import com.ktsdk.Models

// 1. Retrieve your API key securely (e.g., from environment variables)
val apiKey = System.getenv("GEMINI_API_KEY")

// 2. Select a model
val model = Models.GEMINI_2_5_FLASH.modelName

// 3. Initialize the API client
val geminiApi = GeminiApi(apiKey = apiKey, model = model)
```

### 2. Basic Text Generation (Streaming)

For real-time responses, use the `streamGenerateContent` method. It takes a lambda that is invoked for each chunk of the response.

```kotlin
// This is a suspend function, so it must be called from a coroutine scope
// or another suspend function.
val prompt = "Explain the importance of Kotlin Coroutines in 3 sentences."

geminiApi.streamGenerateContent(prompt = prompt) { response ->
    // Process each chunk of the response as it arrives
    print(response.text) 
}
```

### 3. Non-Streaming Generation with Tools

For simpler cases where you need the full response at once, use methods like `generateContentWithTool`. This example also demonstrates enabling the built-in Google Search tool.

```kotlin
import com.ktsdk.models.Part

val message = listOf(
    Part(text = "What were the key announcements at the last Google I/O?")
)

// Call the API with Google Search enabled
geminiApi.generateContentWithTool(
    prompts = message,
    googleSearchTool = true
) { response ->
    // The lambda receives the complete, final response
    println(response.text)
}
```

## Advanced Usage

### Multi-modal Prompts (Text and Images)

Combine different types of content in a single prompt using `Part` and `InlineData`.

```kotlin
import com.ktsdk.MimeType
import com.ktsdk.models.InlineData
import com.ktsdk.models.Part

// Note: The 'data' should be a Base64-encoded string of the image bytes.
val base64ImageString = "..." // your base64 encoded image

val promptWithImage = listOf(
    Part(
        text = "What is shown in this image?",
        inlineData = InlineData(
            mimeType = MimeType.JPEG.mime, 
            data = base64ImageString
        )
    )
)

geminiApi.generateContent(prompts = promptWithImage) { response ->
    println(response.text)
}
```

### Sending Local Files

You can easily reference local files in your prompts by providing their paths.

```kotlin
val filesPath = listOf("/path/to/report.pdf", "/path/to/data.csv")
val prompt = "Summarize the key findings from the attached documents."

geminiApi.generateContent(
    filesPath = filesPath,
    prompt = prompt
) { response ->
    println(response.text)
}
```

### Function Calling

Define your tools as native Kotlin functions and let the SDK handle the schema generation.

#### Step 1: Define a Function with Annotations

Use the `@GeminiFunction` and `@GeminiParameter` annotations to describe your function to the model.

```kotlin
import com.ktsdk.tools.GeminiFunction
import com.ktsdk.tools.GeminiParameter

@GeminiFunction(description = "Gets the current weather for a specified location")
fun getCurrentWeather(
    @GeminiParameter(description = "The city and state, e.g., San Francisco, CA")
    location: String,
): String {
    // In a real app, you would call a weather API here.
    return "The weather in $location is sunny with a high of 75°F."
}
```

#### Step 2: Generate Schema and Make the API Call

Use `FunctionSchemaGenerator` to create the tool schema and pass it to your API call. The model will then be able to request that your function be called.

```kotlin
import com.ktsdk.tools.FunctionSchemaGenerator

// Generate the schema from your Kotlin function reference
val weatherFunction = FunctionSchemaGenerator.generateSchema(::getCurrentWeather)

val prompt = "What's the weather like in Boston?"

// Make a streaming call with your function and Google Search enabled
geminiApi.streamGenerateContent(
    prompt = prompt,
    functionCall = listOf(weatherFunction),
    googleSearchTool = true
) { response ->
    // The response may contain a 'functionCall' part.
    // Your code would then execute the function and send the result back.
    println(response)
}
```

## Utilities

### Token Counting

Before sending a large prompt, you can check how many tokens it will consume.

```kotlin
val systemInstruction = "You are a helpful assistant."
val filesPath = listOf("/path/file1.txt", "/path/file2.txt")
val prompt = "This is a long prompt..."

val tokenCount = geminiApi.tokenCount(
    systemInstruction = systemInstruction,
    filesPath = filesPath,
    prompt = prompt
)

println("Total tokens: ${tokenCount.totalTokens}")
```

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request or open an issue for bugs, features, or questions.

## 📄 License

This project is licensed under the Apache 2.0 License. See the [LICENSE](LICENSE) file for details.
