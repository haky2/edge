import SwiftUI
import SharedLogic

private enum BriefingTab: String, CaseIterable {
    case myStocks = "내 종목"
    case market  = "시장"
}

struct BriefingView: View {
    private let api = Db.api

    // 설정 탭에서 정한 분석 모드(전역). 시장 분위기 코멘트 톤을 결정한다.
    @AppStorage(analysisModeKey) private var modeRaw = AnalysisMode.defensive.rawValue
    private var analysisMode: AnalysisMode { AnalysisMode(rawValue: modeRaw) ?? .defensive }

    @State private var selectedTab: BriefingTab = .myStocks

    // quotes 로드 중: 전체 화면 스피너. false가 되면 하이라이트/보유현황 즉시 표시.
    @State private var loading = false
    @State private var lastUpdated: Date?
    // supply 로드 중: 수급 섹션 내부 스피너. quotes와 독립적으로 관리.
    @State private var supplyLoading = false
    @State private var errorText: String?
    @State private var calendar: MarketCalendar?   // 개장 캘린더(휴장/다음 거래일 배너)

    // 시장 지표(코스피·환율·미국지수). 관심종목과 무관해 quotes와 독립 병렬 로드.
    @State private var macroLoading = false
    @State private var macroItems: [MacroIndicator] = []

    // 매크로 → 내 종목 영향(Claude 해석). 느려서 섹션 내부 스피너 + 독립 병렬.
    @State private var impactLoading = false
    @State private var impactComment = ""
    @State private var impactHoldings: [StockImpact] = []
    @State private var impactWatch: [StockImpact] = []
    @State private var impactGeneratedAt = ""      // 캐시 최초 생성 시각 HH:mm
    @State private var impactDate = ""             // 생성 기준일 YYYY-MM-DD
    @State private var watchlistIsEmpty = false    // 관심종목 0개 여부 (로드 후 확정)

    @State private var topGainers: [QuoteRow] = []
    @State private var topLosers: [QuoteRow] = []
    @State private var holdings: [HoldingRow] = []
    @State private var supplyRows: [SupplyRow] = []

    // DART 최근 공시
    @State private var dartLoading = false
    @State private var dartItems: [DartItem] = []

    // 실적 일정
    @State private var earningsLoading = false
    @State private var earningsItems: [EarningsEntry] = []
    @State private var earningsPreviews: [String: EarningsPreview] = [:]  // F3: D-7 이내 프리뷰

    // 거시 이벤트 캘린더
    @State private var eventsLoading = false
    @State private var eventItems: [MarketEvent] = []

    // 오늘 시장 분위기 (Claude 코멘트)
    @State private var moodLoading = false
    @State private var moodComment = ""
    @State private var moodGeneratedAt = ""
    @State private var moodDate = ""          // 생성 기준일 YYYY-MM-DD
    @State private var moodExpanded = true    // 시장 탭 최상단이라 기본 펼침

    // 코스피 방향 선행 신호 (미국 지수 가중합 예측 + 적중률)
    @State private var moodAccuracy: MoodAccuracyReport? = nil

    // 섹터 동향
    @State private var sectorLoading = false
    @State private var sectorItems: [SectorIndex] = []

    // 섹터 자금 순환
    @State private var rotationLoading = false
    @State private var sectorRotation: SectorRotation? = nil

    // 섹터 분석 (Claude 코멘트 + 주목 종목)
    @State private var sectorBriefingLoading = false
    @State private var sectorBriefingComment = ""
    @State private var sectorSpotlight: [SpotlightStock] = []
    @State private var sectorBriefingGeneratedAt = ""  // 캐시 최초 생성 시각 HH:mm
    @State private var sectorBriefingDate = ""         // 생성 기준일 YYYY-MM-DD

    // 테마별 재료 동향 (catalyst brief)
    @State private var catalystBriefLoading = false
    @State private var catalystBriefSectors: [SectorCatalystLine] = []
    @State private var catalystBriefExpanded = false


    // 접기/펼치기 상태 (기본 접힘)
    @State private var supplyExpanded = false
    @State private var dartExpanded = false
    @State private var earningsExpanded = false
    @State private var eventsExpanded = false
    @State private var impactSectionExpanded = false  // 내 종목 영향: 섹션 전체 접기
    @State private var impactExpanded = false        // 내 종목 영향: AI 코멘트(프로즈) 접기
    @State private var impactWatchExpanded = false   // 내 종목 영향: 관심 종목 목록 접기
    @State private var marketExpanded = false
    @State private var moodSignalExpanded = false
    @State private var sectorExpanded = false
    @State private var sectorBriefingExpanded = false // 섹터 분석 AI 코멘트 + 주목 종목 접기

    // 하이라이트·보유현황용: 쿼츠 로드 후 저장 (spotlight NavigationLink에서도 재사용)
    @State private var allItemsLoaded: [WatchItem] = []
    @State private var quoteMapLoaded: [String: Quote] = [:]

    var body: some View {
        NavigationStack {
            Group {
                if loading {
                    ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    content
                }
            }
            .navigationTitle("브리핑")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    let isLoading = loading || supplyLoading || dartLoading || macroLoading || moodLoading || impactLoading || earningsLoading || eventsLoading || sectorLoading || sectorBriefingLoading || catalystBriefLoading || rotationLoading
                    if isLoading {
                        ProgressView().scaleEffect(0.8)
                    } else {
                        Menu {
                            if let t = lastUpdated {
                                Section("\(shortTime(t)) 기준") {
                                    Button { Task { await load() } } label: {
                                        Label("전체 새로고침", systemImage: "arrow.clockwise")
                                    }
                                    Button { Task { await regenAllAI() } } label: {
                                        Label("AI 코멘트 재생성", systemImage: "sparkles")
                                    }
                                }
                            } else {
                                Button { Task { await load() } } label: {
                                    Label("전체 새로고침", systemImage: "arrow.clockwise")
                                }
                            }
                        } label: {
                            Image(systemName: "arrow.clockwise")
                        } primaryAction: {
                            Task { await load() }
                        }
                    }
                }
            }
            .task { await load() }
            .onAppear { Usage.shared.view("briefing") }
            .refreshable { await load() }
            // 설정에서 모드를 바꾸면 시장 분위기 + 내 종목 영향 재호출(두 섹션이 모드 영향).
            .onChange(of: modeRaw) {
                Task { await buildMarketMood() }
                Task { await buildImpact(allItems: allItemsLoaded) }
            }
        }
    }

    private var content: some View {
        List {
            if let e = errorText {
                Section {
                    Text(e).font(.footnote).foregroundColor(.secondary)
                }
            }
            // 탭 선택기 — 섹션 구분선 없이 세그먼트 컨트롤만 노출
            // Picker + 데이터 기준일 배너(footer). footer로 붙여 별도 빈 행이 생기지 않게 한다.
            Section {
                Picker("", selection: $selectedTab) {
                    ForEach(BriefingTab.allCases, id: \.self) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
                .listRowBackground(Color(.systemGroupedBackground))
                .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 4, trailing: 16))
            } footer: {
                dataFreshnessBanner
            }

            // 휴장일 배너 — 양 탭 공통, 오늘 휴장일 때만. 거래일엔 노출 안 함(군더더기 방지).
            if let cal = calendar, cal.isHoliday {
                Section {
                    HStack(spacing: 8) {
                        Image(systemName: "calendar.badge.exclamationmark")
                            .foregroundColor(.orange)
                        Text("오늘은 휴장입니다").font(.subheadline.weight(.semibold))
                        if let next = formatBizDay(cal.nextBusinessDay) {
                            Spacer()
                            Text("다음 거래일 \(next)").font(.footnote).foregroundColor(.secondary)
                        }
                    }
                }
            }

            if selectedTab == .myStocks {
                highlightSection
                holdingsSection
                supplySection
                dartSection
                catalystBriefSection
            } else {
                marketMoodSection    // 오늘 시장 분위기 — 장 전 코스피 방향 한눈에
                moodSignalSection    // 코스피 방향 선행 신호 (가중합 예측 + 적중률)
                impactSection        // 내 종목 영향
                marketSection
                sectorSection
                sectorRotationSection
                sectorBriefingSection
                eventCalendarSection
                earningsSection
            }
        }
        .contentMargins(.top, 0, for: .scrollContent)
    }

    // MARK: - 섹션: 오늘 시장 분위기 (Claude)

    @ViewBuilder
    private var marketMoodSection: some View {
        Section {
            if moodLoading {
                VStack(alignment: .leading, spacing: 6) {
                    aiSectionTitle("오늘 시장 분위기", aggressive: analysisMode == .aggressive)
                    HStack {
                        ProgressView().scaleEffect(0.8)
                        Text("AI가 분석 중…").font(.footnote).foregroundColor(.secondary)
                    }
                }
            } else if moodComment.isEmpty {
                VStack(alignment: .leading, spacing: 6) {
                    aiSectionTitle("오늘 시장 분위기", aggressive: analysisMode == .aggressive)
                    Text("불러오지 못했어요").font(.footnote).foregroundColor(.secondary)
                }
            } else {
                VStack(alignment: .leading, spacing: 4) {
                    aiCommentToggle(
                        title: "오늘 시장 분위기",
                        aggressive: analysisMode == .aggressive,
                        expanded: moodExpanded,
                        dateLabel: mdLabel(moodDate),
                        generatedAt: moodGeneratedAt,
                        weekendReuse: freshness.isSundayReuse,
                        isLoading: moodLoading,
                        action: { withAnimation { moodExpanded.toggle() } },
                        regenAction: { Task { await buildMarketMood(force: true) } }
                    )
                    if moodExpanded { proseBlock(moodComment) }
                }
            }
        }
    }

    // MARK: - 섹션: 코스피 방향 선행 신호

    @ViewBuilder
    private var moodSignalSection: some View {
        Section {
            Button { withAnimation { moodSignalExpanded.toggle() } } label: {
                HStack {
                    Text("코스피 방향 선행 신호").font(.headline)
                    Spacer()
                    Image(systemName: moodSignalExpanded ? "chevron.up" : "chevron.down")
                        .font(.caption).foregroundColor(.secondary)
                }
            }
            .foregroundColor(.primary)
            if let acc = moodAccuracy, moodSignalExpanded {
                let todayEntry = acc.recentEntries.first
                let todayPending = todayEntry != nil && todayEntry?.isCorrect == nil

                if acc.recentEntries.isEmpty {
                    Text("매일 오전 5시·오후 3시 35분에\n자동으로 기록돼요.")
                        .font(.subheadline).foregroundColor(.secondary)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                } else {
                    if todayPending, let entry = todayEntry {
                        moodTodayCard(entry.direction)
                    }
                    if acc.total > 0 {
                        let rate = Int(Double(acc.correct) / Double(acc.total) * 100)
                        // X1: 색상은 기저율 상대. 무정보 벤치마크(매일 다수 방향 찍기)를 +2%p 넘겨야
                        // 정보력 있음(초록), 못 넘으면 회색 — 절대 %가 높아도 우연 이상이 아님을 표시.
                        let beatsBaseline = rate >= acc.baselineRate + 2
                        VStack(alignment: .leading, spacing: 2) {
                            HStack {
                                Text("\(acc.correct)/\(acc.total)회 적중 · \(rate)%")
                                    .font(.subheadline)
                                    .foregroundColor(beatsBaseline ? .green : .secondary)
                                Spacer()
                                if acc.pending > 0 {
                                    Text("대기 \(acc.pending)").font(.caption).foregroundColor(.secondary)
                                }
                            }
                            Text("같은 기간 매일 \(moodDirectionLabel(acc.baselineDirection)) 찍기: \(acc.baselineRate)%")
                                .font(.caption2).foregroundColor(.secondary)
                        }
                    }
                    let historyEntries = todayPending
                        ? Array(acc.recentEntries.dropFirst().prefix(5))
                        : Array(acc.recentEntries.prefix(5))
                    ForEach(historyEntries, id: \.date) { entry in
                        moodHistoryRow(entry)
                    }
                }
            } else if moodAccuracy == nil {
                HStack {
                    ProgressView().scaleEffect(0.8)
                    Text("불러오는 중…").font(.footnote).foregroundColor(.secondary)
                }
            }
        } footer: {
            if moodSignalExpanded {
                Text("미장 마감(오전 5시) 이후 확인 적기. 장 마감(오후 3:30) 후 자동 채점돼요.")
                    .font(.caption2)
            }
        }
    }

    private func moodTodayCard(_ direction: String) -> some View {
        let (label, color, icon): (String, Color, String) = switch direction {
            case "BULLISH": ("강세 예상 ↑", Color.red,      "arrow.up.circle.fill")
            case "BEARISH": ("약세 예상 ↓", Color.blue,     "arrow.down.circle.fill")
            default:        ("보합 예상",   Color.secondary, "minus.circle.fill")
        }
        return HStack(spacing: 12) {
            Image(systemName: icon).font(.title2).foregroundColor(color)
            VStack(alignment: .leading, spacing: 2) {
                Text("오늘 코스피").font(.caption).foregroundColor(.secondary)
                Text(label).font(.headline).foregroundColor(color)
            }
            Spacer()
            Text("미국 지수·달러 기반").font(.caption2).foregroundColor(.secondary)
        }
        .padding(.vertical, 4)
    }

    private func moodHistoryRow(_ entry: MoodLogEntry) -> some View {
        HStack(spacing: 6) {
            Text(entry.date.suffix(5).replacingOccurrences(of: "-", with: "/"))
                .font(.caption).foregroundColor(.secondary).frame(width: 40, alignment: .leading)

            // 예측 레이블 + 배지
            Text("예측").font(.caption2).foregroundColor(.secondary)
            moodDirectionBadge(entry.direction, isActual: false)

            Image(systemName: "arrow.right").font(.caption2).foregroundColor(.secondary)

            // 실제 레이블 + 배지
            Text("실제").font(.caption2).foregroundColor(.secondary)
            if let actual = entry.actualDirection {
                moodDirectionBadge(actual, isActual: true)
            } else {
                Text("대기").font(.caption2).foregroundColor(.secondary)
            }

            Spacer()

            if let correct = entry.isCorrect {
                Image(systemName: correct.boolValue ? "checkmark.circle.fill" : "xmark.circle.fill")
                    .foregroundColor(correct.boolValue ? .green : .red).font(.body)
            }
            if let kChange = entry.kospiChange {
                let v = kChange.doubleValue
                Text("\(v >= 0 ? "+" : "")\(String(format: "%.1f", v))%")
                    .font(.caption2).foregroundColor(v >= 0 ? .red : .blue)
            }
        }
        .padding(.vertical, 1)
    }

    // X1: 기저율 병기용 방향 라벨(다수 클래스 표기).
    private func moodDirectionLabel(_ direction: String?) -> String {
        switch direction {
        case "BULLISH": return "강세"
        case "BEARISH": return "약세"
        case "NEUTRAL": return "보합"
        default:        return "다수 방향"
        }
    }

    private func moodDirectionBadge(_ direction: String, isActual: Bool) -> some View {
        let (label, color): (String, Color) = switch direction {
            case "BULLISH": ("강세↑", .red)
            case "BEARISH": ("약세↓", .blue)
            default:        ("보합",  .secondary)
        }
        return Text(label)
            .font(.caption2.weight(.semibold))
            .foregroundColor(isActual ? .primary : color)
            .padding(.horizontal, 5).padding(.vertical, 2)
            .background(isActual ? Color.secondary.opacity(0.12) : color.opacity(0.12))
            .clipShape(Capsule())
    }

    // MARK: - 섹션: 시장 지표

    @ViewBuilder
    private var marketSection: some View {
        Section {
            Button { withAnimation { marketExpanded.toggle() } } label: {
                HStack {
                    VStack(alignment: .leading, spacing: 1) {
                        Text("시장 지표").font(.headline)
                        Text("전일 대비").font(.caption2).foregroundColor(.secondary)
                    }
                    Spacer()
                    Image(systemName: marketExpanded ? "chevron.up" : "chevron.down")
                        .font(.caption).foregroundColor(.secondary)
                }
            }
            .foregroundColor(.primary)
            if macroLoading {
                HStack { ProgressView().scaleEffect(0.8); Text("확인 중…").font(.footnote).foregroundColor(.secondary) }
            } else if marketExpanded {
                if macroItems.isEmpty {
                    Text("불러오기 실패").font(.footnote).foregroundColor(.secondary)
                } else {
                    ForEach(macroItems, id: \.key) { macroRow($0) }
                }
            }
        }
    }

    private func macroRow(_ m: MacroIndicator) -> some View {
        // 보합(0%)은 회색, 상승 빨강/하락 파랑(국내 관례).
        let flat = m.changeRate == 0
        let up = m.changeRate > 0
        let color: Color = flat ? .secondary : (up ? .red : .blue)
        let arrow = flat ? "–" : (up ? "▲" : "▼")
        return VStack(alignment: .leading, spacing: 3) {
            HStack {
                Text(m.label).font(.body)
                if !m.tag.isEmpty {
                    Text(m.tag)
                        .font(.caption2.weight(.semibold))
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(tagColor(m.tag).opacity(0.15))
                        .foregroundColor(tagColor(m.tag))
                        .clipShape(Capsule())
                }
                Spacer()
                VStack(alignment: .trailing, spacing: 2) {
                    Text(formatMacroValue(m)).font(.body.weight(.semibold))
                    Text("\(arrow) \(String(format: "%.2f", abs(m.changeRate)))%")
                        .font(.caption).foregroundColor(color)
                }
            }
            if let desc = macroDescription(m.key) {
                Text(verbatim: desc)
                    .font(.caption2).foregroundColor(.secondary)
            }
        }
        .padding(.vertical, 2)
    }

    private func macroDescription(_ key: String) -> String? {
        switch key {
        case "kodex200_ot": return "코스피200 추종 ETF의 시간외 단일가예요. 장 전(8-9시)엔 오늘 코스피 출발 방향을, 장 후(16-18시)엔 연장 거래 방향을 보여줘요."
        case "kospi":       return "국내 대형주 중심 종합주가지수예요. 코스피 방향이 국내 주식 전반의 흐름을 결정해요."
        case "kosdaq":      return "중소형·기술주 중심 지수예요. 코스피보다 변동성이 크고 성장주 비중이 높아요."
        case "usdkrw":      return "원화 대비 달러 환율이에요. 오를수록 원화 약세로, 외국인 매도 압력이 생기는 경향이 있어요."
        case "ewy":         return "미국에 상장된 한국 주식 ETF예요. 미국 장중(한국 기준 밤)에 외국인이 한국 주식을 어떻게 평가하는지 보여줘요."
        case "nasdaq":      return "미국 기술주 중심 지수예요. AI·반도체·플랫폼 등 성장주 방향을 가장 잘 나타내요."
        case "sox":         return "미국 필라델피아 반도체 지수예요. 삼성전자·SK하이닉스 등 반도체 종목 흐름의 선행 지표예요."
        case "sp500":       return "미국 대형주 500개 평균이에요. 미국 증시 전반의 건강을 가장 균형 있게 보여줘요."
        case "dow":         return "미국 블루칩 30개 산업주 평균이에요. 역사가 긴 지수지만 기술주 비중이 낮아요."
        case "rut":         return "미국 소형주 2000개 지수예요. 대형주보다 경기 민감도가 높아 위험 선호 심리를 가늠해요."
        case "tnx":         return "미국 국채 10년물 금리예요. 금리 상승은 주식 밸류에이션에 부담을, 하락은 유동성 개선 신호예요."
        case "dxy":         return "달러의 상대적 강세를 나타내는 지수예요. 오를수록 신흥국 자금이 미국으로 이동하는 경향이 있어요."
        case "rate3y":      return "한국 국고채 3년물 금리예요. 국내 시장금리 기준으로, 상승 시 성장주 밸류에이션 부담이 생겨요."
        case "crude":       return "국제 원유 기준 가격이에요. 에너지·운송 비용과 인플레이션 압력에 영향을 줘요."
        case "copper":      return "구리는 글로벌 경기 선행 지표예요. 오르면 경기 회복, 내리면 경기 둔화 우려를 반영해요."
        case "nikkei":      return "일본 대표 주가지수예요. 엔화 흐름·일본 경기와 연동되며, 아시아 장 개장 시 국내 증시 방향에 선행 영향을 줘요."
        case "usdjpy":      return "달러 대비 엔화 환율이에요. 오를수록 엔 약세로, 일본 수출주에 유리하고 글로벌 위험 회피 심리와 역상관 경향이 있어요."
        case "vix":         return "S&P500 옵션에서 뽑은 미국 증시 30일 변동성 지수예요. 20 이상은 불안, 30 이상은 공포 구간으로 봐요."
        case "fear_greed":  return "CNN이 매일 산출하는 시장 심리 지수예요. 0에 가까울수록 공포, 100에 가까울수록 탐욕이에요."
        case "nqfut":       return "나스닥100 선물이에요. 미국 종가 이후 한국 새벽까지 움직여, 오늘 코스피·성장주 출발 방향을 가장 먼저 보여주는 야간 선행지표예요."
        case "esfut":       return "S&P500 선물이에요. 미국 장 마감 뒤 야간 흐름까지 반영해, 오늘 시장 전반의 출발 분위기를 미리 가늠해요."
        case "ymfut":       return "다우 선물이에요. 미국 블루칩의 야간 흐름을 보여주며, 종가 이후 분위기 변화를 읽는 데 써요."
        default:            return nil
        }
    }

    // 공포탐욕지수는 0–100 점수라 소수 1자리, 미10년물 금리는 "%p" 단위로 소수 2자리, 나머지는 천단위 구분 + 소수 2자리.
    // 주의: Swift String(format:)은 "%,.2f" 천단위 플래그 미지원 → NumberFormatter 사용.
    private func formatMacroValue(_ m: MacroIndicator) -> String {
        if m.key == "fear_greed" { return String(format: "%.1f", m.value) }
        if m.key == "tnx" { return String(format: "%.2f%%", m.value) }
        return macroValueFormatter.string(from: NSNumber(value: m.value)) ?? String(format: "%.2f", m.value)
    }

    // 공포탐욕 tag 색: 공포 계열 → 파랑, 탐욕 계열 → 빨강, 중립 → 회색.
    private func tagColor(_ tag: String) -> Color {
        if tag.contains("공포") { return .blue }
        if tag.contains("탐욕") { return .red }
        return .secondary
    }

    // MARK: - 섹션: 섹터 동향 (관심종목 관련 섹터만)

    // KRX 업종 레이블 → 우리 세부 섹터 레이블 매핑(백엔드 Sector.label과 일치해야 함).
    private let krxToCustom: [String: [String]] = [
        "전기전자": ["메모리반도체", "파운드리·장비", "AI반도체", "가전", "디스플레이", "전자부품"],
        "기계":     ["조선", "방산·항공우주"],
        "운수장비":  ["완성차", "자동차부품", "2차전지", "자율주행"],
        "전기가스업": ["전력기기", "신재생에너지"],
        "서비스업":  ["AI·클라우드", "IT서비스·SI", "인터넷플랫폼", "로봇·자동화"],
        "철강금속":  ["전력기기", "전선", "조선"],
    ]

    // 관심/보유 종목의 섹터 레이블 집합 (impactHoldings/Watch에서 추출)
    private var userSectorLabels: Set<String> {
        let all = (impactHoldings + impactWatch)
            .flatMap { $0.sectorLabel.components(separatedBy: "·") }
            .map { $0.trimmingCharacters(in: .whitespaces) }
        return Set(all).filter { !$0.isEmpty }
    }

    private func isRelevant(_ s: SectorIndex) -> Bool {
        let labels = userSectorLabels
        guard !labels.isEmpty else { return true }  // 영향 미로드 시 전체 표시
        return krxToCustom[s.label]?.contains(where: { labels.contains($0) }) ?? false
    }

    @ViewBuilder
    private var sectorSection: some View {
        let filtered = watchlistIsEmpty ? [] : sectorItems.filter { isRelevant($0) }
        Section {
            Button { withAnimation { sectorExpanded.toggle() } } label: {
                HStack {
                    Text("섹터 동향 (내 종목 관련)").font(.headline)
                    Spacer()
                    Image(systemName: sectorExpanded ? "chevron.up" : "chevron.down")
                        .font(.caption).foregroundColor(.secondary)
                }
            }
            .foregroundColor(.primary)
            if sectorExpanded {
                if watchlistIsEmpty {
                    Text("관심종목을 추가하면 내 종목 관련 섹터를 볼 수 있어요").font(.footnote).foregroundColor(.secondary)
                } else if sectorLoading {
                    HStack { ProgressView().scaleEffect(0.8); Text("확인 중…").font(.footnote).foregroundColor(.secondary) }
                } else if filtered.isEmpty {
                    Text("관련 섹터가 없어요").font(.footnote).foregroundColor(.secondary)
                } else {
                    ForEach(filtered, id: \.key) { sectorRow($0) }
                }
            }
        }
    }

    private func sectorRow(_ s: SectorIndex) -> some View {
        let flat = s.changeRate == 0
        let up = s.changeRate > 0
        let color: Color = flat ? .secondary : (up ? .red : .blue)
        let arrow = flat ? "–" : (up ? "▲" : "▼")
        return HStack {
            Text(s.label).font(.body)
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                Text(sectorValueFormatter.string(from: NSNumber(value: s.value)) ?? String(format: "%.2f", s.value))
                    .font(.body.weight(.semibold))
                Text("\(arrow) \(String(format: "%.2f", abs(s.changeRate)))%")
                    .font(.caption).foregroundColor(color)
            }
        }
        .padding(.vertical, 2)
    }

    // MARK: - 섹션: 섹터 자금 순환

    @ViewBuilder
    private var sectorRotationSection: some View {
        if rotationLoading || sectorRotation != nil {
            Section {
                let rotation = sectorRotation
                HStack {
                    Text("섹터 자금 순환").font(.headline)
                    Spacer()
                    if rotationLoading {
                        ProgressView().scaleEffect(0.8)
                    } else if let r = rotation {
                        if !r.inflow.isEmpty || !r.outflow.isEmpty {
                            let inflowText = r.inflow.isEmpty ? "" : "▲\(r.inflow.count)"
                            let outflowText = r.outflow.isEmpty ? "" : "▼\(r.outflow.count)"
                            Text([inflowText, outflowText].filter { !$0.isEmpty }.joined(separator: " "))
                                .font(.caption2).foregroundColor(.secondary)
                        }
                    }
                }
                if let r = rotation {
                    // 섹터별 5일/20일 수익률 + 순위변화(유입/이탈은 헤더 카운트 + 행별 ▲▼로 표현)
                    ForEach(0..<r.sectors.count, id: \.self) { i in
                        let s = r.sectors[i]
                        HStack(spacing: 0) {
                            Text(s.label)
                                .font(.caption2)
                                .frame(width: 80, alignment: .leading)
                                .lineLimit(1)
                            Spacer()
                            Group {
                                let ret5Color: Color = s.ret5 > 0 ? .red : s.ret5 < 0 ? Color(red: 0.2, green: 0.4, blue: 1.0) : .secondary
                                Text(String(format: "%+.1f%%", s.ret5))
                                    .foregroundColor(ret5Color)
                                Text(" / ")
                                    .foregroundColor(.secondary)
                                let ret20Color: Color = s.ret20 > 0 ? .red : s.ret20 < 0 ? Color(red: 0.2, green: 0.4, blue: 1.0) : .secondary
                                Text(String(format: "%+.1f%%", s.ret20))
                                    .foregroundColor(ret20Color)
                            }
                            .font(.caption2.monospacedDigit())
                            Spacer()
                            rankDeltaBadge(Int(s.rankDelta))
                        }
                        .padding(.vertical, 1)
                        // Divider 금지: List에서 Section 직속 Divider는 독립 행(최소 높이 ~44pt)이 되어
                        // 거대한 빈 블록으로 렌더된다(실기기 발견). 행 구분선은 List가 자동으로 그린다.
                    }
                    Text("5일 / 20일 수익률 · ▲▼ 단기 순위 변화")
                        .font(.caption2).foregroundColor(Color(.tertiaryLabel))
                }
            }
        }
    }

    private func rankDeltaBadge(_ delta: Int) -> some View {
        let text: String
        let color: Color
        if delta >= 2 {
            text = "▲\(delta)"; color = .red
        } else if delta <= -2 {
            text = "▼\(abs(delta))"; color = Color(red: 0.2, green: 0.4, blue: 1.0)
        } else {
            text = "━"; color = .secondary
        }
        return Text(text).font(.caption2.monospacedDigit()).foregroundColor(color).frame(width: 28)
    }

    // MARK: - 섹션: 섹터 분석 (Claude)

    @ViewBuilder
    private var sectorBriefingSection: some View {
        Section {
            if sectorBriefingLoading {
                VStack(alignment: .leading, spacing: 6) {
                    aiSectionTitle("섹터 분석")
                    HStack {
                        ProgressView().scaleEffect(0.8)
                        Text("AI가 분석 중…").font(.footnote).foregroundColor(.secondary)
                    }
                }
            } else if watchlistIsEmpty || sectorBriefingComment.isEmpty {
                VStack(alignment: .leading, spacing: 6) {
                    aiSectionTitle("섹터 분석")
                    Text(watchlistIsEmpty ? "관심종목을 추가하면 섹터 분석을 볼 수 있어요" : "장 시작 전이거나 섹터 데이터가 없어요")
                        .font(.footnote).foregroundColor(.secondary)
                }
            } else {
                VStack(alignment: .leading, spacing: 4) {
                    aiCommentToggle(
                        title: "섹터 분석",
                        expanded: sectorBriefingExpanded,
                        dateLabel: mdLabel(sectorBriefingDate),
                        generatedAt: sectorBriefingGeneratedAt,
                        weekendReuse: freshness.isSundayReuse,
                        isLoading: sectorBriefingLoading,
                        action: { withAnimation { sectorBriefingExpanded.toggle() } },
                        regenAction: {
                            let codes = Db.watchlist.all().map { $0.code }
                            Task { await buildSectorBriefing(codes: codes, force: true) }
                        }
                    )
                    if sectorBriefingExpanded { proseBlock(sectorBriefingComment) }
                }
                // 주목 종목 — 같은 섹션(카드) 안의 별도 List 행. VStack 밖이라야 NavigationLink 단일 발화.
                if sectorBriefingExpanded && !sectorSpotlight.isEmpty {
                    Text("오늘 주목 종목")
                        .font(.caption.weight(.semibold))
                        .foregroundColor(.secondary)
                    ForEach(sectorSpotlight, id: \.code) { stock in
                        let item = allItemsLoaded.first { $0.code == stock.code }
                        let quote = quoteMapLoaded[stock.code]
                        if let item {
                            NavigationLink {
                                StockDetailView(item: item, quote: quote, api: api)
                            } label: {
                                spotlightRow(stock, quote: quote)
                            }
                        } else {
                            spotlightRow(stock, quote: nil)
                        }
                    }
                }
            }
        }
    }

    private func spotlightRow(_ stock: SpotlightStock, quote: Quote?) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(stock.name).font(.body)
                Text(stock.sectorLabel).font(.caption2).foregroundColor(.secondary)
            }
            Spacer()
            if let q = quote {
                let chgColor: Color = q.changeRate > 0 ? .red : q.changeRate < 0 ? .blue : .secondary
                let symbol = q.changeRate > 0 ? "▲" : q.changeRate < 0 ? "▼" : "—"
                VStack(alignment: .trailing, spacing: 2) {
                    Text("\(q.price.formatted())원").font(.body.weight(.semibold))
                    Text("\(symbol) \(String(format: "%.2f", abs(q.changeRate)))%")
                        .font(.caption).foregroundColor(chgColor)
                }
            }
        }
        .padding(.vertical, 2)
    }

    // MARK: - 섹션: 실적 일정

    @ViewBuilder
    private var earningsSection: some View {
        Section {
            Button { withAnimation { earningsExpanded.toggle() } } label: {
                HStack {
                    Text("실적 일정 (D-90 이내)").font(.headline)
                    Spacer()
                    Image(systemName: earningsExpanded ? "chevron.up" : "chevron.down")
                        .font(.caption).foregroundColor(.secondary)
                }
            }
            .foregroundColor(.primary)
            if watchlistIsEmpty {
                Text("관심종목을 추가하면 실적 일정을 볼 수 있어요").font(.footnote).foregroundColor(.secondary)
            } else if earningsLoading {
                HStack { ProgressView().scaleEffect(0.8); Text("확인 중…").font(.footnote).foregroundColor(.secondary) }
            } else if earningsExpanded {
                if earningsItems.isEmpty {
                    Text("90일 이내 예정된 실적이 없어요").font(.footnote).foregroundColor(.secondary)
                } else {
                    ForEach(earningsItems, id: \.code) { earningsRow($0) }
                }
            }
        }
    }

    private func earningsRow(_ e: EarningsEntry) -> some View {
        let days = Int(e.daysUntil)  // Kotlin Int → Swift Int32 → Int
        return VStack(alignment: .leading, spacing: 4) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(e.corpName).font(.body)
                    Text(e.reportName).font(.caption2).foregroundColor(.secondary)
                }
                Spacer()
                Text(ddayLabel(days))
                    .font(.caption.weight(.semibold))
                    .padding(.horizontal, 8).padding(.vertical, 3)
                    .background(ddayColor(days).opacity(0.15))
                    .foregroundColor(ddayColor(days))
                    .clipShape(Capsule())
            }
            // F3 프리뷰: D-7 이내 임박 종목만 붙는다(run-rate·과거 반응 — 전부 계산치)
            if let p = earningsPreviews[e.code] { earningsPreviewLines(p) }
        }
        .padding(.vertical, 2)
    }

    @ViewBuilder
    private func earningsPreviewLines(_ p: EarningsPreview) -> some View {
        if let yoy = p.runRateYoYPct {
            Text("현재 속도(연환산) 유지 시 작년 연간 대비 \(String(format: "%+.1f", yoy.doubleValue))%")
                .font(.caption2).foregroundColor(yoy.doubleValue >= 0 ? .red : .blue)
        }
        if let r = p.pastReactions {
            Text("과거 \(r.n)번 발표: 익일 평균 \(String(format: "%+.2f", r.day1AvgPct))% · 5일 \(String(format: "%+.2f", r.day5AvgPct))% · 익일 상승 \(Int(r.day1WinRatePct))% (참고용)")
                .font(.caption2).foregroundColor(.secondary)
        }
    }

    private func ddayLabel(_ days: Int) -> String {
        switch days {
        case 0:         return "D-day"
        case 1...:      return "D-\(days)"
        default:        return "D+\(abs(days))"
        }
    }

    private func ddayColor(_ days: Int) -> Color {
        switch days {
        case ..<0:      return .red      // 기한 초과
        case 0..<14:    return .red
        case 14..<30:   return .orange
        default:        return .secondary
        }
    }

    // MARK: - 섹션: 거시 이벤트 캘린더

    @ViewBuilder
    private var eventCalendarSection: some View {
        Section {
            Button { withAnimation { eventsExpanded.toggle() } } label: {
                HStack {
                    Text("이벤트 캘린더 (30일)").font(.headline)
                    Spacer()
                    Image(systemName: eventsExpanded ? "chevron.up" : "chevron.down")
                        .font(.caption).foregroundColor(.secondary)
                }
            }
            .foregroundColor(.primary)
            if eventsLoading {
                HStack { ProgressView().scaleEffect(0.8); Text("이벤트 일정 수집 중…").font(.footnote).foregroundColor(.secondary) }
            } else if eventsExpanded {
                if eventItems.isEmpty {
                    Text("이번 달 주요 이벤트 일정이 없어요.")
                        .font(.footnote).foregroundColor(.secondary)
                } else {
                    ForEach(Array(eventItems.enumerated()), id: \.offset) { _, event in eventRow(event) }
                }
                VStack(alignment: .leading, spacing: 3) {
                    eventLegendRow("호재", color: .teal,      desc: "결과에 따라 주가 상승 기대")
                    eventLegendRow("주의", color: .orange,    desc: "결과에 따라 주가 크게 흔들릴 수 있음")
                    eventLegendRow("중립", color: .secondary, desc: "시장 방향과 무관")
                }
                .font(.caption2)
                .padding(.top, 4)
            }
        }
    }

    private func eventRow(_ e: MarketEvent) -> some View {
        let (accentColor, badgeLabel): (Color, String) = switch e.category {
            case "호재":  (.teal,      "호재")
            case "주의":  (.orange,    "주의")
            default:      (.secondary, "중립")
        }
        let isPast = isPastEvent(e.date)
        return HStack(alignment: .top, spacing: 10) {
            VStack(spacing: 2) {
                Text(eventDateLabel(e.date))
                    .font(.caption.weight(.semibold))
                    .foregroundColor(.secondary)
                    .frame(width: 44, alignment: .center)
                Text(isPast ? "종료" : badgeLabel)
                    .font(.caption2.weight(.semibold))
                    .padding(.horizontal, 5).padding(.vertical, 2)
                    .background((isPast ? Color.secondary : accentColor).opacity(0.15))
                    .foregroundColor(isPast ? .secondary : accentColor)
                    .clipShape(Capsule())
            }
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 4) {
                    Text(e.title).font(.body)
                    if e.confirmed {
                        Image(systemName: "checkmark.circle.fill")
                            .font(.caption2).foregroundColor(.teal)
                    }
                }
                Text(e.impact).font(.caption2).foregroundColor(.secondary)
            }
        }
        .padding(.vertical, 3)
        .opacity(isPast ? 0.5 : 1)
    }

    /// 이벤트 날짜가 오늘보다 과거인지(KST 기준). 막 지난 일정을 "종료"로 흐리게 표시하는 데 쓴다.
    private func isPastEvent(_ dateStr: String) -> Bool {
        let fmt = DateFormatter()
        fmt.dateFormat = "yyyy-MM-dd"
        fmt.timeZone = TimeZone(identifier: "Asia/Seoul")
        guard let d = fmt.date(from: dateStr) else { return false }
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "Asia/Seoul") ?? .current
        return cal.startOfDay(for: d) < cal.startOfDay(for: Date())
    }

    private func eventLegendRow(_ label: String, color: Color, desc: String) -> some View {
        HStack(spacing: 5) {
            Text(label)
                .font(.caption2.weight(.semibold))
                .padding(.horizontal, 5).padding(.vertical, 1)
                .background(color.opacity(0.15))
                .foregroundColor(color)
                .clipShape(Capsule())
            Text(desc).foregroundColor(.secondary)
        }
    }

    private func eventDateLabel(_ dateStr: String) -> String {
        let parts = dateStr.split(separator: "-")
        guard parts.count == 3 else { return dateStr }
        return "\(parts[1])/\(parts[2])"
    }

    // MARK: - 섹션: 매크로 영향 (내 종목)

    @ViewBuilder
    private var impactSection: some View {
        Section {
            Button { withAnimation { impactSectionExpanded.toggle() } } label: {
                HStack(spacing: 6) {
                    Text("내 종목 영향").font(.headline)
                    if analysisMode == .aggressive {
                        Text("⚔️ 공격적 모드")
                            .font(.caption2.weight(.semibold))
                            .padding(.horizontal, 6).padding(.vertical, 2)
                            .background(Color.orange.opacity(0.15))
                            .foregroundColor(.orange)
                            .clipShape(Capsule())
                    }
                    Spacer()
                    Image(systemName: impactSectionExpanded ? "chevron.up" : "chevron.down")
                        .font(.caption).foregroundColor(.secondary)
                }
            }
            .foregroundColor(.primary)
            if impactSectionExpanded {
                if impactLoading {
                    HStack { ProgressView().scaleEffect(0.8); Text("AI가 해석 중…").font(.footnote).foregroundColor(.secondary) }
                } else if watchlistIsEmpty {
                    Text("관심종목을 추가하면 내 종목 기준 영향 분석을 볼 수 있어요").font(.footnote).foregroundColor(.secondary)
                } else if impactComment.isEmpty && impactHoldings.isEmpty && impactWatch.isEmpty {
                    Text("불러오지 못했어요").font(.footnote).foregroundColor(.secondary)
                } else {
                    if !impactHoldings.isEmpty {
                        Text("보유 종목").font(.caption.weight(.semibold)).foregroundColor(.secondary)
                        ForEach(impactHoldings, id: \.code) { impactRow($0) }
                    }
                    if !impactWatch.isEmpty {
                        Button { withAnimation { impactWatchExpanded.toggle() } } label: {
                            HStack {
                                Text("관심 종목 \(impactWatch.count)개")
                                    .font(.caption.weight(.semibold)).foregroundColor(.secondary)
                                Spacer()
                                Image(systemName: impactWatchExpanded ? "chevron.up" : "chevron.down")
                                    .font(.caption2).foregroundColor(.secondary)
                            }
                        }
                        .foregroundColor(.primary)
                        if impactWatchExpanded {
                            ForEach(impactWatch, id: \.code) { impactRow($0) }
                        }
                    }
                    if !impactComment.isEmpty {
                        VStack(alignment: .leading, spacing: 4) {
                            aiCommentToggle(
                                title: "AI 코멘트",
                                compact: true,
                                expanded: impactExpanded,
                                dateLabel: mdLabel(impactDate),
                                generatedAt: impactGeneratedAt,
                                weekendReuse: freshness.isSundayReuse,
                                isLoading: impactLoading,
                                action: { withAnimation { impactExpanded.toggle() } },
                                regenAction: { Task { await buildImpact(allItems: allItemsLoaded, force: true) } }
                            )
                            if impactExpanded { proseBlock(impactComment) }
                        }
                    }
                }
            }
        }
    }

    // "AI 코멘트" 접기 토글 + 메타 행. 제목 행(접기/펼치기)과 재생성 버튼을
    // 서로 다른 줄에 두어, 제목을 탭할 때 재생성이 잘못 눌리지 않게 분리한다.
    // 섹션 제목 행 — ✨(AI 생성 표시) + 제목. 로딩/실패 상태에서도 동일하게 사용.
    // compact=true: 다른 섹션 하위에 들어가는 작은 라벨(.subheadline). false: 섹션 제목(.headline).
    private func aiSectionTitle(_ title: String, aggressive: Bool = false, compact: Bool = false) -> some View {
        HStack(spacing: 5) {
            Image(systemName: "sparkles").font(compact ? .caption2 : .caption).foregroundColor(.purple)
            Text(title).font(compact ? .subheadline.weight(.medium) : .headline)
            if aggressive {
                Text("⚔️ 공격적 모드")
                    .font(.caption2.weight(.semibold))
                    .padding(.horizontal, 6).padding(.vertical, 2)
                    .background(Color.orange.opacity(0.15))
                    .foregroundColor(.orange)
                    .clipShape(Capsule())
            }
        }
    }

    // MARK: - 데이터 신선도(배너 + 주말 재사용 판정)

    // KST 기준 데이터가 언제 것인지 판정. 장중=실시간, 그 외=직전 거래일 종가 기준.
    // 일요일은 백엔드가 토요일 분석을 재사용하므로(데이터 동일) AI 섹션에 안내 + 재생성 비활성.
    private struct Freshness {
        let isLive: Bool         // 장중 실시간 시세
        let isSundayReuse: Bool  // 일요일: 토요일 분석 재사용 안내 + 재생성 잠금
        let bannerText: String
    }

    private var freshness: Freshness { Self.computeFreshness() }

    private static func computeFreshness(_ now: Date = Date()) -> Freshness {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "Asia/Seoul")!
        let wd = cal.component(.weekday, from: now)        // 1=일 … 7=토
        let mins = cal.component(.hour, from: now) * 60 + cal.component(.minute, from: now)
        let isWeekend = (wd == 1 || wd == 7)
        let open = 540, close = 930                        // 09:00 ~ 15:30
        let isLive = !isWeekend && mins >= open && mins < close
        let afterClose = !isWeekend && mins >= close

        // 마지막 거래일: 장 마감 후 평일이면 오늘, 그 외엔 직전 평일(주말 건너뜀). 공휴일은 미반영.
        var lastTD = now
        if !afterClose {
            lastTD = cal.date(byAdding: .day, value: -1, to: now)!
            while cal.component(.weekday, from: lastTD) == 1 || cal.component(.weekday, from: lastTD) == 7 {
                lastTD = cal.date(byAdding: .day, value: -1, to: lastTD)!
            }
        }
        let banner: String
        if isLive {
            banner = "실시간 시세 · 수초 지연"
        } else {
            let f = DateFormatter()
            f.locale = Locale(identifier: "ko_KR"); f.timeZone = cal.timeZone; f.dateFormat = "M/d(E)"
            let suffix = isWeekend ? " · 주말 휴장" : (mins < open ? " · 개장 전" : "")
            banner = "\(f.string(from: lastTD)) 종가 기준\(suffix)"
        }
        return Freshness(isLive: isLive, isSundayReuse: (wd == 1), bannerText: banner)
    }

    // "YYYY-MM-DD" → "M/d(요일)" (AI 섹션 생성 기준일 표시용)
    private func mdLabel(_ ymd: String) -> String {
        let inF = DateFormatter(); inF.dateFormat = "yyyy-MM-dd"; inF.timeZone = TimeZone(identifier: "Asia/Seoul")
        guard let d = inF.date(from: ymd) else { return "" }
        let outF = DateFormatter()
        outF.locale = Locale(identifier: "ko_KR"); outF.timeZone = inF.timeZone; outF.dateFormat = "M/d(E)"
        return outF.string(from: d)
    }

    // 데이터 기준일 배너 (양 탭 공통). Picker 섹션의 footer로 붙여 빈 행 없이 picker 바로 아래 표시.
    private var dataFreshnessBanner: some View {
        let fr = freshness
        return HStack(spacing: 5) {
            Image(systemName: fr.isLive ? "dot.radiowaves.left.and.right" : "calendar")
                .font(.caption2)
            Text(fr.bannerText).font(.caption2)
        }
        .foregroundColor(.secondary)
    }

    private func aiCommentToggle(
        title: String,
        aggressive: Bool = false,
        compact: Bool = false,
        expanded: Bool,
        dateLabel: String = "",
        generatedAt: String,
        weekendReuse: Bool = false,
        isLoading: Bool,
        action: @escaping () -> Void,
        regenAction: @escaping () -> Void
    ) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            // 제목 행 — ✨ + 제목 + chevron. 행 전체 탭 = 접기/펼치기
            HStack(spacing: 5) {
                aiSectionTitle(title, aggressive: aggressive, compact: compact)
                Spacer()
                Image(systemName: expanded ? "chevron.up" : "chevron.down")
                    .font(compact ? .caption2 : .caption).foregroundColor(.secondary)
            }
            .foregroundColor(.primary)
            .contentShape(Rectangle())
            .onTapGesture(perform: action)

            // 메타 행 — 생성 기준일·시각 + 재생성(제목 행과 다른 줄이라 오탭 없음)
            HStack {
                if weekendReuse {
                    // 일요일: 백엔드가 토요일 분석을 재사용(데이터 동일) → 안내만, 재생성 잠금.
                    Text("주말 휴장이라 직전 영업일 기준 분석이에요")
                        .font(.caption2).foregroundColor(.secondary)
                    Spacer()
                } else {
                    if !generatedAt.isEmpty {
                        Text(dateLabel.isEmpty ? "오늘 \(generatedAt) 생성" : "\(dateLabel) \(generatedAt) 생성")
                            .font(.caption2).foregroundColor(.secondary)
                    }
                    Spacer()
                    if isLoading {
                        ProgressView().scaleEffect(0.6)
                    } else {
                        Button(action: regenAction) {
                            Label("재생성", systemImage: "arrow.clockwise")
                                .font(.caption2).foregroundColor(.purple)
                        }
                        // List row 안의 Button은 buttonStyle을 지정하지 않으면 row 전체가 탭 영역이 돼
                        // 본문(proseBlock)을 눌러도 재생성이 발동한다 → borderless로 버튼 자체로만 한정.
                        .buttonStyle(.borderless)
                    }
                }
            }
        }
    }

    // 빈 줄(\n\n) 기준 문단 분리 + 마크다운 렌더. 줄간격·문단간격을 넉넉히 주고
    // 왼쪽 보라 액센트 바로 'AI 글'임을 시각적으로 구분해 텍스트 벽처럼 보이지 않게 한다.
    private func proseBlock(_ text: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            RoundedRectangle(cornerRadius: 2)
                .fill(Color.purple.opacity(0.35))
                .frame(width: 3)
            VStack(alignment: .leading, spacing: 12) {
                ForEach(text.components(separatedBy: "\n\n"), id: \.self) { para in
                    let t = para.trimmingCharacters(in: .whitespacesAndNewlines)
                    if !t.isEmpty {
                        Text(markdown(t))
                            .font(.callout)
                            .lineSpacing(5)
                            .fixedSize(horizontal: false, vertical: true)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            }
        }
        .padding(.vertical, 2)
    }

    private func impactRow(_ s: StockImpact) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(s.name).font(.body)
                    Text(s.sectorLabel).font(.caption2).foregroundColor(.secondary)
                }
                Spacer()
                netBadge(s.net)
            }
            if s.signals.isEmpty {
                Text("영향 매핑 준비 중").font(.caption2).foregroundColor(.secondary)
            } else {
                ForEach(s.signals, id: \.indicator) { sig in
                    let dir = Int(sig.direction)
                    // 실측 미검증(INCONCLUSIVE) 신호는 방향색 대신 회색 + 꼬리표로 시각 강등(X3).
                    let supported = sig.grade == "SUPPORTED"
                    HStack(spacing: 4) {
                        Text("\(sig.indicator) \(signedRate(sig.changeRate))% → \(directionLabel(dir))")
                            .font(.caption2)
                            .foregroundColor(supported ? directionColor(dir) : .secondary)
                        if !supported {
                            Text("· 실측 미검증").font(.caption2).foregroundColor(Color(.tertiaryLabel))
                        }
                    }
                }
            }
        }
        .padding(.vertical, 2)
    }

    private func netBadge(_ net: String) -> some View {
        let color: Color = net == "우호적" ? .red : (net == "부담" ? .blue : .secondary)
        return Text(net)
            .font(.caption2.weight(.semibold))
            .padding(.horizontal, 8).padding(.vertical, 3)
            .background(color.opacity(0.15))
            .foregroundColor(color)
            .clipShape(Capsule())
    }

    private func directionLabel(_ d: Int) -> String {
        d > 0 ? "우호" : (d < 0 ? "부담" : "중립")
    }

    private func directionColor(_ d: Int) -> Color {
        d > 0 ? .red : (d < 0 ? .blue : .secondary)
    }

    private func signedRate(_ v: Double) -> String {
        (v >= 0 ? "+" : "") + String(format: "%.2f", v)
    }

    // Claude 코멘트의 **굵게**·줄바꿈을 살려서 렌더(실패 시 평문).
    // 취소선 제거 + **굵게** 직접 파싱. SwiftUI 마크다운 파서는 "**+2.4%**에"처럼
    // 굵은 구간 뒤에 한글이 바로 붙으면 CommonMark 경계 규칙 탓에 별표를 그대로 남기는
    // 버그가 있어, 한글 문장에선 쓸 수 없다. 그래서 굵게는 우리가 직접 적용한다.
    private func markdown(_ s: String) -> AttributedString {
        var text = s.replacingOccurrences(of: #"~~(.+?)~~"#, with: "$1", options: .regularExpression)
        text = text.replacingOccurrences(of: "~~", with: "")

        guard let regex = try? NSRegularExpression(pattern: #"\*\*(.+?)\*\*"#) else {
            return AttributedString(text.replacingOccurrences(of: "**", with: ""))
        }
        let ns = text as NSString
        var out = AttributedString()
        var cursor = 0
        for m in regex.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
            if m.range.location > cursor {
                let plain = ns.substring(with: NSRange(location: cursor, length: m.range.location - cursor))
                out += AttributedString(plain.replacingOccurrences(of: "**", with: ""))
            }
            var bold = AttributedString(ns.substring(with: m.range(at: 1)))
            bold.inlinePresentationIntent = .stronglyEmphasized
            out += bold
            cursor = m.range.location + m.range.length
        }
        if cursor < ns.length {
            out += AttributedString(ns.substring(from: cursor).replacingOccurrences(of: "**", with: ""))
        }
        return out
    }

    // MARK: - 섹션: 하이라이트

    @ViewBuilder
    private var highlightSection: some View {
        if topGainers.isEmpty && topLosers.isEmpty {
            // 변동 없으면 섹션 자체 숨김
        } else {
            if !topGainers.isEmpty {
                Section("상승 종목") {
                    ForEach(topGainers, id: \.item.code) { quoteLink($0) }
                }
            }
            if !topLosers.isEmpty {
                Section("하락 종목") {
                    ForEach(topLosers, id: \.item.code) { quoteLink($0) }
                }
            }
        }
    }

    private func quoteLink(_ row: QuoteRow) -> some View {
        NavigationLink {
            StockDetailView(item: row.item, quote: row.quote, api: api)
        } label: {
            let chgColor: Color = row.quote.changeRate > 0 ? .red : row.quote.changeRate < 0 ? .blue : .secondary
            let symbol = row.quote.changeRate > 0 ? "▲" : row.quote.changeRate < 0 ? "▼" : "—"
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(row.item.name).font(.body)
                    Text(row.item.code).font(.caption2).foregroundColor(.secondary)
                }
                Spacer()
                VStack(alignment: .trailing, spacing: 2) {
                    Text("\(row.quote.price.formatted())원").font(.body.weight(.semibold))
                    Text("\(symbol) \(String(format: "%.2f", abs(row.quote.changeRate)))%")
                        .font(.caption).foregroundColor(chgColor)
                }
            }
            .padding(.vertical, 2)
        }
    }

    // MARK: - 섹션: 보유현황 (요약 1줄 — 상세는 내 자산 탭)

    @ViewBuilder
    private var holdingsSection: some View {
        if !holdings.isEmpty {
            let invested  = holdings.reduce(0.0) { $0 + $1.invested }
            let evaluated = holdings.reduce(0.0) { $0 + $1.evaluated }
            let totalPnl  = evaluated - invested
            let totalRate = invested == 0 ? 0.0 : totalPnl / invested * 100
            let up        = totalPnl >= 0
            Section("보유현황") {
                HStack {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("\(holdings.count)개 종목").font(.caption).foregroundColor(.secondary)
                        Text("\(Int(evaluated).formatted())원")
                            .font(.body.weight(.semibold))
                    }
                    Spacer()
                    VStack(alignment: .trailing, spacing: 3) {
                        Text("\(up ? "+" : "")\(Int(totalPnl).formatted())원")
                            .font(.body.weight(.semibold))
                            .foregroundColor(up ? .red : .blue)
                        Text("\(up ? "+" : "")\(String(format: "%.2f", totalRate))%")
                            .font(.caption).foregroundColor(up ? .red : .blue)
                    }
                }
                .padding(.vertical, 2)
                Text("종목별 상세는 '내 자산' 탭에서 볼 수 있어요")
                    .font(.caption2).foregroundColor(.secondary)
            }
        }
    }

    private func holdingRow(_ row: HoldingRow) -> some View {
        let up = row.pnl >= 0
        return HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(row.item.name).font(.body)
                Text(row.item.code).font(.caption2).foregroundColor(.secondary)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                Text("\(up ? "+" : "")\(Int(row.pnl).formatted())원")
                    .font(.subheadline.weight(.semibold)).foregroundColor(up ? .red : .blue)
                Text("\(up ? "+" : "")\(String(format: "%.1f", row.pnlRate))%")
                    .font(.caption).foregroundColor(up ? .red : .blue)
            }
        }
        .padding(.vertical, 2)
    }

    // MARK: - 섹션: 외인·기관 동향

    @ViewBuilder
    private var supplySection: some View {
        Section {
            Button { withAnimation { supplyExpanded.toggle() } } label: {
                HStack {
                    Text("외인·기관 동향").font(.headline)
                    // 신호(3일 연속 순매수) 있는 종목 수 뱃지 — 접힌 상태에서도 클릭 유도
                    if !supplyLoading && !supplyRows.isEmpty {
                        Text("\(supplyRows.count)")
                            .font(.caption2.weight(.bold))
                            .padding(.horizontal, 6).padding(.vertical, 2)
                            .background(Color.orange.opacity(0.15))
                            .foregroundColor(.orange)
                            .clipShape(Capsule())
                    }
                    Spacer()
                    Image(systemName: supplyExpanded ? "chevron.up" : "chevron.down")
                        .font(.caption).foregroundColor(.secondary)
                }
            }
            .foregroundColor(.primary)
            if watchlistIsEmpty {
                Text("관심종목을 추가하면 외인·기관 동향을 볼 수 있어요").font(.footnote).foregroundColor(.secondary)
            } else if supplyLoading {
                HStack {
                    ProgressView().scaleEffect(0.8)
                    Text("확인 중…").font(.footnote).foregroundColor(.secondary)
                }
            } else if supplyExpanded {
                Text("3일 연속 순매수 · 전일 확정")
                    .font(.caption2).foregroundColor(.secondary)
                if supplyRows.isEmpty {
                    Text("해당하는 종목이 없어요").font(.footnote).foregroundColor(.secondary)
                } else {
                    ForEach(supplyRows, id: \.item.code) { row in
                        NavigationLink {
                            StockDetailView(item: row.item, quote: row.quote, api: api)
                        } label: {
                            supplyRow(row)
                        }
                    }
                }
            }
        }
    }

    private func supplyRow(_ row: SupplyRow) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(row.item.name).font(.body)
                Text(row.item.code).font(.caption2).foregroundColor(.secondary)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                ForEach(row.labels, id: \.self) { label in
                    Text(label)
                        .font(.caption2.weight(.semibold))
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(Color.orange.opacity(0.15))
                        .foregroundColor(.orange)
                        .clipShape(Capsule())
                }
            }
        }
        .padding(.vertical, 2)
    }

    // MARK: - 섹션: DART 최근 공시

    @ViewBuilder
    private var dartSection: some View {
        Section {
            Button { withAnimation { dartExpanded.toggle() } } label: {
                HStack {
                    Text("최근 공시 (7일)").font(.headline)
                    Spacer()
                    Image(systemName: dartExpanded ? "chevron.up" : "chevron.down")
                        .font(.caption).foregroundColor(.secondary)
                }
            }
            .foregroundColor(.primary)
            if watchlistIsEmpty {
                Text("관심종목을 추가하면 관심종목의 공시를 볼 수 있어요").font(.footnote).foregroundColor(.secondary)
            } else if dartLoading {
                HStack { ProgressView().scaleEffect(0.8); Text("확인 중…").font(.footnote).foregroundColor(.secondary) }
            } else if dartExpanded {
                if dartItems.isEmpty {
                    Text("최근 7일간 공시가 없어요").font(.footnote).foregroundColor(.secondary)
                } else {
                    ForEach(dartItems.indices, id: \.self) { i in
                        let item = dartItems[i]
                        if let url = URL(string: item.url) {
                            Link(destination: url) { dartRow(item) }.foregroundColor(.primary)
                        } else {
                            dartRow(item)
                        }
                    }
                }
            }
        }
    }

    private func dartRow(_ item: DartItem) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(item.reportName)
                .font(.subheadline)
                .lineLimit(2)
            HStack(spacing: 6) {
                Text(item.corpName).font(.caption2).foregroundColor(.secondary)
                Text(item.formattedDate).font(.caption2).foregroundColor(.secondary)
            }
        }
        .padding(.vertical, 3)
    }

    private var catalystBriefSection: some View {
        Section {
            Button { withAnimation { catalystBriefExpanded.toggle() } } label: {
                HStack {
                    Text("테마별 재료 동향").font(.headline)
                    Spacer()
                    if !catalystBriefSectors.isEmpty {
                        let notable = catalystBriefSectors.filter { $0.bias == "악재우위" || $0.bias == "호재우위" }
                        if !notable.isEmpty {
                            Text("\(notable.count)개 테마")
                                .font(.caption2)
                                .foregroundColor(.secondary)
                        }
                    }
                    Image(systemName: catalystBriefExpanded ? "chevron.up" : "chevron.down")
                        .font(.caption).foregroundColor(.secondary)
                }
            }
            .foregroundColor(.primary)
            if watchlistIsEmpty {
                Text("관심종목을 추가하면 테마별 재료 동향을 볼 수 있어요")
                    .font(.footnote).foregroundColor(.secondary)
            } else if catalystBriefLoading {
                HStack { ProgressView().scaleEffect(0.8); Text("확인 중…").font(.footnote).foregroundColor(.secondary) }
            } else if catalystBriefExpanded {
                if catalystBriefSectors.isEmpty {
                    Text("아직 재료 판정이 없어요. 종목 상세에서 뉴스·공시 영향 카드를 열면 채워집니다.")
                        .font(.footnote).foregroundColor(.secondary)
                } else {
                    // Divider 금지 — List Section 직속 Divider는 독립 행이 된다(섹터 자금 순환과 동일 버그).
                    ForEach(catalystBriefSectors.indices, id: \.self) { i in
                        catalystBriefRow(catalystBriefSectors[i])
                    }
                }
            }
        }
    }

    private func catalystBriefRow(_ item: SectorCatalystLine) -> some View {
        HStack(alignment: .top, spacing: 10) {
            // 섹터 + bias 뱃지
            VStack(alignment: .leading, spacing: 4) {
                Text(item.sector)
                    .font(.caption2)
                    .padding(.horizontal, 6).padding(.vertical, 2)
                    .background(Color(.secondarySystemFill))
                    .cornerRadius(4)
                let (bLabel, bColor) = biasBadge(item.bias)
                Text(bLabel)
                    .font(.caption2).bold()
                    .foregroundColor(bColor)
            }
            // 한 줄 요약
            Text(item.line)
                .font(.subheadline)
                .lineLimit(3)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .padding(.vertical, 3)
    }

    private func biasBadge(_ bias: String) -> (String, Color) {
        switch bias {
        case "호재우위": return ("▲ 호재", .red)
        case "악재우위": return ("▼ 악재", Color(red: 0.2, green: 0.4, blue: 1.0))
        default:        return ("━ 혼조", .orange)
        }
    }

    // MARK: - 로드

    private func load() async {
        loading = true
        supplyLoading = true
        dartLoading = true
        macroLoading = true
        moodLoading = true
        impactLoading = true
        earningsLoading = true
        eventsLoading = true
        sectorLoading = true
        sectorBriefingLoading = true
        catalystBriefLoading = true
        rotationLoading = true
        errorText = nil
        supplyRows = []
        dartItems = []
        macroItems = []
        moodComment = ""
        moodGeneratedAt = ""
        impactComment = ""
        impactHoldings = []
        impactWatch = []
        impactGeneratedAt = ""
        earningsItems = []
        eventItems = []
        sectorItems = []
        sectorBriefingComment = ""
        sectorSpotlight = []
        sectorBriefingGeneratedAt = ""
        catalystBriefSectors = []
        sectorRotation = nil

        let allItems = Db.watchlist.all()
        watchlistIsEmpty = allItems.isEmpty
        let codes = allItems.map { $0.code }

        // 시장 지표·분위기·섹터·실적일정·매크로 영향·섹터 브리핑은 quotes와 독립 병렬.
        async let calendarTask: Void        = buildCalendar()
        async let macroTask: Void           = buildMacro()
        async let moodTask: Void            = buildMarketMood()
        async let moodAccuracyTask: Void    = loadMoodAccuracy()
        async let sectorTask: Void          = buildSectors()
        async let earningsTask: Void        = buildEarnings(codes: codes)
        async let eventsTask: Void          = buildEvents()
        async let impactTask: Void          = buildImpact(allItems: allItems)
        async let sectorBriefingTask: Void  = buildSectorBriefing(codes: codes)
        async let catalystBriefTask: Void   = buildCatalystBrief(codes: codes)
        async let rotationTask: Void        = buildSectorRotation()

        let quotes: [Quote]
        do {
            quotes = try await api.getQuotes(codes: codes)
        } catch {
            errorText = "불러오기 실패: \(error.localizedDescription)\n(백엔드: cd backend && ./run.sh)"
            loading = false
            supplyLoading = false
            dartLoading = false
            _ = await (macroTask, moodTask, moodAccuracyTask, sectorTask, earningsTask, eventsTask, impactTask, sectorBriefingTask, catalystBriefTask, rotationTask)
            return
        }
        let quoteMap = Dictionary(uniqueKeysWithValues: quotes.map { ($0.code, $0) })

        // quotes 완료 → 하이라이트/보유현황 즉시 반영. allItems·quoteMap을 state에 저장(섹터 브리핑 spotlight에서 재사용).
        allItemsLoaded = allItems
        quoteMapLoaded = quoteMap
        buildHighlights(allItems: allItems, quoteMap: quoteMap)
        buildHoldings(allItems: allItems, quoteMap: quoteMap)
        lastUpdated = Date()
        loading = false

        // supply·dart·macro·impact·sectorBriefing은 섹션 내부 스피너 유지하며 병렬 진행
        async let supplyTask: Void = buildSupply(allItems: allItems, quoteMap: quoteMap)
        async let dartTask: Void   = buildDart(codes: codes, allItems: allItems)
        _ = await (supplyTask, dartTask, macroTask, moodTask, moodAccuracyTask, sectorTask, earningsTask, eventsTask, impactTask, sectorBriefingTask, catalystBriefTask, rotationTask)

        supplyLoading = false
        dartLoading = false
    }

    private func buildCatalystBrief(codes: [String]) async {
        defer { catalystBriefLoading = false }
        guard !codes.isEmpty else { return }
        guard let result = try? await api.getCatalystBrief(codes: codes) else { return }
        catalystBriefSectors = result.sectors
    }

    private func buildSectorRotation() async {
        defer { rotationLoading = false }
        sectorRotation = try? await api.getSectorRotation()
    }

    private func buildSectorBriefing(codes: [String], force: Bool = false) async {
        sectorBriefingLoading = true
        defer { sectorBriefingLoading = false }
        guard let result = try? await api.getSectorBriefing(codes: codes, refresh: force) else { return }
        sectorBriefingComment = result.comment
        sectorSpotlight = result.spotlight
        sectorBriefingGeneratedAt = result.generatedAt
        sectorBriefingDate = result.date
    }

    private func buildMacro() async {
        defer { macroLoading = false }
        guard let items = try? await api.getMacro() else { return }
        macroItems = items
    }

    private func buildCalendar() async {
        calendar = try? await api.getMarketCalendar()
    }

    // "2026-06-24" → "6/24(수)". 파싱 실패 시 원문 그대로(빈 문자열이면 nil).
    private func formatBizDay(_ ymd: String) -> String? {
        guard !ymd.isEmpty else { return nil }
        let inFmt = DateFormatter()
        inFmt.dateFormat = "yyyy-MM-dd"
        inFmt.timeZone = TimeZone(identifier: "Asia/Seoul")
        guard let d = inFmt.date(from: ymd) else { return ymd }
        let outFmt = DateFormatter()
        outFmt.locale = Locale(identifier: "ko_KR")
        outFmt.timeZone = TimeZone(identifier: "Asia/Seoul")
        outFmt.dateFormat = "M/d(E)"
        return outFmt.string(from: d)
    }

    private func buildMarketMood(force: Bool = false) async {
        moodLoading = true
        defer { moodLoading = false }
        guard let result = try? await api.getMarketMood(mode: analysisMode.rawValue, refresh: force) else { return }
        moodComment = result.comment
        moodGeneratedAt = result.generatedAt
        moodDate = result.date
    }

    private func loadMoodAccuracy() async {
        moodAccuracy = try? await api.getMoodAccuracy()
    }

    private func regenAllAI() async {
        let codes = allItemsLoaded.map { $0.code }
        async let moodTask: Void = buildMarketMood(force: true)
        async let impactTask: Void = buildImpact(allItems: allItemsLoaded, force: true)
        async let briefingTask: Void = buildSectorBriefing(codes: codes, force: true)
        _ = await (moodTask, impactTask, briefingTask)
    }

    private func buildSectors() async {
        defer { sectorLoading = false }
        guard let items = try? await api.getSectors() else { return }
        sectorItems = items
    }

    private func buildEarnings(codes: [String]) async {
        defer { earningsLoading = false }
        guard let items = try? await api.getEarnings(codes: codes) else { return }
        earningsItems = items
        // F3: D-7 이내 임박 종목만 프리뷰(run-rate YoY·과거 발표 반응) 병렬 로드
        let imminent = items.filter { (0...7).contains(Int($0.daysUntil)) }.map { $0.code }
        guard !imminent.isEmpty else { return }
        var found: [String: EarningsPreview] = [:]
        await withTaskGroup(of: (String, EarningsPreview?).self) { group in
            for code in imminent {
                group.addTask { (code, try? await api.getEarningsPreview(code: code)) }
            }
            for await (code, preview) in group {
                if let p = preview { found[code] = p }
            }
        }
        earningsPreviews = found
    }

    private func buildEvents() async {
        defer { eventsLoading = false }
        guard let items = try? await api.getEvents(days: 30) else { return }
        if items.isEmpty {
            // 캐시가 없으면 백그라운드 동기화 후 재조회
            _ = try? await api.syncEvents()
            guard let synced = try? await api.getEvents(days: 30) else { return }
            eventItems = synced
        } else {
            eventItems = items
        }
    }

    private func buildImpact(allItems: [WatchItem], force: Bool = false) async {
        impactLoading = true
        defer { impactLoading = false }
        // 평단·수량이 있으면 보유, 없으면 관심(미보유) — holdingsSection 판정과 동일 기준.
        let holdingItems = allItems.filter { $0.avgPrice != nil && $0.qty != nil }
        let holdings = holdingItems.map { $0.code }
        let watchlist = allItems.filter { $0.avgPrice == nil || $0.qty == nil }.map { $0.code }
        // 포지션 맵: code → (avgPrice, qty). 공격 모드에서 포트폴리오 스탠스 의견에 활용.
        var positions: [String: KotlinPair<KotlinDouble, KotlinLong>] = [:]
        for item in holdingItems {
            if let avg = item.avgPrice, let qty = item.qty {
                positions[item.code] = KotlinPair(first: avg, second: qty)
            }
        }
        guard let impact = try? await api.getMacroImpact(
            holdings: holdings,
            watchlist: watchlist,
            mode: analysisMode.rawValue,
            positions: positions,
            refresh: force
        ) else { return }
        impactComment = impact.comment
        impactHoldings = impact.holdings
        impactWatch = impact.watchlist
        impactGeneratedAt = impact.generatedAt
        impactDate = impact.date
    }

    private func buildHighlights(allItems: [WatchItem], quoteMap: [String: Quote]) {
        let rows = allItems.compactMap { item -> QuoteRow? in
            guard let q = quoteMap[item.code] else { return nil }
            return QuoteRow(item: item, quote: q)
        }
        let sorted = rows.sorted { $0.quote.changeRate > $1.quote.changeRate }
        topGainers = Array(sorted.prefix(2).filter { $0.quote.changeRate > 0 })
        topLosers  = Array(sorted.suffix(2).reversed().filter { $0.quote.changeRate < 0 })
    }

    private func buildHoldings(allItems: [WatchItem], quoteMap: [String: Quote]) {
        holdings = allItems.compactMap { item in
            guard let avgNum = item.avgPrice, let qtyNum = item.qty else { return nil }
            let avg   = avgNum.doubleValue
            let qty   = Double(qtyNum.int64Value)
            let q     = quoteMap[item.code]
            let price = q.map { Double($0.price) } ?? avg
            return HoldingRow(item: item, quote: q, avg: avg, qty: qty, price: price)
        }
    }

    private func buildDart(codes: [String], allItems: [WatchItem]) async {
        guard let disclosures = try? await api.getDartBatch(codes: codes, days: 7) else { return }
        dartItems = Array(disclosures.prefix(10).map { d in
            DartItem(corpName: d.corpName, reportName: d.reportName, date: d.date, url: d.url)
        })
    }

    private func buildSupply(allItems: [WatchItem], quoteMap: [String: Quote]) async {
        let codes = allItems.map { $0.code }
        guard let flowMap = try? await api.getInvestorBatch(codes: codes, days: 3) else { return }
        var result: [SupplyRow] = []
        for item in allItems {
            guard let flows = flowMap[item.code], flows.count >= 3 else { continue }
            var labels: [String] = []
            if flows[0].foreign > 0 && flows[1].foreign > 0 && flows[2].foreign > 0 {
                labels.append("외인 3일↑")
            }
            if flows[0].institution > 0 && flows[1].institution > 0 && flows[2].institution > 0 {
                labels.append("기관 3일↑")
            }
            if !labels.isEmpty {
                result.append(SupplyRow(item: item, quote: quoteMap[item.code], labels: labels))
            }
        }
        supplyRows = result.sorted { $0.item.name < $1.item.name }
    }
}

// MARK: - 포맷터

// 업종지수 값 표시용(천단위 구분 + 소수 2자리).
private let sectorValueFormatter: NumberFormatter = {
    let f = NumberFormatter()
    f.numberStyle = .decimal
    f.minimumFractionDigits = 2
    f.maximumFractionDigits = 2
    return f
}()

// 지수·환율 값 표시용(천단위 구분 + 소수 2자리). 매 호출 생성 비용을 피해 파일 레벨 1회 생성.
private let macroValueFormatter: NumberFormatter = {
    let f = NumberFormatter()
    f.numberStyle = .decimal
    f.minimumFractionDigits = 2
    f.maximumFractionDigits = 2
    return f
}()

// MARK: - 내부 모델

private struct QuoteRow {
    let item: WatchItem
    let quote: Quote
}

private struct HoldingRow {
    let item: WatchItem
    let quote: Quote?
    let avg: Double
    let qty: Double
    let price: Double

    var invested:  Double { avg * qty }
    var evaluated: Double { price * qty }
    var pnl:       Double { (price - avg) * qty }
    var pnlRate:   Double { avg == 0 ? 0 : (price - avg) / avg * 100 }
}

private struct SupplyRow {
    let item: WatchItem
    let quote: Quote?
    let labels: [String]
}

private struct DartItem {
    let corpName: String
    let reportName: String
    let date: String    // YYYYMMDD
    let url: String

    // "20250601" → "25.06.01"
    var formattedDate: String {
        guard date.count == 8 else { return date }
        let y = date.dropFirst(2).prefix(2)
        let m = date.dropFirst(4).prefix(2)
        let d = date.dropFirst(6).prefix(2)
        return "\(y).\(m).\(d)"
    }
}
