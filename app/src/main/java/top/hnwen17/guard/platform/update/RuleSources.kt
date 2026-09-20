package top.hnwen17.guard.platform.update

/**
 * 阶段4：规则包托管源集中配置（多源回退）。
 *
 * 更换托管位置时只需修改本文件 BASES；客户端按顺序尝试，第一个成功的源生效。
 * 无论从哪个源取得，规则包都必须通过验签（RuleSignatureVerifier，P16 通道）才能落地；
 * 未验签包仅允许以「仅观察」状态进入（阶段3 订阅页语义）。
 */
object RuleSources {
    /** 按优先级排列的源基址（全部 https）。 */
    val BASES: List<String> = listOf(
        "https://raw.githubusercontent.com/canyexuanfan/qinghu/main/rules-dist"
        // 备用源可追加在此（如镜像站、Release 附件直链）
    )

    /** 预置推荐订阅的规则包文件名（与 rules-dist/ 产物一致）。 */
    const val RECOMMENDED_PACK = "litiaotiao-custom-rules.json"

    /** 版本清单文件名（几百字节；内容不变则不下载主包）。 */
    const val VERSION_FILE = "version.json"

    fun packUrls(name: String): List<String> = BASES.map { "$it/$name" }
}
