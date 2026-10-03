package io.github.madeye.meow.repo

/**
 * Decides whether a package or class name looks like a Chinese app/SDK.
 *
 * The same approach that sing-box for Android, FlClash, and husi converged
 * on: a package name is domestic when it starts with a known Chinese vendor
 * prefix (`com.tencent`, `tv.danmaku`, …), and an app is also domestic when
 * one of its declared manifest components — including the `<application>`
 * class itself — lives under such a prefix. CN apps almost always embed a CN
 * SDK (Bugly, Umeng, MiPush, JPush, …) or a CN packer stub (`com.secneo`,
 * `s.h.e.l.l`, `com.ijiami`), which leaks the origin even when the package
 * name itself is neutral.
 *
 * Pure JVM: no Android imports so the matcher is unit-testable. The prefix
 * lists deliberately match unanchored (`com.ali` must catch `com.aliyun`,
 * `com.qihoo` must catch `com.qihoo360`), which is also why [SKIP_PREFIXES]
 * exists for the known foreign collisions (`com.mx` → MX Player, `com.stub`
 * → StubHub, `com.vivo` → Vivox voice SDK).
 *
 * Trade-off worth knowing: prefixes are vendor signals, not product signals.
 * Chinese vendors' international products (Kwai, NetEase global games, …)
 * still classify domestic — acceptable because their servers are reachable
 * directly, so bypassing the tunnel rarely hurts. The exceptions that do
 * hurt — products whose servers need the tunnel — go on [SKIP_PREFIXES].
 * The same applies at component level: a foreign app that embeds a Chinese
 * SDK (HMS, MiPush, Pangle/ByteDance ads, Alipay, UnionPay) classifies
 * domestic — usually the right call anyway, since that build was targeted
 * at the Chinese market.
 *
 * Maintenance: any change to these lists invalidates cached verdicts —
 * bump `DomesticAppClassifier.CACHE_VERSION` in the same commit.
 */
object ChinaPackageMatcher {

    /**
     * Foreign packages that would otherwise trip a CN prefix, or products of
     * Chinese vendors that need the tunnel. Matched exactly or at a `.`
     * boundary (`com.tencent.ig` does not cover `com.tencent.iglite`), so each
     * variant needs its own entry. Applied to component class names too.
     */
    private val SKIP_PREFIXES = listOf(
        "com.google",
        "com.android.chrome",
        "com.android.vending",
        "com.microsoft",
        "com.apple",
        // CN-vendor products that need the tunnel anyway:
        "com.zhiliaoapp.musically",  // TikTok
        "com.ss.android.ugc.trill",  // TikTok's other package — trips "com.ss.android"
        "com.tencent.ig", "com.tencent.iglite", // PUBG Mobile global
        "com.iqiyi.i18n",            // 爱奇艺国际版 — trips "com.iqiyi"
        "com.bilibili.app.in",       // bilibili intl — trips "com.bilibili"
        "com.didiglobal.passenger",  // DiDi international
        "com.didiglobal.driver", "com.didiglobal.food", // DiDi driver/food intl — same trap
        "com.miHoYo.GenshinImpact",  // Genshin global (CN build is com.miHoYo.Yuanshen)
        // Honkai 3rd has a package per overseas region; the dot-boundary
        // skip can't wildcard them (bh3oversea_vn is not a bh3oversea.* child).
        "com.miHoYo.bh3oversea", "com.miHoYo.bh3oversea_vn", "com.miHoYo.bh3global",
        "com.miHoYo.bh3rdJP", "com.miHoYo.bh3tw", "com.miHoYo.bh3korea",
        "com.miHoYo.tot.glb",        // Tears of Themis global — same trap
        "com.taptap.global",         // TapTap international — trips "com.taptap"
        // Collisions of unanchored prefixes, observed as false positives:
        "com.mxtech.videoplayer",    // MX Player, trips "com.mx" (Maxthon)
        "com.stubhub",               // trips "com.stub" (packer stub)
        "com.vivox",                 // US voice SDK embedded in games, trips "com.vivo"
        "com.alightcreative",        // Alight Motion, trips "com.ali"
        "com.alienware",
        "com.pikcloud.pikpak",       // bundles MiPush/Umeng/Bugly
    )

    private val CN_PREFIXES = listOf(
        // Big vendors and their apps
        "com.tencent",        // incl. WeChat com.tencent.mm, qq.e ad SDK
        "com.alibaba", "com.ali", "com.alipay", "com.taobao", "com.tmall",
        "com.eg.android",     // Alipay: com.eg.android.AlipayGphone
        "com.amap", "com.autonavi", "com.UCMobile",
        "com.baidu",
        "com.qihoo",
        "com.sina", "com.weibo",
        "com.netease",
        "com.sohu",
        "com.bytedance", "com.ss.android", // com.ss.android.ugc.aweme = 抖音
        "com.kuaishou", "com.kwai", "com.smile.gifmaker", // 快手
        "tv.danmaku", "com.bilibili",
        "com.xiaomi", "com.miui", "com.huawei", "com.hihonor",
        "com.vivo", "com.oppo", "com.coloros", "com.iqoo", "com.oplus",
        // ("andes.oplus" dropped: no such reversed domain, and com.oplus
        // already covers the real com.oplus.andes* push packages.)
        "com.heytap", "com.oneplus", "com.realme",
        "com.meizu", "com.gionee", "com.nubia", "cn.nubia",
        "com.smartisan", "com.lenovo", "zte.com", "com.yulong", "com.tcl",
        // CN services / apps
        "com.meituan", "com.sankuai", "com.dianping", "me.ele",
        "com.xunmeng", // com.xunmeng.pinduoduo = 拼多多
        "com.jingdong",
        "com.didichuxing", "com.didiglobal", "com.sdu.didi", // 滴滴 com.sdu.didi.psnger
        "com.xunlei", "com.immomo", "com.zhihu", "com.ximalaya",
        "com.qiyi", "com.iqiyi", "com.youku", "com.hupu", "com.gotokeep",
        "com.smzdm", "com.lalamove", "com.coolapk", "com.wandoujia",
        "com.xingin", // 小红书 com.xingin.xhs
        "com.douban", "com.cainiao", "com.MobileTicket", // 豆瓣 菜鸟 12306
        "com.jingyao", "com.shizhuang", "com.achievo", // 哈啰 得物 唯品会
        "com.miHoYo", "air.tv.douyu", "com.hunantv",
        "com.kugou", "cn.kuwo", "com.iflytek", "com.youdao", "com.sogou",
        "com.intsig", "com.quark.", "com.hpbr", "com.qidian", "com.duowan",
        "com.kmxs", // 七猫 com.kmxs.reader
        "com.dragon.read", "com.xs.fm", // 番茄小说/畅听 — bare com.dragon/com.xs too broad
        "com.anjuke", "com.ganji", "com.tongcheng",
        "com.mx", // Maxthon — com.mxtech.videoplayer is skip-listed
        "com.sf.activity", // 顺丰
        "com.chehejia", "com.xiaopeng", "cn.com.weilaihui3", // 理想 小鹏 蔚来
        "cn.soulapp", "com.p1.mobile", "cn.damai", // Soul 探探 大麦
        "com.eastmoney", "com.hexin", "com.xueqiu", // 东财 同花顺 雪球
        "com.lianjia", "com.huaxiaozhu", "com.didapinche", // 贝壳 花小猪 嘀嗒
        "com.taptap", "com.suning", "com.cubic.autohome",
        "com.luna.music", "com.sup.android", // 汽水音乐 皮皮虾 — anchored, tails are foreign-heavy
        "com.zte", // ZTE ships com.zte.* alongside zte.com.*
        // Banks and carriers
        "cmb.pb", "com.cmbchina", "com.icbc", "com.chinamworld", "com.bankcomm",
        "com.android.bankabc", "com.pingan", "com.citiccard", "com.yitong",
        "com.greenpoint", "com.sinovatech", "com.ct.client", "com.chinatelecom",
        "com.ctrip", "ctrip.android", "com.Qunar", "com.wuba",
        "cn.wps", "com.unionpay",
        // CN SDKs surfaced through manifest component names
        "com.umeng", "com.bugly", "com.qq.e",
        "cn.jpush", "cn.jiguang",   // 极光推送 + JCore
        "com.igexin", "com.getui",  // 个推
        "com.tendcloud",            // TalkingData
        "com.sensorsdata",          // 神策
        "com.growingio",
        "com.geetest",              // 极验
        "com.hyphenate",            // 环信
        "cn.rongcloud",             // 融云
        "com.analysys",             // 易观
        "com.reyun",                // 热云
        "com.shuzilm",              // 数盟
        "com.mob.",                 // Mob/ShareSDK — trailing dot avoids com.mobile.*
        "cn.sharesdk",              // ShareSDK's other namespace
        "io.dcloud",                // uni-app/H5+ packages ship as io.dcloud.*
        "com.kwad", "com.ksad",     // 快手联盟广告 SDK
        "io.rong",                  // 融云 io.rong.imlib/imkit
        "com.volcengine", "com.qiniu", "cn.leancloud",
        // io.agora deliberately absent: Agora is embedded in apps worldwide
        // and would false-positive foreign apps.
        // CN app packers / hardening stubs (seen as <application> or component
        // class names: StubApp, AW, ShellApplication, …)
        "com.secneo", "s.h.e.l.l", "com.stub", "com.kiwisec", "com.secshell",
        "com.wrapper", "cn.securitystack", "com.mogosec", "com.secoen",
        "com.bangcle", "com.ijiami", "com.nqshield", "com.payegis",
    )

    private val cnRegex by lazy {
        ("(" + CN_PREFIXES.joinToString("|").replace(".", "\\.") + ").*").toRegex()
    }

    /** True when [packageName] is an explicit foreign exception. */
    fun isSkipped(packageName: String): Boolean =
        SKIP_PREFIXES.any { packageName == it || packageName.startsWith("$it.") }

    /** True when [name] (package or class name) has a CN prefix. */
    fun isChineseName(name: String): Boolean = name.matches(cnRegex)

    /**
     * Component/class-name verdict: CN prefix and not a skipped foreign
     * namespace. The skip matters for names like `com.vivox.sdk.*`, which
     * trip the `com.vivo` prefix without being Chinese.
     */
    fun isChineseComponent(className: String): Boolean =
        !isSkipped(className) && isChineseName(className)
}
