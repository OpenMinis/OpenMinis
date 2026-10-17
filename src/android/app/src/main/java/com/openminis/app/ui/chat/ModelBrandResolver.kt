package com.openminis.app.ui.chat

import com.openminis.app.data.model.ProviderType

/**
 * Resolves the lobe-icons brand slug for a model reference in the top bar.
 *
 * Resolution chain (first hit wins):
 *  1. **ProviderType mapping** — the configured provider type is the
 *     authoritative signal when it is a KNOWN brand type. Relay/compat
 *     types carry no brand information — a relay can serve any vendor's
 *     model — so they fall through to 2.
 *  2. **Model-name keyword scoring** — the fallback for relays and custom
 *     instances. The normalized model id/display name is scanned for brand
 *     keywords; the LONGEST matching keyword wins (a longer hit is a
 *     stronger signal: "deepseek-chat" must outrank any 3-char fragment).
 *     Ties keep table order.
 *  3. OpenAI fallback — an unrecognized but non-empty reference shows the
 *     OpenAI mark (product decision: a stable icon slot beats an empty
 *     one; the model text next to it carries the exact identity). null is
 *     returned ONLY when there is no model reference at all.
 *
 * Icons are lobe-icons v1.95.1 (MIT) SVGs packed verbatim under res/raw
 * (color variants) and res/raw-night (mono brands follow the theme via
 * the night qualifier — currentColor semantics, no color conversion).
 * The catalog covers ALL 358 static-svg brands (verified byte-identical
 * against the v6.0.0 tag — the static-svg subpackage did not rev with
 * the v6.0.0 release); keywords are slug-as-keyword with longest-match
 * priority, slugs of length <= 2 are excluded (uncontrollable false hits).
 */
internal object ModelBrandResolver {

    /** Fallback brand for an unrecognized non-empty model reference. */
    internal const val FALLBACK_BRAND = "openai"

    /**
     * Brand keywords → lobe-icons slug. Order matters for ties; longer
     * keywords outrank shorter ones by length, not position.
     */
    private val KEYWORDS: List<Pair<String, String>> = listOf(
        // Long, unambiguous brand names first so they win length ties.
        "deepseek" to "deepseek",
        "moonshot" to "moonshot",
        "minimax" to "minimax",
        "mistral" to "mistral",
        "codestral" to "mistral",
        "mixtral" to "mistral",
        "anthropic" to "claude",
        "perplexity" to "perplexity",
        "openrouter" to "openrouter",
        "huggingface" to "huggingface",
        "siliconflow" to "siliconcloud",
        "siliconcloud" to "siliconcloud",
        "midjourney" to "midjourney",
        "fireworks" to "fireworks",
        "deepinfra" to "deepinfra",
        "chatgpt" to "openai",
        "chatglm" to "zhipu",
        "cogvideo" to "zhipu",
        "cogview" to "zhipu",
        "zhipu" to "zhipu",
        "baichuan" to "baichuan",
        "sensenova" to "sensenova",
        "tiangong" to "tiangong",
        "tongyi" to "qwen",
        "doubao" to "doubao",
        "skylark" to "doubao",
        "hunyuan" to "hunyuan",
        "wenxin" to "wenxin",
        "ernie" to "wenxin",
        "claude" to "claude",
        "sonnet" to "claude",
        "opus" to "claude",
        "haiku" to "claude",
        "gemini" to "gemini",
        "gemma" to "gemini",
        "grok" to "grok",
        "llama" to "meta",
        "codellama" to "meta",
        "qwen" to "qwen",
        "qwq" to "qwen",
        "qvq" to "qwen",
        "glm" to "zhipu",
        "kimi" to "kimi",
        "copilot" to "copilot",
        "command" to "cohere",
        "together" to "together",
        "ollama" to "ollama",
        "davinci" to "openai",
        "codex" to "openai",
        "dalle" to "openai",
        "sora" to "sora",
        "gpt" to "openai",
        "dbrx" to "dbrx",
        "jina" to "jina",
        "phi-" to "microsoft",
        "yi-" to "yi",
        "o1" to "openai",
        "o3" to "openai",
        "o4" to "openai",
        "amdradeoncloud" to "amdradeoncloud",
        "bilibiliindex" to "bilibiliindex",
        "githubcopilot" to "githubcopilot",
        "adobefirefly" to "adobefirefly",
        "alibabacloud" to "alibabacloud",
        "cherrystudio" to "cherrystudio",
        "digitalocean" to "digitalocean",
        "dreammachine" to "dreammachine",
        "iflytekcloud" to "iflytekcloud",
        "nousresearch" to "nousresearch",
        "pollinations" to "pollinations",
        "tencentcloud" to "tencentcloud",
        "unstructured" to "unstructured",
        "vectorizerai" to "vectorizerai",
        "antigravity" to "antigravity",
        "browserless" to "browserless",
        "codeflicker" to "codeflicker",
        "commandcode" to "commandcode",
        "essentialai" to "essentialai",
        "featherless" to "featherless",
        "googlecloud" to "googlecloud",
        "hermesagent" to "hermesagent",
        "huaweicloud" to "huaweicloud",
        "infinigence" to "infinigence",
        "sillytavern" to "sillytavern",
        "worldrouter" to "worldrouter",
        "agentvoice" to "agentvoice",
        "alephalpha" to "alephalpha",
        "askverdict" to "askverdict",
        "assemblyai" to "assemblyai",
        "atlascloud" to "atlascloud",
        "baiducloud" to "baiducloud",
        "claudecode" to "claudecode",
        "cloudflare" to "cloudflare",
        "copilotkit" to "copilotkit",
        "deepcogito" to "deepcogito",
        "dotsstudio" to "dotsstudio",
        "elevenlabs" to "elevenlabs",
        "happyhorse" to "happyhorse",
        "hyperbolic" to "hyperbolic",
        "infermatic" to "infermatic",
        "inflection" to "inflection",
        "lightricks" to "lightricks",
        "llamaindex" to "llamaindex",
        "modelscope" to "modelscope",
        "nanobanana" to "nanobanana",
        "nano-banana" to "nanobanana",
        "notebooklm" to "notebooklm",
        "perceptron" to "perceptron",
        "pydanticai" to "pydanticai",
        "search1api" to "search1api",
        "statecloud" to "statecloud",
        "streamlake" to "streamlake",
        "unionalpha" to "unionalpha",
        "volcengine" to "volcengine",
        "xiaomimimo" to "xiaomimimo",
        "xinference" to "xinference",
        "akashchat" to "akashchat",
        "automatic" to "automatic",
        "burncloud" to "burncloud",
        "bytedance" to "bytedance",
        "celestoai" to "celestoai",
        "codebuddy" to "codebuddy",
        "docsearch" to "docsearch",
        "firecrawl" to "firecrawl",
        "fishaudio" to "fishaudio",
        "geminicli" to "geminicli",
        "inception" to "inception",
        "inceptron" to "inceptron",
        "inference" to "inference",
        "kwaipilot" to "kwaipilot",
        "langchain" to "langchain",
        "langgraph" to "langgraph",
        "langsmith" to "langsmith",
        "openhands" to "openhands",
        "openhuman" to "openhuman",
        "openwebui" to "openwebui",
        "replicate" to "replicate",
        "sambanova" to "sambanova",
        "searchapi" to "searchapi",
        "snowflake" to "snowflake",
        "stability" to "stability",
        "topazlabs" to "topazlabs",
        "wavespeed" to "wavespeed",
        "workersai" to "workersai",
        "aihubmix" to "aihubmix",
        "aionlabs" to "aionlabs",
        "airjelly" to "airjelly",
        "aistudio" to "aistudio",
        "antgroup" to "antgroup",
        "anyscale" to "anyscale",
        "bilibili" to "bilibili",
        "cerebras" to "cerebras",
        "clipdrop" to "clipdrop",
        "codegeex" to "codegeex",
        "cometapi" to "cometapi",
        "commanda" to "commanda",
        "cybercut" to "cybercut",
        "deepmind" to "deepmind",
        "friendli" to "friendli",
        "gmicloud" to "gmicloud",
        "greptile" to "greptile",
        "ideogram" to "ideogram",
        "internlm" to "internlm",
        "kilocode" to "kilocode",
        "langfuse" to "langfuse",
        "leptonai" to "leptonai",
        "lmstudio" to "lmstudio",
        "nplcloud" to "nplcloud",
        "obsidian" to "obsidian",
        "openchat" to "openchat",
        "openclaw" to "openclaw",
        "opencode" to "opencode",
        "parasail" to "parasail",
        "pixverse" to "pixverse",
        "poolside" to "poolside",
        "smithery" to "smithery",
        "submodel" to "submodel",
        "vertexai" to "vertexai",
        "windsurf" to "windsurf",
        "xuanyuan" to "xuanyuan",
        "zencoder" to "zencoder",
        "agnesai" to "agnesai",
        "alibaba" to "alibaba",
        "anspire" to "anspire",
        "apertis" to "apertis",
        "azureai" to "azureai",
        "bailian" to "bailian",
        "baseten" to "baseten",
        "bedrock" to "bedrock",
        "civitai" to "civitai",
        "comfyui" to "comfyui",
        "dolphin" to "dolphin",
        "elevenx" to "elevenx",
        "fastgpt" to "fastgpt",
        "flowith" to "flowith",
        "giteeai" to "giteeai",
        "higress" to "higress",
        "kluster" to "kluster",
        "kwaikat" to "kwaikat",
        "livekit" to "livekit",
        "lobehub" to "lobehub",
        "longcat" to "longcat",
        "lovable" to "lovable",
        "metagpt" to "metagpt",
        "myshell" to "myshell",
        "nextbit" to "nextbit",
        "novelai" to "novelai",
        "phidata" to "phidata",
        "player2" to "player2",
        "prunaai" to "prunaai",
        "qingyan" to "qingyan",
        "railway" to "railway",
        "recraft" to "recraft",
        "roocode" to "roocode",
        "searxng" to "searxng",
        "skywork" to "skywork",
        "sophnet" to "sophnet",
        "speedai" to "speedai",
        "stepfun" to "stepfun",
        "straico" to "straico",
        "tencent" to "tencent",
        "unsloth" to "unsloth",
        "upstage" to "upstage",
        "youmind" to "youmind",
        "yuanbao" to "yuanbao",
        "zeroone" to "zeroone",
        "aimass" to "aimass",
        "briaai" to "briaai",
        "capcut" to "capcut",
        "centml" to "centml",
        "chutes" to "chutes",
        "crewai" to "crewai",
        "crusoe" to "crusoe",
        "cursor" to "cursor",
        "decart" to "decart",
        "deepai" to "deepai",
        "github" to "github",
        "google" to "google",
        "gradio" to "gradio",
        "hailuo" to "hailuo",
        "haiper" to "haiper",
        "huawei" to "huawei",
        "jimeng" to "jimeng",
        "kolors" to "kolors",
        "lambda" to "lambda",
        "liquid" to "liquid",
        "llmapi" to "llmapi",
        "lovart" to "lovart",
        "mastra" to "mastra",
        "metaai" to "metaai",
        "monica" to "monica",
        "nebius" to "nebius",
        "newapi" to "newapi",
        "notion" to "notion",
        "novita" to "novita",
        "nvidia" to "nvidia",
        "relace" to "relace",
        "replit" to "replit",
        "rsshub" to "rsshub",
        "runway" to "runway",
        "sakana" to "sakana",
        "targon" to "targon",
        "tavily" to "tavily",
        "venice" to "venice",
        "vercel" to "vercel",
        "viggle" to "viggle",
        "voyage" to "voyage",
        "yandex" to "yandex",
        "zapier" to "zapier",
        "zeabur" to "zeabur",
        "zenmux" to "zenmux",
        "adobe" to "adobe",
        "ai302" to "ai302",
        "ai360" to "ai360",
        "apple" to "apple",
        "arcee" to "arcee",
        "azure" to "azure",
        "baidu" to "baidu",
        "bocha" to "bocha",
        "brave" to "brave",
        "cline" to "cline",
        "colab" to "colab",
        "coqui" to "coqui",
        "deepl" to "deepl",
        "devin" to "devin",
        "doc2x" to "doc2x",
        "figma" to "figma",
        "flora" to "flora",
        "glama" to "glama",
        "goose" to "goose",
        "hedra" to "hedra",
        "ionet" to "ionet",
        "junie" to "junie",
        "kling" to "kling",
        "llava" to "llava",
        "magic" to "magic",
        "manus" to "manus",
        "mcpso" to "mcpso",
        "menlo" to "menlo",
        "meshy" to "meshy",
        "morph" to "morph",
        "phala" to "phala",
        "phind" to "phind",
        "qiniu" to "qiniu",
        "qoder" to "qoder",
        "slock" to "slock",
        "spark" to "spark",
        "tripo" to "tripo",
        "turix" to "turix",
        "wafer" to "wafer",
        "wandb" to "wandb",
        "agui" to "agui",
        "ai21" to "ai21",
        "baai" to "baai",
        "bing" to "bing",
        "coze" to "coze",
        "dify" to "dify",
        "flux" to "flux",
        "glif" to "glif",
        "glmv" to "glmv",
        "groq" to "groq",
        "kagi" to "kagi",
        "kiro" to "kiro",
        "krea" to "krea",
        "luma" to "luma",
        "make" to "make",
        "moxt" to "moxt",
        "nova" to "nova",
        "palm" to "palm",
        "pika" to "pika",
        "ppio" to "ppio",
        "reka" to "reka",
        "reve" to "reve",
        "rwkv" to "rwkv",
        "suno" to "suno",
        "sync" to "sync",
        "trae" to "trae",
        "udio" to "udio",
        "vidu" to "vidu",
        "vllm" to "vllm",
        "xpay" to "xpay",
        "ace" to "ace",
        "ai2" to "ai2",
        "amp" to "amp",
        "aws" to "aws",
        "aya" to "aya",
        "bfl" to "bfl",
        "exa" to "exa",
        "fal" to "fal",
        "ibm" to "ibm",
        "mcp" to "mcp",
        "n8n" to "n8n",
        "poe" to "poe",
        "tii" to "tii",
        "xai" to "xai",
        "zai" to "zai",
    )

    /**
     * ProviderType → lobe-icons slug. Only types that assert a brand.
     * Compat/relay/unsupported types return null on purpose — see KDoc.
     */
    private fun slugForType(type: ProviderType): String? = when (type) {
        ProviderType.anthropic -> "claude"
        ProviderType.gemini -> "gemini"
        ProviderType.openAI, ProviderType.openAIResponses -> "openai"
        ProviderType.xAI -> "xai"
        ProviderType.kimiCode -> "kimi"
        else -> null
    }

    /**
     * @param providerType the instance's configured type, when known.
     * @param modelId raw model id (e.g. "glm-5.3", "claude-sonnet-4-5").
     * @param displayName display name as a second signal — relay catalogs
     *   often use friendlier ids than the wire id.
     * @return lobe-icons slug, or null when nothing matches well enough.
     */
    fun resolve(providerType: ProviderType?, modelId: String?, displayName: String?): String? {
        providerType?.let { type -> slugForType(type)?.let { return it } }

        val haystack = "${modelId ?: ""} ${displayName ?: ""}".lowercase()
        // No model reference at all → no icon (the row itself is empty).
        if (haystack.isBlank()) return null

        var best: String? = null
        var bestScore = 0
        for ((keyword, slug) in KEYWORDS) {
            if (keyword.length <= bestScore) continue
            if (haystack.contains(keyword)) {
                best = slug
                bestScore = keyword.length
            }
        }
        // [T-usage-topbar-brand-icon] Product decision: an UNRECOGNIZED but
        // non-empty model reference falls back to the OpenAI mark rather
        // than rendering no icon — the icon slot is always filled so the
        // top bar keeps a stable visual rhythm across model switches.
        return best ?: FALLBACK_BRAND
    }

    /** Compiled resource id for a slug, or null when not packed. */
    fun iconResId(slug: String): Int? = ModelBrandIconMap.iconResId(slug)
}
