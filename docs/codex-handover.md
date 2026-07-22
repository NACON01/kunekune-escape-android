# クネクネ離脱 Android版 — Codex 引継書

最終更新: 2026-07-22 / 対象リポジトリ: `C:\Users\junno\Projects\kunekune-escape-android`（GitHub: `NACON01/kunekune-escape-android`）

このドキュメントは、これまでの開発経緯・現在地・技術的制約・今後の実装をCodexに引き継ぐための総合資料です。**新しく着手する前に必ず全体を読んでください。** 各フェーズの詳細な解説は `docs/phase1a-*.md` 〜 `docs/phase1d-*.md` にもあります。

---

## 1. プロジェクト概要とコンセプト

**クネクネ離脱システム** = スマホで動画を見ながら先延ばししている人を、目的地(風呂・デスク等)へ物理的に誘導するアプリ。

コンセプトの核心: 報酬(動画)を切らさずに、**停滞すると画面が劣化し、正しい方向へ歩くと回復する**という報酬ループで、「視聴の延長線上の姿勢調整」として移動させる。ブロッカー(動画を止める)のような離散的・意志力依存の介入ではなく、連続的でオンセットの無い介入にするのが狙い。無限フィード(YouTube Shorts等)に物理的な「終わり(到着)」を与える装置、という位置づけ。

要求精度: 目的地との誤差 **数十cm**。これがGPS/BLE/WiFi/コンパス+歩数(PDR)を全て却下し、VIO(カメラ+IMUによる自己位置推定)に至った理由。

---

## 2. アーキテクチャの決定(なぜ「道2」なのか)

### 精度の検討結果
- GPS(屋内で数十m・測位不能)、BLE/WiFi(数m)、コンパス+歩数PDR(±15〜30°+距離誤差累積)は全て要求精度に届かない。
- AirTag級の精度はUWB専用ハードが必要だが、ユーザー端末(Pixel 3a / 借用Pixel 5)はUWB非搭載、かつWebにUWB APIは無い。
- **数十cm精度でWebに無く実現可能なのはVIO(ARCore)のみ**。ARCoreはAndroidネイティブで使える。

### Web版からネイティブへの転換
- 旧Web版(`C:\Users\junno\Projects\kunekune-escape`、Vite+TS)はPDRベースで精度不足。コンセプト実証・演出ロジックの参照元として残置。
- ネイティブ化により、UWB(端末非対応で不採用)、ARCore Cloud Anchors、実アプリへのオーバーレイ介入、UsageStatsManagerによる自動発動が解禁される。

### 「道1」vs「道2」の分岐(重要)
本物のログイン済みYouTubeでパーソナライズされたShortsを対象にしたい、という要求から:

- **道1(却下)**: WebViewにYouTube埋め込み + 位置ドリフト演出。→ GoogleがWebView内アカウントログインをブロックするためパーソナライズ不可。位置ドリフト(動画のピクセルを動かす)は自前所有のWebViewでしか不可能。
- **道2(採用)**: **本物のYouTubeアプリの上にシステムオーバーレイでフェード膜+矢印を描く**。パーソナライズされた実コンテンツが使える。ただし**位置ドリフトは不可能**(他アプリのピクセルは動かせない)、演出は**フェード(暗転)+矢印のみ**。ユーザー判断「どうせ最終形がフェード+矢印なら道1は無駄」。

**道2の代償**: 実アプリの上に被せるため、Androidのタッチセキュリティ(後述§8)と正面衝突する。これが現在の未解決課題。

---

## 3. 踏んできたフェーズ全記録

開発方針: **1フェーズ=増える概念1つ**、毎フェーズ実機(Pixel 3a、実機ID `98SAY16MF5`)で検証、デバッグHUDで内部状態を可視化。実装はCodexに委任し、Claude側がビルド/コミット/プッシュ/実機インストールを担当。

### Phase 0: 環境構築 ✅
- JDK 17(Temurin)+ Android SDK コマンドラインツール(Android Studio無し)をセットアップ。
- Kotlin単一モジュール、`applicationId = com.nacon01.kunekune`、minSdk 29 / compile・targetSdk 34、AGP 8.2 / Gradle 8.2。
- 「Kunekune Escape Phase 0」を表示するだけの雛形。APK生成・実機起動を確認。

### Phase 1a: VIOトラッキング + デバッグHUD ✅
- **概念**: 「スマホが自己位置をcm単位で知っている」(ARCore VIO)。
- `ArTrackingManager`(セッション管理・毎フレームのポーズ取得)、`CameraBackgroundRenderer`(カメラGL背景)、`DebugHud`(状態/座標/距離/FPS表示)。
- **検証**: 3m歩行で誤差**約10cm**。無地面ではPAUSED(特徴点不足)になることも確認。
- 詳細: `docs/phase1a-vio-tracking.md`

### Phase 1b: マーカー位置合わせ(Augmented Images)✅
- **概念**: 「紙マーカーが座標系の原点になる」。VIO座標はセッション開始位置が原点でリセットされるため、物理的に動かないマーカーで座標系を毎回同じ場所に固定する。
- `MarkerAnchor`(AugmentedImageDatabase登録、検出、Anchor作成、マーカー座標系変換)。マーカー画像は `tools/GenerateMarker.java` で生成、印刷用は `docs/marker/marker-print.html`(A4・一辺15cm)。
- **仕組み**: マーカーの模様配置と物理サイズ(15cm)が既知なので、斜め/遠距離からの見え方の歪みからカメラとマーカーの相対姿勢を逆算(PnP)。一度認識するとAnchorをVIOが維持し、視界から外れても座標系は保たれる。
- **検証**: タスクキル→再認識で同一座標系を復元。斜めからでも正しい座標。
- **ハマり**: 初版マーカーが品質70点(`arcoreimg`基準75点)でセッション作成失敗。高密度パターンに再生成し100点に。**教訓: マーカー画像を変えたら必ず `tools/arcoreimg/arcoreimg.exe eval-img` で85点以上を確認**。
- 詳細: `docs/phase1b-marker-alignment.md`

### Phase 1c: 経路記録 ✅
- **概念**: 「マーカー座標系で歩いた軌跡をポリラインとして保存」。
- `RouteRecorder`(前回点から**0.3m以上移動で1点追加**の距離ベースサンプリング)、`RouteStore`(`route.json`保存/読込、`getExternalFilesDir`配下)。
- **検証**: 「マーカー正対→90度右→立ち上がり→2m歩行」を記録し9点2.46m。取り出したJSONの可視化で実動作と一致。
- 詳細: `docs/phase1c-route-recording.md`

### Phase 1d: 矢印誘導(pure pursuit)✅
- **概念**: 「現在位置と保存経路の差分から進むべき方向を出す」。
- `GuidanceEngine`(ARCore非依存の純粋ロジック): 現在位置を経路に射影→**1m先(lookahead)**を目標点に→方向角・残距離・進捗・**到着(終点0.6m以内)**を計算。`projectedDistanceMeters`(スタートからの弧長)を公開。`GuidanceArrowView`(Canvas矢印、EMA平滑化)。
- **座標系の設計**: 幾何計算はマーカー座標系ではなく**VIOワールド座標系(重力基準+Yが上)**で実施。route.jsonの点を毎フレーム、アンカーの現在ポーズでワールド座標へ変換。マーカーが壁でも床でも水平面計算が正しくなるため。
- **検証**: 矢印が経路方向を指す/体の向きに追従/経路から外れると復帰方向を指す/到着判定、全て実機OK。
- **既知の制約**: 自己交差・近接往復する経路は射影が先の区間へ飛ぶ可能性。一方向経路(ソファ→風呂)では問題なし。
- 詳細: `docs/phase1d-arrow-guidance.md`

### Phase 2a: バックグラウンド追跡の実現性検証 ✅(両関門クリア)
- **概念**: 「自アプリが裏に回り、YouTubeが前面でも、ARCoreがトラッキングを継続できるか」。道2の成否を分ける最重要スパイク。
- `BackgroundTrackingService`(カメラ型フォアグラウンドサービス): 専用スレッドで**オフスクリーンEGL(pbuffer + 外部OESテクスチャ)**を作り `setCameraTextureName` に渡してARCoreをヘッドレス起動、update()ループを回す。`TrackingOverlay`(最前面テキスト)にポーズ表示。
- **検証(🟢🟢)**: ①前面がChrome/YouTubeでも裏でVIOポーズが更新継続。②動画視聴の自然な持ち方で歩いてもトラッキング維持。→ 道2は技術的に実現可能と確定。
- **ハマり**: サービスのフィールド初期化で `Handler(mainLooper)` がNPE(Context未接続)→ `Looper.getMainLooper()` で修正。

### Phase 2b: 実アプリ上への矢印オーバーレイ誘導 ✅
- **概念**: 誘導パイプラインをサービスに載せ、YouTube等の上に矢印を描く。
- サービスに誘導モード追加(`ACTION_START_GUIDANCE`): マーカーDB設定、route.json読込、毎フレームGuidanceEngine計算。`GuidanceOverlay`(画面上部中央・半透明黒・矢印長=画面幅の約1/5・`FLAG_NOT_TOUCHABLE`)。MainActivityに「離脱開始」ボタン(Phase1セッションを閉じてからサービス起動→`moveTaskToBack`)。
- **検証**: マーカーロック後、YouTube上で矢印が経路方向を指し続ける。
- **修正**: 当初マーカーが視界から外れると矢印が消えた(state==TRACKINGを要求していた)→ **一度アンカー確立後(markerPose≠null)は継続誘導**に修正。矢印も半透明化(不透明度165/255)。

### Phase 2c: フェード膜(スクリム)✅実装/⚠️課題あり
- **概念**: 停滞で画面が暗くなり、進むと透明に戻る、コンセプトの核心の報酬ループ。
- `FadeController`(純粋ロジック・テスト付き): 濃さ D∈[0,1]。進行中(経路弧長が0.03m超増加)/非誘導は D=0、停滞中は毎秒 1/30 加算で**30秒で真っ暗**。`ScrimOverlay`(全画面黒・alpha=D・`FLAG_NOT_TOUCHABLE`)。矢印は膜より前面。
- **ユニットテスト・ビルドは成功**、実機インストール済み。
- **⚠️ 実機で重大な課題が発覚(下記§4・§5)**。

---

## 4. 現在地

**Phase 2cを実装・デプロイしたが、実機テストでスクリムのタッチ遮断問題が発覚し、その修正方針を決めている段階。** コミットは `6e5ab98`(Phase 2c)まで、作業ツリーはクリーン。次にやるのは§5の修正。

---

## 5. 今すぐ着手すべき課題: スクリムのタッチ遮断

### 症状
離脱開始してスクリムが出ると、**ほぼ透明(暗くなる前)でも画面全体のタップが効かなくなる**。ホーム画面のアプリアイコンも開けず、フィルター外のナビゲーションバーだけ操作可能。**通知の「表示切替」でオーバーレイをオフ(GONE)にするとタップが復活する。**

### 確定した原因
**表示状態(VISIBLE)のオーバーレイは、alpha 0(透明)でも下のアプリへのタッチをAndroidが遮断する**(タップジャッキング対策のセキュリティ)。判定は**不透明度ではなく「表示されているか」**。`ScrimOverlay` は `alpha=0` だが `visibility=VISIBLE` で全画面に追加されていたため、透明でも全画面を覆う扱いになり遮断していた。「表示切替」で `GONE` にすると覆いが消えて復活する、が動かぬ証拠。
- 補足: 不透明度80%超では別途 Android 12「untrusted touch」も遮断する(`maximum_obscuring_opacity_for_touch` 既定0.8)。ただし今回の主因は**表示されている限り透明でも遮断される**方(opacity非依存)。
- `FLAG_NOT_TOUCHABLE` は正しく付いているが、この遮断はそれとは別のOS層。

### 制約(避けられない)
**「動画を薄暗くしつつ、その下のYouTubeも普通に触れる」はAndroidの仕様上できない。** 膜が見えている限り、下のタッチは死ぬ。

### 決定した修正方針
**膜を「暗くしている時だけ表示、それ以外は完全に消す(GONE/またはWindowManagerからremove)」にする。** タッチ遮断を"バグ"ではなく**メカニズムの一部**(止まると使えない→動くと戻る)に転化する:
- 見ている/進んでいる/待機中/到着 → 膜は非表示 → YouTube完全操作可能(不具合解消)
- 停滞して暗くなる時 → 膜表示・暗転 → タッチ効かない=「見るな、歩け」
- 歩き出す → 膜が即消える → 操作可能

実装ポイント:
1. `ScrimOverlay`: density が閾値以下なら `visibility=GONE`(またはWindowManagerからdetach)、閾値超で `VISIBLE`+alpha=density。停滞開始直後にいきなりロックされないよう、表示開始に小さな猶予/閾値(例: density>0.1、約3秒)を設ける。
2. `visibility` 切替はメインスレッドで(既存の `mainHandler` / view の `post` を利用)。
3. ユーザーの「表示切替」手動トグル(`userHidden`)と競合しないように。

### 未決定の設計判断(実装前にユーザーに確認)
暗転中はYouTubeを触れない(歩いて解除)を許容するか、それとも**暗さを80%以下に抑えてYouTube操作を優先**するか(80%以下ならYouTube側は遮断しない可能性があるが真っ暗にはならない)。ユーザー未回答。**この点はユーザー確認後に実装すること。**

---

## 6. 残りのロードマップ

- **Phase 2c仕上げ**: 上記§5のスクリム修正。効き方(30秒・暗転カーブ・猶予)は実機体感後に調整。
- **Phase 2d**: 
  - **自動発動**: `UsageStatsManager`(`PACKAGE_USAGE_STATS`権限)でYouTube連続視聴を検知し自動で離脱開始。これにより「アプリ→ホーム→YouTube起動」の導線(ランチャーのタッチ遮断に当たる)を回避でき、実アプリ上で自然に発動できる。
  - **到着挙動の3モード切替**(ユーザーが最も気にしている点、A/B検証用): ①フェードで終了 ②解除して全開 ③置いて確認するまで保留(NFC等)。実装で固定せず切替パラメータにする。
  - **セッションログ**: 発動時刻・軌跡・到着時刻・到着後の再視聴までの時間 等をJSON保存し、仮説検証(ブロッカー方式との比較)に使う。
- **将来**: マーカーレス化(出発点が毎回同じ利用特性を使った軌跡マッチング / Cloud Anchors)、複数目的地。

---

## 7. コード構成(ファイルマップ)

`app/src/main/java/com/nacon01/kunekune/`
- `MainActivity.kt` — Phase1前面AR画面の統括。ボタン: 記録開始/終了、誘導開始/終了(前面AR用)、2a裏で追跡テスト、離脱開始。権限フローもここ。
- `ArTrackingManager.kt` — **前面**ARCoreセッション(Phase1)。`TrackingSnapshot`発行。
- `CameraBackgroundRenderer.kt` — カメラGL背景(Phase1前面)。
- `DebugHud.kt` — 前面のデバッグHUD。
- `MarkerAnchor.kt` — Augmented Images。マーカーDB登録・検出・Anchor・マーカー↔ワールド変換。`markerPoseInWorld` を公開。**サービスからも利用**。
- `RouteRecorder.kt` — 0.3mサンプリング記録(ARCore非依存・テスト有)。
- `RouteStore.kt` — route.json 保存/読込。
- `GuidanceEngine.kt` — pure pursuit 純粋ロジック(ARCore非依存・テスト有)。`projectedDistanceMeters`公開。
- `GuidanceArrowView.kt` — Canvas矢印。`compact=true`でオーバーレイ用縮小版。EMA平滑化・半透明。
- `BackgroundTrackingService.kt` — **道2の中核**。カメラ型フォアグラウンドサービスでヘッドレスARCore。2a生トラッキングモード + 2b/2c誘導モード。オフスクリーンEGL(内部`HeadlessEgl`)。`ACTION_START_GUIDANCE` / `ACTION_STOP` / `ACTION_TOGGLE_OVERLAY`。
- `TrackingOverlay.kt` — 2a用デバッグ最前面テキスト(コンパクト箱)。
- `GuidanceOverlay.kt` — 2b用矢印オーバーレイ(上部中央・半透明黒・`FLAG_NOT_TOUCHABLE`)。
- `ScrimOverlay.kt` — 2c用フェード膜(全画面黒・alpha=density・`FLAG_NOT_TOUCHABLE`)。**§5の修正対象**。
- `FadeController.kt` — フェード濃さロジック(30秒で真っ暗・ARCore非依存・テスト有)。

`app/src/test/java/.../` — `RouteRecorderTest`, `GuidanceEngineTest`, `FadeControllerTest`。
`tools/GenerateMarker.java` — マーカー画像生成(乱数シード固定)。`tools/arcoreimg/`(gitignore) — 品質検証ツール。
`docs/` — 各フェーズ解説、`marker/marker-print.html`。

---

## 8. 技術的に確定した重要事実・ハマりどころ

1. **ヘッドレスARCore**: カメラ型フォアグラウンドサービス + オフスクリーンEGL(pbuffer+外部OESテクスチャを`setCameraTextureName`)で、他アプリ前面でもVIO継続可能(2aで実証)。`requestInstall`はサービスから呼べないので、ARCoreインストール済み前提で`Session(context)`直接生成。
2. **Androidタッチセキュリティ(最重要・現行課題)**: 別アプリを覆う**表示状態のオーバーレイは透明でも下のタッチを遮断**する。`GONE`/detachで解消。opacity>0.8では別途untrusted touchでも遮断。→ 覆いは"暗くする時だけ"出す設計にする(§5)。
3. **二重ARCoreセッション衝突**: 前面Phase1セッションとサービスセッションが同時だとカメラ衝突(真っ白)。離脱開始時にPhase1セッションを`close()`してからサービス起動する順序が必須。
4. **マーカーアンカーの維持**: 一度認識すれば視界外でもVIOがAnchor維持。誘導継続の判定は `markerPoseInWorld != null`(state==TRACKING を要求してはいけない)。
5. **マーカー品質**: 変更時は `tools/arcoreimg/arcoreimg.exe eval-img --input_image_path=app/src/main/assets/marker.png` で85点以上を確認。
6. **サービス初期化NPE**: フィールドで`context.mainLooper`を呼ぶとContext未接続でNPE。`Looper.getMainLooper()`を使う。
7. **color反転など端末設定**: アプリはシステム色を反転できない。`accessibility_display_inversion_enabled`等の端末設定が原因のことがある。

---

## 9. 制約・設計判断のまとめ

- 道2 = 実YouTubeアプリの上にオーバーレイ。演出は**フェード+矢印のみ**(位置ドリフト不可)。
- パーソナライズされたShortsは実アプリでしか得られない(WebViewはログイン不可)。
- フェードと操作性はトレードオフ(§5)。真っ暗にするなら暗転中はタッチ不可を受け入れる。
- 到着挙動(消える/解除/保留)は2dで切替式にしてA/B検証。
- 対象端末: Pixel 3a(Android 12/API31)主。借用でPixel 5。屋外は安全上不可(このメカニズムの生息地は屋内のみ)。

---

## 10. ビルド・デプロイ環境 / Codex委任の手順

### 環境
- `JAVA_HOME` = Temurin JDK 17（`C:\Program Files\Eclipse Adoptium\jdk-17*`）。`ANDROID_HOME` = `C:\Users\junno\AppData\Local\Android\Sdk`。Android Studio無し。
- ビルド: `./gradlew assembleDebug`（要 `JAVA_HOME`）。
- **`local.properties` の注意**: **BOM無し・スラッシュ区切り**で `sdk.dir=C:/Users/junno/AppData/Local/Android/Sdk`。BOM付きやバックスラッシュだとGradleがSDKを見つけられずビルド失敗。

### Codexの実行
- 委任コマンド例（[[codex-model-preference]]に従う）:
  `codex exec -m gpt-5.6-luna -c model_reasoning_effort=high --sandbox workspace-write --skip-git-repo-check -C "C:/Users/junno/Projects/kunekune-escape-android" "<指示>"`（npmグローバル版 `C:/Users/junno/AppData/Roaming/npm/codex`）。
- **Codexサンドボックスの制約**: `.git`書き込み・GitHub認証ができないため **commit/pushはCodex側で不可**。Codexにはビルド検証まで(`GRADLE_USER_HOME=.gradle-user-home` / `ANDROID_USER_HOME=.android-user-home` を使用)させ、**commit/push/実機インストールはClaude(またはユーザー)側で実施**する。
- Codexが作る一時ファイル(`.gradle-tmp*`, `_patch_probe*.txt`等)はコミット前に掃除。

### 実機インストール
- `adb install -r app/build/outputs/apk/debug/app-debug.apk`。
- **署名不一致**(`INSTALL_FAILED_UPDATE_INCOMPATIBLE`)が出たら、Codexが一時鍵で署名した可能性。通常の`~/.android/debug.keystore`で再ビルドし、必要なら`adb uninstall com.nacon01.kunekune`してから入れ直す。
- route.json取得: `MSYS_NO_PATHCONV=1 adb pull /sdcard/Android/data/com.nacon01.kunekune/files/route.json`。
- オーバーレイ/カメラ/通知権限、`appops set ... SYSTEM_ALERT_WINDOW allow` 等はadbで付与可能。

---

## 11. 開発の進め方(維持すること)

- **1フェーズ=概念1つ**、毎フェーズ実機検証。既存フェーズ(Phase1前面AR、2a生トラッキング)を壊さない。
- 各フェーズ完了時に `docs/phaseX-*.md` に日本語解説(仕組み・なぜこの方式・どこが狂うとどう壊れるか)を書く。
- デバッグHUD/オーバーレイで内部状態を可視化し、ユーザーが「どの値がおかしいか」で不具合を切り分けられる状態を保つ。
- コードコメントは要点のみ日本語。
