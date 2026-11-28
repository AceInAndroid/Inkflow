import Foundation

/// Only interface copy is localized. Book titles, body text, questions and custom mottos are verbatim.
enum InterfaceText: CaseIterable {
    case home, library, me, importBook, delete, cancel, done, ok, close
    case emptyTitle, emptyDetail, continueReading, recentReading, unread, history, books, started, completed
    case homeSettings, style, classic, wallpaper, minimal, eink, appearance, theme, system, light, dark
    case language, automatic, motto, defaultMotto, resetMotto, resetHome, components, clock, showMotto, showRecent, showHistory
    case chooseWallpaper, removeWallpaper, invalidWallpaper, wallpaperHelp, search, localOnly, about, foundation, noHistory
    case openError, opening, previous, next, textPage, smallerType, largerType, stop, listen, context
    case capturedPosition, visibleOffset, localPreparation, readingMethod, skill, question, prepare, sourceOnly, bridgeUnavailable, prepared, readingContext
    case unsupported, tooLarge, invalidText, invalidPDF, loadLibraryError
    case saveLibraryError, damagedIndex, missingBook, importResults, imported, alreadyImported, restoredFile, importFailed, dismissResults
    case jumpToPage, pageNumber, page, retry, defaultQuestion

    func text(_ language: InterfaceLanguage) -> String {
        let values: (String, String, String, String)
        switch self {
        case .home: values = ("Home", "首页", "ホーム", "홈")
        case .library: values = ("Library", "书架", "ライブラリ", "서재")
        case .me: values = ("Me", "我的", "マイページ", "내 정보")
        case .importBook: values = ("Import", "导入", "読み込む", "가져오기")
        case .delete: values = ("Delete", "删除", "削除", "삭제")
        case .cancel: values = ("Cancel", "取消", "キャンセル", "취소")
        case .done: values = ("Done", "完成", "完了", "완료")
        case .ok: values = ("OK", "好", "OK", "확인")
        case .close: values = ("Close reader", "关闭阅读器", "リーダーを閉じる", "리더 닫기")
        case .emptyTitle: values = ("Your reading starts here", "从这里开始阅读", "ここから読書を始めよう", "여기서 독서를 시작하세요")
        case .emptyDetail: values = ("Import a TXT or PDF from Files.", "从文件导入 TXT 或 PDF。", "ファイルから TXT または PDF を読み込めます。", "파일에서 TXT 또는 PDF를 가져오세요.")
        case .continueReading: values = ("Continue reading", "继续阅读", "読書を続ける", "이어 읽기")
        case .recentReading: values = ("Recently read", "最近阅读", "最近読んだ本", "최근 읽은 책")
        case .unread: values = ("Start reading", "开始阅读", "読み始める", "읽기 시작")
        case .history: values = ("Reading journey", "阅读历程", "読書の記録", "독서 기록")
        case .books: values = ("Books", "藏书", "蔵書", "보유 도서")
        case .started: values = ("Opened", "已打开", "開いた本", "열어 본 책")
        case .completed: values = ("Completed", "已读完", "読了", "완독")
        case .homeSettings: values = ("Home settings", "桌面设置", "ホーム設定", "홈 설정")
        case .style: values = ("Home style", "首页风格", "ホームスタイル", "홈 스타일")
        case .classic: values = ("Classic", "经典", "クラシック", "클래식")
        case .wallpaper: values = ("Wallpaper", "壁纸沉浸", "壁紙", "배경화면")
        case .minimal: values = ("Minimal", "极简", "ミニマル", "미니멀")
        case .eink: values = ("E-INK", "E-INK", "E-INK", "E-INK")
        case .appearance: values = ("Appearance & Home", "外观与桌面", "外観とホーム", "화면 및 홈")
        case .theme: values = ("App theme", "应用主题", "アプリの外観", "앱 테마")
        case .system: values = ("System", "跟随系统", "システム", "시스템 설정")
        case .light: values = ("Light", "浅色", "ライト", "라이트")
        case .dark: values = ("Dark", "深色", "ダーク", "다크")
        case .language: values = ("Interface language", "界面语言", "表示言語", "화면 언어")
        case .automatic: values = ("Automatic", "自动", "自動", "자동")
        case .motto: values = ("Motto", "座右铭", "モットー", "좌우명")
        case .defaultMotto: values = ("Read locally, at your own pace.", "按自己的节奏，静心阅读。", "自分のペースで、読書を。", "나만의 속도로 읽으세요.")
        case .resetMotto: values = ("Use default motto", "恢复默认座右铭", "標準のモットーに戻す", "기본 좌우명 사용")
        case .resetHome: values = ("Reset Home", "重置桌面", "ホームをリセット", "홈 초기화")
        case .components: values = ("Home components", "桌面组件", "ホームの表示項目", "홈 표시 항목")
        case .clock: values = ("Time & date", "时间与日期", "時刻と日付", "시간 및 날짜")
        case .showMotto: values = ("Show motto", "显示座右铭", "モットーを表示", "좌우명 표시")
        case .showRecent: values = ("Show recent books", "显示最近阅读", "最近読んだ本を表示", "최근 도서 표시")
        case .showHistory: values = ("Show reading journey", "显示阅读历程", "読書の記録を表示", "독서 기록 표시")
        case .chooseWallpaper: values = ("Choose local image", "选择本地图片", "端末の画像を選ぶ", "기기 이미지 선택")
        case .removeWallpaper: values = ("Remove wallpaper", "移除壁纸", "壁紙を削除", "배경화면 삭제")
        case .invalidWallpaper: values = ("Choose an image smaller than 20 MB.", "请选择小于 20 MB 的图片。", "20 MB 未満の画像を選んでください。", "20 MB 미만의 이미지를 선택하세요.")
        case .wallpaperHelp: values = ("Saved on this device. Missing images use a solid background.", "仅存于本机。图片失效时使用纯色背景。", "この端末に保存します。画像がない場合は単色で表示します。", "이 기기에 저장됩니다. 이미지가 없으면 단색 배경을 사용합니다.")
        case .search: values = ("Search books", "搜索书籍", "本を検索", "도서 검색")
        case .localOnly: values = ("Stored on this device", "仅存于此设备", "この端末に保存", "이 기기에 저장됨")
        case .about: values = ("About", "关于", "このアプリについて", "앱 정보")
        case .foundation: values = ("iOS foundation · TXT, PDF and on-device speech. EPUB, sync, notes and AI sending are not available yet.", "iOS 基础版 · TXT、PDF 和本机朗读。EPUB、同步、笔记和 AI 发送尚未提供。", "iOS 基盤版 · TXT、PDF、端末での読み上げに対応。EPUB、同期、ノート、AI 送信は未対応です。", "iOS 기반 버전 · TXT, PDF, 기기 내 읽어주기를 지원합니다. EPUB, 동기화, 노트, AI 전송은 아직 지원하지 않습니다.")
        case .noHistory: values = ("Open a book to begin your reading journey.", "打开一本书，开始阅读历程。", "本を開いて読書の記録を始めましょう。", "책을 열어 독서 기록을 시작하세요.")
        case .openError: values = ("Could not open book", "无法打开书籍", "本を開けませんでした", "책을 열 수 없습니다")
        case .opening: values = ("Opening…", "正在打开…", "開いています…", "여는 중…")
        case .previous: values = ("Previous page", "上一页", "前のページ", "이전 페이지")
        case .next: values = ("Next page", "下一页", "次のページ", "다음 페이지")
        case .textPage: values = ("Text page", "文本页码", "テキストのページ", "텍스트 페이지")
        case .smallerType: values = ("Smaller type", "缩小字号", "文字を小さく", "글자 작게")
        case .largerType: values = ("Larger type", "放大字号", "文字を大きく", "글자 크게")
        case .stop: values = ("Stop", "停止", "停止", "중지")
        case .listen: values = ("Listen", "朗读", "読み上げ", "듣기")
        case .context: values = ("Context", "阅读上下文", "読書コンテキスト", "읽기 문맥")
        case .capturedPosition: values = ("Captured reading position", "已捕获的阅读位置", "取得した読書位置", "저장된 읽기 위치")
        case .visibleOffset: values = ("Visible UTF-16 offset", "可见 UTF-16 偏移", "表示範囲の UTF-16 位置", "표시 범위 UTF-16 위치")
        case .localPreparation: values = ("Opening this panel does not contact a model. Preparation stays on this device.", "打开此面板不会调用模型。请求仅在本机准备。", "このパネルを開いてもモデルには接続しません。準備は端末内で行います。", "이 패널을 열어도 모델에 연결하지 않습니다. 요청은 기기에서만 준비됩니다.")
        case .readingMethod: values = ("Shared reading method", "共享阅读方法", "共有の読書メソッド", "공유 읽기 방식")
        case .skill: values = ("Skill", "技能", "スキル", "스킬")
        case .question: values = ("Question", "问题", "質問", "질문")
        case .prepare: values = ("Prepare request locally", "在本机准备请求", "端末内でリクエストを準備", "기기에서 요청 준비")
        case .sourceOnly: values = ("Source-only build", "仅源码构建", "ソースのみのビルド", "소스 전용 빌드")
        case .bridgeUnavailable: values = ("Shared reading skills require InkflowNativeSharedCore and InkflowCore.xcframework. See ios-native/README.md.", "共享阅读技能需要 InkflowNativeSharedCore 和 InkflowCore.xcframework。详见 ios-native/README.md。", "共有スキルには InkflowNativeSharedCore と InkflowCore.xcframework が必要です。ios-native/README.md を参照してください。", "공유 스킬에는 InkflowNativeSharedCore와 InkflowCore.xcframework가 필요합니다. ios-native/README.md를 참고하세요.")
        case .prepared: values = ("Prepared request · not sent", "已准备请求 · 未发送", "準備済みリクエスト・未送信", "준비된 요청 · 전송 안 됨")
        case .readingContext: values = ("Reading context", "阅读上下文", "読書コンテキスト", "읽기 문맥")
        case .unsupported: values = ("This app supports TXT and PDF files.", "此应用支持 TXT 和 PDF 文件。", "TXT と PDF ファイルに対応しています。", "TXT 및 PDF 파일을 지원합니다.")
        case .tooLarge: values = ("Choose a file smaller than 64 MB.", "请选择小于 64 MB 的文件。", "64 MB 未満のファイルを選んでください。", "64 MB 미만의 파일을 선택하세요.")
        case .invalidText: values = ("TXT files must use UTF-8 or BOM-marked UTF-16 encoding.", "TXT 文件须使用 UTF-8 或带 BOM 的 UTF-16 编码。", "TXT は UTF-8 または BOM 付き UTF-16 が必要です。", "TXT는 UTF-8 또는 BOM이 있는 UTF-16 인코딩이어야 합니다.")
        case .invalidPDF: values = ("The PDF is empty, damaged, or password protected.", "PDF 为空、已损坏或受密码保护。", "PDF が空、破損、またはパスワードで保護されています。", "PDF가 비어 있거나 손상되었거나 암호로 보호되어 있습니다.")
        case .saveLibraryError: values = ("Could not save the library", "无法保存书架", "ライブラリを保存できませんでした", "서재를 저장할 수 없습니다")
        case .damagedIndex: values = ("The library index is damaged or unreadable. Its files have been preserved.", "书库索引损坏或无法读取，原有文件已保留。", "ライブラリの索引が破損しているか読み込めません。元のファイルは保持されています。", "서재 색인이 손상되었거나 읽을 수 없습니다. 기존 파일은 보존되었습니다.")
        case .missingBook: values = ("This book is unavailable. Import the original file again to restore a missing copy.", "此书不可用。可重新导入原文件以恢复缺失的副本。", "この本は利用できません。元のファイルを再度読み込むと、失われたコピーを復元できます。", "이 책을 사용할 수 없습니다. 원본 파일을 다시 가져오면 누락된 사본을 복원할 수 있습니다.")
        case .importResults: values = ("Import results", "导入结果", "読み込み結果", "가져오기 결과")
        case .imported: values = ("Imported", "已导入", "読み込み済み", "가져옴")
        case .alreadyImported: values = ("Already in Library", "已在书架中", "ライブラリに登録済み", "서재에 이미 있음")
        case .restoredFile: values = ("Local file restored", "本地文件已恢复", "端末のファイルを復元しました", "기기 파일 복원됨")
        case .importFailed: values = ("Import failed", "导入失败", "読み込みに失敗しました", "가져오기 실패")
        case .dismissResults: values = ("Dismiss results", "收起结果", "結果を閉じる", "결과 닫기")
        case .jumpToPage: values = ("Go to page", "跳转到页", "ページへ移動", "페이지로 이동")
        case .pageNumber: values = ("Page number", "页码", "ページ番号", "페이지 번호")
        case .page: values = ("Page", "页", "ページ", "페이지")
        case .retry: values = ("Try again", "重试", "再試行", "다시 시도")
        case .defaultQuestion: values = ("Explain the passage I have read.", "请解释我已读的这段内容。", "読んだ箇所を説明してください。", "읽은 구절을 설명해 주세요.")
        case .loadLibraryError: values = ("Could not load the library", "无法加载书架", "ライブラリを読み込めませんでした", "서재를 불러올 수 없습니다")
        }
        switch language { case .en: return values.0; case .zhHans: return values.1; case .ja: return values.2; case .ko: return values.3 }
    }
}

extension HomeStyle {
    var title: InterfaceText { switch self { case .classic: return .classic; case .wallpaper: return .wallpaper; case .minimal: return .minimal; case .eink: return .eink } }
}
extension AppTheme {
    var title: InterfaceText { switch self { case .system: return .system; case .light: return .light; case .dark: return .dark } }
}
extension LanguageChoice {
    func title(_ language: InterfaceLanguage) -> String {
        switch self { case .automatic: return InterfaceText.automatic.text(language); case .en: return "English"; case .zhHans: return "简体中文"; case .ja: return "日本語"; case .ko: return "한국어" }
    }
}
