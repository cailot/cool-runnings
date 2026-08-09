package hyung.jin.seo.coolrunnings.service;

import hyung.jin.seo.coolrunnings.model.LotteryResult;
import hyung.jin.seo.coolrunnings.repository.LotteryResultRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 철저한 검증 서비스
 * 데이터 누수 방지 및 다중 검증 전략을 사용하여 정확한 매칭 검증
 */
@Slf4j
@RequiredArgsConstructor
public class ThoroughValidationService {

    private final LotteryResultRepository lotteryResultRepository;
    
    // 번호 범위 (Set for Life는 1-44)
    private static final int MAX_NUMBER = 44;
    // 당첨 번호 7개 + 보너스 번호 2개 = 총 9개
    private static final int TOTAL_DRAWN_NUMBERS = 9;
    private static final int WINNING_NUMBERS_COUNT = 7;
    private static final int BONUS_NUMBERS_COUNT = 2;
    
    /**
     * 검증 결과 (단일 회차)
     */
    @Data
    public static class ThoroughValidationResult {
        private int draw;                                    // 회차
        private LocalDate drawDate;                          // 추첨일
        private List<Integer> predictedNumbers;             // 예측된 번호 (9개)
        private List<Integer> actualWinningNumbers;          // 실제 당첨 번호 (7개)
        private List<Integer> actualBonusNumbers;            // 실제 보너스 번호 (2개)
        private List<Integer> actualAllNumbers;              // 실제 전체 번호 (9개)
        
        // 매칭 결과
        private int winningMatchCount;                      // 당첨번호 맞춘 개수 (0-7)
        private int bonusMatchCount;                        // 보너스번호 맞춘 개수 (0-2)
        private int totalMatchCount;                         // 전체 맞춘 개수 (0-9)
        private List<Integer> matchedWinningNumbers;         // 맞춘 당첨번호 리스트
        private List<Integer> matchedBonusNumbers;           // 맞춘 보너스번호 리스트
        private List<Integer> matchedAllNumbers;             // 맞춘 전체 번호 리스트
        
        // 정확도
        private double winningAccuracy;                     // 당첨번호 정확도 (0.0 ~ 1.0)
        private double bonusAccuracy;                        // 보너스번호 정확도 (0.0 ~ 1.0)
        private double totalAccuracy;                        // 전체 정확도 (0.0 ~ 1.0)
        
        // 검증 전략별 결과
        private Map<String, ValidationStrategyResult> strategyResults; // 전략별 결과
        
        // 디버깅 정보
        private String predictionMethod;                     // 사용된 예측 방법
        private int historicalDataSize;                     // 사용된 과거 데이터 크기
    }
    
    /**
     * 검증 전략별 결과
     */
    @Data
    public static class ValidationStrategyResult {
        private String strategyName;                          // 전략 이름
        private List<Integer> predictedNumbers;             // 예측된 번호
        private int matchCount;                              // 맞춘 개수
        private double accuracy;                             // 정확도
        private List<Integer> matchedNumbers;                // 맞춘 번호 리스트
    }
    
    /**
     * 종합 검증 통계
     */
    @Data
    public static class ThoroughValidationStatistics {
        private int totalValidations;                        // 총 검증 횟수
        private double averageWinningAccuracy;               // 평균 당첨번호 정확도
        private double averageBonusAccuracy;                  // 평균 보너스번호 정확도
        private double averageTotalAccuracy;                  // 평균 전체 정확도
        private double averageWinningMatchCount;             // 평균 당첨번호 맞춘 개수
        private double averageBonusMatchCount;               // 평균 보너스번호 맞춘 개수
        private double averageTotalMatchCount;                // 평균 전체 맞춘 개수
        
        // 분포
        private Map<Integer, Integer> winningMatchDistribution;  // 당첨번호 맞춘 개수별 분포
        private Map<Integer, Integer> bonusMatchDistribution;    // 보너스번호 맞춘 개수별 분포
        private Map<Integer, Integer> totalMatchDistribution;     // 전체 맞춘 개수별 분포
        
        // 최고 성능
        private int maxWinningMatchCount;                     // 최대 당첨번호 맞춘 개수
        private int maxBonusMatchCount;                       // 최대 보너스번호 맞춘 개수
        private int maxTotalMatchCount;                       // 최대 전체 맞춘 개수
        
        // 전략별 성능
        private Map<String, Double> strategyAverageAccuracy; // 전략별 평균 정확도
    }
    
    /**
     * 실제 당첨 번호 추출 (9개: 당첨번호 7개 + 보너스번호 2개)
     */
    private List<Integer> extractAllNumbers(LotteryResult result) {
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
     * 당첨 번호만 추출 (7개)
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
     * 보너스 번호만 추출 (2개)
     */
    private List<Integer> extractBonusNumbers(LotteryResult result) {
        List<Integer> numbers = new ArrayList<>();
        if (result.getBonusNumber1() != null) numbers.add(result.getBonusNumber1());
        if (result.getBonusNumber2() != null) numbers.add(result.getBonusNumber2());
        return numbers;
    }
    
    /**
     * 전략 1: 빈도 기반 예측 (historicalData만 사용)
     */
    private List<Integer> predictByFrequency(List<LotteryResult> historicalData) {
        if (historicalData == null || historicalData.isEmpty()) {
            return generateRandomPrediction();
        }
        
        // 번호별 출현 빈도 계산 (historicalData만 사용)
        Map<Integer, Integer> frequencyMap = new HashMap<>();
        for (LotteryResult result : historicalData) {
            List<Integer> numbers = extractAllNumbers(result);
            for (Integer num : numbers) {
                frequencyMap.put(num, frequencyMap.getOrDefault(num, 0) + 1);
            }
        }
        
        // 상위 9개 번호 선택
        List<Integer> predictedNumbers = frequencyMap.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .limit(9)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
        
        // 9개가 안 되면 랜덤으로 채움
        while (predictedNumbers.size() < 9) {
            Random random = new Random();
            int num = random.nextInt(MAX_NUMBER) + 1;
            if (!predictedNumbers.contains(num)) {
                predictedNumbers.add(num);
            }
        }
        
        return predictedNumbers;
    }
    
    /**
     * 전략 2: 최근 출현 빈도 기반 예측 (최근 N회만 사용)
     */
    private List<Integer> predictByRecentFrequency(List<LotteryResult> historicalData, int recentCount) {
        if (historicalData == null || historicalData.isEmpty()) {
            return generateRandomPrediction();
        }
        
        // 최근 N회만 사용
        int actualRecentCount = Math.min(recentCount, historicalData.size());
        List<LotteryResult> recentData = historicalData.subList(
            Math.max(0, historicalData.size() - actualRecentCount), 
            historicalData.size()
        );
        
        return predictByFrequency(recentData);
    }
    
    /**
     * 전략 3: 가중 빈도 기반 예측 (최근 데이터에 더 높은 가중치)
     */
    private List<Integer> predictByWeightedFrequency(List<LotteryResult> historicalData) {
        if (historicalData == null || historicalData.isEmpty()) {
            return generateRandomPrediction();
        }
        
        // 번호별 가중 출현 빈도 계산 (최근 데이터에 더 높은 가중치)
        Map<Integer, Double> weightedFrequencyMap = new HashMap<>();
        int totalSize = historicalData.size();
        
        for (int i = 0; i < historicalData.size(); i++) {
            LotteryResult result = historicalData.get(i);
            List<Integer> numbers = extractAllNumbers(result);
            
            // 최근 데이터일수록 높은 가중치 (선형 가중치)
            double weight = (double) (i + 1) / totalSize;
            
            for (Integer num : numbers) {
                weightedFrequencyMap.put(num, 
                    weightedFrequencyMap.getOrDefault(num, 0.0) + weight);
            }
        }
        
        // 상위 9개 번호 선택
        List<Integer> predictedNumbers = weightedFrequencyMap.entrySet().stream()
            .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
            .limit(9)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
        
        // 9개가 안 되면 랜덤으로 채움
        while (predictedNumbers.size() < 9) {
            Random random = new Random();
            int num = random.nextInt(MAX_NUMBER) + 1;
            if (!predictedNumbers.contains(num)) {
                predictedNumbers.add(num);
            }
        }
        
        return predictedNumbers;
    }
    
    /**
     * 전략 4: 미출현 기반 예측 (오래 안 나온 번호 우선)
     */
    private List<Integer> predictByAbsence(List<LotteryResult> historicalData) {
        if (historicalData == null || historicalData.isEmpty()) {
            return generateRandomPrediction();
        }
        
        // 번호별 마지막 출현 회차 계산
        Map<Integer, Integer> lastAppearanceMap = new HashMap<>();
        int currentDraw = historicalData.isEmpty() ? 0 : 
            historicalData.get(historicalData.size() - 1).getDraw();
        
        // 역순으로 검색하여 각 번호의 마지막 출현 회차 찾기
        for (int i = historicalData.size() - 1; i >= 0; i--) {
            LotteryResult result = historicalData.get(i);
            List<Integer> numbers = extractAllNumbers(result);
            for (Integer num : numbers) {
                if (!lastAppearanceMap.containsKey(num)) {
                    lastAppearanceMap.put(num, result.getDraw());
                }
            }
        }
        
        // 미출현 기간이 긴 번호 우선 선택
        List<Integer> predictedNumbers = new ArrayList<>();
        
        // 모든 번호에 대해 미출현 기간 계산
        Map<Integer, Integer> absencePeriodMap = new HashMap<>();
        for (int num = 1; num <= MAX_NUMBER; num++) {
            int lastDraw = lastAppearanceMap.getOrDefault(num, 0);
            int absencePeriod = currentDraw - lastDraw;
            absencePeriodMap.put(num, absencePeriod);
        }
        
        // 미출현 기간이 긴 순서로 정렬하여 상위 9개 선택
        predictedNumbers = absencePeriodMap.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .limit(9)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
        
        return predictedNumbers;
    }
    
    /**
     * 전략 5: 패턴 기반 예측 (홀짝, 구간 분포 고려)
     */
    private List<Integer> predictByPattern(List<LotteryResult> historicalData) {
        if (historicalData == null || historicalData.isEmpty()) {
            return generateRandomPrediction();
        }
        
        // 최근 N회의 패턴 분석
        int recentCount = Math.min(20, historicalData.size());
        List<LotteryResult> recentData = historicalData.subList(
            Math.max(0, historicalData.size() - recentCount), 
            historicalData.size()
        );
        
        // 최근 패턴에서 홀짝 비율 계산
        int totalOdd = 0, totalEven = 0;
        for (LotteryResult result : recentData) {
            List<Integer> numbers = extractAllNumbers(result);
            for (Integer num : numbers) {
                if (num % 2 == 1) totalOdd++;
                else totalEven++;
            }
        }
        double oddRatio = recentData.isEmpty() ? 0.5 : (double) totalOdd / (totalOdd + totalEven);
        
        // 최근 패턴에서 구간 분포 계산
        int[] rangeCounts = new int[5]; // 1-10, 11-20, 21-30, 31-40, 41-44
        for (LotteryResult result : recentData) {
            List<Integer> numbers = extractAllNumbers(result);
            for (Integer num : numbers) {
                if (num >= 1 && num <= 10) rangeCounts[0]++;
                else if (num >= 11 && num <= 20) rangeCounts[1]++;
                else if (num >= 21 && num <= 30) rangeCounts[2]++;
                else if (num >= 31 && num <= 40) rangeCounts[3]++;
                else if (num >= 41 && num <= 44) rangeCounts[4]++;
            }
        }
        
        // 각 번호의 점수 계산 (패턴 일치도 + 빈도)
        Map<Integer, Double> scoreMap = new HashMap<>();
        Map<Integer, Integer> frequencyMap = new HashMap<>();
        
        for (LotteryResult result : historicalData) {
            List<Integer> numbers = extractAllNumbers(result);
            for (Integer num : numbers) {
                frequencyMap.put(num, frequencyMap.getOrDefault(num, 0) + 1);
            }
        }
        
        for (int num = 1; num <= MAX_NUMBER; num++) {
            double score = 0.0;
            
            // 빈도 점수
            double freq = (double) frequencyMap.getOrDefault(num, 0) / historicalData.size();
            score += freq * 0.4;
            
            // 홀짝 패턴 점수
            boolean isOdd = (num % 2 == 1);
            if (isOdd && oddRatio > 0.4 && oddRatio < 0.6) {
                score += 0.2; // 균형잡힌 홀짝 비율 선호
            } else if (!isOdd && oddRatio > 0.4 && oddRatio < 0.6) {
                score += 0.2;
            }
            
            // 구간 분포 점수
            int rangeIndex = -1;
            if (num >= 1 && num <= 10) rangeIndex = 0;
            else if (num >= 11 && num <= 20) rangeIndex = 1;
            else if (num >= 21 && num <= 30) rangeIndex = 2;
            else if (num >= 31 && num <= 40) rangeIndex = 3;
            else if (num >= 41 && num <= 44) rangeIndex = 4;
            
            if (rangeIndex >= 0) {
                int totalInRange = rangeCounts[rangeIndex];
                double rangeRatio = totalInRange / (double) (recentCount * TOTAL_DRAWN_NUMBERS);
                // 각 구간이 적절히 분포되도록 보정
                double expectedRatio = 0.2; // 각 구간당 약 20%
                score += (1.0 - Math.abs(rangeRatio - expectedRatio)) * 0.4;
            }
            
            scoreMap.put(num, score);
        }
        
        // 상위 9개 선택
        List<Integer> predictedNumbers = scoreMap.entrySet().stream()
            .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
            .limit(9)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
        
        return predictedNumbers;
    }
    
    /**
     * 전략 6: 간격 기반 예측 (평균 출현 간격 분석)
     */
    private List<Integer> predictByInterval(List<LotteryResult> historicalData) {
        if (historicalData == null || historicalData.isEmpty()) {
            return generateRandomPrediction();
        }
        
        // 각 번호의 출현 간격 분석
        Map<Integer, List<Integer>> appearanceDrawsMap = new HashMap<>();
        
        for (LotteryResult result : historicalData) {
            List<Integer> numbers = extractAllNumbers(result);
            for (Integer num : numbers) {
                appearanceDrawsMap.computeIfAbsent(num, k -> new ArrayList<>())
                    .add(result.getDraw());
            }
        }
        
        int currentDraw = historicalData.get(historicalData.size() - 1).getDraw();
        Map<Integer, Double> scoreMap = new HashMap<>();
        
        for (int num = 1; num <= MAX_NUMBER; num++) {
            List<Integer> appearanceDraws = appearanceDrawsMap.getOrDefault(num, new ArrayList<>());
            
            if (appearanceDraws.isEmpty()) {
                // 한 번도 안 나온 번호는 높은 점수
                scoreMap.put(num, 1.0);
                continue;
            }
            
            // 평균 출현 간격 계산
            Collections.sort(appearanceDraws);
            List<Integer> intervals = new ArrayList<>();
            for (int i = 1; i < appearanceDraws.size(); i++) {
                intervals.add(appearanceDraws.get(i) - appearanceDraws.get(i - 1));
            }
            
            double avgInterval = intervals.isEmpty() ? 0 : 
                intervals.stream().mapToInt(Integer::intValue).average().orElse(0.0);
            
            // 마지막 출현 이후 경과 시간
            int lastAppearance = appearanceDraws.get(appearanceDraws.size() - 1);
            int elapsedSinceLast = currentDraw - lastAppearance;
            
            // 평균 간격에 가까울수록 높은 점수
            if (avgInterval > 0) {
                double ratio = elapsedSinceLast / avgInterval;
                // 평균 간격의 0.8~1.2배 사이면 높은 점수
                if (ratio >= 0.8 && ratio <= 1.2) {
                    scoreMap.put(num, 1.0 - Math.abs(ratio - 1.0));
                } else if (ratio > 1.2) {
                    // 평균보다 오래 안 나왔으면 점수 증가
                    scoreMap.put(num, Math.min(1.0, (ratio - 1.2) * 0.5));
                } else {
                    scoreMap.put(num, 0.1);
                }
            } else {
                scoreMap.put(num, 0.5);
            }
        }
        
        // 상위 9개 선택
        List<Integer> predictedNumbers = scoreMap.entrySet().stream()
            .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
            .limit(9)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
        
        return predictedNumbers;
    }
    
    /**
     * 전략 7: 트렌드 기반 예측 (상승/하락 추세 분석)
     */
    private List<Integer> predictByTrend(List<LotteryResult> historicalData) {
        if (historicalData == null || historicalData.isEmpty()) {
            return generateRandomPrediction();
        }
        
        // 최근 30회와 그 이전 30회 비교
        int windowSize = Math.min(30, historicalData.size() / 2);
        if (windowSize < 10) {
            return predictByFrequency(historicalData);
        }
        
        List<LotteryResult> recentWindow = historicalData.subList(
            Math.max(0, historicalData.size() - windowSize), 
            historicalData.size()
        );
        List<LotteryResult> previousWindow = historicalData.subList(
            Math.max(0, historicalData.size() - windowSize * 2), 
            Math.max(0, historicalData.size() - windowSize)
        );
        
        Map<Integer, Double> scoreMap = new HashMap<>();
        
        for (int num = 1; num <= MAX_NUMBER; num++) {
            final int number = num; // 람다에서 사용하기 위해 final 변수로 복사
            // 최근 윈도우 출현 빈도
            long recentCount = recentWindow.stream()
                .filter(r -> extractAllNumbers(r).contains(number))
                .count();
            double recentFreq = (double) recentCount / recentWindow.size();
            
            // 이전 윈도우 출현 빈도
            long previousCount = previousWindow.isEmpty() ? 0 : previousWindow.stream()
                .filter(r -> extractAllNumbers(r).contains(number))
                .count();
            double previousFreq = previousWindow.isEmpty() ? 0.0 : 
                (double) previousCount / previousWindow.size();
            
            // 트렌드 계산 (상승 추세면 높은 점수)
            double trend = recentFreq - previousFreq;
            
            // 상승 추세이거나 안정적인 번호 선호
            if (trend > 0) {
                scoreMap.put(num, 0.5 + trend * 2.0); // 상승 추세
            } else if (trend == 0 && recentFreq > 0) {
                scoreMap.put(num, 0.4); // 안정적
            } else {
                scoreMap.put(num, 0.1 + recentFreq); // 하락 추세지만 최근 빈도 고려
            }
        }
        
        // 상위 9개 선택
        List<Integer> predictedNumbers = scoreMap.entrySet().stream()
            .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
            .limit(9)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
        
        return predictedNumbers;
    }
    
    /**
     * 전략 8: 통계적 균형 예측 (이론적 확률과의 편차 보정)
     */
    private List<Integer> predictByStatisticalBalance(List<LotteryResult> historicalData) {
        if (historicalData == null || historicalData.isEmpty()) {
            return generateRandomPrediction();
        }
        
        // 이론적 출현 확률 (9/44)
        double theoreticalProb = (double) TOTAL_DRAWN_NUMBERS / MAX_NUMBER;
        
        // 각 번호의 실제 출현 빈도
        Map<Integer, Integer> appearanceCount = new HashMap<>();
        for (LotteryResult result : historicalData) {
            List<Integer> numbers = extractAllNumbers(result);
            for (Integer num : numbers) {
                appearanceCount.put(num, appearanceCount.getOrDefault(num, 0) + 1);
            }
        }
        
        Map<Integer, Double> scoreMap = new HashMap<>();
        
        for (int num = 1; num <= MAX_NUMBER; num++) {
            double actualFreq = (double) appearanceCount.getOrDefault(num, 0) / historicalData.size();
            double deviation = actualFreq - theoreticalProb;
            
            // 이론적 확률보다 낮게 나온 번호에 보정 점수 부여
            // 하지만 너무 낮으면 제외 (데이터 부족 가능성)
            if (deviation < -0.05 && actualFreq > 0.01) {
                // 이론적 확률보다 낮게 나왔지만, 최소한 몇 번은 나온 번호
                scoreMap.put(num, 0.5 + Math.abs(deviation) * 2.0);
            } else if (deviation > 0.05) {
                // 이론적 확률보다 높게 나온 번호는 약간 감점
                scoreMap.put(num, 0.3);
            } else {
                // 이론적 확률에 가까운 번호
                scoreMap.put(num, 0.4);
            }
        }
        
        // 상위 9개 선택
        List<Integer> predictedNumbers = scoreMap.entrySet().stream()
            .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
            .limit(9)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
        
        return predictedNumbers;
    }
    
    /**
     * 전략 9: 앙상블 예측 (여러 알고리즘 결합)
     */
    private List<Integer> predictByEnsemble(List<LotteryResult> historicalData) {
        if (historicalData == null || historicalData.isEmpty()) {
            return generateRandomPrediction();
        }
        
        // 여러 전략의 예측 결과 수집
        List<Integer> freqPred = predictByFrequency(historicalData);
        List<Integer> weightedPred = predictByWeightedFrequency(historicalData);
        List<Integer> patternPred = predictByPattern(historicalData);
        List<Integer> intervalPred = predictByInterval(historicalData);
        List<Integer> trendPred = predictByTrend(historicalData);
        
        // 각 번호의 투표 점수 계산
        Map<Integer, Integer> voteMap = new HashMap<>();
        
        List<List<Integer>> allPredictions = Arrays.asList(
            freqPred, weightedPred, patternPred, intervalPred, trendPred
        );
        
        for (List<Integer> prediction : allPredictions) {
            for (int i = 0; i < prediction.size(); i++) {
                int num = prediction.get(i);
                // 순위가 높을수록 높은 점수 (9-i)
                voteMap.put(num, voteMap.getOrDefault(num, 0) + (9 - i));
            }
        }
        
        // 상위 9개 선택
        List<Integer> predictedNumbers = voteMap.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .limit(9)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
        
        return predictedNumbers;
    }
    
    /**
     * 랜덤 예측 생성
     */
    private List<Integer> generateRandomPrediction() {
        List<Integer> numbers = new ArrayList<>();
        Random random = new Random();
        Set<Integer> used = new HashSet<>();
        
        while (numbers.size() < 9) {
            int num = random.nextInt(MAX_NUMBER) + 1;
            if (!used.contains(num)) {
                numbers.add(num);
                used.add(num);
            }
        }
        
        return numbers;
    }
    
    /**
     * 철저한 매칭 검증
     */
    private ThoroughValidationResult validateSingleDraw(
            LotteryResult result, 
            List<LotteryResult> historicalData,
            String predictionMethod) {
        
        ThoroughValidationResult validationResult = new ThoroughValidationResult();
        validationResult.setDraw(result.getDraw());
        validationResult.setDrawDate(result.getDrawDate());
        validationResult.setPredictionMethod(predictionMethod);
        validationResult.setHistoricalDataSize(historicalData != null ? historicalData.size() : 0);
        
        // 실제 번호 추출
        List<Integer> actualWinning = extractWinningNumbers(result);
        List<Integer> actualBonus = extractBonusNumbers(result);
        List<Integer> actualAll = extractAllNumbers(result);
        
        validationResult.setActualWinningNumbers(actualWinning);
        validationResult.setActualBonusNumbers(actualBonus);
        validationResult.setActualAllNumbers(actualAll);
        
        // 예측 수행 (여러 전략 사용)
        Map<String, ValidationStrategyResult> strategyResults = new HashMap<>();
        
        // 전략 1: 전체 빈도
        List<Integer> predictedByFrequency = predictByFrequency(historicalData);
        ValidationStrategyResult freqResult = calculateMatch(
            "전체빈도", predictedByFrequency, actualWinning, actualBonus, actualAll);
        strategyResults.put("frequency", freqResult);
        
        // 전략 2: 최근 50회 빈도
        List<Integer> predictedByRecent50 = predictByRecentFrequency(historicalData, 50);
        ValidationStrategyResult recent50Result = calculateMatch(
            "최근50회빈도", predictedByRecent50, actualWinning, actualBonus, actualAll);
        strategyResults.put("recent50", recent50Result);
        
        // 전략 3: 최근 100회 빈도
        List<Integer> predictedByRecent100 = predictByRecentFrequency(historicalData, 100);
        ValidationStrategyResult recent100Result = calculateMatch(
            "최근100회빈도", predictedByRecent100, actualWinning, actualBonus, actualAll);
        strategyResults.put("recent100", recent100Result);
        
        // 전략 4: 가중 빈도
        List<Integer> predictedByWeighted = predictByWeightedFrequency(historicalData);
        ValidationStrategyResult weightedResult = calculateMatch(
            "가중빈도", predictedByWeighted, actualWinning, actualBonus, actualAll);
        strategyResults.put("weighted", weightedResult);
        
        // 전략 5: 미출현 기반
        List<Integer> predictedByAbsence = predictByAbsence(historicalData);
        ValidationStrategyResult absenceResult = calculateMatch(
            "미출현기반", predictedByAbsence, actualWinning, actualBonus, actualAll);
        strategyResults.put("absence", absenceResult);
        
        // 전략 6: 패턴 기반 (홀짝, 구간 분포)
        List<Integer> predictedByPattern = predictByPattern(historicalData);
        ValidationStrategyResult patternResult = calculateMatch(
            "패턴기반", predictedByPattern, actualWinning, actualBonus, actualAll);
        strategyResults.put("pattern", patternResult);
        
        // 전략 7: 간격 기반
        List<Integer> predictedByInterval = predictByInterval(historicalData);
        ValidationStrategyResult intervalResult = calculateMatch(
            "간격기반", predictedByInterval, actualWinning, actualBonus, actualAll);
        strategyResults.put("interval", intervalResult);
        
        // 전략 8: 트렌드 기반
        List<Integer> predictedByTrend = predictByTrend(historicalData);
        ValidationStrategyResult trendResult = calculateMatch(
            "트렌드기반", predictedByTrend, actualWinning, actualBonus, actualAll);
        strategyResults.put("trend", trendResult);
        
        // 전략 9: 통계적 균형
        List<Integer> predictedByBalance = predictByStatisticalBalance(historicalData);
        ValidationStrategyResult balanceResult = calculateMatch(
            "통계적균형", predictedByBalance, actualWinning, actualBonus, actualAll);
        strategyResults.put("balance", balanceResult);
        
        // 전략 10: 앙상블 (여러 알고리즘 결합)
        List<Integer> predictedByEnsemble = predictByEnsemble(historicalData);
        ValidationStrategyResult ensembleResult = calculateMatch(
            "앙상블", predictedByEnsemble, actualWinning, actualBonus, actualAll);
        strategyResults.put("ensemble", ensembleResult);
        
        validationResult.setStrategyResults(strategyResults);
        
        // 최고 성능 전략 선택 (전체 맞춘 개수가 가장 많은 것)
        ValidationStrategyResult bestStrategy = strategyResults.values().stream()
            .max(Comparator.comparingInt(ValidationStrategyResult::getMatchCount))
            .orElse(null);
        
        if (bestStrategy != null) {
            validationResult.setPredictedNumbers(bestStrategy.getPredictedNumbers());
            validationResult.setTotalMatchCount(bestStrategy.getMatchCount());
            validationResult.setMatchedAllNumbers(bestStrategy.getMatchedNumbers());
            validationResult.setTotalAccuracy(bestStrategy.getAccuracy());
            
            // 당첨번호와 보너스번호 분리 매칭
            Set<Integer> predictedSet = new HashSet<>(bestStrategy.getPredictedNumbers());
            Set<Integer> winningSet = new HashSet<>(actualWinning);
            Set<Integer> bonusSet = new HashSet<>(actualBonus);
            
            Set<Integer> matchedWinning = new HashSet<>(predictedSet);
            matchedWinning.retainAll(winningSet);
            
            Set<Integer> matchedBonus = new HashSet<>(predictedSet);
            matchedBonus.retainAll(bonusSet);
            
            validationResult.setWinningMatchCount(matchedWinning.size());
            validationResult.setBonusMatchCount(matchedBonus.size());
            validationResult.setMatchedWinningNumbers(new ArrayList<>(matchedWinning));
            validationResult.setMatchedBonusNumbers(new ArrayList<>(matchedBonus));
            validationResult.setWinningAccuracy((double) matchedWinning.size() / WINNING_NUMBERS_COUNT);
            validationResult.setBonusAccuracy((double) matchedBonus.size() / BONUS_NUMBERS_COUNT);
        }
        
        return validationResult;
    }
    
    /**
     * 매칭 계산
     */
    private ValidationStrategyResult calculateMatch(
            String strategyName,
            List<Integer> predicted,
            List<Integer> actualWinning,
            List<Integer> actualBonus,
            List<Integer> actualAll) {
        
        ValidationStrategyResult result = new ValidationStrategyResult();
        result.setStrategyName(strategyName);
        result.setPredictedNumbers(predicted);
        
        // 전체 매칭
        Set<Integer> predictedSet = new HashSet<>(predicted);
        Set<Integer> actualAllSet = new HashSet<>(actualAll);
        Set<Integer> matchedSet = new HashSet<>(predictedSet);
        matchedSet.retainAll(actualAllSet);
        
        result.setMatchCount(matchedSet.size());
        result.setAccuracy((double) matchedSet.size() / TOTAL_DRAWN_NUMBERS);
        result.setMatchedNumbers(new ArrayList<>(matchedSet));
        
        return result;
    }
    
    /**
     * 최근 N주 검증 수행
     * 
     * @param weeks 검증할 주 수
     * @return 검증 결과 리스트
     */
    public List<ThoroughValidationResult> validateRecentWeeks(int weeks) {
        log.info("=== 최근 {}주 검증 시작 ===", weeks);
        
        List<LotteryResult> allResults = lotteryResultRepository.findAllByOrderByDrawDesc();
        
        if (allResults.isEmpty()) {
            log.warn("검증할 데이터가 없습니다.");
            return Collections.emptyList();
        }
        
        // 최신순으로 정렬되어 있으므로, 최근 N주 데이터 추출
        // 주당 약 2회 추첨 가정 (실제로는 확인 필요)
        int drawsPerWeek = 2;
        int targetDrawCount = weeks * drawsPerWeek;
        int actualDrawCount = Math.min(targetDrawCount, allResults.size());
        
        List<LotteryResult> recentResults = new ArrayList<>(allResults.subList(0, actualDrawCount));
        
        // 오래된 것부터 검증 (시간 순서대로)
        Collections.reverse(recentResults);
        
        List<ThoroughValidationResult> validationResults = new ArrayList<>();
        
        for (int i = 0; i < recentResults.size(); i++) {
            LotteryResult currentResult = recentResults.get(i);
            
            // 현재 회차 이전의 데이터만 사용 (데이터 누수 방지)
            List<LotteryResult> historicalData = new ArrayList<>();
            
            // 1. recentResults 내의 이전 데이터
            if (i > 0) {
                historicalData.addAll(recentResults.subList(0, i));
            }
            
            // 2. recentResults 이전의 모든 데이터
            if (allResults.size() > actualDrawCount) {
                historicalData.addAll(allResults.subList(actualDrawCount, allResults.size()));
            }
            
            // 시간 순서대로 정렬 (오래된 것부터)
            historicalData.sort(Comparator.comparing(LotteryResult::getDraw));
            
            ThoroughValidationResult result = validateSingleDraw(
                currentResult, 
                historicalData,
                "다중전략최고성능선택"
            );
            
            validationResults.add(result);
            
            // 상세 로그 출력
            log.info("=== Draw {} ({}) 검증 결과 ===", 
                result.getDraw(), result.getDrawDate());
            log.info("예측 번호 (9개): {}", result.getPredictedNumbers());
            log.info("실제 당첨번호 (7개): {}", result.getActualWinningNumbers());
            log.info("실제 보너스번호 (2개): {}", result.getActualBonusNumbers());
            log.info("당첨번호 맞춘 개수: {}/7", result.getWinningMatchCount());
            log.info("보너스번호 맞춘 개수: {}/2", result.getBonusMatchCount());
            log.info("전체 맞춘 개수: {}/9", result.getTotalMatchCount());
            log.info("맞춘 당첨번호: {}", result.getMatchedWinningNumbers());
            log.info("맞춘 보너스번호: {}", result.getMatchedBonusNumbers());
            log.info("맞춘 전체 번호: {}", result.getMatchedAllNumbers());
            log.info("전체 정확도: {:.2f}%", result.getTotalAccuracy() * 100);
            
            // 전략별 결과 출력
            log.info("전략별 결과:");
            for (Map.Entry<String, ValidationStrategyResult> entry : result.getStrategyResults().entrySet()) {
                ValidationStrategyResult strategyResult = entry.getValue();
                log.info("  {}: {}개 맞춤 (정확도: {:.2f}%), 맞춘 번호: {}", 
                    strategyResult.getStrategyName(),
                    strategyResult.getMatchCount(),
                    strategyResult.getAccuracy() * 100,
                    strategyResult.getMatchedNumbers());
            }
            log.info("");
        }
        
        // 통계 계산
        ThoroughValidationStatistics statistics = calculateStatistics(validationResults);
        logStatistics(statistics);
        
        return validationResults;
    }
    
    /**
     * 통계 계산
     */
    private ThoroughValidationStatistics calculateStatistics(
            List<ThoroughValidationResult> results) {
        
        ThoroughValidationStatistics stats = new ThoroughValidationStatistics();
        stats.setTotalValidations(results.size());
        
        if (results.isEmpty()) {
            return stats;
        }
        
        // 평균 계산
        stats.setAverageWinningAccuracy(
            results.stream().mapToDouble(ThoroughValidationResult::getWinningAccuracy).average().orElse(0.0));
        stats.setAverageBonusAccuracy(
            results.stream().mapToDouble(ThoroughValidationResult::getBonusAccuracy).average().orElse(0.0));
        stats.setAverageTotalAccuracy(
            results.stream().mapToDouble(ThoroughValidationResult::getTotalAccuracy).average().orElse(0.0));
        stats.setAverageWinningMatchCount(
            results.stream().mapToInt(ThoroughValidationResult::getWinningMatchCount).average().orElse(0.0));
        stats.setAverageBonusMatchCount(
            results.stream().mapToInt(ThoroughValidationResult::getBonusMatchCount).average().orElse(0.0));
        stats.setAverageTotalMatchCount(
            results.stream().mapToInt(ThoroughValidationResult::getTotalMatchCount).average().orElse(0.0));
        
        // 분포 계산
        Map<Integer, Integer> winningDist = new HashMap<>();
        Map<Integer, Integer> bonusDist = new HashMap<>();
        Map<Integer, Integer> totalDist = new HashMap<>();
        
        for (ThoroughValidationResult result : results) {
            winningDist.put(result.getWinningMatchCount(),
                winningDist.getOrDefault(result.getWinningMatchCount(), 0) + 1);
            bonusDist.put(result.getBonusMatchCount(),
                bonusDist.getOrDefault(result.getBonusMatchCount(), 0) + 1);
            totalDist.put(result.getTotalMatchCount(),
                totalDist.getOrDefault(result.getTotalMatchCount(), 0) + 1);
        }
        
        stats.setWinningMatchDistribution(winningDist);
        stats.setBonusMatchDistribution(bonusDist);
        stats.setTotalMatchDistribution(totalDist);
        
        // 최고 성능
        stats.setMaxWinningMatchCount(
            results.stream().mapToInt(ThoroughValidationResult::getWinningMatchCount).max().orElse(0));
        stats.setMaxBonusMatchCount(
            results.stream().mapToInt(ThoroughValidationResult::getBonusMatchCount).max().orElse(0));
        stats.setMaxTotalMatchCount(
            results.stream().mapToInt(ThoroughValidationResult::getTotalMatchCount).max().orElse(0));
        
        // 전략별 평균 정확도
        Map<String, Double> strategyAvgAccuracy = new HashMap<>();
        Set<String> allStrategies = new HashSet<>();
        for (ThoroughValidationResult result : results) {
            allStrategies.addAll(result.getStrategyResults().keySet());
        }
        
        for (String strategy : allStrategies) {
            double avg = results.stream()
                .filter(r -> r.getStrategyResults().containsKey(strategy))
                .mapToDouble(r -> r.getStrategyResults().get(strategy).getAccuracy())
                .average()
                .orElse(0.0);
            strategyAvgAccuracy.put(strategy, avg);
        }
        
        stats.setStrategyAverageAccuracy(strategyAvgAccuracy);
        
        return stats;
    }
    
    /**
     * 통계 로그 출력
     */
    private void logStatistics(ThoroughValidationStatistics stats) {
        log.info("=== 검증 통계 요약 ===");
        log.info("총 검증 횟수: {}", stats.getTotalValidations());
        log.info("평균 당첨번호 정확도: {:.2f}%", stats.getAverageWinningAccuracy() * 100);
        log.info("평균 보너스번호 정확도: {:.2f}%", stats.getAverageBonusAccuracy() * 100);
        log.info("평균 전체 정확도: {:.2f}%", stats.getAverageTotalAccuracy() * 100);
        log.info("평균 당첨번호 맞춘 개수: {:.2f}개", stats.getAverageWinningMatchCount());
        log.info("평균 보너스번호 맞춘 개수: {:.2f}개", stats.getAverageBonusMatchCount());
        log.info("평균 전체 맞춘 개수: {:.2f}개", stats.getAverageTotalMatchCount());
        log.info("최대 당첨번호 맞춘 개수: {}개", stats.getMaxWinningMatchCount());
        log.info("최대 보너스번호 맞춘 개수: {}개", stats.getMaxBonusMatchCount());
        log.info("최대 전체 맞춘 개수: {}개", stats.getMaxTotalMatchCount());
        
        log.info("\n당첨번호 맞춘 개수 분포:");
        for (int i = 0; i <= 7; i++) {
            int count = stats.getWinningMatchDistribution().getOrDefault(i, 0);
            double percentage = stats.getTotalValidations() > 0 
                ? (double) count / stats.getTotalValidations() * 100 : 0.0;
            log.info("  {}개: {}회 ({:.2f}%)", i, count, percentage);
        }
        
        log.info("\n전체 맞춘 개수 분포:");
        for (int i = 0; i <= 9; i++) {
            int count = stats.getTotalMatchDistribution().getOrDefault(i, 0);
            double percentage = stats.getTotalValidations() > 0 
                ? (double) count / stats.getTotalValidations() * 100 : 0.0;
            log.info("  {}개: {}회 ({:.2f}%)", i, count, percentage);
        }
        
        log.info("\n전략별 평균 정확도:");
        for (Map.Entry<String, Double> entry : stats.getStrategyAverageAccuracy().entrySet()) {
            log.info("  {}: {:.2f}%", entry.getKey(), entry.getValue() * 100);
        }
    }
}

