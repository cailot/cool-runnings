package hyung.jin.seo.coolrunnings.dto;

import lombok.Data;
import java.util.Map;

/**
 * 공용 DTO: 1차 통계 분석 피처
 * StatisticalAnalysisService에서 생성하는 설명 가능한 지표들
 */
@Data
public class NumberStat {
    private int number;                          // 번호 (1-44)
    
    // 출현 빈도 관련
    private double hitRate;                     // 출현률 (0.0 ~ 1.0)
    private long totalAppearances;               // 전체 출현 횟수
    private double overallFrequency;            // 전체 출현 빈도
    private double recentFrequency;             // 최근 k회 출현 빈도
    
    // 미출현 관련
    private int missStreak;                     // 현재 연속 미출현 횟수
    private int maxMissStreak;                  // 최대 연속 미출현 횟수
    private int averageAbsenceInterval;         // 평균 미출현 간격
    
    // 이동평균 관련
    private double recentKMovingAvg;            // 최근 k회 이동평균 (기본 k=20)
    private Map<Integer, Double> movingAverages; // k값별 이동평균
    
    // 패턴 관련
    private String oddEvenPattern;              // 홀짝 패턴 ("ODD" 또는 "EVEN")
    private String rangeBucket;                 // 구간 버킷 ("1-10", "11-20", "21-30", "31-40", "41-44")
    
    // 추세 관련
    private double trend;                       // 추세 (양수: 상승, 음수: 하락)
    
    // 종합 점수 (랭킹용)
    private double compositeScore;               // 종합 점수 (0.0 ~ 1.0)
}

