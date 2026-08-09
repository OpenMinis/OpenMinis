//
//  ModelUseOffload.m
//  MinisApp
//
//  Native offload handler for `minis-model-use`.
//  Subcommands: list, search, run
//

#import <Foundation/Foundation.h>
#import "NativeOffloadUtils.h"
#include "kernel/native_offload.h"
#include <unistd.h>

#import "Minis-Swift.h"

static NSString *const TOOL_NAME = @"minis-model-use";

static NSString *const HELP_TEXT =
    @"minis-model-use - List, search, and invoke LLM models\n"
     "\n"
     "USAGE:\n"
     "  minis-model-use <command> [options]\n"
     "\n"
     "COMMANDS:\n"
     "  list                           List models you can use\n"
     "  search <query>                 Search models by name or id\n"
     "  run [options]                  Send messages to a model\n"
     "\n"
     "LIST / SEARCH OPTIONS:\n"
     "  --all                          Show all configured models (default: agent loop only)\n"
     "  --provider <name>              Filter by provider (e.g. Anthropic, OpenAI)\n"
     "  --modality <type>              Filter by modality (image, audio, video, pdf, text)\n"
     "\n"
     "RUN OPTIONS:\n"
     "  --model <id_or_name>           Model id, display name, or entry_id (required).\n"
     "                                 Use qualified `<instance_label>/<model_id>` (e.g.\n"
     "                                 `deepseek/deepseek-v4-flash`) to disambiguate when\n"
     "                                 the same model_id is configured under multiple\n"
     "                                 provider instances. See `list` output for labels.\n"
     "  --provider <label_or_id>       Restrict --model lookup to a specific provider\n"
     "                                 instance. Matches `instance_label` (case-insensitive)\n"
     "                                 or instance UUID. Alternative to the qualified\n"
     "                                 `<label>/<model_id>` form when --model id is ambiguous.\n"
     "  --input <path>                 Path to input JSON file (OpenAI messages format)\n"
     "  --prompt <text>                Inline user-message text. Mutually exclusive with --input.\n"
     "  --prompt-file <path>           Read user-message text from file (plain text, not JSON).\n"
     "  --output <path>                Path to write output (default: stdout)\n"
     "  --system <text>                Custom system prompt. When omitted a short default is used\n"
     "                                 that identifies the caller as Minis and states the task is a\n"
     "                                 sub-call from the in-app agent loop.\n"
     "  --system-file <path>           Read system prompt from file\n"
     "  --max-tokens <n>               Max output tokens (default: 4096)\n"
     "  --temperature <n>              Temperature 0.0-2.0\n"
     "  --stream                       Stream output token by token\n"
     "  --endpoint <kind|path>         Force the endpoint for THIS call. Built-in kinds:\n"
     "                                 auto (default) | images-gen | chat. ALSO accepts a\n"
     "                                 CUSTOM PATH starting with \"/\" (e.g.\n"
     "                                 /api/v3/images/generations) which replaces the\n"
     "                                 ENTIRE path on the provider's own base URL host.\n"
     "                                 Default follows the provider's saved preference.\n"
     "\n"
     "INPUT FORMAT (OpenAI Chat Completions JSON — the ONLY input format):\n"
     "  The --input / stdin body MUST be OpenAI Chat Completions shape. It is\n"
     "  provider-agnostic: standard fields are AUTOMATICALLY CONVERTED to\n"
     "  whatever the selected model's provider (Anthropic, Gemini, OpenAI\n"
     "  Responses, etc.) expects on the wire. Do NOT hand-write provider-\n"
     "  native bodies as the primary input.\n"
     "  {\n"
     "    \"messages\": [\n"
     "      {\"role\": \"user\", \"content\": \"Hello\"}\n"
     "    ]\n"
     "  }\n"
     "  Or pass a single user message via stdin.\n"
     "\n"
     "  AUDIO INPUT (audio_input models, e.g. gpt-4o-audio / Qwen-Audio):\n"
     "    Add an input_audio content block to a user message (official\n"
     "    OpenAI shape; forwarded verbatim on both the Chat Completions and\n"
     "    Responses API paths). The model MUST declare the audio_input\n"
     "    modality (see `list --modality audio`), otherwise the call fails\n"
     "    with an explicit modality error.\n"
     "    {\"messages\":[{\"role\":\"user\",\"content\":[\n"
     "       {\"type\":\"text\",\"text\":\"Transcribe this audio\"},\n"
     "       {\"type\":\"input_audio\",\"input_audio\":{\"data\":\"<base64>\",\n"
     "        \"format\":\"wav\"}}]}]}\n"
     "    NOTE: dedicated STT transcription endpoints (POST\n"
     "    /v1/audio/transcriptions on speaches / faster-whisper /\n"
     "    whisper.cpp servers) require multipart/form-data uploads, which\n"
     "    this tool does NOT support yet — passthrough mode sends JSON\n"
     "    bodies only (known limitation). For speech-to-text today, use a\n"
     "    chat-protocol audio model as above.\n"
     "\n"
     "  Standard-mode extras (OpenAI-compatible providers, all endpoints):\n"
     "    extra_body     object — merged VERBATIM into the final request body\n"
     "                   (your keys win; `model` stays locked). Use for\n"
     "                   provider fields our schema doesn't model, e.g.\n"
     "                   OpenRouter web search: {\"extra_body\":{\"plugins\":\n"
     "                   [{\"id\":\"web\"}]}} — these are NOT converted.\n"
     "    extra_headers  string map — added to request headers; same-name\n"
     "                   REPLACES the default (incl. auth headers).\n"
     "\n"
     "PASSTHROUGH MODE (raw escape hatch; response is NOT parsed):\n"
     "  For endpoints/formats our schema doesn't model at all (video gen,\n"
     "  TTS, provider-native APIs), add a top-level \"passthrough\" object:\n"
     "    {\"passthrough\": {\n"
     "       \"endpoint\": \"/v1/audio/speech\",   // \"/abs/path\" replaces the\n"
     "                                          // whole path on the provider's\n"
     "                                          // base host; omit = chat path\n"
     "       \"method\":  \"POST\",                // default POST\n"
     "       \"headers\": {\"X-Custom\": \"1\"},     // same-name REPLACES defaults\n"
     "       \"body\":    {\"input\": \"hi\", \"voice\": \"nova\"},\n"
     "       \"body_mode\": \"replace\"            // replace = body IS the whole\n"
     "                                          // request body; merge (default)\n"
     "                                          // = layered over the converted\n"
     "                                          // OpenAI baseline, model locked\n"
     "    }}\n"
     "  Passthrough values are sent VERBATIM (no conversion). The response is\n"
     "  returned RAW: full bytes to --output (required for binary), or inline\n"
     "  text up to 64KB; the result JSON reports http_status, content_type,\n"
     "  bytes and the fully-assembled endpoint_url. You (or a follow-up\n"
     "  script) own parsing. OpenAI-compatible providers only for now.\n"
     "\n"
     "IMAGE GENERATION FIELDS (only for image_output models):\n"
     "  IMPORTANT: image generation is SLOW — typically 1-5 minutes per\n"
     "  request. When invoking this command from shell_execute, set the\n"
     "  largest timeout you can (e.g. timeout: 600) so the generation isn't\n"
     "  killed mid-render. The default tool timeout will often be too short.\n"
     "\n"
     "  Use the SAME messages format as text models: put the image prompt\n"
     "  in the user message, and image parameters under \"generation_config\".\n"
     "\n"
     "  OpenAI-style (DALL-E / gpt-image-1 / gpt-image-2 etc.) — generation_config:\n"
     "    n         integer, number of images (default 1)\n"
     "    size      \"1024x1024\" | \"1792x1024\" | \"1024x1792\" | etc.\n"
     "    quality   \"standard\" | \"hd\"\n"
     "\n"
     "  Gemini-style (Imagen / gemini-2.5-flash-image etc.) — generation_config:\n"
     "    aspect_ratio       \"1:1\" | \"16:9\" | \"9:16\" | \"4:3\" | \"3:4\"\n"
     "    image_size         \"512px\" | \"1K\" | \"2K\" | \"4K\"\n"
     "    number_of_images   1-4\n"
     "    person_generation  \"DONT_ALLOW\" | \"ALLOW_ADULT\"\n"
     "  Unknown providers silently ignore unsupported fields.\n"
     "\n"
     "  Example (OpenAI image model):\n"
     "    {\"messages\":[{\"role\":\"user\",\"content\":\"a red panda astronaut\"}],\n"
     "     \"generation_config\":{\"size\":\"1792x1024\",\"quality\":\"hd\",\"n\":1}}\n"
     "  Example (Gemini image model):\n"
     "    {\"messages\":[{\"role\":\"user\",\"content\":\"a red panda astronaut\"}],\n"
     "     \"generation_config\":{\"aspect_ratio\":\"16:9\",\"image_size\":\"2K\"}}\n"
     "\n"
     "COMMON OPTIONS:\n"
     "  --help, -h           Show this help message\n"
     "  --compact            Minimize JSON output\n"
     "  -q, --quiet          Output only data field\n"
     "\n"
     "EXAMPLES:\n"
     "  minis-model-use list\n"
     "  minis-model-use list --modality audio\n"
     "  minis-model-use search gemini\n"
     "  minis-model-use search gemini --modality video\n"
     "  minis-model-use run --model GPT-5.5 --prompt 'Summarize: ...'\n"
     "  minis-model-use run --model GPT-5.5 --prompt-file /var/minis/workspace/text.txt\n"
     "  minis-model-use run --model claude-sonnet-4-6 --input /var/minis/workspace/prompt.json\n"
     "  minis-model-use run --model deepseek/deepseek-v4-flash --input msgs.json   # qualified form\n"
     "  minis-model-use run --model deepseek-v4-flash --provider deepseek --input msgs.json   # equivalent\n"
     "  echo 'What is 2+2?' | minis-model-use run --model gpt-4o\n"
     "  minis-model-use run --model gemini-2.5-flash --system 'You are a poet' --input msgs.json --output /var/minis/workspace/out.json\n";

// ── Subcommands ──

static int cmd_list(int argc, char **argv, int stdout_fd, BOOL compact, BOOL quiet) {
    NSString *providerFilter = noff_find_arg(argc, argv, "--provider");
    NSString *modalityFilter = noff_find_arg(argc, argv, "--modality");
    BOOL showAll = noff_has_flag(argc, argv, "--all");

    __block NSDictionary *result = nil;

    noff_dispatch_main_sync(^id{
        result = [ModelUseOffloadBridge listModelsWithProvider:providerFilter
                                               modalityFilter:modalityFilter
                                                      showAll:showAll];
        return nil;
    });

    noff_emit_json(stdout_fd, noff_json_envelope(TOOL_NAME, @"list", result ?: @{}), compact, quiet);
    return NOFF_EXIT_SUCCESS;
}

static int cmd_search(int argc, char **argv, int stdout_fd, int stderr_fd, BOOL compact, BOOL quiet) {
    NSArray<NSString *> *positional = noff_positional_args(argc, argv);
    if (positional.count < 1) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"search",
                                             NOFF_ERR_INVALID_ARGS,
                                             @"No search query provided. Usage: minis-model-use search <query>");
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_INVALID_ARGS;
    }

    NSString *query = [positional componentsJoinedByString:@" "];
    NSString *modalityFilter = noff_find_arg(argc, argv, "--modality");
    BOOL showAll = noff_has_flag(argc, argv, "--all");
    __block NSDictionary *result = nil;

    noff_dispatch_main_sync(^id{
        result = [ModelUseOffloadBridge searchModelsWithQuery:query
                                              modalityFilter:modalityFilter
                                                     showAll:showAll];
        return nil;
    });

    noff_emit_json(stdout_fd, noff_json_envelope(TOOL_NAME, @"search", result ?: @{}), compact, quiet);
    return NOFF_EXIT_SUCCESS;
}

static int cmd_run(int argc, char **argv, int stdin_fd, int stdout_fd, int stderr_fd,
                    BOOL compact, BOOL quiet) {
    NSString *modelIdOrName = noff_find_arg(argc, argv, "--model");
    if (!modelIdOrName) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"run",
                                             NOFF_ERR_INVALID_ARGS,
                                             @"--model is required. Usage: minis-model-use run --model <id_or_name>");
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_INVALID_ARGS;
    }

    // Optional provider scoping — disambiguates when multiple instances expose the same model_id.
    NSString *providerFilter = noff_find_arg(argc, argv, "--provider");

    // Read messages from --input file, --prompt/--prompt-file inline text, or stdin
    NSString *inputPath = noff_find_arg(argc, argv, "--input");
    NSString *promptText = noff_find_arg(argc, argv, "--prompt");
    NSString *promptFile = noff_find_arg(argc, argv, "--prompt-file");
    NSString *outputPath = noff_find_arg(argc, argv, "--output");
    NSString *systemPrompt = noff_find_arg(argc, argv, "--system");
    NSString *systemFile = noff_find_arg(argc, argv, "--system-file");
    NSString *maxTokensStr = noff_find_arg(argc, argv, "--max-tokens");
    NSString *temperatureStr = noff_find_arg(argc, argv, "--temperature");
    NSString *endpointArg = noff_find_arg(argc, argv, "--endpoint");
    BOOL stream = noff_has_flag(argc, argv, "--stream");

    int maxTokens = maxTokensStr ? [maxTokensStr intValue] : 4096;
    if (maxTokens <= 0) maxTokens = 4096;

    double temperature = -1.0; // sentinel for "not set"
    if (temperatureStr) {
        temperature = [temperatureStr doubleValue];
    }

    // System prompt from file
    if (systemFile && !systemPrompt) {
        NSString *hostPath = noff_resolve_host_path(systemFile);
        if (hostPath) {
            NSError *readErr = nil;
            systemPrompt = [NSString stringWithContentsOfFile:hostPath
                                                    encoding:NSUTF8StringEncoding
                                                       error:&readErr];
            if (readErr) {
                NSDictionary *err = noff_json_error(TOOL_NAME, @"run",
                                                     NOFF_ERR_INVALID_ARGS,
                                                     [NSString stringWithFormat:@"Failed to read system file: %@", readErr.localizedDescription]);
                noff_emit_json(stdout_fd, err, compact, quiet);
                return NOFF_EXIT_ERROR;
            }
        }
    }

    // --prompt / --prompt-file resolve to plain user-message text, which we
    // wrap as a single-user OpenAI messages envelope below. These are
    // mutually exclusive with --input; if both are supplied, --input wins.
    NSString *resolvedPromptText = promptText;
    if (!resolvedPromptText && promptFile) {
        NSString *hostPath = nil;
        if ([promptFile hasPrefix:@"/var/minis/"] || [promptFile hasPrefix:@"/home/"] || [promptFile hasPrefix:@"/tmp/"]) {
            hostPath = noff_resolve_host_path(promptFile);
        } else {
            hostPath = promptFile;
        }
        if (!hostPath || ![[NSFileManager defaultManager] fileExistsAtPath:hostPath]) {
            NSDictionary *err = noff_json_error(TOOL_NAME, @"run",
                                                 NOFF_ERR_INVALID_ARGS,
                                                 [NSString stringWithFormat:@"Prompt file not found: %@", promptFile]);
            noff_emit_json(stdout_fd, err, compact, quiet);
            return NOFF_EXIT_ERROR;
        }
        NSError *readErr = nil;
        resolvedPromptText = [NSString stringWithContentsOfFile:hostPath encoding:NSUTF8StringEncoding error:&readErr];
        if (readErr || !resolvedPromptText) {
            NSDictionary *err = noff_json_error(TOOL_NAME, @"run",
                                                 NOFF_ERR_INVALID_ARGS,
                                                 [NSString stringWithFormat:@"Failed to read prompt file: %@", readErr.localizedDescription ?: @"unknown"]);
            noff_emit_json(stdout_fd, err, compact, quiet);
            return NOFF_EXIT_ERROR;
        }
    }

    // Read input messages
    NSString *inputJSON = nil;
    if (inputPath) {
        NSString *hostPath = nil;
        if ([inputPath hasPrefix:@"/var/minis/"] || [inputPath hasPrefix:@"/home/"] || [inputPath hasPrefix:@"/tmp/"]) {
            hostPath = noff_resolve_host_path(inputPath);
        } else {
            hostPath = inputPath;
        }
        if (!hostPath || ![[NSFileManager defaultManager] fileExistsAtPath:hostPath]) {
            NSDictionary *err = noff_json_error(TOOL_NAME, @"run",
                                                 NOFF_ERR_INVALID_ARGS,
                                                 [NSString stringWithFormat:@"Input file not found: %@", inputPath]);
            noff_emit_json(stdout_fd, err, compact, quiet);
            return NOFF_EXIT_ERROR;
        }
        NSError *readErr = nil;
        inputJSON = [NSString stringWithContentsOfFile:hostPath encoding:NSUTF8StringEncoding error:&readErr];
        if (readErr || !inputJSON) {
            NSDictionary *err = noff_json_error(TOOL_NAME, @"run",
                                                 NOFF_ERR_INVALID_ARGS,
                                                 [NSString stringWithFormat:@"Failed to read input file: %@", readErr.localizedDescription ?: @"unknown"]);
            noff_emit_json(stdout_fd, err, compact, quiet);
            return NOFF_EXIT_ERROR;
        }
    } else if (resolvedPromptText && resolvedPromptText.length > 0) {
        // Inline --prompt / --prompt-file: wrap plain text as a single user message.
        NSData *textData = [NSJSONSerialization dataWithJSONObject:@{
            @"messages": @[@{@"role": @"user", @"content": resolvedPromptText}]
        } options:0 error:nil];
        inputJSON = [[NSString alloc] initWithData:textData encoding:NSUTF8StringEncoding];
    } else {
        // Try stdin
        NSString *stdinContent = noff_read_stdin(stdin_fd);
        if (stdinContent && stdinContent.length > 0) {
            // Check if it's JSON (starts with { or [)
            NSString *trimmed = [stdinContent stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceAndNewlineCharacterSet]];
            if ([trimmed hasPrefix:@"{"] || [trimmed hasPrefix:@"["]) {
                inputJSON = stdinContent;
            } else {
                // Wrap plain text as a single user message
                NSData *textData = [NSJSONSerialization dataWithJSONObject:@{
                    @"messages": @[@{@"role": @"user", @"content": stdinContent}]
                } options:0 error:nil];
                inputJSON = [[NSString alloc] initWithData:textData encoding:NSUTF8StringEncoding];
            }
        }
    }

    if (!inputJSON || inputJSON.length == 0) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"run",
                                             NOFF_ERR_INVALID_ARGS,
                                             @"No input provided. Use one of: --input <path>, --prompt <text>, --prompt-file <path>, or pipe text/JSON via stdin.");
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_INVALID_ARGS;
    }

    // If caller didn't pass --system / --system-file, inject a short default
    // identifying the caller as Minis. Some Responses-API providers (Codex
    // proxies, etc.) reject requests without an instructions block, and even
    // when accepted a generic identity helps the sub-model orient itself when
    // invoked from the parent agent loop. Plain explicit empty `--system ""`
    // is preserved (no default).
    if (!systemPrompt) {
        systemPrompt = @"You are Minis, an on-device AI assistant running on iOS. "
                       @"You are being invoked as a sub-call from the parent agent loop "
                       @"to handle a focused task — answer the user's request directly and "
                       @"concisely, without restating the request or wrapping your answer in "
                       @"extra meta-commentary.";
    }

    // If --endpoint was passed, merge it into the input JSON's top-level so the
    // Swift bridge picks it up via parseEndpointOverride. Accepts the same shorthand
    // forms documented in HELP_TEXT (auto / images-gen / chat). Unknown values are
    // silently dropped — Swift does the canonical validation.
    if (endpointArg) {
        NSData *inputData = [inputJSON dataUsingEncoding:NSUTF8StringEncoding];
        NSError *parseErr = nil;
        id parsed = inputData ? [NSJSONSerialization JSONObjectWithData:inputData options:NSJSONReadingMutableContainers error:&parseErr] : nil;
        if ([parsed isKindOfClass:[NSMutableDictionary class]]) {
            ((NSMutableDictionary *)parsed)[@"image_endpoint"] = endpointArg;
            NSData *patched = [NSJSONSerialization dataWithJSONObject:parsed options:0 error:nil];
            if (patched) {
                inputJSON = [[NSString alloc] initWithData:patched encoding:NSUTF8StringEncoding];
            }
        }
    }

    // Resolve output host path.
    // [T-model-use-output-relative-path] --output must be an absolute guest
    // path. minis-model-use forwards to the host, which does NOT inherit the
    // iSH shell's cwd, so a relative path can't be resolved and previously
    // leaked through verbatim — the host then tried to open the bare parent
    // component (e.g. "workspace") and failed with a misleading
    // "The file ... doesn't exist" pointing at a directory name. Reject it up
    // front with an actionable message instead.
    NSString *outputHostPath = nil;
    if (outputPath) {
        if (![outputPath hasPrefix:@"/"]) {
            NSString *msg = [NSString stringWithFormat:
                @"--output must be an absolute path, got \"%@\". minis-model-use runs on the host and does not inherit the shell's current directory, so relative paths can't be resolved. Use an absolute path like /var/minis/workspace/out.jpg.",
                outputPath];
            NSDictionary *err = noff_json_error(TOOL_NAME, @"run",
                                                 NOFF_ERR_INVALID_ARGS, msg);
            noff_emit_json(stdout_fd, err, compact, quiet);
            return NOFF_EXIT_INVALID_ARGS;
        }
        if ([outputPath hasPrefix:@"/var/minis/"] || [outputPath hasPrefix:@"/home/"] || [outputPath hasPrefix:@"/tmp/"]) {
            outputHostPath = noff_resolve_host_path(outputPath);
        } else {
            outputHostPath = outputPath;
        }
    }

    // Call Swift bridge
    __block NSDictionary *result = nil;
    __block NSString *errorMsg = nil;

    dispatch_semaphore_t sem = dispatch_semaphore_create(0);

    if (stream) {
        // Streaming mode: write tokens directly to stdout
        [ModelUseOffloadBridge runModelWithIdOrName:modelIdOrName
                                    providerFilter:providerFilter
                                         inputJSON:inputJSON
                                      systemPrompt:systemPrompt
                                         maxTokens:maxTokens
                                       temperature:temperature
                                    outputHostPath:outputHostPath
                                          streamFd:stdout_fd
                                        completion:^(NSDictionary *data, NSString *error) {
            result = data;
            errorMsg = error;
            dispatch_semaphore_signal(sem);
        }];
    } else {
        [ModelUseOffloadBridge runModelWithIdOrName:modelIdOrName
                                    providerFilter:providerFilter
                                         inputJSON:inputJSON
                                      systemPrompt:systemPrompt
                                         maxTokens:maxTokens
                                       temperature:temperature
                                    outputHostPath:outputHostPath
                                          streamFd:-1
                                        completion:^(NSDictionary *data, NSString *error) {
            result = data;
            errorMsg = error;
            dispatch_semaphore_signal(sem);
        }];
    }

    // Wait with 5 minute timeout for model response
    dispatch_semaphore_wait(sem, dispatch_time(DISPATCH_TIME_NOW, 300 * NSEC_PER_SEC));

    if (errorMsg) {
        NSDictionary *err = noff_json_error(TOOL_NAME, @"run",
                                             NOFF_ERR_INTERNAL_ERROR, errorMsg);
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_ERROR;
    }

    if (!stream) {
        noff_emit_json(stdout_fd, noff_json_envelope(TOOL_NAME, @"run", result ?: @{}), compact, quiet);
    }
    // [T-model-use-passthrough-warnings] Passthrough calls carry the provider's
    // HTTP status in the result. A non-2xx status exits non-zero (raw body is
    // still fully delivered above) so scripts can detect failure via $?.
    // Matches the Android handler's semantics.
    if ([result[@"passthrough"] boolValue]) {
        NSInteger status = [result[@"http_status"] integerValue];
        if (status < 200 || status >= 300) {
            return NOFF_EXIT_ERROR;
        }
    }
    return NOFF_EXIT_SUCCESS;
}

// ── Main handler ──

static int model_use_handler(int argc, char **argv,
                              int stdin_fd, int stdout_fd, int stderr_fd) {
    @autoreleasepool {
        if (noff_has_flag(argc, argv, "--help") || noff_has_flag(argc, argv, "-h")) {
            noff_emit_help(stderr_fd, HELP_TEXT);
            return NOFF_EXIT_SUCCESS;
        }

        BOOL compact = noff_has_flag(argc, argv, "--compact");
        BOOL quiet = noff_has_flag(argc, argv, "-q") || noff_has_flag(argc, argv, "--quiet");

        NSString *subcmd = noff_get_subcommand(argc, argv);
        if (!subcmd) {
            noff_emit_help(stderr_fd, HELP_TEXT);
            NSDictionary *err = noff_json_error(TOOL_NAME, @"unknown",
                                                 NOFF_ERR_INVALID_ARGS,
                                                 @"No command specified. Use --help for usage.");
            noff_emit_json(stdout_fd, err, compact, quiet);
            return NOFF_EXIT_INVALID_ARGS;
        }

        if ([subcmd isEqualToString:@"list"])    return cmd_list(argc, argv, stdout_fd, compact, quiet);
        if ([subcmd isEqualToString:@"search"])  return cmd_search(argc, argv, stdout_fd, stderr_fd, compact, quiet);
        if ([subcmd isEqualToString:@"run"])     return cmd_run(argc, argv, stdin_fd, stdout_fd, stderr_fd, compact, quiet);

        noff_emit_help(stderr_fd, HELP_TEXT);
        NSDictionary *err = noff_json_error(TOOL_NAME, subcmd,
                                             NOFF_ERR_INVALID_ARGS,
                                             [NSString stringWithFormat:@"Unknown command '%@'. Valid commands: list, search, run. Use --help for details.", subcmd]);
        noff_emit_json(stdout_fd, err, compact, quiet);
        return NOFF_EXIT_INVALID_ARGS;
    }
}

// ── Registration ──

void model_use_offload_register(void) {
    int err = native_offload_add_handler("minis-model-use", model_use_handler);
    if (err == 0) {
        noff_ensure_guest_stub("/usr/local/bin/minis-model-use");
        NSLog(@"NativeOffloads: minis-model-use handler registered");
    } else {
        NSLog(@"NativeOffloads: failed to register minis-model-use handler (err=%d)", err);
    }
}
