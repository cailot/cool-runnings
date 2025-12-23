package hyung.jin.seo.coolrunnings.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * 백테스트 메트릭 (고정 유스케이스)
 * "모델이 추천한 상위 10개 번호 세트를 매 회차 샀다고 가정했을 때, 최근 1500회에서 평균 맞춘 개수"
 */
@Data
public class BacktestMetrics {
    private int testPeriod;                    // 테스트 기간 (회차 수, 기본값: 1500)
    private int topK;                          // 상위 K개 번호 (기본값: 10)
    private double averageMatchCount;           // 평균 맞춘 개수
    private double hitRate;                    // Hit rate (최소 1개 이상 맞춘 비율)
    private List<Integer> matchCountHistory;   // 각 회차별 맞춘 개수 히스토리
    private Map<Integer, Integer> matchCountDistribution; // 맞춘 개수별 분포
    private String summary;                    // 요약 정보
}

