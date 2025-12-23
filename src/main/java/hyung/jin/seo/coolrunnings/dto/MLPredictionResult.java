package hyung.jin.seo.coolrunnings.dto;

import lombok.Data;

/**
 * 공용 DTO: ML 예측 결과 (종합)
 */
@Data
public class MLPredictionResult {
    private NumberScore[] scoreArray;              // 길이 44짜리 score 배열 (번호 1-44)
    private ModelMetadata modelMetadata;            // 모델 메타 정보
    private double modelReliability;               // 모델 신뢰도 (0.0 ~ 1.0)
    private String overallWarning;                 // 전체 경고 메시지
}

