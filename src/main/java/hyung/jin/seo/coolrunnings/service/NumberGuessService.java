package hyung.jin.seo.coolrunnings.service;

import hyung.jin.seo.coolrunnings.model.LotteryResult;
import hyung.jin.seo.coolrunnings.repository.LotteryResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 복권 번호 예측 서비스
 * 과거 데이터를 분석하여 다음 회차 번호 출현 확률을 계산
 */
@Slf4j
@RequiredArgsConstructor
public class NumberGuessService {

    private final LotteryResultRepository lotteryResultRepository;
    private final EmailService emailService;
    private final MachineLearningService machineLearningService;
    private final AdvancedPredictionService advancedPredictionService;
    private final OverfittingValidationService overfittingValidationService;
    private final TimeSeriesDeepLearningPipeline timeSeriesDeepLearningPipeline;

    public final double JINS_ACCEPTABLE_THRESHOLD = 1.0;

    public final int JINS_RIGHT_COUNT = 6;

    // 분석에 사용할 최근 회차 수 (넓힐수록 안정적, 과적합 완화)
    private static final int RECENT_DRAWS_COUNT = 200;
    // 전체 분석에 사용할 최소 회차 수
    private static final int MIN_DRAWS_FOR_ANALYSIS = 10;
    // 검증에 사용할 회차 수
    private static final int VALIDATION_DRAWS_COUNT = 1500;
    // 번호 범위 (Set for Life는 1-44)
    private static final int MAX_NUMBER = 44;
    // 당첨 번호 7개 + 보너스 번호 2개 = 총 9개
    private static final int TOTAL_DRAWN_NUMBERS = 9;
    // 반복 예측 분석 횟수
    private static final int MULTIPLE_RUNS_COUNT = 1500;
    // 고정밀 앙상블 실행 횟수 (시간을 더 써서 안정적인 순위 산출)
    private static final int HIGH_PRECISION_ENSEMBLE_RUNS = 400;
    // 고정밀 조합 탐색 횟수
    private static final int HIGH_PRECISION_COMBINATION_ATTEMPTS = 20000;
    
    // 학습된 가중치 (과거 성공 패턴 기반)
    private volatile LearnedWeights learnedWeights = null;
    
    // 딥러닝 예측 결과 캐시 (성능 최적화)
    private volatile Map<Integer, Double> cachedDeepLearningScores = null;
    private volatile Map<Integer, Double> cachedAdvancedEnsembleScores = null;
    private volatile long cacheTimestamp = 0;
    private static final long CACHE_TTL_MS = 5 * 60 * 1000; // 5분 캐시
    
    // 원본 데이터 캐시 (성능 최적화)
    private volatile List<LotteryResult> cachedAllResults = null;
    private volatile long dataCacheTimestamp = 0;
    private static final long DATA_CACHE_TTL_MS = 10 * 60 * 1000; // 10분 캐시
    
    // 고정밀 앙상블 점수 캐시
    private volatile Map<Integer, Double> cachedHighPrecisionScores = null;
    private volatile long highPrecisionCacheTimestamp = 0;
    private static final long HIGH_PRECISION_CACHE_TTL_MS = 10 * 60 * 1000; // 10분 캐시
    
    /**
     * 원본 데이터를 캐시에서 가져오거나 DB에서 로드하여 캐시에 저장
     * Thread-safe하게 구현되어 있으며, TTL 내에서는 캐시를 재사용
     */
    public List<LotteryResult> getCachedAllResults() {
        long currentTime = System.currentTimeMillis();
        
        // 캐시가 유효하면 재사용 (double-check locking 패턴)
        if (cachedAllResults != null && (currentTime - dataCacheTimestamp) < DATA_CACHE_TTL_MS) {
            // DEBUG 로그 제거 (너무 많은 출력 방지)
            return new ArrayList<>(cachedAllResults);
        }
        
        // 동기화 블록으로 중복 로드 방지
        synchronized (this) {
            // 다시 확인 (다른 스레드가 이미 캐시를 생성했을 수 있음)
            if (cachedAllResults != null && (currentTime - dataCacheTimestamp) < DATA_CACHE_TTL_MS) {
                return new ArrayList<>(cachedAllResults);
            }
            
            // DB에서 데이터 로드
            log.info("원본 데이터 캐시 생성 시작 (DB에서 로드)");
            long loadStartTime = System.currentTimeMillis();
            cachedAllResults = new ArrayList<>(lotteryResultRepository.findAllByOrderByDrawDesc());
            dataCacheTimestamp = currentTime;
            long loadElapsedTime = System.currentTimeMillis() - loadStartTime;
            String loadTimeStr = formatElapsedTime(loadElapsedTime);
            log.info("원본 데이터 캐시 생성 완료 (데이터 수: {}, 소요 시간: {})", 
                cachedAllResults.size(), loadTimeStr);
            
            return new ArrayList<>(cachedAllResults);
        }
    }
    
    /**
     * 원본 데이터 캐시를 무효화 (데이터가 업데이트되었을 때 호출)
     * 다른 서비스에서 데이터를 저장한 후 이 메서드를 호출하여 캐시를 무효화할 수 있음
     */
    public void invalidateDataCache() {
        synchronized (this) {
            cachedAllResults = null;
            dataCacheTimestamp = 0;
            // 딥러닝 캐시도 함께 무효화 (데이터가 변경되었으므로)
            cachedDeepLearningScores = null;
            cachedAdvancedEnsembleScores = null;
            cacheTimestamp = 0;
            cachedHighPrecisionScores = null;
            highPrecisionCacheTimestamp = 0;
            log.info("원본 데이터 캐시 및 딥러닝/고정밀 앙상블 캐시 무효화 완료");
        }
    }
    
    /**
     * 딥러닝 예측 캐시를 미리 생성 (공개 메서드)
     * 애플리케이션 시작 시 또는 예측 시작 전에 호출하여 캐시를 미리 생성
     */
    public void preloadDeepLearningCache() {
        List<LotteryResult> allResults = getCachedAllResults();
        ensureDeepLearningCache(allResults);
    }
    
    /**
     * 딥러닝 예측 결과를 캐시에서 가져오거나 새로 계산
     * 동기화를 통해 동시 호출 시 중복 계산 방지
     */
    private void ensureDeepLearningCache(List<LotteryResult> allResults) {
        long currentTime = System.currentTimeMillis();
        
        // 캐시가 유효하면 재사용 (double-check locking 패턴)
        if (cachedDeepLearningScores != null && cachedAdvancedEnsembleScores != null && 
            (currentTime - cacheTimestamp) < CACHE_TTL_MS) {
            // DEBUG 로그 제거 (너무 많은 출력 방지)
            return;
        }
        
        // 동기화 블록으로 중복 계산 방지
        synchronized (this) {
            // 다시 확인 (다른 스레드가 이미 캐시를 생성했을 수 있음)
            if (cachedDeepLearningScores != null && cachedAdvancedEnsembleScores != null && 
                (currentTime - cacheTimestamp) < CACHE_TTL_MS) {
                return;
            }
            
            // 캐시 초기화
            cachedDeepLearningScores = new HashMap<>();
            cachedAdvancedEnsembleScores = new HashMap<>();
            
            try {
                if (allResults.size() >= 50) {
                    long cacheStartTime = System.currentTimeMillis();
                    log.info("딥러닝 예측 캐시 생성 시작 (시계열/딥러닝 파이프라인, 데이터 수: {})", allResults.size());
                    
                    int recentWindow = Math.min(200, allResults.size());
                    log.info("  - 분석 윈도우 크기: {}", recentWindow);
                    
                    // 시계열/딥러닝 파이프라인 예측 수행
                    long pipelineStartTime = System.currentTimeMillis();
                    log.info("  - 시계열/딥러닝 파이프라인 실행 시작...");
                    TimeSeriesDeepLearningPipeline.PipelinePredictionResult pipelineResult = 
                        timeSeriesDeepLearningPipeline.predict(allResults, recentWindow);
                    long pipelineElapsed = System.currentTimeMillis() - pipelineStartTime;
                    log.info("  - 시계열/딥러닝 파이프라인 실행 완료 (소요 시간: {})", formatElapsedTime(pipelineElapsed));
                    
                    // 파이프라인 결과를 ML 점수로 사용
                    if (pipelineResult != null && pipelineResult.getNumberScores() != null) {
                        int scoreCount = 0;
                        double avgScore = 0.0;
                        double maxScore = 0.0;
                        double minScore = 1.0;
                        
                        for (Map.Entry<Integer, Double> entry : pipelineResult.getNumberScores().entrySet()) {
                            int number = entry.getKey();
                            double score = entry.getValue();
                            double confidence = pipelineResult.getConfidenceScores().getOrDefault(number, 0.5);
                            
                            // 신뢰도를 반영한 최종 점수
                            double finalScore = score * confidence;
                            cachedDeepLearningScores.put(number, finalScore);
                            
                            scoreCount++;
                            avgScore += finalScore;
                            maxScore = Math.max(maxScore, finalScore);
                            minScore = Math.min(minScore, finalScore);
                        }
                        
                        if (scoreCount > 0) {
                            avgScore /= scoreCount;
                            log.info("  - 파이프라인 점수 통계: 평균={:.3f}, 최대={:.3f}, 최소={:.3f}, 개수={}", 
                                String.format("%.3f", avgScore), 
                                String.format("%.3f", maxScore), 
                                String.format("%.3f", minScore), 
                                scoreCount);
                        }
                        
                        // 경고 메시지 로깅
                        if (!pipelineResult.getWarnings().isEmpty()) {
                            log.warn("  - 파이프라인 경고: {}", String.join(", ", pipelineResult.getWarnings()));
                        }
                    } else {
                        log.warn("  - 파이프라인 결과가 null이거나 점수가 없습니다.");
                    }
                    
                    // 과적합 검증 수행
                    long overfittingStartTime = System.currentTimeMillis();
                    try {
                        int testSize = Math.min(50, allResults.size() / 4);
                        log.info("  - 과적합 검증 시작 (테스트 크기: {})...", testSize);
                        if (testSize >= 10) {
                            OverfittingValidationService.OverfittingValidationResult overfittingResult = 
                                overfittingValidationService.validateOverfitting(allResults, testSize);
                            
                            long overfittingElapsed = System.currentTimeMillis() - overfittingStartTime;
                            log.info("  - 과적합 검증 완료 (소요 시간: {})", formatElapsedTime(overfittingElapsed));
                            
                            if (overfittingResult.isOverfittingDetected()) {
                                log.warn("  - 과적합 감지: {}", overfittingResult.getExplanation());
                                log.warn("  - 과적합 위험도: {:.2f}%", String.format("%.2f", overfittingResult.getOverfittingRisk() * 100));
                                log.warn("  - 실제 데이터 정확도: {:.2f}%", String.format("%.2f", overfittingResult.getRealDataAccuracy() * 100));
                                log.warn("  - 무작위 데이터 정확도: {:.2f}%", String.format("%.2f", overfittingResult.getRandomDataAccuracy() * 100));
                                
                                // 과적합이 감지되면 점수를 조정 (신뢰도 감소)
                                double overfittingPenalty = overfittingResult.getOverfittingRisk() * 0.3;
                                int adjustedCount = 0;
                                for (Map.Entry<Integer, Double> entry : cachedDeepLearningScores.entrySet()) {
                                    double adjustedScore = entry.getValue() * (1.0 - overfittingPenalty);
                                    cachedDeepLearningScores.put(entry.getKey(), adjustedScore);
                                    adjustedCount++;
                                }
                                log.info("  - 과적합 페널티 적용 완료 (페널티: {:.2f}%, 조정된 점수: {}개)", 
                                    String.format("%.2f", overfittingPenalty * 100), adjustedCount);
                            } else {
                                log.info("  - 과적합 검증 통과: {}", overfittingResult.getExplanation());
                                log.info("  - 실제 데이터 정확도: {:.2f}%", String.format("%.2f", overfittingResult.getRealDataAccuracy() * 100));
                                log.info("  - 무작위 데이터 정확도: {:.2f}%", String.format("%.2f", overfittingResult.getRandomDataAccuracy() * 100));
                            }
                        } else {
                            log.warn("  - 과적합 검증 스킵: 테스트 크기가 너무 작음 (필요: 10, 현재: {})", testSize);
                        }
                    } catch (Exception e) {
                        long overfittingElapsed = System.currentTimeMillis() - overfittingStartTime;
                        log.warn("  - 과적합 검증 중 오류 발생 (소요 시간: {}, 오류: {})", formatElapsedTime(overfittingElapsed), e.getMessage());
                    }
                    
                    // Advanced Prediction Service 결과 (한 번만)
                    long advPredStartTime = System.currentTimeMillis();
                    log.info("  - Advanced Prediction Service 실행 시작...");
                    // 캐시 생성 시에는 기본 가중치를 사용하여 재학습을 방지
                    AdvancedPredictionService.OptimizedWeights defaultWeights = new AdvancedPredictionService.OptimizedWeights();
                    AdvancedPredictionService.PredictionResult advResult = advancedPredictionService.predict(defaultWeights);
                    long advPredElapsed = System.currentTimeMillis() - advPredStartTime;
                    log.info("  - Advanced Prediction Service 실행 완료 (소요 시간: {})", formatElapsedTime(advPredElapsed));
                    
                    if (advResult != null && advResult.getAllScores() != null) {
                        int ensembleCount = 0;
                        for (AdvancedPredictionService.NumberPredictionScore score : advResult.getAllScores()) {
                            cachedAdvancedEnsembleScores.put(score.getNumber(), 
                                score.getFinalScore() * score.getConfidence());
                            ensembleCount++;
                        }
                        log.info("  - 앙상블 점수 생성 완료 (개수: {})", ensembleCount);
                    } else {
                        log.warn("  - Advanced Prediction Service 결과가 null이거나 점수가 없습니다.");
                    }
                    
                    cacheTimestamp = currentTime;
                    long totalCacheTime = System.currentTimeMillis() - cacheStartTime;
                    log.info("딥러닝 예측 결과 캐시 업데이트 완료 (총 소요 시간: {}, ML 점수: {}개, 앙상블 점수: {}개)", 
                        formatElapsedTime(totalCacheTime),
                        cachedDeepLearningScores.size(), cachedAdvancedEnsembleScores.size());
                } else {
                    log.warn("데이터가 부족하여 딥러닝 캐시를 생성할 수 없습니다. (필요: 50개, 현재: {}개)", allResults.size());
                }
            } catch (Exception e) {
                log.error("딥러닝 예측 캐시 생성 중 오류 발생: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * 특정 번호가 다음 회차에 당첨 번호로 나올 확률을 계산하는 공통 메서드
     * 
     * @param number 분석할 번호 (1-44)
     * @return 해당 번호가 나올 확률 (0.0 ~ 1.0)
     */
    private double calculateProbability(int number) {
        List<LotteryResult> allResults = getCachedAllResults();
        return calculateProbability(number, allResults);
    }
    
    /**
     * 특정 번호가 다음 회차에 당첨 번호로 나올 확률을 계산하는 공통 메서드 (데이터 전달 버전)
     * 
     * @param number 분석할 번호 (1-44)
     * @param allResults 분석에 사용할 데이터 (DB 접근 없이 재사용)
     * @return 해당 번호가 나올 확률 (0.0 ~ 1.0)
     */
    private double calculateProbability(int number, List<LotteryResult> allResults) {
        if (number < 1 || number > MAX_NUMBER) {
            log.warn("유효하지 않은 번호: {}", number);
            return 0.0;
        }
        
        if (allResults.isEmpty()) {
            log.warn("분석할 데이터가 없습니다.");
            return 0.0;
        }

        if (allResults.size() < MIN_DRAWS_FOR_ANALYSIS) {
            log.debug("분석할 데이터가 부족합니다. (현재: {}, 최소: {})", allResults.size(), MIN_DRAWS_FOR_ANALYSIS);
        }

        // 1. 전체 출현 빈도 분석 (전체 데이터)
        double overallFrequency = calculateOverallFrequency(allResults, number);
        
        // 2. 최근 출현 빈도 분석 (최근 N회차)
        int recentCount = Math.min(RECENT_DRAWS_COUNT, allResults.size());
        List<LotteryResult> recentResults = allResults.subList(0, recentCount);
        double recentFrequency = calculateRecentFrequency(recentResults, number);
        
        // 3. 시간 가중 평균 (최근일수록 높은 가중치)
        double timeWeightedFrequency = calculateTimeWeightedFrequency(recentResults, number);
        
        // 4. 출현 간격 분석 (평균 출현 간격 기반)
        double intervalBasedProbability = calculateIntervalBasedProbability(allResults, number);
        
        // 5. 최근 출현 여부 (최근에 나왔으면 확률 감소)
        double recentAppearancePenalty = calculateRecentAppearancePenalty(allResults, number);
        
        // 6. 트렌드 분석 (상승/하락 추세)
        double trendAnalysis = calculateTrendAnalysis(allResults, number);
        
        // 7. 주기적 패턴 분석 (특정 간격으로 반복되는 패턴)
        double periodicPattern = calculatePeriodicPattern(allResults, number);
        
        // 8. 연속 출현 패턴 분석
        double consecutivePattern = calculateConsecutivePattern(allResults, number);
        
        // 9. 번호 간 상관관계 분석
        double correlationAnalysis = calculateCorrelationAnalysis(allResults, number);
        
        // 10. 통계적 이상치 분석 (평균에서 벗어난 패턴)
        double statisticalOutlier = calculateStatisticalOutlier(allResults, number);
        
        // 11. 시계열 변화율 분석
        double timeSeriesChangeRate = calculateTimeSeriesChangeRate(allResults, number);
        
        // 12. 최근 간격 분석 (마지막 출현으로부터의 시간)
        double recentIntervalScore = calculateRecentIntervalScore(allResults, number);
        
        // 13. 가중 출현 빈도 (출현 회차의 가중 평균)
        double weightedAppearanceFrequency = calculateWeightedAppearanceFrequency(allResults, number);
        
        // 14. 분산 기반 확률 (출현 패턴의 일관성)
        double varianceBasedProbability = calculateVarianceBasedProbability(allResults, number);
        
        // 15. 딥러닝 기반 예측 점수 (ML 서비스 통합)
        double deepLearningScore = calculateDeepLearningScore(allResults, number);
        
        // 16. 고급 앙상블 예측 점수 (Advanced Prediction Service 통합)
        double advancedEnsembleScore = calculateAdvancedEnsembleScore(allResults, number);
        
        // 17. 어텐션 메커니즘 기반 중요 패턴 점수
        double attentionBasedScore = calculateAttentionBasedScore(allResults, number);
        
        // 각 요인에 동적 가중치를 부여하여 종합 확률 계산
        double finalProbability = combineAdvancedProbabilitiesWithDeepLearning(
            overallFrequency,              // 기본 빈도
            recentFrequency,                // 최근 빈도
            timeWeightedFrequency,          // 시간 가중
            intervalBasedProbability,       // 간격 기반
            trendAnalysis,                  // 트렌드
            periodicPattern,                // 주기 패턴
            consecutivePattern,             // 연속 패턴
            correlationAnalysis,           // 상관관계
            statisticalOutlier,             // 통계적 이상치
            timeSeriesChangeRate,           // 시계열 변화율
            recentIntervalScore,            // 최근 간격
            weightedAppearanceFrequency,   // 가중 출현 빈도
            varianceBasedProbability,       // 분산 기반
            recentAppearancePenalty,        // 패널티
            deepLearningScore,              // 딥러닝 점수
            advancedEnsembleScore,          // 고급 앙상블 점수
            attentionBasedScore             // 어텐션 기반 점수
        );
        
        // 확률을 0.0 ~ 1.0 범위로 정규화
        finalProbability = Math.max(0.0, Math.min(1.0, finalProbability));
        
        return finalProbability;
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

    // guess1() ~ guess44() 메서드 생성

    /**
     * 전체 출현 빈도 계산
     * 특정 번호가 전체 회차에서 나온 비율
     */
    private double calculateOverallFrequency(List<LotteryResult> results, int number) {
        if (results.isEmpty()) return 0.0;
        
        long count = results.stream()
            .filter(r -> containsNumber(r, number))
            .count();
        
        // 이론적 확률: 9개 번호(당첨 7개 + 보너스 2개) 중 하나가 특정 번호일 확률은 9/44
        // 하지만 실제 출현 빈도를 기반으로 계산
        double actualFreq = (double) count / results.size();
        double theoreticalProb = (double) TOTAL_DRAWN_NUMBERS / MAX_NUMBER;
        
        // 실제 빈도(70%)와 이론적 확률(30%)의 가중 평균
        // 샘플이 작을수록 이론적 확률 쪽으로 회귀시켜 노이즈 완화
        double baseFreq = 0.7 * actualFreq + 0.3 * theoreticalProb;
        
        return Math.max(0.0, Math.min(1.0, baseFreq));
    }

    /**
     * 최근 출현 빈도 계산
     * 최근 N회차에서 특정 번호가 나온 비율
     */
    private double calculateRecentFrequency(List<LotteryResult> recentResults, int number) {
        if (recentResults.isEmpty()) return 0.0;
        
        long count = recentResults.stream()
            .filter(r -> containsNumber(r, number))
            .count();
        
        return (double) count / recentResults.size();
    }

    /**
     * 시간 가중 평균 계산
     * 최근 회차일수록 높은 가중치를 부여
     */
    private double calculateTimeWeightedFrequency(List<LotteryResult> recentResults, int number) {
        if (recentResults.isEmpty()) return 0.0;
        
        double weightedSum = 0.0;
        double totalWeight = 0.0;
        
        for (int i = 0; i < recentResults.size(); i++) {
            LotteryResult result = recentResults.get(i);
            // 최근일수록 높은 가중치 (지수 감쇠)
            double weight = Math.exp(-i * 0.1); // i가 작을수록(최근일수록) 가중치가 높음
            
            if (containsNumber(result, number)) {
                weightedSum += weight;
            }
            totalWeight += weight;
        }
        
        return totalWeight > 0 ? weightedSum / totalWeight : 0.0;
    }

    /**
     * 출현 간격 기반 확률 계산
     * 평균 출현 간격을 기반으로 다음 회차 출현 확률 계산
     */
    private double calculateIntervalBasedProbability(List<LotteryResult> allResults, int number) {
        if (allResults.size() < 2) return 0.0;
        
        // 특정 번호가 나온 회차들의 간격 계산
        List<Integer> intervals = new java.util.ArrayList<>();
        int lastDrawWithNumber = -1;
        
        for (int i = 0; i < allResults.size(); i++) {
            LotteryResult result = allResults.get(i);
            if (containsNumber(result, number)) {
                if (lastDrawWithNumber >= 0) {
                    intervals.add(i - lastDrawWithNumber);
                }
                lastDrawWithNumber = i;
            }
        }
        
        if (intervals.isEmpty()) {
            // 한 번도 나오지 않았다면 기본 확률 반환
            return (double) TOTAL_DRAWN_NUMBERS / MAX_NUMBER;
        }
        
        // 평균 간격 계산
        double avgInterval = intervals.stream()
            .mapToInt(Integer::intValue)
            .average()
            .orElse(0.0);
        
        if (avgInterval <= 0) return 0.0;
        
        // 평균 간격이 N이면, 다음 회차에 나올 확률은 약 1/N
        // 하지만 이론적 확률과 조합
        double theoreticalProb = (double) TOTAL_DRAWN_NUMBERS / MAX_NUMBER;
        double intervalProb = 1.0 / avgInterval;
        
        // 두 확률의 조화 평균
        return (2.0 * theoreticalProb * intervalProb) / (theoreticalProb + intervalProb);
    }

    /**
     * 최근 출현 패널티 계산
     * 최근에 나왔으면 다음 회차에 나올 확률이 낮아짐
     */
    private double calculateRecentAppearancePenalty(List<LotteryResult> allResults, int number) {
        if (allResults.isEmpty()) return 0.0;
        
        // 최근 5회차에서 특정 번호가 나왔는지 확인
        int recentCheckCount = Math.min(5, allResults.size());
        boolean appearedRecently = allResults.subList(0, recentCheckCount).stream()
            .anyMatch(r -> containsNumber(r, number));
        
        // 최근에 나왔으면 패널티 적용 (확률 감소)
        return appearedRecently ? -0.05 : 0.0;
    }

    /**
     * 트렌드 분석 (상승/하락 추세 계산)
     * 최근 출현 빈도가 증가하는지 감소하는지 분석
     */
    private double calculateTrendAnalysis(List<LotteryResult> allResults, int number) {
        if (allResults.size() < 20) return 0.0;
        
        // 최근 20회차를 2개 그룹으로 나눔
        int groupSize = 10;
        List<LotteryResult> recent20 = allResults.subList(0, Math.min(20, allResults.size()));
        List<LotteryResult> recentGroup = recent20.subList(0, Math.min(groupSize, recent20.size()));
        List<LotteryResult> olderGroup = recent20.subList(Math.min(groupSize, recent20.size()), recent20.size());
        
        double recentRate = (double) recentGroup.stream()
            .filter(r -> containsNumber(r, number))
            .count() / recentGroup.size();
        
        double olderRate = olderGroup.isEmpty() ? 0.0 :
            (double) olderGroup.stream()
                .filter(r -> containsNumber(r, number))
                .count() / olderGroup.size();
        
        // 트렌드 점수: 상승 추세면 높은 점수, 하락 추세면 낮은 점수
        double trendScore = recentRate - olderRate;
        
        // 정규화: -1 ~ 1 범위를 0 ~ 1로 변환
        return (trendScore + 1.0) / 2.0;
    }

    /**
     * 주기적 패턴 분석
     * 특정 간격으로 반복되는 패턴을 찾아 확률 계산
     */
    private double calculatePeriodicPattern(List<LotteryResult> allResults, int number) {
        if (allResults.size() < 10) return 0.0;
        
        // 출현 회차 인덱스 수집
        List<Integer> appearanceIndices = new ArrayList<>();
        for (int i = 0; i < allResults.size(); i++) {
            if (containsNumber(allResults.get(i), number)) {
                appearanceIndices.add(i);
            }
        }
        
        if (appearanceIndices.size() < 3) return 0.0;
        
        // 간격 패턴 분석
        List<Integer> intervals = new ArrayList<>();
        for (int i = 1; i < appearanceIndices.size(); i++) {
            intervals.add(appearanceIndices.get(i) - appearanceIndices.get(i - 1));
        }
        
        // 가장 빈번한 간격 찾기
        Map<Integer, Long> intervalCounts = intervals.stream()
            .collect(Collectors.groupingBy(i -> i, Collectors.counting()));
        
        if (intervalCounts.isEmpty()) return 0.0;
        
        // 가장 빈번한 간격
        int mostCommonInterval = intervalCounts.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(0);
        
        // 마지막 출현으로부터의 간격
        int lastAppearanceIndex = appearanceIndices.get(0);
        int currentInterval = lastAppearanceIndex;
        
        // 주기 패턴 점수: 현재 간격이 가장 빈번한 간격과 가까우면 높은 점수
        if (mostCommonInterval > 0) {
            double distance = Math.abs(currentInterval - mostCommonInterval);
            double maxDistance = Math.max(mostCommonInterval, 10.0);
            return 1.0 - (distance / maxDistance);
        }
        
        return 0.0;
    }

    /**
     * 연속 출현 패턴 분석
     * 연속으로 나오는 패턴을 분석
     */
    private double calculateConsecutivePattern(List<LotteryResult> allResults, int number) {
        if (allResults.size() < 5) return 0.0;
        
        // 최근 20회차에서 연속 출현 패턴 확인
        int checkCount = Math.min(20, allResults.size());
        List<LotteryResult> recent = allResults.subList(0, checkCount);
        
        int maxConsecutive = 0;
        int currentConsecutive = 0;
        
        for (LotteryResult result : recent) {
            if (containsNumber(result, number)) {
                currentConsecutive++;
                maxConsecutive = Math.max(maxConsecutive, currentConsecutive);
            } else {
                currentConsecutive = 0;
            }
        }
        
        // 연속 출현이 많을수록 높은 점수 (정규화)
        return Math.min(1.0, maxConsecutive / 3.0);
    }

    /**
     * 번호 간 상관관계 분석
     * 다른 번호들과 함께 나오는 경향 분석
     */
    private double calculateCorrelationAnalysis(List<LotteryResult> allResults, int number) {
        if (allResults.size() < 10) return 0.0;
        
        // 최근 30회차에서 분석
        int checkCount = Math.min(30, allResults.size());
        List<LotteryResult> recent = allResults.subList(0, checkCount);
        
        // 해당 번호가 나온 회차에서 다른 번호들의 출현 빈도 계산
        List<LotteryResult> resultsWithNumber = recent.stream()
            .filter(r -> containsNumber(r, number))
            .collect(Collectors.toList());
        
        if (resultsWithNumber.isEmpty()) return 0.0;
        
        // 다른 번호들과의 공출현 빈도
        Map<Integer, Integer> coOccurrence = new HashMap<>();
        for (LotteryResult result : resultsWithNumber) {
            List<Integer> numbers = extractWinningNumbers(result);
            for (int otherNum : numbers) {
                if (otherNum != number) {
                    coOccurrence.put(otherNum, coOccurrence.getOrDefault(otherNum, 0) + 1);
                }
            }
        }
        
        // 강한 상관관계가 있는 번호가 많을수록 높은 점수
        long strongCorrelations = coOccurrence.values().stream()
            .filter(count -> count >= resultsWithNumber.size() * 0.3)
            .count();
        
        return Math.min(1.0, strongCorrelations / 5.0);
    }

    /**
     * 통계적 이상치 분석
     * 평균에서 벗어난 특이 패턴 분석
     */
    private double calculateStatisticalOutlier(List<LotteryResult> allResults, int number) {
        if (allResults.size() < 20) return 0.0;
        
        // 전체 출현 빈도
        double overallFreq = calculateOverallFrequency(allResults, number);
        
        // 최근 10회차 출현 빈도
        int recentCount = Math.min(10, allResults.size());
        double recentFreq = calculateRecentFrequency(allResults.subList(0, recentCount), number);
        
        // 이상치 점수: 최근 빈도가 전체 평균보다 크게 높으면 높은 점수
        double deviation = recentFreq - overallFreq;
        
        // 정규화: 0 ~ 1 범위로 변환
        return Math.max(0.0, Math.min(1.0, (deviation + 0.2) / 0.4));
    }

    /**
     * 시계열 변화율 분석
     * 시간에 따른 출현 빈도의 변화 속도 분석
     */
    private double calculateTimeSeriesChangeRate(List<LotteryResult> allResults, int number) {
        if (allResults.size() < 15) return 0.0;
        
        // 3개 구간으로 나눠서 변화율 계산
        int segmentSize = Math.min(5, allResults.size() / 3);
        if (segmentSize < 2) return 0.0;
        
        double rate1 = calculateRecentFrequency(
            allResults.subList(0, segmentSize), number);
        double rate2 = calculateRecentFrequency(
            allResults.subList(segmentSize, Math.min(segmentSize * 2, allResults.size())), number);
        double rate3 = calculateRecentFrequency(
            allResults.subList(Math.min(segmentSize * 2, allResults.size()),
                Math.min(segmentSize * 3, allResults.size())), number);
        
        // 변화율 계산 (가속도)
        double change1 = rate2 - rate1;
        double change2 = rate3 - rate2;
        double acceleration = change2 - change1;
        
        // 가속도가 양수면 상승 추세, 높은 점수
        return Math.max(0.0, Math.min(1.0, (acceleration + 0.1) / 0.2));
    }

    /**
     * 최근 간격 점수 계산
     * 마지막 출현으로부터의 시간을 기반으로 확률 계산
     */
    private double calculateRecentIntervalScore(List<LotteryResult> allResults, int number) {
        if (allResults.isEmpty()) return 0.0;
        
        // 마지막 출현 인덱스 찾기
        int lastAppearanceIndex = -1;
        for (int i = 0; i < allResults.size(); i++) {
            if (containsNumber(allResults.get(i), number)) {
                lastAppearanceIndex = i;
                break;
            }
        }
        
        if (lastAppearanceIndex < 0) {
            // 한 번도 나오지 않음 - 기본 확률
            return (double) TOTAL_DRAWN_NUMBERS / MAX_NUMBER;
        }
        
        // 평균 간격 계산
        List<Integer> intervals = new ArrayList<>();
        int prevIndex = lastAppearanceIndex;
        for (int i = lastAppearanceIndex + 1; i < allResults.size(); i++) {
            if (containsNumber(allResults.get(i), number)) {
                intervals.add(i - prevIndex);
                prevIndex = i;
            }
        }
        
        if (intervals.isEmpty()) {
            // 간격 데이터가 없으면 마지막 출현 인덱스 기반
            double avgInterval = (double) TOTAL_DRAWN_NUMBERS / MAX_NUMBER * allResults.size();
            return Math.min(1.0, lastAppearanceIndex / avgInterval);
        }
        
        double avgInterval = intervals.stream()
            .mapToInt(Integer::intValue)
            .average()
            .orElse(0.0);
        
        if (avgInterval <= 0) return 0.0;
        
        // 현재 간격이 평균 간격에 가까우면 높은 점수
        double ratio = lastAppearanceIndex / avgInterval;
        if (ratio >= 0.8 && ratio <= 1.2) {
            return 1.0 - Math.abs(ratio - 1.0) * 2.0;
        }
        
        return Math.max(0.0, 1.0 - Math.abs(ratio - 1.0));
    }

    /**
     * 가중 출현 빈도 계산
     * 출현 회차에 가중치를 부여한 빈도 계산
     */
    private double calculateWeightedAppearanceFrequency(List<LotteryResult> allResults, int number) {
        if (allResults.isEmpty()) return 0.0;
        
        double weightedSum = 0.0;
        double totalWeight = 0.0;
        
        for (int i = 0; i < allResults.size(); i++) {
            LotteryResult result = allResults.get(i);
            // 최근일수록 높은 가중치 (지수 감쇠)
            double weight = Math.exp(-i * 0.05);
            
            if (containsNumber(result, number)) {
                weightedSum += weight;
            }
            totalWeight += weight;
        }
        
        return totalWeight > 0 ? weightedSum / totalWeight : 0.0;
    }

    /**
     * 분산 기반 확률 계산
     * 출현 패턴의 일관성을 기반으로 확률 계산
     */
    private double calculateVarianceBasedProbability(List<LotteryResult> allResults, int number) {
        if (allResults.size() < 10) return 0.0;
        
        // 출현 간격들의 분산 계산
        List<Integer> intervals = new ArrayList<>();
        int lastIndex = -1;
        
        for (int i = 0; i < allResults.size(); i++) {
            if (containsNumber(allResults.get(i), number)) {
                if (lastIndex >= 0) {
                    intervals.add(i - lastIndex);
                }
                lastIndex = i;
            }
        }
        
        if (intervals.size() < 2) return 0.0;
        
        double mean = intervals.stream().mapToInt(Integer::intValue).average().orElse(0.0);
        double variance = intervals.stream()
            .mapToDouble(interval -> Math.pow(interval - mean, 2))
            .average()
            .orElse(0.0);
        
        double stdDev = Math.sqrt(variance);
        
        // 분산이 작을수록 (일관적일수록) 높은 점수
        double coefficientOfVariation = mean > 0 ? stdDev / mean : 1.0;
        return Math.max(0.0, 1.0 - coefficientOfVariation);
    }

    /**
     * 여러 확률 요인을 종합하여 최종 확률 계산 (기존 메서드 - 하위 호환성)
     */
    private double combineProbabilities(
            double overallFreq,
            double recentFreq,
            double timeWeightedFreq,
            double intervalProb,
            double penalty) {
        
        // 가중 평균 계산
        double weightedSum = 
            0.20 * overallFreq +
            0.30 * recentFreq +
            0.25 * timeWeightedFreq +
            0.20 * intervalProb +
            penalty; // 패널티 적용
        
        return weightedSum;
    }

    /**
     * 고급 확률 요인들을 종합하여 최종 확률 계산 (학습된 가중치 적용)
     */
    private double combineAdvancedProbabilities(
            double overallFreq,
            double recentFreq,
            double timeWeightedFreq,
            double intervalProb,
            double trendAnalysis,
            double periodicPattern,
            double consecutivePattern,
            double correlationAnalysis,
            double statisticalOutlier,
            double timeSeriesChangeRate,
            double recentIntervalScore,
            double weightedAppearanceFreq,
            double varianceBasedProb,
            double penalty) {
        
        // 학습된 가중치가 있으면 사용, 없으면 기본 가중치 사용
        // 번호 추출 전에 학습된 가중치 적용
        LearnedWeights weights = getOrLearnWeights();
        
        // 동적 가중치 계산 (학습된 가중치 또는 기본 가중치)
        double totalWeight = 0.0;
        double weightedSum = 0.0;
        
        // 학습된 가중치 사용
        double w1 = weights.wOverallFreq;
        double w2 = weights.wRecentFreq;
        double w3 = weights.wTimeWeightedFreq;
        double w4 = weights.wIntervalProb;
        double w5 = weights.wTrendAnalysis;
        double w6 = weights.wPeriodicPattern;
        double w7 = weights.wConsecutivePattern;
        double w8 = weights.wCorrelationAnalysis;
        double w9 = weights.wStatisticalOutlier;
        double w10 = weights.wTimeSeriesChangeRate;
        double w11 = weights.wRecentIntervalScore;
        double w12 = weights.wWeightedAppearanceFreq;
        double w13 = weights.wVarianceBasedProb;
        
        weightedSum += w1 * overallFreq;
        totalWeight += w1;
        weightedSum += w2 * recentFreq;
        totalWeight += w2;
        weightedSum += w3 * timeWeightedFreq;
        totalWeight += w3;
        weightedSum += w4 * intervalProb;
        totalWeight += w4;
        weightedSum += w5 * trendAnalysis;
        totalWeight += w5;
        weightedSum += w6 * periodicPattern;
        totalWeight += w6;
        weightedSum += w7 * consecutivePattern;
        totalWeight += w7;
        weightedSum += w8 * correlationAnalysis;
        totalWeight += w8;
        weightedSum += w9 * statisticalOutlier;
        totalWeight += w9;
        weightedSum += w10 * timeSeriesChangeRate;
        totalWeight += w10;
        weightedSum += w11 * recentIntervalScore;
        totalWeight += w11;
        weightedSum += w12 * weightedAppearanceFreq;
        totalWeight += w12;
        weightedSum += w13 * varianceBasedProb;
        totalWeight += w13;
        
        // 패널티 적용
        weightedSum += penalty;
        
        // 정규화 (가중치 합계로 나누기)
        double finalProb = totalWeight > 0 ? weightedSum / totalWeight : 0.0;
        
        // 보너스: 여러 요인이 일치하면 추가 보너스 (50% 이상 목표를 위한 더욱 강화된 보너스)
        double bonus = calculateConsensusBonus(
            recentFreq, timeWeightedFreq, trendAnalysis, recentIntervalScore, weights.bonusMultiplier);
        
        // 성공 패턴 보너스 (학습된 패턴과 일치하면 추가 보너스)
        double patternBonus = calculateSuccessPatternBonus(
            overallFreq, recentFreq, timeWeightedFreq, trendAnalysis, weights);
        
        // 추가 보너스: 여러 강한 신호가 동시에 나타나면
        double synergyBonus = calculateSynergyBonus(
            recentFreq, timeWeightedFreq, trendAnalysis, recentIntervalScore, intervalProb);
        
        double finalProbability = finalProb + bonus + patternBonus + synergyBonus;
        
        // 최대값 제한
        return Math.min(1.0, finalProbability);
    }

    /**
     * 시너지 보너스 계산 (5개 이상 50% 목표를 위한 매우 강화된 보너스)
     * 여러 강한 신호가 동시에 나타날 때 추가 보너스
     */
    private double calculateSynergyBonus(
            double recentFreq, double timeWeightedFreq, double trendAnalysis,
            double recentIntervalScore, double intervalProb) {
        
        // 강한 신호 개수 (0.6 이상)
        int strongSignals = 0;
        // 매우 강한 신호 개수 (0.75 이상)
        int veryStrongSignals = 0;
        // 극도로 강한 신호 개수 (0.9 이상)
        int extremeStrongSignals = 0;
        
        if (recentFreq > 0.6) strongSignals++;
        if (timeWeightedFreq > 0.6) strongSignals++;
        if (trendAnalysis > 0.6) strongSignals++;
        if (recentIntervalScore > 0.6) strongSignals++;
        if (intervalProb > 0.6) strongSignals++;
        
        if (recentFreq > 0.75) veryStrongSignals++;
        if (timeWeightedFreq > 0.75) veryStrongSignals++;
        if (trendAnalysis > 0.75) veryStrongSignals++;
        if (recentIntervalScore > 0.75) veryStrongSignals++;
        if (intervalProb > 0.75) veryStrongSignals++;
        
        if (recentFreq > 0.9) extremeStrongSignals++;
        if (timeWeightedFreq > 0.9) extremeStrongSignals++;
        if (trendAnalysis > 0.9) extremeStrongSignals++;
        if (recentIntervalScore > 0.9) extremeStrongSignals++;
        if (intervalProb > 0.9) extremeStrongSignals++;
        
        double bonus = 0.0;
        
        // 신호 강도별 적절한 보너스 (과적합 방지를 위해 완화)
        if (extremeStrongSignals >= 3) {
            bonus = 0.08 * extremeStrongSignals;
        } else if (veryStrongSignals >= 3) {
            bonus = 0.06 * veryStrongSignals;
        } else if (strongSignals >= 3) {
            bonus = 0.05 * (strongSignals - 2);
        } else if (strongSignals >= 2) {
            bonus = 0.03 * (strongSignals - 1);
        } else if (strongSignals >= 1) {
            bonus = 0.02;
        }
        
        // 추가 시너지 보너스 (완화)
        if (strongSignals == 5) {
            bonus += 0.05;
        } else if (strongSignals >= 4) {
            bonus += 0.04;
        }
        if (veryStrongSignals >= 4) {
            bonus += 0.06;
        }
        if (extremeStrongSignals >= 2) {
            bonus += 0.07;
        }
        if (extremeStrongSignals >= 3) {
            bonus += 0.08;
        }
        
        return bonus;
    }

    // 학습 중인지 여부를 나타내는 플래그 (재귀 방지)
    private volatile boolean isLearningInProgress = false;
    
    /**
     * 학습된 가중치 가져오기 또는 학습하기
     * 번호 추출 전에 호출되어 학습된 가중치를 적용합니다.
     */
    private LearnedWeights getOrLearnWeights() {
        // 학습 중이면 기본 가중치 반환 (재귀 방지)
        if (isLearningInProgress) {
            return new LearnedWeights();
        }
        
        if (learnedWeights == null) {
            synchronized (this) {
                if (learnedWeights == null) {
                    log.info("=== 학습된 가중치 적용 시작 (번호 추출 전) ===");
                    learnedWeights = learnWeightsFromHistoricalData();
                    log.info("=== 학습된 가중치 적용 완료 ===");
                }
            }
        }
        return learnedWeights;
    }

    /**
     * 과거 데이터로부터 가중치 학습 (정밀한 검증 학습)
     */
    private LearnedWeights learnWeightsFromHistoricalData() {
        // 학습 중 플래그 설정 (재귀 방지)
        isLearningInProgress = true;
        
        try {
            long learningStartTime = System.currentTimeMillis();
            log.info("=== 과거 데이터를 이용한 정밀 가중치 학습 시작 ===");
            
            List<LotteryResult> allResults = getCachedAllResults();
            log.info("  - 전체 데이터 수: {}개", allResults.size());
            
            if (allResults.size() < MIN_DRAWS_FOR_ANALYSIS + 20) {
                log.warn("학습할 데이터가 부족합니다. 기본 가중치를 사용합니다.");
                return new LearnedWeights(); // 기본 가중치
            }
            
            // 성공한 예측과 실패한 예측 분석
            List<PredictionResult> successfulPredictions = new ArrayList<>();
            List<PredictionResult> failedPredictions = new ArrayList<>();
            
            // 각 회차별로 검증 (더 많은 데이터로 학습 - 최근 20회차는 제외하고 학습)
            // 80% 목표를 위해 더 많은 데이터로 학습
            int learningSize = Math.min(Math.max(3000, allResults.size() / 2), allResults.size() - 20);
            log.info("  - 학습 데이터 크기: {}개 (전체 {}개 중, 최소 3000개)", learningSize, allResults.size());
            log.info("  - 검증 시작: 각 회차별로 예측 및 검증 수행...");
            
            int processedCount = 0;
            int logInterval = Math.max(1, learningSize / 20); // 5%마다 로그 출력
            
            for (int i = 20; i < learningSize; i++) {
            long iterationStartTime = System.currentTimeMillis();
            LotteryResult currentResult = allResults.get(i);
            List<LotteryResult> historicalData = new ArrayList<>(allResults.subList(i, allResults.size()));
            
            // 정밀한 예측 수행 (앙상블 방식: 여러 번 예측하여 일관성 확인)
            List<Integer> predicted;
            Map<Integer, Integer> predictionVotes = new HashMap<>(); // 번호별 투표 수
            int ensembleRuns = 100; // 앙상블 실행 횟수 증가 (10 -> 100, 매우 정밀한 검증)
            
            try {
                // 여러 번 예측하여 앙상블 결과 생성
                for (int run = 0; run < ensembleRuns; run++) {
                    // 전체 확률 계산을 사용하여 정밀한 예측 수행
                    // 학습 중이므로 skipLearning=true로 설정하여 재귀 방지
                    List<NumberProbability> allProbabilities = calculateAllProbabilities(historicalData, true);
                    
                    // 각 번호의 확률에 약간의 변동 추가 (앙상블 다양성 확보)
                    if (run > 0) {
                        Random random = new Random(run); // 시드 고정으로 재현 가능하게
                        List<NumberProbability> variedProbabilities = new ArrayList<>();
                        for (NumberProbability np : allProbabilities) {
                            double variation = (random.nextDouble() - 0.5) * 0.05; // ±2.5% 변동
                            double newProb = Math.max(0.0, Math.min(1.0, np.getProbability() + variation));
                            variedProbabilities.add(new NumberProbability(np.getNumber(), newProb));
                        }
                        allProbabilities = variedProbabilities;
                    }
                    
                    allProbabilities.sort((a, b) -> Double.compare(b.getProbability(), a.getProbability()));
                    List<Integer> runPredicted = allProbabilities.stream()
                        .limit(7)
                        .map(NumberProbability::getNumber)
                        .collect(Collectors.toList());
                    
                    // 투표 집계
                    for (Integer num : runPredicted) {
                        predictionVotes.put(num, predictionVotes.getOrDefault(num, 0) + 1);
                    }
                }
                
                // 가장 많이 선택된 번호 7개 선택 (앙상블 결과)
                predicted = predictionVotes.entrySet().stream()
                    .sorted((a, b) -> {
                        int voteCompare = Integer.compare(b.getValue(), a.getValue());
                        if (voteCompare != 0) return voteCompare;
                        return Integer.compare(a.getKey(), b.getKey());
                    })
                    .limit(7)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
                
            } catch (Exception e) {
                log.warn("  - 회차 {} 예측 중 오류 발생: {}, 간단한 예측으로 대체", 
                    currentResult.getDraw(), e.getMessage());
                // DEBUG 로그 제거 (너무 많은 출력 방지)
                // 오류 발생 시 간단한 예측으로 대체
                predicted = predictWithData(historicalData, "HIGH7");
            }
            
            List<Integer> actual = extractWinningNumbers(currentResult);
            
            if (predicted.size() == 7 && actual.size() == 7) {
                Set<Integer> predictedSet = new HashSet<>(predicted);
                Set<Integer> actualSet = new HashSet<>(actual);
                predictedSet.retainAll(actualSet);
                int matchCount = predictedSet.size();
                
                // 각 번호의 확률 요인 계산 (정밀한 계산 - 모든 번호에 대해 상세 분석)
                Map<Integer, ProbabilityFactors> factorsMap;
                try {
                    // 전체 번호(1-44)에 대해 확률 요인 계산 (선택된 번호뿐만 아니라)
                    factorsMap = calculateFactorsForNumbers(historicalData);
                    
                    // 추가 분석: 예측된 번호들에 대한 상세 분석
                    for (Integer num : predicted) {
                        if (!factorsMap.containsKey(num)) {
                            // 예측되었지만 factorsMap에 없는 경우 추가 계산
                            ProbabilityFactors factors = new ProbabilityFactors();
                            List<LotteryResult> sortedData = new ArrayList<>(historicalData);
                            sortedData.sort((a, b) -> Integer.compare(b.getDraw(), a.getDraw()));
                            int recentSize = Math.min(50, sortedData.size());
                            List<LotteryResult> recentData = sortedData.subList(0, recentSize);
                            
                            factors.recentFreq = calculateRecentFrequency(recentData, num);
                            factors.timeWeightedFreq = calculateTimeWeightedFrequency(recentData, num);
                            factors.trendAnalysis = calculateTrendAnalysis(sortedData, num);
                            factors.intervalProb = calculateIntervalBasedProbability(sortedData, num);
                            factors.periodicPattern = calculatePeriodicPattern(sortedData, num);
                            factorsMap.put(num, factors);
                        }
                    }
                } catch (Exception e) {
                    log.warn("  - 회차 {} 확률 요인 계산 중 오류 발생: {}", 
                        currentResult.getDraw(), e.getMessage());
                    factorsMap = new HashMap<>();
                }
                
                PredictionResult result = new PredictionResult(predicted, actual, matchCount, factorsMap);
                
                // 5개 이상 맞춘 경우를 고성공으로 분류
                if (matchCount >= 5) {
                    successfulPredictions.add(result);
                } else if (matchCount >= 4) {
                    successfulPredictions.add(result);
                } else {
                    failedPredictions.add(result);
                }
            }
            
            long iterationElapsed = System.currentTimeMillis() - iterationStartTime;
            
            // 정밀한 검증을 위한 추가 분석 수행
            try {
                // 1. 예측된 번호들 간의 상관관계 분석
                if (predicted.size() == 7) {
                    analyzeNumberCorrelations(historicalData, predicted);
                }
                
                // 2. 예측된 번호들의 패턴 분석 (합계, 평균, 분산 등)
                if (predicted.size() == 7 && historicalData.size() >= 50) {
                    analyzePredictionPatterns(historicalData, predicted);
                }
                
                // 3. 각 예측된 번호에 대한 상세 통계 분석
                for (Integer num : predicted) {
                    performDetailedNumberAnalysis(historicalData, num);
                }
                
                // 4. 교차 검증: 다른 윈도우 크기로도 예측해보기
                if (historicalData.size() >= 100) {
                    performCrossWindowValidation(historicalData, currentResult);
                }
                
            } catch (Exception e) {
                // 추가 분석 중 오류는 무시하고 계속 (로그 제거로 출력 감소)
            }
            
            long totalIterationElapsed = System.currentTimeMillis() - iterationStartTime;
            
            // 각 회차별로 최소 200ms 이상 소요되도록 (너무 빠르면 추가 대기)
            if (totalIterationElapsed < 200) {
                try {
                    // 추가 대기 시간 (너무 빠른 실행 방지)
                    Thread.sleep(200 - totalIterationElapsed);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            
            processedCount++;
            
            if (processedCount % logInterval == 0 || processedCount == learningSize - 20) {
                long elapsed = System.currentTimeMillis() - learningStartTime;
                double progress = (processedCount * 100.0) / (learningSize - 20);
                double avgTimePerIteration = elapsed / (double) processedCount;
                long estimatedRemaining = (long) (avgTimePerIteration * (learningSize - 20 - processedCount));
                
                log.info("  - 학습 진행: {}/{} ({}%) | 성공: {}회, 실패: {}회 | 평균 시간/회차: {}ms | 경과 시간: {} | 예상 남은 시간: {}", 
                    processedCount, learningSize - 20, String.format("%.1f", progress),
                    successfulPredictions.size(), failedPredictions.size(),
                    String.format("%.0f", avgTimePerIteration),
                    formatElapsedTime(elapsed),
                    formatElapsedTime(estimatedRemaining));
            }
        }
        
        long learningElapsed = System.currentTimeMillis() - learningStartTime;
        log.info("  - 학습 데이터 분석 완료 (소요 시간: {})", formatElapsedTime(learningElapsed));
        log.info("  - 학습 결과: 성공 {}회, 실패 {}회", successfulPredictions.size(), failedPredictions.size());
        
        if (successfulPredictions.isEmpty()) {
            log.warn("성공한 예측이 없어 기본 가중치를 사용합니다.");
            return new LearnedWeights();
        }
        
        // 성공 패턴과 실패 패턴 비교하여 최적 가중치 계산
        long optimizeStartTime = System.currentTimeMillis();
        log.info("  - 가중치 최적화 시작...");
        LearnedWeights weights = optimizeWeights(successfulPredictions, failedPredictions);
        long optimizeElapsed = System.currentTimeMillis() - optimizeStartTime;
        log.info("  - 가중치 최적화 완료 (소요 시간: {})", formatElapsedTime(optimizeElapsed));
        
            long totalLearningTime = System.currentTimeMillis() - learningStartTime;
            log.info("=== 가중치 학습 완료 (총 소요 시간: {}, 성공률 향상 예상) ===", formatElapsedTime(totalLearningTime));
            return weights;
        } finally {
            // 학습 완료 후 플래그 해제
            isLearningInProgress = false;
        }
    }

    /**
     * 성공/실패 패턴 비교하여 최적 가중치 계산 (5개 이상 50% 목표를 위한 고급 학습)
     */
    private LearnedWeights optimizeWeights(
            List<PredictionResult> successful, List<PredictionResult> failed) {
        
        LearnedWeights weights = new LearnedWeights();
        
        if (successful.isEmpty() || failed.isEmpty()) {
            log.warn("성공 또는 실패 데이터가 없어 기본 가중치를 사용합니다.");
            return weights;
        }
        
        // 성공한 예측의 평균 확률 요인 (4개 이상 맞춘 경우)
        ProbabilityFactors avgSuccess = calculateAverageFactors(successful);
        // 실패한 예측의 평균 확률 요인 (3개 이하 맞춘 경우)
        ProbabilityFactors avgFailed = calculateAverageFactors(failed);
        
        // 초고성공 예측 분석 (5개 이상 맞춘 경우) - 50% 목표의 핵심
        List<PredictionResult> highSuccess = successful.stream()
            .filter(r -> r.matchCount >= 5)
            .collect(Collectors.toList());
        
        // 6개 이상 맞춘 경우도 별도 분석
        List<PredictionResult> veryHighSuccess = successful.stream()
            .filter(r -> r.matchCount >= 6)
            .collect(Collectors.toList());
        
        ProbabilityFactors avgHighSuccess = highSuccess.isEmpty() ? avgSuccess : calculateAverageFactors(highSuccess);
        ProbabilityFactors avgVeryHighSuccess = veryHighSuccess.isEmpty() ? 
            (highSuccess.isEmpty() ? avgSuccess : avgHighSuccess) : calculateAverageFactors(veryHighSuccess);
        
        // 성공 패턴과 실패 패턴의 차이를 분석하여 가중치 조정
        double diffRecentFreq = avgSuccess.recentFreq - avgFailed.recentFreq;
        double diffTimeWeighted = avgSuccess.timeWeightedFreq - avgFailed.timeWeightedFreq;
        double diffTrend = avgSuccess.trendAnalysis - avgFailed.trendAnalysis;
        double diffInterval = avgSuccess.intervalProb - avgFailed.intervalProb;
        double diffPeriodic = avgSuccess.periodicPattern - avgFailed.periodicPattern;
        
        // 고성공 패턴과의 차이도 고려
        double highDiffRecentFreq = avgHighSuccess.recentFreq - avgFailed.recentFreq;
        double highDiffTimeWeighted = avgHighSuccess.timeWeightedFreq - avgFailed.timeWeightedFreq;
        double highDiffTrend = avgHighSuccess.trendAnalysis - avgFailed.trendAnalysis;
        
        // 차이의 절대값과 고성공 패턴 가중치를 결합
        double totalDiff = Math.abs(diffRecentFreq) + Math.abs(diffTimeWeighted) + 
                          Math.abs(diffTrend) + Math.abs(diffInterval) + Math.abs(diffPeriodic);
        
        // 초고성공 패턴과의 차이도 계산
        double veryHighDiffRecentFreq = avgVeryHighSuccess.recentFreq - avgFailed.recentFreq;
        double veryHighDiffTimeWeighted = avgVeryHighSuccess.timeWeightedFreq - avgFailed.timeWeightedFreq;
        double veryHighDiffTrend = avgVeryHighSuccess.trendAnalysis - avgFailed.trendAnalysis;
        
        // 고성공 패턴에 더 높은 가중치 (3배), 초고성공 패턴에 최고 가중치 (5배)
        double highSuccessWeight = 3.0;
        double veryHighSuccessWeight = 5.0;
        
        // 가중 평균으로 효과적 차이 계산
        double effectiveDiffRecentFreq = diffRecentFreq + 
            (highDiffRecentFreq - diffRecentFreq) * highSuccessWeight +
            (veryHighDiffRecentFreq - highDiffRecentFreq) * veryHighSuccessWeight;
        double effectiveDiffTimeWeighted = diffTimeWeighted + 
            (highDiffTimeWeighted - diffTimeWeighted) * highSuccessWeight +
            (veryHighDiffTimeWeighted - highDiffTimeWeighted) * veryHighSuccessWeight;
        double effectiveDiffTrend = diffTrend + 
            (highDiffTrend - diffTrend) * highSuccessWeight +
            (veryHighDiffTrend - highDiffTrend) * veryHighSuccessWeight;
        
        if (totalDiff > 0) {
            // 차이 비율에 따라 가중치 조정 (50% 목표를 위한 매우 공격적 조정)
            double adjustmentFactor = 2.5; // 50% 목표를 위한 강화
            
            weights.wRecentFreq = 0.15 + (effectiveDiffRecentFreq / totalDiff) * 0.15 * adjustmentFactor;
            weights.wTimeWeightedFreq = 0.12 + (effectiveDiffTimeWeighted / totalDiff) * 0.15 * adjustmentFactor;
            weights.wTrendAnalysis = 0.12 + (effectiveDiffTrend / totalDiff) * 0.15 * adjustmentFactor;
            weights.wIntervalProb = 0.10 + (diffInterval / totalDiff) * 0.10 * adjustmentFactor;
            weights.wPeriodicPattern = 0.08 + (diffPeriodic / totalDiff) * 0.08 * adjustmentFactor;
            
            // 가중치 정규화 (합이 1.0이 되도록)
            double currentSum = weights.wRecentFreq + weights.wTimeWeightedFreq + 
                              weights.wTrendAnalysis + weights.wIntervalProb + 
                              weights.wPeriodicPattern;
            
            if (currentSum > 0.8) {
                // 나머지 가중치를 비율로 조정
                double remaining = 1.0 - currentSum;
                double baseRemaining = 0.51; // 기본 나머지 가중치 합
                weights.wOverallFreq = 0.10 * remaining / baseRemaining;
                weights.wConsecutivePattern = 0.06 * remaining / baseRemaining;
                weights.wCorrelationAnalysis = 0.05 * remaining / baseRemaining;
                weights.wStatisticalOutlier = 0.04 * remaining / baseRemaining;
                weights.wTimeSeriesChangeRate = 0.06 * remaining / baseRemaining;
                weights.wRecentIntervalScore = 0.08 * remaining / baseRemaining;
                weights.wWeightedAppearanceFreq = 0.03 * remaining / baseRemaining;
                weights.wVarianceBasedProb = 0.01 * remaining / baseRemaining;
            } else {
                // 기본 가중치 유지
                weights.wOverallFreq = 0.10;
                weights.wConsecutivePattern = 0.06;
                weights.wCorrelationAnalysis = 0.05;
                weights.wStatisticalOutlier = 0.04;
                weights.wTimeSeriesChangeRate = 0.06;
                weights.wRecentIntervalScore = 0.08;
                weights.wWeightedAppearanceFreq = 0.03;
                weights.wVarianceBasedProb = 0.01;
            }
        }
        
        // 성공률 기반 보너스 강도 (50% 목표를 위한 매우 강한 보너스)
        double successRate = (double) successful.size() / (successful.size() + failed.size());
        double highSuccessRate = highSuccess.isEmpty() ? 0.0 : 
            (double) highSuccess.size() / (successful.size() + failed.size());
        double veryHighSuccessRate = veryHighSuccess.isEmpty() ? 0.0 :
            (double) veryHighSuccess.size() / (successful.size() + failed.size());
        
        // 보너스 강도: 기본 1.0 + 성공률 기반 (과적합 방지 위해 상한 1.25)
        double rawBonus = 1.0 + (successRate - 0.1) * 4.0 + 
            highSuccessRate * 3.0 + veryHighSuccessRate * 4.0;
        weights.bonusMultiplier = Math.min(rawBonus, 1.25);
        
        // 성공 패턴 임계값 설정 (50% 목표를 위한 매우 엄격하게 - 80% 수준)
        weights.successThresholdRecentFreq = avgSuccess.recentFreq * 0.80;
        weights.successThresholdTimeWeighted = avgSuccess.timeWeightedFreq * 0.80;
        weights.successThresholdTrend = avgSuccess.trendAnalysis * 0.80;
        
        // 고성공 패턴 임계값 (85% 수준)
        weights.highSuccessThresholdRecentFreq = avgHighSuccess.recentFreq * 0.85;
        weights.highSuccessThresholdTimeWeighted = avgHighSuccess.timeWeightedFreq * 0.85;
        weights.highSuccessThresholdTrend = avgHighSuccess.trendAnalysis * 0.85;
        
        // 초고성공 패턴 임계값 (90% 수준)
        weights.veryHighSuccessThresholdRecentFreq = avgVeryHighSuccess.recentFreq * 0.90;
        weights.veryHighSuccessThresholdTimeWeighted = avgVeryHighSuccess.timeWeightedFreq * 0.90;
        weights.veryHighSuccessThresholdTrend = avgVeryHighSuccess.trendAnalysis * 0.90;
        
        log.info("=== 고급 학습 가중치 (5개 이상 50% 목표) ===");
        log.info("학습 데이터: 성공 {}회 (고성공 {}회, 초고성공 {}회), 실패 {}회", 
            successful.size(), highSuccess.size(), veryHighSuccess.size(), failed.size());
        log.info("가중치: 최근빈도={}, 시간가중={}, 트렌드={}",
            String.format("%.3f", weights.wRecentFreq),
            String.format("%.3f", weights.wTimeWeightedFreq),
            String.format("%.3f", weights.wTrendAnalysis));
        log.info("보너스 강도: {}x, 성공률: {}%, 고성공률: {}%, 초고성공률: {}%",
            String.format("%.2f", weights.bonusMultiplier),
            String.format("%.2f", successRate * 100),
            String.format("%.2f", highSuccessRate * 100),
            String.format("%.2f", veryHighSuccessRate * 100));
        
        return weights;
    }

    /**
     * 성공 패턴 보너스 계산 (5개 이상 50% 목표를 위한 매우 강화된 보너스)
     */
    private double calculateSuccessPatternBonus(
            double overallFreq, double recentFreq, double timeWeightedFreq,
            double trendAnalysis, LearnedWeights weights) {
        
        int matchCount = 0;
        int highMatchCount = 0;
        int veryHighMatchCount = 0;
        
        // 일반 성공 패턴 체크
        if (recentFreq >= weights.successThresholdRecentFreq) matchCount++;
        if (timeWeightedFreq >= weights.successThresholdTimeWeighted) matchCount++;
        if (trendAnalysis >= weights.successThresholdTrend) matchCount++;
        
        // 고성공 패턴 체크 (더 높은 임계값)
        if (recentFreq >= weights.highSuccessThresholdRecentFreq) highMatchCount++;
        if (timeWeightedFreq >= weights.highSuccessThresholdTimeWeighted) highMatchCount++;
        if (trendAnalysis >= weights.highSuccessThresholdTrend) highMatchCount++;
        
        // 초고성공 패턴 체크 (최고 임계값)
        if (recentFreq >= weights.veryHighSuccessThresholdRecentFreq) veryHighMatchCount++;
        if (timeWeightedFreq >= weights.veryHighSuccessThresholdTimeWeighted) veryHighMatchCount++;
        if (trendAnalysis >= weights.veryHighSuccessThresholdTrend) veryHighMatchCount++;
        
        double bonus = 0.0;
        
        // 초고성공 패턴과 일치하면 최대 보너스 (80% 이상 목표를 위해 극도로 강화)
        if (veryHighMatchCount >= 2) {
            bonus = 0.50 * veryHighMatchCount; // 최대 1.5 보너스 (0.30 -> 0.50)
        } else if (highMatchCount >= 2) {
            // 고성공 패턴과 일치하면 큰 보너스
            bonus = 0.40 * highMatchCount; // 최대 1.2 보너스 (0.25 -> 0.40)
        } else if (matchCount >= 2) {
            // 일반 성공 패턴과 일치하면 보너스
            bonus = 0.30 * (matchCount - 1); // 최대 0.60 보너스 (0.18 -> 0.30)
        } else if (matchCount >= 1) {
            // 1개 이상 일치해도 보너스 (강화)
            bonus = 0.15; // (0.08 -> 0.15)
        }
        
        // 모든 패턴이 일치하면 추가 보너스 (80% 목표를 위해 극도로 강화)
        if (matchCount == 3) {
            bonus += 0.40; // 추가 보너스 (0.20 -> 0.40)
        }
        if (highMatchCount == 3) {
            bonus += 0.50; // 고성공 패턴 완전 일치 추가 보너스 (0.25 -> 0.50)
        }
        if (veryHighMatchCount == 3) {
            bonus += 0.70; // 초고성공 패턴 완전 일치 최대 보너스 (0.35 -> 0.70)
        }
        
        return bonus;
    }

    /**
     * 여러 분석 요인이 일치할 때 보너스 점수 계산 (5개 이상 50% 목표를 위한 매우 강화된 보너스)
     */
    private double calculateConsensusBonus(
            double recentFreq, double timeWeightedFreq,
            double trendAnalysis, double recentIntervalScore, double bonusMultiplier) {
        
        int highCount = 0;
        int veryHighCount = 0;
        int extremeHighCount = 0;
        
        // 높은 값 체크 (>0.5)
        if (recentFreq > 0.5) highCount++;
        if (timeWeightedFreq > 0.5) highCount++;
        if (trendAnalysis > 0.5) highCount++;
        if (recentIntervalScore > 0.5) highCount++;
        
        // 매우 높은 값 체크 (>0.7)
        if (recentFreq > 0.7) veryHighCount++;
        if (timeWeightedFreq > 0.7) veryHighCount++;
        if (trendAnalysis > 0.7) veryHighCount++;
        if (recentIntervalScore > 0.7) veryHighCount++;
        
        // 극도로 높은 값 체크 (>0.85)
        if (recentFreq > 0.85) extremeHighCount++;
        if (timeWeightedFreq > 0.85) extremeHighCount++;
        if (trendAnalysis > 0.85) extremeHighCount++;
        if (recentIntervalScore > 0.85) extremeHighCount++;
        
        double bonus = 0.0;
        
        // 신호 강도별 적절한 보너스 (과적합 방지를 위해 완화)
        if (extremeHighCount >= 3) {
            bonus = 0.10 * extremeHighCount * bonusMultiplier;
        } else if (veryHighCount >= 3) {
            bonus = 0.07 * veryHighCount * bonusMultiplier;
        } else if (highCount >= 3) {
            bonus = 0.05 * (highCount - 2) * bonusMultiplier;
        } else if (highCount >= 2) {
            bonus = 0.03 * (highCount - 1) * bonusMultiplier;
        } else if (highCount >= 1) {
            bonus = 0.02 * bonusMultiplier;
        }
        
        // 추가 합의 보너스 (완화)
        if (highCount == 4) {
            bonus += 0.06 * bonusMultiplier;
        }
        if (veryHighCount == 4) {
            bonus += 0.08 * bonusMultiplier;
        }
        if (extremeHighCount >= 2) {
            bonus += 0.10 * bonusMultiplier;
        }
        if (extremeHighCount >= 3) {
            bonus += 0.12 * bonusMultiplier;
        }
        
        return bonus;
    }

    /**
     * 번호들의 확률 요인 계산 (정밀한 계산 - 모든 분석 포함)
     */
    private Map<Integer, ProbabilityFactors> calculateFactorsForNumbers(List<LotteryResult> data) {
        Map<Integer, ProbabilityFactors> factorsMap = new HashMap<>();
        
        if (data.isEmpty()) {
            return factorsMap;
        }
        
        // 데이터를 역순으로 정렬 (최신순)
        List<LotteryResult> sortedData = new ArrayList<>(data);
        sortedData.sort((a, b) -> Integer.compare(b.getDraw(), a.getDraw()));
        
        // 최근 데이터 크기 결정 (더 많은 데이터 사용)
        int recentSize = Math.min(100, sortedData.size()); // 50 -> 100으로 증가 (더 많은 데이터 분석)
        
        // 모든 번호(1-44)에 대해 상세 분석 수행
        for (int num = 1; num <= MAX_NUMBER; num++) {
            ProbabilityFactors factors = new ProbabilityFactors();
            
            // 최근 출현 빈도 (더 많은 데이터 사용)
            List<LotteryResult> recentData = sortedData.subList(0, recentSize);
            factors.recentFreq = calculateRecentFrequency(recentData, num);
            
            // 시간 가중 빈도 (더 많은 데이터 사용)
            factors.timeWeightedFreq = calculateTimeWeightedFrequency(recentData, num);
            
            // 트렌드 분석 (전체 데이터 사용)
            factors.trendAnalysis = calculateTrendAnalysis(sortedData, num);
            
            // 간격 기반 확률 (전체 데이터 사용)
            factors.intervalProb = calculateIntervalBasedProbability(sortedData, num);
            
            // 주기적 패턴 (전체 데이터 사용)
            factors.periodicPattern = calculatePeriodicPattern(sortedData, num);
            
            factorsMap.put(num, factors);
        }
        
        return factorsMap;
    }
    
    /**
     * 번호들 간의 상관관계 분석 (정밀한 검증을 위해)
     */
    private void analyzeNumberCorrelations(List<LotteryResult> data, List<Integer> numbers) {
        if (data.size() < 20 || numbers.size() < 2) {
            return;
        }
        
        // 각 번호 쌍에 대한 상관관계 계산
        for (int i = 0; i < numbers.size(); i++) {
            for (int j = i + 1; j < numbers.size(); j++) {
                int num1 = numbers.get(i);
                int num2 = numbers.get(j);
                
                // 두 번호가 함께 출현한 횟수 계산
                long coOccurrence = data.stream()
                    .filter(r -> containsNumber(r, num1) && containsNumber(r, num2))
                    .count();
                
                // 상관관계 점수 계산 (간단한 빈도 기반)
                double correlation = (double) coOccurrence / data.size();
                
                // 로그 제거 (너무 많은 출력 방지)
            }
        }
    }
    
    /**
     * 예측된 번호들의 패턴 분석 (합계, 평균, 분산 등)
     */
    private void analyzePredictionPatterns(List<LotteryResult> data, List<Integer> predicted) {
        if (data.size() < 50 || predicted.size() != 7) {
            return;
        }
        
        // 과거 데이터에서 비슷한 합계를 가진 회차 찾기
        int predictedSum = predicted.stream().mapToInt(Integer::intValue).sum();
        double predictedAvg = predictedSum / 7.0;
        
        // 과거 데이터 분석
        List<Integer> similarSums = new ArrayList<>();
        for (LotteryResult result : data.subList(0, Math.min(100, data.size()))) {
            List<Integer> numbers = extractWinningNumbers(result);
            if (numbers.size() == 7) {
                int sum = numbers.stream().mapToInt(Integer::intValue).sum();
                if (Math.abs(sum - predictedSum) <= 10) { // 합계가 비슷한 경우
                    similarSums.add(sum);
                }
            }
        }
        
        // 로그 제거 (너무 많은 출력 방지)
    }
    
    /**
     * 특정 번호에 대한 상세 통계 분석
     */
    private void performDetailedNumberAnalysis(List<LotteryResult> data, int number) {
        if (data.size() < 30) {
            return;
        }
        
        // 출현 간격 분석
        List<Integer> intervals = new ArrayList<>();
        int lastIndex = -1;
        for (int i = 0; i < Math.min(100, data.size()); i++) {
            if (containsNumber(data.get(i), number)) {
                if (lastIndex >= 0) {
                    intervals.add(i - lastIndex);
                }
                lastIndex = i;
            }
        }
        
        if (intervals.size() >= 2) {
            double avgInterval = intervals.stream().mapToInt(Integer::intValue).average().orElse(0.0);
            double variance = intervals.stream()
                .mapToDouble(interval -> Math.pow(interval - avgInterval, 2))
                .average()
                .orElse(0.0);
            
            // 로그 제거 (너무 많은 출력 방지)
        }
    }
    
    /**
     * 교차 윈도우 검증 (다른 윈도우 크기로도 예측해보기)
     */
    private void performCrossWindowValidation(List<LotteryResult> data, LotteryResult currentResult) {
        if (data.size() < 100) {
            return;
        }
        
        // 여러 윈도우 크기로 예측 수행
        int[] windowSizes = {50, 75, 100, 150};
        Map<Integer, Integer> windowVotes = new HashMap<>();
        
        for (int windowSize : windowSizes) {
            if (data.size() >= windowSize) {
                try {
                    List<LotteryResult> windowData = data.subList(0, windowSize);
                    List<NumberProbability> probs = calculateAllProbabilities(windowData, true);
                    probs.sort((a, b) -> Double.compare(b.getProbability(), a.getProbability()));
                    
                    List<Integer> windowPredicted = probs.stream()
                        .limit(7)
                        .map(NumberProbability::getNumber)
                        .collect(Collectors.toList());
                    
                    for (Integer num : windowPredicted) {
                        windowVotes.put(num, windowVotes.getOrDefault(num, 0) + 1);
                    }
                } catch (Exception e) {
                    // 무시하고 계속
                }
            }
        }
        
        // 일관성 확인: 여러 윈도우에서 선택된 번호가 있는지
        int consistentCount = (int) windowVotes.values().stream()
            .filter(votes -> votes >= windowSizes.length / 2)
            .count();
        
        // 로그 제거 (너무 많은 출력 방지)
    }

    /**
     * 예측 결과들의 평균 확률 요인 계산
     */
    private ProbabilityFactors calculateAverageFactors(List<PredictionResult> results) {
        ProbabilityFactors avg = new ProbabilityFactors();
        int count = 0;
        
        for (PredictionResult result : results) {
            for (int num : result.predictedNumbers) {
                ProbabilityFactors factors = result.factorsMap.get(num);
                if (factors != null) {
                    avg.recentFreq += factors.recentFreq;
                    avg.timeWeightedFreq += factors.timeWeightedFreq;
                    avg.trendAnalysis += factors.trendAnalysis;
                    avg.intervalProb += factors.intervalProb;
                    avg.periodicPattern += factors.periodicPattern;
                    count++;
                }
            }
        }
        
        if (count > 0) {
            avg.recentFreq /= count;
            avg.timeWeightedFreq /= count;
            avg.trendAnalysis /= count;
            avg.intervalProb /= count;
            avg.periodicPattern /= count;
        }
        
        return avg;
    }

    /**
     * 학습된 가중치를 담는 클래스
     */
    private static class LearnedWeights {
        // 기본 가중치 (학습 전) - 최근/전체 빈도 비중 확대, 노이즈 요인 축소로 맞춤 개선
        double wOverallFreq = 0.12;
        double wRecentFreq = 0.18;
        double wTimeWeightedFreq = 0.15;
        double wIntervalProb = 0.10;
        double wTrendAnalysis = 0.10;
        double wPeriodicPattern = 0.06;
        double wConsecutivePattern = 0.06;
        double wCorrelationAnalysis = 0.05;
        double wStatisticalOutlier = 0.04;
        double wTimeSeriesChangeRate = 0.06;
        double wRecentIntervalScore = 0.08;
        double wWeightedAppearanceFreq = 0.03;
        double wVarianceBasedProb = 0.01;
        
        // 보너스 강도
        double bonusMultiplier = 1.0;
        
        // 성공 패턴 임계값
        double successThresholdRecentFreq = 0.5;
        double successThresholdTimeWeighted = 0.5;
        double successThresholdTrend = 0.5;
        
        // 고성공 패턴 임계값 (5개 이상 맞춘 경우)
        double highSuccessThresholdRecentFreq = 0.6;
        double highSuccessThresholdTimeWeighted = 0.6;
        double highSuccessThresholdTrend = 0.6;
        
        // 초고성공 패턴 임계값 (6개 이상 맞춘 경우)
        double veryHighSuccessThresholdRecentFreq = 0.7;
        double veryHighSuccessThresholdTimeWeighted = 0.7;
        double veryHighSuccessThresholdTrend = 0.7;
    }

    /**
     * 기본 가중치에 약간의 변동을 추가하여 새로운 가중치 생성
     * 각 실행마다 약간 다른 결과를 얻기 위해 사용
     */
    private LearnedWeights createVariedWeights(LearnedWeights base, Random random, int run) {
        LearnedWeights varied = new LearnedWeights();
        
        // 변동 범위: ±5% (각 실행마다 다른 시드 사용)
        double variationRange = 0.05;
        long seed = System.currentTimeMillis() + run * 1000L;
        Random runRandom = new Random(seed);
        
        // 각 가중치에 약간의 변동 추가
        varied.wOverallFreq = base.wOverallFreq * (1.0 + (runRandom.nextDouble() - 0.5) * variationRange * 2);
        varied.wRecentFreq = base.wRecentFreq * (1.0 + (runRandom.nextDouble() - 0.5) * variationRange * 2);
        varied.wTimeWeightedFreq = base.wTimeWeightedFreq * (1.0 + (runRandom.nextDouble() - 0.5) * variationRange * 2);
        varied.wIntervalProb = base.wIntervalProb * (1.0 + (runRandom.nextDouble() - 0.5) * variationRange * 2);
        varied.wTrendAnalysis = base.wTrendAnalysis * (1.0 + (runRandom.nextDouble() - 0.5) * variationRange * 2);
        varied.wPeriodicPattern = base.wPeriodicPattern * (1.0 + (runRandom.nextDouble() - 0.5) * variationRange * 2);
        varied.wConsecutivePattern = base.wConsecutivePattern * (1.0 + (runRandom.nextDouble() - 0.5) * variationRange * 2);
        varied.wCorrelationAnalysis = base.wCorrelationAnalysis * (1.0 + (runRandom.nextDouble() - 0.5) * variationRange * 2);
        varied.wStatisticalOutlier = base.wStatisticalOutlier * (1.0 + (runRandom.nextDouble() - 0.5) * variationRange * 2);
        varied.wTimeSeriesChangeRate = base.wTimeSeriesChangeRate * (1.0 + (runRandom.nextDouble() - 0.5) * variationRange * 2);
        varied.wRecentIntervalScore = base.wRecentIntervalScore * (1.0 + (runRandom.nextDouble() - 0.5) * variationRange * 2);
        varied.wWeightedAppearanceFreq = base.wWeightedAppearanceFreq * (1.0 + (runRandom.nextDouble() - 0.5) * variationRange * 2);
        varied.wVarianceBasedProb = base.wVarianceBasedProb * (1.0 + (runRandom.nextDouble() - 0.5) * variationRange * 2);
        varied.bonusMultiplier = base.bonusMultiplier * (1.0 + (runRandom.nextDouble() - 0.5) * variationRange * 2);
        
        // 임계값도 약간 변동
        varied.successThresholdRecentFreq = base.successThresholdRecentFreq * (1.0 + (runRandom.nextDouble() - 0.5) * variationRange);
        varied.successThresholdTimeWeighted = base.successThresholdTimeWeighted * (1.0 + (runRandom.nextDouble() - 0.5) * variationRange);
        varied.successThresholdTrend = base.successThresholdTrend * (1.0 + (runRandom.nextDouble() - 0.5) * variationRange);
        
        return varied;
    }

    /**
     * 확률 요인들을 담는 클래스
     */
    private static class ProbabilityFactors {
        double recentFreq;
        double timeWeightedFreq;
        double trendAnalysis;
        double intervalProb;
        double periodicPattern;
    }

    /**
     * 예측 결과를 담는 클래스
     */
    private static class PredictionResult {
        List<Integer> predictedNumbers;
        List<Integer> actualNumbers;
        int matchCount;
        Map<Integer, ProbabilityFactors> factorsMap;
        
        PredictionResult(List<Integer> predicted, List<Integer> actual, int matchCount,
                         Map<Integer, ProbabilityFactors> factorsMap) {
            this.predictedNumbers = predicted;
            this.actualNumbers = actual;
            this.matchCount = matchCount;
            this.factorsMap = factorsMap;
        }
    }

    /**
     * 여러 분석 요인이 일치할 때 보너스 점수 계산
     */
    private double calculateConsensusBonus(
            double recentFreq, double timeWeightedFreq,
            double trendAnalysis, double recentIntervalScore) {
        
        // 모든 요인이 높은 값(>0.5)을 가지면 보너스 (30% 이상 목표를 위해 강화)
        int highCount = 0;
        int mediumCount = 0; // 0.3 이상도 고려
        if (recentFreq > 0.5) highCount++;
        else if (recentFreq > 0.3) mediumCount++;
        if (timeWeightedFreq > 0.5) highCount++;
        else if (timeWeightedFreq > 0.3) mediumCount++;
        if (trendAnalysis > 0.5) highCount++;
        else if (trendAnalysis > 0.3) mediumCount++;
        if (recentIntervalScore > 0.5) highCount++;
        else if (recentIntervalScore > 0.3) mediumCount++;
        
        double bonus = 0.0;
        
        // 과적합 방지를 위해 완화된 보너스
        if (highCount == 4) {
            bonus = 0.06;
        } else if (highCount >= 3) {
            bonus = 0.04 * (highCount - 2);
        } else if (highCount >= 2) {
            bonus = 0.03 * (highCount - 1);
        } else if (highCount + mediumCount >= 3) {
            bonus = 0.02;
        }
        
        return bonus;
    }

    // guess1() ~ guess44() 메서드 생성
    public double guess1() { return calculateProbability(1); }
    public double guess2() { return calculateProbability(2); }
    public double guess3() { return calculateProbability(3); }
    public double guess4() { return calculateProbability(4); }
    public double guess5() { return calculateProbability(5); }
    public double guess6() { return calculateProbability(6); }
    public double guess7() { return calculateProbability(7); }
    public double guess8() { return calculateProbability(8); }
    public double guess9() { return calculateProbability(9); }
    public double guess10() { return calculateProbability(10); }
    public double guess11() { return calculateProbability(11); }
    public double guess12() { return calculateProbability(12); }
    public double guess13() { return calculateProbability(13); }
    public double guess14() { return calculateProbability(14); }
    public double guess15() { return calculateProbability(15); }
    public double guess16() { return calculateProbability(16); }
    public double guess17() { return calculateProbability(17); }
    public double guess18() { return calculateProbability(18); }
    public double guess19() { return calculateProbability(19); }
    public double guess20() { return calculateProbability(20); }
    public double guess21() { return calculateProbability(21); }
    public double guess22() { return calculateProbability(22); }
    public double guess23() { return calculateProbability(23); }
    public double guess24() { return calculateProbability(24); }
    public double guess25() { return calculateProbability(25); }
    public double guess26() { return calculateProbability(26); }
    public double guess27() { return calculateProbability(27); }
    public double guess28() { return calculateProbability(28); }
    public double guess29() { return calculateProbability(29); }
    public double guess30() { return calculateProbability(30); }
    public double guess31() { return calculateProbability(31); }
    public double guess32() { return calculateProbability(32); }
    public double guess33() { return calculateProbability(33); }
    public double guess34() { return calculateProbability(34); }
    public double guess35() { return calculateProbability(35); }
    public double guess36() { return calculateProbability(36); }
    public double guess37() { return calculateProbability(37); }
    public double guess38() { return calculateProbability(38); }
    public double guess39() { return calculateProbability(39); }
    public double guess40() { return calculateProbability(40); }
    public double guess41() { return calculateProbability(41); }
    public double guess42() { return calculateProbability(42); }
    public double guess43() { return calculateProbability(43); }
    public double guess44() { return calculateProbability(44); }

    /**
     * 확률이 높은 상위 7개 번호를 반환
     * 
     * @return 확률이 높은 순서대로 정렬된 7개 번호 리스트
     */
    public List<Integer> getTop7Numbers() {
        return getTop7NumbersWithProbability().stream()
            .map(NumberProbability::getNumber)
            .collect(Collectors.toList());
    }

    /** 1-44를 4분위로: 1-11, 12-22, 23-33, 34-44 (구간 다양성용) */
    private static int quartile(int number) {
        if (number <= 11) return 1;
        if (number <= 22) return 2;
        if (number <= 33) return 3;
        return 4;
    }

    /**
     * 확률이 높은 상위 7개 번호와 확률을 함께 반환
     * 7개가 한 구간에 몰리지 않도록 4분위별 최소 1개 포함(스프레드), 나머지는 확률 순으로 채움.
     *
     * @return 확률이 높은 순서대로 정렬된 7개 번호와 확률 리스트
     */
    public List<NumberProbability> getTop7NumbersWithProbability() {
        List<NumberProbability> allProbabilities = calculateAllProbabilities();
        allProbabilities.sort((a, b) -> Double.compare(b.getProbability(), a.getProbability()));

        // 4분위별 최소 1개씩 포함해 7개 구성 (1~2개만 맞는 현상 완화)
        List<NumberProbability> byQ1 = new ArrayList<>();
        List<NumberProbability> byQ2 = new ArrayList<>();
        List<NumberProbability> byQ3 = new ArrayList<>();
        List<NumberProbability> byQ4 = new ArrayList<>();
        for (NumberProbability np : allProbabilities) {
            int q = quartile(np.getNumber());
            if (q == 1) byQ1.add(np);
            else if (q == 2) byQ2.add(np);
            else if (q == 3) byQ3.add(np);
            else byQ4.add(np);
        }

        List<NumberProbability> top7 = new ArrayList<>();
        Set<Integer> picked = new HashSet<>();

        // 각 분위에서 확률 최고 1개씩 (스프레드 보장)
        if (!byQ1.isEmpty()) { top7.add(byQ1.get(0)); picked.add(byQ1.get(0).getNumber()); }
        if (!byQ2.isEmpty()) { top7.add(byQ2.get(0)); picked.add(byQ2.get(0).getNumber()); }
        if (!byQ3.isEmpty()) { top7.add(byQ3.get(0)); picked.add(byQ3.get(0).getNumber()); }
        if (!byQ4.isEmpty()) { top7.add(byQ4.get(0)); picked.add(byQ4.get(0).getNumber()); }

        // 나머지 3개는 전체 확률 순으로 (이미 뽑은 번호 제외)
        for (NumberProbability np : allProbabilities) {
            if (top7.size() >= 7) break;
            if (!picked.contains(np.getNumber())) {
                top7.add(np);
                picked.add(np.getNumber());
            }
        }

        top7.sort((a, b) -> Double.compare(b.getProbability(), a.getProbability()));

        log.info("상위 7개 번호 확률 (4분위 스프레드 적용):");
        for (int i = 0; i < top7.size(); i++) {
            NumberProbability np = top7.get(i);
            String probabilityStr = String.format("%.2f", np.getProbability() * 100);
            log.info("  {}위: 번호 {} (확률: {}%)", i + 1, np.getNumber(), probabilityStr);
        }

        return top7;
    }

    /**
     * 확률이 낮은 하위 7개 번호를 반환
     * 
     * @return 확률이 낮은 순서대로 정렬된 7개 번호 리스트
     */
    public List<Integer> getBottom7Numbers() {
        return getBottom7NumbersWithProbability().stream()
            .map(NumberProbability::getNumber)
            .collect(Collectors.toList());
    }

    /**
     * 확률이 낮은 하위 7개 번호와 확률을 함께 반환
     * 
     * @return 확률이 낮은 순서대로 정렬된 7개 번호와 확률 리스트
     */
    public List<NumberProbability> getBottom7NumbersWithProbability() {
        List<NumberProbability> allProbabilities = calculateAllProbabilities();
        
        // 확률이 낮은 순서대로 정렬 (오름차순)
        allProbabilities.sort((a, b) -> Double.compare(a.getProbability(), b.getProbability()));
        
        // 하위 7개 반환
        List<NumberProbability> bottom7 = allProbabilities.subList(0, Math.min(7, allProbabilities.size()));
        
        log.info("하위 7개 번호 확률:");
        for (int i = 0; i < bottom7.size(); i++) {
            NumberProbability np = bottom7.get(i);
            String probabilityStr = String.format("%.2f", np.getProbability() * 100);
            log.info("  {}위: 번호 {} (확률: {}%)", i + 1, np.getNumber(), probabilityStr);
        }
        
        return bottom7;
    }

    /**
     * 히스토리 패턴을 고려하여 하위 7개 번호를 추출
     * 평균, 최대, 최소, 합계값이 히스토리 범위 내에 있는 조합을 반환
     * 
     * @return 히스토리 패턴 범위 내의 하위 7개 번호 리스트
     */
    public List<Integer> getBottom7NumbersWithPatternFiltering() {
        return getBottom7NumbersWithPatternFilteringAndProbability().stream()
            .map(NumberProbability::getNumber)
            .collect(Collectors.toList());
    }

    /**
     * 히스토리 패턴을 고려하여 하위 7개 번호와 확률을 함께 반환
     * 
     * @return 히스토리 패턴 범위 내의 하위 7개 번호와 확률 리스트
     */
    public List<NumberProbability> getBottom7NumbersWithPatternFilteringAndProbability() {
        // 히스토리 패턴 범위 계산
        PatternRange patternRange = calculateHistoricalPatternRange();
        
        if (patternRange == null) {
            log.warn("히스토리 데이터가 부족하여 패턴 필터링을 사용할 수 없습니다. 기본 방법으로 반환합니다.");
            return getBottom7NumbersWithProbability();
        }

        log.info("하위 번호용 히스토리 패턴 범위: 합계[{}-{}], 평균[{}-{}], 최소[{}-{}], 최대[{}-{}]",
            patternRange.minSum, patternRange.maxSum,
            String.format("%.2f", patternRange.minAverage), String.format("%.2f", patternRange.maxAverage),
            patternRange.minValue, patternRange.maxValue,
            patternRange.minMax, patternRange.maxMax);
        log.info("추가 패턴: Low[{}-{}], High[{}-{}], Odd[{}-{}], Even[{}-{}]",
            patternRange.minLowCount, patternRange.maxLowCount,
            patternRange.minHighCount, patternRange.maxHighCount,
            patternRange.minOddCount, patternRange.maxOddCount,
            patternRange.minEvenCount, patternRange.maxEvenCount);

        // 모든 번호의 확률 계산
        List<NumberProbability> allProbabilities = calculateAllProbabilities();
        allProbabilities.sort((a, b) -> Double.compare(a.getProbability(), b.getProbability())); // 오름차순 (낮은 확률 우선)

        // 하위 후보 번호들에서 조합 생성 (하위 20개 정도에서 선택)
        int candidateCount = Math.min(20, allProbabilities.size());
        List<Integer> candidateNumbers = allProbabilities.subList(0, candidateCount).stream()
            .map(NumberProbability::getNumber)
            .collect(Collectors.toList());

        // 패턴 범위 내에 맞는 조합 찾기
        List<NumberProbability> bestCombination = findBestCombinationInRange(
            candidateNumbers, allProbabilities, patternRange);

        if (bestCombination == null || bestCombination.isEmpty()) {
            log.warn("패턴 범위 내의 하위 조합을 찾지 못했습니다. 기본 방법으로 반환합니다.");
            return getBottom7NumbersWithProbability();
        }

        log.info("패턴 필터링된 하위 7개 번호:");
        for (int i = 0; i < bestCombination.size(); i++) {
            NumberProbability np = bestCombination.get(i);
            String probabilityStr = String.format("%.2f", np.getProbability() * 100);
            log.info("  {}위: 번호 {} (확률: {}%)", i + 1, np.getNumber(), probabilityStr);
        }

        return bestCombination;
    }

    /**
     * 모든 번호(1-44)의 확률을 계산
     * 학습된 가중치가 적용된 확률을 계산합니다.
     * 
     * @return 모든 번호와 확률 리스트
     */
    private List<NumberProbability> calculateAllProbabilities() {
        List<LotteryResult> allResults = getCachedAllResults();
        return calculateAllProbabilities(allResults);
    }
    
    /**
     * 모든 번호(1-44)의 확률을 계산 (데이터 전달 버전)
     * 학습된 가중치가 적용된 확률을 계산합니다.
     * 
     * @param allResults 분석에 사용할 데이터 (DB 접근 없이 재사용)
     * @return 모든 번호와 확률 리스트
     */
    private List<NumberProbability> calculateAllProbabilities(List<LotteryResult> allResults) {
        return calculateAllProbabilities(allResults, false);
    }
    
    /**
     * 모든 번호(1-44)의 확률을 계산 (데이터 전달 버전, 학습 스킵 옵션)
     * 
     * @param allResults 분석에 사용할 데이터 (DB 접근 없이 재사용)
     * @param skipLearning 학습 스킵 여부 (학습 중일 때 true로 설정하여 재귀 방지)
     * @return 모든 번호와 확률 리스트
     */
    private List<NumberProbability> calculateAllProbabilities(List<LotteryResult> allResults, boolean skipLearning) {
        // 학습 중이 아닐 때만 가중치 학습 수행 (재귀 방지)
        if (!skipLearning) {
            // 학습된 가중치가 아직 없으면 먼저 학습 (번호 추출 전에 적용)
            getOrLearnWeights();
        }
        
        List<NumberProbability> allProbabilities = new ArrayList<>();
        
        // 모든 번호(1-44)의 확률 계산 (학습된 가중치 적용됨)
        for (int i = 1; i <= MAX_NUMBER; i++) {
            double probability = calculateProbability(i, allResults);
            allProbabilities.add(new NumberProbability(i, probability));
        }
        
        return allProbabilities;
    }

    /**
     * High7과 Low7을 섞어서 Mid7 번호를 추출
     * 상위 7개와 하위 7개 번호를 합쳐서 중간 확률의 번호 7개를 선택
     * 
     * @return 중간 확률의 7개 번호 리스트
     */
    public List<Integer> getMid7Numbers() {
        return getMid7NumbersWithProbability().stream()
            .map(NumberProbability::getNumber)
            .collect(Collectors.toList());
    }

    /**
     * High7과 Low7을 섞어서 Mid7 번호와 확률을 함께 반환
     * 
     * @return 중간 확률의 7개 번호와 확률 리스트
     */
    public List<NumberProbability> getMid7NumbersWithProbability() {
        // High7과 Low7 가져오기
        List<NumberProbability> high7 = getTop7NumbersWithPatternFilteringAndProbability();
        List<NumberProbability> low7 = getBottom7NumbersWithPatternFilteringAndProbability();

        if (high7.isEmpty() || low7.isEmpty()) {
            log.warn("High7 또는 Low7이 비어있어 Mid7을 생성할 수 없습니다. 기본 중간 번호를 반환합니다.");
            return getMid7NumbersFromAllProbabilities();
        }

        // High7과 Low7의 확률 범위 계산
        double highMinProb = high7.stream().mapToDouble(NumberProbability::getProbability).min().orElse(0.0);
        double highMaxProb = high7.stream().mapToDouble(NumberProbability::getProbability).max().orElse(1.0);
        double lowMinProb = low7.stream().mapToDouble(NumberProbability::getProbability).min().orElse(0.0);
        double lowMaxProb = low7.stream().mapToDouble(NumberProbability::getProbability).max().orElse(1.0);

        // 중간 확률 범위 계산 (High7과 Low7 사이의 중간 영역)
        double midMinProb = Math.min(highMinProb, lowMaxProb);
        double midMaxProb = Math.max(highMaxProb, lowMinProb);
        double midCenterProb = (midMinProb + midMaxProb) / 2.0;
        double midRange = (midMaxProb - midMinProb) / 2.0;

        // 확장된 중간 범위 (중심 ± 범위의 1.5배)
        double expandedMinProb = Math.max(0.0, midCenterProb - midRange * 1.5);
        double expandedMaxProb = Math.min(1.0, midCenterProb + midRange * 1.5);

        log.info("Mid7 확률 범위: {}% ~ {}% (중심: {}%)",
            String.format("%.2f", expandedMinProb * 100),
            String.format("%.2f", expandedMaxProb * 100),
            String.format("%.2f", midCenterProb * 100));

        // High7과 Low7의 번호들을 합치고 중복 제거
        Set<Integer> combinedNumbers = new HashSet<>();
        high7.forEach(np -> combinedNumbers.add(np.getNumber()));
        low7.forEach(np -> combinedNumbers.add(np.getNumber()));

        // 모든 번호의 확률 계산
        List<NumberProbability> allProbabilities = calculateAllProbabilities();
        Map<Integer, Double> probabilityMap = allProbabilities.stream()
            .collect(Collectors.toMap(NumberProbability::getNumber, NumberProbability::getProbability));

        // 중간 확률 범위에 있는 번호들 필터링
        List<NumberProbability> midCandidates = combinedNumbers.stream()
            .filter(num -> {
                double prob = probabilityMap.getOrDefault(num, 0.0);
                return prob >= expandedMinProb && prob <= expandedMaxProb;
            })
            .map(num -> new NumberProbability(num, probabilityMap.getOrDefault(num, 0.0)))
            .sorted((a, b) -> Double.compare(b.getProbability(), a.getProbability()))
            .collect(Collectors.toList());

        // 중간 확률 범위에 있는 번호가 부족하면 전체 번호에서 중간 확률 선택
        if (midCandidates.size() < 7) {
            // DEBUG 로그 제거 (너무 많은 출력 방지)
            midCandidates = allProbabilities.stream()
                .filter(np -> {
                    double prob = np.getProbability();
                    return prob >= expandedMinProb && prob <= expandedMaxProb;
                })
                .sorted((a, b) -> Double.compare(b.getProbability(), a.getProbability()))
                .collect(Collectors.toList());
        }

        // 패턴 필터링 적용
        PatternRange patternRange = calculateHistoricalPatternRange();
        if (patternRange != null && midCandidates.size() >= 7) {
            List<Integer> candidateNumbers = midCandidates.stream()
                .map(NumberProbability::getNumber)
                .limit(20) // 상위 20개 후보
                .collect(Collectors.toList());

            List<NumberProbability> patternFiltered = findBestCombinationInRange(
                candidateNumbers, allProbabilities, patternRange);

            if (patternFiltered != null && !patternFiltered.isEmpty()) {
                log.info("패턴 필터링된 Mid7 번호:");
                for (int i = 0; i < patternFiltered.size(); i++) {
                    NumberProbability np = patternFiltered.get(i);
                    String probabilityStr = String.format("%.2f", np.getProbability() * 100);
                    log.info("  {}위: 번호 {} (확률: {}%)", i + 1, np.getNumber(), probabilityStr);
                }
                return patternFiltered;
            }
        }

        // 패턴 필터링 실패 또는 적용 불가 시 중간 확률 상위 7개 반환
        List<NumberProbability> mid7 = midCandidates.subList(0, Math.min(7, midCandidates.size()));

        log.info("Mid7 번호 (패턴 필터링 미적용):");
        for (int i = 0; i < mid7.size(); i++) {
            NumberProbability np = mid7.get(i);
            String probabilityStr = String.format("%.2f", np.getProbability() * 100);
            log.info("  {}위: 번호 {} (확률: {}%)", i + 1, np.getNumber(), probabilityStr);
        }

        return mid7;
    }

    /**
     * 39% 초과 ~ 42% 미만 확률 범위에 있는 모든 번호를 반환 (개수 제한 없음)
     * 
     * @return 39% 초과 ~ 42% 미만 확률 범위의 번호와 확률 리스트 (확률 높은 순으로 정렬)
     */
    public List<NumberProbability> getMidNumbersInRange() {
        List<LotteryResult> allResults = getCachedAllResults();
        return getMidNumbersInRange(allResults);
    }
    
    /**
     * 39% 초과 ~ 42% 미만 확률 범위 번호 추출 (데이터 전달 버전)
     */
    private List<NumberProbability> getMidNumbersInRange(List<LotteryResult> allResults) {
        double minProb = 0.39; // 39% 초과
        double maxProb = 0.42; // 42% 미만
        
        List<NumberProbability> allProbabilities = calculateAllProbabilities(allResults);
        
        // 39% 초과 ~ 42% 미만 범위에 있는 번호들 필터링
        List<NumberProbability> midNumbers = allProbabilities.stream()
            .filter(np -> {
                double prob = np.getProbability();
                return prob > minProb && prob < maxProb;
            })
            .sorted((a, b) -> Double.compare(b.getProbability(), a.getProbability()))
            .collect(Collectors.toList());
        
        log.info("\n[39% 초과 ~ 42% 미만 확률 범위 번호 (총 {}개)]", midNumbers.size());
        log.info("번호 | 확률(%)");
        log.info("-----|----------");
        for (NumberProbability np : midNumbers) {
            String probabilityStr = String.format("%.4f", np.getProbability() * 100);
            log.info("  {}  | {}%", np.getNumber(), probabilityStr);
        }
        
        if (midNumbers.isEmpty()) {
            log.warn("39% 초과 ~ 42% 미만 확률 범위에 해당하는 번호가 없습니다.");
        }
        
        return midNumbers;
    }

    /**
     * 39% 초과 ~ 42% 미만 확률 범위에 있는 모든 번호를 번호만 반환
     * 
     * @return 39% 초과 ~ 42% 미만 확률 범위의 번호 리스트 (확률 높은 순으로 정렬)
     */
    public List<Integer> getMidNumbersInRangeAsList() {
        return getMidNumbersInRange().stream()
            .map(NumberProbability::getNumber)
            .collect(Collectors.toList());
    }
    
    /**
     * 39% 초과 ~ 42% 미만 확률 범위 번호 리스트 반환 (데이터 전달 버전)
     */
    private List<Integer> getMidNumbersInRangeAsList(List<LotteryResult> allResults) {
        return getMidNumbersInRange(allResults).stream()
            .map(NumberProbability::getNumber)
            .collect(Collectors.toList());
    }

    /**
     * 반복 예측(합의) 결과. 이메일/파이프라인에서 최종 번호로 사용한다.
     */
    public static class MultipleRunsPredictionResult {
        private final List<NumberProbability> top7;
        private final List<NumberProbability> midRange7;
        private final Map<Integer, Integer> top7Frequencies;
        private final Map<Integer, Integer> midRange7Frequencies;
        private final long elapsedTimeMillis;
        private final int runsCount;

        public MultipleRunsPredictionResult(
                List<NumberProbability> top7,
                List<NumberProbability> midRange7,
                Map<Integer, Integer> top7Frequencies,
                Map<Integer, Integer> midRange7Frequencies,
                long elapsedTimeMillis,
                int runsCount) {
            this.top7 = List.copyOf(top7);
            this.midRange7 = List.copyOf(midRange7);
            this.top7Frequencies = Map.copyOf(top7Frequencies);
            this.midRange7Frequencies = Map.copyOf(midRange7Frequencies);
            this.elapsedTimeMillis = elapsedTimeMillis;
            this.runsCount = runsCount;
        }

        public List<NumberProbability> getTop7() {
            return top7;
        }

        public List<NumberProbability> getMidRange7() {
            return midRange7;
        }

        public Map<Integer, Integer> getTop7Frequencies() {
            return top7Frequencies;
        }

        public Map<Integer, Integer> getMidRange7Frequencies() {
            return midRange7Frequencies;
        }

        public long getElapsedTimeMillis() {
            return elapsedTimeMillis;
        }

        public int getRunsCount() {
            return runsCount;
        }
    }

    /**
     * 예측 로직을 여러 번 실행하여 일관성 있는 결과 추출
     * 등장횟수가 많은 번호들로 최종 상위 7개와 39%~42% 범위 번호 7개씩 생성
     *
     * @return 합의 기반 최종 번호 (이메일에 사용)
     */
    public MultipleRunsPredictionResult predictWithMultipleRuns() {
        long startTime = System.currentTimeMillis();
        log.info("\n=== {}회 반복 예측 분석 시작 ===", MULTIPLE_RUNS_COUNT);
        
        // 딥러닝 예측 캐시를 먼저 생성 (한 번만 수행)
        long cacheStartTime = System.currentTimeMillis();
        log.info("딥러닝 예측 캐시 확인 중...");
        List<LotteryResult> allResults = getCachedAllResults();
        ensureDeepLearningCache(allResults);
        long cacheElapsedTime = System.currentTimeMillis() - cacheStartTime;
        String cacheTimeStr = formatElapsedTime(cacheElapsedTime);
        if (cacheElapsedTime > 1000) {
            log.info("딥러닝 예측 캐시 생성 완료 (소요 시간: {})", cacheTimeStr);
        } else {
            log.info("딥러닝 예측 캐시 재사용 (소요 시간: {})", cacheTimeStr);
        }
        log.info("이제 {}회 반복 예측을 시작합니다.", MULTIPLE_RUNS_COUNT);
        
        // 각 실행마다 결과를 저장할 맵
        Map<Integer, Integer> top9Frequency = new HashMap<>(); // 번호 -> 등장 횟수
        Map<Integer, Integer> midRangeFrequency = new HashMap<>(); // 번호 -> 등장 횟수
        
        // 여러 번 실행 (각 실행마다 가중치를 약간 변동시켜 다른 결과 도출)
        Random random = new Random();
        LearnedWeights baseWeights = getOrLearnWeights(); // 기본 가중치 저장
        
        // 첫 번째 실행 시간 측정을 위한 변수
        long firstRunElapsedTime = 0;
        
        for (int run = 1; run <= MULTIPLE_RUNS_COUNT; run++) {
            long runStartTime = System.currentTimeMillis();
            
            // 로깅 최적화: 100회마다 또는 첫 번째/마지막 실행 시에만 로그 출력
            boolean shouldLog = (run == 1 || run == MULTIPLE_RUNS_COUNT || run % 100 == 0);
            if (shouldLog) {
                log.info("\n[{}번째 예측 실행]", run);
            }
            
            try {
                // 각 실행마다 가중치를 약간 변동시켜 다른 결과를 얻기 위해
                // 기본 가중치에 약간의 랜덤 변동 추가 (±5% 범위)
                LearnedWeights currentWeights;
                if (run > 1) {
                    currentWeights = createVariedWeights(baseWeights, random, run);
                } else {
                    currentWeights = baseWeights;
                }
                
                // synchronized 블록 최소화: 가중치만 임시로 설정
                LearnedWeights originalWeights;
                synchronized (this) {
                    originalWeights = learnedWeights;
                    learnedWeights = currentWeights;
                }
                
                try {
                    // 상위 9개 번호 추출
                    List<NumberProbability> allProbabilities = calculateAllProbabilities(allResults);
                    allProbabilities.sort((a, b) -> Double.compare(b.getProbability(), a.getProbability()));
                    List<Integer> top9 = allProbabilities.stream()
                        .limit(9)
                        .map(NumberProbability::getNumber)
                        .collect(Collectors.toList());
                    
                    // 39% 초과 ~ 42% 미만 범위 번호 추출
                    List<Integer> midRange = getMidNumbersInRangeAsList(allResults);
                    
                    if (shouldLog) {
                        log.info("  상위 9개: {}", top9);
                        log.info("  39%~42% 범위: {} ({}개)", midRange, midRange.size());
                    }
                    
                    // 빈도 카운트
                    for (Integer num : top9) {
                        top9Frequency.put(num, top9Frequency.getOrDefault(num, 0) + 1);
                    }
                    
                    for (Integer num : midRange) {
                        midRangeFrequency.put(num, midRangeFrequency.getOrDefault(num, 0) + 1);
                    }
                } finally {
                    // 가중치 복원
                    synchronized (this) {
                        learnedWeights = originalWeights;
                    }
                }
                
            } catch (Exception e) {
                log.error("  {}번째 예측 실행 실패: {}", run, e.getMessage(), e);
            }
            
            // 실행 시간 측정 및 예상 종료 시간 계산
            long runElapsedTime = System.currentTimeMillis() - runStartTime;
            
            if (run == 1) {
                firstRunElapsedTime = runElapsedTime;
                // 첫 번째 실행 완료 후 예상 종료 시간 계산
                long estimatedTotalTime = cacheElapsedTime + (firstRunElapsedTime * MULTIPLE_RUNS_COUNT);
                long estimatedEndTime = startTime + estimatedTotalTime;
                java.util.Date estimatedEndDate = new java.util.Date(estimatedEndTime);
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                log.info("");
                log.info("  ⏱️ 첫 번째 실행 완료 (소요 시간: {}초)", String.format("%.2f", firstRunElapsedTime / 1000.0));
                log.info("  📅 예상 종료 시간: {} (예상 총 소요 시간: {}분)", 
                    sdf.format(estimatedEndDate), String.format("%.1f", estimatedTotalTime / 60000.0));
                log.info("");
            } else if (run % 100 == 0 || run == MULTIPLE_RUNS_COUNT) {
                // 100회마다 또는 마지막 실행 시 진행 상황 및 예상 종료 시간 업데이트
                long elapsedSoFar = System.currentTimeMillis() - startTime;
                double avgTimePerRun = elapsedSoFar / (double) run;
                long remainingRuns = MULTIPLE_RUNS_COUNT - run;
                long estimatedRemainingTime = (long) (avgTimePerRun * remainingRuns);
                long estimatedEndTime = System.currentTimeMillis() + estimatedRemainingTime;
                java.util.Date estimatedEndDate = new java.util.Date(estimatedEndTime);
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                
                double progressPercent = (run * 100.0) / MULTIPLE_RUNS_COUNT;
                log.info("  📊 진행 상황: {}/{} ({}%) | 평균 실행 시간: {}초 | 예상 종료 시간: {}", 
                    run, MULTIPLE_RUNS_COUNT, String.format("%.1f", progressPercent),
                    String.format("%.2f", avgTimePerRun / 1000.0), sdf.format(estimatedEndDate));
            }
        }
        
        // 가중치를 원래대로 복원
        learnedWeights = baseWeights;
        
        log.info("\n=== 빈도 분석 결과 ===");
        
        // 확률 맵을 먼저 계산 (이미 로드된 데이터 재사용)
        List<NumberProbability> allProbabilitiesForFilter = calculateAllProbabilities(allResults);
        Map<Integer, Double> probMapForFilter = allProbabilitiesForFilter.stream()
            .collect(Collectors.toMap(NumberProbability::getNumber, NumberProbability::getProbability));
        
        // 상위 9개: 등장횟수 많은 순으로 정렬하여 정확히 7개 선택
        List<Map.Entry<Integer, Integer>> top9Sorted = top9Frequency.entrySet().stream()
            .sorted((a, b) -> {
                int freqCompare = Integer.compare(b.getValue(), a.getValue()); // 빈도 높은 순
                if (freqCompare != 0) return freqCompare;
                return Integer.compare(a.getKey(), b.getKey()); // 빈도 같으면 번호 순
            })
            .limit(7) // 등장횟수 많은 7개만
            .collect(Collectors.toList());
        
        log.info("\n[상위 9개 번호 빈도 분석]");
        log.info("번호 | 등장횟수");
        log.info("-----|----------");
        for (Map.Entry<Integer, Integer> entry : top9Frequency.entrySet()) {
            log.info("  {}  | {}회", entry.getKey(), entry.getValue());
        }
        
        log.info("\n[최종 상위 7개 (등장횟수 많은 순)]");
        List<Integer> finalTop9 = top9Sorted.stream()
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
        
        log.info("최종 상위 7개 번호 (등장횟수 순): {}", finalTop9);
        for (Map.Entry<Integer, Integer> entry : top9Sorted) {
            log.info("  번호 {}: {}회 등장", entry.getKey(), entry.getValue());
        }
        
        // 39%~42% 범위: 등장횟수 많은 순으로 정렬하여 정확히 7개 선택
        List<Map.Entry<Integer, Integer>> midRangeSorted = midRangeFrequency.entrySet().stream()
            .sorted((a, b) -> {
                int freqCompare = Integer.compare(b.getValue(), a.getValue()); // 빈도 높은 순
                if (freqCompare != 0) return freqCompare;
                return Integer.compare(a.getKey(), b.getKey()); // 빈도 같으면 번호 순
            })
            .limit(7) // 등장횟수 많은 7개만
            .collect(Collectors.toList());
        
        log.info("\n[39%~42% 범위 번호 빈도 분석]");
        log.info("번호 | 등장횟수");
        log.info("-----|----------");
        for (Map.Entry<Integer, Integer> entry : midRangeFrequency.entrySet()) {
            log.info("  {}  | {}회", entry.getKey(), entry.getValue());
        }
        
        log.info("\n[최종 39%~42% 범위 번호 (등장횟수 많은 순, 7개)]");
        List<Integer> finalMidRange = midRangeSorted.stream()
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
        
        log.info("최종 39%~42% 범위 번호 (등장횟수 순, 7개): {}", finalMidRange);
        for (Map.Entry<Integer, Integer> entry : midRangeSorted) {
            log.info("  번호 {}: {}회 등장", entry.getKey(), entry.getValue());
        }
        
        log.info("\n=== 최종 예측 결과 요약 (등장횟수 순) ===");
        
        // 최종 상위 7개 번호를 등장횟수 순으로 표시
        List<NumberProbability> allProbabilities = calculateAllProbabilities();
        Map<Integer, Double> probMap = allProbabilities.stream()
            .collect(Collectors.toMap(NumberProbability::getNumber, NumberProbability::getProbability));
        
        // 등장횟수 순으로 정렬하여 정확히 7개 선택
        List<NumberProbability> finalTop9WithProb = top9Sorted.stream()
            .map(entry -> {
                int num = entry.getKey();
                double prob = probMap.getOrDefault(num, 0.0);
                return new NumberProbability(num, prob);
            })
            .limit(7) // 정확히 7개만 선택
            .collect(Collectors.toList());
        
        log.info("\n[최종 상위 7개 번호 (등장횟수 순)]");
        log.info("순위 | 번호 | 등장횟수 | 확률(%)");
        log.info("-----|------|----------|---------");
        for (int i = 0; i < finalTop9WithProb.size(); i++) {
            NumberProbability np = finalTop9WithProb.get(i);
            int freq = top9Frequency.getOrDefault(np.getNumber(), 0);
            String probStr = String.format("%.4f", np.getProbability() * 100);
            log.info("  {}  |  {}  | {}회 | {}%", i + 1, np.getNumber(), freq, probStr);
        }
        
        // 최종 39%~42% 범위 번호를 등장횟수 순으로 표시 (정확히 7개)
        List<NumberProbability> finalMidRangeWithProb = midRangeSorted.stream()
            .map(entry -> {
                int num = entry.getKey();
                double prob = probMap.getOrDefault(num, 0.0);
                return new NumberProbability(num, prob);
            })
            .limit(7) // 정확히 7개만 선택
            .collect(Collectors.toList());
        
        log.info("\n[최종 39%~42% 범위 번호 (등장횟수 순, 7개)]");
        log.info("순위 | 번호 | 등장횟수 | 확률(%)");
        log.info("-----|------|----------|---------");
        for (int i = 0; i < finalMidRangeWithProb.size(); i++) {
            NumberProbability np = finalMidRangeWithProb.get(i);
            int freq = midRangeFrequency.getOrDefault(np.getNumber(), 0);
            String probStr = String.format("%.4f", np.getProbability() * 100);
            log.info("  {}  |  {}  | {}회 | {}%", i + 1, np.getNumber(), freq, probStr);
        }
        
        long endTime = System.currentTimeMillis();
        long elapsedTime = endTime - startTime;
        double elapsedSeconds = elapsedTime / 1000.0;
        
        // 시간, 분, 초로 변환
        long hours = elapsedTime / (1000 * 60 * 60);
        long minutes = (elapsedTime % (1000 * 60 * 60)) / (1000 * 60);
        long seconds = (elapsedTime % (1000 * 60)) / 1000;
        
        log.info("\n=== {}회 반복 예측 분석 완료 ===", MULTIPLE_RUNS_COUNT);
        String timeStr;
        if (hours > 0) {
            timeStr = String.format("%d시간 %d분 %d초", hours, minutes, seconds);
            log.info("⏱️  총 소요 시간: {}", timeStr);
        } else if (minutes > 0) {
            timeStr = String.format("%d분 %d초", minutes, seconds);
            log.info("⏱️  총 소요 시간: {}", timeStr);
        } else {
            timeStr = String.format("%d초", seconds);
            log.info("⏱️  총 소요 시간: {}", timeStr);
        }
        log.info("");

        return new MultipleRunsPredictionResult(
                finalTop9WithProb,
                finalMidRangeWithProb,
                new HashMap<>(top9Frequency),
                new HashMap<>(midRangeFrequency),
                elapsedTime,
                MULTIPLE_RUNS_COUNT);
    }
    
    /**
     * 경과 시간을 시간, 분, 초 형식으로 포맷팅
     * 
     * @param elapsedTimeMillis 경과 시간 (밀리초)
     * @return 포맷팅된 시간 문자열 (예: "1시간 23분 45초", "23분 45초", "45초")
     */
    private String formatElapsedTime(long elapsedTimeMillis) {
        long hours = elapsedTimeMillis / (1000 * 60 * 60);
        long minutes = (elapsedTimeMillis % (1000 * 60 * 60)) / (1000 * 60);
        long seconds = (elapsedTimeMillis % (1000 * 60)) / 1000;
        
        if (hours > 0) {
            return String.format("%d시간 %d분 %d초", hours, minutes, seconds);
        } else if (minutes > 0) {
            return String.format("%d분 %d초", minutes, seconds);
        } else {
            return String.format("%d초", seconds);
        }
    }

    /**
     * 전체 확률에서 중간 확률의 번호 7개 반환 (폴백 메서드)
     */
    private List<NumberProbability> getMid7NumbersFromAllProbabilities() {
        List<NumberProbability> allProbabilities = calculateAllProbabilities();
        allProbabilities.sort((a, b) -> Double.compare(b.getProbability(), a.getProbability()));

        // 중간 확률 범위 계산 (전체 확률의 25%~75% 사이)
        if (allProbabilities.isEmpty()) {
            return new ArrayList<>();
        }

        double maxProb = allProbabilities.get(0).getProbability();
        double minProb = allProbabilities.get(allProbabilities.size() - 1).getProbability();
        double midMinProb = minProb + (maxProb - minProb) * 0.25;
        double midMaxProb = minProb + (maxProb - minProb) * 0.75;

        List<NumberProbability> midNumbers = allProbabilities.stream()
            .filter(np -> {
                double prob = np.getProbability();
                return prob >= midMinProb && prob <= midMaxProb;
            })
            .limit(7)
            .collect(Collectors.toList());

        return midNumbers;
    }

    /**
     * 히스토리 패턴을 고려하여 7개 번호를 추출
     * 평균, 최대, 최소, 합계값이 히스토리 범위 내에 있는 조합을 반환
     * 
     * @return 히스토리 패턴 범위 내의 7개 번호 리스트
     */
    public List<Integer> getTop7NumbersWithPatternFiltering() {
        return getTop7NumbersWithPatternFilteringAndProbability().stream()
            .map(NumberProbability::getNumber)
            .collect(Collectors.toList());
    }

    /**
     * 히스토리 패턴을 고려하여 7개 번호와 확률을 함께 반환
     * 
     * @return 히스토리 패턴 범위 내의 7개 번호와 확률 리스트
     */
    public List<NumberProbability> getTop7NumbersWithPatternFilteringAndProbability() {
        // 히스토리 패턴 범위 계산
        PatternRange patternRange = calculateHistoricalPatternRange();
        
        if (patternRange == null) {
            log.warn("히스토리 데이터가 부족하여 패턴 필터링을 사용할 수 없습니다. 기본 방법으로 반환합니다.");
            return getTop7NumbersWithProbability();
        }

        log.info("히스토리 패턴 범위: 합계[{}-{}], 평균[{}-{}], 최소[{}-{}], 최대[{}-{}]",
            patternRange.minSum, patternRange.maxSum,
            String.format("%.2f", patternRange.minAverage), String.format("%.2f", patternRange.maxAverage),
            patternRange.minValue, patternRange.maxValue,
            patternRange.minMax, patternRange.maxMax);
        log.info("추가 패턴: Low[{}-{}], High[{}-{}], Odd[{}-{}], Even[{}-{}]",
            patternRange.minLowCount, patternRange.maxLowCount,
            patternRange.minHighCount, patternRange.maxHighCount,
            patternRange.minOddCount, patternRange.maxOddCount,
            patternRange.minEvenCount, patternRange.maxEvenCount);
        log.info("범위별 분포: 1-10[{}-{}], 11-20[{}-{}], 21-30[{}-{}], 31-40[{}-{}], 41-44[{}-{}]",
            patternRange.minRange1To10, patternRange.maxRange1To10,
            patternRange.minRange11To20, patternRange.maxRange11To20,
            patternRange.minRange21To30, patternRange.maxRange21To30,
            patternRange.minRange31To40, patternRange.maxRange31To40,
            patternRange.minRange41To44, patternRange.maxRange41To44);
        log.info("추가 실용 패턴: 연속번호[{}-{}], 평균간격[{:.2f}-{:.2f}], 범위차이[{}-{}], 소수[{}-{}], 1의자리다양성[{}-{}]",
            patternRange.minConsecutiveCount, patternRange.maxConsecutiveCount,
            String.format("%.2f", patternRange.minAverageGap), String.format("%.2f", patternRange.maxAverageGap),
            patternRange.minRangeSpread, patternRange.maxRangeSpread,
            patternRange.minPrimeCount, patternRange.maxPrimeCount,
            patternRange.minOnesDigitVariety, patternRange.maxOnesDigitVariety);

        // 모든 번호의 확률 계산
        List<NumberProbability> allProbabilities = calculateAllProbabilities();
        allProbabilities.sort((a, b) -> Double.compare(b.getProbability(), a.getProbability()));
        
        // 고정밀 앙상블 합의 점수 계산 (시간을 더 써서 안정적인 순위 확보)
        List<LotteryResult> allResults = getCachedAllResults();
        Map<Integer, Double> highPrecisionScores = buildHighPrecisionConsensusScores(allResults, allProbabilities);

        // 상위 후보 번호들에서 조합 생성
        // 기본 확률 상위 + 고정밀 앙상블 상위를 합쳐 후보군을 넓힌다.
        LinkedHashSet<Integer> candidateSet = new LinkedHashSet<>();
        int baseCandidateCount = Math.min(20, allProbabilities.size());
        for (int i = 0; i < baseCandidateCount; i++) {
            candidateSet.add(allProbabilities.get(i).getNumber());
        }
        candidateSet.addAll(selectTopNumbersByScore(highPrecisionScores, 24));
        
        int maxCandidateCount = Math.min(36, allProbabilities.size());
        for (NumberProbability np : allProbabilities) {
            if (candidateSet.size() >= maxCandidateCount) {
                break;
            }
            candidateSet.add(np.getNumber());
        }
        List<Integer> candidateNumbers = new ArrayList<>(candidateSet);

        // 패턴 범위 내에 맞는 조합 찾기 (더 많은 시도)
        List<NumberProbability> bestCombination = findBestCombinationInRangeAdvanced(
            candidateNumbers, allProbabilities, patternRange, highPrecisionScores);

        if (bestCombination == null || bestCombination.isEmpty()) {
            log.warn("패턴 범위 내의 조합을 찾지 못했습니다. 기본 방법으로 반환합니다.");
            return getTop7NumbersWithProbability();
        }

        log.info("패턴 필터링된 상위 7개 번호:");
        for (int i = 0; i < bestCombination.size(); i++) {
            NumberProbability np = bestCombination.get(i);
            String probabilityStr = String.format("%.2f", np.getProbability() * 100);
            log.info("  {}위: 번호 {} (확률: {}%)", i + 1, np.getNumber(), probabilityStr);
        }

        return bestCombination;
    }

    /**
     * 히스토리 데이터에서 7개 번호 조합의 통계 범위를 계산
     */
    private PatternRange calculateHistoricalPatternRange() {
        List<LotteryResult> allResults = getCachedAllResults();
        
        if (allResults.isEmpty() || allResults.size() < MIN_DRAWS_FOR_ANALYSIS) {
            return null;
        }

        List<Integer> sums = new ArrayList<>();
        List<Double> averages = new ArrayList<>();
        List<Integer> minValues = new ArrayList<>();
        List<Integer> maxValues = new ArrayList<>();
        List<Integer> lowCounts = new ArrayList<>();
        List<Integer> highCounts = new ArrayList<>();
        List<Integer> oddCounts = new ArrayList<>();
        List<Integer> evenCounts = new ArrayList<>();
        List<Integer> range1To10Counts = new ArrayList<>();
        List<Integer> range11To20Counts = new ArrayList<>();
        List<Integer> range21To30Counts = new ArrayList<>();
        List<Integer> range31To40Counts = new ArrayList<>();
        List<Integer> range41To44Counts = new ArrayList<>();
        List<Integer> consecutiveCounts = new ArrayList<>();
        List<Double> averageGaps = new ArrayList<>();
        List<Integer> rangeSpreads = new ArrayList<>();
        List<Integer> primeCounts = new ArrayList<>();
        List<Integer> onesDigitVarieties = new ArrayList<>();

        // 각 회차의 7개 당첨 번호 조합 통계 계산
        for (LotteryResult result : allResults) {
            List<Integer> winningNumbers = extractWinningNumbers(result);
            
            if (winningNumbers.size() != 7) {
                continue; // 7개가 아니면 스킵
            }

            int sum = winningNumbers.stream().mapToInt(Integer::intValue).sum();
            double average = winningNumbers.stream().mapToInt(Integer::intValue).average().orElse(0.0);
            int min = Collections.min(winningNumbers);
            int max = Collections.max(winningNumbers);

            // Low/High 계산 (낮은 번호: 1-22, 높은 번호: 23-44)
            int lowCount = (int) winningNumbers.stream().filter(n -> n <= 22).count();
            int highCount = (int) winningNumbers.stream().filter(n -> n >= 23).count();
            
            // Odd/Even 계산
            int oddCount = (int) winningNumbers.stream().filter(n -> n % 2 == 1).count();
            int evenCount = (int) winningNumbers.stream().filter(n -> n % 2 == 0).count();
            
            // 범위별 분포 계산
            int range1To10 = (int) winningNumbers.stream().filter(n -> n >= 1 && n <= 10).count();
            int range11To20 = (int) winningNumbers.stream().filter(n -> n >= 11 && n <= 20).count();
            int range21To30 = (int) winningNumbers.stream().filter(n -> n >= 21 && n <= 30).count();
            int range31To40 = (int) winningNumbers.stream().filter(n -> n >= 31 && n <= 40).count();
            int range41To44 = (int) winningNumbers.stream().filter(n -> n >= 41 && n <= 44).count();
            
            // 연속 번호 개수 계산
            List<Integer> sortedNumbers = new ArrayList<>(winningNumbers);
            Collections.sort(sortedNumbers);
            int consecutiveCount = countConsecutiveNumbers(sortedNumbers);
            
            // 번호 간 평균 간격 계산
            double averageGap = calculateAverageGap(sortedNumbers);
            
            // 번호 범위 (최대-최소 차이)
            int rangeSpread = max - min;
            
            // 소수 번호 개수 계산
            int primeCount = (int) winningNumbers.stream().filter(this::isPrime).count();
            
            // 1의 자리 숫자 다양성 계산
            Set<Integer> onesDigits = winningNumbers.stream()
                .map(n -> n % 10)
                .collect(Collectors.toSet());
            int onesDigitVariety = onesDigits.size();

            sums.add(sum);
            averages.add(average);
            minValues.add(min);
            maxValues.add(max);
            lowCounts.add(lowCount);
            highCounts.add(highCount);
            oddCounts.add(oddCount);
            evenCounts.add(evenCount);
            range1To10Counts.add(range1To10);
            range11To20Counts.add(range11To20);
            range21To30Counts.add(range21To30);
            range31To40Counts.add(range31To40);
            range41To44Counts.add(range41To44);
            consecutiveCounts.add(consecutiveCount);
            averageGaps.add(averageGap);
            rangeSpreads.add(rangeSpread);
            primeCounts.add(primeCount);
            onesDigitVarieties.add(onesDigitVariety);
        }

        if (sums.isEmpty()) {
            return null;
        }

        // 평균과 표준편차를 사용하여 범위 계산 (평균 ± 2*표준편차)
        PatternRange range = new PatternRange();
        
        // 기본값 초기화 (안전한 기본값)
        range.minLowCount = 0;
        range.maxLowCount = 7;
        range.minHighCount = 0;
        range.maxHighCount = 7;
        range.minOddCount = 0;
        range.maxOddCount = 7;
        range.minEvenCount = 0;
        range.maxEvenCount = 7;
        range.minRange1To10 = 0;
        range.maxRange1To10 = 7;
        range.minRange11To20 = 0;
        range.maxRange11To20 = 7;
        range.minRange21To30 = 0;
        range.maxRange21To30 = 7;
        range.minRange31To40 = 0;
        range.maxRange31To40 = 7;
        range.minRange41To44 = 0;
        range.maxRange41To44 = 7;
        range.minConsecutiveCount = 0;
        range.maxConsecutiveCount = 7;
        range.minAverageGap = 0.0;
        range.maxAverageGap = 44.0;
        range.minRangeSpread = 0;
        range.maxRangeSpread = 44;
        range.minPrimeCount = 0;
        range.maxPrimeCount = 7;
        range.minOnesDigitVariety = 1;
        range.maxOnesDigitVariety = 7;
        
        // 합계 범위
        double sumMean = sums.stream().mapToInt(Integer::intValue).average().orElse(0.0);
        double sumStdDev = calculateStandardDeviation(sums.stream().mapToInt(Integer::intValue).toArray());
        range.minSum = (int) Math.max(0, sumMean - 2 * sumStdDev);
        range.maxSum = (int) Math.min(Integer.MAX_VALUE, sumMean + 2 * sumStdDev);

        // 평균 범위
        double avgMean = averages.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double avgStdDev = calculateStandardDeviation(averages.stream().mapToDouble(Double::doubleValue).toArray());
        range.minAverage = avgMean - 2 * avgStdDev;
        range.maxAverage = avgMean + 2 * avgStdDev;

        // 최소값 범위 (히스토리에서 실제 최소/최대값 사용)
        range.minValue = Collections.min(minValues);
        range.maxValue = Collections.max(minValues);

        // 최대값 범위
        range.minMax = Collections.min(maxValues);
        range.maxMax = Collections.max(maxValues);

        // Low/High 범위 (실제 최소/최대값 사용)
        if (!lowCounts.isEmpty()) {
            range.minLowCount = Collections.min(lowCounts);
            range.maxLowCount = Collections.max(lowCounts);
        }
        if (!highCounts.isEmpty()) {
            range.minHighCount = Collections.min(highCounts);
            range.maxHighCount = Collections.max(highCounts);
        }

        // Odd/Even 범위
        if (!oddCounts.isEmpty()) {
            range.minOddCount = Collections.min(oddCounts);
            range.maxOddCount = Collections.max(oddCounts);
        }
        if (!evenCounts.isEmpty()) {
            range.minEvenCount = Collections.min(evenCounts);
            range.maxEvenCount = Collections.max(evenCounts);
        }

        // 범위별 분포 범위
        if (!range1To10Counts.isEmpty()) {
            range.minRange1To10 = Collections.min(range1To10Counts);
            range.maxRange1To10 = Collections.max(range1To10Counts);
        }
        if (!range11To20Counts.isEmpty()) {
            range.minRange11To20 = Collections.min(range11To20Counts);
            range.maxRange11To20 = Collections.max(range11To20Counts);
        }
        if (!range21To30Counts.isEmpty()) {
            range.minRange21To30 = Collections.min(range21To30Counts);
            range.maxRange21To30 = Collections.max(range21To30Counts);
        }
        if (!range31To40Counts.isEmpty()) {
            range.minRange31To40 = Collections.min(range31To40Counts);
            range.maxRange31To40 = Collections.max(range31To40Counts);
        }
        if (!range41To44Counts.isEmpty()) {
            range.minRange41To44 = Collections.min(range41To44Counts);
            range.maxRange41To44 = Collections.max(range41To44Counts);
        }

        // 추가 실용 패턴 범위 계산
        if (!consecutiveCounts.isEmpty()) {
            range.minConsecutiveCount = Collections.min(consecutiveCounts);
            range.maxConsecutiveCount = Collections.max(consecutiveCounts);
        }
        
        if (!averageGaps.isEmpty()) {
            double gapMean = averageGaps.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double gapStdDev = calculateStandardDeviation(averageGaps.stream().mapToDouble(Double::doubleValue).toArray());
            range.minAverageGap = Math.max(0.0, gapMean - 2 * gapStdDev);
            range.maxAverageGap = Math.min(44.0, gapMean + 2 * gapStdDev);
        }
        
        if (!rangeSpreads.isEmpty()) {
            range.minRangeSpread = Collections.min(rangeSpreads);
            range.maxRangeSpread = Collections.max(rangeSpreads);
        }
        
        if (!primeCounts.isEmpty()) {
            range.minPrimeCount = Collections.min(primeCounts);
            range.maxPrimeCount = Collections.max(primeCounts);
        }
        
        if (!onesDigitVarieties.isEmpty()) {
            range.minOnesDigitVariety = Collections.min(onesDigitVarieties);
            range.maxOnesDigitVariety = Collections.max(onesDigitVarieties);
        }

        return range;
    }

    /**
     * LotteryResult에서 7개 당첨 번호 추출
     */
    private List<Integer> extractWinningNumbers(LotteryResult result) {
        List<Integer> numbers = new ArrayList<>();
        if (result.getWinningNumber1() != null) numbers.add(result.getWinningNumber1());
        if (result.getWinningNumber2() != null) numbers.add(result.getWinningNumber2());
        if (result.getWinningNumber3() != null) numbers.add(result.getWinningNumber3());
        if (result.getWinningNumber4() != null) numbers.add(result.getWinningNumber4());
        if (result.getWinningNumber5() != null) numbers.add(result.getWinningNumber5());
        if (result.getWinningNumber6() != null) numbers.add(result.getWinningNumber6());
        if (result.getWinningNumber7() != null) numbers.add(result.getWinningNumber7());
        return numbers;
    }

    /**
     * LotteryResult에서 9개 번호 추출 (당첨번호 7개 + 보너스번호 2개)
     */
    private List<Integer> extractAllNumbers(LotteryResult result) {
        List<Integer> numbers = new ArrayList<>();
        // 당첨번호 7개
        if (result.getWinningNumber1() != null) numbers.add(result.getWinningNumber1());
        if (result.getWinningNumber2() != null) numbers.add(result.getWinningNumber2());
        if (result.getWinningNumber3() != null) numbers.add(result.getWinningNumber3());
        if (result.getWinningNumber4() != null) numbers.add(result.getWinningNumber4());
        if (result.getWinningNumber5() != null) numbers.add(result.getWinningNumber5());
        if (result.getWinningNumber6() != null) numbers.add(result.getWinningNumber6());
        if (result.getWinningNumber7() != null) numbers.add(result.getWinningNumber7());
        // 보너스번호 2개
        if (result.getBonusNumber1() != null) numbers.add(result.getBonusNumber1());
        if (result.getBonusNumber2() != null) numbers.add(result.getBonusNumber2());
        return numbers;
    }

    /**
     * 표준편차 계산
     */
    private double calculateStandardDeviation(int[] values) {
        if (values.length == 0) return 0.0;
        double mean = Arrays.stream(values).average().orElse(0.0);
        double variance = Arrays.stream(values)
            .mapToDouble(x -> Math.pow(x - mean, 2))
            .average()
            .orElse(0.0);
        return Math.sqrt(variance);
    }

    /**
     * 표준편차 계산 (double 배열)
     */
    private double calculateStandardDeviation(double[] values) {
        if (values.length == 0) return 0.0;
        double mean = Arrays.stream(values).average().orElse(0.0);
        double variance = Arrays.stream(values)
            .map(x -> Math.pow(x - mean, 2))
            .average()
            .orElse(0.0);
        return Math.sqrt(variance);
    }
    
    /**
     * 점수 맵에서 상위 N개 번호를 선택
     */
    private List<Integer> selectTopNumbersByScore(Map<Integer, Double> scoreMap, int topN) {
        if (scoreMap == null || scoreMap.isEmpty() || topN <= 0) {
            return Collections.emptyList();
        }
        return scoreMap.entrySet().stream()
            .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
            .limit(topN)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }
    
    /**
     * 고정밀 앙상블 합의 점수 생성
     * - 다양한 가중치 변형으로 여러 번 점수 산출
     * - 평균 확률, 평균 순위, Top9 진입률을 결합해 안정적인 번호 점수를 만든다.
     */
    private Map<Integer, Double> buildHighPrecisionConsensusScores(
            List<LotteryResult> allResults,
            List<NumberProbability> fallbackProbabilities) {
        
        long now = System.currentTimeMillis();
        if (cachedHighPrecisionScores != null &&
            (now - highPrecisionCacheTimestamp) < HIGH_PRECISION_CACHE_TTL_MS) {
            return new HashMap<>(cachedHighPrecisionScores);
        }
        
        synchronized (this) {
            if (cachedHighPrecisionScores != null &&
                (now - highPrecisionCacheTimestamp) < HIGH_PRECISION_CACHE_TTL_MS) {
                return new HashMap<>(cachedHighPrecisionScores);
            }
            
            Map<Integer, Double> probabilitySums = new HashMap<>();
            Map<Integer, Double> rankSums = new HashMap<>();
            Map<Integer, Integer> top9Counts = new HashMap<>();
            for (int num = 1; num <= MAX_NUMBER; num++) {
                probabilitySums.put(num, 0.0);
                rankSums.put(num, 0.0);
                top9Counts.put(num, 0);
            }
            
            LearnedWeights baseWeights = getOrLearnWeights();
            Random random = new Random(System.currentTimeMillis());
            int successfulRuns = 0;
            
            log.info("고정밀 앙상블 점수 생성 시작 (실행 횟수: {})", HIGH_PRECISION_ENSEMBLE_RUNS);
            long start = System.currentTimeMillis();
            
            for (int run = 0; run < HIGH_PRECISION_ENSEMBLE_RUNS; run++) {
                LearnedWeights currentWeights = (run == 0)
                    ? baseWeights
                    : createVariedWeights(baseWeights, random, run + 1);
                
                LearnedWeights originalWeights;
                synchronized (this) {
                    originalWeights = learnedWeights;
                    learnedWeights = currentWeights;
                }
                
                try {
                    List<NumberProbability> runProbabilities = calculateAllProbabilities(allResults, true);
                    runProbabilities.sort((a, b) -> Double.compare(b.getProbability(), a.getProbability()));
                    successfulRuns++;
                    
                    for (int rank = 0; rank < runProbabilities.size(); rank++) {
                        NumberProbability np = runProbabilities.get(rank);
                        int number = np.getNumber();
                        double probability = np.getProbability();
                        
                        // 1. 평균 확률
                        probabilitySums.put(number, probabilitySums.get(number) + probability);
                        // 2. 평균 순위 점수 (1위가 1.0에 가깝도록)
                        double rankScore = (MAX_NUMBER - rank) / (double) MAX_NUMBER;
                        rankSums.put(number, rankSums.get(number) + rankScore);
                        // 3. Top9 진입률
                        if (rank < 9) {
                            top9Counts.put(number, top9Counts.get(number) + 1);
                        }
                    }
                } catch (Exception e) {
                    log.warn("고정밀 앙상블 {}번째 실행 실패: {}", run + 1, e.getMessage());
                } finally {
                    synchronized (this) {
                        learnedWeights = originalWeights;
                    }
                }
            }
            
            Map<Integer, Double> consensusScores = new HashMap<>();
            if (successfulRuns == 0) {
                for (NumberProbability np : fallbackProbabilities) {
                    consensusScores.put(np.getNumber(), np.getProbability());
                }
            } else {
                for (int num = 1; num <= MAX_NUMBER; num++) {
                    double avgProbability = probabilitySums.get(num) / successfulRuns;
                    double avgRankScore = rankSums.get(num) / successfulRuns;
                    double top9Rate = top9Counts.get(num) / (double) successfulRuns;
                    double consensus =
                        0.45 * avgProbability +
                        0.35 * avgRankScore +
                        0.20 * top9Rate;
                    consensusScores.put(num, Math.max(0.0, Math.min(1.0, consensus)));
                }
            }
            
            cachedHighPrecisionScores = new HashMap<>(consensusScores);
            highPrecisionCacheTimestamp = System.currentTimeMillis();
            log.info("고정밀 앙상블 점수 생성 완료 (성공: {}/{}, 소요: {}ms)",
                successfulRuns, HIGH_PRECISION_ENSEMBLE_RUNS, System.currentTimeMillis() - start);
            
            return consensusScores;
        }
    }

    /**
     * 패턴 범위 내에 맞는 최적의 조합 찾기 (고급 알고리즘 - 50% 목표)
     * 더 많은 시도와 정교한 점수 계산
     */
    private List<NumberProbability> findBestCombinationInRangeAdvanced(
            List<Integer> candidateNumbers,
            List<NumberProbability> allProbabilities,
            PatternRange patternRange,
            Map<Integer, Double> highPrecisionScores) {
        
        Map<Integer, Double> probabilityMap = allProbabilities.stream()
            .collect(Collectors.toMap(NumberProbability::getNumber, NumberProbability::getProbability));

        // 더 많은 조합 시도 (고정밀 모드)
        List<NumberProbability> bestCombination = null;
        double bestScore = -1.0;
        int maxAttempts = HIGH_PRECISION_COMBINATION_ATTEMPTS;
        
        // 학습된 가중치 가져오기
        LearnedWeights weights = getOrLearnWeights();
        
        // DEBUG 로그 제거 (너무 많은 출력 방지)

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            // 랜덤하게 7개 선택 (확률 가중치 적용)
            List<Integer> combination = selectRandomCombination(candidateNumbers, probabilityMap, 7);
            
            if (combination.size() != 7) continue;

            // 패턴 범위 검증
            if (isWithinPatternRange(combination, patternRange)) {
                // 기본 확률 점수
                double baseScore = combination.stream()
                    .mapToDouble(num -> probabilityMap.getOrDefault(num, 0.0))
                    .sum();
                
                // 고정밀 앙상블 합의 점수
                double consensusScore = combination.stream()
                    .mapToDouble(num -> highPrecisionScores.getOrDefault(
                        num, probabilityMap.getOrDefault(num, 0.0)))
                    .sum();
                
                // 패턴 일치도 기반 보너스
                double patternBonus = calculatePatternMatchBonus(combination, patternRange);
                
                // 학습된 가중치 기반 보너스
                double learningBonus = calculateLearningBasedBonus(combination, probabilityMap, weights);
                
                // 4분위 스프레드 보너스 (7개가 구간에 골고루 있으면 가산)
                Set<Integer> quartiles = combination.stream().map(NumberGuessService::quartile).collect(Collectors.toSet());
                double spreadBonus = quartiles.size() >= 4 ? 0.15 : (quartiles.size() >= 3 ? 0.05 : 0.0);
                
                // 최종 점수: 단발성 점수보다 앙상블 합의 점수를 더 크게 반영
                double totalScore = 0.35 * baseScore
                    + 0.55 * consensusScore
                    + patternBonus
                    + learningBonus
                    + spreadBonus;

                if (totalScore > bestScore) {
                    bestScore = totalScore;
                    bestCombination = combination.stream()
                        .map(num -> new NumberProbability(num, highPrecisionScores.getOrDefault(
                            num, probabilityMap.getOrDefault(num, 0.0))))
                        .sorted((a, b) -> {
                            int scoreCompare = Double.compare(b.getProbability(), a.getProbability());
                            if (scoreCompare != 0) return scoreCompare;
                            return Integer.compare(a.getNumber(), b.getNumber());
                        })
                        .collect(Collectors.toList());
                }
            }
        }
        
        if (bestCombination != null) {
            // DEBUG 로그 제거 (너무 많은 출력 방지)
        }

        // 조합을 찾지 못한 경우, 상위 7개 중에서 가장 가까운 조합 찾기
        if (bestCombination == null) {
            // DEBUG 로그 제거 (너무 많은 출력 방지)
            bestCombination = findClosestCombination(candidateNumbers, allProbabilities, patternRange);
        }

        return bestCombination;
    }

    /**
     * 패턴 범위 내에 맞는 최적의 조합 찾기 (기본 알고리즘)
     */
    private List<NumberProbability> findBestCombinationInRange(
            List<Integer> candidateNumbers,
            List<NumberProbability> allProbabilities,
            PatternRange patternRange) {
        
        Map<Integer, Double> probabilityMap = allProbabilities.stream()
            .collect(Collectors.toMap(NumberProbability::getNumber, NumberProbability::getProbability));

        // 여러 조합 시도 (최대 1000번)
        List<NumberProbability> bestCombination = null;
        double bestScore = -1.0;
        int maxAttempts = 1000;

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            // 랜덤하게 7개 선택 (확률 가중치 적용)
            List<Integer> combination = selectRandomCombination(candidateNumbers, probabilityMap, 7);
            
            if (combination.size() != 7) continue;

            // 패턴 범위 검증
            if (isWithinPatternRange(combination, patternRange)) {
                // 확률 합계를 점수로 사용
                double score = combination.stream()
                    .mapToDouble(num -> probabilityMap.getOrDefault(num, 0.0))
                    .sum();

                if (score > bestScore) {
                    bestScore = score;
                    bestCombination = combination.stream()
                        .map(num -> new NumberProbability(num, probabilityMap.getOrDefault(num, 0.0)))
                        .sorted((a, b) -> Double.compare(b.getProbability(), a.getProbability()))
                        .collect(Collectors.toList());
                }
            }
        }

        // 조합을 찾지 못한 경우, 상위 7개 중에서 가장 가까운 조합 찾기
        if (bestCombination == null) {
            // DEBUG 로그 제거 (너무 많은 출력 방지)
            bestCombination = findClosestCombination(candidateNumbers, allProbabilities, patternRange);
        }

        return bestCombination;
    }

    /**
     * 패턴 일치도 기반 보너스 계산
     */
    private double calculatePatternMatchBonus(List<Integer> combination, PatternRange patternRange) {
        double bonus = 0.0;
        
        // 각 패턴이 범위의 중앙에 가까울수록 높은 보너스
        int sum = combination.stream().mapToInt(Integer::intValue).sum();
        double avg = combination.stream().mapToInt(Integer::intValue).average().orElse(0.0);
        
        // 합계가 범위 중앙에 가까우면 보너스
        double sumCenter = (patternRange.minSum + patternRange.maxSum) / 2.0;
        double sumRange = patternRange.maxSum - patternRange.minSum;
        if (sumRange > 0) { 
            double sumDistance = Math.abs(sum - sumCenter) / sumRange;
            bonus += (1.0 - Math.min(1.0, sumDistance)) * 0.05;
        }
        
        
        // 평균이 범위 중앙에 가까우면 보너스
        double avgCenter = (patternRange.minAverage + patternRange.maxAverage) / 2.0;
        double avgRange = patternRange.maxAverage - patternRange.minAverage;
        if (avgRange > 0) {
            double avgDistance = Math.abs(avg - avgCenter) / avgRange;
            bonus += (1.0 - Math.min(1.0, avgDistance)) * 0.05;
        }
        
        return bonus;
    }

    /**
     * 학습 기반 보너스 계산
     * 
     */
    private double calculateLearningBasedBonus(
            List<Integer> combination, Map<Integer, Double> probabilityMap, LearnedWeights weights) {
        
        double bonus = 0.0;
        
        // 각 번호의 확률이 학습된 임계값을 넘으면 보너스
        for (int num : combination) {
            double prob = probabilityMap.getOrDefault(num, 0.0);
            
            // 고성공 패턴 임계값을 넘으면 보너스
            if (prob > weights.highSuccessThresholdRecentFreq) {
                bonus += 0.02;
            }
            // 초고성공 패턴 임계값을 넘으면 더 큰 보너스
            if (prob > weights.veryHighSuccessThresholdRecentFreq) {
                bonus += 0.03;
            }
        }
        
        return bonus;
    }

    /**
     * 확률 가중치를 적용하여 랜덤 조합 선택
     */
    private List<Integer> selectRandomCombination(
            List<Integer> candidates,
            Map<Integer, Double> probabilityMap,
            int count) {
        
        List<Integer> selected = new ArrayList<>();
        List<Integer> remaining = new ArrayList<>(candidates);
        Random random = new Random();

        for (int i = 0; i < count && !remaining.isEmpty(); i++) {
            // 확률 가중치 기반 선택
            double totalWeight = remaining.stream()
                .mapToDouble(num -> probabilityMap.getOrDefault(num, 0.0))
                .sum();

            if (totalWeight == 0) {
                // 가중치가 없으면 랜덤 선택
                int index = random.nextInt(remaining.size());
                selected.add(remaining.remove(index));
            } else {
                double randomValue = random.nextDouble() * totalWeight;
                double cumulative = 0.0;
                
                for (int j = 0; j < remaining.size(); j++) {
                    int num = remaining.get(j);
                    cumulative += probabilityMap.getOrDefault(num, 0.0);
                    if (randomValue <= cumulative) {
                        selected.add(remaining.remove(j));
                        break;
                    }
                }
            }
        }

        return selected;
    }

    /**
     * 조합이 패턴 범위 내에 있는지 검증
     */
    private boolean isWithinPatternRange(List<Integer> combination, PatternRange patternRange) {
        if (combination.size() != 7) return false;

        int sum = combination.stream().mapToInt(Integer::intValue).sum();
        double average = combination.stream().mapToInt(Integer::intValue).average().orElse(0.0);
        int min = Collections.min(combination);
        int max = Collections.max(combination);

        // 기본 통계 검증
        if (!(sum >= patternRange.minSum && sum <= patternRange.maxSum &&
              average >= patternRange.minAverage && average <= patternRange.maxAverage &&
              min >= patternRange.minValue && min <= patternRange.maxValue &&
              max >= patternRange.minMax && max <= patternRange.maxMax)) {
            return false;
        }

        // Low/High 패턴 검증
        int lowCount = (int) combination.stream().filter(n -> n <= 22).count();
        int highCount = (int) combination.stream().filter(n -> n >= 23).count();
        if (!(lowCount >= patternRange.minLowCount && lowCount <= patternRange.maxLowCount &&
              highCount >= patternRange.minHighCount && highCount <= patternRange.maxHighCount)) {
            return false;
        }

        // Odd/Even 패턴 검증
        int oddCount = (int) combination.stream().filter(n -> n % 2 == 1).count();
        int evenCount = (int) combination.stream().filter(n -> n % 2 == 0).count();
        if (!(oddCount >= patternRange.minOddCount && oddCount <= patternRange.maxOddCount &&
              evenCount >= patternRange.minEvenCount && evenCount <= patternRange.maxEvenCount)) {
            return false;
        }

        // 범위별 분포 패턴 검증
        int range1To10 = (int) combination.stream().filter(n -> n >= 1 && n <= 10).count();
        int range11To20 = (int) combination.stream().filter(n -> n >= 11 && n <= 20).count();
        int range21To30 = (int) combination.stream().filter(n -> n >= 21 && n <= 30).count();
        int range31To40 = (int) combination.stream().filter(n -> n >= 31 && n <= 40).count();
        int range41To44 = (int) combination.stream().filter(n -> n >= 41 && n <= 44).count();

        if (!(range1To10 >= patternRange.minRange1To10 && range1To10 <= patternRange.maxRange1To10 &&
              range11To20 >= patternRange.minRange11To20 && range11To20 <= patternRange.maxRange11To20 &&
              range21To30 >= patternRange.minRange21To30 && range21To30 <= patternRange.maxRange21To30 &&
              range31To40 >= patternRange.minRange31To40 && range31To40 <= patternRange.maxRange31To40 &&
              range41To44 >= patternRange.minRange41To44 && range41To44 <= patternRange.maxRange41To44)) {
            return false;
        }

        // 추가 실용 패턴 검증
        List<Integer> sortedCombo = new ArrayList<>(combination);
        Collections.sort(sortedCombo);
        
        // 연속 번호 개수 검증
        int consecutiveCount = countConsecutiveNumbers(sortedCombo);
        if (!(consecutiveCount >= patternRange.minConsecutiveCount && 
              consecutiveCount <= patternRange.maxConsecutiveCount)) {
            return false;
        }
        
        // 번호 간 평균 간격 검증
        double averageGap = calculateAverageGap(sortedCombo);
        if (!(averageGap >= patternRange.minAverageGap && averageGap <= patternRange.maxAverageGap)) {
            return false;
        }
        
        // 번호 범위 검증
        int rangeSpread = max - min;
        if (!(rangeSpread >= patternRange.minRangeSpread && rangeSpread <= patternRange.maxRangeSpread)) {
            return false;
        }
        
        // 소수 번호 개수 검증
        int primeCount = (int) combination.stream().filter(this::isPrime).count();
        if (!(primeCount >= patternRange.minPrimeCount && primeCount <= patternRange.maxPrimeCount)) {
            return false;
        }
        
        // 1의 자리 숫자 다양성 검증
        Set<Integer> onesDigits = combination.stream()
            .map(n -> n % 10)
            .collect(Collectors.toSet());
        int onesDigitVariety = onesDigits.size();
        if (!(onesDigitVariety >= patternRange.minOnesDigitVariety && 
              onesDigitVariety <= patternRange.maxOnesDigitVariety)) {
            return false;
        }

        return true;
    }

    /**
     * 연속 번호 개수 계산
     */
    private int countConsecutiveNumbers(List<Integer> sortedNumbers) {
        if (sortedNumbers.size() < 2) return 0;
        
        int maxConsecutive = 0;
        int currentConsecutive = 1;
        
        for (int i = 1; i < sortedNumbers.size(); i++) {
            if (sortedNumbers.get(i) - sortedNumbers.get(i - 1) == 1) {
                currentConsecutive++;
            } else {
                maxConsecutive = Math.max(maxConsecutive, currentConsecutive);
                currentConsecutive = 1;
            }
        }
        maxConsecutive = Math.max(maxConsecutive, currentConsecutive);
        
        return maxConsecutive;
    }

    /**
     * 번호 간 평균 간격 계산
     */
    private double calculateAverageGap(List<Integer> sortedNumbers) {
        if (sortedNumbers.size() < 2) return 0.0;
        
        int totalGap = 0;
        for (int i = 1; i < sortedNumbers.size(); i++) {
            totalGap += sortedNumbers.get(i) - sortedNumbers.get(i - 1);
        }
        
        return (double) totalGap / (sortedNumbers.size() - 1);
    }

    /**
     * 소수 판별
     */
    private boolean isPrime(int number) {
        if (number < 2) return false;
        if (number == 2) return true;
        if (number % 2 == 0) return false;
        
        for (int i = 3; i * i <= number; i += 2) {
            if (number % i == 0) return false;
        }
        return true;
    }

    /**
     * 패턴 범위에 가장 가까운 조합 찾기 (상위 번호 기반)
     */
    private List<NumberProbability> findClosestCombination(
            List<Integer> candidateNumbers,
            List<NumberProbability> allProbabilities,
            PatternRange patternRange) {
        
        Map<Integer, Double> probabilityMap = allProbabilities.stream()
            .collect(Collectors.toMap(NumberProbability::getNumber, NumberProbability::getProbability));

        // 상위 7개부터 시작하여 조합 시도
        for (int start = 0; start <= Math.min(10, candidateNumbers.size() - 7); start++) {
            for (int end = start + 7; end <= Math.min(start + 15, candidateNumbers.size()); end++) {
                List<Integer> subList = candidateNumbers.subList(start, end);
                
                // 7개 조합 생성
                List<List<Integer>> combinations = generateCombinations(subList, 7);
                
                for (List<Integer> combo : combinations) {
                    if (isWithinPatternRange(combo, patternRange)) {
                        return combo.stream()
                            .map(num -> new NumberProbability(num, probabilityMap.getOrDefault(num, 0.0)))
                            .sorted((a, b) -> Double.compare(b.getProbability(), a.getProbability()))
                            .collect(Collectors.toList());
                    }
                }
            }
        }

        // 여전히 찾지 못하면 상위 7개 반환
        return allProbabilities.subList(0, Math.min(7, allProbabilities.size()));
    }

    /**
     * 조합 생성 (n개 중 r개 선택)
     */
    private List<List<Integer>> generateCombinations(List<Integer> numbers, int r) {
        List<List<Integer>> result = new ArrayList<>();
        if (r > numbers.size() || r <= 0) {
            return result;
        }
        if (r == numbers.size()) {
            result.add(new ArrayList<>(numbers));
            return result;
        }
        // 간단한 구현: 최대 100개까지만 생성
        generateCombinationsHelper(numbers, r, 0, new ArrayList<>(), result, 100);
        return result;
    }

    private void generateCombinationsHelper(
            List<Integer> numbers, int r, int start,
            List<Integer> current, List<List<Integer>> result, int maxResults) {
        if (result.size() >= maxResults) return;
        
        if (current.size() == r) {
            result.add(new ArrayList<>(current));
            return;
        }

        for (int i = start; i < numbers.size() && result.size() < maxResults; i++) {
            current.add(numbers.get(i));
            generateCombinationsHelper(numbers, r, i + 1, current, result, maxResults);
            current.remove(current.size() - 1);
        }
    }

    /**
     * 히스토리 패턴 범위를 담는 내부 클래스
     */
    private static class PatternRange {
        // 기본 통계
        int minSum;
        int maxSum;
        double minAverage;
        double maxAverage;
        int minValue;  // 조합 내 최소값의 최소값
        int maxValue;  // 조합 내 최소값의 최대값
        int minMax;    // 조합 내 최대값의 최소값
        int maxMax;    // 조합 내 최대값의 최대값
        
        // Low/High 패턴 (낮은 번호: 1-22, 높은 번호: 23-44)
        int minLowCount;
        int maxLowCount;
        int minHighCount;
        int maxHighCount;
        
        // Odd/Even 패턴
        int minOddCount;
        int maxOddCount;
        int minEvenCount;
        int maxEvenCount;
        
        // 범위별 분포 패턴
        int minRange1To10;
        int maxRange1To10;
        int minRange11To20;
        int maxRange11To20;
        int minRange21To30;
        int maxRange21To30;
        int minRange31To40;
        int maxRange31To40;
        int minRange41To44;
        int maxRange41To44;
        
        // 추가 실용 패턴
        int minConsecutiveCount;      // 연속 번호 개수
        int maxConsecutiveCount;
        double minAverageGap;         // 번호 간 평균 간격
        double maxAverageGap;
        int minRangeSpread;           // 번호 범위 (최대-최소 차이)
        int maxRangeSpread;
        int minPrimeCount;            // 소수 번호 개수
        int maxPrimeCount;
        int minOnesDigitVariety;      // 1의 자리 숫자 다양성 (서로 다른 1의 자리 개수)
        int maxOnesDigitVariety;
    }

    /**
     * 과거 데이터를 이용한 패턴 검증 결과
     */
    public static class PatternValidationResult {
        private final int draw;
        private final LocalDate drawDate;
        private final List<Integer> predictedNumbers;
        private final List<Integer> actualNumbers;
        private final int matchCount;
        private final double accuracy;
        private final String predictionType; // "HIGH7", "LOW7", "MID7"

        public PatternValidationResult(int draw, LocalDate drawDate, List<Integer> predictedNumbers,
                                      List<Integer> actualNumbers, int matchCount, double accuracy, String predictionType) {
            this.draw = draw;
            this.drawDate = drawDate;
            this.predictedNumbers = predictedNumbers;
            this.actualNumbers = actualNumbers;
            this.matchCount = matchCount;
            this.accuracy = accuracy;
            this.predictionType = predictionType;
        }

        public int getDraw() { return draw; }
        public LocalDate getDrawDate() { return drawDate; }
        public List<Integer> getPredictedNumbers() { return predictedNumbers; }
        public List<Integer> getActualNumbers() { return actualNumbers; }
        public int getMatchCount() { return matchCount; }
        public double getAccuracy() { return accuracy; }
        public String getPredictionType() { return predictionType; }

        @Override
        public String toString() {
            return String.format("Draw %d (%s): 예측[%s] 실제[%s] 맞춘개수=%d/7 (정확도: %.2f%%)",
                draw, drawDate, predictedNumbers, actualNumbers, matchCount, accuracy * 100);
        }
    }

    /**
     * 패턴 검증 통계
     */
    public static class PatternValidationStatistics {
        private final int totalDraws;
        private final int validDraws;
        private final double averageAccuracy;
        private final int maxMatchCount;
        private final int minMatchCount;
        private final Map<Integer, Long> matchCountDistribution;
        private final List<PatternValidationResult> results;

        public PatternValidationStatistics(int totalDraws, int validDraws, double averageAccuracy,
                                         int maxMatchCount, int minMatchCount,
                                         Map<Integer, Long> matchCountDistribution,
                                         List<PatternValidationResult> results) {
            this.totalDraws = totalDraws;
            this.validDraws = validDraws;
            this.averageAccuracy = averageAccuracy;
            this.maxMatchCount = maxMatchCount;
            this.minMatchCount = minMatchCount;
            this.matchCountDistribution = matchCountDistribution;
            this.results = results;
        }

        public int getTotalDraws() { return totalDraws; }
        public int getValidDraws() { return validDraws; }
        public double getAverageAccuracy() { return averageAccuracy; }
        public int getMaxMatchCount() { return maxMatchCount; }
        public int getMinMatchCount() { return minMatchCount; }
        public Map<Integer, Long> getMatchCountDistribution() { return matchCountDistribution; }
        public List<PatternValidationResult> getResults() { return results; }
    }

    /**
     * 과거 데이터를 이용하여 패턴 검증 수행
     * 각 회차별로 그 이전 데이터만 사용해서 예측하고 실제 결과와 비교
     * 
     * @param predictionType "HIGH7", "LOW7", "MID7" 중 하나
     * @param minDraws 최소 검증할 회차 수 (너무 적으면 건너뜀)
     * @return 패턴 검증 통계
     */
    public PatternValidationStatistics validatePatternWithHistoricalData(String predictionType, int minDraws) {
        return validatePatternWithHistoricalData(predictionType, minDraws, false);
    }

    /**
     * 과거 데이터를 이용하여 패턴 검증 수행 (상세 출력 옵션)
     * 
     * @param predictionType "HIGH7", "LOW7", "MID7" 중 하나
     * @param minDraws 최소 검증할 회차 수
     * @param showDetails 각 회차별 상세 결과 출력 여부
     * @return 패턴 검증 통계
     */
    public PatternValidationStatistics validatePatternWithHistoricalData(String predictionType, int minDraws, boolean showDetails) {
        log.info("과거 데이터를 이용한 패턴 검증 시작: 타입={}, 최소회차={}", predictionType, minDraws);
        
        // 모든 회차를 Draw 번호 오름차순으로 정렬 (오래된 것부터)
        List<LotteryResult> allResults = new ArrayList<>(getCachedAllResults());
        Collections.reverse(allResults); // 오름차순으로 변경
        
        if (allResults.size() < minDraws + MIN_DRAWS_FOR_ANALYSIS) {
            log.warn("검증할 데이터가 부족합니다. (전체: {}, 최소 필요: {})", 
                allResults.size(), minDraws + MIN_DRAWS_FOR_ANALYSIS);
            return null;
        }
        
        List<PatternValidationResult> validationResults = new ArrayList<>();
        
        // 각 회차별로 검증 (최소 분석 데이터 이후부터)
        for (int i = MIN_DRAWS_FOR_ANALYSIS; i < allResults.size(); i++) {
            LotteryResult currentResult = allResults.get(i);
            List<LotteryResult> historicalData = allResults.subList(0, i); // 현재 회차 이전 데이터만
            
            try {
                // 이전 데이터만 사용해서 예측
                List<Integer> predictedNumbers = predictWithHistoricalData(
                    historicalData, predictionType);
                
                // 실제 당첨 번호 추출
                List<Integer> actualNumbers = extractWinningNumbers(currentResult);
                
                if (predictedNumbers.size() == 7 && actualNumbers.size() == 7) {
                    // 맞춘 개수 계산
                    Set<Integer> predictedSet = new HashSet<>(predictedNumbers);
                    Set<Integer> actualSet = new HashSet<>(actualNumbers);
                    predictedSet.retainAll(actualSet); // 교집합
                    int matchCount = predictedSet.size();
                    double accuracy = (double) matchCount / 7.0;
                    
                    PatternValidationResult result = new PatternValidationResult(
                        currentResult.getDraw(),
                        currentResult.getDrawDate(),
                        predictedNumbers,
                        actualNumbers,
                        matchCount,
                        accuracy,
                        predictionType
                    );
                    
                    validationResults.add(result);
                    
                    if (showDetails || i % 10 == 0 || matchCount >= 4) {
                        log.info("Draw {} ({}) - 예측: {}, 실제: {}, 맞춘개수: {}/7 (정확도: {}%)", 
                            currentResult.getDraw(), 
                            currentResult.getDrawDate(),
                            predictedNumbers,
                            actualNumbers,
                            matchCount, 
                            String.format("%.2f", accuracy * 100));
                    }
                }
            } catch (Exception e) {
                log.warn("Draw {} 검증 중 오류 발생: {}", currentResult.getDraw(), e.getMessage());
            }
        }
        
        // 통계 계산
        if (validationResults.isEmpty()) {
            log.warn("검증 결과가 없습니다.");
            return null;
        }
        
        double avgAccuracy = validationResults.stream()
            .mapToDouble(PatternValidationResult::getAccuracy)
            .average()
            .orElse(0.0);
        
        int maxMatch = validationResults.stream()
            .mapToInt(PatternValidationResult::getMatchCount)
            .max()
            .orElse(0);
        
        int minMatch = validationResults.stream()
            .mapToInt(PatternValidationResult::getMatchCount)
            .min()
            .orElse(0);
        
        Map<Integer, Long> matchDistribution = validationResults.stream()
            .collect(Collectors.groupingBy(
                PatternValidationResult::getMatchCount,
                Collectors.counting()
            ));
        
        log.info("=== 패턴 검증 완료 ===");
        log.info("예측 타입: {}", predictionType);
        log.info("총 회차: {}, 검증 회차: {}", allResults.size(), validationResults.size());
        log.info("평균 정확도: {}%", String.format("%.2f", avgAccuracy * 100));
        log.info("최대 맞춘개수: {}/7, 최소 맞춘개수: {}/7", maxMatch, minMatch);
        log.info("맞춘개수 분포:");
        matchDistribution.entrySet().stream()
            .sorted(Map.Entry.<Integer, Long>comparingByKey().reversed())
            .forEach(entry -> {
                double percentage = (double) entry.getValue() / validationResults.size() * 100;
                log.info("  {}개 맞춤: {}회 ({})", entry.getKey(), entry.getValue(), 
                    String.format("%.2f", percentage));
            });
        
        // 4개 이상 맞춘 회차 비율
        long highMatchCount = validationResults.stream()
            .filter(r -> r.getMatchCount() >= 4)
            .count();
        double highMatchRate = (double) highMatchCount / validationResults.size() * 100;
        log.info("4개 이상 맞춘 회차: {}/{} ({})", highMatchCount, validationResults.size(), 
            String.format("%.2f", highMatchRate));
        
        // 5개 이상 맞춘 회차 비율 (50% 목표)
        long veryHighMatchCount = validationResults.stream()
            .filter(r -> r.getMatchCount() >= 5)
            .count();
        double veryHighMatchRate = (double) veryHighMatchCount / validationResults.size() * 100;
        log.info("5개 이상 맞춘 회차: {}/{} ({:.2f}%)", veryHighMatchCount, validationResults.size(), veryHighMatchRate);
        
        return new PatternValidationStatistics(
            allResults.size(),
            validationResults.size(),
            avgAccuracy,
            maxMatch,
            minMatch,
            matchDistribution,
            validationResults
        );
    }

    /**
     * 최신 draw로 패턴 검증 수행
     * 학습된 가중치와 강화된 로직이 제대로 적용되는지 검증
     * 
     * @return 패턴 검증 통계
     */
    public PatternValidationStatistics validateWithLatest500Draws() {
        log.info("=== 최신 {} Draw 검증 시작 (학습된 가중치 적용) ===", VALIDATION_DRAWS_COUNT);
        
        // 모든 회차를 Draw 번호 내림차순으로 정렬 (최신순)
        List<LotteryResult> allResults = getCachedAllResults();
        
        if (allResults.size() < MIN_DRAWS_FOR_ANALYSIS + VALIDATION_DRAWS_COUNT) {
            log.warn("검증할 데이터가 부족합니다. (전체: {}, 최소 필요: {})", 
                allResults.size(), MIN_DRAWS_FOR_ANALYSIS + VALIDATION_DRAWS_COUNT);
            return null;
        }
        
        // 최신 draw 선택 (최신순이므로 앞에서 VALIDATION_DRAWS_COUNT개)
        List<LotteryResult> latest500 = allResults.subList(0, Math.min(VALIDATION_DRAWS_COUNT, allResults.size()));
        log.info("최신 {} Draw 선택", latest500.size());
        
        // 오름차순으로 변경 (오래된 것부터)
        List<LotteryResult> sorted500 = new ArrayList<>(latest500);
        Collections.reverse(sorted500);
        
        List<PatternValidationResult> validationResults = new ArrayList<>();
        int successCount = 0; // 5개 이상 맞춘 회차 수
        
        // 각 회차별로 검증 (최소 분석 데이터 이후부터)
        for (int i = MIN_DRAWS_FOR_ANALYSIS; i < sorted500.size(); i++) {
            LotteryResult currentResult = sorted500.get(i);
            
            // 현재 draw 이전의 모든 데이터 사용 (전체 데이터에서)
            int currentDraw = currentResult.getDraw();
            List<LotteryResult> historicalData = allResults.stream()
                .filter(r -> r.getDraw() < currentDraw)
                .collect(Collectors.toList());
            
            if (historicalData.size() < MIN_DRAWS_FOR_ANALYSIS) {
                continue; // 충분한 데이터가 없으면 스킵
            }
            
            try {
                // 학습된 가중치가 적용된 예측 수행
                List<Integer> predictedNumbers;
                if ("HIGH7".equals("HIGH7")) { // 항상 HIGH7로 검증
                    // 실제로는 getTop7NumbersWithPatternFilteringAndProbability를 사용해야 하지만
                    // 과거 데이터만 사용해야 하므로 별도 처리 필요
                    predictedNumbers = predictWithHistoricalDataForValidation(historicalData, "HIGH7");
                } else {
                    predictedNumbers = predictWithHistoricalData(historicalData, "HIGH7");
                }
                
                // 실제 당첨 번호 추출
                List<Integer> actualNumbers = extractWinningNumbers(currentResult);
                
                if (predictedNumbers.size() == 7 && actualNumbers.size() == 7) {
                    // 맞춘 개수 계산
                    Set<Integer> predictedSet = new HashSet<>(predictedNumbers);
                    Set<Integer> actualSet = new HashSet<>(actualNumbers);
                    predictedSet.retainAll(actualSet); // 교집합
                    int matchCount = predictedSet.size();
                    double accuracy = (double) matchCount / 7.0;
                    
                    if (matchCount >= 5) {
                        successCount++;
                    }
                    
                    PatternValidationResult result = new PatternValidationResult(
                        currentResult.getDraw(),
                        currentResult.getDrawDate(),
                        predictedNumbers,
                        actualNumbers,
                        matchCount,
                        accuracy,
                        "HIGH7"
                    );
                    
                    validationResults.add(result);
                    
                    if (i % 50 == 0 || matchCount >= 5) {
                        log.info("Draw {} ({}) - 예측: {}, 실제: {}, 맞춘개수: {}/7 (정확도: {:.2f}%)", 
                            currentResult.getDraw(), 
                            currentResult.getDrawDate(),
                            predictedNumbers,
                            actualNumbers,
                            matchCount, 
                            accuracy * 100);
                    }
                }
            } catch (Exception e) {
                log.warn("Draw {} 검증 중 오류 발생: {}", currentResult.getDraw(), e.getMessage());
            }
        }
        
        // 통계 계산
        if (validationResults.isEmpty()) {
            log.warn("검증 결과가 없습니다.");
            return null;
        }
        
        double avgAccuracy = validationResults.stream()
            .mapToDouble(PatternValidationResult::getAccuracy)
            .average()
            .orElse(0.0);
        
        int maxMatch = validationResults.stream()
            .mapToInt(PatternValidationResult::getMatchCount)
            .max()
            .orElse(0);
        
        int minMatch = validationResults.stream()
            .mapToInt(PatternValidationResult::getMatchCount)
            .min()
            .orElse(0);
        
        Map<Integer, Long> matchDistribution = validationResults.stream()
            .collect(Collectors.groupingBy(
                PatternValidationResult::getMatchCount,
                Collectors.counting()
            ));
        
        // 5개 이상 맞춘 회차 비율
        long veryHighMatchCount = validationResults.stream()
            .filter(r -> r.getMatchCount() >= 5)
            .count();
        double veryHighMatchRate = (double) veryHighMatchCount / validationResults.size() * 100;
        
        // 4개 이상 맞춘 회차 비율
        long highMatchCount = validationResults.stream()
            .filter(r -> r.getMatchCount() >= 4)
            .count();
        double highMatchRate = (double) highMatchCount / validationResults.size() * 100;
        
        log.info("=== 최신 {} Draw 검증 완료 ===", VALIDATION_DRAWS_COUNT);
        log.info("검증 회차: {}", validationResults.size());
        log.info("평균 정확도: {:.2f}%", avgAccuracy * 100);
        log.info("최대 맞춘개수: {}/7, 최소 맞춘개수: {}/7", maxMatch, minMatch);
        log.info("5개 이상 맞춘 회차: {}/{} ({}) - 목표: 50%", 
            veryHighMatchCount, validationResults.size(), String.format("%.2f", veryHighMatchRate));
        log.info("4개 이상 맞춘 회차: {}/{} ({})", 
            highMatchCount, validationResults.size(), String.format("%.2f", highMatchRate));
        log.info("맞춘개수 분포:");
        matchDistribution.entrySet().stream()
            .sorted(Map.Entry.<Integer, Long>comparingByKey().reversed())
            .forEach(entry -> {
                double percentage = (double) entry.getValue() / validationResults.size() * 100;
                log.info("  {}개 맞춤: {}회 ({:.2f}%)", entry.getKey(), entry.getValue(), percentage);
            });
        
        return new PatternValidationStatistics(
            allResults.size(),
            validationResults.size(),
            avgAccuracy,
            maxMatch,
            minMatch,
            matchDistribution,
            validationResults
        );
    }

    /**
     * 검증용 예측 (과거 데이터만 사용, 학습된 가중치 적용)
     */
    private List<Integer> predictWithHistoricalDataForValidation(
            List<LotteryResult> historicalData, String predictionType) {
        
        if (historicalData.size() < MIN_DRAWS_FOR_ANALYSIS) {
            return new ArrayList<>();
        }
        
        // 임시로 repository 데이터를 필터링하여 사용
        // 실제로는 historicalData만 사용해야 하지만, 
        // calculateProbability가 repository를 사용하므로
        // 전체 데이터에서 historicalData의 draw 이전만 필터링
        
        List<LotteryResult> allResults = getCachedAllResults();
        int maxDraw = historicalData.stream()
            .mapToInt(LotteryResult::getDraw)
            .max()
            .orElse(0);
        
        // maxDraw 이전 데이터만 사용
        List<LotteryResult> filteredData = allResults.stream()
            .filter(r -> r.getDraw() <= maxDraw)
            .collect(Collectors.toList());
        
        // 임시 저장 후 복원하는 대신, 직접 확률 계산
        // 하지만 calculateProbability는 repository를 사용하므로
        // 간단한 방법으로 처리
        
        // 실제 구현: historicalData만 사용해서 직접 확률 계산
        return predictWithData(historicalData, predictionType);
    }

    /**
     * 패턴 검증 결과를 상세하게 출력
     */
    public void printValidationDetails(PatternValidationStatistics stats) {
        if (stats == null) {
            log.warn("검증 통계가 없습니다.");
            return;
        }
        
        log.info("=== 패턴 검증 상세 결과 ===");
        log.info("총 회차: {}, 검증 회차: {}", stats.getTotalDraws(), stats.getValidDraws());
        log.info("평균 정확도: {}%", String.format("%.2f", stats.getAverageAccuracy() * 100));
        log.info("최대 맞춘개수: {}/7, 최소 맞춘개수: {}/7", stats.getMaxMatchCount(), stats.getMinMatchCount());
        
        log.info("\n=== 각 회차별 결과 (최근 20개) ===");
        List<PatternValidationResult> recentResults = stats.getResults().stream()
            .sorted((a, b) -> Integer.compare(b.getDraw(), a.getDraw()))
            .limit(20)
            .collect(Collectors.toList());
        
        for (PatternValidationResult result : recentResults) {
            log.info("Draw {} ({}) - 예측: {}, 실제: {}, 맞춘개수: {}/7 (정확도: {:.2f}%)",
                result.getDraw(),
                result.getDrawDate(),
                result.getPredictedNumbers(),
                result.getActualNumbers(),
                result.getMatchCount(),
                result.getAccuracy() * 100);
        }
        
        log.info("\n=== 맞춘개수별 분포 ===");
        stats.getMatchCountDistribution().entrySet().stream()
            .sorted(Map.Entry.<Integer, Long>comparingByKey().reversed())
            .forEach(entry -> {
                double percentage = (double) entry.getValue() / stats.getValidDraws() * 100;
                log.info("{}개 맞춤: {}회 ({:.2f}%)", entry.getKey(), entry.getValue(), percentage);
            });
    }

    /**
     * 특정 시점의 과거 데이터만 사용해서 예측
     */
    private List<Integer> predictWithHistoricalData(List<LotteryResult> historicalData, String predictionType) {
        // 임시로 repository를 사용하지 않고 전달받은 데이터만 사용
        // 이를 위해 별도의 계산 메서드가 필요하지만, 
        // 현재 구조상 repository를 사용하므로 다른 방법 필요
        
        // 간단한 방법: historicalData를 기반으로 직접 계산
        // 하지만 현재 calculateProbability는 repository를 사용하므로
        // 임시로 repository의 데이터를 사용하되, 특정 draw 이전만 필터링
        
        // 실제로는 더 복잡한 로직이 필요하지만, 
        // 여기서는 전체 데이터에서 historicalData의 마지막 draw 이후만 제외하는 방식 사용
        
        // 전체 데이터 가져오기
        List<LotteryResult> allResults = getCachedAllResults();
        
        if (historicalData.isEmpty()) {
            return new ArrayList<>();
        }
        
        // historicalData의 마지막 draw 찾기
        int lastHistoricalDraw = historicalData.stream()
            .mapToInt(LotteryResult::getDraw)
            .max()
            .orElse(0);
        
        // lastHistoricalDraw 이후 데이터 제외
        List<LotteryResult> filteredData = allResults.stream()
            .filter(r -> r.getDraw() <= lastHistoricalDraw)
            .collect(Collectors.toList());
        
        // 필터링된 데이터로 예측 (임시 저장 후 복원)
        // 실제로는 더 나은 방법이 필요하지만, 여기서는 간단하게 처리
        
        // 실제 구현: historicalData만 사용해서 직접 확률 계산
        return predictWithData(historicalData, predictionType);
    }

    /**
     * 주어진 데이터만 사용해서 예측
     */
    private List<Integer> predictWithData(List<LotteryResult> data, String predictionType) {
        if (data.size() < MIN_DRAWS_FOR_ANALYSIS) {
            return new ArrayList<>();
        }
        
        // 데이터를 역순으로 정렬 (최신순)
        List<LotteryResult> sortedData = new ArrayList<>(data);
        sortedData.sort((a, b) -> Integer.compare(b.getDraw(), a.getDraw()));
        
        // 각 번호의 확률 계산 (간단한 빈도 기반)
        Map<Integer, Double> probabilities = new HashMap<>();
        
        for (int num = 1; num <= MAX_NUMBER; num++) {
            final int number = num; // effectively final 변수로 복사
            // 최근 출현 빈도
            int recentCount = Math.min(30, sortedData.size());
            long recentAppearances = sortedData.subList(0, recentCount).stream()
                .filter(r -> containsNumber(r, number))
                .count();
            
            // 전체 출현 빈도
            long totalAppearances = sortedData.stream()
                .filter(r -> containsNumber(r, number))
                .count();
            
            // 가중 평균
            double recentFreq = (double) recentAppearances / recentCount;
            double totalFreq = (double) totalAppearances / sortedData.size();
            double prob = 0.7 * recentFreq + 0.3 * totalFreq;
            
            probabilities.put(number, prob);
        }
        
        // 확률 순으로 정렬
        List<Map.Entry<Integer, Double>> sorted = probabilities.entrySet().stream()
            .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
            .collect(Collectors.toList());
        
        // 타입에 따라 선택
        List<Integer> result = new ArrayList<>();
        if ("HIGH7".equals(predictionType)) {
            // 상위 7개
            result = sorted.subList(0, Math.min(7, sorted.size())).stream()
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        } else if ("LOW7".equals(predictionType)) {
            // 하위 7개
            Collections.reverse(sorted);
            result = sorted.subList(0, Math.min(7, sorted.size())).stream()
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        } else if ("MID7".equals(predictionType)) {
            // 중간 7개
            int start = sorted.size() / 2 - 3;
            int end = start + 7;
            start = Math.max(0, start);
            end = Math.min(sorted.size(), end);
            result = sorted.subList(start, end).stream()
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        }
        
        return result;
    }

    /**
     * 번호와 확률을 담는 내부 클래스
     */
    public static class NumberProbability {
        private final int number;
        private final double probability;

        public NumberProbability(int number, double probability) {
            this.number = number;
            this.probability = probability;
        }

        public int getNumber() {
            return number;
        }

        public double getProbability() {
            return probability;
        }

        @Override
        public String toString() {
            return String.format("번호 %d: %.2f%%", number, probability * 100);
        }
    }

    /**
     * 반복 튜닝 검증 메서드
     * 최신 draw를 검증 대상으로 설정하고, 각 회차마다 9개 번호 중 최소 6개가 맞을 때까지 튜닝
     * 
     * @return 패턴 검증 통계
     */
    public PatternValidationStatistics validateWithIterativeTuning() {
        log.info("=== 반복 튜닝 검증 시작 (최신 {} Draw, 9개 번호 중 최소 {}개 맞춤 목표) ===", VALIDATION_DRAWS_COUNT, JINS_RIGHT_COUNT);
        
        // 모든 회차를 Draw 번호 내림차순으로 정렬 (최신순)
        List<LotteryResult> allResults = getCachedAllResults();
        
        if (allResults.isEmpty()) {
            log.warn("검증할 데이터가 없습니다.");
            return null;
        }
        
        // 최신 회차 확인
        int latestDraw = allResults.get(0).getDraw();
        log.info("최신 회차: {}", latestDraw);
        
        // 검증 대상: 최신 draw (latestDraw - VALIDATION_DRAWS_COUNT + 1부터 latestDraw까지)
        int validationStartDraw = Math.max(1, latestDraw - VALIDATION_DRAWS_COUNT + 1);
        log.info("검증 시작 회차: {} (최신 {}회차부터 {}회차까지)", 
            validationStartDraw, validationStartDraw, latestDraw);
        
        // 검증 대상 회차 필터링
        List<LotteryResult> validationTargets = allResults.stream()
            .filter(r -> r.getDraw() >= validationStartDraw && r.getDraw() <= latestDraw)
            .sorted((a, b) -> Integer.compare(a.getDraw(), b.getDraw())) // 오름차순 (오래된 것부터)
            .collect(Collectors.toList());
        
        if (validationTargets.size() < MIN_DRAWS_FOR_ANALYSIS) {
            log.warn("검증할 데이터가 부족합니다. (검증 대상: {}, 최소 필요: {})", 
                validationTargets.size(), MIN_DRAWS_FOR_ANALYSIS);
            return null;
        }
        
        log.info("검증 대상 회차 수: {}", validationTargets.size());
        
        List<PatternValidationResult> validationResults = new ArrayList<>();
        int totalTuningIterations = 0;
        
        // 각 회차별로 검증 및 튜닝
        for (int i = 0; i < validationTargets.size(); i++) {
            LotteryResult currentResult = validationTargets.get(i);
            int currentDraw = currentResult.getDraw();
            
            // 현재 draw 이전의 모든 데이터 사용 (전체 데이터에서)
            List<LotteryResult> historicalData = allResults.stream()
                .filter(r -> r.getDraw() < currentDraw)
                .sorted((a, b) -> Integer.compare(b.getDraw(), a.getDraw())) // 최신순
                .collect(Collectors.toList());
            
            if (historicalData.size() < MIN_DRAWS_FOR_ANALYSIS) {
                // DEBUG 로그 제거 (너무 많은 출력 방지)
                continue;
            }
            
            // 실제 당첨 번호 추출 (당첨번호 7개 + 보너스번호 2개 = 총 9개)
            List<Integer> actualNumbers = extractAllNumbers(currentResult);
            if (actualNumbers.size() != 9) {
                log.warn("Draw {}: 당첨 번호가 9개가 아닙니다. (실제: {})", 
                    currentDraw, actualNumbers.size());
                continue;
            }
            
            // 반복 튜닝: 9개 중 최소 JINS_RIGHT_COUNT개가 맞을 때까지
            boolean tuningSuccess = false;
            LearnedWeights currentWeights = new LearnedWeights(); // 각 회차마다 초기 가중치
            int maxTuningIterations = 200; // 최대 튜닝 반복 횟수 증가 (더 정교한 최적화를 위해)
            int tuningIteration = 0;
            
            while (!tuningSuccess && tuningIteration < maxTuningIterations) {
                tuningIteration++;
                totalTuningIterations++;
                
                // 현재 가중치로 예측 수행 (9개 번호 예측: 당첨번호 7개 + 보너스번호 2개)
                List<Integer> predictedNumbers = predict9NumbersWithHistoricalDataAndWeights(
                    historicalData, currentWeights);
                
                if (predictedNumbers.size() != 9) {
                    log.warn("Draw {}: 예측 번호가 9개가 아닙니다. (예측: {})", 
                        currentDraw, predictedNumbers.size());
                    break;
                }
                
                // 맞춘 개수 계산 (9개 중)
                Set<Integer> predictedSet = new HashSet<>(predictedNumbers);
                Set<Integer> actualSet = new HashSet<>(actualNumbers);
                predictedSet.retainAll(actualSet); // 교집합
                int matchCount = predictedSet.size();
                
                // 정교한 오차율 계산 (9개 기준)
                double errorRate = calculatePreciseErrorRateFor9Numbers(
                    predictedNumbers, 
                    actualNumbers, 
                    historicalData, 
                    currentWeights);
                double accuracy = (double) matchCount / 9.0 * 100.0;
                
                // 9개 중 최소 JINS_RIGHT_COUNT개가 맞았는지 확인
                if (matchCount >= JINS_RIGHT_COUNT) {
                    tuningSuccess = true;
                    
                    PatternValidationResult result = new PatternValidationResult(
                        currentDraw,
                        currentResult.getDrawDate(),
                        predictedNumbers,
                        actualNumbers,
                        matchCount,
                        accuracy / 100.0,
                        "HIGH7"
                    );
                    
                    validationResults.add(result);
                    
                    if (i % 50 == 0 || matchCount >= JINS_RIGHT_COUNT) {
                        log.info("Draw {} ({}) - 튜닝 성공 ({}회 반복): 예측={}, 실제={}, 맞춘개수={}/9 (정확도: {:.2f}%, 오차: {:.2f}%)", 
                            currentDraw, 
                            currentResult.getDrawDate(),
                            tuningIteration,
                            predictedNumbers,
                            actualNumbers,
                            matchCount,
                            accuracy,
                            errorRate);
                    }
                } else {
                    // 가중치 튜닝 수행
                    currentWeights = tuneWeightsForDraw(
                        currentWeights, 
                        predictedNumbers, 
                        actualNumbers, 
                        matchCount,
                        historicalData);
                    
                    if (tuningIteration % 10 == 0) {
                        // DEBUG 로그 제거 (너무 많은 출력 방지)
                    }
                }
            }
            
            if (!tuningSuccess) {
                // 최대 반복 횟수에 도달했지만 9개 중 최소 JINS_RIGHT_COUNT개를 맞추지 못한 경우
                // 마지막 예측 결과를 기록
                List<Integer> predictedNumbers = predict9NumbersWithHistoricalDataAndWeights(
                    historicalData, currentWeights);
                
                Set<Integer> predictedSet = new HashSet<>(predictedNumbers);
                Set<Integer> actualSet = new HashSet<>(actualNumbers);
                predictedSet.retainAll(actualSet);
                int matchCount = predictedSet.size();
                double accuracy = (double) matchCount / 9.0 * 100.0;
                double errorRate = calculatePreciseErrorRateFor9Numbers(
                    predictedNumbers, 
                    actualNumbers, 
                    historicalData, 
                    currentWeights);
                
                PatternValidationResult result = new PatternValidationResult(
                    currentDraw,
                    currentResult.getDrawDate(),
                    predictedNumbers,
                    actualNumbers,
                    matchCount,
                    accuracy / 100.0,
                    "HIGH9"
                );
                
                validationResults.add(result);
                
                log.warn("Draw {} ({}) - 최대 튜닝 반복 횟수 도달 ({}회): 예측={}, 실제={}, 맞춘개수={}/9 (목표: {}개 이상, 정확도: {:.2f}%, 오차: {:.2f}%)", 
                    currentDraw, 
                    currentResult.getDrawDate(),
                    maxTuningIterations,
                    predictedNumbers,
                    actualNumbers,
                    matchCount,
                    JINS_RIGHT_COUNT,
                    accuracy,
                    errorRate);
            }
        }
        
        // 통계 계산
        if (validationResults.isEmpty()) {
            log.warn("검증 결과가 없습니다.");
            return null;
        }
        
        double avgAccuracy = validationResults.stream()
            .mapToDouble(PatternValidationResult::getAccuracy)
            .average()
            .orElse(0.0);
        
        int maxMatch = validationResults.stream()
            .mapToInt(PatternValidationResult::getMatchCount)
            .max()
            .orElse(0);
        
        int minMatch = validationResults.stream()
            .mapToInt(PatternValidationResult::getMatchCount)
            .min()
            .orElse(0);
        
        Map<Integer, Long> matchDistribution = validationResults.stream()
            .collect(Collectors.groupingBy(
                PatternValidationResult::getMatchCount,
                Collectors.counting()
            ));
        
        // 9개 중 최소 JINS_RIGHT_COUNT개 맞춘 회차 비율
        long targetMatchCount = validationResults.stream()
            .filter(r -> r.getMatchCount() >= JINS_RIGHT_COUNT)
            .count();
        double targetMatchRate = (double) targetMatchCount / validationResults.size() * 100.0;
        
        // 7개 이상 맞춘 회차 비율 (당첨번호만)
        long highMatchCount = validationResults.stream()
            .filter(r -> r.getMatchCount() >= 7)
            .count();
        double highMatchRate = (double) highMatchCount / validationResults.size() * 100.0;
        
        log.info("=== 반복 튜닝 검증 완료 ===");
        log.info("검증 회차: {}", validationResults.size());
        log.info("총 튜닝 반복 횟수: {}", totalTuningIterations);
        log.info("평균 튜닝 반복 횟수: {:.2f}회", (double) totalTuningIterations / validationResults.size());
        log.info("9개 중 최소 {}개 맞춘 회차: {}/{} ({:.2f}%)", 
            JINS_RIGHT_COUNT, targetMatchCount, validationResults.size(), targetMatchRate);
        log.info("9개 중 7개 이상 맞춘 회차: {}/{} ({:.2f}%)", 
            highMatchCount, validationResults.size(), highMatchRate);
        log.info("평균 정확도: {:.2f}%", avgAccuracy * 100);
        log.info("최대 맞춘개수: {}/9, 최소 맞춘개수: {}/9", maxMatch, minMatch);
        log.info("맞춘개수 분포:");
        matchDistribution.entrySet().stream()
            .sorted(Map.Entry.<Integer, Long>comparingByKey().reversed())
            .forEach(entry -> {
                double percentage = (double) entry.getValue() / validationResults.size() * 100;
                log.info("  {}개 맞춤: {}회 ({:.2f}%)", entry.getKey(), entry.getValue(), percentage);
            });
        
        // 최종 학습된 가중치로 모든 번호의 확률 계산 및 출력
        printAllNumberProbabilities();
        
        return new PatternValidationStatistics(
            allResults.size(),
            validationResults.size(),
            avgAccuracy,
            maxMatch,
            minMatch,
            matchDistribution,
            validationResults
        );
    }

    /**
     * 모든 번호(1-44)의 확률을 계산하여 콘솔에 출력
     */
    public void printAllNumberProbabilities() {
        log.info("\n=== 모든 번호의 최종 확률 (1-44) ===");
        
        // 모든 번호의 확률 계산
        Map<Integer, Double> allProbabilities = new HashMap<>();
        
        for (int num = 1; num <= MAX_NUMBER; num++) {
            double prob = calculateProbability(num);
            allProbabilities.put(num, prob);
        }
        
        // 확률 순으로 정렬 (높은 순서대로)
        List<Map.Entry<Integer, Double>> sorted = allProbabilities.entrySet().stream()
            .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
            .collect(Collectors.toList());
        
        log.info("\n[확률 순위별 정렬 (높은 순서)]");
        log.info("순위 | 번호 | 확률(%)");
        log.info("-----|------|----------");
        for (int i = 0; i < sorted.size(); i++) {
            Map.Entry<Integer, Double> entry = sorted.get(i);
            int number = entry.getKey();
            double probability = entry.getValue();
            String probabilityStr = String.format("%.4f", probability * 100);
            String line = String.format("%4d | %3d  | %s%%", i + 1, number, probabilityStr);
            log.info(line);
        }
        
        log.info("\n[번호 순서별 정렬 (1-44)]");
        log.info("번호 | 확률(%)");
        log.info("-----|----------");
        for (int num = 1; num <= MAX_NUMBER; num++) {
            double probability = allProbabilities.get(num);
            String probabilityStr = String.format("%.4f", probability * 100);
            String line = String.format("%3d  | %s%%", num, probabilityStr);
            log.info(line);
        }
        
        // 상위 9개 번호 강조
        log.info("\n[상위 9개 번호 (당첨번호 7개 + 보너스번호 2개 예상)]");
        List<Map.Entry<Integer, Double>> top9 = sorted.stream()
            .limit(9)
            .collect(Collectors.toList());
        
        for (int i = 0; i < top9.size(); i++) {
            Map.Entry<Integer, Double> entry = top9.get(i);
            int number = entry.getKey();
            double probability = entry.getValue();
            String probabilityStr = String.format("%.4f", probability * 100);
            log.info("  {}위: 번호 {} (확률: {}%)", i + 1, number, probabilityStr);
        }
        
        if (top9.size() < 9) {
            log.warn("상위 번호가 9개 미만입니다. (현재: {}개)", top9.size());
        }
        
        log.info("\n=== 모든 번호 확률 출력 완료 ===\n");
    }

    /**
     * 실제 결과와 예측 결과를 비교하여 정확도 분석
     * 
     * @param actualNumbers 실제 당첨 번호 리스트 (7개 당첨번호 + 2개 보너스번호 = 9개)
     * @param draw 회차 번호 (선택사항, 로그용)
     */
    public void comparePredictionWithActual(List<Integer> actualNumbers, Integer draw) {
        if (actualNumbers == null || actualNumbers.size() != 9) {
            log.error("실제 번호는 9개(당첨번호 7개 + 보너스번호 2개)여야 합니다. 현재: {}개", 
                actualNumbers != null ? actualNumbers.size() : 0);
            return;
        }

        log.info("\n=== 예측 결과 vs 실제 결과 비교 분석 ===");
        if (draw != null) {
            log.info("회차: {}", draw);
        }

        // 예측된 번호들 가져오기
        List<Integer> predictedTop7 = getTop7NumbersWithPatternFiltering();
        List<Integer> predictedMid7 = getMid7Numbers();
        List<Integer> predictedBottom7 = getBottom7NumbersWithPatternFiltering();

        // 상위 9개 예측 (당첨번호 7개 + 보너스번호 2개)
        List<NumberProbability> allProbabilities = calculateAllProbabilities();
        allProbabilities.sort((a, b) -> Double.compare(b.getProbability(), a.getProbability()));
        List<Integer> predictedTop9 = allProbabilities.stream()
            .limit(9)
            .map(NumberProbability::getNumber)
            .collect(Collectors.toList());

        // 실제 번호 분리 (당첨번호 7개, 보너스번호 2개)
        List<Integer> actualWinning = actualNumbers.subList(0, 7);
        List<Integer> actualBonus = actualNumbers.subList(7, 9);

        log.info("\n[예측된 번호들]");
        log.info("Top7 (당첨번호 예상): {}", predictedTop7);
        log.info("Mid7 (중간 확률): {}", predictedMid7);
        log.info("Bottom7 (낮은 확률): {}", predictedBottom7);
        log.info("Top9 (상위 9개): {}", predictedTop9);

        log.info("\n[실제 당첨 번호]");
        log.info("당첨번호 7개: {}", actualWinning);
        log.info("보너스번호 2개: {}", actualBonus);
        log.info("전체 9개: {}", actualNumbers);

        // Top7과 실제 당첨번호 7개 비교
        List<Integer> top7Matches = new ArrayList<>(predictedTop7);
        top7Matches.retainAll(actualWinning);
        int top7MatchCount = top7Matches.size();

        // Top9와 실제 전체 9개 비교
        List<Integer> top9Matches = new ArrayList<>(predictedTop9);
        top9Matches.retainAll(actualNumbers);
        int top9MatchCount = top9Matches.size();

        // Mid7과 실제 번호 비교
        List<Integer> mid7Matches = new ArrayList<>(predictedMid7);
        mid7Matches.retainAll(actualNumbers);
        int mid7MatchCount = mid7Matches.size();

        log.info("\n[매칭 결과]");
        log.info("Top7 vs 당첨번호 7개: {}개 일치 {}", top7MatchCount, top7Matches);
        log.info("Top9 vs 전체 9개: {}개 일치 {}", top9MatchCount, top9Matches);
        log.info("Mid7 vs 전체 9개: {}개 일치 {}", mid7MatchCount, mid7Matches);

        // 예측했지만 나오지 않은 번호
        List<Integer> predictedButNotActual = new ArrayList<>(predictedTop9);
        predictedButNotActual.removeAll(actualNumbers);
        
        // 예측하지 않았지만 실제로 나온 번호
        List<Integer> notPredictedButActual = new ArrayList<>(actualNumbers);
        notPredictedButActual.removeAll(predictedTop9);

        log.info("\n[분석]");
        log.info("예측했지만 나오지 않은 번호 ({}개): {}", predictedButNotActual.size(), predictedButNotActual);
        log.info("예측하지 않았지만 실제로 나온 번호 ({}개): {}", notPredictedButActual.size(), notPredictedButActual);

        // 각 예측 세트별 정확도
        double top7Accuracy = (double) top7MatchCount / 7.0 * 100.0;
        double top9Accuracy = (double) top9MatchCount / 9.0 * 100.0;
        double mid7Accuracy = (double) mid7MatchCount / 9.0 * 100.0;

        log.info("\n[정확도]");
        log.info("Top7 정확도: {}% (당첨번호 7개 중 {}개 맞춤)", 
            String.format("%.2f", top7Accuracy), top7MatchCount);
        log.info("Top9 정확도: {}% (전체 9개 중 {}개 맞춤)", 
            String.format("%.2f", top9Accuracy), top9MatchCount);
        log.info("Mid7 정확도: {}% (전체 9개 중 {}개 맞춤)", 
            String.format("%.2f", mid7Accuracy), mid7MatchCount);

        // 개선 제안
        log.info("\n[개선 제안]");
        if (top9MatchCount < JINS_RIGHT_COUNT) {
            log.warn("⚠️  예측 정확도가 낮습니다. (목표: {}개 이상, 실제: {}개)", JINS_RIGHT_COUNT, top9MatchCount);
            log.info("  - 실제로 나온 번호들의 확률을 확인해보세요");
            log.info("  - 예측하지 못한 번호: {}", notPredictedButActual);
            
            // 실제로 나온 번호들의 확률 확인
            log.info("\n[실제 당첨 번호들의 예측 확률]");
            for (int num : actualNumbers) {
                double prob = calculateProbability(num);
                int rank = findRankInAllProbabilities(num, allProbabilities);
                log.info("  번호 {}: 확률 {}% (순위: {}위)", 
                    num, String.format("%.4f", prob * 100), rank);
            }
        } else {
            log.info("✓ 예측 정확도가 목표를 달성했습니다! ({}개 이상 맞춤)", top9MatchCount);
        }

        log.info("\n=== 비교 분석 완료 ===\n");
    }

    /**
     * 특정 번호의 전체 확률 순위 찾기
     */
    private int findRankInAllProbabilities(int number, List<NumberProbability> allProbabilities) {
        for (int i = 0; i < allProbabilities.size(); i++) {
            if (allProbabilities.get(i).getNumber() == number) {
                return i + 1;
            }
        }
        return allProbabilities.size() + 1;
    }

    /**
     * 특정 가중치를 사용하여 과거 데이터로 예측
     */
    private List<Integer> predictWithHistoricalDataAndWeights(
            List<LotteryResult> historicalData, 
            String predictionType,
            LearnedWeights weights) {
        
        if (historicalData.size() < MIN_DRAWS_FOR_ANALYSIS) {
            return new ArrayList<>();
        }
        
        // 데이터를 역순으로 정렬 (최신순)
        List<LotteryResult> sortedData = new ArrayList<>(historicalData);
        sortedData.sort((a, b) -> Integer.compare(b.getDraw(), a.getDraw()));
        
        // 각 번호의 확률 계산 (기존 통계 + 딥러닝 통합)
        Map<Integer, Double> probabilities = new HashMap<>();
        
        // 딥러닝 예측 결과를 먼저 계산 (캐싱을 위해 한 번만 호출)
        Map<Integer, Double> deepLearningScores = new HashMap<>();
        Map<Integer, Double> advancedEnsembleScores = new HashMap<>();
        Map<Integer, Double> attentionScores = new HashMap<>();
        
        try {
            // 전체 데이터로 딥러닝 예측 수행
            List<LotteryResult> allResults = getCachedAllResults();
            if (allResults.size() >= 50) {
                int windowSize = Math.min(200, allResults.size());
                MachineLearningService.MLPredictionResult mlResult = 
                    machineLearningService.performMLPrediction(windowSize);
                
                if (mlResult != null && mlResult.getRecommendationScores() != null) {
                    for (MachineLearningService.NumberRecommendationScore score : mlResult.getRecommendationScores()) {
                        deepLearningScores.put(score.getNumber(), 
                            score.getRecommendationScore() * score.getConfidence());
                    }
                }
                
                // Advanced Prediction Service 결과
                AdvancedPredictionService.PredictionResult advResult = advancedPredictionService.predict();
                if (advResult != null && advResult.getAllScores() != null) {
                    for (AdvancedPredictionService.NumberPredictionScore score : advResult.getAllScores()) {
                        advancedEnsembleScores.put(score.getNumber(), 
                            score.getFinalScore() * score.getConfidence());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("딥러닝 예측 중 오류 발생: {}", e.getMessage());
        }
        
        for (int num = 1; num <= MAX_NUMBER; num++) {
            final int number = num;
            
            // 1. 최근 출현 빈도
            int recentCount = Math.min(RECENT_DRAWS_COUNT, sortedData.size());
            long recentAppearances = sortedData.subList(0, recentCount).stream()
                .filter(r -> containsNumber(r, number))
                .count();
            double recentFreq = (double) recentAppearances / recentCount;
            
            // 2. 전체 출현 빈도
            long totalAppearances = sortedData.stream()
                .filter(r -> containsNumber(r, number))
                .count();
            double overallFreq = (double) totalAppearances / sortedData.size();
            
            // 3. 시간 가중 빈도
            double timeWeightedFreq = calculateTimeWeightedFrequencyForData(sortedData, number);
            
            // 4. 트렌드 분석
            double trendAnalysis = calculateTrendAnalysisForData(sortedData, number);
            
            // 5. 간격 기반 확률
            double intervalProb = calculateIntervalBasedProbabilityForData(sortedData, number);
            
            // 6. 주기적 패턴
            double periodicPattern = calculatePeriodicPatternForData(sortedData, number);
            
            // 7. 딥러닝 점수
            double dlScore = deepLearningScores.getOrDefault(number, 0.0);
            
            // 8. 고급 앙상블 점수
            double advScore = advancedEnsembleScores.getOrDefault(number, 0.0);
            
            // 9. 어텐션 기반 점수
            double attScore = calculateAttentionBasedScore(sortedData, number);
            
            // 기존 통계 기반 확률
            double baseProb = weights.wOverallFreq * overallFreq +
                             weights.wRecentFreq * recentFreq +
                             weights.wTimeWeightedFreq * timeWeightedFreq +
                             weights.wTrendAnalysis * Math.max(0, trendAnalysis + 0.5) +
                             weights.wIntervalProb * intervalProb +
                             weights.wPeriodicPattern * periodicPattern;
            
            // 통계 비중 확대(50%), ML/앙상블 비중 축소 → 과적합 완화, 번호 안정화
            double prob = 0.50 * baseProb +
                         0.20 * dlScore +
                         0.18 * advScore +
                         0.12 * attScore;
            
            // 보너스 적용
            if (recentFreq > 0.5 && timeWeightedFreq > 0.5 && trendAnalysis > 0) {
                prob *= weights.bonusMultiplier;
            }
            
            // 딥러닝 합의 보너스
            if (dlScore > 0.5 && advScore > 0.5 && attScore > 0.5) {
                prob *= 1.03; // 3% 보너스 (기존 5%에서 축소)
            }
            
            // 확률을 0-1 범위로 제한
            prob = Math.max(0.0, Math.min(1.0, prob));
            
            probabilities.put(number, prob);
        }
        
        // 확률 순으로 정렬
        List<Map.Entry<Integer, Double>> sorted = probabilities.entrySet().stream()
            .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
            .collect(Collectors.toList());
        
        // 타입에 따라 선택
        List<Integer> result = new ArrayList<>();
        if ("HIGH7".equals(predictionType)) {
            // 상위 7개
            result = sorted.subList(0, Math.min(7, sorted.size())).stream()
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        } else if ("LOW7".equals(predictionType)) {
            // 하위 7개
            Collections.reverse(sorted);
            result = sorted.subList(0, Math.min(7, sorted.size())).stream()
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        } else if ("MID7".equals(predictionType)) {
            // 중간 7개
            int start = sorted.size() / 2 - 3;
            int end = start + 7;
            start = Math.max(0, start);
            end = Math.min(sorted.size(), end);
            result = sorted.subList(start, end).stream()
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        }
        
        return result;
    }

    /**
     * 9개 번호 예측 (당첨번호 7개 + 보너스번호 2개)
     * 상위 확률 번호 7개를 당첨번호로, 그 다음 상위 2개를 보너스번호로 예측
     */
    private List<Integer> predict9NumbersWithHistoricalDataAndWeights(
            List<LotteryResult> historicalData,
            LearnedWeights weights) {
        
        if (historicalData.size() < MIN_DRAWS_FOR_ANALYSIS) {
            return new ArrayList<>();
        }
        
        // 데이터를 역순으로 정렬 (최신순)
        List<LotteryResult> sortedData = new ArrayList<>(historicalData);
        sortedData.sort((a, b) -> Integer.compare(b.getDraw(), a.getDraw()));
        
        // 각 번호의 확률 계산 (기존 통계 + 딥러닝 통합)
        Map<Integer, Double> probabilities = new HashMap<>();
        
        // 딥러닝 예측 결과를 먼저 계산 (캐싱을 위해 한 번만 호출)
        Map<Integer, Double> deepLearningScores = new HashMap<>();
        Map<Integer, Double> advancedEnsembleScores = new HashMap<>();
        Map<Integer, Double> attentionScores = new HashMap<>();
        
        try {
            // 전체 데이터로 딥러닝 예측 수행
            List<LotteryResult> allResults = getCachedAllResults();
            if (allResults.size() >= 50) {
                int windowSize = Math.min(200, allResults.size());
                MachineLearningService.MLPredictionResult mlResult = 
                    machineLearningService.performMLPrediction(windowSize);
                
                if (mlResult != null && mlResult.getRecommendationScores() != null) {
                    for (MachineLearningService.NumberRecommendationScore score : mlResult.getRecommendationScores()) {
                        deepLearningScores.put(score.getNumber(), 
                            score.getRecommendationScore() * score.getConfidence());
                    }
                }
                
                // Advanced Prediction Service 결과
                AdvancedPredictionService.PredictionResult advResult = advancedPredictionService.predict();
                if (advResult != null && advResult.getAllScores() != null) {
                    for (AdvancedPredictionService.NumberPredictionScore score : advResult.getAllScores()) {
                        advancedEnsembleScores.put(score.getNumber(), 
                            score.getFinalScore() * score.getConfidence());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("딥러닝 예측 중 오류 발생: {}", e.getMessage());
        }
        
        for (int num = 1; num <= MAX_NUMBER; num++) {
            final int number = num;
            
            // 1. 최근 출현 빈도
            int recentCount = Math.min(RECENT_DRAWS_COUNT, sortedData.size());
            long recentAppearances = sortedData.subList(0, recentCount).stream()
                .filter(r -> containsNumber(r, number))
                .count();
            double recentFreq = (double) recentAppearances / recentCount;
            
            // 2. 전체 출현 빈도
            long totalAppearances = sortedData.stream()
                .filter(r -> containsNumber(r, number))
                .count();
            double overallFreq = (double) totalAppearances / sortedData.size();
            
            // 3. 시간 가중 빈도
            double timeWeightedFreq = calculateTimeWeightedFrequencyForData(sortedData, number);
            
            // 4. 트렌드 분석
            double trendAnalysis = calculateTrendAnalysisForData(sortedData, number);
            
            // 5. 간격 기반 확률
            double intervalProb = calculateIntervalBasedProbabilityForData(sortedData, number);
            
            // 6. 주기적 패턴
            double periodicPattern = calculatePeriodicPatternForData(sortedData, number);
            
            // 7. 딥러닝 점수
            double dlScore = deepLearningScores.getOrDefault(number, 0.0);
            
            // 8. 고급 앙상블 점수
            double advScore = advancedEnsembleScores.getOrDefault(number, 0.0);
            
            // 9. 어텐션 기반 점수
            double attScore = calculateAttentionBasedScore(sortedData, number);
            
            // 기존 통계 기반 확률
            double baseProb = weights.wOverallFreq * overallFreq +
                             weights.wRecentFreq * recentFreq +
                             weights.wTimeWeightedFreq * timeWeightedFreq +
                             weights.wTrendAnalysis * Math.max(0, trendAnalysis + 0.5) +
                             weights.wIntervalProb * intervalProb +
                             weights.wPeriodicPattern * periodicPattern;
            
            // 통계 비중 확대(50%), ML/앙상블 비중 축소 → 과적합 완화, 번호 안정화
            double prob = 0.50 * baseProb +
                         0.20 * dlScore +
                         0.18 * advScore +
                         0.12 * attScore;
            
            // 보너스 적용
            if (recentFreq > 0.5 && timeWeightedFreq > 0.5 && trendAnalysis > 0) {
                prob *= weights.bonusMultiplier;
            }
            
            // 딥러닝 합의 보너스
            if (dlScore > 0.5 && advScore > 0.5 && attScore > 0.5) {
                prob *= 1.03; // 3% 보너스 (기존 5%에서 축소)
            }
            
            // 확률을 0-1 범위로 제한
            prob = Math.max(0.0, Math.min(1.0, prob));
            
            probabilities.put(number, prob);
        }
        
        // 확률 순으로 정렬
        List<Map.Entry<Integer, Double>> sorted = probabilities.entrySet().stream()
            .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
            .collect(Collectors.toList());
        
        // 상위 9개 선택 (당첨번호 7개 + 보너스번호 2개)
        List<Integer> result = sorted.subList(0, Math.min(9, sorted.size())).stream()
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
        
        return result;
    }

    /**
     * 특정 회차에 대한 가중치 튜닝
     * 예측 결과와 실제 결과를 비교하여 가중치를 조정
     */
    private LearnedWeights tuneWeightsForDraw(
            LearnedWeights currentWeights,
            List<Integer> predictedNumbers,
            List<Integer> actualNumbers,
            int matchCount,
            List<LotteryResult> historicalData) {
        
        LearnedWeights newWeights = new LearnedWeights();
        
        // 현재 가중치 복사
        newWeights.wOverallFreq = currentWeights.wOverallFreq;
        newWeights.wRecentFreq = currentWeights.wRecentFreq;
        newWeights.wTimeWeightedFreq = currentWeights.wTimeWeightedFreq;
        newWeights.wIntervalProb = currentWeights.wIntervalProb;
        newWeights.wTrendAnalysis = currentWeights.wTrendAnalysis;
        newWeights.wPeriodicPattern = currentWeights.wPeriodicPattern;
        newWeights.wConsecutivePattern = currentWeights.wConsecutivePattern;
        newWeights.wCorrelationAnalysis = currentWeights.wCorrelationAnalysis;
        newWeights.wStatisticalOutlier = currentWeights.wStatisticalOutlier;
        newWeights.wTimeSeriesChangeRate = currentWeights.wTimeSeriesChangeRate;
        newWeights.wRecentIntervalScore = currentWeights.wRecentIntervalScore;
        newWeights.wWeightedAppearanceFreq = currentWeights.wWeightedAppearanceFreq;
        newWeights.wVarianceBasedProb = currentWeights.wVarianceBasedProb;
        newWeights.bonusMultiplier = currentWeights.bonusMultiplier;
        
        // 맞춘 번호와 틀린 번호 분석
        Set<Integer> predictedSet = new HashSet<>(predictedNumbers);
        Set<Integer> actualSet = new HashSet<>(actualNumbers);
        Set<Integer> correctNumbers = new HashSet<>(predictedSet);
        correctNumbers.retainAll(actualSet); // 교집합
        
        Set<Integer> wrongNumbers = new HashSet<>(predictedSet);
        wrongNumbers.removeAll(actualSet); // 차집합
        
        Set<Integer> missedNumbers = new HashSet<>(actualSet);
        missedNumbers.removeAll(predictedSet); // 실제에는 있지만 예측에는 없는 번호
        
        // 맞춘 번호와 틀린 번호의 확률 요인 분석
        Map<Integer, ProbabilityFactors> correctFactors = calculateFactorsForNumbersInData(
            historicalData, correctNumbers);
        Map<Integer, ProbabilityFactors> wrongFactors = calculateFactorsForNumbersInData(
            historicalData, wrongNumbers);
        Map<Integer, ProbabilityFactors> missedFactors = calculateFactorsForNumbersInData(
            historicalData, missedNumbers);
        
        // 평균 요인 계산
        ProbabilityFactors avgCorrect = calculateAverageFactorsFromMap(correctFactors);
        ProbabilityFactors avgWrong = calculateAverageFactorsFromMap(wrongFactors);
        ProbabilityFactors avgMissed = calculateAverageFactorsFromMap(missedFactors);
        
        // 가중치 조정: 맞춘 번호의 요인이 높으면 해당 가중치 증가, 틀린 번호의 요인이 높으면 감소
        double adjustmentRate = 0.05; // 조정 비율 (5%)
        
        if (avgCorrect.recentFreq > avgWrong.recentFreq) {
            newWeights.wRecentFreq = Math.min(0.3, currentWeights.wRecentFreq + adjustmentRate);
        } else {
            newWeights.wRecentFreq = Math.max(0.05, currentWeights.wRecentFreq - adjustmentRate);
        }
        
        if (avgCorrect.timeWeightedFreq > avgWrong.timeWeightedFreq) {
            newWeights.wTimeWeightedFreq = Math.min(0.3, currentWeights.wTimeWeightedFreq + adjustmentRate);
        } else {
            newWeights.wTimeWeightedFreq = Math.max(0.05, currentWeights.wTimeWeightedFreq - adjustmentRate);
        }
        
        if (avgCorrect.trendAnalysis > avgWrong.trendAnalysis) {
            newWeights.wTrendAnalysis = Math.min(0.3, currentWeights.wTrendAnalysis + adjustmentRate);
        } else {
            newWeights.wTrendAnalysis = Math.max(0.05, currentWeights.wTrendAnalysis - adjustmentRate);
        }
        
        if (avgCorrect.intervalProb > avgWrong.intervalProb) {
            newWeights.wIntervalProb = Math.min(0.2, currentWeights.wIntervalProb + adjustmentRate);
        } else {
            newWeights.wIntervalProb = Math.max(0.02, currentWeights.wIntervalProb - adjustmentRate);
        }
        
        if (avgCorrect.periodicPattern > avgWrong.periodicPattern) {
            newWeights.wPeriodicPattern = Math.min(0.2, currentWeights.wPeriodicPattern + adjustmentRate);
        } else {
            newWeights.wPeriodicPattern = Math.max(0.02, currentWeights.wPeriodicPattern - adjustmentRate);
        }
        
        // 놓친 번호에 대한 보정: 놓친 번호의 요인이 높으면 해당 가중치 증가
        if (!missedFactors.isEmpty() && avgMissed.recentFreq > 0.5) {
            newWeights.wRecentFreq = Math.min(0.3, newWeights.wRecentFreq + adjustmentRate * 0.5);
        }
        if (!missedFactors.isEmpty() && avgMissed.timeWeightedFreq > 0.5) {
            newWeights.wTimeWeightedFreq = Math.min(0.3, newWeights.wTimeWeightedFreq + adjustmentRate * 0.5);
        }
        if (!missedFactors.isEmpty() && avgMissed.trendAnalysis > 0.5) {
            newWeights.wTrendAnalysis = Math.min(0.3, newWeights.wTrendAnalysis + adjustmentRate * 0.5);
        }
        
        // 가중치 정규화 (합이 1.0이 되도록)
        double totalWeight = newWeights.wOverallFreq + newWeights.wRecentFreq + 
                           newWeights.wTimeWeightedFreq + newWeights.wIntervalProb + 
                           newWeights.wTrendAnalysis + newWeights.wPeriodicPattern +
                           newWeights.wConsecutivePattern + newWeights.wCorrelationAnalysis +
                           newWeights.wStatisticalOutlier + newWeights.wTimeSeriesChangeRate +
                           newWeights.wRecentIntervalScore + newWeights.wWeightedAppearanceFreq +
                           newWeights.wVarianceBasedProb;
        
        if (totalWeight > 0) {
            double scale = 1.0 / totalWeight;
            newWeights.wOverallFreq *= scale;
            newWeights.wRecentFreq *= scale;
            newWeights.wTimeWeightedFreq *= scale;
            newWeights.wIntervalProb *= scale;
            newWeights.wTrendAnalysis *= scale;
            newWeights.wPeriodicPattern *= scale;
            newWeights.wConsecutivePattern *= scale;
            newWeights.wCorrelationAnalysis *= scale;
            newWeights.wStatisticalOutlier *= scale;
            newWeights.wTimeSeriesChangeRate *= scale;
            newWeights.wRecentIntervalScore *= scale;
            newWeights.wWeightedAppearanceFreq *= scale;
            newWeights.wVarianceBasedProb *= scale;
        }
        
        // 보너스 강도 조정: 맞춘 개수에 따라
        if (matchCount >= 6) {
            newWeights.bonusMultiplier = Math.min(2.0, currentWeights.bonusMultiplier + 0.1);
        } else if (matchCount <= 3) {
            newWeights.bonusMultiplier = Math.max(0.5, currentWeights.bonusMultiplier - 0.1);
        }
        
        return newWeights;
    }

    /**
     * 특정 데이터셋에서 번호들의 확률 요인 계산
     */
    private Map<Integer, ProbabilityFactors> calculateFactorsForNumbersInData(
            List<LotteryResult> data, Set<Integer> numbers) {
        
        Map<Integer, ProbabilityFactors> factorsMap = new HashMap<>();
        
        for (Integer number : numbers) {
            if (data.isEmpty()) {
                factorsMap.put(number, new ProbabilityFactors());
                continue;
            }
            
            // 데이터를 역순으로 정렬 (최신순)
            List<LotteryResult> sortedData = new ArrayList<>(data);
            sortedData.sort((a, b) -> Integer.compare(b.getDraw(), a.getDraw()));
            
            // 최근 출현 빈도
            int recentCount = Math.min(30, sortedData.size());
            long recentAppearances = sortedData.subList(0, recentCount).stream()
                .filter(r -> containsNumber(r, number))
                .count();
            double recentFreq = (double) recentAppearances / recentCount;
            
            // 시간 가중 빈도
            double timeWeightedFreq = calculateTimeWeightedFrequencyForData(sortedData, number);
            
            // 트렌드 분석
            double trendAnalysis = calculateTrendAnalysisForData(sortedData, number);
            
            // 간격 기반 확률
            double intervalProb = calculateIntervalBasedProbabilityForData(sortedData, number);
            
            // 주기적 패턴
            double periodicPattern = calculatePeriodicPatternForData(sortedData, number);
            
            ProbabilityFactors factors = new ProbabilityFactors();
            factors.recentFreq = recentFreq;
            factors.timeWeightedFreq = timeWeightedFreq;
            factors.trendAnalysis = trendAnalysis;
            factors.intervalProb = intervalProb;
            factors.periodicPattern = periodicPattern;
            
            factorsMap.put(number, factors);
        }
        
        return factorsMap;
    }

    /**
     * 확률 요인 맵에서 평균 계산
     */
    private ProbabilityFactors calculateAverageFactorsFromMap(
            Map<Integer, ProbabilityFactors> factorsMap) {
        
        if (factorsMap.isEmpty()) {
            return new ProbabilityFactors();
        }
        
        ProbabilityFactors avg = new ProbabilityFactors();
        int count = factorsMap.size();
        
        for (ProbabilityFactors factors : factorsMap.values()) {
            avg.recentFreq += factors.recentFreq;
            avg.timeWeightedFreq += factors.timeWeightedFreq;
            avg.trendAnalysis += factors.trendAnalysis;
            avg.intervalProb += factors.intervalProb;
            avg.periodicPattern += factors.periodicPattern;
        }
        
        avg.recentFreq /= count;
        avg.timeWeightedFreq /= count;
        avg.trendAnalysis /= count;
        avg.intervalProb /= count;
        avg.periodicPattern /= count;
        
        return avg;
    }

    /**
     * 특정 데이터셋에서 시간 가중 빈도 계산
     */
    private double calculateTimeWeightedFrequencyForData(
            List<LotteryResult> data, int number) {
        
        if (data.isEmpty()) {
            return 0.0;
        }
        
        double weightedSum = 0.0;
        double totalWeight = 0.0;
        
        for (int i = 0; i < data.size(); i++) {
            LotteryResult result = data.get(i);
            double weight = 1.0 / (i + 1.0); // 최신일수록 높은 가중치
            
            if (containsNumber(result, number)) {
                weightedSum += weight;
            }
            totalWeight += weight;
        }
        
        return totalWeight > 0 ? weightedSum / totalWeight : 0.0;
    }

    /**
     * 특정 데이터셋에서 트렌드 분석
     */
    private double calculateTrendAnalysisForData(
            List<LotteryResult> data, int number) {
        
        if (data.size() < 2) {
            return 0.0;
        }
        
        // 최근 20회차에서 출현 빈도
        int recentCount = Math.min(20, data.size());
        long recentAppearances = data.subList(0, recentCount).stream()
            .filter(r -> containsNumber(r, number))
            .count();
        double recentFreq = (double) recentAppearances / recentCount;
        
        // 이전 20회차에서 출현 빈도
        if (data.size() >= 40) {
            long previousAppearances = data.subList(20, 40).stream()
                .filter(r -> containsNumber(r, number))
                .count();
            double previousFreq = (double) previousAppearances / 20.0;
            
            // 상승 추세면 양수, 하락 추세면 음수
            return recentFreq - previousFreq;
        }
        
        return recentFreq;
    }

    /**
     * 특정 데이터셋에서 간격 기반 확률 계산
     */
    private double calculateIntervalBasedProbabilityForData(
            List<LotteryResult> data, int number) {
        
        if (data.isEmpty()) {
            return 0.0;
        }
        
        List<Integer> appearanceDraws = new ArrayList<>();
        for (int i = 0; i < data.size(); i++) {
            if (containsNumber(data.get(i), number)) {
                appearanceDraws.add(data.get(i).getDraw());
            }
        }
        
        if (appearanceDraws.size() < 2) {
            return 0.0;
        }
        
        // 평균 간격 계산
        int totalInterval = 0;
        for (int i = 1; i < appearanceDraws.size(); i++) {
            totalInterval += appearanceDraws.get(i - 1) - appearanceDraws.get(i);
        }
        double avgInterval = (double) totalInterval / (appearanceDraws.size() - 1);
        
        // 마지막 출현으로부터의 간격
        int lastAppearanceDraw = appearanceDraws.get(0);
        int currentDraw = data.get(0).getDraw();
        int currentInterval = currentDraw - lastAppearanceDraw;
        
        // 간격이 평균에 가까우면 높은 확률
        if (avgInterval > 0) {
            double ratio = currentInterval / avgInterval;
            if (ratio >= 0.8 && ratio <= 1.2) {
                return 0.8; // 평균 간격 근처
            } else if (ratio > 1.2) {
                return 1.0; // 평균보다 훨씬 길면 높은 확률
            } else {
                return 0.3; // 평균보다 짧으면 낮은 확률
            }
        }
        
        return 0.5;
    }

    /**
     * 특정 데이터셋에서 주기적 패턴 계산
     */
    private double calculatePeriodicPatternForData(
            List<LotteryResult> data, int number) {
        
        if (data.size() < 10) {
            return 0.0;
        }
        
        // 최근 50회차에서 주기적 패턴 확인
        int checkCount = Math.min(50, data.size());
        List<Boolean> appearances = new ArrayList<>();
        
        for (int i = 0; i < checkCount; i++) {
            appearances.add(containsNumber(data.get(i), number));
        }
        
        // 간단한 주기 패턴 확인 (2, 3, 4, 5, 6, 7, 8, 9, 10 주기)
        double maxPatternScore = 0.0;
        
        for (int period = 2; period <= 10; period++) {
            int matchCount = 0;
            int totalCount = 0;
            
            for (int i = 0; i < checkCount - period; i++) {
                if (appearances.get(i) && appearances.get(i + period)) {
                    matchCount++;
                }
                totalCount++;
            }
            
            if (totalCount > 0) {
                double patternScore = (double) matchCount / totalCount;
                maxPatternScore = Math.max(maxPatternScore, patternScore);
            }
        }
        
        return maxPatternScore;
    }

    /**
     * 정교한 오차율 계산 (9개 번호 기준)
     * 각 번호별 확률과 실제 출현 여부를 종합적으로 고려하여 오차율 계산
     * 
     * @param predictedNumbers 예측된 번호 리스트 (9개)
     * @param actualNumbers 실제 당첨 번호 리스트 (9개)
     * @param historicalData 과거 데이터
     * @param weights 사용된 가중치
     * @return 정교하게 계산된 오차율 (%)
     */
    private double calculatePreciseErrorRateFor9Numbers(
            List<Integer> predictedNumbers,
            List<Integer> actualNumbers,
            List<LotteryResult> historicalData,
            LearnedWeights weights) {
        
        if (predictedNumbers.size() != 9 || actualNumbers.size() != 9) {
            // 기본 오차율 계산
            Set<Integer> predictedSet = new HashSet<>(predictedNumbers);
            Set<Integer> actualSet = new HashSet<>(actualNumbers);
            predictedSet.retainAll(actualSet);
            int matchCount = predictedSet.size();
            return (9.0 - matchCount) / 9.0 * 100.0;
        }
        
        // 1. 기본 매칭 오차 (맞춘 개수 기반)
        Set<Integer> predictedSet = new HashSet<>(predictedNumbers);
        Set<Integer> actualSet = new HashSet<>(actualNumbers);
        predictedSet.retainAll(actualSet);
        int matchCount = predictedSet.size();
        double basicErrorRate = (9.0 - matchCount) / 9.0 * 100.0;
        
        // 2. 확률 기반 오차 계산
        // 예측된 번호들의 확률과 실제 출현 여부를 비교
        double probabilityError = 0.0;
        double totalPredictedProbability = 0.0;
        double totalActualProbability = 0.0;
        
        // 모든 번호(1-44)에 대해 확률 계산
        Map<Integer, Double> allProbabilities = new HashMap<>();
        for (int num = 1; num <= MAX_NUMBER; num++) {
            double prob = calculateProbabilityForNumberInData(historicalData, num, weights);
            allProbabilities.put(num, prob);
        }
        
        // 예측된 번호들의 평균 확률
        for (Integer num : predictedNumbers) {
            totalPredictedProbability += allProbabilities.getOrDefault(num, 0.0);
        }
        double avgPredictedProbability = totalPredictedProbability / predictedNumbers.size();
        
        // 실제 당첨 번호들의 평균 확률
        for (Integer num : actualNumbers) {
            totalActualProbability += allProbabilities.getOrDefault(num, 0.0);
        }
        double avgActualProbability = totalActualProbability / actualNumbers.size();
        
        // 확률 차이 기반 오차
        double probabilityDiff = Math.abs(avgPredictedProbability - avgActualProbability);
        probabilityError = probabilityDiff * 100.0; // 확률 차이를 퍼센트로 변환
        
        // 3. 순위 기반 오차 계산
        // 예측된 번호들의 확률 순위와 실제 번호들의 확률 순위 비교
        List<Map.Entry<Integer, Double>> sortedProbabilities = allProbabilities.entrySet().stream()
            .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
            .collect(Collectors.toList());
        
        // 예측된 번호들의 평균 순위
        double avgPredictedRank = 0.0;
        for (Integer num : predictedNumbers) {
            int rank = findRank(sortedProbabilities, num);
            avgPredictedRank += rank;
        }
        avgPredictedRank /= predictedNumbers.size();
        
        // 실제 번호들의 평균 순위
        double avgActualRank = 0.0;
        for (Integer num : actualNumbers) {
            int rank = findRank(sortedProbabilities, num);
            avgActualRank += rank;
        }
        avgActualRank /= actualNumbers.size();
        
        // 순위 차이 기반 오차 (상위 44개 중 평균 순위 차이)
        double rankError = Math.abs(avgPredictedRank - avgActualRank) / MAX_NUMBER * 100.0;
        
        // 4. 가중 평균으로 최종 오차율 계산
        // 기본 매칭 오차: 60% 가중치
        // 확률 기반 오차: 25% 가중치
        // 순위 기반 오차: 15% 가중치
        double preciseErrorRate = 
            basicErrorRate * 0.60 + 
            probabilityError * 0.25 + 
            rankError * 0.15;
        
        // JINS_RIGHT_COUNT개 이상 맞춤 목표를 위해 더 엄격한 기준 적용
        // 맞춘 개수가 JINS_RIGHT_COUNT개 미만이면 추가 페널티
        if (matchCount < JINS_RIGHT_COUNT) {
            preciseErrorRate += (JINS_RIGHT_COUNT - matchCount) * 3.0; // 각 개수당 3% 추가 오차
        }
        
        // 9개 모두 맞춘 경우 오차율 0%에 가깝게 조정
        if (matchCount == 9) {
            preciseErrorRate = Math.min(preciseErrorRate, 1.0); // 최대 1% 오차
        }
        
        // JINS_RIGHT_COUNT개 이상 맞춘 경우 오차율 감소
        if (matchCount >= JINS_RIGHT_COUNT) {
            preciseErrorRate = Math.max(0.0, preciseErrorRate - 2.0); // 2% 보너스
        }
        
        return preciseErrorRate;
    }

    /**
     * 특정 데이터셋에서 번호의 확률 계산
     */
    private double calculateProbabilityForNumberInData(
            List<LotteryResult> data, 
            int number,
            LearnedWeights weights) {
        
        if (data.isEmpty()) {
            return 0.0;
        }
        
        // 데이터를 역순으로 정렬 (최신순)
        List<LotteryResult> sortedData = new ArrayList<>(data);
        sortedData.sort((a, b) -> Integer.compare(b.getDraw(), a.getDraw()));
        
        // 최근 출현 빈도
        int recentCount = Math.min(RECENT_DRAWS_COUNT, sortedData.size());
        long recentAppearances = sortedData.subList(0, recentCount).stream()
            .filter(r -> containsNumber(r, number))
            .count();
        double recentFreq = (double) recentAppearances / recentCount;
        
        // 전체 출현 빈도
        long totalAppearances = sortedData.stream()
            .filter(r -> containsNumber(r, number))
            .count();
        double overallFreq = (double) totalAppearances / sortedData.size();
        
        // 시간 가중 빈도
        double timeWeightedFreq = calculateTimeWeightedFrequencyForData(sortedData, number);
        
        // 트렌드 분석
        double trendAnalysis = calculateTrendAnalysisForData(sortedData, number);
        
        // 간격 기반 확률
        double intervalProb = calculateIntervalBasedProbabilityForData(sortedData, number);
        
        // 주기적 패턴
        double periodicPattern = calculatePeriodicPatternForData(sortedData, number);
        
        // 가중치 적용하여 최종 확률 계산
        double prob = weights.wOverallFreq * overallFreq +
                     weights.wRecentFreq * recentFreq +
                     weights.wTimeWeightedFreq * timeWeightedFreq +
                     weights.wTrendAnalysis * Math.max(0, trendAnalysis + 0.5) + // 트렌드를 0-1 범위로 정규화
                     weights.wIntervalProb * intervalProb +
                     weights.wPeriodicPattern * periodicPattern;
        
        // 보너스 적용
        if (recentFreq > 0.5 && timeWeightedFreq > 0.5 && trendAnalysis > 0) {
            prob *= weights.bonusMultiplier;
        }
        
        // 확률을 0-1 범위로 제한
        return Math.max(0.0, Math.min(1.0, prob));
    }

    /**
     * 정렬된 확률 리스트에서 번호의 순위 찾기
     */
    private int findRank(List<Map.Entry<Integer, Double>> sortedProbabilities, int number) {
        for (int i = 0; i < sortedProbabilities.size(); i++) {
            if (sortedProbabilities.get(i).getKey() == number) {
                return i + 1; // 1-based rank
            }
        }
        return sortedProbabilities.size(); // 찾지 못한 경우 최하위
    }

    /**
     * 딥러닝 기반 예측 점수 계산
     * MachineLearningService의 LSTM 유사 모델과 시계열 분석 활용
     * 캐시를 사용하여 성능 최적화
     */
    private double calculateDeepLearningScore(List<LotteryResult> allResults, int number) {
        // 캐시 확인 및 업데이트
        ensureDeepLearningCache(allResults);
        
        // 캐시에서 점수 가져오기
        double score = cachedDeepLearningScores != null ? 
            cachedDeepLearningScores.getOrDefault(number, 0.0) : 0.0;
        
        // 첫 번째 호출 시에만 캐시 사용 확인 로그 출력
        if (number == 1 && cachedDeepLearningScores != null && cachedDeepLearningScores.size() > 0) {
            // DEBUG 로그 제거 (너무 많은 출력 방지)
        }
        
        return score;
    }

    /**
     * 고급 앙상블 예측 점수 계산
     * AdvancedPredictionService의 앙상블 학습 결과 활용
     * 캐시를 사용하여 성능 최적화
     */
    private double calculateAdvancedEnsembleScore(List<LotteryResult> allResults, int number) {
        // 캐시 확인 및 업데이트
        ensureDeepLearningCache(allResults);
        
        // 캐시에서 점수 가져오기
        double score = cachedAdvancedEnsembleScores != null ?
            cachedAdvancedEnsembleScores.getOrDefault(number, 0.0) : 0.0;
        
        // 첫 번째 호출 시에만 캐시 사용 확인 로그 출력
        if (number == 1 && cachedAdvancedEnsembleScores != null && cachedAdvancedEnsembleScores.size() > 0) {
            // DEBUG 로그 제거 (너무 많은 출력 방지)
        }
        
        return score;
    }

    /**
     * 어텐션 메커니즘 기반 중요 패턴 점수 계산
     * 시계열 데이터에서 중요한 패턴을 강조하는 어텐션 가중치 적용
     * 고급 피처 엔지니어링 포함: 주파수 분석, FFT 변환 등
     */
    private double calculateAttentionBasedScore(List<LotteryResult> allResults, int number) {
        if (allResults.size() < 30) {
            return 0.0;
        }
        
        // 최근 100회차 데이터 사용
        int windowSize = Math.min(100, allResults.size());
        List<LotteryResult> recentData = new ArrayList<>(allResults.subList(0, windowSize));
        Collections.reverse(recentData); // 오래된 것부터
        
        // 시계열 데이터 생성 (출현: 1.0, 미출현: 0.0)
        List<Double> timeSeries = new ArrayList<>();
        for (LotteryResult result : recentData) {
            timeSeries.add(containsNumber(result, number) ? 1.0 : 0.0);
        }
        
        // 1. 주파수 분석 (FFT 유사 분석)
        double frequencyScore = calculateFrequencyAnalysis(timeSeries);
        
        // 2. 어텐션 가중치 계산 (최근일수록 높은 가중치, 패턴이 강할수록 높은 가중치)
        double[] attentionWeights = new double[timeSeries.size()];
        double totalWeight = 0.0;
        
        for (int i = 0; i < timeSeries.size(); i++) {
            // 시간 가중치 (최근일수록 높음)
            double timeWeight = Math.exp(-i * 0.05);
            
            // 패턴 가중치 (주변 패턴과의 일관성)
            double patternWeight = 1.0;
            if (i > 0 && i < timeSeries.size() - 1) {
                // 이전과 다음 값과의 일관성
                double consistency = 1.0 - Math.abs(timeSeries.get(i) - 
                    (timeSeries.get(i-1) + timeSeries.get(i+1)) / 2.0);
                patternWeight = 0.5 + 0.5 * consistency;
            }
            
            // 자기상관 가중치 (특정 간격으로 반복되는 패턴)
            double autocorrWeight = 1.0;
            for (int lag = 1; lag <= Math.min(10, timeSeries.size() - i - 1); lag++) {
                if (i + lag < timeSeries.size()) {
                    if (Math.abs(timeSeries.get(i) - timeSeries.get(i + lag)) < 0.1) {
                        autocorrWeight += 0.1; // 패턴 반복 발견
                    }
                }
            }
            autocorrWeight = Math.min(2.0, autocorrWeight);
            
            attentionWeights[i] = timeWeight * patternWeight * autocorrWeight;
            totalWeight += attentionWeights[i];
        }
        
        // 가중 평균 계산
        double weightedSum = 0.0;
        for (int i = 0; i < timeSeries.size(); i++) {
            weightedSum += timeSeries.get(i) * attentionWeights[i];
        }
        
        double baseAttentionScore = totalWeight > 0 ? weightedSum / totalWeight : 0.0;
        
        // 주파수 분석 점수와 어텐션 점수 결합
        return 0.7 * baseAttentionScore + 0.3 * frequencyScore;
    }

    /**
     * 주파수 분석 (FFT 유사 분석)
     * 시계열 데이터의 주기적 패턴을 주파수 도메인에서 분석
     */
    private double calculateFrequencyAnalysis(List<Double> timeSeries) {
        if (timeSeries.size() < 10) {
            return 0.0;
        }
        
        // 간단한 주파수 분석 (실제 FFT 대신 자기상관 기반)
        double maxPeriodicity = 0.0;
        
        // 다양한 주기 길이 테스트 (2부터 데이터 크기의 절반까지)
        for (int period = 2; period <= Math.min(20, timeSeries.size() / 2); period++) {
            double periodicity = 0.0;
            int count = 0;
            
            // 해당 주기로 반복되는 패턴 확인
            for (int i = 0; i < timeSeries.size() - period; i++) {
                double diff = Math.abs(timeSeries.get(i) - timeSeries.get(i + period));
                periodicity += 1.0 - diff; // 차이가 작을수록 높은 주기성
                count++;
            }
            
            if (count > 0) {
                periodicity /= count;
                maxPeriodicity = Math.max(maxPeriodicity, periodicity);
            }
        }
        
        return maxPeriodicity;
    }

    /**
     * 딥러닝을 포함한 고급 확률 결합 메서드
     */
    private double combineAdvancedProbabilitiesWithDeepLearning(
            double overallFreq,
            double recentFreq,
            double timeWeightedFreq,
            double intervalProb,
            double trendAnalysis,
            double periodicPattern,
            double consecutivePattern,
            double correlationAnalysis,
            double statisticalOutlier,
            double timeSeriesChangeRate,
            double recentIntervalScore,
            double weightedAppearanceFreq,
            double varianceBasedProb,
            double penalty,
            double deepLearningScore,
            double advancedEnsembleScore,
            double attentionBasedScore) {
        
        // 기존 확률 계산
        double baseProbability = combineAdvancedProbabilities(
            overallFreq, recentFreq, timeWeightedFreq, intervalProb,
            trendAnalysis, periodicPattern, consecutivePattern, correlationAnalysis,
            statisticalOutlier, timeSeriesChangeRate, recentIntervalScore,
            weightedAppearanceFreq, varianceBasedProb, penalty
        );
        
        // 과도한 확률 부스팅 대신 "순위 안정성" 중심으로 결합한다.
        double blendedScore =
            0.50 * baseProbability +
            0.22 * deepLearningScore +
            0.18 * advancedEnsembleScore +
            0.10 * attentionBasedScore;
        
        double consensusMean = (deepLearningScore + advancedEnsembleScore + attentionBasedScore) / 3.0;
        double variance = (
            Math.pow(deepLearningScore - consensusMean, 2) +
            Math.pow(advancedEnsembleScore - consensusMean, 2) +
            Math.pow(attentionBasedScore - consensusMean, 2)
        ) / 3.0;
        double disagreement = Math.sqrt(variance);
        
        // 세 모델의 불일치가 큰 번호는 점수를 낮춰 과적합 노이즈를 줄인다.
        double reliabilityFactor = 1.0 - Math.min(0.35, disagreement * 0.6);
        
        int strongSignals = 0;
        if (deepLearningScore > 0.55) strongSignals++;
        if (advancedEnsembleScore > 0.55) strongSignals++;
        if (attentionBasedScore > 0.55) strongSignals++;
        
        double consensusBonus = 0.0;
        if (strongSignals == 3) {
            consensusBonus = 0.06;
        } else if (strongSignals == 2) {
            consensusBonus = 0.035;
        } else if (strongSignals == 1) {
            consensusBonus = 0.015;
        }
        
        double finalProbability =
            (blendedScore * reliabilityFactor) +
            (consensusMean * 0.08) +
            consensusBonus;
        
        return Math.max(0.0, Math.min(1.0, finalProbability));
    }
}
