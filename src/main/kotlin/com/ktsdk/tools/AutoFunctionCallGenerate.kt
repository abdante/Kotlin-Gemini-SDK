package com.ktsdk.tools

import com.ktsdk.models.FunctionDescription
import com.ktsdk.models.FunctionParameters
import com.ktsdk.models.GeminiRequest
import com.ktsdk.models.GeminiResponse
import com.ktsdk.models.ItemsDetail
import com.ktsdk.models.PropertySchema
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KType
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.valueParameters

// Annotation for the function itself
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class GeminiFunction(
    val description: String
    // Optional: You could add a custom name here if you don't want to use the Kotlin function name
    // val name: String = ""
)

// Annotation for function parameters
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class GeminiParameter(
    val description: String=""
    // Optional: You could specify the type explicitly if needed, but we'll try to infer it
    // val type: String = ""
)

// Annotation (Optional but Recommended) for specifying enum choices
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class GeminiEnumValues(
    val values: Array<String>
)


object FunctionSchemaGenerator {

    fun generateSchema(func: KFunction<*>): FunctionDescription {
        val functionAnnotation = func.findAnnotation<GeminiFunction>()
            ?: throw IllegalArgumentException("Function ${func.name} must be annotated with @GeminiFunction")

        val functionName = func.name // Use Kotlin function name directly
        val functionDescription = functionAnnotation.description

        val properties = mutableMapOf<String, PropertySchema>()
        val required = mutableListOf<String>()

        // Iterate over value parameters (excluding extension/dispatch receivers)
        func.valueParameters.forEach { param ->
            val paramName = param.name
                ?: throw IllegalArgumentException("Parameter in ${func.name} has no name.")
            val paramAnnotation = param.findAnnotation<GeminiParameter>()
            //?: throw IllegalArgumentException("Parameter '$paramName' in ${func.name} must be annotated with @GeminiParameter") // Make description optional?
            val paramDescription = paramAnnotation?.description

            val schemaInfo = mapKotlinTypeToJsonSchema(param.type)

            val enumAnnotation = param.findAnnotation<GeminiEnumValues>()
            val enumValues = enumAnnotation?.values?.toList()


            properties[paramName] = PropertySchema(
                type = schemaInfo.type.lowercase(),
                description = paramDescription,
                items = schemaInfo.items,
                enum = enumValues
            )

            // Determine if required: non-nullable parameters without default values
            if (!param.isOptional && !param.type.isMarkedNullable) {
                required.add(paramName)
            }
        }

        return FunctionDescription(
            name = functionName,
            description = functionDescription,
            parameters = FunctionParameters(
                type = "object",
                properties = properties,
                required = required.sorted() // Sort for consistency
            )
        )
    }

    private data class SchemaInfo(val type: String, val items: ItemsDetail? = null)

    private fun mapKotlinTypeToJsonSchema(type: KType): SchemaInfo {
        val classifier = type.classifier

        return when (classifier) {
            String::class -> SchemaInfo("string")
            Int::class, Long::class, Short::class, Byte::class -> SchemaInfo("integer")
            Double::class, Float::class -> SchemaInfo("number")
            Boolean::class -> SchemaInfo("boolean")
            List::class, Set::class, Array::class -> {
                // Get the type of the list elements
                val itemType = type.arguments.firstOrNull()?.type
                    ?: throw IllegalArgumentException("Cannot determine item type for collection type $type")
                val itemSchemaInfo = mapKotlinTypeToJsonSchema(itemType)
                // Nested arrays are complex, handle basic case for now
                if (itemSchemaInfo.type == "array") {
                    throw IllegalArgumentException("Nested arrays are not directly supported yet in this basic generator.")
                }
                SchemaInfo("array", ItemsDetail(itemSchemaInfo.type))
            }
            // Basic Enum Handling (uses string type with enum constraints)
            is KClass<*> -> {
                if (classifier.isSubclassOf(Enum::class)) {
                    // We extract actual enum values via annotation @GeminiEnumValues for now,
                    // as getting them reliably via reflection alone can be tricky.
                    // We still map the *type* to string.
                    SchemaInfo("string")
                } else if (classifier.isData) {
                    // Basic support for data classes as objects - could be expanded
                    // For now, treat as a generic object or require explicit definition
                    println("Warning: Data class ${classifier.simpleName} used as parameter. Mapping to basic 'object'. Consider defining its schema explicitly if nested properties are needed by the LLM.")
                    SchemaInfo("object")
                } else {
                    throw IllegalArgumentException("Unsupported parameter type: ${classifier.simpleName}")
                }
            }

            else -> throw IllegalArgumentException("Unsupported parameter type: $type")
        }
    }
}

// Example Enum
enum class MeetingImportance {
    HIGH, MEDIUM, LOW
}


// Example functions to generate schemas for
@GeminiFunction(description = "Gets the current temperature for a given location.")
fun get_current_temperature(
    @GeminiParameter(description = "The city name, e.g. San Francisco") location: String,
    @GeminiParameter(description = "Optional unit for temperature (Celsius or Fahrenheit)") unit: String? = "Celsius" // Optional parameter
): String { // Return type doesn't affect schema generation for parameters
    // Dummy implementation
    println("Called get_current_temperature(location=$location, unit=$unit)")
    return "30 $unit"
}

@GeminiFunction(description = "Schedules a meeting with specified attendees at a given time and date.")
fun schedule_meeting(
    @GeminiParameter(description = "List of people attending the meeting.") attendees: List<String>,
    @GeminiParameter(description = "Date of the meeting (e.g., '2024-07-29')") date: String,
    @GeminiParameter(description = "Time of the meeting (e.g., '15:00')") time: String,
    @GeminiParameter(description = "The subject or topic of the meeting.") topic: String,
    @GeminiParameter(description = "Importance level of the meeting.")
    @GeminiEnumValues(values = ["HIGH", "MEDIUM", "LOW"]) // Provide enum values for LLM
    importance: MeetingImportance = MeetingImportance.MEDIUM // Has default, so not 'required' in schema
): Boolean {
    // Dummy implementation
    println("Called schedule_meeting(attendees=$attendees, date=$date, time=$time, topic=$topic, importance=$importance)")
    return true
}

@GeminiFunction(description = "Creates a bar chart given a title, labels, and corresponding values.")
fun create_bar_chart(
    @GeminiParameter(description = "The title for the chart.") title: String,
    @GeminiParameter(description = "List of labels for the data points (e.g., ['Q1', 'Q2', 'Q3']).") labels: List<String>,
    @GeminiParameter(description = "List of numerical values corresponding to the labels (e.g., [50000, 75000, 60000]).") values: List<Double> // Use Double for "number"
): String {
    // Dummy implementation
    println("Called create_bar_chart(title=$title, labels=$labels, values=$values)")
    return "Chart created successfully."
}

// --- Keep ALL your data classes from the original code AND the refined ones + annotations ---
// --- Keep the FunctionSchemaGenerator object and the annotated functions ---

/*
suspend fun main() {
    // IMPORTANT: Replace with your actual API key or load securely
    val geminiApiKey = System.getenv("GEMINI_API_KEY")

    val apiUrl =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$geminiApiKey"

    val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
                encodeDefaults = true // Important for sending default values like type:"object"
                prettyPrint = true // Makes debugging request JSON easier
            })
        }
        // Optional: Add logging for requests/responses
        // install(Logging) { level = LogLevel.ALL }
        // Optional: Increase timeout if needed
        install(HttpTimeout) {
            requestTimeoutMillis = 60000 // 60 seconds
        }
    }

    try {
        // Generate schemas automatically
        val tempFuncSchema = FunctionSchemaGenerator.generateSchema(::get_current_temperature)
        val meetingFuncSchema = FunctionSchemaGenerator.generateSchema(::schedule_meeting)
        val chartFuncSchema = FunctionSchemaGenerator.generateSchema(::create_bar_chart)

        // --- Test Schema Generation (Optional Debugging) ---
//        val json = Json { prettyPrint = true; encodeDefaults = true }
//        println("--- Generated Schema for get_current_temperature ---")
//        println(json.encodeToString(tempFuncSchema))
//        println("--- Generated Schema for schedule_meeting ---")
//        println(json.encodeToString(meetingFuncSchema))
//        println("--- Generated Schema for create_bar_chart ---")
//        println(json.encodeToString(chartFuncSchema))
//        println("-------------------------------------------------")


        // Request 1: Temperature
        val requestBody1 = GeminiRequest(
            contents = listOf(
                Content(
                    role = "user",
                    parts = listOf(Part("What's the temperature in London?"))
                )
            ),
            tools = listOf(Tool(functionDeclarations = listOf(tempFuncSchema))) // Use generated schema
        )
        println("Making request for temperature in London...")
        // Use callGemini function for clarity
        callGemini(client, apiUrl, geminiApiKey, requestBody1, "Temperature")

        // Request 2: Meeting
        val requestBody2 = GeminiRequest(
            contents = listOf(
                Content(
                    role = "user",
                    parts = listOf(Part("Schedule a meeting with Bob and Alice for 03/27/2025 at 10:00 AM about the Q3 planning."))
                )
            ),
            tools = listOf(Tool(functionDeclarations = listOf(meetingFuncSchema))) // Use generated schema
        )
        println("\nMaking request for scheduling a meeting...")
        callGemini(client, apiUrl, geminiApiKey, requestBody2, "Meeting")

        // Request 3: Bar Chart
        val requestBody3 = GeminiRequest(
            contents = listOf(
                Content(
                    role = "user",
                    parts = listOf(Part("Create a bar chart titled 'Quarterly Sales' with data: Q1: 50000, Q2: 75000, Q3: 60000."))
                )
            ),
            tools = listOf(Tool(functionDeclarations = listOf(chartFuncSchema))) // Use generated schema
        )
        println("\nMaking request for creating a bar chart...")
        callGemini(client, apiUrl, geminiApiKey, requestBody3, "Bar Chart")

        // Example: Call with multiple functions available
        val requestBodyMulti = GeminiRequest(
            contents = listOf(
                Content(
                    role = "user",
                    parts = listOf(Part("What's the weather in Paris and can you also schedule a quick sync with Carol for tomorrow at 2 PM about the project status?"))
                )
            ),
            tools = listOf(
                Tool(
                    functionDeclarations = listOf(
                        tempFuncSchema,
                        meetingFuncSchema
                    )
                )
            ) // Provide multiple schemas
        )
        println("\nMaking request with multiple functions (Weather & Meeting)...")
        callGemini(client, apiUrl, geminiApiKey, requestBodyMulti, "Multi-Function")


    } catch (e: Exception) {
        println("Error: ${e.message}")
        e.printStackTrace()
    } finally {
        client.close()
    }
}
*/

// Helper function to make the API call and print response
suspend fun callGemini(
    client: HttpClient,
    apiUrl: String,
    apiKey: String,
    requestBody: GeminiRequest,
    requestType: String
) {
    try {
//        val apiUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey"
        // val apiUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-pro:generateContent?key=$apiKey" // Try gemini-pro if flash has issues
        val response = client.post(apiUrl) {
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }
        val encodeToString = Json {
            prettyPrint = true
            encodeDefaults = true
        }
        println(
            "$requestType Request Body JSON:\n${encodeToString.encodeToString(requestBody)}"
        )
        println("$requestType Response Status: ${response.status}")
        val responseText = response.bodyAsText()
        println("$requestType Response Body:\n$responseText")

        // Optional: Attempt to parse the response
        try {
            val json = Json { ignoreUnknownKeys = true }
            val responseJson = json.decodeFromString<GeminiResponse>(responseText)
            // Process parsed response if needed (e.g., check for function calls)
            responseJson.candidates?.forEach { candidate ->
                if (candidate.content?.parts?.any { it.functionCall != null } == true) { // Assuming functionCall property exists in .Part (needs update)
                    println(">>> Function call detected in response!")
                    candidate.content.parts.forEach { part ->
                        println(part)
                    }
                    // Add logic here to handle the function call:
                    // 1. Extract function name and arguments.
                    // 2. Find the corresponding Kotlin function (e.g., using a map).
                    // 3. Execute the Kotlin function with the arguments.
                    // 4. Send the function result back to Gemini in a subsequent API call.
                }
            }
        } catch (e: SerializationException) {
            println("Could not parse $requestType response body as JSON: ${e.message}")
        } catch (e: Exception) {
            println("Error processing $requestType response: ${e.message}")
        }


    } catch (e: Exception) {
        println("Error during $requestType API call: ${e.message}")
        // Potentially print stack trace for detailed errors during development
        // e.printStackTrace()
    }
}


// Make sure ResponseBodyx, Candidatex, .Content use the updated .Part
// Your existing ResponseBodyx structure might already handle this if 'content'
// uses the updated .Content/.Part. Double check .Content uses List<.Part>.
