package hyung.jin.seo.coolrunnings.dto;

import lombok.Data;
import java.util.Map;

/**
 * 공용 DTO: 2차 ML/딥러닝 score + 모델 메타 정보
 * MachineLearningService에서 생성하는 추천 점수 및 모델 정보
 */
@Data
public class NumberScore {
    private int number;                          // 번호 (1-44)
    private double recommendationScore;         // 추천 점수 (0.0 ~ 1.0)
    private double confidence;                  // 신뢰도 (0.0 ~ 1.0) - 낮을수록 랜덤성 높음
    private Map<String, Double> factorScores;   // 각 요인별 점수
    private String warning;                     // 과적합/랜덤성 경고 메시지
}


