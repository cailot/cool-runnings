package hyung.jin.seo.coolrunnings.service;

import hyung.jin.seo.coolrunnings.model.LotteryResult;
import hyung.jin.seo.coolrunnings.repository.LotteryResultRepository;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 고급 예측 서비스
 * Ensemble Learning, Cross-Validation, Association Rule Mining 등을 활용한
 * 실용적이고 정확도 높은 복권 번호 예측 알고리즘
 * 
 * 주요 특징:
 * 1. 여러 모델의 앙상블 (Statistical, ML, Pattern-based)
 * 2. Cross-Validation을 통한 최적 가중치 학습
 * 3. Association Rule Mining으로 번호 간 연관 규칙 발견
 * 4. Gradient Boosting 기반 Feature Importance 학습
 * 5. 동적 가중치 조정 (성능 기반)
 */
@Slf4j
@Service
public class AdvancedPredictionService {

    private final LotteryResultRepository lotteryResultRepository;
    private final MachineLearningService machineLearningService;
    private final NumberGuessService numberGuessService;
    
    // 순환 참조 방지를 위해 @Lazy를 생성자 파라미터에 적용
    public AdvancedPredictionService(
            LotteryResultRepository lotteryResultRepository,
            MachineLearningService machineLearningService,
            @Lazy NumberGuessService numberGuessService) {
        this.lotteryResultRepository = lotteryResultRepository;
        this.machineLearningService = machineLearningService;
        this.numberGuessService = numberGuessService;
    }
    // statisticalAnalysisService는 향후 확장을 위해 주석 처리 (현재는 직접 계산)
    // private final StatisticalAnalysisService statisticalAnalysisService;
    
    // 번호 범위 (Set for Life는 1-44)
    private static final int MAX_NUMBER = 44;
    // 당첨 번호 7개 + 보너스 번호 2개 = 총 9개
    private static final int TOTAL_DRAWN_NUMBERS = 9;
    // Cross-Validation 폴드 수
    private static final int CV_FOLDS = 5;
    // 최소 학습 데이터 크기
    private static final int MIN_TRAINING_SIZE = 50;
    
    // 학습된 최적 가중치 (캐시)
    private volatile OptimizedWeights cachedWeights = null;
    private volatile LocalDate lastTrainingDate = null;
    
    // ML 예측 결과 캐시 (성능 최적화)
    private volatile Map<Integer, Double> cachedMLScores = null;
    private volatile long mlCacheTimestamp = 0;
    private volatile int mlCacheDataSize = -1;
    private volatile int mlCacheMinDraw = -1;
    private volatile int mlCacheMaxDraw = -1;
    private static final long ML_CACHE_TTL_MS = 60 * 60 * 1000; // 1시간 캐시 (성능 최적화)
    
    /**
     * 최적화된 가중치
     * Cross-Validation을 통해 학습된 각 모델의 가중치
     */
    @Data
    public static class OptimizedWeights {
        // 모델별 가중치
        private double statisticalWeight;      // 통계 분석 모델 가중치
        private double mlWeight;                // ML 모델 가중치
        private double patternWeight;           // 패턴 기반 모델 가중치
        private double associationWeight;       // 연관 규칙 모델 가중치
        
        // Feature별 가중치 (통계 분석 내부)
        private Map<String, Double> featureWeights;
        
        // 검증 성능
        private double cvAccuracy;              // Cross-Validation 정확도
        private double cvAverageMatchCount;    // CV 평균 맞춘 개수
        private LocalDate trainingDate;         // 학습 일자
        private int trainingDataSize;           // 학습 데이터 크기
        
        public OptimizedWeights() {
            this.featureWeights = new HashMap<>();
            // 기본값 설정
            this.statisticalWeight = 0.3;
            this.mlWeight = 0.3;
            this.patternWeight = 0.2;
            this.associationWeight = 0.2;
        }
    }
    
    /**
     * 번호별 예측 점수
     */
    @Data
    public static class NumberPredictionScore {
        private int number;
        private double finalScore;              // 최종 예측 점수
        private double statisticalScore;        // 통계 분석 점수
        private double mlScore;                  // ML 점수
        private double patternScore;             // 패턴 기반 점수
        private double associationScore;        // 연관 규칙 점수
        private double confidence;               // 신뢰도
        private Map<String, Double> factorScores; // 세부 요인별 점수
    }
    
    /**
     * 연관 규칙
     * 번호 간의 연관 관계를 나타냄
     */
    @Data
    public static class AssociationRule {
        private Set<Integer> antecedent;        // 선행 번호들
        private int consequent;                 // 결과 번호
        private double support;                  // 지지도
        private double confidence;               // 신뢰도
        private double lift;                     // 향상도
    }
    
    /**
     * 예측 결과
     */
    @Data
    public static class PredictionResult {
        private List<NumberPredictionScore> allScores;  // 모든 번호의 점수
        private List<Integer> predictedNumbers;        // 예측된 번호 (9개)
        private OptimizedWeights usedWeights;           // 사용된 가중치
        private double predictionConfidence;            // 예측 신뢰도
        private String algorithmInfo;                    // 알고리즘 정보
    }
    
    /**
     * Cross-Validation 결과
     */
    @Data
    public static class CrossValidationResult {
        private double averageAccuracy;
        private double averageMatchCount;
        private Map<String, List<Double>> modelPerformances; // 모델별 성능 (폴드별)
        private OptimizedWeights bestWeights;
        private List<Double> foldAccuracies;
        private List<Double> foldMatchCounts;
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
     * 통계 분석 기반 점수 계산
     */
    private double calculateStatisticalScore(int number, List<LotteryResult> historicalData) {
        if (historicalData.isEmpty()) return 0.0;
        
        // 최근 50회차 데이터
        int recentCount = Math.min(50, historicalData.size());
        List<LotteryResult> recentData = historicalData.subList(0, recentCount);
        
        // 1. 최근 출현 빈도
        long recentAppearances = recentData.stream()
            .filter(r -> containsNumber(r, number))
            .count();
        double recentFreq = (double) recentAppearances / recentCount;
        
        // 2. 전체 출현 빈도
        long totalAppearances = historicalData.stream()
            .filter(r -> containsNumber(r, number))
            .count();
        double overallFreq = (double) totalAppearances / historicalData.size();
        
        // 3. 미출현 간격 분석
        int currentAbsence = calculateCurrentAbsence(historicalData, number);
        double absenceScore = Math.min(1.0, currentAbsence / 10.0); // 10회 이상 미출현이면 높은 점수
        
        // 4. 이동평균 (최근 20회)
        int maWindow = Math.min(20, recentData.size());
        long maAppearances = recentData.subList(0, maWindow).stream()
            .filter(r -> containsNumber(r, number))
            .count();
        double movingAvg = (double) maAppearances / maWindow;
        
        // 5. 트렌드 (최근 20회 vs 그 이전 20회)
        double trend = 0.0;
        if (historicalData.size() >= 40) {
            long recent20 = historicalData.subList(0, 20).stream()
                .filter(r -> containsNumber(r, number))
                .count();
            long previous20 = historicalData.subList(20, 40).stream()
                .filter(r -> containsNumber(r, number))
                .count();
            trend = ((double) recent20 - previous20) / 20.0;
        }
        
        // 가중 평균 (기본 가중치, 최적화 시 조정됨)
        double score = 0.25 * recentFreq + 
                      0.25 * overallFreq + 
                      0.20 * absenceScore + 
                      0.15 * movingAvg + 
                      0.15 * Math.max(0.0, trend + 0.1);
        
        return Math.max(0.0, Math.min(1.0, score));
    }
    
    /**
     * 현재 미출현 횟수 계산
     */
    private int calculateCurrentAbsence(List<LotteryResult> data, int number) {
        for (int i = 0; i < data.size(); i++) {
            if (containsNumber(data.get(i), number)) {
                return i;
            }
        }
        return data.size();
    }
    
    /**
     * ML 예측 캐시를 미리 생성 (성능 최적화: ML 예측 비활성화, 간단한 통계 점수 사용)
     */
    private void ensureMLCache(List<LotteryResult> historicalData) {
        long currentTime = System.currentTimeMillis();
        int dataSize = historicalData.size();
        int minDraw = historicalData.stream().mapToInt(LotteryResult::getDraw).min().orElse(-1);
        int maxDraw = historicalData.stream().mapToInt(LotteryResult::getDraw).max().orElse(-1);
        
        // 캐시가 유효하면 재사용
        if (cachedMLScores != null && (currentTime - mlCacheTimestamp) < ML_CACHE_TTL_MS
                && mlCacheDataSize == dataSize && mlCacheMinDraw == minDraw && mlCacheMaxDraw == maxDraw) {
            log.debug("AdvancedPredictionService: ML 캐시 재사용 (캐시 크기: {}, 남은 시간: {}초)", 
                cachedMLScores.size(), (ML_CACHE_TTL_MS - (currentTime - mlCacheTimestamp)) / 1000);
            return;
        }
        
        // 동기화 블록으로 중복 계산 방지
        synchronized (this) {
            // 다시 확인 (다른 스레드가 이미 캐시를 생성했을 수 있음)
            if (cachedMLScores != null && (currentTime - mlCacheTimestamp) < ML_CACHE_TTL_MS
                    && mlCacheDataSize == dataSize && mlCacheMinDraw == minDraw && mlCacheMaxDraw == maxDraw) {
                return;
            }
            
            // 캐시 초기화
            cachedMLScores = new HashMap<>();
            
            try {
                if (historicalData.size() >= 50) {
                    // 성능 최적화: ML 예측 대신 간단한 통계 점수 사용
                    log.info("AdvancedPredictionService: ML 점수 계산 시작 (간소화 버전, 데이터 수: {})", historicalData.size());
                    
                    // 간단한 빈도 기반 점수 계산 (ML 예측 대신)
                    for (int num = 1; num <= MAX_NUMBER; num++) {
                        final int number = num; // 람다에서 사용하기 위해 final 변수로
                        // 최근 100개 데이터에서의 출현 빈도
                        int recentWindow = Math.min(100, historicalData.size());
                        long recentCount = historicalData.subList(0, recentWindow).stream()
                            .filter(r -> containsNumber(r, number))
                            .count();
                        double recentFreq = (double) recentCount / recentWindow;
                        
                        // 전체 데이터에서의 출현 빈도
                        long totalCount = historicalData.stream()
                            .filter(r -> containsNumber(r, number))
                            .count();
                        double overallFreq = (double) totalCount / historicalData.size();
                        
                        // 간단한 가중 평균 점수
                        double score = 0.6 * recentFreq + 0.4 * overallFreq;
                        cachedMLScores.put(number, score);
                    }
                    
                    mlCacheTimestamp = currentTime;
                    mlCacheDataSize = dataSize;
                    mlCacheMinDraw = minDraw;
                    mlCacheMaxDraw = maxDraw;
                    log.info("AdvancedPredictionService: ML 점수 계산 완료 (캐시된 번호 수: {})", 
                        cachedMLScores.size());
                }
            } catch (Exception e) {
                log.warn("AdvancedPredictionService: ML 점수 계산 중 오류 발생: {}", e.getMessage());
            }
        }
    }
    
    /**
     * ML 기반 점수 계산 (캐시 사용)
     */
    private double calculateMLScore(int number, List<LotteryResult> historicalData) {
        // 캐시 확인 및 업데이트
        ensureMLCache(historicalData);
        
        // 캐시에서 점수 가져오기
        double score = cachedMLScores != null ? cachedMLScores.getOrDefault(number, 0.0) : 0.0;
        
        // 첫 번째 호출 시에만 캐시 사용 확인 로그 출력
        if (number == 1 && cachedMLScores != null && cachedMLScores.size() > 0) {
            log.debug("AdvancedPredictionService: ML 캐시에서 점수 조회 (캐시 크기: {})", cachedMLScores.size());
        }
        
        return score;
    }
    
    /**
     * 패턴 기반 점수 계산
     * 주기적 패턴, 연속 패턴 등을 분석
     */
    private double calculatePatternScore(int number, List<LotteryResult> historicalData) {
        if (historicalData.size() < 20) return 0.0;
        
        // 출현 회차 추출
        List<Integer> appearanceDraws = new ArrayList<>();
        for (LotteryResult result : historicalData) {
            if (containsNumber(result, number)) {
                appearanceDraws.add(result.getDraw());
            }
        }
        
        if (appearanceDraws.size() < 3) return 0.0;
        
        // 1. 주기성 분석 (간격의 일관성)
        List<Integer> intervals = new ArrayList<>();
        for (int i = 1; i < appearanceDraws.size(); i++) {
            intervals.add(appearanceDraws.get(i - 1) - appearanceDraws.get(i));
        }
        
        double intervalVariance = calculateVariance(intervals);
        double periodicityScore = 1.0 / (1.0 + intervalVariance); // 분산이 낮을수록 주기성 높음
        
        // 2. 최근 간격 분석
        int currentAbsence = calculateCurrentAbsence(historicalData, number);
        double avgInterval = intervals.stream().mapToInt(Integer::intValue).average().orElse(0.0);
        double intervalScore = 0.0;
        if (avgInterval > 0) {
            double ratio = currentAbsence / avgInterval;
            if (ratio >= 0.8 && ratio <= 1.2) {
                intervalScore = 1.0; // 평균 간격 근처면 높은 점수
            } else if (ratio > 1.2) {
                intervalScore = 0.7; // 평균보다 길면 중간 점수
            } else {
                intervalScore = 0.3; // 평균보다 짧으면 낮은 점수
            }
        }
        
        // 3. 연속 출현 패턴
        int maxConsecutive = 0;
        int currentConsecutive = 0;
        for (int i = 0; i < historicalData.size() - 1; i++) {
            boolean current = containsNumber(historicalData.get(i), number);
            boolean next = containsNumber(historicalData.get(i + 1), number);
            if (current && next) {
                currentConsecutive++;
                maxConsecutive = Math.max(maxConsecutive, currentConsecutive);
            } else {
                currentConsecutive = 0;
            }
        }
        double consecutiveScore = Math.min(1.0, maxConsecutive / 3.0);
        
        // 종합 점수
        double score = 0.4 * periodicityScore + 
                      0.4 * intervalScore + 
                      0.2 * (1.0 - consecutiveScore); // 연속 출현은 낮은 점수
        
        return Math.max(0.0, Math.min(1.0, score));
    }
    
    /**
     * 분산 계산
     */
    private double calculateVariance(List<Integer> values) {
        if (values.isEmpty()) return 0.0;
        double mean = values.stream().mapToInt(Integer::intValue).average().orElse(0.0);
        double variance = values.stream()
            .mapToDouble(v -> Math.pow(v - mean, 2))
            .average()
            .orElse(0.0);
        return variance;
    }
    
    /**
     * Association Rule Mining
     * 번호 간 연관 규칙을 발견하여 점수 계산
     */
    private List<AssociationRule> mineAssociationRules(List<LotteryResult> historicalData, int minSupport) {
        List<AssociationRule> rules = new ArrayList<>();
        
        if (historicalData.size() < 20) return rules;
        
        // 각 번호 쌍의 동시 출현 빈도 계산
        Map<Set<Integer>, Integer> cooccurrence = new HashMap<>();
        Map<Integer, Integer> numberFrequency = new HashMap<>();
        
        for (LotteryResult result : historicalData) {
            List<Integer> numbers = extractActualNumbers(result);
            Set<Integer> numberSet = new HashSet<>(numbers);
            
            // 단일 번호 빈도
            for (Integer num : numbers) {
                numberFrequency.put(num, numberFrequency.getOrDefault(num, 0) + 1);
            }
            
            // 번호 쌍 동시 출현 (2개 조합)
            List<Integer> numberList = new ArrayList<>(numberSet);
            for (int i = 0; i < numberList.size(); i++) {
                for (int j = i + 1; j < numberList.size(); j++) {
                    Set<Integer> pair = new HashSet<>(Arrays.asList(numberList.get(i), numberList.get(j)));
                    cooccurrence.put(pair, cooccurrence.getOrDefault(pair, 0) + 1);
                }
            }
        }
        
        int totalDraws = historicalData.size();
        double minSupportRatio = (double) minSupport / totalDraws;
        
        // 연관 규칙 생성 (A -> B 형태)
        for (Map.Entry<Set<Integer>, Integer> entry : cooccurrence.entrySet()) {
            if (entry.getValue() < minSupport) continue;
            
            List<Integer> pairList = new ArrayList<>(entry.getKey());
            if (pairList.size() != 2) continue;
            
            int a = pairList.get(0);
            int b = pairList.get(1);
            
            double support = (double) entry.getValue() / totalDraws;
            double confidenceAB = (double) entry.getValue() / numberFrequency.getOrDefault(a, 1);
            double confidenceBA = (double) entry.getValue() / numberFrequency.getOrDefault(b, 1);
            
            double liftAB = confidenceAB / ((double) numberFrequency.getOrDefault(b, 1) / totalDraws);
            double liftBA = confidenceBA / ((double) numberFrequency.getOrDefault(a, 1) / totalDraws);
            
            // A -> B 규칙
            if (support >= minSupportRatio && liftAB > 1.0) {
                AssociationRule rule = new AssociationRule();
                rule.setAntecedent(Collections.singleton(a));
                rule.setConsequent(b);
                rule.setSupport(support);
                rule.setConfidence(confidenceAB);
                rule.setLift(liftAB);
                rules.add(rule);
            }
            
            // B -> A 규칙
            if (support >= minSupportRatio && liftBA > 1.0) {
                AssociationRule rule = new AssociationRule();
                rule.setAntecedent(Collections.singleton(b));
                rule.setConsequent(a);
                rule.setSupport(support);
                rule.setConfidence(confidenceBA);
                rule.setLift(liftBA);
                rules.add(rule);
            }
        }
        
        // lift 기준으로 정렬
        rules.sort((r1, r2) -> Double.compare(r2.getLift(), r1.getLift()));
        
        return rules;
    }
    
    /**
     * Association Rule 기반 점수 계산
     */
    private double calculateAssociationScore(int number, List<LotteryResult> historicalData, 
                                           List<AssociationRule> rules) {
        if (rules.isEmpty()) return 0.0;
        
        // 최근 회차의 번호들
        List<Integer> recentNumbers = new ArrayList<>();
        int recentCount = Math.min(10, historicalData.size());
        for (int i = 0; i < recentCount; i++) {
            recentNumbers.addAll(extractActualNumbers(historicalData.get(i)));
        }
        Set<Integer> recentNumberSet = new HashSet<>(recentNumbers);
        
        // 해당 번호를 결과로 하는 규칙들 찾기
        double totalScore = 0.0;
        double totalWeight = 0.0;
        
        for (AssociationRule rule : rules) {
            if (rule.getConsequent() == number) {
                // 선행 번호가 최근에 출현했는지 확인
                boolean antecedentAppeared = rule.getAntecedent().stream()
                    .anyMatch(recentNumberSet::contains);
                
                if (antecedentAppeared) {
                    // lift와 confidence를 가중치로 사용
                    double weight = rule.getLift() * rule.getConfidence();
                    totalScore += weight;
                    totalWeight += weight;
                }
            }
        }
        
        return totalWeight > 0 ? Math.min(1.0, totalScore / totalWeight) : 0.0;
    }
    
    /**
     * Cross-Validation을 통한 최적 가중치 학습
     */
    public CrossValidationResult performCrossValidation(int dataSize) {
        log.info("Cross-Validation 시작 (데이터 크기: {})", dataSize);
        
        List<LotteryResult> allResults = numberGuessService.getCachedAllResults();
        
        if (allResults.isEmpty() || allResults.size() < dataSize + MIN_TRAINING_SIZE) {
            log.warn("Cross-Validation을 위한 데이터가 부족합니다.");
            return null;
        }
        
        // Cross-Validation 시작 전에 ML 예측 캐시를 미리 생성 (한 번만 수행)
        if (allResults.size() >= 50) {
            int windowSize = Math.min(100, allResults.size());
            ensureMLCache(allResults.subList(0, Math.min(windowSize + 50, allResults.size())));
        }
        
        // 검증할 데이터 (최신 dataSize개)
        List<LotteryResult> validationData = new ArrayList<>(allResults.subList(0, dataSize));
        Collections.reverse(validationData); // 오래된 것부터
        
        // 폴드별로 나누기
        int foldSize = validationData.size() / CV_FOLDS;
        List<Double> foldAccuracies = new ArrayList<>();
        List<Double> foldMatchCounts = new ArrayList<>();
        Map<String, List<Double>> modelPerformances = new HashMap<>();
        modelPerformances.put("statistical", new ArrayList<>());
        modelPerformances.put("ml", new ArrayList<>());
        modelPerformances.put("pattern", new ArrayList<>());
        modelPerformances.put("association", new ArrayList<>());
        
        // 가중치 후보들 (그리드 서치)
        List<OptimizedWeights> weightCandidates = generateWeightCandidates();
        
        OptimizedWeights bestWeights = null;
        double bestScore = -1.0;
        
        // 각 가중치 후보에 대해 Cross-Validation 수행
        for (OptimizedWeights weights : weightCandidates) {
            double totalAccuracy = 0.0;
            double totalMatchCount = 0.0;
            
            for (int fold = 0; fold < CV_FOLDS; fold++) {
                int startIdx = fold * foldSize;
                int endIdx = (fold == CV_FOLDS - 1) ? validationData.size() : (fold + 1) * foldSize;
                
                // 테스트 데이터
                List<LotteryResult> testData = validationData.subList(startIdx, endIdx);
                
                // 학습 데이터 (테스트 데이터 제외)
                List<LotteryResult> trainData = new ArrayList<>();
                if (startIdx > 0) {
                    trainData.addAll(validationData.subList(0, startIdx));
                }
                if (endIdx < validationData.size()) {
                    trainData.addAll(validationData.subList(endIdx, validationData.size()));
                }
                // validationData 이전의 데이터도 추가
                if (allResults.size() > dataSize) {
                    trainData.addAll(allResults.subList(dataSize, allResults.size()));
                }
                
                if (trainData.size() < MIN_TRAINING_SIZE) continue;
                
                // 예측 수행
                double foldAccuracy = 0.0;
                double foldMatchCount = 0.0;
                int testCount = 0;
                
                for (LotteryResult testResult : testData) {
                    // 해당 회차 이전의 데이터만 사용
                    List<LotteryResult> historicalData = new ArrayList<>();
                    for (LotteryResult trainResult : trainData) {
                        if (trainResult.getDraw() < testResult.getDraw()) {
                            historicalData.add(trainResult);
                        }
                    }
                    
                    if (historicalData.size() < MIN_TRAINING_SIZE) continue;
                    
                    // 예측
                    PredictionResult prediction = predictWithWeights(historicalData, weights);
                    
                    // 실제 번호와 비교
                    List<Integer> actualNumbers = extractActualNumbers(testResult);
                    Set<Integer> predictedSet = new HashSet<>(prediction.getPredictedNumbers());
                    Set<Integer> actualSet = new HashSet<>(actualNumbers);
                    predictedSet.retainAll(actualSet);
                    
                    int matchCount = predictedSet.size();
                    foldMatchCount += matchCount;
                    foldAccuracy += (double) matchCount / TOTAL_DRAWN_NUMBERS;
                    testCount++;
                }
                
                if (testCount > 0) {
                    foldMatchCount /= testCount;
                    foldAccuracy /= testCount;
                    totalMatchCount += foldMatchCount;
                    totalAccuracy += foldAccuracy;
                }
            }
            
            double avgAccuracy = totalAccuracy / CV_FOLDS;
            double avgMatchCount = totalMatchCount / CV_FOLDS;
            
            // 점수 = 정확도 * 0.6 + 평균 맞춘 개수 / 9 * 0.4
            double score = avgAccuracy * 0.6 + (avgMatchCount / TOTAL_DRAWN_NUMBERS) * 0.4;
            
            if (score > bestScore) {
                bestScore = score;
                bestWeights = weights;
                bestWeights.setCvAccuracy(avgAccuracy);
                bestWeights.setCvAverageMatchCount(avgMatchCount);
            }
        }
        
        // 최종 결과 생성
        CrossValidationResult result = new CrossValidationResult();
        result.setBestWeights(bestWeights);
        result.setAverageAccuracy(bestWeights != null ? bestWeights.getCvAccuracy() : 0.0);
        result.setAverageMatchCount(bestWeights != null ? bestWeights.getCvAverageMatchCount() : 0.0);
        result.setModelPerformances(modelPerformances);
        result.setFoldAccuracies(foldAccuracies);
        result.setFoldMatchCounts(foldMatchCounts);
        
        log.info("Cross-Validation 완료 - 최적 정확도: {}%, 평균 맞춘 개수: {}개", 
            String.format("%.2f", result.getAverageAccuracy() * 100), String.format("%.2f", result.getAverageMatchCount()));
        
        return result;
    }
    
    /**
     * 가중치 후보 생성 (그리드 서치)
     */
    private List<OptimizedWeights> generateWeightCandidates() {
        List<OptimizedWeights> candidates = new ArrayList<>();
        
        // 다양한 가중치 조합 생성
        double[] weights = {0.1, 0.2, 0.3, 0.4, 0.5};
        
        for (double stat : weights) {
            for (double ml : weights) {
                for (double pattern : weights) {
                    double assoc = 1.0 - stat - ml - pattern;
                    if (assoc >= 0.0 && assoc <= 0.5) {
                        OptimizedWeights w = new OptimizedWeights();
                        w.setStatisticalWeight(stat);
                        w.setMlWeight(ml);
                        w.setPatternWeight(pattern);
                        w.setAssociationWeight(assoc);
                        candidates.add(w);
                    }
                }
            }
        }
        
        return candidates;
    }
    
    /**
     * 주어진 가중치로 예측 수행
     */
    private PredictionResult predictWithWeights(List<LotteryResult> historicalData, OptimizedWeights weights) {
        PredictionResult result = new PredictionResult();
        result.setUsedWeights(weights);
        result.setAllScores(new ArrayList<>());
        
        // ML 예측 캐시를 먼저 생성 (한 번만 수행)
        ensureMLCache(historicalData);
        
        // Association Rules 마이닝
        List<AssociationRule> rules = mineAssociationRules(historicalData, 5);
        
        // 각 번호에 대해 점수 계산
        Map<Integer, NumberPredictionScore> scores = new HashMap<>();
        
        for (int num = 1; num <= MAX_NUMBER; num++) {
            NumberPredictionScore score = new NumberPredictionScore();
            score.setNumber(num);
            score.setFactorScores(new HashMap<>());
            
            // 각 모델별 점수 계산
            double statScore = calculateStatisticalScore(num, historicalData);
            double mlScore = calculateMLScore(num, historicalData);
            double patternScore = calculatePatternScore(num, historicalData);
            double assocScore = calculateAssociationScore(num, historicalData, rules);
            
            score.setStatisticalScore(statScore);
            score.setMlScore(mlScore);
            score.setPatternScore(patternScore);
            score.setAssociationScore(assocScore);
            
            score.getFactorScores().put("statistical", statScore);
            score.getFactorScores().put("ml", mlScore);
            score.getFactorScores().put("pattern", patternScore);
            score.getFactorScores().put("association", assocScore);
            
            // 가중 평균으로 최종 점수 계산
            double finalScore = weights.getStatisticalWeight() * statScore +
                              weights.getMlWeight() * mlScore +
                              weights.getPatternWeight() * patternScore +
                              weights.getAssociationWeight() * assocScore;
            
            score.setFinalScore(finalScore);
            
            // 신뢰도 계산 (점수들의 일관성)
            double mean = (statScore + mlScore + patternScore + assocScore) / 4.0;
            double variance = Math.pow(statScore - mean, 2) + 
                            Math.pow(mlScore - mean, 2) + 
                            Math.pow(patternScore - mean, 2) + 
                            Math.pow(assocScore - mean, 2);
            variance /= 4.0;
            double confidence = 1.0 / (1.0 + Math.sqrt(variance)); // 분산이 낮을수록 높은 신뢰도
            score.setConfidence(confidence);
            
            scores.put(num, score);
        }
        
        // 점수 순으로 정렬
        List<NumberPredictionScore> sortedScores = scores.values().stream()
            .sorted((a, b) -> Double.compare(b.getFinalScore(), a.getFinalScore()))
            .collect(Collectors.toList());
        
        result.setAllScores(sortedScores);
        
        // 상위 9개 번호 선택
        List<Integer> predictedNumbers = sortedScores.stream()
            .limit(TOTAL_DRAWN_NUMBERS)
            .map(NumberPredictionScore::getNumber)
            .collect(Collectors.toList());
        
        result.setPredictedNumbers(predictedNumbers);
        
        // 전체 예측 신뢰도
        double avgConfidence = sortedScores.stream()
            .limit(TOTAL_DRAWN_NUMBERS)
            .mapToDouble(NumberPredictionScore::getConfidence)
            .average()
            .orElse(0.0);
        result.setPredictionConfidence(avgConfidence);
        
        result.setAlgorithmInfo(String.format(
            "Ensemble Learning (Statistical: %.2f, ML: %.2f, Pattern: %.2f, Association: %.2f)",
            weights.getStatisticalWeight(), weights.getMlWeight(), 
            weights.getPatternWeight(), weights.getAssociationWeight()));
        
        return result;
    }
    
    /**
     * 최적 가중치 학습 및 캐싱
     */
    public OptimizedWeights trainAndCacheWeights(int dataSize) {
        log.info("최적 가중치 학습 시작 (데이터 크기: {})", dataSize);
        
        // Cross-Validation 수행
        CrossValidationResult cvResult = performCrossValidation(dataSize);
        
        if (cvResult == null || cvResult.getBestWeights() == null) {
            log.warn("가중치 학습 실패");
            return null;
        }
        
        OptimizedWeights weights = cvResult.getBestWeights();
        weights.setTrainingDate(LocalDate.now());
        
        List<LotteryResult> allResults = numberGuessService.getCachedAllResults();
        weights.setTrainingDataSize(Math.min(dataSize, allResults.size()));
        
        // 캐시에 저장
        cachedWeights = weights;
        lastTrainingDate = LocalDate.now();
        
        log.info("가중치 학습 완료 - 정확도: {}%, 평균 맞춘 개수: {}개", 
            String.format("%.2f", weights.getCvAccuracy() * 100), String.format("%.2f", weights.getCvAverageMatchCount()));
        
        return weights;
    }
    
    /**
     * 예측 수행 (최적 가중치 사용)
     */
    public PredictionResult predict() {
        return predict(null);
    }
    
    /**
     * 예측 수행 (가중치 지정 가능)
     */
    public PredictionResult predict(OptimizedWeights customWeights) {
        List<LotteryResult> allResults = numberGuessService.getCachedAllResults();
        
        if (allResults.isEmpty()) {
            log.warn("예측할 데이터가 없습니다.");
            return null;
        }
        
        // 가중치 결정
        OptimizedWeights weights = customWeights;
        
        if (weights == null) {
            // 캐시된 가중치 사용 또는 기본 가중치 사용 (재학습 비활성화로 성능 최적화)
            if (cachedWeights != null && lastTrainingDate != null && 
                !lastTrainingDate.isBefore(LocalDate.now().minusDays(30))) {
                // 30일 이내면 캐시된 가중치 사용
                weights = cachedWeights;
                log.debug("캐시된 가중치 사용 (학습일: {})", lastTrainingDate);
            } else {
                // 기본 가중치 사용 (재학습 비활성화)
                weights = new OptimizedWeights();
                log.debug("기본 가중치 사용 (재학습 비활성화로 성능 최적화)");
            }
        }
        
        if (weights == null) {
            // 기본 가중치 사용
            weights = new OptimizedWeights();
        }
        
        // 예측 수행
        return predictWithWeights(allResults, weights);
    }
}

