# MoreMoreComic — プロジェクトメモ (Claude Code 用)

Claude Codeがこのディレクトリで起動すると自動で読み込まれる。
進め方のルールは、同じユーザーの別プロジェクト FoldLauncher(`~/AndroidStudioProjects/FoldLauncher`)から
写したもの。FoldLauncherでの会話の履歴はこちらには引き継がれないので、必要なことはここに書く。

## プロジェクト概要

- **何のアプリか**: Android用の漫画ビューア。iOSの「Booklover」
  (`https://www.plastic-software.com/ja/booklover/`)のようなものを目指す。
  主に使う端末は Google Pixel 11 Pro Fold(折りたたみ)。開いた画面で見開き、閉じた画面で単ページ、が基本。
- **ユーザーについて**: プログラミング経験ゼロから始めている。**コードは全てClaudeが書き**、ユーザーは
  機能を指示して、Android Studioでビルド・実機テストする分業。説明は専門用語を避けて日本語で。
- **アプリ名(仮)**: `MoreMoreComic`。2026-10-06時点で、Playストアの検索は0件、ウェブ検索でも同名のアプリは
  見つからなかった。商標(J-PlatPat、USPTO)は未確認。
  - **ルール**: 名称案が出る/変わるたび、聞かれなくても Playストア + 商標(J-PlatPat、可能ならUSPTO)を
    調べて、結果を日本語で報告する。「Booklover」など他人のアプリ名は使わない。
- **パッケージ名**: `io.github.escalatesnack.moremorecomic`(ストアに登録できる形。`com.example`は登録できない)。
  正式なアプリ名が決まったら見直す。
- **プロジェクトパス**: `~/AndroidStudioProjects/MoreMoreComic`(Mac)。
- **リポジトリ**: GitHubのプライベートリポジトリ `escalatesnack/MoreMoreComic`(Macは SSH)。
  このMacには`gh`コマンドが無い(GitHub上の操作が要るときは、ユーザーにサイトでやってもらう)。
- **技術スタック**: Kotlin + Jetpack Compose。`minSdk=26`, `targetSdk=37`, `compileSdk=37`
  (Gradleの設定・ライブラリのバージョンはFoldLauncherと同じにしてある)。
- **Macでのビルド確認の方法**: MacにはJava単体が入っていないので、Android Studio付属のJavaを指定する。
  `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` のあと
  `./gradlew :app:compileDebugKotlin --offline -q`(全体は`:app:assembleDebug`)。
  **新しいライブラリを足した直後の1回だけは`--offline`を外す**(ダウンロードが必要なため)。
  実機・エミュレータが繋がっていないことが多く、画面の見た目と操作はユーザーの実機確認に頼る。

## 現在の状態(2026-10-06)

第1段階を実装済み(**実機未確認**。QA No.1〜23)。ビルドと単体テストは通っている。
アプリのアイコンはFoldLauncherのものを仮に流用している。
ユーザーにまだ聞けていないこと: 漫画のファイルが今どこにあって形式は何か(端末内/NAS/クラウド)、
機能の仕分けで早くほしいもの・要らないもの。

## ディレクトリ構成(主要)

```
app/src/main/java/io/github/escalatesnack/moremorecomic/
  MainActivity.kt        本棚⇔ビューアの切り替え、フォルダ選択画面の呼び出し
  AppViewModel.kt        アプリ全体の状態(本棚の中身・開いている本・設定・読んだ位置)
  data/Library.kt        Book、本棚の保存(library.json)、フォルダの中から本を探す、数字順の並べ方
  data/ZipArchive.kt     ZIPの読み取り(自前。下の設計メモ参照)
  data/PageSource.kt     ページを番号で取り出す口(ZIP/画像フォルダ共通)、画像を縮めて読む
  data/CoverCache.kt     本棚の表紙(cacheDir/covers に保存)
  ui/BookshelfScreen.kt  本棚
  ui/ViewerScreen.kt     ビューア(めくる・見開き・拡大・上下のバー)
  ui/Spreads.kt          単ページ/見開きのまとまりの作り方
app/src/test/            ZipArchive・並べ方・見開きの単体テスト
```

## 主要な設計・技術パターン

- **ファイルの読み方**: Android標準のフォルダ選択(`OpenDocumentTree`)で許可をもらったフォルダの中だけを読む
  (`takePersistableUriPermission`で許可を保存)。本の`id`は、そのフォルダの中での場所(document URI)。
  ストレージ全体の許可(`MANAGE_EXTERNAL_STORAGE`)は使わない(ストア審査が厳しいため)。
- **ZIPは自前で読む(`ZipArchive`)**: 標準の`ZipFile`はファイルのパスが要るが、フォルダ選択で選んだファイルは
  パスをもらえない。`ZipInputStream`は頭から順にしか読めず、後ろのページを開くのが遅い。そこで、開いたファイルの
  `FileChannel`から目次(セントラルディレクトリ)を読み、必要なページだけ取り出す。無圧縮/Deflate/Zip64対応。
  ファイル名はUTF-8→だめならShift_JIS。**好きな位置から読めるファイル(端末内)が前提**で、クラウドの
  ファイルなどは読めない可能性がある(第4段階でいったん端末にコピーする等の対応が要る)。
  変えたら`./gradlew :app:testDebugUnitTest --offline`で確かめる。
- **本の単位**: ZIP/CBZは1ファイルで1冊。画像が直接入っているフォルダは1フォルダで1冊。
- **画像の読み込み**: 1ページ800万画素を上限に、縦横を半分ずつ縮めて読む(`decodeSampled`)。開いている本の
  ページは、メモリの3分の1までを上限に持っておく(`PageLoader`)。`largeHeap`を有効にしている。
- **単ページ/見開き**: 「自動」は画面の幅が600dp以上で見開き(Foldの開いた画面)。見開きは表紙だけ1枚。
  開閉で画面を作り直さないよう、マニフェストの`configChanges`で自分で受けている。
  1枚で見開きになっている横長の画像を1画面で出す処理はまだ無い(第3段階の「見開き位置の調節」とあわせて)。
- **拡大**: ピンチは1画面分(`SpreadPage`)で受ける。拡大中はスワイプでのページめくりを止め、
  めくると等倍に戻す。ダブルタップでの拡大は入れていない(タップでめくる反応が遅れるため)。
- **保存場所**: 本棚=`filesDir/library.json`、読んだ位置=SharedPreferences `positions`、
  設定(とじ方・表示)=SharedPreferences `settings`。

## 作る機能の計画(Bookloverの機能一覧から拾ったもの)

ユーザーとまだ相談中。優先順と範囲はユーザーの返事で確定させる。

- **第1段階(まず読めるようにする)**: 端末内のフォルダを選んで取り込み(Android標準のフォルダ選択)、
  ZIP/CBZと画像フォルダ、本棚(表紙のグリッド)、ビューア(右開き/左開き、単ページ/見開き、
  開閉に合わせた自動切り替え、ピンチで拡大、読んだ位置の記憶)。
- **第2段階(本棚を整える)**: グリッド/リスト切り替え、並べ替え(タイトル・ファイル名・追加日など)、
  未読/読書中/読了、お気に入り、タグ・ジャンル、検索、追加履歴・閲覧履歴、ファイル名からの
  タイトル・巻数・タグの読み取り、メタデータ編集、表紙の切り抜き、まとめて変更。
- **第3段階(ビューアを磨く)**: 見開き位置の調節、余白の切り落とし、表示方法(全体/縦フィット/横フィット)、
  ページめくりの効果、縦スクロール、ページのサムネイル一覧、プログレスバー、画質フィルター
  (コントラスト・グレー化・反転・シャープ)、ブックマーク、次の本/前の本へ移動、本ごとの個別設定、
  ページの書き出し(共有)。
- **第4段階(形式・取り込み元を広げる)**: RAR/CBR、PDF(画像として表示)、「…で開く」、
  NAS(SMB)、クラウド(Googleドライブ等。直接つなぐにはGoogle Cloudでの登録が必要)。
- **難しい/後回し**: PDFのテキスト選択・検索・注釈、画像内の文字認識、JPEG XL(Androidは標準で読めない)。
- **対象外(iOS専用)**: iTunes/Finder、AirDrop、iCloud Drive、QuickLook、Slide Over/Split View。

## 複数PC(Mac / Windows)での開発ルール

- **両方のPCで同時に作業しない**(衝突の原因)。
- **Claudeが自動でやること(ユーザーから事前に許可済み。毎回確認しなくてよい)**:
  - **セッション開始時**: 最初に`git pull --ff-only`で最新を取り込む。未コミットの変更があって
    取り込めない/衝突する場合は、勝手に解消せず、状況をユーザーに説明して止まる。
  - **区切りごと**: 機能を1つ実装し終えるたびに、変更内容を確認してからコミットして`git push`する。
  - Pushに失敗したら、黙って諦めず、失敗したことと原因をユーザーに報告する。
  - **自動でやらないこと**: 強制Push(`--force`)、`git reset --hard`、ブランチ削除など、履歴や他の変更を
    消す操作は、必ず事前にユーザーへ確認する。
  - `git add`の前に`git status`で、ビルド生成物・`local.properties`・鍵/パスワード類が混ざっていないか確認する。
- `local.properties`(SDKの場所)と `build/` `.gradle/` はPCごとに違う/自動生成なのでGitに入れない
  (`.gitignore`で除外済み)。
- 初回のGradle同期は10分ほどかかることがある。`gradle/gradle-daemon-jvm.properties`は JDK 25 を要求している。

## 関連アーティファクト

- **QA動作確認チェックリスト**(このアプリ専用。FoldLauncherのものとは別):
  `https://claude.ai/artifact/3ZRtWixwWtctUSDLAHVqJ9`
  FoldLauncherのチェックリストと同じ作り(ページ内の`app-data`に項目、`GROUPS`に見出し)。
  項目を足すときは、読み込んでから`items`と`GROUPS`に足し、チェック済みを`archive`へ移して公開し直す。

## 進め方・コミュニケーションの好み

- やり取りは日本語。
- チェックリスト系の依頼は、インタラクティブなチェックボックス付きアーティファクト(進捗トラッカー付き)で作る。
- 新しく検証が必要な変更を実装したら、聞かれなくてもQAチェックリストに項目を追加する。
  チェック済みの項目は、新規項目を追加するタイミングで「アーカイブ済み」へ移す(手動アーカイブボタンも常設)。
- UIは基本的に角丸をデフォルトにする。
- 設定の項目を別のカテゴリへ移すなど、ユーザーが覚えている場所を変える変更は、提案して了承を得てから。
- 他アプリの画面を開くインテントには、必ず`FLAG_ACTIVITY_NEW_TASK`を付ける(FoldLauncherで踏んだ不具合)。
