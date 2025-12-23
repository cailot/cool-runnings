package hyung.jin.seo.coolrunnings.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * 공용 DTO: 3차 검증 결과
 * ValidationService에서 생성하는 검증 메트릭
 */
@Data
public class ValidationMetrics {
    // 기본 검증 통계
    private int totalValidations;                       // 총 검증 횟수
    private double averageAccuracy;                     // 평균 정확도
    private double averageMatchCount;                   // 평균 맞춘 개수
    private Map<Integer, Integer> matchCountDistribution; // 맞춘 개수별 분포
    
    // 전략별 성능 비교
    private double topKStrategyHitRate;        // 상위 K개 번호 전략 hit rate
    private double randomStrategyHitRate;       // 랜덤 전략 hit rate
    private double frequencyStrategyHitRate;    // 단순 빈도 전략 hit rate
    private double upliftVsRandom;              // 랜덤 전략 대비 uplift
    private double upliftVsFrequency;           // 빈도 전략 대비 uplift
    private int topK;                          // 사용된 K 값
    
    // 리트레이닝 경고
    private boolean retrainingNeeded;           // 리트레이닝 필요 여부
    private String warningMessage;              // 경고 메시지
    private double currentPerformance;          // 현재 성능
    private double thresholdPerformance;        // 임계 성능
    private List<String> recommendations;       // 권장사항 리스트
    
    // 백테스트 메트릭 (고정 유스케이스)
    private BacktestMetrics backtestMetrics;   // 백테스트 메트릭
}


