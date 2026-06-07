package helium314.keyboard.latin.voice

enum class VoiceUiState {
    IDLE,
    RECORDING,
    TRANSCRIBING,
    REFORMATTING,
    PREVIEW,
    ERROR,
    PERMISSION_DENIED,
    NO_PROVIDER,
}
