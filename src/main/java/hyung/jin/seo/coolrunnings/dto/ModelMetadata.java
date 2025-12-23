package hyung.jin.seo.coolrunnings.dto;

import lombok.Data;
import java.time.LocalDate;

/**
 * 공용 DTO: 모델 메타 정보
 */
@Data
public class ModelMetadata {
    private String modelVersion;              // 모델 버전 (예: "v1.0.0")
    private Integer trainedUntilDraw;         // 학습에 사용된 마지막 회차
    private String modelType;                  // 모델 타입 (예: "LSTM_LIKE", "TIME_SERIES")
    private double backtestWinRate;           // 백테스트 승률 (과거 N회 백테스트 결과)
    private int backtestPeriod;                // 백테스트 기간 (회차 수)
    private LocalDate trainedDate;             // 학습 일자
}

