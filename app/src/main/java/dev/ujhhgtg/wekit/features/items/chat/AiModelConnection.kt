package dev.ujhhgtg.wekit.features.items.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/** AI 配置的连接测试与模型列表获取。 */
internal object AiModelConnection {

    /**
     * 测试连接：向「API 地址 + API 路径」拼出的端点原样发起 OpenAI Chat Completions
     * 格式的最小请求，不做额外的 /chat/completions 拼接——用户填什么路径就测什么路径。
     * HTTP 2xx 且响应包含「OK」才算成功。
     */
    suspend fun testConnection(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val endpoint = AiModelConfig.resolvedBaseUrl().trimEnd('/')
            check(endpoint.isNotEmpty()) { "未配置 API 地址" }
            check(AiModelConfig.apiKey.isNotBlank()) { "未配置 API Key" }
            check(AiModelConfig.modelId.isNotBlank()) { "未配置模型名称" }

            val body = JSONObject()
                .put("model", AiModelConfig.modelId.trim())
                .put("stream", false)
                .put(
                    "messages",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put("content", "仅回复 OK"),
                    ),
                )
                .toString()

            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer ${AiModelConfig.apiKey.trim()}")
                doOutput = true
            }
            try {
                conn.outputStream.use { out: OutputStream ->
                    out.write(body.toByteArray(Charsets.UTF_8))
                }
                val code = conn.responseCode
                val content = if (code in 200..299) {
                    conn.inputStream.use { readAll(it) }
                } else {
                    (conn.errorStream?.use { readAll(it) })?.ifBlank { "HTTP $code" } ?: "HTTP $code"
                }
                check(code in 200..299) {
                    if (content.isBlank()) "HTTP $code @ $endpoint" else "HTTP $code @ $endpoint: ${content.take(160)}"
                }
                check(content.contains("OK", ignoreCase = true)) { "响应异常：${content.take(120)}" }
                "OK"
            } finally {
                conn.disconnect()
            }
        }
    }

    /** 获取模型列表：GET {已填端点去掉 /chat/completions 后缀后拼接}/models，从 data[].id 提取。 */
    suspend fun fetchModels(): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val base = AiModelConfig.resolvedBaseUrl().trimEnd('/')
            check(base.isNotEmpty()) { "未配置 API 地址" }
            check(AiModelConfig.apiKey.isNotBlank()) { "未配置 API Key" }

            val endpoint = when {
                base.endsWith("/chat/completions") -> base.removeSuffix("/chat/completions").trimEnd('/') + "/models"
                else -> "$base/models"
            }

            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer ${AiModelConfig.apiKey.trim()}")
            }
            try {
                val code = conn.responseCode
                val content = if (code in 200..299) {
                    conn.inputStream.use { readAll(it) }
                } else {
                    (conn.errorStream?.use { readAll(it) })?.ifBlank { "HTTP $code" } ?: "HTTP $code"
                }
                check(code in 200..299) {
                    if (content.isBlank()) "HTTP $code @ $endpoint" else "HTTP $code @ $endpoint: ${content.take(160)}"
                }
                val json = JSONObject(content)
                val data = json.optJSONArray("data") ?: throw IllegalStateException("响应缺少 data 字段")
                val ids = mutableListOf<String>()
                for (i in 0 until data.length()) {
                    val item = data.optJSONObject(i) ?: continue
                    item.optString("id").takeIf { it.isNotBlank() }?.let { ids.add(it) }
                }
                check(ids.isNotEmpty()) { "模型列表为空" }
                ids
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun readAll(input: InputStream): String =
        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { it.readText() }
}
