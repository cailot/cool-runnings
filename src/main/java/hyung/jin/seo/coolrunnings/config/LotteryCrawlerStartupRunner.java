package hyung.jin.seo.coolrunnings.config;

import hyung.jin.seo.coolrunnings.service.EmailService;
import hyung.jin.seo.coolrunnings.service.LotteryCrawlerService;
import hyung.jin.seo.coolrunnings.service.NumberGuessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 애플리케이션 시작 시 자동으로 크롤링과 번호 예측을 실행하는 Runner
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LotteryCrawlerStartupRunner implements ApplicationRunner {

    @Autowired(required = false)
    private LotteryCrawlerService lotteryCrawlerService;
    
    private final NumberGuessService numberGuessService;
    private final EmailService emailService;
    private final ApplicationContext applicationContext;

    @Value("${lottery.crawler.enabled:true}")
    private boolean crawlerEnabled;
    
    @Value("${lottery.auto.shutdown:true}")
    private boolean autoShutdown;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!crawlerEnabled) {
            log.info("크롤링이 비활성화되어 있습니다. (lottery.crawler.enabled=false)");
        } else if (lotteryCrawlerService == null) {
            log.warn("크롤링이 활성화되어 있지만 LotteryCrawlerService를 사용할 수 없습니다.");
        } else {
            log.info("애플리케이션 시작 시 자동 크롤링 실행");
            
            try {
                int savedCount = lotteryCrawlerService.checkAndUpdateLatestDraws();
                log.info("자동 크롤링 완료: {}개 새 회차 저장", savedCount);
            } catch (Exception e) {
                log.error("자동 크롤링 실패: {}", e.getMessage(), e);
                // 시작 시 크롤링 실패는 애플리케이션 시작을 막지 않음
            }
        }

        // 크롤링 후 번호 예측 실행
        log.info("크롤링 완료 후 번호 예측 분석 시작");
        try {
            // 상위 7개 번호 분석 (히스토리 패턴 필터링 적용)
            List<NumberGuessService.NumberProbability> top7 = numberGuessService.getTop7NumbersWithPatternFilteringAndProbability();
            
            // 하위 7개 번호 분석 (히스토리 패턴 필터링 적용)
            List<NumberGuessService.NumberProbability> bottom7 = numberGuessService.getBottom7NumbersWithPatternFilteringAndProbability();
            
            // 중간 7개 번호 분석 (High7 + Low7 혼합)
            List<NumberGuessService.NumberProbability> mid7 = numberGuessService.getMid7NumbersWithProbability();
            
            log.info("번호 예측 분석 완료");
            
            // 모든 번호의 확률 출력
            try {
                numberGuessService.printAllNumberProbabilities();
            } catch (Exception e) {
                log.error("번호 확률 출력 실패: {}", e.getMessage(), e);
                // 확률 출력 실패는 애플리케이션 시작을 막지 않음
            }
            
            // 39% 초과 ~ 42% 미만 확률 범위 번호 출력
            try {
                numberGuessService.getMidNumbersInRange();
            } catch (Exception e) {
                log.error("39%~42% 확률 범위 번호 출력 실패: {}", e.getMessage(), e);
                // 출력 실패는 애플리케이션 시작을 막지 않음
            }
            
            // 10회 반복 예측 분석 (최소 5회 이상 나온 번호들로 최종 결과 구성)
            try {
                numberGuessService.predictWithMultipleRuns();
            } catch (Exception e) {
                log.error("10회 반복 예측 분석 실패: {}", e.getMessage(), e);
                // 분석 실패는 애플리케이션 시작을 막지 않음
            }
            
            // 이메일로 결과 전송
            try {
                emailService.sendNumberPredictionResults(top7, bottom7, mid7);
                log.info("번호 예측 결과 이메일 전송 완료");
            } catch (Exception e) {
                log.error("이메일 전송 실패: {}", e.getMessage(), e);
                // 이메일 전송 실패는 애플리케이션 시작을 막지 않음
            }
            
        } catch (Exception e) {
            log.error("번호 예측 분석 실패: {}", e.getMessage(), e);
            // 번호 예측 실패는 애플리케이션 시작을 막지 않음
        }
        
        // 모든 작업 완료 후 자동 종료
        if (autoShutdown) {
            log.info("모든 작업이 완료되었습니다. 애플리케이션을 종료합니다.");
            SpringApplication.exit(applicationContext, () -> 0);
        } else {
            log.info("모든 작업이 완료되었습니다. 서버는 계속 실행됩니다.");
        }
    }
}
