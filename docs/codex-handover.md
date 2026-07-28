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
- ネイティブ化により、ARCore、実アプリへのオーバーレイ介入、UsageStatsManagerによる利用検知が可能になる。ただし targetSdk 34 のカメラFGSは while-in-use 制約があるため、検知だけでバックグラウンドからカメラを自動起動する設計にはできない。

### 「道1」vs「道2」の分岐(重要)
本物のログイン済みYouTubeでパーソナライズされたShortsを対象にしたい、という要求から:

- **道1(却下)**: WebViewにYouTube埋め込み + 位置ドリフト演出。→ GoogleがWebView内アカウントログインをブロックするためパーソナライズ不可。位置ドリフト(動画のピクセルを動かす)は自前所有のWebViewでしか不可能。
- **道2(採用)**: **本物のYouTubeアプリの上にシステムオーバーレイでフェード膜+矢印を描く**。パーソナライズされた実コンテンツが使える。ただし**位置ドリフトは不可能**(他アプリのピクセルは動かせない)、演出は**フェード(暗転)+矢印のみ**。ユーザー判断「どうせ最終形がフェード+矢印なら道1は無駄」。

**道2の制約**: 実アプリの上に被せるためAndroidのタッチセキュリティに従う必要がある。単一windowと安全なwindow alphaで通常操作を維持する(後述§5・§8)。

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

### Phase 2b/2c+: 実アプリ上への安全な誘導・暗転
- **概念**: 誘導パイプラインをサービスに載せ、YouTube等の上に矢印を描く。
- サービスに誘導モード追加(`ACTION_START_GUIDANCE`): マーカーDB設定、route.json読込、毎フレームGuidanceEngine計算。矢印と暗転膜は一枚の `GuidanceOverlay` に統合する。70%まではタッチ透過用window alpha 0.70、それ以降は100%暗転へ向けてwindow alphaも上げる。
- **検証**: マーカーロック後、YouTube上で矢印が経路方向を指し続ける。
- **追跡信頼度**: camera と Anchor の両方が TRACKING のフレームだけ方向を更新する。初回位置確立は8秒で打ち切るが、一度確立した後のPAUSEDは同じSession/Anchorで無期限に復帰を待ち、古い矢印を消す。STOPPEDだけを復帰不能として安全停止する。非nullの古い pose は誘導根拠にしない。

### Phase 2c: 進行報酬とフェード
- **概念**: 停滞で画面が暗くなり、進むと透明に戻る、コンセプトの核心の報酬ループ。
- `FadeController` はフレームごとの3cm判定やノイズデッドバンドではなく、平滑化した0.5秒の正味前進ウィンドウで進行を判定する。開始/停滞猶予、速度上限、暗転/回復レート上限を持ち、逆行・オフルートは報酬にせず、追跡喪失中は濃度を凍結する。
- 到着は水平 endpoint 距離と cross-track の両方を満たした状態を1秒継続してラッチし、2.5秒後にカメラを解放する。

---

## 4. 現在地

**Phase 2+を製品化方向へ再構築済み。** ホスト側ユニットテストとDebugビルドを通した段階であり、実機でのタッチ通過、マーカーロック遷移、追跡喪失、到着精度は未検証。詳細は `docs/phase2-architecture.md`。

---

## 5. オーバーレイとタッチ通過の正しい前提

### 症状
離脱開始してスクリムが出ると、**ほぼ透明(暗くなる前)でも画面全体のタップが効かなくなる**。ホーム画面のアプリアイコンも開けず、フィルター外のナビゲーションバーだけ操作可能。**通知の「表示切替」でオーバーレイをオフ(GONE)にするとタップが復活する。**

Android 12 の untrusted-touch 判定は `View.alpha` ではなく `WindowManager.LayoutParams.alpha` を使う。`TYPE_APPLICATION_OVERLAY` が `FLAG_NOT_TOUCHABLE` で、同一UIDの重なりを式 `1-(1-a)(1-b)` で合成した不透明度が既定上限0.8以下なら、下のアプリへタッチを通せる。旧実装は `View.alpha` を変えていた一方、矢印と膜の各 window alpha が1.0だった。

修正後は矢印と膜を一枚のwindowに統合した。暗転70%まではwindow alphaを0.70に保ち内部黒Viewで調整する。70%以降はwindow alphaも1.0まで上げて完全な黒にする。Android 12以降では不透明度0.8超のオーバーレイ越しのタッチがブロックされるため、100%暗転との両立はできない。通知の「表示切替」「停止」は逃げ道として残す。Phase 2aのデバッグwindowは0.70のまま。

---

## 6. 残りのロードマップ

- **Phase 2実機仕上げ**: 70%以下でYouTube操作が継続し80%超で意図通り遮断されること、100%暗転、暗転/回復カーブ、経路終端の数十cm精度を端末で調整する。
- **Phase 2d（実装済み、実機確認待ち）**:
  - 常時監視して検知後にカメラを起動するUsageStats試作は撤回した。代わりに、可視Activityから開始済みのcamera FGS内でだけ、実際に起動したYouTubeアプリ/ブラウザの連続前面時間を測る。
  - 「位置合わせして視聴開始」→Phase 1マーカー認識→camera FGSのARCore Session再開→YouTubeアプリ/既定ブラウザ起動、の順で視聴前にカメラサービスを準備する。サービス側のマーカー再確立はYouTube表示後も背後で継続する。
  - 視聴先の未設定時はブラウザ版が既定。Intent開始成功後だけ起動予約を完了し、失敗時は1秒間隔で最大3回再試行する。既定ブラウザが失敗すれば別のインストール済みブラウザを順次試す。
  - サービス側の初回マーカー再確立は8秒を上限とする。確立後のPAUSEDはマーカー再スキャンなしで自動復帰を待ち、STOPPEDだけを安全停止する。
  - マーカー待機・権限設定中の開始要求はActivityの保存状態、サービス開始後の一度だけの視聴先起動予約はサービスが保持する。画面回転や一時的なバックグラウンド移行後も継続する。
  - 連続視聴の介入閾値は10〜60秒を10秒単位、1分超は120分まで1分単位、既定30分。旧分単位設定は同じ時間を維持して秒単位へ移行する。別アプリ、画面消灯、画面ロック、データ欠損でリセットし、到達前のオーバーレイは透明・無表示にする。
  - UsageStatsは視聴先起動後の専用スレッドで初回5分・以後差分だけを1秒間隔で読む。ARCore owner threadは観測済みスナップショットを1件1回だけ消費し、別アプリ往復・消灯・ロック・欠損の中断フラグを失わない。
  - 対象ActivityのPAUSEDと後続RESUMEDが読取境界をまたぐ場合は連続時間を保持して時計だけ止める。旧サービスからの再開始は、STOPPINGでActivityをunbindし、onDestroy後の静的IDLEをmain-loopで確認してから発行する。STOPPING中は再bindしない。
  - 停滞判定後から100%暗転までの時間は1〜60秒を1秒単位、既定30秒から選択する。
  - フェード回復を発動する経路上の前進距離は0.5〜300cmを直接入力し、既定8cm。誘導サービス開始時に固定する。
  - 到着挙動は①フェード終了（2.5秒で暗転を滑らかに解除）②すぐ解除（650ms表示後に終了）の2モード。誘導開始のたびに選択し、サービス開始時に固定する。
  - 変更履歴は `docs/patch-notes.md` にパッチノート形式で継続記録する。
  - **セッションログ**: 発動時刻・軌跡・到着時刻・到着後の再視聴までの時間 等をJSON保存し、仮説検証(ブロッカー方式との比較)に使う。
- **将来**: マーカーレス化(出発点が毎回同じ利用特性を使った軌跡マッチング / Cloud Anchors)、複数目的地。

---

## 7. コード構成(ファイルマップ)

`app/src/main/java/com/nacon01/kunekune/`
- `MainActivity.kt` — Phase1前面AR画面の統括。ボタン: 記録開始/終了、誘導開始/終了(前面AR用)、位置合わせして視聴開始。利用状況を含む権限フロー、開始時の到着モード選択、YouTube起動もここ。
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
- `GuidanceOverlay.kt` — 矢印と内部暗転Viewを持つ誘導window。70%まではwindow alpha 0.70、以降は1.0まで上げる。99.9%以上では専用の方向HUDを子として持つopaqueな完全黒windowを最前面へ追加する。Home・別アプリでは従来どおり完全非表示にし、PiPは解決済み実パッケージごとに初回だけ設定案内する。
- `FadeController.kt` — 正味前進蓄積、猶予、ヒステリシス、追跡喪失凍結を持つ純粋ロジック。
- `ContinuousViewingTracker.kt` / `UsageStatsForegroundReader.kt` — camera FGSセッション内だけで、起動した実パッケージの連続前面利用を判定する。
- `TrackingRecoveryPolicy.kt` — 初回8秒、確立後PAUSEDの自動復帰、STOPPED安全停止を分離した純粋ポリシー。

`app/src/test/java/.../` — pure test classes: `RouteRecorderTest`, `GuidanceEngineTest`, `FadeControllerTest`, `GuidanceProgressSafetyTest`, `StoredRouteValidatorTest`, `TerminalFailureStatusTest`。
`tools/GenerateMarker.java` — マーカー画像生成(乱数シード固定)。`tools/arcoreimg/`(gitignore) — 品質検証ツール。
`docs/` — 各フェーズ解説、`marker/marker-print.html`。

---

## 8. 技術的に確定した重要事実・ハマりどころ

1. **ヘッドレスARCore**: カメラ型フォアグラウンドサービス + オフスクリーンEGL(pbuffer+外部OESテクスチャを`setCameraTextureName`)で、他アプリ前面でもVIO継続可能(2aで実証)。`requestInstall`はサービスから呼べないので、ARCoreインストール済み前提で`Session(context)`直接生成。
2. **Androidタッチセキュリティ**: `LayoutParams.alpha` と複数windowの合成不透明度が0.8以下なら、`FLAG_NOT_TOUCHABLE` overlay越しのタッチを許可できる。`View.alpha`だけでは条件を満たさない。
3. **二重ARCoreセッション衝突**: Activityとサービスはバインドした状態通知で所有権を引き渡す。Phase 2がPREPARINGからSTOPPINGの間、Phase 1は作成・resumeしない。
4. **マーカーアンカーの信頼度**: 非null poseだけでは不十分。cameraとAnchorの両方がTRACKINGのフレームだけ新しい方向を出す。
5. **マーカー品質**: 変更時は `tools/arcoreimg/arcoreimg.exe eval-img --input_image_path=app/src/main/assets/marker.png` で85点以上を確認。
6. **サービス初期化NPE**: フィールドで`context.mainLooper`を呼ぶとContext未接続でNPE。`Looper.getMainLooper()`を使う。
7. **color反転など端末設定**: アプリはシステム色を反転できない。`accessibility_display_inversion_enabled`等の端末設定が原因のことがある。

---

## 9. 制約・設計判断のまとめ

- 道2 = 実YouTubeアプリの上にオーバーレイ。演出は**フェード+矢印のみ**(位置ドリフト不可)。
- パーソナライズされたShortsは実アプリでしか得られない(WebViewはログイン不可)。
- 70%まではタッチ操作を優先してwindow alpha 0.70を維持し、以降は100%暗転を優先する。80%超ではOSにより下層タッチがブロックされる。通知の表示切替と停止を逃げ道として残す。
- 到着挙動は2dで「フェード終了/すぐ解除」を誘導開始ごとに選ぶ。将来NFC等の保留モードを追加できるよう値はサービス開始時に固定する。
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
