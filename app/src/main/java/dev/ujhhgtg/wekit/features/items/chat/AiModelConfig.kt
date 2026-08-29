package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.wekit.agent.data.entity.ModelProviderType
import dev.ujhhgtg.wekit.preferences.WePrefs

/**
 * AI 回复/群聊分析的独立模型配置，与 WeAgent 的模型数据库解耦，直接持久化到 MMKV。
 * AiReply 和 GroupChatSummary 共用同一份配置。
 */
internal object AiModelConfig {
    var providerTypeName by WePrefs.prefOption(
        "ai_reply_provider_type",
        ModelProviderType.OPENAI_CHAT_COMPLETION.name,
    )
    var baseUrl by WePrefs.prefOption("ai_reply_base_url", "")
    var apiPath by WePrefs.prefOption("ai_reply_api_path", "")
    var apiKey by WePrefs.prefOption("ai_reply_api_key", "")
    var modelId by WePrefs.prefOption("ai_reply_model_id", "")

    /** 完整请求前缀 = baseUrl + apiPath，provider 客户端再拼接协议端点（如 /chat/completions） */
    fun resolvedBaseUrl(): String {
        val base = baseUrl.trim().trimEnd('/')
        val path = apiPath.trim().trimStart('/')
        return if (base.isNotEmpty() && path.isNotEmpty()) "$base/$path" else base
    }

    fun providerType(): ModelProviderType =
        runCatching { ModelProviderType.valueOf(providerTypeName) }
            .getOrDefault(ModelProviderType.OPENAI_CHAT_COMPLETION)

    fun isConfigured(): Boolean =
        baseUrl.isNotBlank() && apiKey.isNotBlank() && modelId.isNotBlank()
}
