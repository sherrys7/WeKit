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
        var base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return base
        // 用户可能在 baseUrl 里误填了完整端点（如 https://api.deepseek.com/v1/chat/completions），
        // 剥离掉尾部的 /chat/completions，避免客户端再拼一次导致 HTTP 404
        base = base.removeSuffix("/chat/completions").trimEnd('/')
        var path = apiPath.trim().trim('/')
        // apiPath 里误填的完整端点同样剥离
        path = path.removeSuffix("/chat/completions").trim('/')
        if (path.isEmpty()) return base
        // 防重复路径：baseUrl 已带该前缀（如 baseUrl=https://api.deepseek.com/v1 且 apiPath=/v1）
        // 时避免拼成 /v1/v1 导致 HTTP 404
        if (base.endsWith("/$path") || base == path) return base
        return "$base/$path"
    }

    fun providerType(): ModelProviderType =
        runCatching { ModelProviderType.valueOf(providerTypeName) }
            .getOrDefault(ModelProviderType.OPENAI_CHAT_COMPLETION)

    fun isConfigured(): Boolean =
        baseUrl.isNotBlank() && apiKey.isNotBlank() && modelId.isNotBlank()
}
