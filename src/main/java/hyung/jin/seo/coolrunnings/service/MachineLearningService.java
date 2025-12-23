package hyung.jin.seo.coolrunnings.service;

import hyung.jin.seo.coolrunnings.model.LotteryResult;
import hyung.jin.seo.coolrunnings.repository.LotteryResultRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 2차 ML/딥러닝 레이어
 * LSTM/시계열 NN 같은 모델로 "직접 번호 예측" 시도는 가능하나,
 * 복권 특성상 과적합·랜덤성 한계를 명확히 인지하고
 * "추천 점수/가중치" 수준의 출력으로 정의
 * 
 * 주의: 복권은 본질적으로 랜덤성이 높으므로, 모델의 예측력에 한계가 있음을 명확히 인지해야 함
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MachineLearningService {

    private final LotteryResultRepository lotteryResultRepository;
    
    // 번호 범위 (Set for Life는 1-44)
    private static final int MAX_NUMBER = 44;
    
    /**
     * 번호별 추천 점수 (가중치)
     * 직접 번호 예측이 아닌 "추천 점수" 수준의 출력
     */
    @Data
    public static class NumberRecommendationScore {
        private int number;
        private double recommendationScore;  // 추천 점수 (0.0 ~ 1.0)
        private double confidence;            // 신뢰도 (0.0 ~ 1.0) - 낮을수록 랜덤성 높음
        private Map<String, Double> factorScores; // 각 요인별 점수
        private String warning;                // 과적합/랜덤성 경고 메시지
    }
    
    /**
     * 시계열 분석 결과
     */
    @Data
    public static class TimeSeriesAnalysis {
        private int number;
        private List<Double> timeSeries;      // 시계열 데이터 (출현 여부: 1.0 또는 0.0)
        private double trend;                  // 추세 (양수: 상승, 음수: 하락)
        private double volatility;             // 변동성 (높을수록 랜덤성 높음)
        private double autocorrelation;        // 자기상관계수 (패턴 존재 여부)
        private double predictionScore;        // 예측 점수 (낮을수록 랜덤성 높음)
    }
    
    /**
     * LSTM 유사 시계열 분석 결과
     * 실제 LSTM은 아니지만, 시계열 패턴을 학습하는 유사한 접근법 사용
     */
    @Data
    public static class LSTMLikeAnalysis {
        private int number;
        private double sequencePatternScore;  // 시퀀스 패턴 점수
        private double longTermMemoryScore;    // 장기 기억 점수 (과거 패턴)
        private double shortTermMemoryScore;   // 단기 기억 점수 (최근 패턴)
        private double overfittingRisk;         // 과적합 위험도 (높을수록 위험)
        private double randomnessIndicator;    // 랜덤성 지표 (높을수록 예측 불가능)
    }
    
    /**
     * 모델 메타 정보
     */
    @Data
    public static class ModelMetadata {
        private String modelVersion;              // 모델 버전 (예: "v1.0.0")
        private Integer trainedUntilDraw;         // 학습에 사용된 마지막 회차
        private String modelType;                  // 모델 타입 (예: "LSTM_LIKE", "TIME_SERIES")
        private double backtestWinRate;           // 백테스트 승률 (과거 N회 백테스트 결과)
        private int backtestPeriod;                // 백테스트 기간 (회차 수)
        private LocalDate trainedDate;             // 학습 일자
    }
    
    /**
     * ML 모델 예측 결과 (최종)
     */
    @Data
    public static class MLPredictionResult {
        private List<NumberRecommendationScore> recommendationScores; // 모든 번호의 추천 점수
        private List<TimeSeriesAnalysis> timeSeriesAnalyses;          // 시계열 분석 결과
        private List<LSTMLikeAnalysis> lstmLikeAnalyses;             // LSTM 유사 분석 결과
        private String overallWarning;                                 // 전체 경고 메시지
        private double modelReliability;                               // 모델 신뢰도 (0.0 ~ 1.0)
        
        // 개선: score 배열 및 모델 메타 정보 추가
        private double[] scoreArray;                                   // 길이 44짜리 score 배열 (번호 1-44)
        private ModelMetadata modelMetadata;                           // 모델 메타 정보
    }
    
    /**
     * 번호가 당첨 번호에 포함되어 있는지 확인
     */
    private boolean containsNumber(LotteryResult result, int number) {
        return (result.getWinningNumber1() != null && result.getWinningNumber1() == number) ||
               (result.getWinningNumber2() != null && result.getWinningNumber2() == number) ||
               (result.getWinningNumber3() != null && result.getWinningNumber3() == number) ||
               (result.getWinningNumber4() != null && result.getWinningNumber4() == number) ||
               (result.getWinningNumber5() != null && result.getWinningNumber5() == number) ||
               (result.getWinningNumber6() != null && result.getWinningNumber6() == number) ||
               (result.getWinningNumber7() != null && result.getWinningNumber7() == number) ||
               (result.getBonusNumber1() != null && result.getBonusNumber1() == number) ||
               (result.getBonusNumber2() != null && result.getBonusNumber2() == number);
    }
    
    /**
     * 시계열 분석 수행
     * 
     * @param windowSize 분석할 윈도우 크기
     * @return 번호별 시계열 분석 결과
     */
    public List<TimeSeriesAnalysis> performTimeSeriesAnalysis(int windowSize) {
        List<LotteryResult> allResults = lotteryResultRepository.findAllByOrderByDrawDesc();
        
        if (allResults.isEmpty() || allResults.size() < windowSize) {
            log.warn("시계열 분석을 위한 데이터가 부족합니다. (필요: {}, 현재: {})", 
                windowSize, allResults.size());
            return new ArrayList<>();
        }
        
        List<LotteryResult> analysisData = allResults.subList(0, Math.min(windowSize, allResults.size()));
        // 역순으로 정렬 (오래된 것부터)
        Collections.reverse(analysisData);
        
        List<TimeSeriesAnalysis> analyses = new ArrayList<>();
        
        for (int num = 1; num <= MAX_NUMBER; num++) {
            final int number = num;
            
            TimeSeriesAnalysis analysis = new TimeSeriesAnalysis();
            analysis.setNumber(number);
            
            // 시계열 데이터 생성 (출현: 1.0, 미출현: 0.0)
            List<Double> timeSeries = analysisData.stream()
                .map(r -> containsNumber(r, number) ? 1.0 : 0.0)
                .collect(Collectors.toList());
            
            analysis.setTimeSeries(timeSeries);
            
            // 추세 계산 (선형 회귀 기반)
            double trend = calculateTrend(timeSeries);
            analysis.setTrend(trend);
            
            // 변동성 계산 (표준편차)
            double volatility = calculateVolatility(timeSeries);
            analysis.setVolatility(volatility);
            
            // 자기상관계수 계산 (lag=1, 2, 3)
            double autocorrelation = calculateAutocorrelation(timeSeries, 3);
            analysis.setAutocorrelation(autocorrelation);
            
            // 예측 점수 계산 (낮을수록 랜덤성 높음)
            // 변동성이 높고 자기상관이 낮으면 예측 불가능
            double predictionScore = Math.max(0.0, 1.0 - volatility - (1.0 - Math.abs(autocorrelation)));
            analysis.setPredictionScore(predictionScore);
            
            analyses.add(analysis);
        }
        
        return analyses;
    }
    
    /**
     * LSTM 유사 시계열 분석 수행
     * 실제 LSTM은 아니지만, 시계열 패턴을 학습하는 유사한 접근법 사용
     * 
     * @param sequenceLength 시퀀스 길이
     * @return 번호별 LSTM 유사 분석 결과
     */
    public List<LSTMLikeAnalysis> performLSTMLikeAnalysis(int sequenceLength) {
        List<LotteryResult> allResults = lotteryResultRepository.findAllByOrderByDrawDesc();
        
        if (allResults.isEmpty() || allResults.size() < sequenceLength * 2) {
            log.warn("LSTM 유사 분석을 위한 데이터가 부족합니다. (필요: {}, 현재: {})", 
                sequenceLength * 2, allResults.size());
            return new ArrayList<>();
        }
        
        List<LSTMLikeAnalysis> analyses = new ArrayList<>();
        
        for (int num = 1; num <= MAX_NUMBER; num++) {
            final int number = num;
            
            LSTMLikeAnalysis analysis = new LSTMLikeAnalysis();
            analysis.setNumber(number);
            
            // 시계열 데이터 생성
            List<Double> timeSeries = allResults.stream()
                .map(r -> containsNumber(r, number) ? 1.0 : 0.0)
                .collect(Collectors.toList());
            
            // 단기 기억 점수 (최근 sequenceLength 회차)
            int shortTermSize = Math.min(sequenceLength, timeSeries.size());
            List<Double> shortTerm = timeSeries.subList(0, shortTermSize);
            double shortTermScore = calculatePatternScore(shortTerm);
            analysis.setShortTermMemoryScore(shortTermScore);
            
            // 장기 기억 점수 (전체 데이터)
            double longTermScore = calculatePatternScore(timeSeries);
            analysis.setLongTermMemoryScore(longTermScore);
            
            // 시퀀스 패턴 점수 (특정 길이의 패턴 반복 여부)
            double sequencePatternScore = calculateSequencePatternScore(timeSeries, sequenceLength);
            analysis.setSequencePatternScore(sequencePatternScore);
            
            // 과적합 위험도 계산
            // 단기와 장기 점수가 크게 다르면 과적합 위험
            double overfittingRisk = Math.abs(shortTermScore - longTermScore);
            analysis.setOverfittingRisk(overfittingRisk);
            
            // 랜덤성 지표 계산
            // 변동성이 높고 패턴 점수가 낮으면 랜덤성 높음
            double volatility = calculateVolatility(timeSeries);
            double randomnessIndicator = (volatility + (1.0 - sequencePatternScore)) / 2.0;
            analysis.setRandomnessIndicator(randomnessIndicator);
            
            analyses.add(analysis);
        }
        
        return analyses;
    }
    
    /**
     * 번호별 추천 점수 계산
     * 직접 번호 예측이 아닌 "추천 점수" 수준의 출력
     * 
     * @param windowSize 분석 윈도우 크기
     * @return 번호별 추천 점수 리스트
     */
    public List<NumberRecommendationScore> calculateRecommendationScores(int windowSize) {
        List<LotteryResult> allResults = lotteryResultRepository.findAllByOrderByDrawDesc();
        
        if (allResults.isEmpty()) {
            log.warn("분석할 데이터가 없습니다.");
            return new ArrayList<>();
        }
        
        // 시계열 분석 수행
        List<TimeSeriesAnalysis> timeSeriesAnalyses = performTimeSeriesAnalysis(windowSize);
        
        // LSTM 유사 분석 수행
        List<LSTMLikeAnalysis> lstmAnalyses = performLSTMLikeAnalysis(Math.min(50, windowSize));
        
        List<NumberRecommendationScore> scores = new ArrayList<>();
        
        for (int num = 1; num <= MAX_NUMBER; num++) {
            final int number = num;
            
            NumberRecommendationScore score = new NumberRecommendationScore();
            score.setNumber(number);
            score.setFactorScores(new HashMap<>());
            
            // 시계열 분석 결과 찾기
            TimeSeriesAnalysis tsAnalysis = timeSeriesAnalyses.stream()
                .filter(a -> a.getNumber() == number)
                .findFirst()
                .orElse(null);
            
            // LSTM 유사 분석 결과 찾기
            LSTMLikeAnalysis lstmAnalysis = lstmAnalyses.stream()
                .filter(a -> a.getNumber() == number)
                .findFirst()
                .orElse(null);
            
            if (tsAnalysis == null || lstmAnalysis == null) {
                continue;
            }
            
            // 각 요인별 점수 계산
            double trendScore = normalizeScore(tsAnalysis.getTrend(), -1.0, 1.0);
            double volatilityScore = 1.0 - tsAnalysis.getVolatility(); // 변동성 낮을수록 좋음
            double autocorrScore = Math.abs(tsAnalysis.getAutocorrelation());
            double sequenceScore = lstmAnalysis.getSequencePatternScore();
            double memoryScore = (lstmAnalysis.getLongTermMemoryScore() + 
                                lstmAnalysis.getShortTermMemoryScore()) / 2.0;
            
            score.getFactorScores().put("trend", trendScore);
            score.getFactorScores().put("volatility", volatilityScore);
            score.getFactorScores().put("autocorrelation", autocorrScore);
            score.getFactorScores().put("sequence_pattern", sequenceScore);
            score.getFactorScores().put("memory", memoryScore);
            
            // 가중 평균으로 최종 추천 점수 계산
            // 각 요인의 중요도에 따라 가중치 부여
            double recommendationScore = 
                trendScore * 0.15 +
                volatilityScore * 0.20 +
                autocorrScore * 0.15 +
                sequenceScore * 0.25 +
                memoryScore * 0.25;
            
            score.setRecommendationScore(Math.max(0.0, Math.min(1.0, recommendationScore)));
            
            // 신뢰도 계산 (랜덤성과 과적합 위험도 고려)
            double confidence = 1.0 - lstmAnalysis.getRandomnessIndicator() - 
                               lstmAnalysis.getOverfittingRisk() * 0.5;
            score.setConfidence(Math.max(0.0, Math.min(1.0, confidence)));
            
            // 경고 메시지 생성
            StringBuilder warning = new StringBuilder();
            if (lstmAnalysis.getRandomnessIndicator() > 0.7) {
                warning.append("높은 랜덤성 감지. 예측 불가능성이 높습니다. ");
            }
            if (lstmAnalysis.getOverfittingRisk() > 0.5) {
                warning.append("과적합 위험이 있습니다. ");
            }
            if (tsAnalysis.getPredictionScore() < 0.3) {
                warning.append("예측 점수가 낮습니다. ");
            }
            if (warning.length() == 0) {
                warning.append("상대적으로 예측 가능한 패턴이 감지되었습니다. (단, 복권의 본질적 랜덤성은 여전히 존재합니다)");
            }
            score.setWarning(warning.toString());
            
            scores.add(score);
        }
        
        // 추천 점수 순으로 정렬
        scores.sort((a, b) -> Double.compare(b.getRecommendationScore(), a.getRecommendationScore()));
        
        return scores;
    }
    
    /**
     * 백테스트 승률 계산
     * 과거 N회에 대해 모델의 성능을 평가
     * 
     * @param backtestPeriod 백테스트 기간 (회차 수)
     * @return 백테스트 승률 (0.0 ~ 1.0)
     */
    private double calculateBacktestWinRate(int backtestPeriod) {
        List<LotteryResult> allResults = lotteryResultRepository.findAllByOrderByDrawDesc();
        
        if (allResults.isEmpty() || allResults.size() < backtestPeriod + 10) {
            return 0.0;
        }
        
        // 백테스트 데이터 (최근 backtestPeriod개)
        List<LotteryResult> backtestData = allResults.subList(0, Math.min(backtestPeriod, allResults.size()));
        Collections.reverse(backtestData);
        
        int correctPredictions = 0;
        int totalPredictions = 0;
        
        // 각 회차에 대해 예측하고 실제와 비교
        for (int i = 10; i < backtestData.size(); i++) {
            LotteryResult currentResult = backtestData.get(i);
            List<LotteryResult> historicalData = backtestData.subList(0, i);
            
            // 예측 수행
            List<NumberRecommendationScore> scores = calculateRecommendationScores(Math.min(100, historicalData.size()));
            List<Integer> predictedTop9 = scores.stream()
                .limit(9)
                .map(NumberRecommendationScore::getNumber)
                .collect(Collectors.toList());
            
            // 실제 번호 추출
            List<Integer> actualNumbers = extractActualNumbers(currentResult);
            
            // 맞춘 개수 계산
            Set<Integer> predictedSet = new HashSet<>(predictedTop9);
            Set<Integer> actualSet = new HashSet<>(actualNumbers);
            predictedSet.retainAll(actualSet);
            
            if (predictedSet.size() >= 3) { // 최소 3개 이상 맞추면 성공으로 간주
                correctPredictions++;
            }
            totalPredictions++;
        }
        
        return totalPredictions > 0 ? (double) correctPredictions / totalPredictions : 0.0;
    }
    
    /**
     * 실제 당첨 번호 추출
     */
    private List<Integer> extractActualNumbers(LotteryResult result) {
        List<Integer> numbers = new ArrayList<>();
        if (result.getWinningNumber1() != null) numbers.add(result.getWinningNumber1());
        if (result.getWinningNumber2() != null) numbers.add(result.getWinningNumber2());
        if (result.getWinningNumber3() != null) numbers.add(result.getWinningNumber3());
        if (result.getWinningNumber4() != null) numbers.add(result.getWinningNumber4());
        if (result.getWinningNumber5() != null) numbers.add(result.getWinningNumber5());
        if (result.getWinningNumber6() != null) numbers.add(result.getWinningNumber6());
        if (result.getWinningNumber7() != null) numbers.add(result.getWinningNumber7());
        if (result.getBonusNumber1() != null) numbers.add(result.getBonusNumber1());
        if (result.getBonusNumber2() != null) numbers.add(result.getBonusNumber2());
        return numbers;
    }
    
    /**
     * ML 모델 예측 수행 (종합)
     * 
     * @param windowSize 분석 윈도우 크기
     * @return ML 예측 결과
     */
    public MLPredictionResult performMLPrediction(int windowSize) {
        log.info("ML 모델 예측 시작 (windowSize: {})", windowSize);
        
        List<LotteryResult> allResults = lotteryResultRepository.findAllByOrderByDrawDesc();
        Optional<LotteryResult> latestResult = allResults.isEmpty() ? Optional.empty() : Optional.of(allResults.get(0));
        
        MLPredictionResult result = new MLPredictionResult();
        
        // 추천 점수 계산
        List<NumberRecommendationScore> recommendationScores = calculateRecommendationScores(windowSize);
        result.setRecommendationScores(recommendationScores);
        
        // 시계열 분석
        List<TimeSeriesAnalysis> timeSeriesAnalyses = performTimeSeriesAnalysis(windowSize);
        result.setTimeSeriesAnalyses(timeSeriesAnalyses);
        
        // LSTM 유사 분석
        List<LSTMLikeAnalysis> lstmAnalyses = performLSTMLikeAnalysis(Math.min(50, windowSize));
        result.setLstmLikeAnalyses(lstmAnalyses);
        
        // 개선: 길이 44짜리 score 배열 생성 (번호 1-44)
        double[] scoreArray = new double[MAX_NUMBER];
        Map<Integer, Double> scoreMap = recommendationScores.stream()
            .collect(Collectors.toMap(
                NumberRecommendationScore::getNumber,
                NumberRecommendationScore::getRecommendationScore
            ));
        for (int i = 0; i < MAX_NUMBER; i++) {
            scoreArray[i] = scoreMap.getOrDefault(i + 1, 0.0);
        }
        result.setScoreArray(scoreArray);
        
        // 전체 모델 신뢰도 계산
        double avgConfidence = recommendationScores.stream()
            .mapToDouble(NumberRecommendationScore::getConfidence)
            .average()
            .orElse(0.0);
        result.setModelReliability(avgConfidence);
        
        // 개선: 모델 메타 정보 생성
        ModelMetadata metadata = new ModelMetadata();
        metadata.setModelVersion("v1.0.0");
        metadata.setModelType("LSTM_LIKE_TIME_SERIES");
        metadata.setTrainedDate(LocalDate.now());
        
        if (latestResult.isPresent()) {
            metadata.setTrainedUntilDraw(latestResult.get().getDraw());
        }
        
        // 백테스트 승률 계산
        int backtestPeriod = Math.min(100, allResults.size());
        double backtestWinRate = calculateBacktestWinRate(backtestPeriod);
        metadata.setBacktestWinRate(backtestWinRate);
        metadata.setBacktestPeriod(backtestPeriod);
        
        result.setModelMetadata(metadata);
        
        // 전체 경고 메시지
        StringBuilder overallWarning = new StringBuilder();
        overallWarning.append("=== ML 모델 예측 결과 ===\n");
        overallWarning.append("모델 버전: ").append(metadata.getModelVersion()).append("\n");
        overallWarning.append("모델 타입: ").append(metadata.getModelType()).append("\n");
        if (metadata.getTrainedUntilDraw() != null) {
            overallWarning.append("학습 마지막 회차: ").append(metadata.getTrainedUntilDraw()).append("\n");
        }
        overallWarning.append(String.format("백테스트 승률: %.2f%% (기간: %d회차)\n", 
            backtestWinRate * 100, backtestPeriod));
        overallWarning.append("주의: 복권은 본질적으로 랜덤성이 높은 시스템입니다.\n");
        overallWarning.append("모델의 예측력에 한계가 있으며, 과적합 위험이 항상 존재합니다.\n");
        overallWarning.append("이 결과는 '추천 점수' 수준이며, 절대적인 예측이 아닙니다.\n");
        overallWarning.append(String.format("전체 모델 신뢰도: %.2f%%\n", avgConfidence * 100));
        overallWarning.append("상위 7개 추천 번호:\n");
        
        for (int i = 0; i < Math.min(7, recommendationScores.size()); i++) {
            NumberRecommendationScore score = recommendationScores.get(i);
            overallWarning.append(String.format("  %d. 번호 %d (점수: %.3f, 신뢰도: %.2f%%) - %s\n",
                i + 1, score.getNumber(), score.getRecommendationScore(),
                score.getConfidence() * 100, score.getWarning()));
        }
        
        result.setOverallWarning(overallWarning.toString());
        
        log.info("ML 모델 예측 완료 (신뢰도: {:.2f}%, 백테스트 승률: {:.2f}%)", 
            avgConfidence * 100, backtestWinRate * 100);
        
        return result;
    }
    
    /**
     * 추세 계산 (선형 회귀 기반)
     */
    private double calculateTrend(List<Double> timeSeries) {
        if (timeSeries.size() < 2) {
            return 0.0;
        }
        
        int n = timeSeries.size();
        double sumX = 0.0, sumY = 0.0, sumXY = 0.0, sumX2 = 0.0;
        
        for (int i = 0; i < n; i++) {
            double x = i;
            double y = timeSeries.get(i);
            sumX += x;
            sumY += y;
            sumXY += x * y;
            sumX2 += x * x;
        }
        
        double slope = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX);
        return slope;
    }
    
    /**
     * 변동성 계산 (표준편차)
     */
    private double calculateVolatility(List<Double> timeSeries) {
        if (timeSeries.isEmpty()) {
            return 0.0;
        }
        
        double mean = timeSeries.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double variance = timeSeries.stream()
            .mapToDouble(x -> Math.pow(x - mean, 2))
            .average()
            .orElse(0.0);
        
        return Math.sqrt(variance);
    }
    
    /**
     * 자기상관계수 계산
     */
    private double calculateAutocorrelation(List<Double> timeSeries, int maxLag) {
        if (timeSeries.size() < maxLag + 1) {
            return 0.0;
        }
        
        double mean = timeSeries.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double variance = timeSeries.stream()
            .mapToDouble(x -> Math.pow(x - mean, 2))
            .average()
            .orElse(0.0);
        
        if (variance == 0.0) {
            return 0.0;
        }
        
        double maxAutocorr = 0.0;
        
        for (int lag = 1; lag <= maxLag; lag++) {
            double covariance = 0.0;
            for (int i = 0; i < timeSeries.size() - lag; i++) {
                covariance += (timeSeries.get(i) - mean) * (timeSeries.get(i + lag) - mean);
            }
            covariance /= (timeSeries.size() - lag);
            
            double autocorr = covariance / variance;
            maxAutocorr = Math.max(maxAutocorr, Math.abs(autocorr));
        }
        
        return maxAutocorr;
    }
    
    /**
     * 패턴 점수 계산
     */
    private double calculatePatternScore(List<Double> timeSeries) {
        if (timeSeries.isEmpty()) {
            return 0.0;
        }
        
        // 간단한 패턴 점수: 연속성과 주기성 고려
        double continuityScore = calculateContinuityScore(timeSeries);
        double periodicityScore = calculatePeriodicityScore(timeSeries);
        
        return (continuityScore + periodicityScore) / 2.0;
    }
    
    /**
     * 연속성 점수 계산
     */
    private double calculateContinuityScore(List<Double> timeSeries) {
        if (timeSeries.size() < 2) {
            return 0.0;
        }
        
        int transitions = 0;
        for (int i = 1; i < timeSeries.size(); i++) {
            if (!timeSeries.get(i).equals(timeSeries.get(i - 1))) {
                transitions++;
            }
        }
        
        // 전환이 적을수록 연속성 높음
        return 1.0 - ((double) transitions / (timeSeries.size() - 1));
    }
    
    /**
     * 주기성 점수 계산
     */
    private double calculatePeriodicityScore(List<Double> timeSeries) {
        if (timeSeries.size() < 4) {
            return 0.0;
        }
        
        double maxPeriodicity = 0.0;
        
        // 2부터 timeSeries.size()/2까지의 주기 확인
        for (int period = 2; period <= timeSeries.size() / 2; period++) {
            int matches = 0;
            int comparisons = 0;
            
            for (int i = 0; i < timeSeries.size() - period; i++) {
                if (timeSeries.get(i).equals(timeSeries.get(i + period))) {
                    matches++;
                }
                comparisons++;
            }
            
            if (comparisons > 0) {
                double periodicity = (double) matches / comparisons;
                maxPeriodicity = Math.max(maxPeriodicity, periodicity);
            }
        }
        
        return maxPeriodicity;
    }
    
    /**
     * 시퀀스 패턴 점수 계산
     */
    private double calculateSequencePatternScore(List<Double> timeSeries, int sequenceLength) {
        if (timeSeries.size() < sequenceLength * 2) {
            return 0.0;
        }
        
        // 특정 길이의 시퀀스가 반복되는지 확인
        int matches = 0;
        int comparisons = 0;
        
        for (int i = 0; i <= timeSeries.size() - sequenceLength * 2; i++) {
            List<Double> seq1 = timeSeries.subList(i, i + sequenceLength);
            
            for (int j = i + sequenceLength; j <= timeSeries.size() - sequenceLength; j++) {
                List<Double> seq2 = timeSeries.subList(j, j + sequenceLength);
                
                if (seq1.equals(seq2)) {
                    matches++;
                }
                comparisons++;
            }
        }
        
        return comparisons > 0 ? (double) matches / comparisons : 0.0;
    }
    
    /**
     * 점수를 0.0 ~ 1.0 범위로 정규화
     */
    private double normalizeScore(double value, double min, double max) {
        if (max == min) {
            return 0.5;
        }
        double normalized = (value - min) / (max - min);
        return Math.max(0.0, Math.min(1.0, normalized));
    }
}

