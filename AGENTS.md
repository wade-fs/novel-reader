# AGENTS.md

本專案為 Android 小說有聲閱讀器（`com.novel.reader`），使用 Kotlin + Android Views (XML Layout) + ViewBinding 開發。

## 技術棧與環境
- **語言**：Kotlin（Java 21 target）
- **SDK**：`minSdk = 24`、`compileSdk = 35`、`targetSdk = 35`
- **UI 框架**：XML Layout + **ViewBinding** + Material Components (`com.google.android.material:material:1.12.0`) + ConstraintLayout
  - **不要**引入 Jetpack Compose，所有畫面與元件皆以 XML + ViewBinding 實作。
- **非同步與網路**：Kotlin Coroutines、OkHttp、Jsoup

## 專案結構地圖
- `app/src/main/java/com/novel/reader/`
  - `ui/`
    - `MainActivity.kt` — 主閱讀畫面、頂部/底部控制列、播放控制、全螢幕切換
    - `NovelContentAdapter.kt` — 章節內文 RecyclerView（章名、段落、工作紀錄、章末導航）
    - `BookListBottomSheetDialogFragment.kt` / `BookListAdapter.kt` — 書架列表
    - `ChapterListBottomSheetDialogFragment.kt` / `ChapterListAdapter.kt` — 章節目錄與搜尋
    - `JumpChapterBottomSheetDialogFragment.kt` — 直達章節與分卷跳轉
    - `ImportBottomSheetDialogFragment.kt` — GitHub / 本機檔案匯入
    - `SettingsBottomSheetDialogFragment.kt` — 語速、語調、語音、朗讀範圍、主題設定
    - `WebNovelBrowserDialogFragment.kt` — 內建網頁小說瀏覽器
  - `model/` — 資料模型（`BookEntry`, `Chapter`, `ChapterContent`, `ReadingSettings`, `TtsSegment` 等）
  - `parser/` — `NovelChapterParser.kt`（Markdown 章節解析與文字清洗）
  - `repository/` — `NovelRepository.kt`
  - `service/` — `TtsReadingService.kt`（前景 TTS 朗讀服務與通知欄控制）
  - `util/` — `ThemeManager.kt`, `ReadingProgressManager.kt`, `BookLibraryManager.kt`, `GitHubHelper.kt`, `ArchiveHelper.kt` 等
- `app/src/main/res/`
  - `layout/` — `activity_main.xml`、`bottom_sheet_*.xml`、`dialog_*.xml`、`item_*.xml`
  - `values/` 與 `values-night/` — `colors.xml`、`themes.xml`、`strings.xml`
  - `drawable/` — 向量圖示與背景樣式

## 前端 UI 開發規範
1. **ViewBinding 優先**：新增或修改 XML 元件時，使用對應的 ViewBinding 存取，不要使用 `findViewById`（除非必要動態 View）。
2. **日夜間模式相容**：
   - 顏色必須定義於 `res/values/colors.xml` 與 `res/values-night/colors.xml`（或使用主題屬性 `?attr/...`），**避免在 XML 或 Kotlin 中寫死色碼**。
   - 修改樣式時需同時確認淺色與深色模式的可讀性，並留意 `ThemeManager.kt` 的行為。
3. **文字與語系**：UI 顯示文案統一放在 `res/values/strings.xml`，維持繁體中文。
4. **互動與狀態一致**：
   - 修改主畫面或列表 UI 時，確保不破壞 TTS 朗讀高光、自動捲動與全螢幕模式切換。
   - BottomSheet / Dialog 的修改需留意鍵盤彈出、捲動容器與小螢幕裝置的顯示空間。

## 建置與驗證
每次修改程式碼後，至少執行以下指令確認編譯與測試通過：

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

產出 APK 路徑：`app/build/outputs/apk/debug/app-debug.apk`

## 注意事項與禁區
- **不要修改** `app/build.gradle.kts` 中的 `signingConfigs` 設定。
- 若任務僅涉及前端 UI 修改，**不要**任意改動 `TtsReadingService.kt`、`NovelChapterParser.kt` 或匯入/解壓工具的核心邏輯，除非 UI 需求明確需要新增介面或回呼。
- 避免無關的重構與大規模格式化，保持 diff 精簡聚焦。
