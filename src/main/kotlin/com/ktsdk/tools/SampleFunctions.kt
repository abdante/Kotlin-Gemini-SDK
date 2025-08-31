package com.ktsdk.tools

@GeminiFunction(description = "save contacts (phone number and name, save in any languages user speaks")
fun contactSaver(
    @GeminiParameter()
    phoneNumber: String,
    @GeminiParameter()
    name: String,
): String {
    println("the phone number is $phoneNumber and the name is $name")
    return "phone number received"
}