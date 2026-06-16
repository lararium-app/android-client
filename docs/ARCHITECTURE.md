# Architecture Overview

Privacy-focused Android AI assistant (`com.satory.graphenosai`). Integrates as a system digital assistant with volume-key activation, voice/text input, optional web search, and cloud or on-device LLM backends.

## Project Structure

```
app/src/main/
├── java/com/satory/graphenosai/
│   ├── AssistantApplication.kt
│   ├── MainActivity.kt                    # Setup dashboard + navigation
│   ├── service/
│   │   ├── AssistantService.kt            # Central orchestrator
│   │   ├── AssistantAccessibilityService.kt
│   │   ├── AssistantVoiceInteractionService.kt
│   │   ├── AssistantVoiceInteractionSessionService.kt
│   │   └── AssistantTileService.kt
│   ├── llm/
│   │   ├── OpenRouterClient.kt            # Cloud LLM (streaming, vision, tools)
│   │   ├── LlamaCppClient.kt              # Local llama.cpp inference
│   │   ├── LlamaCppBridge.kt              # JNI bridge
│   │   ├── LocalModelManager.kt           # GGUF download/management
│   │   ├── ChatSession.kt                 # Conversation context
│   │   ├── SearchIntent.kt                # Weather/web intent classifier
│   │   ├── SystemPrompts.kt               # Shared default prompts
│   │   └── ToolCall.kt                    # web_search tool + DSML parsing
│   ├── audio/
│   │   ├── VoskTranscriber.kt             # Offline STT
│   │   ├── WhisperTranscriber.kt          # Cloud Whisper (Groq/OpenAI)
│   │   ├── SpeechRecognizerManager.kt     # Android system STT
│   │   └── AudioCaptureManager.kt
│   ├── tts/TTSManager.kt
│   ├── search/                            # Brave, Exa, LangSearch clients
│   ├── weather/OpenMeteoClient.kt
│   ├── storage/ChatHistoryManager.kt
│   ├── security/SecureKeyManager.kt       # Android Keystore encryption
│   └── ui/
│       ├── CompactAssistantUI.kt          # Primary assistant overlay
│       ├── SettingsScreen.kt
│       ├── SettingsManager.kt
│       ├── VoskLanguageManagerScreen.kt
│       └── StateIndicator.kt
└── cpp/llama/                             # llama.cpp JNI (arm64-v8a)
```

## Activation Flow

```
Volume Up+Down / Triple Power / Accessibility button / Default assistant / QS tile
    → AssistantService (foreground)
    → CompactAssistantActivity (Compose overlay)
```

## Query Processing Pipeline

```
User input (text or voice→text)
    → sanitizeQuery (PII redaction for cloud)
    → classifyRetrievalIntent
        ├─ WEATHER  → OpenMeteo → LLM with weather context
        ├─ WEB_SEARCH → Search API → LLM with results
        └─ NONE → function calling (web_search tool) or direct stream
    → finalizeResponse (sources, URLs, TTS)
```

Local provider (`PROVIDER_LOCAL`) skips search/weather/tools and uses `LlamaCppClient`.

## LLM Backends

### OpenRouter (cloud)
- Streaming SSE, model list in `SettingsManager.AVAILABLE_MODELS`
- Vision for supported models
- Tool calling with DSML fallback
- Default: `temperature 0.3`, `max_tokens 4096`

### Local llama.cpp
- GGUF models via `LocalModelManager`
- Requires prebuilt native libs (`scripts/build-llama-android.sh`)
- ChatML / Gemma prompt formats
- Default: `temperature 0.4`, `max_tokens 1024`

## Speech

| Backend | Class | Notes |
|---------|-------|-------|
| Vosk | `VoskTranscriber` | Offline, multilingual models |
| System | `SpeechRecognizerManager` | Android built-in |
| Whisper | `WhisperTranscriber` | Groq or OpenAI API |

TTS via Android `TextToSpeech` in `TTSManager`.

## Search

- **Brave Search** (default), **Exa**, **LangSearch** — selectable in Settings
- Intent classifier in `SearchIntent.kt` routes obvious live-data queries before the model answers from memory
- Fallback when model claims no web access (`shouldFallbackToSearch`)

## Security

- API keys encrypted with Android Keystore (`SecureKeyManager`)
- PII sanitization before cloud requests (device IDs, phones, emails)
- No analytics or tracking
- TLS for all network calls (`network_security_config.xml`)

## Context Management

`ChatSession` keeps up to 30 messages (~16K estimated tokens). Older messages are trimmed from the start.

## Build

- **minSdk** 26, **targetSdk** 36, **arm64-v8a** only for native code
- Release: ProGuard enabled
- Local AI dev: run `scripts/build-llama-android.sh` if prebuilt `.so` files are missing

See [LOCAL_AI_SETUP.md](LOCAL_AI_SETUP.md) for model download and native library setup.