package com.satory.graphenosai.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Kotlin client for llama.cpp local inference
 * Mirrors the interface of OpenRouterClient
 */
class LlamaCppClient {

    companion object {
        private const val TAG = "LlamaCppClient"
        
        // Default context size optimized for mobile
        const val DEFAULT_CONTEXT_SIZE = 2048
        
        // Default temperature aligned with cloud client for factual responses
        const val DEFAULT_TEMPERATURE = 0.4f
        
        // Top-p sampling
        const val DEFAULT_TOP_P = 0.9f
        
        // Max generation tokens
        const val DEFAULT_MAX_TOKENS = 1024
        
        // ChatML stop tokens to filter from output
        private val STOP_TOKENS_CHATML = listOf(
            "<|im_end|>",
            "<|im_start|>",
            "<|im_start|>user",
            "<|im_start|>assistant",
            "<|im_start|>system",
            "<|endoftext|>",
            "<|eot_id|>",
            "<|end|>"
        )

        // Gemma format stop tokens
        private val STOP_TOKENS_GEMMA = listOf(
            "<end_of_turn>",
            "<start_of_turn>",
            "<turn|>",
            "<|think|>"
        )
        
        val DEFAULT_SYSTEM_PROMPT = SystemPrompts.LOCAL_DEFAULT
        
        /**
         * Clean output by removing any format tokens that leaked through
         */
        fun cleanOutput(text: String, format: String = "chatml"): String {
            var cleaned = text
            val tokens = if (format == "gemma") STOP_TOKENS_GEMMA else STOP_TOKENS_CHATML
            for (token in tokens) {
                cleaned = cleaned.replace(token, "")
            }
            if (format == "chatml") {
                cleaned = cleaned.replace(Regex("<\\|im_[^>]*>?"), "")
                cleaned = cleaned.replace(Regex("<\\|[^>]*\\|>"), "")
            } else {
                cleaned = cleaned.replace(Regex("<[^>]*>"), "")
            }
            return cleaned.trim()
        }
    }
    
    // Chat session for context
    val chatSession = ChatSession()
    
    // Current model info
    private var currentModelPath: String? = null
    private var currentModelName: String = "Local Model"
    private var currentModelFormat: String = "chatml"
    private var systemPrompt: String = DEFAULT_SYSTEM_PROMPT
    
    // Generation parameters
    var temperature: Float = DEFAULT_TEMPERATURE
    var topP: Float = DEFAULT_TOP_P
    var maxTokens: Int = DEFAULT_MAX_TOKENS
    var contextSize: Int = DEFAULT_CONTEXT_SIZE
    
    /**
     * Get the active stop tokens based on current model format
     */
    private fun getStopTokens(): List<String> {
        return if (currentModelFormat == "gemma") STOP_TOKENS_GEMMA else STOP_TOKENS_CHATML
    }

    /**
     * Check if llama.cpp is available on this device
     */
    fun isAvailable(): Boolean = LlamaCppBridge.isAvailable()
    
    /**
     * Initialize the llama.cpp backend
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (!LlamaCppBridge.isAvailable()) {
            Log.e(TAG, "Native library not available")
            return@withContext false
        }
        LlamaCppBridge.nativeInit()
    }
    
    /**
     * Load a GGUF model from file
     */
    suspend fun loadModel(modelPath: String, modelName: String = "Local Model", modelFormat: String = "chatml"): Result<Unit> = withContext(Dispatchers.IO) {
        if (!LlamaCppBridge.isAvailable()) {
            return@withContext Result.failure(Exception("Native library not available"))
        }
        
        Log.i(TAG, "Loading model: $modelPath")
        
        // Determine optimal thread count (leave 1 core for UI)
        val threads = maxOf(1, Runtime.getRuntime().availableProcessors() - 1)
        
        val success = LlamaCppBridge.nativeLoadModel(
            modelPath = modelPath,
            nCtx = contextSize,
            nThreads = threads,
            useGpu = false  // No GPU support on most Android devices
        )
        
        if (success) {
            currentModelPath = modelPath
            currentModelName = modelName
            currentModelFormat = modelFormat
            Log.i(TAG, "Model loaded successfully: $modelName")
            Result.success(Unit)
        } else {
            Log.e(TAG, "Failed to load model")
            Result.failure(Exception("Failed to load model"))
        }
    }
    
    /**
     * Unload the current model
     */
    fun unloadModel() {
        if (LlamaCppBridge.isAvailable()) {
            LlamaCppBridge.nativeUnloadModel()
            currentModelPath = null
            Log.i(TAG, "Model unloaded")
        }
    }
    
    /**
     * Check if a model is loaded
     */
    fun isModelLoaded(): Boolean {
        return LlamaCppBridge.isAvailable() && LlamaCppBridge.nativeIsModelLoaded()
    }
    
    /**
     * Get current model name
     */
    fun getModelName(): String = currentModelName
    
    /**
     * Set system prompt
     */
    fun setSystemPrompt(prompt: String) {
        systemPrompt = prompt
    }
    
    /**
     * Clear chat session
     */
    fun clearSession() {
        chatSession.clear()
    }
    
    /**
     * Stream completion with callback (matches OpenRouterClient interface)
     */
    fun streamCompletion(
        userQuery: String,
        imageBase64: String? = null  // Ignored for local models (no vision support)
    ): Flow<String> = callbackFlow {
        if (!isModelLoaded()) {
            trySend("[No local model loaded. Please download a model in Settings.]")
            close()
            return@callbackFlow
        }
        
        // Build prompt from chat session (user message already added by AssistantService)
        val fullPrompt = buildPrompt()
        
        Log.i(TAG, "Generating with prompt length: ${fullPrompt.length}")
        
        // Buffer to detect stop sequences across token boundaries
        val tokenBuffer = StringBuilder()
        var shouldStop = false
        
        // Generate with streaming callback
        val callback = object : LlamaCppBridge.TokenCallback {
            override fun onToken(token: String) {
                if (shouldStop) return
                
                tokenBuffer.append(token)
                val bufferStr = tokenBuffer.toString()
                
                val activeStopTokens = getStopTokens()
                for (stopToken in activeStopTokens) {
                    if (bufferStr.contains(stopToken)) {
                        shouldStop = true
                        // Send only the part before the stop token
                        val idx = bufferStr.indexOf(stopToken)
                        if (idx > 0) {
                            val cleanPart = bufferStr.substring(0, idx)
                            if (cleanPart.isNotEmpty()) {
                                trySend(cleanPart)
                            }
                        }
                        LlamaCppBridge.nativeStopGeneration()
                        return
                    }
                }
                
                // Check for partial stop sequence at end - hold back potential matches
                var holdBack = 0
                for (stopToken in activeStopTokens) {
                    for (i in 1 until minOf(stopToken.length, bufferStr.length + 1)) {
                        if (bufferStr.endsWith(stopToken.substring(0, i))) {
                            holdBack = maxOf(holdBack, i)
                        }
                    }
                }
                
                // Send the safe portion
                if (bufferStr.length > holdBack) {
                    val safeToSend = bufferStr.substring(0, bufferStr.length - holdBack)
                    trySend(safeToSend)
                    tokenBuffer.clear()
                    tokenBuffer.append(bufferStr.substring(bufferStr.length - holdBack))
                }
            }
        }
        
        val result = LlamaCppBridge.nativeGenerate(
            prompt = fullPrompt,
            maxTokens = maxTokens,
            temperature = temperature,
            topP = topP,
            callback = callback
        )
        
        // Clean and add assistant response to session
        val cleanedResult = cleanOutput(result, currentModelFormat)
        chatSession.addAssistantMessage(cleanedResult)
        
        close()
        
        awaitClose {
            // Cleanup if needed
        }
    }.flowOn(Dispatchers.IO)
    
    /**
     * Stream completion without adding user message (already added externally)
     */
    fun streamCompletionDirect(
        prompt: String,
        imageBase64: String? = null
    ): Flow<String> = callbackFlow {
        if (!isModelLoaded()) {
            trySend("[No local model loaded]")
            close()
            return@callbackFlow
        }
        
        // Build full prompt with context
        val fullPrompt = buildPromptFromContext(prompt)
        
        // Buffer to detect stop sequences across token boundaries
        val tokenBuffer = StringBuilder()
        var shouldStop = false
        
        val callback = object : LlamaCppBridge.TokenCallback {
            override fun onToken(token: String) {
                if (shouldStop) return
                
                tokenBuffer.append(token)
                val bufferStr = tokenBuffer.toString()
                
                val activeStopTokens = getStopTokens()
                for (stopToken in activeStopTokens) {
                    if (bufferStr.contains(stopToken)) {
                        shouldStop = true
                        val idx = bufferStr.indexOf(stopToken)
                        if (idx > 0) {
                            trySend(bufferStr.substring(0, idx))
                        }
                        LlamaCppBridge.nativeStopGeneration()
                        return
                    }
                }
                
                // Check for partial stop sequence at end
                var holdBack = 0
                for (stopToken in activeStopTokens) {
                    for (i in 1 until minOf(stopToken.length, bufferStr.length + 1)) {
                        if (bufferStr.endsWith(stopToken.substring(0, i))) {
                            holdBack = maxOf(holdBack, i)
                        }
                    }
                }
                
                if (bufferStr.length > holdBack) {
                    val safeToSend = bufferStr.substring(0, bufferStr.length - holdBack)
                    trySend(safeToSend)
                    tokenBuffer.clear()
                    tokenBuffer.append(bufferStr.substring(bufferStr.length - holdBack))
                }
            }
        }
        
        val result = LlamaCppBridge.nativeGenerate(
            prompt = fullPrompt,
            maxTokens = maxTokens,
            temperature = temperature,
            topP = topP,
            callback = callback
        )
        
        // Clean and add assistant response to session
        val cleanedResult = cleanOutput(result, currentModelFormat)
        chatSession.addAssistantMessage(cleanedResult)
        close()
        
        awaitClose { }
    }.flowOn(Dispatchers.IO)
    
    /**
     * Stop ongoing generation
     */
    fun stopGeneration() {
        if (LlamaCppBridge.isAvailable()) {
            LlamaCppBridge.nativeStopGeneration()
        }
    }
    
    /**
     * Get model info as JSON
     */
    fun getModelInfo(): String {
        return if (LlamaCppBridge.isAvailable()) {
            LlamaCppBridge.nativeGetModelInfo()
        } else {
            "{\"error\":\"Native library not available\"}"
        }
    }
    
    /**
     * Get memory usage in bytes
     */
    fun getMemoryUsage(): Long {
        return if (LlamaCppBridge.isAvailable()) {
            LlamaCppBridge.nativeGetMemoryUsage()
        } else {
            0L
        }
    }
    
    /**
     * Build a full prompt including system prompt and chat history
     * Supports ChatML and Gemma prompt formats
     */
    private fun buildPrompt(): String {
        val sb = StringBuilder()
        
        if (currentModelFormat == "gemma") {
            sb.append("<start_of_turn>system\n")
            sb.append(systemPrompt)
            sb.append("<end_of_turn>\n")
            
            for (msg in chatSession.getAllMessages()) {
                when (msg.role) {
                    "user" -> {
                        sb.append("<start_of_turn>user\n")
                        sb.append(msg.content)
                        sb.append("<end_of_turn>\n")
                    }
                    "assistant" -> {
                        sb.append("<start_of_turn>model\n")
                        sb.append(msg.content)
                        sb.append("<end_of_turn>\n")
                    }
                }
            }
            
            sb.append("<start_of_turn>model\n")
        } else {
            sb.append("<|im_start|>system\n")
            sb.append(systemPrompt)
            sb.append("<|im_end|>\n")
            
            for (msg in chatSession.getAllMessages()) {
                when (msg.role) {
                    "user" -> {
                        sb.append("<|im_start|>user\n")
                        sb.append(msg.content)
                        sb.append("<|im_end|>\n")
                    }
                    "assistant" -> {
                        sb.append("<|im_start|>assistant\n")
                        sb.append(msg.content)
                        sb.append("<|im_end|>\n")
                    }
                }
            }
            
            sb.append("<|im_start|>assistant\n")
        }
        
        return sb.toString()
    }
    
    /**
     * Build prompt from existing context (for web search augmented queries)
     */
    private fun buildPromptFromContext(enhancedPrompt: String): String {
        val sb = StringBuilder()
        
        if (currentModelFormat == "gemma") {
            sb.append("<start_of_turn>system\n")
            sb.append(systemPrompt)
            sb.append("<end_of_turn>\n")
            
            val messages = chatSession.getAllMessages()
            for (i in 0 until maxOf(0, messages.size - 1)) {
                val msg = messages[i]
                when (msg.role) {
                    "user" -> {
                        sb.append("<start_of_turn>user\n")
                        sb.append(msg.content)
                        sb.append("<end_of_turn>\n")
                    }
                    "assistant" -> {
                        sb.append("<start_of_turn>model\n")
                        sb.append(msg.content)
                        sb.append("<end_of_turn>\n")
                    }
                }
            }
            
            sb.append("<start_of_turn>user\n")
            sb.append(enhancedPrompt)
            sb.append("<end_of_turn>\n")
            sb.append("<start_of_turn>model\n")
        } else {
            sb.append("<|im_start|>system\n")
            sb.append(systemPrompt)
            sb.append("<|im_end|>\n")
            
            val messages = chatSession.getAllMessages()
            for (i in 0 until maxOf(0, messages.size - 1)) {
                val msg = messages[i]
                when (msg.role) {
                    "user" -> {
                        sb.append("<|im_start|>user\n")
                        sb.append(msg.content)
                        sb.append("<|im_end|>\n")
                    }
                    "assistant" -> {
                        sb.append("<|im_start|>assistant\n")
                        sb.append(msg.content)
                        sb.append("<|im_end|>\n")
                    }
                }
            }
            
            sb.append("<|im_start|>user\n")
            sb.append(enhancedPrompt)
            sb.append("<|im_end|>\n")
            sb.append("<|im_start|>assistant\n")
        }
        
        return sb.toString()
    }
}
