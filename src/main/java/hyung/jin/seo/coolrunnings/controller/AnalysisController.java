package hyung.jin.seo.coolrunnings.controller;

import hyung.jin.seo.coolrunnings.service.MachineLearningService;
import hyung.jin.seo.coolrunnings.service.StatisticalAnalysisService;
import hyung.jin.seo.coolrunnings.service.ValidationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 분석 레이어 컨트롤러
 * 1차 통계 분석, 2차 ML/딥러닝, 3차 검증 레이어의 API 엔드포인트 제공
 */
@Slf4j
@RestController
@RequestMapping("/api/analysis")
@RequiredArgsConstructor
public class AnalysisController {

    private final StatisticalAnalysisService statisticalAnalysisService;
    private final MachineLearningService machineLearningService;
    private final ValidationService validationService;

    /**
     * 1차 통계 분석 수행
     * 
     * @param recentK 최근 k회차 (선택사항, null이면 전체 데이터 사용)
     * @param kValues 이동평균 k값 리스트 (선택사항, 기본값: [10, 20, 30, 50, 100])
     * @return 통계 분석 결과
     */
    @GetMapping("/statistical")
    public ResponseEntity<Map<String, Object>> performStatisticalAnalysis(
            @RequestParam(required = false) Integer recentK,
            @RequestParam(required = false) List<Integer> kValues) {
        
        log.info("1차 통계 분석 요청 (recentK: {}, kValues: {})", recentK, kValues);
        
        Map<String, Object> response = new HashMap<>();
        
        try {
            StatisticalAnalysisService.ComprehensiveStats stats = 
                statisticalAnalysisService.performComprehensiveAnalysis(recentK, kValues);
            
            response.put("success", true);
            response.put("message", "통계 분석 완료");
            response.put("data", stats);
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("통계 분석 실패: {}", e.getMessage(), e);
            
            response.put("success", false);
            response.put("message", "통계 분석 실패: " + e.getMessage());
            
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 2차 ML/딥러닝 분석 수행
     * 
     * @param windowSize 분석 윈도우 크기 (선택사항, 기본값: 100)
     * @return ML 예측 결과
     */
    @GetMapping("/ml")
    public ResponseEntity<Map<String, Object>> performMLAnalysis(
            @RequestParam(required = false, defaultValue = "100") Integer windowSize) {
        
        log.info("2차 ML/딥러닝 분석 요청 (windowSize: {})", windowSize);
        
        Map<String, Object> response = new HashMap<>();
        
        try {
            MachineLearningService.MLPredictionResult mlResult = 
                machineLearningService.performMLPrediction(windowSize);
            
            response.put("success", true);
            response.put("message", "ML 분석 완료");
            response.put("data", mlResult);
            response.put("warning", mlResult.getOverallWarning());
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("ML 분석 실패: {}", e.getMessage(), e);
            
            response.put("success", false);
            response.put("message", "ML 분석 실패: " + e.getMessage());
            
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 3차 검증 수행
     * 
     * @param validationCount 검증할 회차 수 (선택사항, 기본값: 1500)
     * @return 검증 결과
     */
    @GetMapping("/validation")
    public ResponseEntity<Map<String, Object>> performValidation(
            @RequestParam(required = false, defaultValue = "1500") Integer validationCount) {
        
        log.info("3차 검증 요청 (validationCount: {})", validationCount);
        
        Map<String, Object> response = new HashMap<>();
        
        try {
            ValidationService.ComprehensiveValidationResult validationResult = 
                validationService.performValidation(validationCount);
            
            if (validationResult == null) {
                response.put("success", false);
                response.put("message", "검증을 위한 데이터가 부족합니다.");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
            }
            
            response.put("success", true);
            response.put("message", "검증 완료");
            response.put("data", validationResult);
            response.put("summary", validationResult.getSummary());
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("검증 실패: {}", e.getMessage(), e);
            
            response.put("success", false);
            response.put("message", "검증 실패: " + e.getMessage());
            
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 전체 분석 파이프라인 실행
     * 1차 통계 분석 -> 2차 ML 분석 -> 3차 검증 순서로 실행
     * 
     * @param recentK 통계 분석에 사용할 최근 k회차 (선택사항)
     * @param windowSize ML 분석 윈도우 크기 (선택사항, 기본값: 100)
     * @param validationCount 검증 회차 수 (선택사항, 기본값: 1500)
     * @return 전체 분석 결과
     */
    @GetMapping("/pipeline")
    public ResponseEntity<Map<String, Object>> runAnalysisPipeline(
            @RequestParam(required = false) Integer recentK,
            @RequestParam(required = false, defaultValue = "100") Integer windowSize,
            @RequestParam(required = false, defaultValue = "1500") Integer validationCount) {
        
        log.info("전체 분석 파이프라인 실행 요청");
        
        Map<String, Object> response = new HashMap<>();
        Map<String, Object> pipelineResults = new HashMap<>();
        
        try {
            // 1차 통계 분석
            log.info("1차 통계 분석 시작");
            StatisticalAnalysisService.ComprehensiveStats stats = 
                statisticalAnalysisService.performComprehensiveAnalysis(recentK, null);
            pipelineResults.put("statistical", stats);
            
            // 2차 ML 분석
            log.info("2차 ML/딥러닝 분석 시작");
            MachineLearningService.MLPredictionResult mlResult = 
                machineLearningService.performMLPrediction(windowSize);
            pipelineResults.put("ml", mlResult);
            
            // 3차 검증
            log.info("3차 검증 시작");
            ValidationService.ComprehensiveValidationResult validationResult = 
                validationService.performValidation(validationCount);
            pipelineResults.put("validation", validationResult);
            
            response.put("success", true);
            response.put("message", "전체 분석 파이프라인 완료");
            response.put("data", pipelineResults);
            
            // 요약 생성
            StringBuilder summary = new StringBuilder();
            summary.append("=== 전체 분석 파이프라인 결과 ===\n\n");
            summary.append("1차 통계 분석: 완료 (총 회차: ").append(stats.getTotalDraws()).append(")\n");
            summary.append("2차 ML 분석: 완료 (신뢰도: ").append(String.format("%.2f%%", mlResult.getModelReliability() * 100)).append(")\n");
            if (validationResult != null) {
                summary.append("3차 검증: 완료 (평균 정확도: ").append(String.format("%.2f%%", validationResult.getStatistics().getAverageAccuracy() * 100)).append(")\n");
            }
            response.put("summary", summary.toString());
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("분석 파이프라인 실패: {}", e.getMessage(), e);
            
            response.put("success", false);
            response.put("message", "분석 파이프라인 실패: " + e.getMessage());
            response.put("partialResults", pipelineResults);
            
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }
}

