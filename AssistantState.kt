package com.example.model

enum class AssistantStatus {
    IDLE,
    RUNNING,
    PAUSED,
    STOPPED,
    ERROR
}

data class RealSuggestionInfo(
    val name: String? = null,
    val profileUrl: String? = null,
    val uid: String? = null,
    val mutualInfo: String? = null,
    val domIndex: Int = 0
)

data class FriendAssistantState(
    val status: AssistantStatus = AssistantStatus.IDLE,
    val sentCount: Int = 0,
    val skippedCount: Int = 0,
    val failedCount: Int = 0,
    val batchSentCount: Int = 0,
    val batchTarget: Int = 100, // Updated from 50 to 100
    val isAutoSending: Boolean = false,
    val showBatchPrompt: Boolean = false,
    val nextDelaySeconds: Int = 0,
    val currentSuggestion: RealSuggestionInfo? = null,
    val isScanning: Boolean = false,
    val isExecutingAction: Boolean = false,
    val showConfirmDialog: Boolean = false,
    val statusMessage: String? = null,
    val isSecurityWarning: Boolean = false
) {
    val totalProcessed: Int
        get() = sentCount + skippedCount + failedCount
}
