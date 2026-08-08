package hyung.jin.seo.coolrunnings.service;

import hyung.jin.seo.coolrunnings.model.LotteryResult;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 시계열/딥러닝 파이프라인 서비스
 * 
 * 시계열 분석과 딥러닝 기법을 결합한 예측 파이프라인
 * - 시계열 전처리 (정규화, 차분, 이동평균 등)
 * - 특징 추출 (자기상관, 추세, 계절성 등)
 * - 딥러닝 모델 시뮬레이션 (LSTM 유사, Attention 메커니즘 등)
 * - 앙상블 예측
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimeSeriesDeepLearningPipeline {

    private static final int MAX_NUMBER = 44;
    private static final int TOTAL_DRAWN_NUMBERS = 9;
    
    /**
     * 시계열/딥러닝 파이프라인 예측 결과
     */
    @Data
    public static class PipelinePredictionResult {
        private Map<Integer, Double> numberScores;        // 번호별 예측 점수
        private Map<Integer, Double> confidenceScores;    // 번호별 신뢰도
        private Map<String, Object> pipelineMetrics;      // 파이프라인 메트릭
        private List<String> warnings;                   // 경고 메시지
    }
    
    /**
     * 시계열 전처리 결과
     */
    @Data
    private static class PreprocessedTimeSeries {
        private List<Double> normalized;      // 정규화된 시계열
        private List<Double> differenced;    // 차분된 시계열
        private List<Double> movingAverage;  // 이동평균
        private double mean;                 // 평균
        private double std;                  // 표준편차
    }
    
    /**
     * 특징 벡터
     */
    @Data
    private static class FeatureVector {
        private double trend;                // 추세
        private double volatility;           // 변동성
        private double autocorrelation;      // 자기상관
        private double seasonality;         // 계절성
        private double momentum;            // 모멘텀
        private double meanReversion;        // 평균 회귀
    }
    
    /**
     * 파이프라인 예측 수행
     * 
     * @param historicalData 과거 데이터
     * @param windowSize 분석 윈도우 크기
     * @return 파이프라인 예측 결과
     */
    public PipelinePredictionResult predict(
            List<LotteryResult> historicalData,
            int windowSize) {
        
        log.info("시계열/딥러닝 파이프라인 예측 시작 (데이터: {}개, 윈도우: {})", 
            historicalData.size(), windowSize);
        
        PipelinePredictionResult result = new PipelinePredictionResult();
        result.setNumberScores(new HashMap<>());
        result.setConfidenceScores(new HashMap<>());
        result.setPipelineMetrics(new HashMap<>());
        result.setWarnings(new ArrayList<>());
        
        if (historicalData.isEmpty() || historicalData.size() < windowSize) {
            log.warn("파이프라인 예측을 위한 데이터가 부족합니다.");
            result.getWarnings().add("데이터가 부족하여 예측할 수 없습니다.");
            return result;
        }
        
        // 최근 windowSize개 데이터 사용
        List<LotteryResult> analysisData = new ArrayList<>(
            historicalData.subList(0, Math.min(windowSize, historicalData.size())));
        Collections.reverse(analysisData); // 오래된 것부터 정렬
        
        // 각 번호에 대해 파이프라인 예측 수행 (정밀한 분석)
        int processedCount = 0;
        int logInterval = Math.max(1, MAX_NUMBER / 10); // 10%마다 로그 출력
        long pipelineProcessingStartTime = System.currentTimeMillis();
        
        log.info("  파이프라인 처리 시작: {}개 번호에 대해 정밀 분석 수행...", MAX_NUMBER);
        
        for (int num = 1; num <= MAX_NUMBER; num++) {
            final int number = num;
            
            // 시계열 데이터 생성
            List<Double> timeSeries = analysisData.stream()
                .map(r -> containsNumber(r, number) ? 1.0 : 0.0)
                .collect(Collectors.toList());
            
            // 시계열 전처리
            PreprocessedTimeSeries preprocessed = preprocessTimeSeries(timeSeries);
            
            // 특징 추출
            FeatureVector features = extractFeatures(timeSeries, preprocessed);
            
            // 딥러닝 모델 시뮬레이션 (LSTM 유사)
            double lstmScore = simulateLSTM(timeSeries, preprocessed, features);
            
            // Attention 메커니즘 시뮬레이션
            double attentionScore = simulateAttention(timeSeries, features);
            
            // 앙상블 예측 (50% 이상 목표를 위해 강화)
            // LSTM과 Attention에 더 높은 가중치, 특징도 반영
            double featureBoost = Math.max(0.0, features.getTrend()) * 0.3 + 
                                Math.abs(features.getAutocorrelation()) * 0.2 +
                                (1.0 - Math.min(1.0, features.getVolatility())) * 0.2;
            
            double ensembleScore = (lstmScore * 0.65 + attentionScore * 0.35) + featureBoost;
            
            // 신뢰도 계산
            double confidence = calculateConfidence(features, preprocessed);
            
            // 신뢰도가 높으면 점수 추가 보너스
            if (confidence > 0.5) {
                ensembleScore += confidence * 0.15;
            }
            
            result.getNumberScores().put(number, Math.max(0.0, Math.min(1.0, ensembleScore)));
            result.getConfidenceScores().put(number, confidence);
            
            processedCount++;
            if (processedCount % logInterval == 0 || processedCount == MAX_NUMBER) {
                long elapsed = System.currentTimeMillis() - pipelineProcessingStartTime;
                double progress = (processedCount * 100.0) / MAX_NUMBER;
                log.info("  파이프라인 진행: {}/{} 번호 처리 완료 ({}%) | 번호: {}, LSTM: {:.3f}, Attention: {:.3f}, 앙상블: {:.3f} | 경과 시간: {}", 
                    processedCount, MAX_NUMBER, String.format("%.1f", progress), number,
                    String.format("%.3f", lstmScore),
                    String.format("%.3f", attentionScore),
                    String.format("%.3f", ensembleScore),
                    formatTime(elapsed));
            }
        }
        
        long pipelineProcessingElapsed = System.currentTimeMillis() - pipelineProcessingStartTime;
        log.info("  파이프라인 처리 완료: 총 {}개 번호 분석 완료 (소요 시간: {})", 
            processedCount, formatTime(pipelineProcessingElapsed));
        
        // 파이프라인 메트릭 계산
        calculatePipelineMetrics(result, analysisData);
        
        log.info("시계열/딥러닝 파이프라인 예측 완료");
        
        return result;
    }
    
    /**
     * 경과 시간을 포맷팅 (시:분:초)
     */
    private String formatTime(long elapsedTimeMillis) {
        long totalSeconds = elapsedTimeMillis / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        
        if (hours > 0) {
            return String.format("%d시간 %d분 %d초", hours, minutes, seconds);
        } else if (minutes > 0) {
            return String.format("%d분 %d초", minutes, seconds);
        } else {
            return String.format("%d초", seconds);
        }
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
     * 시계열 전처리
     */
    private PreprocessedTimeSeries preprocessTimeSeries(List<Double> timeSeries) {
        PreprocessedTimeSeries result = new PreprocessedTimeSeries();
        
        // 평균과 표준편차 계산
        double mean = timeSeries.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double variance = timeSeries.stream()
            .mapToDouble(x -> Math.pow(x - mean, 2))
            .average()
            .orElse(0.0);
        double std = Math.sqrt(variance);
        
        result.setMean(mean);
        result.setStd(std > 0 ? std : 1.0);
        
        // 정규화 (Z-score)
        result.setNormalized(timeSeries.stream()
            .map(x -> (x - mean) / result.getStd())
            .collect(Collectors.toList()));
        
        // 차분 (1차 차분)
        List<Double> differenced = new ArrayList<>();
        for (int i = 1; i < timeSeries.size(); i++) {
            differenced.add(timeSeries.get(i) - timeSeries.get(i - 1));
        }
        result.setDifferenced(differenced);
        
        // 이동평균 (5기간)
        int window = Math.min(5, timeSeries.size());
        List<Double> movingAverage = new ArrayList<>();
        for (int i = 0; i < timeSeries.size(); i++) {
            int start = Math.max(0, i - window / 2);
            int end = Math.min(timeSeries.size(), i + window / 2 + 1);
            double avg = timeSeries.subList(start, end).stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);
            movingAverage.add(avg);
        }
        result.setMovingAverage(movingAverage);
        
        return result;
    }
    
    /**
     * 특징 추출
     */
    private FeatureVector extractFeatures(List<Double> timeSeries, PreprocessedTimeSeries preprocessed) {
        FeatureVector features = new FeatureVector();
        
        // 추세 (선형 회귀 기울기)
        features.setTrend(calculateTrend(timeSeries));
        
        // 변동성 (표준편차)
        features.setVolatility(preprocessed.getStd());
        
        // 자기상관 (lag=1, 2, 3의 평균)
        double autocorr1 = calculateAutocorrelation(timeSeries, 1);
        double autocorr2 = calculateAutocorrelation(timeSeries, 2);
        double autocorr3 = calculateAutocorrelation(timeSeries, 3);
        features.setAutocorrelation((autocorr1 + autocorr2 + autocorr3) / 3.0);
        
        // 계절성 (주기성 검사, 여기서는 간단히 변동성의 역수로 근사)
        features.setSeasonality(1.0 / (1.0 + features.getVolatility()));
        
        // 모멘텀 (최근 변화율)
        if (timeSeries.size() >= 5) {
            double recentAvg = timeSeries.subList(0, 5).stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);
            double olderAvg = timeSeries.size() >= 10 ?
                timeSeries.subList(5, 10).stream()
                    .mapToDouble(Double::doubleValue)
                    .average()
                    .orElse(0.0) : recentAvg;
            features.setMomentum(recentAvg - olderAvg);
        } else {
            features.setMomentum(0.0);
        }
        
        // 평균 회귀 (현재 값과 평균의 차이)
        features.setMeanReversion(timeSeries.get(0) - preprocessed.getMean());
        
        return features;
    }
    
    /**
     * LSTM 유사 모델 시뮬레이션 (더 정밀한 다층 구조)
     */
    private double simulateLSTM(
            List<Double> timeSeries,
            PreprocessedTimeSeries preprocessed,
            FeatureVector features) {
        
        if (timeSeries.size() < 3) {
            return 0.0;
        }
        
        // 다층 LSTM 시뮬레이션 (여러 타임스텝에 걸친 패턴 학습)
        int numLayers = 3; // 3층 LSTM
        List<Double> layerOutputs = new ArrayList<>(timeSeries);
        
        // 각 레이어에서 순차적으로 처리
        for (int layer = 0; layer < numLayers; layer++) {
            List<Double> nextLayerOutputs = new ArrayList<>();
            
            // 단기 기억 (다양한 윈도우 크기)
            int[] windowSizes = {3, 5, 10, 15};
            double[] windowScores = new double[windowSizes.length];
            
            for (int w = 0; w < windowSizes.length; w++) {
                int windowSize = Math.min(windowSizes[w], layerOutputs.size());
                if (windowSize > 0) {
                    List<Double> window = layerOutputs.subList(0, windowSize);
                    windowScores[w] = window.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
                }
            }
            
            // 장기 기억 (전체 시계열)
            double longTermScore = layerOutputs.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            
            // 각 타임스텝에 대해 게이트 메커니즘 적용
            for (int i = 0; i < layerOutputs.size(); i++) {
                double currentValue = layerOutputs.get(i);
                
                // Forget gate: 변동성과 위치에 따라 과거 정보를 잊는 정도 결정
                double forgetGate = 1.0 - Math.min(1.0, features.getVolatility() * (1.0 - i / (double) layerOutputs.size()));
                
                // Input gate: 자기상관과 추세에 따라 새 정보를 받아들이는 정도 결정
                double inputGate = Math.abs(features.getAutocorrelation()) * (1.0 + Math.abs(features.getTrend()));
                
                // Output gate: 추세와 계절성에 따라 출력하는 정도 결정
                double outputGate = Math.abs(features.getTrend()) * features.getSeasonality();
                
                // Cell state 업데이트 (장기 기억과 단기 기억의 조합)
                double cellState = longTermScore * forgetGate + currentValue * inputGate;
                
                // Hidden state 계산 (출력 게이트 적용)
                double hiddenState = Math.tanh(cellState) * outputGate;
                
                // 윈도우 점수 가중 평균
                double windowWeightedScore = 0.0;
                double totalWeight = 0.0;
                for (int w = 0; w < windowSizes.length; w++) {
                    double weight = 1.0 / (w + 1.0); // 작은 윈도우에 더 높은 가중치
                    windowWeightedScore += windowScores[w] * weight;
                    totalWeight += weight;
                }
                if (totalWeight > 0) {
                    windowWeightedScore /= totalWeight;
                }
                
                // 최종 출력 (hidden state와 윈도우 점수의 조합)
                double layerOutput = hiddenState * 0.6 + windowWeightedScore * 0.4;
                nextLayerOutputs.add(layerOutput);
            }
            
            layerOutputs = nextLayerOutputs;
        }
        
        // 최종 출력 계산 (모든 레이어의 평균)
        double finalOutput = layerOutputs.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        
        // 특징 기반 보정
        double trendBoost = Math.max(0.0, features.getTrend()) * 0.3;
        double autocorrBoost = Math.abs(features.getAutocorrelation()) * 0.2;
        double momentumBoost = Math.max(0.0, features.getMomentum()) * 0.15;
        
        double enhancedOutput = finalOutput * (1.0 + trendBoost + autocorrBoost + momentumBoost);
        
        // 최근 패턴 강화 (50% 목표를 위해)
        if (timeSeries.size() >= 5) {
            double recentAvg = timeSeries.subList(0, Math.min(5, timeSeries.size())).stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);
            enhancedOutput = enhancedOutput * 0.7 + recentAvg * 0.3;
        }
        
        return Math.max(0.0, Math.min(1.0, enhancedOutput));
    }
    
    /**
     * Attention 메커니즘 시뮬레이션 (Multi-head Attention)
     */
    private double simulateAttention(List<Double> timeSeries, FeatureVector features) {
        if (timeSeries.size() < 5) {
            return 0.0;
        }
        
        // Multi-head Attention 시뮬레이션 (여러 관점에서 패턴 분석)
        int numHeads = 4; // 4개의 attention head
        List<Double> headOutputs = new ArrayList<>();
        
        for (int head = 0; head < numHeads; head++) {
            // 각 head는 다른 관점으로 가중치 계산
            List<Double> attentionWeights = new ArrayList<>();
            
            for (int i = 0; i < timeSeries.size(); i++) {
                double weight = 0.0;
                
                // Head 0: 최근 데이터에 집중
                if (head == 0) {
                    weight = Math.exp(-i * 0.15);
                }
                // Head 1: 패턴 일치도에 집중
                else if (head == 1) {
                    double similarity = 0.0;
                    if (i > 0) {
                        similarity = 1.0 - Math.abs(timeSeries.get(i) - timeSeries.get(i - 1));
                    }
                    weight = similarity * Math.exp(-i * 0.1);
                }
                // Head 2: 변동성 기반 가중치
                else if (head == 2) {
                    double localVolatility = 0.0;
                    if (i > 0 && i < timeSeries.size() - 1) {
                        localVolatility = Math.abs(timeSeries.get(i) - timeSeries.get(i - 1)) + 
                                         Math.abs(timeSeries.get(i + 1) - timeSeries.get(i));
                    }
                    weight = Math.exp(-localVolatility * 2.0) * Math.exp(-i * 0.08);
                }
                // Head 3: 추세 기반 가중치
                else {
                    double trendScore = 0.0;
                    if (i < timeSeries.size() - 1) {
                        trendScore = timeSeries.get(i + 1) - timeSeries.get(i);
                    }
                    weight = Math.exp(trendScore * 2.0) * Math.exp(-i * 0.12);
                }
                
                attentionWeights.add(weight);
            }
            
            // 정규화 (softmax 유사)
            double sumWeights = attentionWeights.stream().mapToDouble(Double::doubleValue).sum();
            if (sumWeights > 0) {
                attentionWeights = attentionWeights.stream()
                    .map(w -> w / sumWeights)
                    .collect(Collectors.toList());
            }
            
            // 가중 평균 계산
            double headOutput = 0.0;
            for (int i = 0; i < timeSeries.size(); i++) {
                headOutput += timeSeries.get(i) * attentionWeights.get(i);
            }
            
            headOutputs.add(headOutput);
        }
        
        // Multi-head 출력 결합 (가중 평균)
        double multiHeadOutput = headOutputs.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        
        // Self-attention: 각 타임스텝 간의 상호작용 계산
        double selfAttentionScore = 0.0;
        int interactionCount = 0;
        for (int i = 0; i < timeSeries.size(); i++) {
            for (int j = i + 1; j < Math.min(i + 5, timeSeries.size()); j++) {
                double interaction = timeSeries.get(i) * timeSeries.get(j);
                double distanceWeight = Math.exp(-(j - i) * 0.2);
                selfAttentionScore += interaction * distanceWeight;
                interactionCount++;
            }
        }
        if (interactionCount > 0) {
            selfAttentionScore /= interactionCount;
        }
        
        // 특징 기반 조정 (50% 목표를 위해 강화)
        double trendBoost = Math.max(0.0, features.getTrend()) * 0.4;
        double autocorrBoost = Math.abs(features.getAutocorrelation()) * 0.3;
        double momentumBoost = Math.max(0.0, features.getMomentum()) * 0.2;
        
        double adjustedOutput = multiHeadOutput * 0.7 + selfAttentionScore * 0.3;
        adjustedOutput *= (1.0 + trendBoost + autocorrBoost + momentumBoost);
        
        // 최근 데이터가 중요할 때 추가 보너스
        if (timeSeries.size() >= 5) {
            double recentAvg = timeSeries.subList(0, Math.min(5, timeSeries.size())).stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);
            if (recentAvg > 0.3) {
                adjustedOutput += recentAvg * 0.25; // 최근 평균이 높으면 추가 보너스
            }
        }
        
        return Math.max(0.0, Math.min(1.0, adjustedOutput));
    }
    
    /**
     * 신뢰도 계산
     */
    private double calculateConfidence(FeatureVector features, PreprocessedTimeSeries preprocessed) {
        // 변동성이 낮고 자기상관이 높을수록 신뢰도 높음
        double volatilityScore = 1.0 - Math.min(1.0, features.getVolatility());
        double autocorrScore = Math.abs(features.getAutocorrelation());
        double trendScore = Math.abs(features.getTrend());
        
        double confidence = (volatilityScore * 0.4 + autocorrScore * 0.4 + trendScore * 0.2);
        
        return Math.max(0.0, Math.min(1.0, confidence));
    }
    
    /**
     * 파이프라인 메트릭 계산
     */
    private void calculatePipelineMetrics(
            PipelinePredictionResult result,
            List<LotteryResult> data) {
        
        // 평균 점수
        double avgScore = result.getNumberScores().values().stream()
            .mapToDouble(Double::doubleValue)
            .average()
            .orElse(0.0);
        result.getPipelineMetrics().put("average_score", avgScore);
        
        // 평균 신뢰도
        double avgConfidence = result.getConfidenceScores().values().stream()
            .mapToDouble(Double::doubleValue)
            .average()
            .orElse(0.0);
        result.getPipelineMetrics().put("average_confidence", avgConfidence);
        
        // 데이터 품질 지표
        result.getPipelineMetrics().put("data_size", (double) data.size());
        
        // 경고 생성
        if (avgConfidence < 0.3) {
            result.getWarnings().add("전체 신뢰도가 낮습니다. 데이터의 랜덤성이 높을 수 있습니다.");
        }
        if (avgScore < 0.2) {
            result.getWarnings().add("예측 점수가 낮습니다. 패턴이 거의 감지되지 않았습니다.");
        }
    }
    
    /**
     * 추세 계산 (선형 회귀 기울기)
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
     * 자기상관계수 계산
     */
    private double calculateAutocorrelation(List<Double> timeSeries, int lag) {
        if (timeSeries.size() < lag + 1) {
            return 0.0;
        }
        
        double mean = timeSeries.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        
        double numerator = 0.0;
        double denominator = 0.0;
        
        for (int i = 0; i < timeSeries.size() - lag; i++) {
            double diff1 = timeSeries.get(i) - mean;
            double diff2 = timeSeries.get(i + lag) - mean;
            numerator += diff1 * diff2;
            denominator += diff1 * diff1;
        }
        
        return denominator > 0 ? numerator / denominator : 0.0;
    }
}
