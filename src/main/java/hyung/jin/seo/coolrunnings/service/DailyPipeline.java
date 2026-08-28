package hyung.jin.seo.coolrunnings.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * One-shot daily batch: crawl → predict → email.
 */
@Slf4j
@RequiredArgsConstructor
public class DailyPipeline {

    private final LotteryCrawlerService lotteryCrawlerService;
    private final NumberGuessService numberGuessService;
    private final EmailService emailService;
    private final boolean crawlerEnabled;

    public void runOnce() {
        if (!crawlerEnabled) {
            log.info("크롤링이 비활성화되어 있습니다. (lottery.crawler.enabled=false)");
        } else if (lotteryCrawlerService == null) {
            throw new IllegalStateException("크롤링이 활성화되어 있지만 LotteryCrawlerService를 사용할 수 없습니다.");
        } else {
            log.info("자동 크롤링 실행");
            try {
                int savedCount = lotteryCrawlerService.checkAndUpdateLatestDraws();
                log.info("자동 크롤링 완료: {}개 새 회차 저장", savedCount);
            } catch (Exception e) {
                throw new IllegalStateException("자동 크롤링 실패: " + e.getMessage(), e);
            }
        }

        log.info("번호 예측 분석 시작");
        long predictionStartTime = System.currentTimeMillis();
        long totalProcessStartTime = predictionStartTime;

        long cachePreloadStartTime = System.currentTimeMillis();
        log.info("딥러닝 예측 캐시 사전 생성 중...");
        try {
            numberGuessService.preloadDeepLearningCache();
            log.info("딥러닝 예측 캐시 사전 생성 완료 (소요 시간: {})",
                    formatElapsedTime(System.currentTimeMillis() - cachePreloadStartTime));
        } catch (Exception e) {
            log.warn("딥러닝 예측 캐시 사전 생성 실패 (자동으로 생성됩니다): {}", e.getMessage());
        }

        // 단일 패스 결과는 참고용 로그. 이메일은 아래 합의(multiple-runs) 결과를 사용한다.
        try {
            List<NumberGuessService.NumberProbability> top7 =
                    numberGuessService.getTop7NumbersWithPatternFilteringAndProbability();
            List<NumberGuessService.NumberProbability> bottom7 =
                    numberGuessService.getBottom7NumbersWithPatternFilteringAndProbability();
            List<NumberGuessService.NumberProbability> mid7 =
                    numberGuessService.getMid7NumbersWithProbability();
            log.info("단일 패스 참고용 top7={}, mid7={}, bottom7={}", top7, mid7, bottom7);
        } catch (Exception e) {
            log.warn("단일 패스 참고 예측 실패 (합의 예측은 계속 진행): {}", e.getMessage());
        }

        log.info("번호 예측 분석 완료 (소요 시간: {})",
                formatElapsedTime(System.currentTimeMillis() - predictionStartTime));

        try {
            numberGuessService.printAllNumberProbabilities();
        } catch (Exception e) {
            log.warn("번호 확률 출력 실패: {}", e.getMessage());
        }

        try {
            numberGuessService.getMidNumbersInRange();
        } catch (Exception e) {
            log.warn("39%~42% 확률 범위 번호 출력 실패: {}", e.getMessage());
        }

        NumberGuessService.MultipleRunsPredictionResult consensus;
        try {
            log.info("반복 예측 시작 전 경과 시간: {}",
                    formatElapsedTime(System.currentTimeMillis() - totalProcessStartTime));
            consensus = numberGuessService.predictWithMultipleRuns();
        } catch (Exception e) {
            throw new IllegalStateException("1500회 반복 예측 분석 실패: " + e.getMessage(), e);
        }

        try {
            // 최종 메일 번호 = 1500회 합의 결과 (단일 패스 top7이 아님)
            emailService.sendMultipleRunsPredictionResults(
                    consensus.getTop7(),
                    consensus.getMidRange7(),
                    consensus.getTop7Frequencies(),
                    consensus.getMidRange7Frequencies(),
                    consensus.getElapsedTimeMillis(),
                    consensus.getRunsCount());
            log.info("{}회 합의 예측 결과 이메일 전송 완료", consensus.getRunsCount());
        } catch (Exception e) {
            throw new IllegalStateException("이메일 전송 실패: " + e.getMessage(), e);
        }

        log.info("모든 작업이 완료되었습니다. 애플리케이션을 종료합니다.");
    }

    private String formatElapsedTime(long elapsedTimeMillis) {
        long hours = elapsedTimeMillis / (1000 * 60 * 60);
        long minutes = (elapsedTimeMillis % (1000 * 60 * 60)) / (1000 * 60);
        long seconds = (elapsedTimeMillis % (1000 * 60)) / 1000;
        if (hours > 0) {
            return String.format("%d시간 %d분 %d초", hours, minutes, seconds);
        } else if (minutes > 0) {
            return String.format("%d분 %d초", minutes, seconds);
        }
        return String.format("%d초", seconds);
    }
}
