package com.astracam.dualdevice.device

enum class DeviceProfileType {
    H18_WATCH,
    REDMI_NOTE_9S,
    GENERIC_PHONE
}

data class DeviceProfile(
    val type: DeviceProfileType,
    val isRoundScreen: Boolean,
    val maxProcessingDimension: Int,
    val enableMultiFrameHdr: Boolean,
    val enableAggressiveDenoising: Boolean,
    val memoryBudgetMb: Int
) {
    companion object {
        fun h18WatchProfile(): DeviceProfile = DeviceProfile(
            type = DeviceProfileType.H18_WATCH,
            isRoundScreen = true,
            maxProcessingDimension = 1280,
            enableMultiFrameHdr = false,
            enableAggressiveDenoising = false,
            memoryBudgetMb = 48
        )

        fun redmiNote9SProfile(): DeviceProfile = DeviceProfile(
            type = DeviceProfileType.REDMI_NOTE_9S,
            isRoundScreen = false,
            maxProcessingDimension = 4000,
            enableMultiFrameHdr = true,
            enableAggressiveDenoising = true,
            memoryBudgetMb = 256
        )

        fun genericPhoneProfile(): DeviceProfile = DeviceProfile(
            type = DeviceProfileType.GENERIC_PHONE,
            isRoundScreen = false,
            maxProcessingDimension = 2048,
            enableMultiFrameHdr = false,
            enableAggressiveDenoising = true,
            memoryBudgetMb = 96
        )
    }
}