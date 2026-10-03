import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// Build-time customization values that must NOT ship in the public
// open-source mirror. `provider-customization.properties` is tracked in the PRIVATE
// MinisApp repo (with real values) and listed under `private:` in
// PUBLISH_MANIFEST.yml so it is never synced; the public repo ships only
// `provider-customization.properties.example` (empty values). A build without a
// configured value compiles fine but fails at runtime the first time the
// value is required (see ClaudeOAuthManager). See docs/PUBLISH_POLICY.md.
val appCustomization = Properties().apply {
    val f = rootProject.file("app/provider-customization.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun customizationValue(key: String): String =
    (appCustomization.getProperty(key) ?: "").replace("\"", "\\\"")

android {
    namespace = "com.openminis.app"
    // [T-android-dynamic-island] Bumped 35→36 so the Android 16 (Baklava)
    // Live Updates APIs — Notification.ProgressStyle, FLAG_PROMOTED_ONGOING,
    // NotificationManager.canPostPromotedNotifications(), setShortCriticalText —
    // are available to compile against. targetSdk stays 35 to avoid pulling in
    // Android 16 behavior changes; the Live Updates path is runtime-gated on
    // Build.VERSION.SDK_INT >= 36 (see DynamicIslandSupport / AgentForegroundService).
    compileSdk = 36

    defaultConfig {
        applicationId = "com.openminis.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 28
        versionName = "1.14"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // DEV_TOOLS gates the on-device developer tooling (127.0.0.1:5321
        // debug server, LLM request log, StreamJitterProbe markers). It is
        // true for `debug` AND for the `perf` build type below, false for
        // `release`. Kept separate from BuildConfig.DEBUG so a non-debuggable
        // build can still carry the tooling.
        buildConfigField("boolean", "DEV_TOOLS", "false")

        // System prompt prefix required by Anthropic for Claude Code OAuth
        // credentials. Empty in the public mirror (see provider-customization.properties).
        buildConfigField(
            "String",
            "ANTHROPIC_OAUTH_IDENTIFIER_PROMPT",
            "\"${customizationValue("ANTHROPIC_OAUTH_IDENTIFIER_PROMPT")}\""
        )

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        getByName("debug") {
            buildConfigField("boolean", "DEV_TOOLS", "true")
        }
        // `perf`: the debug variant minus android:debuggable. ART refuses to
        // AOT-compile a debuggable app (`cmd package compile -m speed`
        // silently downgrades to `verify`) and runs it interpreter + JIT in
        // debuggable mode; simpleperf on a streaming turn showed the main
        // thread ~94% inside the interpreter. Frame-cost measurements taken
        // on `debug` therefore do not represent what users run. `perf` keeps
        // the debug server and probes (DEV_TOOLS) so the measurement harness
        // works, but is profileable/AOT-compilable like a release build.
        // Same debug signing key as release, so it installs in place.
        create("perf") {
            initWith(getByName("debug"))
            isDebuggable = false
            isProfileable = true
            matchingFallbacks += listOf("debug")
            buildConfigField("boolean", "DEV_TOOLS", "true")
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            // [OpenMinis#363] Produce native-debug-symbols.zip alongside the
            // APK so a stripped release .so can still be symbolicated after
            // the fact (`ndk-stack -sym`, or upload to Play Console). Without
            // it every native frame in a field report is a bare PC and the
            // unstripped objects only exist inside the build tree of whoever
            // happened to compile it.
            //
            // NOT `keepDebugSymbols` (the old doNotStrip): that ships the
            // symbols INSIDE the APK and would add tens of MB to a package
            // that already carries libgojni.so and libonnxruntime.so. The zip
            // is a build artifact to archive, not payload for the device.
            ndk {
                debugSymbolLevel = "FULL"
            }
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        noCompress += listOf("tar.gz", "proot-aarch64")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // [T-android-downgrade-compat] MigrationTestHelper loads the exported
    // schema JSON from the TEST APK's assets, not from the project directory —
    // without this it fails with "Cannot find the schema file in the assets
    // folder" no matter that the files exist on disk.
    sourceSets {
        getByName("androidTest") {
            assets.srcDirs("$projectDir/schemas")
        }
        // perf shares the debug-only assets (debug-server skill bundle).
        getByName("perf") {
            assets.srcDirs("$projectDir/src/debug/assets")
        }
    }

    lint {
        // AGP 8.x ships a NonNullableMutableLiveData detector that throws
        // IncompatibleClassChangeError on Kotlin source during
        // lintVitalAnalyzeRelease, regardless of whether the project uses
        // LiveData (this project does not). The crash happens in the
        // detector's dispatch phase — before the configured `disable` list
        // is consulted — so disabling the rule alone is not enough.
        // checkReleaseBuilds=false skips lintVitalAnalyzeRelease entirely;
        // debug-build lint coverage is unaffected. Re-enable once AGP ships
        // a fixed detector (tracked at issuetracker.google.com/388538014).
        checkReleaseBuilds = false
        disable += "NonNullableMutableLiveData"
    }
}

// [T-android-downgrade-compat] Room needs an explicit output directory once
// `exportSchema = true`. The generated JSON is COMMITTED: it is what
// MigrationTestHelper replays to prove every upgrade — and every no-op
// downgrade — still produces the schema the entities expect. Without it the
// migration chain has no automated check and only a real device install can
// catch a break.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// [T-bash-on-demand] Keep the shared bashism rule table / test vectors as a
// SINGLE source of truth (src/shared/bashism) — copy into assets at build
// time instead of committing duplicate JSON. iOS references the same files as
// bundle resources. Runs before every asset merge so debug/release stay fresh.
val copyBashismRules by tasks.registering(Copy::class) {
    from(rootProject.file("../shared/bashism")) {
        include("bashism_rules.json", "bashism_test_vectors.json")
    }
    into(layout.projectDirectory.dir("src/main/assets/bashism"))
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(copyBashismRules) }
tasks.named("preBuild") { dependsOn(copyBashismRules) }

// [T-android-debugserver-skill] Stage the debug-server skill + an Android
// reference client into the DEBUG-ONLY asset source set, so the debug server
// can serve them over GET /skill (mirrors the iOS "Generate Debug Skill" build
// phase). Single source of truth stays .claude/skills/debug-server/.
//
// Wired to DEBUG asset merges only: src/debug/assets never reaches a release
// APK, so the tooling docs can't ship to users. `assets` is also declared as an
// output so Gradle re-runs this when the skill changes but skips it otherwise.
val stageDebugSkillAssets by tasks.registering(Exec::class) {
    val script = rootProject.file("../../scripts/gen_debug_skill_android.sh")
    val skillDir = rootProject.file("../../.claude/skills/debug-server")
    onlyIf { script.exists() }
    // Declare the inputs only when they exist. `.optional()` covers an unset
    // property, not a path that is absent: Gradle validates inputs before it
    // consults onlyIf, so a missing skill dir fails the build outright. The
    // public mirror has neither the script nor .claude/skills, and must still
    // build.
    if (skillDir.isDirectory) inputs.dir(skillDir)
    if (script.isFile) inputs.file(script)
    outputs.dir(layout.projectDirectory.dir("src/debug/assets/debug-skill"))
    commandLine("bash", script.absolutePath)
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") && (it.name.contains("Debug") || it.name.contains("Perf")) }
    .configureEach { dependsOn(stageDebugSkillAssets) }

// [T-android-about-build-date] Stamp the build time into the APK so About can
// show "Built yyyy-MM-dd HH:mm:ss", like iOS. versionName/versionCode are
// static across rebuilds, so without it an install that silently didn't
// replace the app looks identical to one that did.
//
// An asset, not a BuildConfig field: BuildConfig constants are inlined into
// every class that reads BuildConfig, so a value that changes on every build
// would defeat incremental Kotlin compilation. A generated asset only re-runs
// the asset merge. The task never counts as up to date, so the stamp is the
// time of the build that produced the APK.
abstract class WriteBuildInfoTask : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun write() {
        val file = outputDir.file("build_info/build_time.txt").get().asFile
        file.parentFile.mkdirs()
        file.writeText(System.currentTimeMillis().toString())
    }
}
androidComponents {
    onVariants { variant ->
        val cap = variant.name.replaceFirstChar { it.uppercase() }
        val task = tasks.register<WriteBuildInfoTask>("write${cap}BuildInfo") {
            outputs.upToDateWhen { false }
        }
        variant.sources.assets?.addGeneratedSourceDirectory(task, WriteBuildInfoTask::outputDir)
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // [T-android-tablet-split] Adaptive list-detail layout for tablets/large
    // windows. Version pinned explicitly rather than left to the BOM: the
    // 2025.09.00 BOM does not manage the material3.adaptive group at all, so
    // an unversioned coordinate fails to resolve.
    //
    // 1.2.0, not the newer 1.3.0: 1.3.0 hard-requires compileSdk 37 AND
    // Android Gradle Plugin 9.1.0, and this module is on compileSdk 36 /
    // AGP 8.7.3 — it fails at configuration time, not with a warning. Moving
    // either is a separate, much larger change. 1.2.0 is stable and carries
    // NavigableListDetailPaneScaffold, which is all this needs.
    //
    // The adaptive-navigation3 artifact is NOT used — still alpha, and would
    // require migrating off classic Navigation Compose.
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material3.adaptive:adaptive:1.2.0")
    implementation("androidx.compose.material3.adaptive:adaptive-layout:1.2.0")
    implementation("androidx.compose.material3.adaptive:adaptive-navigation:1.2.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Core
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    // ProcessLifecycleOwner — used by XAIOAuthManager to detect Custom
    // Tab dismissal (T-xai-oauth-stop-resume port iOS d1dbdd5d).
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Security (EncryptedSharedPreferences)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // OkHttp
    // [T-android-vad] Silero v5 VAD (ONNX Runtime + WebRTC APM). The Android
    // build of the exact library iOS uses via SPM, from the same author, so
    // both platforms share one model and one set of thresholds. Carries
    // native .so payloads for ONNX Runtime and the APM — see the abiFilters
    // note in `ndk`; we ship arm64-v8a only.
    implementation("com.github.helloooideeeeea:RealTimeCutVADLibraryForAndroid:1.0.5@aar")

    // rclone, via its official gomobile binding, for backup destinations
    // (SMB / WebDAV / SFTP / S3 / FTP). Build it with
    // `deps/build_rclone_android.sh` — the .aar is a build artifact under
    // app/libs/, not a checked-in binary. Backends are decided by
    // deps/rclone-mobile/backends/backends.go, shared with the iOS build.
    implementation(group = "", name = "rclone", ext = "aar")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")

    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Coil (image loading)
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Markdown rendering (mikepenz multiplatform-markdown-renderer)
    implementation("com.mikepenz:multiplatform-markdown-renderer-android:0.33.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3-android:0.33.0")

    // Chrome Custom Tabs (in-app browser for OAuth)
    implementation("androidx.browser:browser:1.8.0")

    // T-pwa-1: WebViewAssetLoader serves pinned PWA HTML under
    // https://appassets.androidplatform.net/ inside PwaActivity, so
    // sibling CSS/JS resolve against the file's parent dir without
    // granting WebView raw file:// access.
    implementation("androidx.webkit:webkit:1.12.1")

    // Drag-to-reorder for LazyColumn
    implementation("sh.calvin.reorderable:reorderable:2.4.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // T283: ACRA — local crash report capture. acra-core only (no http
    // sender, no network permission). CrashFileSender writes reports to
    // filesDir/logs/ where LogManagementScreen surfaces them.
    implementation("ch.acra:acra-core:5.12.0")

    // T322: Shizuku SDK — offloads privileged Android system APIs (PackageManager,
    // PermissionManager, ActivityManager, AppOps, IInputManager, …) through a
    // user-installed Shizuku app running as adb shell (uid=2000) or root. The CLI
    // surface `android-shizuku-cli` is a NativeOffloadHandler that forwards argv into
    // these hidden APIs via Shizuku's binder. `api` provides the manager binder
    // proxy + permission flow, `provider` registers the in-process content
    // provider that hosts the user-app side of the binder.
    //
    // [T-android-privileged-backend] AXManager (Axeron) needs NO extra
    // dependency: its server is a drop-in Shizuku-protocol implementation that
    // `sendBinder`s into the standard `<applicationId>.shizuku` ShizukuProvider
    // (verified against the installed APK). AxeronBackend therefore rides the
    // same `rikka.shizuku.Shizuku` client + provider declared above. The
    // earlier Axeron-API SDK route was dropped — it duplicated the
    // `moe.shizuku.*` classes (AGP checkDuplicateClasses failure) and pulled an
    // incompatible androidx.core / minSdk for zero added capability.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // Testing — JVM unit tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20231013")
    // [T-android-search-visible-only] Runs the session-search SQL (SQLite JSON functions)
    // against a real SQLite in JVM tests; the same driver Room's compiler already uses.
    testImplementation("org.xerial:sqlite-jdbc:3.41.2.2")

    // Testing — Instrumented (on-device) tests
    // [T-android-downgrade-compat] MigrationTestHelper replays the committed
    // schema json to prove every migration — upgrade AND the no-op downgrade —
    // still lands on the schema the entities expect.
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("junit:junit:4.13.2")
}
