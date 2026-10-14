pluginManagement {
    includeBuild("build-logic")
    val extraMirrors = run {
        val mode = (System.getenv("MINIS_BUILD_MIRRORS") ?: "auto").lowercase()
        val extra = mutableListOf<String>()
        val propsFile = file("mirrors.local.properties")
        if (propsFile.isFile) {
            val p = java.util.Properties()
            propsFile.inputStream().use { p.load(it) }
            extra += p.getProperty("maven.urls", "")
                .split(',', '\n')
                .map { it.trim() }
                .filter { it.startsWith("https://") }
        }
        extra += (System.getenv("MINIS_MAVEN_URLS") ?: "")
            .split(',', ';')
            .map { it.trim() }
            .filter { it.startsWith("https://") }
        val cn = listOf(
            "https://maven.aliyun.com/repository/google",
            "https://maven.aliyun.com/repository/public",
            "https://maven.aliyun.com/repository/gradle-plugin",
            "https://maven.aliyun.com/repository/central",
            "https://repo.huaweicloud.com/repository/maven",
            "https://mirrors.cloud.tencent.com/nexus/repository/maven-public",
        )
        val useCn = when (mode) {
            "off" -> false
            "cn" -> true
            else -> extra.isNotEmpty()
        }
        extra.ifEmpty { if (useCn) cn else emptyList() }.distinct()
    }
    repositories {
        extraMirrors.forEach { u -> maven { url = uri(u) } }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        minisCollectMavenMirrors().forEach { u ->
            maven {
                url = uri(u)
                // JitPack-only artifacts (com.github.*) are not mirrored by
                // the CN proxies — asking them for those paths hangs the TLS
                // read instead of 404ing quickly. Route com.github straight
                // to jitpack.io below.
                content { excludeGroupByRegex("com\\.github\\..*") }
            }
        }
        google()
        mavenCentral()
        // [T-android-vad] RealTimeCutVADLibraryForAndroid ships via JitPack
        // only. Same author and same underlying stack (Silero + ONNX Runtime +
        // WebRTC APM) as the RealTimeCutVADLibrary SPM package iOS already
        // uses, so both platforms segment speech with the same model and the
        // same tunables.
        maven { url = uri("https://jitpack.io") }
        // Termux terminal-view (VT emulator). Published to Sonatype OSS;
        // mavenCentral sometimes lags the 0.118.0 artifacts CI needs.
        maven { url = uri("https://s01.oss.sonatype.org/content/repositories/releases/") }
        // rclone.aar — the backup feature's remote destinations (SMB / WebDAV /
        // SFTP / S3 / FTP). Not published to any Maven repo: it is built from
        // deps/rclone-mobile by `deps/build_rclone_android.sh`, which is also
        // what produces the iOS XCFramework from the same Go sources and the
        // same trimmed backend list. Treated as a build artifact, not a vendored
        // binary (backup-restore design doc §6.2).
        // First APK builds may omit the AAR; app/build.gradle.kts then compiles
        // src/rcloneStub instead.
        flatDir { dirs("app/libs") }
    }
}

rootProject.name = "MinisUltra"
include(":app")
include(":core:model")
include(":core:common")
include(":provider:api")
include(":hostcapability:api")
include(":benchmark")

/**
 * Prepend China-reachable Maven mirrors when MINIS_BUILD_MIRRORS=cn, or when
 * auto mode finds src/android/mirrors.local.properties (from
 * scripts/pick_build_mirrors.py). Never pins a single URL: several candidates
 * are listed, then google()/mavenCentral() remain as fallback.
 *
 * MINIS_BUILD_MIRRORS=auto|cn|off
 * MINIS_MAVEN_URLS=https://...,https://...
 */
fun minisCollectMavenMirrors(): List<String> {
    val mode = (System.getenv("MINIS_BUILD_MIRRORS") ?: "auto").lowercase()
    val extra = mutableListOf<String>()
    val propsFile = file("mirrors.local.properties")
    if (propsFile.isFile) {
        val p = java.util.Properties()
        propsFile.inputStream().use { p.load(it) }
        extra += p.getProperty("maven.urls", "")
            .split(',', '\n')
            .map { it.trim() }
            .filter { it.startsWith("https://") }
    }
    extra += (System.getenv("MINIS_MAVEN_URLS") ?: "")
        .split(',', ';')
        .map { it.trim() }
        .filter { it.startsWith("https://") }
    val cn = listOf(
        "https://maven.aliyun.com/repository/google",
        "https://maven.aliyun.com/repository/public",
        "https://maven.aliyun.com/repository/gradle-plugin",
        "https://maven.aliyun.com/repository/central",
        "https://repo.huaweicloud.com/repository/maven",
        "https://mirrors.cloud.tencent.com/nexus/repository/maven-public",
    )
    val useCn = when (mode) {
        "off" -> false
        "cn" -> true
        else -> extra.isNotEmpty()
    }
    return extra.ifEmpty { if (useCn) cn else emptyList() }.distinct()
}
