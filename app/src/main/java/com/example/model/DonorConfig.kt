package com.example.model

data class DonorConfig(
    val nodeId: String,
    val displayName: String,
    val carrier: String, // "Jio", "Airtel", "Vi", "BSNL", "Other"
    val tech: String, // "4G", "5G"
    val quotaLimitBytes: Long,
    val socksPort: Int = 1080,
    val ctrlPort: Int = 9998,
    val broadcastPort: Int = 9999,
    val blocklist: List<String> = emptyList()
)
