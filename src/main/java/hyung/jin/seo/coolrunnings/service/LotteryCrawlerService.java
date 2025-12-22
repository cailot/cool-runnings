package hyung.jin.seo.coolrunnings.service;

import hyung.jin.seo.coolrunnings.model.LotteryResult;
import hyung.jin.seo.coolrunnings.repository.LotteryResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Set for Life 복권 결과를 웹사이트에서 크롤링하는 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LotteryCrawlerService {

    private final LotteryResultRepository lotteryResultRepository;

    @Value("${lottery.crawler.url}")
    private String crawlUrl;

    private static final int CONNECTION_TIMEOUT = 10000; // 10초

    /**
     * 최신 회차 정보를 확인하고 업데이트가 필요하면 크롤링하여 저장
     * 
     * @return 새로 저장된 레코드 수
     */
    @Transactional
    public int checkAndUpdateLatestDraws() {
        log.info("최신 회차 확인 및 업데이트 시작");

        try {
            // DB에서 최신 회차 확인
            Optional<LotteryResult> latestInDb = lotteryResultRepository.findFirstByOrderByDrawDesc();
            Integer latestDrawInDb = latestInDb.map(LotteryResult::getDraw).orElse(0);
            
            log.info("DB의 최신 회차: {}", latestDrawInDb);

            // 웹사이트에서 최신 회차 정보 크롤링
            List<LotteryResult> crawledResults = crawlLatestResults();
            
            if (crawledResults.isEmpty()) {
                log.warn("크롤링된 결과가 없습니다. 웹사이트 구조가 변경되었거나 파싱 로직을 조정해야 할 수 있습니다.");
                log.warn("웹사이트가 JavaScript로 동적 로드되는 경우 Jsoup만으로는 데이터를 가져올 수 없을 수 있습니다.");
                log.warn("웹사이트의 API 엔드포인트를 찾거나 Selenium 같은 브라우저 자동화 도구를 사용해야 할 수 있습니다.");
                // 크롤링 실패는 예외로 처리하지 않고 0을 반환 (이미 최신 데이터일 수 있음)
                return 0;
            }

            // 크롤링된 결과 중 최신 회차 확인
            Integer latestDrawOnWeb = crawledResults.stream()
                    .map(LotteryResult::getDraw)
                    .max(Integer::compareTo)
                    .orElse(0);

            log.info("웹사이트의 최신 회차: {}", latestDrawOnWeb);

            // 업데이트가 필요한지 확인
            if (latestDrawOnWeb <= latestDrawInDb) {
                log.info("이미 최신 데이터입니다. 업데이트 불필요.");
                return 0;
            }

            // 새로 추가할 회차들만 필터링하여 저장
            int savedCount = 0;
            for (LotteryResult result : crawledResults) {
                if (result.getDraw() > latestDrawInDb) {
                    // 중복 확인
                    Optional<LotteryResult> existing = lotteryResultRepository.findByDraw(result.getDraw());
                    if (existing.isEmpty()) {
                        lotteryResultRepository.save(result);
                        savedCount++;
                        log.info("새 회차 저장: Draw {}, Date {}", result.getDraw(), result.getDrawDate());
                    } else {
                        log.debug("이미 존재하는 회차: Draw {}", result.getDraw());
                    }
                }
            }

            log.info("크롤링 완료: {}개 새 회차 저장", savedCount);
            return savedCount;

        } catch (Exception e) {
            log.error("크롤링 중 오류 발생: {}", e.getMessage(), e);
            throw new RuntimeException("크롤링 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 웹사이트에서 최신 회차 결과를 크롤링
     * lottolyzer.com은 HTML 테이블로 데이터를 제공하므로 HTML 파싱만 사용
     * 
     * @return 크롤링된 LotteryResult 리스트
     */
    private List<LotteryResult> crawlLatestResults() {
        // lottolyzer.com은 HTML 테이블로 데이터를 제공하므로 HTML 파싱만 사용
        return crawlFromHtml();
    }

    /**
     * HTML을 통해 복권 결과 크롤링
     * 
     * @return 크롤링된 LotteryResult 리스트
     */
    private List<LotteryResult> crawlFromHtml() {
        List<LotteryResult> results = new ArrayList<>();

        try {
            log.info("HTML 크롤링 시작: {}", crawlUrl);

            // User-Agent 설정하여 봇 차단 방지
            Document doc = Jsoup.connect(crawlUrl)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                    .timeout(CONNECTION_TIMEOUT)
                    .get();

            log.debug("웹사이트 HTML 길이: {} bytes", doc.html().length());
            log.debug("웹사이트 제목: {}", doc.title());
            
            // lottolyzer.com은 테이블 구조로 데이터 제공
            results = parseResults(doc);

            if (results.isEmpty()) {
                log.warn("HTML 파싱으로도 결과를 찾을 수 없습니다.");
            } else {
                log.info("HTML 크롤링 완료: {}개 회차 발견", results.size());
            }

        } catch (Exception e) {
            log.error("HTML 크롤링 실패: {}", e.getMessage(), e);
        }

        return results;
    }

    /**
     * HTML 문서에서 복권 결과 파싱
     * lottolyzer.com 사이트의 테이블 구조를 파싱
     * 
     * @param doc Jsoup Document
     * @return 파싱된 LotteryResult 리스트
     */
    private List<LotteryResult> parseResults(Document doc) {
        List<LotteryResult> results = new ArrayList<>();

        try {
            // lottolyzer.com 사이트는 테이블 형태로 데이터 제공
            // 테이블 행 찾기
            Elements tableRows = doc.select("table tbody tr, table tr");
            
            if (tableRows.isEmpty()) {
                // 다른 테이블 선택자 시도
                tableRows = doc.select("tr[data-draw], .history-table tr, .result-table tr");
            }

            log.debug("발견된 테이블 행 수: {}", tableRows.size());

            // 각 테이블 행 파싱
            for (Element row : tableRows) {
                try {
                    // 헤더 행 건너뛰기
                    Elements headerCells = row.select("th");
                    if (!headerCells.isEmpty()) {
                        continue;
                    }

                    Elements cells = row.select("td");
                    if (cells.size() < 3) {
                        continue; // 최소한의 데이터가 없으면 건너뛰기
                    }

                    // 테이블 구조: Draw | Date | Winning No. | Bonus | From Last | Sum | Average | Low/High | Odd/Even | 1-10 | 11-20 | 21-30 | 31-40 | 41-50
                    // Draw 번호 (첫 번째 셀)
                    String drawStr = cells.get(0).text().trim();
                    Integer draw = parseInteger(drawStr.replace("Draw", "").trim());
                    if (draw == null) {
                        continue;
                    }

                    // 날짜 (두 번째 셀)
                    String dateStr = cells.get(1).text().trim();
                    LocalDate drawDate = parseDate(dateStr);
                    if (drawDate == null) {
                        log.warn("날짜 파싱 실패: Draw {}, Date {}", draw, dateStr);
                        continue;
                    }

                    // 당첨 번호 (세 번째 셀) - 쉼표로 구분
                    String winningNumbersStr = cells.get(2).text().trim();
                    List<Integer> winningNumbers = parseNumberList(winningNumbersStr);
                    if (winningNumbers.size() < 7) {
                        log.warn("당첨 번호 부족: Draw {}, Numbers: {}", draw, winningNumbersStr);
                        continue;
                    }

                    // 보너스 번호 (네 번째 셀) - 쉼표로 구분
                    List<Integer> bonusNumbers = new ArrayList<>();
                    if (cells.size() > 3) {
                        String bonusStr = cells.get(3).text().trim();
                        bonusNumbers = parseNumberList(bonusStr);
                    }

                    // From Last (다섯 번째 셀)
                    String fromLast = null;
                    if (cells.size() > 4) {
                        fromLast = cells.get(4).text().trim();
                        if (fromLast.isEmpty() || fromLast.equals("-")) {
                            fromLast = null;
                        }
                    }

                    // 통계 정보 파싱 (선택적)
                    Integer lowCount = null;
                    Integer highCount = null;
                    Integer oddCount = null;
                    Integer evenCount = null;
                    Integer range1To10 = null;
                    Integer range11To20 = null;
                    Integer range21To30 = null;
                    Integer range31To40 = null;
                    Integer range41To50 = null;

                    if (cells.size() > 7) {
                        // Low/High (8번째 셀) - "3 / 4" 형식
                        String lowHighStr = cells.get(7).text().trim();
                        if (!lowHighStr.isEmpty() && lowHighStr.contains("/")) {
                            String[] parts = lowHighStr.split("/");
                            if (parts.length == 2) {
                                lowCount = parseInteger(parts[0].trim());
                                highCount = parseInteger(parts[1].trim());
                            }
                        }

                        // Odd/Even (9번째 셀) - "3 / 4" 형식
                        if (cells.size() > 8) {
                            String oddEvenStr = cells.get(8).text().trim();
                            if (!oddEvenStr.isEmpty() && oddEvenStr.contains("/")) {
                                String[] parts = oddEvenStr.split("/");
                                if (parts.length == 2) {
                                    oddCount = parseInteger(parts[0].trim());
                                    evenCount = parseInteger(parts[1].trim());
                                }
                            }
                        }

                        // 범위별 개수 (10-14번째 셀)
                        if (cells.size() > 9) range1To10 = parseInteger(cells.get(9).text().trim());
                        if (cells.size() > 10) range11To20 = parseInteger(cells.get(10).text().trim());
                        if (cells.size() > 11) range21To30 = parseInteger(cells.get(11).text().trim());
                        if (cells.size() > 12) range31To40 = parseInteger(cells.get(12).text().trim());
                        if (cells.size() > 13) range41To50 = parseInteger(cells.get(13).text().trim());
                    }

                    // LotteryResult 생성
                    LotteryResult.LotteryResultBuilder builder = LotteryResult.builder()
                            .draw(draw)
                            .drawDate(drawDate)
                            .winningNumber1(winningNumbers.get(0))
                            .winningNumber2(winningNumbers.get(1))
                            .winningNumber3(winningNumbers.get(2))
                            .winningNumber4(winningNumbers.get(3))
                            .winningNumber5(winningNumbers.get(4))
                            .winningNumber6(winningNumbers.get(5))
                            .winningNumber7(winningNumbers.get(6))
                            .fromLast(fromLast)
                            .lowCount(lowCount)
                            .highCount(highCount)
                            .oddCount(oddCount)
                            .evenCount(evenCount)
                            .range1To10(range1To10)
                            .range11To20(range11To20)
                            .range21To30(range21To30)
                            .range31To40(range31To40)
                            .range41To50(range41To50);

                    if (bonusNumbers.size() >= 1) {
                        builder.bonusNumber1(bonusNumbers.get(0));
                    }
                    if (bonusNumbers.size() >= 2) {
                        builder.bonusNumber2(bonusNumbers.get(1));
                    }

                    LotteryResult result = builder.build();
                    results.add(result);
                    log.debug("테이블에서 Draw {} 파싱 성공", draw);

                } catch (Exception e) {
                    log.warn("테이블 행 파싱 실패: {}", e.getMessage());
                }
            }

            if (results.isEmpty()) {
                log.warn("테이블에서 결과를 찾을 수 없습니다.");
            }

        } catch (Exception e) {
            log.error("결과 파싱 중 오류: {}", e.getMessage(), e);
        }

        return results;
    }

    /**
     * 숫자 리스트 파싱 (쉼표로 구분된 문자열)
     */
    private List<Integer> parseNumberList(String numbersStr) {
        List<Integer> numbers = new ArrayList<>();
        if (numbersStr == null || numbersStr.trim().isEmpty()) {
            return numbers;
        }

        String[] parts = numbersStr.split(",");
        for (String part : parts) {
            try {
                Integer num = Integer.parseInt(part.trim());
                if (num > 0) {
                    numbers.add(num);
                }
            } catch (NumberFormatException e) {
                // 무시
            }
        }
        return numbers;
    }

    /**
     * 날짜 파싱 (여러 형식 지원)
     */
    private LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.trim().isEmpty()) {
            return null;
        }

        String[] dateFormats = {
            "yyyy-MM-dd",
            "dd/MM/yyyy",
            "dd-MM-yyyy",
            "yyyy/MM/dd",
            "MM/dd/yyyy"
        };

        for (String format : dateFormats) {
            try {
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern(format);
                return LocalDate.parse(dateStr.trim(), formatter);
            } catch (Exception e) {
                // 다음 형식 시도
            }
        }

        return null;
    }

    /**
     * 정수 파싱 헬퍼 메서드
     */
    private Integer parseInteger(String str) {
        if (str == null || str.trim().isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(str.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
