package com.pagerelay.bot.ai

import com.pagerelay.bot.domain.AppSettings
import com.pagerelay.bot.domain.GeminiTestResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

data class ConversationTurn(
    val isFromUser: Boolean,
    val text: String
)

class GeminiReplyClient(
    private val httpClient: OkHttpClient = defaultHttpClient()
) {

    suspend fun generateReply(
        apiKey: String,
        model: String,
        systemPrompt: String,
        senderName: String,
        history: List<ConversationTurn>,
        latestMessage: String,
        openingMessage: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Gemini API key is not configured."))
        }
        val trimmedMessage = latestMessage.trim()
        if (trimmedMessage.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Incoming message text is empty."))
        }

        val normalizedModel = normalizeModelName(model)

        try {
            val url = HttpUrl.Builder()
                .scheme("https")
                .host("generativelanguage.googleapis.com")
                .addPathSegment("v1beta")
                .addPathSegment("models")
                .addPathSegment("$normalizedModel:generateContent")
                .build()

            val payload = buildRequestPayload(
                model = normalizedModel,
                systemPrompt = systemPrompt,
                senderName = senderName,
                history = history,
                latestMessage = trimmedMessage,
                openingMessage = openingMessage
            )

            val request = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("Accept", "application/json")
                .header("x-goog-api-key", trimmedKey)
                .build()

            httpClient.newCall(request).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val apiError = extractErrorMessage(rawBody)
                    val message = buildString {
                        append("Gemini HTTP ")
                        append(response.code)
                        if (!apiError.isNullOrBlank()) {
                            append(": ")
                            append(apiError)
                        }
                    }
                    return@withContext Result.failure(IOException(message))
                }

                val parsedReply = extractCandidateText(rawBody)
                if (parsedReply.isNullOrBlank()) {
                    return@withContext Result.failure(
                        IllegalStateException("Gemini returned an empty response.")
                    )
                }

                Result.success(clampReplyText(parsedReply))
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (ste: SocketTimeoutException) {
            Result.failure(
                IOException("Gemini request timed out after 60s for $normalizedModel. Try gemini-3.8-flash or check emulator internet.", ste)
            )
        } catch (t: Throwable) {
            Result.failure(
                IllegalStateException(t.message ?: "Unexpected Gemini client error", t)
            )
        }
    }

    suspend fun testConnection(apiKey: String, model: String): GeminiTestResult {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isBlank()) {
            return GeminiTestResult(
                success = false,
                message = "Enter a Gemini API key before testing the connection."
            )
        }

        val normalizedModel = normalizeModelName(model)
        val result = generateReply(
            apiKey = trimmedKey,
            model = normalizedModel,
            systemPrompt = "You are a connectivity probe. Reply concisely in one short sentence.",
            senderName = "PageRelay Diagnostics",
            history = emptyList(),
            latestMessage = "Respond with: PageRelay Gemini connection verified."
        )

        return result.fold(
            onSuccess = { reply ->
                GeminiTestResult(
                    success = true,
                    message = "Connected to $normalizedModel — \"$reply\""
                )
            },
            onFailure = { error ->
                GeminiTestResult(
                    success = false,
                    message = error.message ?: "Failed to connect to Gemini API."
                )
            }
        )
    }

    private fun buildRequestPayload(
        model: String,
        systemPrompt: String,
        senderName: String,
        history: List<ConversationTurn>,
        latestMessage: String,
        openingMessage: String? = null
    ): JSONObject {
        val root = JSONObject()

        val preparedTurns = prepareConversationTurns(
            history = history,
            latestMessage = latestMessage,
            openingMessage = openingMessage,
            maxTurns = MAX_HISTORY_TURNS
        )
        val effectiveOpening = openingMessage?.trim()?.takeIf { it.isNotEmpty() }
            ?: history.firstOrNull { it.isFromUser && it.text.isNotBlank() }?.text?.trim()

        val effectivePrompt = buildEffectiveSystemPrompt(
            systemPrompt = systemPrompt,
            senderName = senderName,
            openingMessage = effectiveOpening,
            totalTurns = preparedTurns.size
        )

        root.put(
            "system_instruction",
            JSONObject().put(
                "parts",
                JSONArray().put(JSONObject().put("text", effectivePrompt))
            )
        )

        val contents = JSONArray()
        for (turn in preparedTurns) {
            contents.put(
                JSONObject()
                    .put("role", if (turn.isFromUser) "user" else "model")
                    .put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", turn.text))
                    )
            )
        }

        root.put("contents", contents)
        val generationConfig = JSONObject()
            .put("temperature", 0.4)
            .put("maxOutputTokens", 1024)
        if (model.startsWith("gemini-3", ignoreCase = true)) {
            generationConfig.put(
                "thinkingConfig",
                JSONObject().put("thinkingLevel", "low")
            )
        }
        root.put("generationConfig", generationConfig)
        return root
    }

    private fun extractCandidateText(rawJson: String): String? {
        if (rawJson.isBlank()) return null
        return try {
            val root = JSONObject(rawJson)
            val candidates = root.optJSONArray("candidates") ?: return null
            if (candidates.length() == 0) return null
            val firstCandidate = candidates.optJSONObject(0) ?: return null
            val content = firstCandidate.optJSONObject("content") ?: return null
            val parts = content.optJSONArray("parts") ?: return null

            val buffer = StringBuilder()
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                if (part.optBoolean("thought", false)) continue
                val text = part.optString("text", "")
                if (text.isNotBlank()) {
                    if (buffer.isNotEmpty()) buffer.append(' ')
                    buffer.append(text.trim())
                }
            }
            buffer.toString().trim().takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractErrorMessage(rawJson: String): String? {
        if (rawJson.isBlank()) return null
        return try {
            val root = JSONObject(rawJson)
            root.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeModelName(model: String): String {
        val cleaned = model.trim()
            .removePrefix("models/")
            .trim()
        return cleaned.ifBlank { AppSettings.DEFAULT_GEMINI_MODEL }
    }

    private fun clampReplyText(text: String): String {
        val singleSpaced = text.replace(Regex("""\s+"""), " ").trim()
        return if (singleSpaced.length <= MAX_REPLY_CHARS) {
            singleSpaced
        } else {
            singleSpaced.take(MAX_REPLY_CHARS - 1).trimEnd() + "…"
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        internal const val MAX_HISTORY_TURNS = 40
        private const val MAX_REPLY_CHARS = 480
        private const val DEFAULT_SYSTEM_PROMPT =
            "You are a polite, concise assistant replying on behalf of a Facebook Page."

        internal fun buildEffectiveSystemPrompt(
            systemPrompt: String,
            senderName: String,
            openingMessage: String?,
            totalTurns: Int
        ): String = buildString {
            append(systemPrompt.trim().ifBlank { DEFAULT_SYSTEM_PROMPT })
            if (senderName.isNotBlank()) {
                append("\nSender display name: ")
                append(senderName.trim())
            }
            append(
                "\nConversation context rule: Always re-read the entire conversation starting from the very first message before replying. " +
                    "The sender may send short or rapid follow-up messages (such as countdown numbers like \"1\", \"2\", \"3\", or brief prompts) " +
                    "whose meaning depends directly on the opening message and earlier turns."
            )
            val cleanOpening = openingMessage?.trim().orEmpty()
            if (cleanOpening.isNotBlank() && totalTurns > 1) {
                append("\nOpening message of this conversation (always keep this context in mind): \"")
                append(cleanOpening.take(400))
                append("\"")
            }
            append("\nKeep replies plain-text, concise (1-3 sentences), and suitable for Facebook Messenger inline replies.")
        }

        /**
         * Prepares the full conversation turns for Gemini:
         * 1. Always preserves the opening user message so long/burst threads never lose initial context.
         * 2. Ensures the latest incoming message is included once at the end.
         * 3. Merges consecutive same-role bursts (e.g. "Grand Prize...", "1", "2", "3") into clean
         *    multi-line chronological turns so Gemini receives valid alternating user/model roles.
         */
        internal fun prepareConversationTurns(
            history: List<ConversationTurn>,
            latestMessage: String,
            openingMessage: String? = null,
            maxTurns: Int = MAX_HISTORY_TURNS
        ): List<ConversationTurn> {
            val cleaned = history
                .map { it.copy(text = it.text.trim()) }
                .filter { it.text.isNotEmpty() }
                .toMutableList()

            val cleanLatest = latestMessage.trim()
            if (cleanLatest.isNotEmpty()) {
                val alreadyEndedWithLatest = cleaned.lastOrNull()?.let { last ->
                    last.isFromUser && last.text == cleanLatest
                } == true
                if (!alreadyEndedWithLatest) {
                    cleaned.add(ConversationTurn(isFromUser = true, text = cleanLatest))
                }
            }

            if (cleaned.isEmpty()) {
                return emptyList()
            }

            val anchorText = openingMessage?.trim()?.takeIf { it.isNotEmpty() }
                ?: cleaned.firstOrNull { it.isFromUser }?.text

            val bounded: List<ConversationTurn> = if (cleaned.size <= maxTurns) {
                if (anchorText != null && cleaned.none { it.isFromUser && it.text == anchorText }) {
                    listOf(ConversationTurn(isFromUser = true, text = anchorText)) + cleaned
                } else {
                    cleaned
                }
            } else {
                val tail = cleaned.takeLast((maxTurns - 1).coerceAtLeast(1))
                val tailHasAnchor = anchorText == null || tail.any { it.isFromUser && it.text == anchorText }
                if (tailHasAnchor) {
                    cleaned.takeLast(maxTurns)
                } else {
                    listOf(ConversationTurn(isFromUser = true, text = anchorText!!)) + tail
                }
            }

            // Merge consecutive same-role turns into clean multi-line messages for valid Gemini alternation.
            val merged = mutableListOf<ConversationTurn>()
            for (turn in bounded) {
                val prev = merged.lastOrNull()
                if (prev != null && prev.isFromUser == turn.isFromUser) {
                    merged[merged.lastIndex] = prev.copy(text = "${prev.text}\n${turn.text}")
                } else {
                    merged.add(turn)
                }
            }

            // Gemini contents must begin with a user turn.
            while (merged.isNotEmpty() && !merged.first().isFromUser) {
                merged.removeAt(0)
            }

            if (merged.isEmpty() && cleanLatest.isNotEmpty()) {
                merged.add(ConversationTurn(isFromUser = true, text = cleanLatest))
            }

            return merged
        }

        private val ipv4FirstDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val addresses = Dns.SYSTEM.lookup(hostname)
                val v4 = addresses.filterIsInstance<Inet4Address>()
                return if (v4.isNotEmpty()) v4 + addresses.filterNot { it is Inet4Address } else addresses
            }
        }

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .dns(ipv4FirstDns)
            .protocols(listOf(Protocol.HTTP_1_1))
            .retryOnConnectionFailure(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(65, TimeUnit.SECONDS)
            .build()
    }
}
