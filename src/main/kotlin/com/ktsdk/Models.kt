package com.ktsdk

enum class Models(val modelName: String) {
    GEMINI_2_0_FLASH_001(modelName = "gemini-2.0-flash-001"),
    GEMINI_2_5_PRO_EXPERIMENTAL_05_06(modelName = "gemini-2.5-pro-exp-05-06"),
    GEMINI_2_5_PRO_EXPERIMENTAL_03_25(modelName = "gemini-2.5-pro-exp-25-03"),
    GEMINI_2_5_PRO_PREVIEW_05_06(modelName = "gemini-2.5-pro-preview-05-06"),
    GEMINI_2_5_FLASH_EXPERIMENTAL_04_17(modelName = "gemini-2.5-flash-exp-04-17"),
    GEMINI_2_5_FLASH_EXPERIMENTAL_05_20(modelName = "gemini-2.5-flash-exp-05-20"),
    GEMINI_2_5_FLASH(modelName = "gemini-2.5-flash"),
    GEMINI_2_5_FLASH_LIVE_PREVIEW(modelName = "gemini-2.5-flash-live-preview"),
    GEMINI_2_0_EXPERIMENTAL(modelName = "gemini-2.0-flash-exp"),
    GEMINI_2_0_LIVE(modelName = "gemini-2.0-flash-live-001"),
    GEMINI_2_0_IMAGE_PREVIEW(modelName = "gemini-2.0-flash-preview-image-generation"),
    GEMINI_2_5_FLASH_LIVE_PREVIEW_NATIVE_AUDIO(modelName = "gemini-2.5-flash-preview-native-audio-dialog"),
    GEMINI_2_5_FLASH_THINKING_EXP_LIVE_PREVIEW(modelName = "gemini-2.5-flash-exp-native-audio-thinking-dialog")
}