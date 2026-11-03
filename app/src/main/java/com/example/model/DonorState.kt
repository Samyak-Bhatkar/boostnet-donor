package com.example.model

enum class DonorState(val label: String) {
    STOPPED("Stopped"),
    ACTIVE("Active"),
    THROTTLED("Throttled"),
    DRAINING("Draining");

    val isRunning: Boolean
        get() = this != STOPPED
}
