package io.github.madeye.meow.repo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChinaPackageMatcherTest {

    @Test
    fun `major chinese apps match by package prefix`() {
        listOf(
            "com.tencent.mm",               // 微信
            "com.tencent.mobileqq",
            "com.eg.android.AlipayGphone",  // 支付宝
            "com.ss.android.ugc.aweme",     // 抖音 — neutral-looking but ss.android is ByteDance
            "com.smile.gifmaker",           // 快手
            "tv.danmaku.bili",              // 哔哩哔哩
            "com.xunmeng.pinduoduo",
            "com.jingdong.app.mall",
            "com.sankuai.meituan",
            "com.dianping.v1",              // 大众点评
            "com.zhihu.android",
            "com.sina.weibo",
            "com.netease.cloudmusic",
            "com.qiyi.video",               // 爱奇艺
            "com.youku.phone",
            "com.coolapk.market",
            "com.ximalaya.ting.android",
            "com.xingin.xhs",               // 小红书
            "com.douban.frodo",
            "com.cainiao.wuliu",
            "com.MobileTicket",             // 铁路12306
            "com.jingyao.easybike",         // 哈啰
            "com.shizhuang.duapp",          // 得物
            "com.achievo.vipshop",          // 唯品会
            "com.miHoYo.Yuanshen",          // 原神（国服）
            "air.tv.douyu.android",         // 斗鱼
            "com.hunantv.imgo.activity",    // 芒果TV
            "com.kugou.android",            // 酷狗
            "cn.kuwo.player",               // 酷我
            "com.hpbr.bosszhipin",          // BOSS直聘
            "com.kmxs.reader",              // 七猫
            "com.sf.activity",              // 顺丰
            "com.chehejia.oc.m01",          // 理想汽车（车和家）
            "com.xiaopeng.globalcarinfo",   // 小鹏
            "cn.com.weilaihui3",            // 蔚来
            "cn.soulapp.android",           // Soul
            "com.p1.mobile.putong",         // 探探
            "cn.damai",                     // 大麦
            "com.eastmoney.android.berlin", // 东方财富
            "com.hexin.plat.android",       // 同花顺
            "com.xueqiu.android",           // 雪球
            "com.lianjia.beike",            // 贝壳
            "com.huaxiaozhu.rider",         // 花小猪
            "com.didapinche.booking",       // 嘀嗒
            "com.taptap",                   // TapTap
            "com.cmbchina",                 // 掌上生活
            "com.citiccard.mobilebank",     // 中信动卡空间
            "com.suning.mobile.ebuy",       // 苏宁易购
            "com.cubic.autohome",           // 汽车之家
            "com.luna.music",               // 汽水音乐
            "com.sup.android.superb",       // 皮皮虾
            "com.xs.fm",                    // 番茄畅听
            "com.yitong.mbank.psbc",        // 邮储银行
            "com.chinatelecom.bestpayclient", // 翼支付
            "io.dcloud.H55BDF6BE",          // uni-app 打包的国产应用
            "com.Qunar",
            "ctrip.android.view",
            "me.ele",
            "cmb.pb",                       // 招商银行
            "com.icbc",                     // 工商银行
            "com.android.bankabc",          // 农业银行
        ).forEach { pkg ->
            assertFalse("$pkg must not be skipped", ChinaPackageMatcher.isSkipped(pkg))
            assertTrue("$pkg should be Chinese", ChinaPackageMatcher.isChineseName(pkg))
        }
    }

    @Test
    fun `chinese oem and sdk component names match`() {
        listOf(
            "com.tencent.bugly.Bugly",
            "com.umeng.commonsdk.UMConfigureImpl",
            "com.xiaomi.push.service.XMPushService",
            "com.huawei.hms.support.api.push.service.HmsMsgService",
            "cn.jpush.android.service.PushService",
            "com.igexin.sdk.PushService",
            "com.qq.e.comm.DownloadService",
            "com.secneo.apkwrapper.AW",
            "com.bytedance.sdk.openadsdk.TTProvider",
            "com.mob.tools.MobLog",
            "com.kwad.sdk.api.KsAdSDK",
            "io.rong.imlib.RongIMClient",
            "cn.sharesdk.framework.ShareSDK",
        ).forEach { name ->
            assertTrue("$name should be Chinese", ChinaPackageMatcher.isChineseName(name))
        }
    }

    @Test
    fun `skip list takes precedence for foreign exceptions`() {
        listOf(
            "com.google.android.gms",
            "com.microsoft.office.word",
            "com.android.chrome",
            "com.zhiliaoapp.musically",       // TikTok
            "com.ss.android.ugc.trill",       // TikTok's other package
            "com.tencent.ig",                 // PUBG Mobile global
            "com.tencent.iglite",
            "com.mxtech.videoplayer",         // would trip com.mx
            "com.stubhub",                    // would trip com.stub
            "com.vivox.sdk",                  // would trip com.vivo
            "com.alightcreative.motion",      // Alight Motion — would trip com.ali
            "com.iqiyi.i18n",                 // 爱奇艺国际版
            "com.bilibili.app.in",            // bilibili 国际版
            "com.didiglobal.passenger",       // DiDi 国际版
            "com.miHoYo.GenshinImpact",       // 原神国际服
        ).forEach { pkg -> assertTrue("$pkg should be skipped", ChinaPackageMatcher.isSkipped(pkg)) }
    }

    @Test
    fun `skipped packages trip the cn prefix so the skip is load-bearing`() {
        // If any of these stop matching the CN regex, the corresponding skip
        // entry is dead weight — this test keeps the two lists honest.
        // (musically/pikcloud trip nothing by name; their skip works because
        // it short-circuits before the component scan.)
        listOf(
            "com.ss.android.ugc.trill",
            "com.tencent.ig",
            "com.tencent.iglite",
            "com.mxtech.videoplayer",
            "com.stubhub",
            "com.vivox.sdk",
            "com.alightcreative.motion",
            "com.iqiyi.i18n",
            "com.bilibili.app.in",
            "com.didiglobal.passenger",
            "com.miHoYo.GenshinImpact",
        ).forEach { pkg ->
            assertTrue("$pkg must trip a CN prefix for its skip to matter",
                ChinaPackageMatcher.isChineseName(pkg))
        }
    }

    @Test
    fun `component verdicts apply the skip list`() {
        assertTrue(ChinaPackageMatcher.isChineseComponent("com.tencent.bugly.Bugly"))
        assertTrue(ChinaPackageMatcher.isChineseComponent("com.stub.StubApp"))
        // A foreign app embedding Vivox components must not classify domestic.
        assertFalse(ChinaPackageMatcher.isChineseComponent("com.vivox.sdk.VoiceService"))
    }

    @Test
    fun `foreign and neutral names do not match`() {
        listOf(
            "com.spotify.music",
            "com.whatsapp",
            "org.telegram.messenger",
            "com.github.shadowsocks",
            "io.github.akari.meow",
            "com.example.myapp",
            "com.mobile.legends",   // trailing-dot guard on "com.mob."
            "com.mobtools.foo",     // also not com.mob.* — anchored on the dot
            "com.quarkdomino.game", // foreign tail — "com.quark." is anchored
            "com.qunar",            // case-sensitive: real package is com.Qunar
        ).forEach { pkg ->
            assertFalse("$pkg must not be skipped", ChinaPackageMatcher.isSkipped(pkg))
            assertFalse("$pkg should not be Chinese", ChinaPackageMatcher.isChineseName(pkg))
        }
    }

    @Test
    fun `prefix matching is deliberately unanchored`() {
        // The lists catch vendor names that are longer than the prefix.
        assertTrue(ChinaPackageMatcher.isChineseName("com.aliyun.aliyunvideo"))
        assertTrue(ChinaPackageMatcher.isChineseName("com.qihoo360.mobilesafe"))
        assertTrue(ChinaPackageMatcher.isChineseName("com.bytedance.sdk.openadsdk"))
    }

    @Test
    fun `empty names never match`() {
        assertFalse(ChinaPackageMatcher.isChineseName(""))
        assertFalse(ChinaPackageMatcher.isSkipped(""))
    }
}
