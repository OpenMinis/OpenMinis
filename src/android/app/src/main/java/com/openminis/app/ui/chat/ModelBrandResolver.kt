package com.openminis.app.ui.chat

import com.openminis.app.tools.BrandKeywordSources

/**
 * Resolves the lobe-icons brand slug for a MODEL reference (top bar, model
 * picker, provider detail).
 *
 * Resolution is a single rule: **scan the model id / display name for brand
 * keywords, longest match wins**, then fall back to the OpenAI mark.
 *
 * The provider type is deliberately NOT an input. A relay configured as
 * `openAI` serves any vendor's model (GLM, Kimi, DeepSeek, Gemini all run over
 * the OpenAI protocol), so a type→brand shortcut painted an entire relay
 * catalog with one vendor's logo. It also made call sites disagree: the top bar
 * passed `null` and worked, the picker passed the type and showed nothing but
 * fallbacks. One entry point, one behaviour — see [resolve].
 *
 * Icons are lobe-icons v1.95.1 (MIT) SVGs packed verbatim under res/raw
 * (color variants) and res/raw-night (mono brands follow the theme via the
 * night qualifier — currentColor semantics, no color conversion). The catalog
 * covers all 358 static-svg brands; keywords are slug-as-keyword with
 * longest-match priority, slugs of length <= 2 are excluded (uncontrollable
 * false hits), and bare generic English words (meta/liquid/reka/...) are
 * excluded too — they swallowed unrelated ids like `metadata-pro`.
 */
internal object ModelBrandResolver {

    /** Fallback brand for an unrecognized non-empty model reference. */
    internal const val FALLBACK_BRAND = "openai"

    /**
     * Brand keywords → lobe-icons slug. Order matters for ties; longer
     * keywords outrank shorter ones by length, not position.
     */
    private val KEYWORDS: List<Pair<String, String>> =
        // Chinese brand aliases live in tools/BrandKeywordSources — match data,
        // not UI copy, so the CJK ratchet on ui/ stays meaningful.
        BrandKeywordSources.CJK_ALIASES + listOf(
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
        "hy3" to "hunyuan",
        "hy4" to "hunyuan",
        "hy2" to "hunyuan",
        "hy-" to "hunyuan",
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
        "cohere" to "cohere",
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
        "smithery" to "smithery",
        "submodel" to "submodel",
        "vertexai" to "vertexai",
        "windsurf" to "windsurf",
        "xuanyuan" to "xuanyuan",
        "zencoder" to "zencoder",
        "agnesai" to "agnesai",
        "agnes" to "agnesai",
        "black-forest" to "bfl",
        "flux-1" to "bfl",
        "flux-pro" to "bfl",
        "nova-pro" to "nova",
        "nova-lite" to "nova",
        "nova-micro" to "nova",
        "vertex" to "vertexai",
        "lepton" to "leptonai",
        "gitee" to "giteeai",
        "pruna" to "prunaai",
        "celesto" to "celestoai",
        "vectorizer" to "vectorizerai",
        "pydantic" to "pydanticai",
        "essential" to "essentialai",
        // [T-brand-short-name] Enablement names differ from icon slugs:
        // a model called "agnes-2.0-flash" must hit agnesai's icon.
        "alibaba" to "alibaba",
        "anspire" to "anspire",
        "apertis" to "apertis",
        "azureai" to "azureai",
        "bailian" to "bailian",
        "baseten" to "baseten",
        "bedrock" to "bedrock",
        "civitai" to "civitai",
        "comfyui" to "comfyui",
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
        "step-5" to "stepfun",
        "step-4" to "stepfun",
        "step-3" to "stepfun",
        "step-2" to "stepfun",
        "step-1" to "stepfun",
        "step2" to "stepfun",
        "stepfun" to "stepfun",
        "stepfun-step" to "stepfun",
        "mimo" to "xiaomimimo",
        "aion-labs" to "aionlabs",
        "aion-2" to "aionlabs",
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
        "hedra" to "hedra",
        "ionet" to "ionet",
        "junie" to "junie",
        "kling" to "kling",
        "llava" to "llava",
        "manus" to "manus",
        "mcpso" to "mcpso",
        "meshy" to "meshy",
        "morph" to "morph",
        "phala" to "phala",
        "qiniu" to "qiniu",
        "qoder" to "qoder",
        "tripo" to "tripo",
        "turix" to "turix",
        "wandb" to "wandb",
        "agui" to "agui",
        "ai21" to "ai21",
        "baai" to "baai",
        "bing" to "bing",
        "coze" to "coze",
        "dify" to "dify",
        "glif" to "glif",
        "glmv" to "glmv",
        "groq" to "groq",
        "kagi" to "kagi",
        "kiro" to "kiro",
        "krea" to "krea",
        "luma" to "luma",
        "make" to "make",
        "moxt" to "moxt",
        "palm" to "palm",
        "pika" to "pika",
        "ppio" to "ppio",
        "reve" to "reve",
        "rwkv" to "rwkv",
        "suno" to "suno",
        "udio" to "udio",
        "vidu" to "vidu",
        "vllm" to "vllm",
        "ace" to "ace",
        "ai2" to "ai2",
        "aws" to "aws",
        "aya" to "aya",
        "bfl" to "bfl",
        "exa" to "exa",
        "fal" to "fal",
        "ibm" to "ibm",
        "mcp" to "mcp",
        "n8n" to "n8n",
        "poe" to "poe",
        "xai" to "xai",
        "zai" to "zai",
    )

    /**
     * [T-brand-single-entry] THE entry point. Callers pass what they have from a
     * model row and nothing else — there is deliberately no `providerType`
     * parameter any more:
     *
     * A relay configured as `openAI` serves ANY vendor's model, so passing the
     * instance type let a type→"openai" short-circuit paint every row of a
     * 22-model relay catalog with the OpenAI mark. Worse, each call site decided
     * its own arguments: the top bar passed `null` (worked), the picker passed
     * the type (all fallbacks), provider detail passed `null` (worked). Three
     * sites, three behaviours, one bug.
     *
     * The model id/display name is the ONE signal that describes the actual
     * vendor; brand-exclusive provider types add nothing a model name does not
     * already say far more precisely.
     */
    fun resolve(modelId: String?, displayName: String? = null): String? {
        // No reference at all → no icon (the row itself is empty). This guard
        // must live HERE, not in resolveExact: an empty reference is not
        // "unrecognized", it is absent, and must not wear the fallback mark.
        if ("${modelId ?: ""} ${displayName ?: ""}".isBlank()) return null
        // Product decision: an unrecognized non-empty reference shows the OpenAI
        // mark so the icon slot keeps a stable rhythm.
        return resolveExact(modelId, displayName) ?: FALLBACK_BRAND
    }

    /**
     * Same keyword scan as [resolve] but WITHOUT the OpenAI fallback: returns
     * null when the reference matches no brand. Group-chat avatars use this —
     // an unrecognized speaker must degrade to its initial-letter avatar, not
     // silently wear another vendor's mark.
     */
    fun resolveExact(modelId: String?, displayName: String? = null): String? {
        val raw = "${modelId ?: ""} ${displayName ?: ""}".trim().lowercase()
        if (raw.isBlank()) return null
        // Second channel with punctuation stripped: dirty relay ids like
        // "deep-seek_chat" or "x.ai/grok-3" glue the brand name across
        // separators, so a plain contains() on the raw text misses it.
        // Keywords that themselves contain separators ("hy-") simply never
        // hit this channel — the raw channel covers them.
        val compact = raw.replace(Regex("[^a-z0-9\\u4e00-\\u9fff]+"), "")

        var best: String? = null
        var bestScore = 0
        for ((keyword, slug) in KEYWORDS) {
            if (keyword.length <= bestScore) continue
            if (raw.contains(keyword) ||
                (compact.length > keyword.length && compact.contains(keyword))
            ) {
                best = slug
                bestScore = keyword.length
            }
        }
        return best
    }

    /** Compiled resource id for a slug, or null when not packed. */
    fun iconResId(slug: String): Int? = ModelBrandIconMap.iconResId(slug)
}
