package com.openminis.app.security

/**
 * Adapted from XINCODE-Public SecurityGate (GPL-3.0-or-later).
 * https://github.com/kusesad-1122/XINCODE-Public
 */
interface SecurityGate {
    fun setPermissionMode(mode: PermissionMode)
    fun getPermissionMode(): PermissionMode
    fun classify(toolName: String, toolArgs: String): GateCommand
    fun classifyRisk(command: String): RiskLevel
    fun decide(cmd: GateCommand, mode: PermissionMode): Decision
    fun preview(cmd: GateCommand): String
    fun audit(cmd: GateCommand, decision: Decision, result: String?)
    fun getAuditTrail(): List<AuditEntry>
    fun setPermissionRules(rules: List<PermissionRule>) {}
    fun setAuthorityProfile(profile: PermissionProfile?) {}
    fun verifyAuditChain(): AuditChainVerification = AuditChainVerification(ok = true)
}

data class AuditChainVerification(
    val ok: Boolean,
    val brokenAt: Int? = null,
)

data class AuditEntry(
    val timestamp: Long,
    val toolName: String,
    val toolArgs: String,
    val capability: Capability,
    val reversibility: Reversibility,
    val decision: String,
    val result: String?,
    val prevHash: String = "",
    val hash: String = "",
)

// ── Authority profile ────────────────────────────────────────────────────
// [T-permission-intersection-removal] These types lived in
// security/PermissionIntersection.kt, whose rest (intersectProfiles /
// IntersectionResult and the public path helpers) had zero callers anywhere:
// no call path ever intersected two profiles. Only this model + the `allows`
// extension are live — SecurityGateImpl consults `authority.allows(path,
// write)` on the setAuthorityProfile below — so they move here and the
// intersection subsystem is deleted. Adapted from XINCODE-Public / OpenAI
// Codex permission profiles (GPL-3.0-or-later / Apache-2.0 respectively).

/** 文件访问位。 */
enum class FsAccess {
    READ,
    READ_WRITE;

    fun allowsWrite() = this == READ_WRITE
}

enum class FsPolicyKind { UNRESTRICTED, RESTRICTED }

/** 一条文件授权:路径前缀 + 访问位。deny 由“不在授权内”表达(默认拒绝)。 */
data class FsGrant(val pathPrefix: String, val access: FsAccess)

/** 文件沙箱策略。 */
data class FsPolicy(val kind: FsPolicyKind, val grants: List<FsGrant>) {
    companion object {
        fun unrestricted() = FsPolicy(FsPolicyKind.UNRESTRICTED, emptyList())
        fun restricted(vararg grants: FsGrant) = FsPolicy(FsPolicyKind.RESTRICTED, grants.toList())
    }
}

enum class NetPolicy { ENABLED, RESTRICTED }

/** 一份权限剖面:文件 + 网络。 */
data class PermissionProfile(val fs: FsPolicy, val net: NetPolicy) {
    companion object {
        /** 全开(仅 as 收敛输入的一边有意义,绝不能直接授予)。 */
        fun unrestricted() = PermissionProfile(FsPolicy.unrestricted(), NetPolicy.ENABLED)

        /** 只读工作区(最常用的收敛结果形状)。 */
        fun readOnlyWorkspace(workspaceRoot: String) = PermissionProfile(
            FsPolicy.restricted(FsGrant(normalizeFsPath(workspaceRoot), FsAccess.READ)),
            NetPolicy.RESTRICTED
        )

        /** 可写工作区(对应 Codex :workspace 内涵:可改检出,不出网)。 */
        fun workspace(workspaceRoot: String) = PermissionProfile(
            FsPolicy.restricted(FsGrant(normalizeFsPath(workspaceRoot), FsAccess.READ_WRITE)),
            NetPolicy.RESTRICTED
        )
    }
}

/** 用剖面判定单次访问(默认拒绝:不在任何授权前缀内即拒)。 */
fun PermissionProfile.allows(path: String, write: Boolean): Boolean {
    if (fs.kind == FsPolicyKind.UNRESTRICTED) return true
    val p = normalizeFsPath(path)
    return fs.grants.any {
        prefixCovers(normalizeFsPath(it.pathPrefix), p) && (!write || it.access.allowsWrite())
    }
}

/**
 * 路径归一化(canonicalize + UnsupportedPath 校验的轻量版):
 * 反斜杠转正、去重复分隔、解“.”、拒绝“..”越界与空路径。符号链接不解析
 * (Android 上 /sdcard 这类马甲太多,解析反而制造歧义;调用方应在入口处
 * 先 realpath,本函数对含 link 的前缀一律按字面比较)。
 */
internal fun normalizeFsPath(raw: String): String {
    val p = raw.trim().replace('\\', '/')
    require(p.isNotEmpty()) { "路径不能为空" }
    val parts = mutableListOf<String>()
    for (seg in p.split('/')) {
        when {
            seg.isEmpty() || seg == "." -> {}
            seg == ".." -> {
                require(parts.isNotEmpty() && !(parts.size == 1 && parts[0].isEmpty())) {
                    "路径越界(.. 逃出根,得 $raw)"
                }
                parts.removeAt(parts.lastIndex)
            }
            else -> parts.add(seg)
        }
    }
    val rooted = p.startsWith("/")
    val joined = parts.joinToString("/")
    return (if (rooted) "/" else "") + joined
}

/** prefix 是否覆盖 path(边界对齐:/a/b 覆盖 /a/b/c,不覆盖 /a/bc)。 */
internal fun prefixCovers(prefix: String, path: String): Boolean {
    if (prefix == "/") return path.startsWith("/")
    if (path == prefix) return true
    return path.startsWith(prefix.trimEnd('/') + "/")
}
