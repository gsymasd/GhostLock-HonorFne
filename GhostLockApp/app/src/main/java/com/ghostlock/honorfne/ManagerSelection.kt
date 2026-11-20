package com.ghostlock.honorfne

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Root 管理器选择（移植月虹 1.7.0 的多管理器思路）。
 *
 * 旧版 HonorRoot 把 `me.weishu.kernelsu` 写死在三个地方（ExtractKitRunner /
 * KsuUserspace / KsuVersionGuard），用户装了 KernelSU Next 等兼容管理器时，
 * 要么守卫把它卸掉重装官方版，要么 payload 找不到目标管理器的 libksud.so。
 *
 * 这里不再假定某个固定包名：扫描已经安装、且 APK 里带 `libksud.so` 的应用，
 * 让用户选一个；选择落到 SharedPreferences，链路 A/B 与守卫都读同一个值。
 * 扫描完全走 Shizuku shell，不需要 root。
 *
 * 2026-11-20 修正（自定义分支适配）：
 *  - 过滤规则从 `contains("ksu")` 改为 [ManagerCatalog]：7kimisu(com.sevenk.core)、
 *    SukiSU-Nova(com.sukisu.nova)、BakaSU(com.resukisu.resukisu) 的包名里
 *    都没有 "ksu" 子串，旧规则会把它们全部漏掉。
 *  - 路径获取从「每个候选一次 pm path」改为一次 `pm list packages -f`，
 *    候选再多也只有一次 shell 往返。
 *  - libksud.so 判定先直接 grep APK 的 zip 目录（明文文件名），不依赖设备
 *    有没有 unzip；grep 失败才回退 unzip。
 *
 * 2026-11-21 修正（扫描结果不再被静默吞掉）：
 *  - 以前 `hasKsud()` 不通过的候选会被直接丢掉，列表为空时界面只报
 *    「未扫描到兼容管理器」，用户无法判断是通道断了还是过滤掉了。现在
 *    关键字/已知包名命中的**全部保留**，用 [Manager.hasKsud] 标出是否带 ksud，
 *    由界面决定是直选还是二次确认。
 *  - 新增兜底：若第一轮没有任何带 ksud 的候选，则遍历用户应用
 *    （`pm list packages -3 -f`）按 libksud.so 复核一遍，自定义包名的管理器
 *    也能被扫出来。
 *  - 新增 [lastScanInfo]，记录每一步的 shell 退出码/行数/命中数，供界面与日志
 *    排障。
 */
object ManagerSelection {
    const val DEFAULT_PACKAGE = ManagerCatalog.OFFICIAL_PKG
    private const val PREFS = "root_manager_selection"
    private const val KEY_PACKAGE = "package_name"
    private const val KEY_LABEL = "label"
    private const val MAX_FALLBACK_APPS = 150
    private val PACKAGE_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

    data class Manager(
        val packageName: String,
        val label: String,
        val apkPath: String,
        /** 该 APK 内是否带 libksud.so（真正能驱动内核的判据）。 */
        val hasKsud: Boolean = false
    )

    /** 最近一次 [scan] 的过程摘要，供界面 toast / 日志排障。 */
    @Volatile
    var lastScanInfo: String = ""
        private set

    fun selected(context: Context): String {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PACKAGE, null)
            ?.trim()
        return if (!raw.isNullOrBlank() && PACKAGE_PATTERN.matches(raw)) raw else DEFAULT_PACKAGE
    }

    fun selectedLabel(context: Context): String {
        val pkg = selected(context)
        return context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LABEL, null)
            ?.takeIf { it.isNotBlank() }
            ?: ManagerCatalog.labelOf(pkg)
    }

    fun save(context: Context, manager: Manager) {
        val pkg = manager.packageName.trim()
            .takeIf { PACKAGE_PATTERN.matches(it) } ?: DEFAULT_PACKAGE
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PACKAGE, pkg)
            .putString(KEY_LABEL, manager.label)
            .commit()
    }

    fun reset(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PACKAGE)
            .remove(KEY_LABEL)
            .commit()
    }

    /** 当前选择是否与内置 kernelsu.ko 配套（官方签名）。 */
    fun selectedBundledKo(context: Context): Boolean =
        ManagerCatalog.bundledKoFor(selected(context))

    /**
     * 找出设备上已安装的 Root 管理器。
     *
     * 第一轮：已知包名 ∪ 包名关键字命中，逐个复核 APK 是否带 libksud.so；
     * 兜底轮：若第一轮没有带 ksud 的候选，遍历用户应用按 libksud.so 复核，
     *         覆盖包名完全自定义的分支。
     *
     * 所有命中项都会返回（[Manager.hasKsud] 标明是否带 ksud），不再静默丢弃。
     */
    suspend fun scan(@Suppress("UNUSED_PARAMETER") context: Context): List<Manager> =
        withContext(Dispatchers.IO) {
            val listedRes = ShizukuController.exec("pm list packages -f 2>/dev/null", 45_000)
            val listed = parsePackageLines(listedRes.out)
            val info = StringBuilder()
            info.append("pm -f code=").append(listedRes.code)
                .append(" lines=").append(listedRes.out.lines().size)
                .append(" parsed=").append(listed.size)

            if (listed.isEmpty()) {
                info.append(" [通道").append(
                    if (ShizukuController.channelDead(listedRes)) "已断，需重新连接 Shizuku" else "无输出"
                ).append(']')
                val sample = listedRes.out.lines().firstOrNull { it.isNotBlank() }
                if (sample != null) info.append(" sample=").append(sample.take(80))
                lastScanInfo = info.toString()
                return@withContext emptyList()
            }

            val byPkg = LinkedHashMap<String, Manager>()
            val matched = listed
                .filter { (pkg, _) -> ManagerCatalog.looksLikeManager(pkg) }
                .distinctBy { it.first }
            for ((pkg, apk) in matched) {
                byPkg[pkg] = Manager(pkg, ManagerCatalog.labelOf(pkg), apk, hasKsud(apk))
            }
            info.append(" matched=").append(matched.size)
                .append(" withKsud=").append(byPkg.values.count { it.hasKsud })

            /* 兜底：包名不含关键字、也不在已知表里的自定义分支。 */
            if (byPkg.values.none { it.hasKsud }) {
                val userRes = ShizukuController.exec("pm list packages -3 -f 2>/dev/null", 30_000)
                val users = parsePackageLines(userRes.out).take(MAX_FALLBACK_APPS)
                for ((pkg, apk) in users) {
                    if (byPkg.containsKey(pkg)) continue
                    if (hasKsud(apk)) {
                        byPkg[pkg] = Manager(pkg, ManagerCatalog.labelOf(pkg), apk, true)
                    }
                }
                info.append(" fallback code=").append(userRes.code)
                    .append(" apps=").append(users.size)
                    .append(" found=").append(byPkg.size)
            }

            lastScanInfo = info.toString()
            byPkg.values.sortedWith(
                compareByDescending<Manager> { it.packageName == DEFAULT_PACKAGE }
                    .thenByDescending { it.hasKsud }
                    .thenBy { it.label }
            )
        }

    /** 解析 `package:/data/app/.../base.apk=com.foo` 形式的行。 */
    private fun parsePackageLines(out: String): List<Pair<String, String>> =
        out.lines().mapNotNull { line ->
            val t = line.trim().removePrefix("package:")
            val eq = t.lastIndexOf('=')
            if (eq <= 0) return@mapNotNull null
            val apk = t.substring(0, eq).trim()
            val pkg = t.substring(eq + 1).trim()
            if (apk.startsWith("/") && PACKAGE_PATTERN.matches(pkg)) pkg to apk else null
        }

    /**
     * APK 里是否带 `lib/<abi>/libksud.so`。
     *
     * zip 的中央目录里文件名是明文，`grep -a` 一次就能判，比 unzip 列目录快一个
     * 数量级；部分 ROM 的 grep 不带 -a 时输出会被当二进制吞掉，故保留 unzip 回退。
     */
    private suspend fun hasKsud(apk: String): Boolean {
        val g = ShizukuController.exec(
            "grep -aq 'libksud\\.so' '$apk' 2>/dev/null && echo YES || echo NO", 15_000
        )
        if (g.out.contains("YES")) return true
        val u = ShizukuController.exec(
            "unzip -Z1 '$apk' 2>/dev/null | grep -c 'libksud\\.so'", 20_000
        )
        return (u.out.trim().toIntOrNull() ?: 0) > 0
    }

    suspend fun installed(@Suppress("UNUSED_PARAMETER") context: Context, pkg: String): Boolean =
        withContext(Dispatchers.IO) {
            ShizukuController.exec("pm path $pkg 2>/dev/null", 12_000).out.contains("package:")
        }
}
