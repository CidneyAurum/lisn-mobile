# 聆 LISN Mobile

桌面端「聆 LISN」(Electron)的安卓原生移植版 —— GitHub 免费音源聚合播放器。

- 包名:`com.glass.lisn` · minSdk 26(Android 8.0)· targetSdk 35
- 技术栈:Kotlin 2.4 + Jetpack Compose + Media3/ExoPlayer + QuickJS(quickjs-kt)+ OkHttp + jsoup
- 与桌面端完全解耦:独立工程、独立数据,桌面端 `D:\Switch\glass-music` 不被读取或修改(逻辑为按源码重写)

## 功能对照(桌面端 → 安卓端)

| 功能 | 状态 |
| --- | --- |
| 聚合搜索(GD音乐台 netease/kuwo + 4 个 MusicFree 插件 kw/kg/tx/wy) | ✅ 多源并发,name|artist 归一合并,翻页累计 |
| **lx-music 自定义源**(v0.2.0 新增) | ✅ QuickJS 协议宿主:EVENT_NAMES/on/send/request/utils.crypto(AES/MD5/RSA)/zlib/Buffer 垫片;huibq/ikun/全豆要/六音,音源中心在线升级 |
| **播放模式**(v0.2.0 新增) | ✅ 列表循环/单曲循环(ExoPlayer 无缝)/随机;通知栏·全屏页·设置页同步 |
| **播放队列**(v0.2.0 新增) | ✅ 全屏播放页队列视图,点击即播,当前曲高亮 |
| **解析韧性**(v0.2.0 新增) | ✅ 失败源 60s TTL 黑名单 + 失败自动顺序跳下一首(整队失败才停) |
| **搜索历史**(v0.2.0 新增) | ✅ 本地持久化 30 条,发现页快速复搜/清空 |
| **睡眠定时器**(v0.2.0 新增) | ✅ 15/30/60 分钟自动暂停 |
| 多源解析(质量链 flac→320k→128k 降级 + 音源顺序竞速 / 手动锁定) | ✅ |
| MusicFree 插件宿主(CommonJS + axios/he/crypto-js/cheerio/qs/big-integer/dayjs 垫片) | ✅ QuickJS 沙箱,脚本 keep-alive 仓库自动下载/缓存/升级 |
| HTTP 模板音源(如 Huibq 后端) | ✅ 默认模板 + 应用内添加 |
| 后台播放 + 通知栏控制 | ✅ Media3 MediaSessionService,自定义上一曲/下一曲按钮 |
| 播放队列自动连播 | ✅ 服务侧队列,播完自动切下一曲 |
| 歌词(LRC 同步滚动) | ✅ 兼容 `[mm:ss.xx]` 与酷我 `[ss.xx]` 时间戳,点行跳转 |
| 封面(搜索惰性补图 + 播放页) | ✅ |
| 自建歌单(新建/删除/加歌/移歌/搜索结果一键保存) | ✅ JSON 持久化,与桌面端同构 |
| 下载(MediaStore 写入 Music/LISN,含标题/歌手/专辑元数据) | ✅ 进度/取消/重试/清空 |
| 本地音乐库扫描 | ✅ |
| 音源中心(健康状态/延迟/测试/开关/升级/解析模式) | ✅ |
| 设置(默认音质/解析模式/自动检查更新) | ✅ |
| 每日推荐 / 热搜词 | ✅ 与桌面端同池同哈希 |

未移植(桌面端也属预留接口):lx 源脚本运行器(`lx-runner`)、ID3 标签写入。

## 构建与安装

```bash
# 本机已配置 D:\Android 为 SDK 根(local.properties 已指向)
./gradlew :app:assembleDebug     # 调试包
./gradlew :app:assembleRelease   # 签名正式包
# 产物:app/build/outputs/apk/{debug,release}/
```

Windows 下用 `gradlew.bat`。JDK 17(AGP 8.7.3 / Kotlin 2.4.10)。

**已签名的成品 APK:`LISN-v0.1.0.apk`(项目根目录,release 签名)。**

## 应用图标

图标由 `tools/gen_icon.py`(PIL)程序化生成,风格与应用一致:深空 aurora 渐变底 + 白色玻璃感双音符 + 青/粉声波弧线。产出:

- `mipmap-*/ic_launcher.png` / `ic_launcher_round.png`(mdpi~xxxhdpi legacy 图标,圆角/圆形遮罩)
- `drawable-nodpi/ic_launcher_background.png`(aurora 全出血底)、`ic_launcher_foreground.png`(音符,居中 66% 安全区)、`ic_launcher_mono.png`(Android 13+ 主题图标单色层)
- 修改图标后重跑:`python tools/gen_icon.py`

## 签名

- Keystore:`keystore/lisn-release.jks`(已 gitignore,**请自行备份,丢失将无法同签名更新**)
- 别名 `lisn`,密码 `lisn2026`(个人使用强度足够,正式发布请自行更换并移出仓库)

## 架构

```
app/src/main/java/com/glass/lisn/
├── engine/                    # 音源引擎(对应桌面端 electron/main/sources)
│   ├── Spi.kt                 # SourceProvider 接口 / 质量链 / ResolveError
│   ├── GdProvider.kt          # GD音乐台(music-api.gdstudio.xyz)
│   ├── HttpGenericProvider.kt # HTTP 模板音源
│   ├── MfShims.kt             # QuickJS 垫片:axios/he/crypto-js/cheerio/qs/big-integer/dayjs/setTimeout
│   ├── MusicFreeHost.kt       # 插件沙箱宿主(CommonJS 包装 + 顶层 await 取值)
│   ├── MusicFreeManager.kt    # 插件源管理(下载/缓存/升级/开关)
│   ├── SourceRegistry.kt      # 聚合搜索合并 + 解析竞速 + 封面/歌词
│   └── Http.kt                # OkHttp 封装 + crypto/bigint/jsoup 树
├── data/                      # SettingsStore / PlaylistStore(JSON)/ DownloadManager(MediaStore)
├── player/
│   ├── PlaybackService.kt     # MediaSessionService:服务侧队列、按需解析、自动连播、自定义通知按钮
│   └── PlayerClient.kt        # MediaController 封装(StateFlow + 会话广播)
└── ui/                        # Compose:发现/搜索/歌单/下载/本地/音源/设置 + 迷你播放条 + 全屏播放页(歌词)
```

### 实现要点(踩坑记录)

1. **quickjs-kt 1.0.x 的 `evaluate` 不会自动解包 Promise**:取异步结果必须用顶层 await —— `evaluate<T>("await plugin.search(...)")`(官方集成测试的标准用法)。
2. **Kotlin Map ↔ JS 对象的自动转换在 Promise 解析边界不可靠**:垫片边界一律传 **JSON 字符串**,JS 侧 `JSON.parse`、Kotlin 侧 `kotlinx.serialization`,零转换歧义。
3. **big-integer 垫片**:内部 `modPow` 结果是十六进制字符串,需与用户入口(十进制默认)分开包装,否则 `BigInteger("cc5..", 10)` 直接 NumberFormatException。
4. **酷我歌词时间戳是 `[ss.xx]` 秒制**,LRC 解析需兼容两种格式。
5. compileSdk 36(quickjs-kt 1.0.15 要求),`android.suppressUnsupportedCompileSdk=36`。

## 隐私与隔离

- 应用数据全部在自身沙箱(`filesDir` 的 `engine/`、`mf-sources/` 与 JSON 存储),不读写设备外任何应用数据。
- 网络仅访问音源相关公开接口(GD音乐台、各平台搜索接口、GitHub/jsdelivr 拉取插件脚本)。
- 测试用模拟器为独立创建的 `lisn-test` AVD,未改动既有 AVD。
