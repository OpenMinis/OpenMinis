import java.util.Properties

// Local instrumentation escape hatch. The release build type is minified by
// R8, and the minified androidTest APK shares the app's classloader; runner
// dependencies that the app never calls (androidx.tracing, Kotlin lazy
// helpers) can be stripped from both dex files, crashing the test process
// before the first test. Production builds keep minification; connected tests
// may pass `-Pminis.skipMinifyForTests=true` to install an unminified pair.
val skipMinifyForTests =
    providers.gradleProperty("minis.skipMinifyForTests").orNull == "true"

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
// value is required (see ClaudeOAuthManager). Publish policy: .github/workflows/android-apk.yml.
val appCustomization = Properties().apply {
    val f = rootProject.file("app/provider-customization.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun customizationValue(key: String): String =
    (appCustomization.getProperty(key) ?: "").replace("\"", "\\\"")

/**
 * [T-build-provenance] Which commit an installed APK was built from.
 *
 * versionCode alone could not answer that: it sat at 220 across eleven commits,
 * so "does the build on this phone already contain fix X?" was only guessable
 * from the install timestamp — and guessing wrong means re-diagnosing a bug
 * that is already fixed, or declaring a fix verified against a build that does
 * not have it. Both happened while reviewing the 2026-10-02 batch.
 *
 * Injected into BuildConfig and logged by EnvironmentBanner at every launch, so
 * a user's bug report and any logcat capture carry it.
 *
 * Both fall back safely for a source tarball or a CI checkout with no `.git`: a
 * build must never fail because provenance is unavailable.
 *
 * Uses `providers.exec`, NOT a raw ProcessBuilder. An external process started
 * straight from the build script is a configuration-cache problem — Gradle
 * reports "external process started 'git rev-parse --short HEAD'" and stores
 * the entry "with problems", which `configuration-cache.problems=warn` hides
 * today but a future Gradle promotes to an error. A ValueSource-backed
 * provider is tracked as a build input and re-queried per build, so the sha
 * also cannot go stale across configuration-cache hits, which a ProcessBuilder
 * result baked in at store time would.
 *
 * Declared as vals, not funs: a top-level `fun` in a .gradle.kts script has no
 * implicit receiver, so it cannot see `providers`.
 */
val gitOutput: (List<String>) -> String = { args ->
    runCatching {
        providers.exec {
            commandLine(listOf("git") + args)
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim()
    }.getOrDefault("")
}

/** Short HEAD sha, or "unknown" when there is no `.git` to ask. */
val gitSha: String =
    gitOutput(listOf("rev-parse", "--short", "HEAD")).ifEmpty { "unknown" }

/**
 * True when the build came from an uncommitted working tree.
 *
 * `git status --porcelain` reports the whole work tree regardless of cwd, so
 * running it from the app module still sees changes elsewhere in the repo.
 * Empty output with a zero exit means CLEAN — which is why this tests the
 * output, not the exit status.
 */
val gitDirty: Boolean = gitOutput(listOf("status", "--porcelain")).isNotEmpty()

android {
    namespace = "com.openminis.app"
    // [T-android-dynamic-island] Bumped 35→36 so the Android 16 (Baklava)
    // Live Updates APIs — Notification.ProgressStyle, FLAG_PROMOTED_ONGOING,
    // NotificationManager.canPostPromotedNotifications(), setShortCriticalText —
    // are available to compile against. targetSdk stays 35 to avoid pulling in
    // Android 16 behavior changes; the Live Updates path is runtime-gated on
    // Build.VERSION.SDK_INT >= 36 (see DynamicIslandSupport / AgentForegroundService).
    compileSdk = 36
    // Pin the same NDK CI installs (ANDROID_NDK=29.0.14206865). Unpinned AGP
    // on GitHub-hosted runners picked 27.0.12077973 and linked the host
    // libunwind.so. r29 still has no usable host libunwind.so; CMake links
    // aarch64 libunwind.a by absolute path. Do not fall back to r28.
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.openminis.linux"
        minSdk = 26
        targetSdk = 35
        versionCode = 251
        versionName = "2.0.51"

        // [T-build-provenance] versionCode alone could not identify a build:
        // it stayed 220 across 11 commits, so "which commit is this APK?" was
        // only answerable from the install timestamp. Both fields degrade
        // gracefully — a source tarball with no .git builds fine and reports
        // "unknown", and the dirty flag distinguishes a rolling build made from
        // a modified working tree (exactly the case that made the 2026-10-02
        // runtime verification ambiguous).
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        buildConfigField("boolean", "GIT_DIRTY", "$gitDirty")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // System prompt prefix required by Anthropic for Claude Code OAuth
        // credentials. Empty in the public mirror (see provider-customization.properties).
        buildConfigField(
            "String",
            "ANTHROPIC_OAUTH_IDENTIFIER_PROMPT",
            "\"${customizationValue("ANTHROPIC_OAUTH_IDENTIFIER_PROMPT")}\""
        )

        ndk {
            abiFilters += listOf("arm64-v8a")
            debugSymbolLevel = "SYMBOL_TABLE"
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
            // A RANGE, not a pin. `3.22.1` alone forces every builder to own that
            // exact CMake, and no aarch64 CMake distribution ships it in a
            // ready-to-use form: Kitware's cmake-3.22.1-linux-aarch64 tarball has
            // no `bin/ninja` (AGP requires one next to cmake) and no
            // `source.properties`, so an arm64 builder had to hand-install a
            // ninja before the native build would even configure.
            //
            // `3.22.1+` (AGP's CmakeVersionRequirements.dslVersionHasPlus) means
            // "3.22.1 or higher", so an arm64 host uses whatever CMake its SDK
            // already has (3.31.6 today) while x86_64 CI — which sdkmanager-installs
            // exactly `cmake;3.22.1` and nothing else — still resolves 3.22.1 and
            // stays byte-for-byte reproducible.
            version = "3.22.1+"
        }
    }

    fun envOrBlank(key: String): String =
        System.getenv(key)?.takeIf { it.isNotBlank() } ?: ""

    val signingProps = Properties().apply {
        val f = rootProject.file("signing.properties")
        if (f.isFile) f.inputStream().use { load(it) }
    }

    fun resolveSigningStoreFile(): java.io.File? {
        val fromEnv = envOrBlank("MINIS_UPLOAD_STORE_FILE")
        if (fromEnv.isNotEmpty()) {
            val f = file(fromEnv)
            if (f.isFile) return f
        }
        val fromProps = signingProps.getProperty("signing.storeFile")?.trim().orEmpty()
        if (fromProps.isNotEmpty()) {
            listOf(rootProject.file(fromProps), file(fromProps))
                .firstOrNull { it.isFile }
                ?.let { return it }
        }
        return rootProject.file("release.keystore").takeIf { it.isFile }
    }

    val uploadStoreFile = resolveSigningStoreFile()
    val hasUploadKeystore = uploadStoreFile != null
    // [T-signing-secret-untracked] signing.properties is no longer in git, so a
    // fresh clone resolves a keystore with a blank password and fails inside
    // apksigner with a message about a corrupt file. Say what is actually
    // missing instead.
    if (hasUploadKeystore &&
        envOrBlank("MINIS_UPLOAD_STORE_PASSWORD").isEmpty() &&
        signingProps.getProperty("signing.storePassword").isNullOrBlank()
    ) {
        logger.warn(
            "Release signing: keystore ${uploadStoreFile?.name} found but no password. " +
                "Set MINIS_UPLOAD_STORE_PASSWORD (and MINIS_UPLOAD_KEY_*), or copy " +
                "signing.properties.example to signing.properties and fill it in. " +
                "Without a password the signRelease task will fail.",
        )
    }
    signingConfigs {
        if (hasUploadKeystore) {
            create("releaseUpload") {
                storeFile = uploadStoreFile!!
                storePassword = envOrBlank("MINIS_UPLOAD_STORE_PASSWORD")
                    .ifEmpty { signingProps.getProperty("signing.storePassword") ?: "" }
                keyAlias = envOrBlank("MINIS_UPLOAD_KEY_ALIAS")
                    .ifEmpty { signingProps.getProperty("signing.keyAlias") ?: "" }
                keyPassword = envOrBlank("MINIS_UPLOAD_KEY_PASSWORD")
                    .ifEmpty { signingProps.getProperty("signing.keyPassword") ?: "" }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = !skipMinifyForTests
            isShrinkResources = !skipMinifyForTests
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Prefer the committed upload keystore (src/android/release.keystore)
            // so CI and local assembleRelease share one cert. MINIS_UPLOAD_* env
            // overrides. Debug keystore is last-resort only.
            signingConfig = if (hasUploadKeystore) {
                signingConfigs.getByName("releaseUpload")
            } else {
                signingConfigs.getByName("debug")
            }
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
        noCompress += listOf("tar.gz", "proot-aarch64", "gz")
    }

    packaging {
        jniLibs {
            pickFirsts += "lib/*/libtermux.so"
        }
        resources {
            // jsch (mwiede fork) and jspecify both ship an OSGi manifest under
            // META-INF/versions/9 — mergeReleaseJavaResource fails on the
            // duplicate. Useless on Android anyway. Both the glob and the
            // literal path are listed so the fix holds regardless of which
            // pattern syntax this AGP version honors.
            excludes += listOf(
                "META-INF/versions/*/OSGI-INF/**",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }


    testOptions {
        unitTests.isReturnDefaultValues = true
        // [T-restore-txn-integration] Robolectric needs the app resource table
        // merged into the unit-test classpath to build its framework shadow
        // (the archive/restore transaction tests run a real in-memory Room
        // database). Pure-JVM tests are unaffected — they never touch
        // resources.
        unitTests.isIncludeAndroidResources = true
    }

    // [T-androidtest-release] This project has no debug buildType, and AGP
    // hangs androidTest on debug by default — which silently meant the
    // androidTest sourceSet (MessageVersionMigrationTest, archive-tx tests)
    // had NO compile task at all: assembleAndroidTest was an empty UP-TO-DATE
    // shell and syntax errors in those files were invisible. Point the test
    // build type at release so the androidTest compile task exists.
    testBuildType = "release"

    // [T-android-downgrade-compat] MigrationTestHelper loads the exported
    // schema JSON from the TEST APK's assets, not from the project directory —
    // without this it fails with "Cannot find the schema file in the assets
    // folder" no matter that the files exist on disk.
    sourceSets {
        getByName("androidTest") {
            assets.srcDirs("$projectDir/schemas")
        }
        // First APK: if rclone.aar is absent (Go/gomobile not required), compile
        // the stub so :app:compile*Kotlin does not fail on Gomobile symbols.
        getByName("main") {
            if (!file("libs/rclone.aar").isFile) {
                java.srcDir("src/rcloneStub/java")
            }
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

// [T-ci-build-speed] Disable the debug variant so kspDebug and kspRelease
// never race on the same Room schema JSON (see .github/workflows/android-apk.yml
// for the original --no-parallel workaround). Removing the race lets CI switch to
// --parallel; local debug builds can re-enable the variant on a local branch.
// AssembleDebug was never part of CI or release tooling.
androidComponents {
    beforeVariants { variantBuilder ->
        if (variantBuilder.buildType == "debug") {
            variantBuilder.enable = false
        }
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
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") && it.name.contains("Debug") }
    .configureEach { dependsOn(stageDebugSkillAssets) }

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":provider:api"))
    implementation(project(":hostcapability:api"))
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
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    // ProcessLifecycleOwner — used by XAIOAuthManager to detect Custom
    // Tab dismissal (T-xai-oauth-stop-resume port iOS d1dbdd5d).
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    // androidx.profileinstaller removed: no baseline profile exists to install —
    // `git ls-files` has no baseline-prof.txt / baselineProfiles source anywhere,
    // and :benchmark (settings.gradle.kts) is never assembled by CI or the docs,
    // so its BaselineProfileGenerator cannot have produced one. The library
    // therefore only added a startup ContentProvider/ProfileInstaller pass that
    // found nothing. RE-ADD IT together with the missing wiring if the profile
    // is ever produced: run :benchmark:assembleBenchmarkBaselineProfile (or apply
    // the androidx.baselineprofile plugin), commit the output to
    // app/src/main/baseline-prof.txt, and add a CI job — sideloaded installs (this
    // project ships APKs from GitHub Releases) rely on profileinstaller at first
    // launch on API 28 and below, so the two belong in the same change.
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // androidx.datastore:datastore-preferences removed — zero usage: `git grep
    // androidx.datastore` over src/android matched only this declaration, no
    // `preferencesDataStore`/`dataStore` builder and no import anywhere in app,
    // :core:model, :core:common, :provider:api or :hostcapability:api. Every
    // settings store in this app is SharedPreferences or
    // androidx.security EncryptedSharedPreferences, and PendingUpdateStore.kt
    // documents deliberately avoiding DataStore.

    // Security (EncryptedSharedPreferences)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // OkHttp
    // [T-android-vad] Silero v5 VAD (ONNX Runtime + WebRTC APM). The Android
    // build of the exact library iOS uses via SPM, from the same author, so
    // both platforms share one model and one set of thresholds. Carries
    // native .so payloads for ONNX Runtime and the APM — see the abiFilters
    // note in `ndk`; we ship arm64-v8a only.
    implementation("com.github.helloooideeeeea:RealTimeCutVADLibraryForAndroid:1.0.5@aar")

    // rclone gomobile AAR is gitignored (~15MB). First APK compiles without Go
    // via src/rcloneStub. Remote backup destinations need the real AAR:
    // host Go 1.22+ + gomobile, then ./deps/build_rclone_android.sh
    val rcloneAar = file("libs/rclone.aar")
    if (rcloneAar.isFile) {
        implementation(group = "", name = "rclone", ext = "aar")
    } else {
        logger.lifecycle(
            "rclone.aar missing — compiling rcloneStub. " +
                "SMB/WebDAV/SFTP/S3/FTP backup needs Go+gomobile " +
                "(deps/build_rclone_android.sh). See BUILDING.md / LINUX.md.",
        )
    }

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.mozilla:rhino:1.7.14")
    // com.squareup.okhttp3:okhttp-sse removed — never imported: `git grep -E
    // "okhttp3\.sse|EventSources|EventSourceListener"` over src/android found
    // zero hits. Streaming is parsed off the raw response body on purpose
    // (AnthropicProvider reads `source().readUtf8Line()` and keeps lines that
    // start with "data:", OpenAIRawStream sniffs the content type), because the
    // providers emit `text/event-stream` bodies that are NOT strictly SSE —
    // Responses API interleaves bare JSON, and okhttp-sse's parser would drop
    // frames the hand-rolled loop still handles.

    // [T-ssh-backend] JSch (mwiede maintained fork) — pure-Java SSH/SFTP
    // client for the remote execution backend. Chosen over sshj: no
    // BouncyCastle requirement (sshj drags bcprov, which collides with the
    // Android platform's stripped BC provider), modern algorithms built in
    // (rsa-sha2, ssh-ed25519, chacha20-poly1305), and a long Android track
    // record. Runs in the host app process, so guest paths must be resolved
    // to host paths before SFTP transfers.
    implementation("com.github.mwiede:jsch:0.2.26")

    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Coil (image loading)
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-svg:2.7.0")

    // Markdown rendering (mikepenz multiplatform-markdown-renderer)
    implementation("com.mikepenz:multiplatform-markdown-renderer-android:0.33.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3-android:0.33.0")

    // Chrome Custom Tabs (in-app browser for OAuth)
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.documentfile:documentfile:1.0.1")

    // T-pwa-1: WebViewAssetLoader serves pinned PWA HTML under
    // https://appassets.androidplatform.net/ inside PwaActivity, so
    // sibling CSS/JS resolve against the file's parent dir without
    // granting WebView raw file:// access.
    implementation("androidx.webkit:webkit:1.12.1")

    // Drag-to-reorder for LazyColumn
    implementation("sh.calvin.reorderable:reorderable:2.4.0")

    // Termux VT emulator (replaces the hand-rolled ANSI parser for the PTY UI)
    implementation("com.termux.termux-app:terminal-view:0.118.0")

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
    // [T-restore-txn-integration] Robolectric runs the archive/restore Room
    // transactions against a real in-memory SQLite. RestoreOrderTest proves
    // the pure ordering function, but the two-pass scratch renumbering, the
    // unique (session_id, sort_order) index and the chunked archive
    // retirement only exist against a real database. 4.14.1 covers SDK 35
    // (= targetSdk); pinned via @Config so a compileSdk bump never silently
    // changes which framework the tests run on.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")

    // Testing — Instrumented (on-device) tests
    // [T-android-downgrade-compat] MigrationTestHelper replays the committed
    // schema json to prove every migration — upgrade AND the no-op downgrade —
    // still lands on the schema the entities expect.
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("junit:junit:4.13.2")
}

// JVM unit tests: Gradle's condensed console prints only
// "AssertionError at FooTest.kt:12", which hides the expected/actual values a
// red build needs. Full exception output makes a CI failure actionable from
// the job log alone — no artifact download, no --info noise.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showCauses = true
        showStackTraces = true
    }
}
