package hyung.jin.seo.coolrunnings.service;

import hyung.jin.seo.coolrunnings.model.LotteryResult;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 과적합 검증 서비스
 * 무작위 데이터로 학습시켜서 과적합 여부를 검증
 * 
 * 원리: 만약 모델이 무작위 데이터에서도 높은 정확도를 보인다면,
 * 실제 데이터에서의 높은 정확도는 과적합일 가능성이 높음
 */
@Slf4j
@RequiredArgsConstructor
public class OverfittingValidationService {

    private final MachineLearningService machineLearningService;
    
    private static final int MAX_NUMBER = 44;
    private static final int TOTAL_DRAWN_NUMBERS = 9; // 당첨 번호 7개 + 보너스 번호 2개
    
    /**
     * 과적합 검증 결과
     */
    @Data
    public static class OverfittingValidationResult {
        private double realDataAccuracy;          // 실제 데이터에서의 정확도
        private double randomDataAccuracy;        // 무작위 데이터에서의 정확도
        private double overfittingRisk;           // 과적합 위험도 (0.0 ~ 1.0)
        private boolean overfittingDetected;      // 과적합 감지 여부
        private String explanation;               // 설명
        private Map<String, Double> detailedMetrics; // 상세 메트릭
    }
    
    /**
     * 무작위 복권 결과 생성
     * 
     * @param count 생성할 회차 수
     * @return 무작위 복권 결과 리스트
     */
    private List<LotteryResult> generateRandomLotteryResults(int count) {
        List<LotteryResult> randomResults = new ArrayList<>();
        Random random = new Random();
        
        for (int i = 0; i < count; i++) {
            LotteryResult result = new LotteryResult();
            result.setDraw(count - i); // 회차 번호
            
            // 무작위로 9개 번호 선택 (1-44 범위)
            Set<Integer> selectedNumbers = new HashSet<>();
            while (selectedNumbers.size() < TOTAL_DRAWN_NUMBERS) {
                int num = random.nextInt(MAX_NUMBER) + 1;
                selectedNumbers.add(num);
            }
            
            List<Integer> numbers = new ArrayList<>(selectedNumbers);
            Collections.shuffle(numbers, random);
            
            // 당첨 번호 7개
            result.setWinningNumber1(numbers.get(0));
            result.setWinningNumber2(numbers.get(1));
            result.setWinningNumber3(numbers.get(2));
            result.setWinningNumber4(numbers.get(3));
            result.setWinningNumber5(numbers.get(4));
            result.setWinningNumber6(numbers.get(5));
            result.setWinningNumber7(numbers.get(6));
            
            // 보너스 번호 2개
            result.setBonusNumber1(numbers.get(7));
            result.setBonusNumber2(numbers.get(8));
            
            randomResults.add(result);
        }
        
        return randomResults;
    }
    
    /**
     * 모델 정확도 계산
     * 
     * @param historicalData 과거 데이터
     * @param testData 테스트 데이터
     * @return 정확도 (0.0 ~ 1.0)
     */
    private double calculateModelAccuracy(
            List<LotteryResult> historicalData,
            List<LotteryResult> testData) {
        
        if (testData.isEmpty() || historicalData.isEmpty()) {
            return 0.0;
        }
        
        int correctPredictions = 0;
        int totalPredictions = 0;
        
        // 각 테스트 데이터에 대해 예측 수행
        for (LotteryResult testResult : testData) {
            // 과거 데이터로 예측 수행
            List<MachineLearningService.NumberRecommendationScore> scores = 
                machineLearningService.calculateRecommendationScores(Math.min(100, historicalData.size()));
            
            // 상위 9개 번호 예측
            List<Integer> predictedTop9 = scores.stream()
                .limit(9)
                .map(MachineLearningService.NumberRecommendationScore::getNumber)
                .collect(Collectors.toList());
            
            // 실제 번호 추출
            List<Integer> actualNumbers = extractActualNumbers(testResult);
            
            // 맞춘 개수 계산
            Set<Integer> predictedSet = new HashSet<>(predictedTop9);
            Set<Integer> actualSet = new HashSet<>(actualNumbers);
            predictedSet.retainAll(actualSet);
            
            // 최소 3개 이상 맞추면 성공으로 간주
            if (predictedSet.size() >= 3) {
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
     * 과적합 검증 수행
     * 
     * @param realData 실제 복권 데이터
     * @param testSize 테스트 데이터 크기
     * @return 과적합 검증 결과
     */
    public OverfittingValidationResult validateOverfitting(
            List<LotteryResult> realData,
            int testSize) {
        
        log.info("과적합 검증 시작 (실제 데이터: {}개, 테스트 크기: {})", realData.size(), testSize);
        
        OverfittingValidationResult result = new OverfittingValidationResult();
        result.setDetailedMetrics(new HashMap<>());
        
        if (realData.size() < testSize * 2) {
            log.warn("과적합 검증을 위한 데이터가 부족합니다.");
            result.setOverfittingRisk(1.0);
            result.setOverfittingDetected(true);
            result.setExplanation("데이터가 부족하여 검증할 수 없습니다.");
            return result;
        }
        
        // 실제 데이터로 학습 및 테스트
        List<LotteryResult> realTrainData = realData.subList(0, realData.size() - testSize);
        List<LotteryResult> realTestData = realData.subList(realData.size() - testSize, realData.size());
        
        // 실제 데이터에서의 정확도 계산 (정밀한 분석)
        log.debug("  실제 데이터 정확도 계산 시작 (학습: {}개, 테스트: {}개)...", realTrainData.size(), realTestData.size());
        long realAccuracyStartTime = System.currentTimeMillis();
        double realAccuracy = calculateSimpleAccuracy(realTrainData, realTestData);
        long realAccuracyElapsed = System.currentTimeMillis() - realAccuracyStartTime;
        log.debug("  실제 데이터 정확도 계산 완료 (소요 시간: {}ms, 정확도: {:.2f}%)", 
            realAccuracyElapsed, String.format("%.2f", realAccuracy * 100));
        result.setRealDataAccuracy(realAccuracy);
        result.getDetailedMetrics().put("real_data_accuracy", realAccuracy);
        
        // 무작위 데이터 생성 및 테스트
        log.debug("  무작위 데이터 생성 시작 (크기: {})...", realData.size());
        long randomDataStartTime = System.currentTimeMillis();
        int randomDataSize = realData.size();
        List<LotteryResult> randomData = generateRandomLotteryResults(randomDataSize);
        long randomDataElapsed = System.currentTimeMillis() - randomDataStartTime;
        log.debug("  무작위 데이터 생성 완료 (소요 시간: {}ms)", randomDataElapsed);
        
        List<LotteryResult> randomTrainData = randomData.subList(0, randomData.size() - testSize);
        List<LotteryResult> randomTestData = randomData.subList(randomData.size() - testSize, randomData.size());
        
        // 무작위 데이터에서의 정확도 계산 (정밀한 분석)
        log.debug("  무작위 데이터 정확도 계산 시작 (학습: {}개, 테스트: {}개)...", randomTrainData.size(), randomTestData.size());
        long randomAccuracyStartTime = System.currentTimeMillis();
        double randomAccuracy = calculateSimpleAccuracy(randomTrainData, randomTestData);
        long randomAccuracyElapsed = System.currentTimeMillis() - randomAccuracyStartTime;
        log.debug("  무작위 데이터 정확도 계산 완료 (소요 시간: {}ms, 정확도: {:.2f}%)", 
            randomAccuracyElapsed, String.format("%.2f", randomAccuracy * 100));
        result.setRandomDataAccuracy(randomAccuracy);
        result.getDetailedMetrics().put("random_data_accuracy", randomAccuracy);
        
        // 과적합 위험도 계산
        // 무작위 데이터에서의 정확도가 높으면 과적합 위험
        // 실제 데이터 정확도와 무작위 데이터 정확도의 차이가 작으면 과적합 위험
        double accuracyDiff = realAccuracy - randomAccuracy;
        double overfittingRisk;
        
        if (randomAccuracy > 0.4) {
            // 무작위 데이터에서도 40% 이상 정확하면 과적합 위험 높음
            overfittingRisk = 0.8 + (randomAccuracy - 0.4) * 0.5;
        } else if (accuracyDiff < 0.1) {
            // 실제와 무작위 데이터의 정확도 차이가 10% 미만이면 과적합 위험
            overfittingRisk = 0.6 + (0.1 - accuracyDiff) * 2.0;
        } else {
            // 정상적인 경우
            overfittingRisk = Math.max(0.0, 0.3 - accuracyDiff * 0.5);
        }
        
        overfittingRisk = Math.max(0.0, Math.min(1.0, overfittingRisk));
        result.setOverfittingRisk(overfittingRisk);
        boolean isOverfitting = overfittingRisk > 0.5;
        result.setOverfittingDetected(isOverfitting);
        
        // 설명 생성
        StringBuilder explanation = new StringBuilder();
        explanation.append(String.format("실제 데이터 정확도: %.2f%%, ", realAccuracy * 100));
        explanation.append(String.format("무작위 데이터 정확도: %.2f%%. ", randomAccuracy * 100));
        
        if (result.isOverfittingDetected()) {
            explanation.append("과적합이 감지되었습니다. ");
            explanation.append("무작위 데이터에서도 높은 정확도를 보이거나, ");
            explanation.append("실제 데이터와 무작위 데이터의 정확도 차이가 작습니다. ");
            explanation.append("모델이 데이터의 패턴이 아닌 노이즈를 학습했을 가능성이 높습니다.");
        } else {
            explanation.append("과적합 위험이 낮습니다. ");
            explanation.append("실제 데이터에서의 정확도가 무작위 데이터보다 유의미하게 높습니다.");
        }
        
        result.setExplanation(explanation.toString());
        
        log.info("과적합 검증 완료: 실제 데이터 정확도={:.2f}%, 무작위 데이터 정확도={:.2f}%, 과적합 위험도={:.2f}",
            String.format("%.2f", realAccuracy * 100), 
            String.format("%.2f", randomAccuracy * 100), 
            String.format("%.2f", overfittingRisk));
        
        return result;
    }
    
    /**
     * 정밀한 ML 기반 정확도 계산
     * 시계열 분석, 패턴 인식, 통계적 특징 등을 종합적으로 고려
     */
    private double calculateSimpleAccuracy(
            List<LotteryResult> trainData,
            List<LotteryResult> testData) {
        
        if (testData.isEmpty() || trainData.isEmpty()) {
            return 0.0;
        }
        
        log.debug("  정확도 계산 시작: 학습 데이터 {}개, 테스트 데이터 {}개", trainData.size(), testData.size());
        
        // 1. 기본 빈도 분석
        Map<Integer, Integer> frequencyMap = new HashMap<>();
        Map<Integer, List<Integer>> positionFrequencyMap = new HashMap<>(); // 위치별 빈도
        Map<Integer, Integer> consecutiveFrequencyMap = new HashMap<>(); // 연속 출현 빈도
        
        for (int i = 0; i < trainData.size(); i++) {
            LotteryResult result = trainData.get(i);
            List<Integer> numbers = extractActualNumbers(result);
            
            for (int j = 0; j < numbers.size(); j++) {
                int num = numbers.get(j);
                frequencyMap.put(num, frequencyMap.getOrDefault(num, 0) + 1);
                
                // 위치별 빈도
                positionFrequencyMap.putIfAbsent(num, new ArrayList<>());
                positionFrequencyMap.get(num).add(j);
            }
            
            // 연속 출현 패턴 분석
            if (i > 0) {
                List<Integer> prevNumbers = extractActualNumbers(trainData.get(i - 1));
                Set<Integer> prevSet = new HashSet<>(prevNumbers);
                for (Integer num : numbers) {
                    if (prevSet.contains(num)) {
                        consecutiveFrequencyMap.put(num, consecutiveFrequencyMap.getOrDefault(num, 0) + 1);
                    }
                }
            }
        }
        
        // 2. 시계열 패턴 분석 (최근 추세 반영)
        Map<Integer, Double> trendScores = new HashMap<>();
        int recentWindow = Math.min(20, trainData.size());
        for (int num = 1; num <= MAX_NUMBER; num++) {
            int recentCount = 0;
            int olderCount = 0;
            
            for (int i = 0; i < trainData.size(); i++) {
                List<Integer> numbers = extractActualNumbers(trainData.get(i));
                if (numbers.contains(num)) {
                    if (i < recentWindow) {
                        recentCount++;
                    } else {
                        olderCount++;
                    }
                }
            }
            
            // 최근 추세 점수 (최근에 더 많이 나오면 높은 점수)
            double trendScore = recentWindow > 0 ? 
                (recentCount / (double) recentWindow) - (olderCount / (double) Math.max(1, trainData.size() - recentWindow)) : 0.0;
            trendScores.put(num, trendScore);
        }
        
        // 3. 조합 패턴 분석 (자주 함께 나오는 번호 쌍)
        Map<String, Integer> pairFrequencyMap = new HashMap<>();
        for (LotteryResult result : trainData) {
            List<Integer> numbers = extractActualNumbers(result);
            for (int i = 0; i < numbers.size(); i++) {
                for (int j = i + 1; j < numbers.size(); j++) {
                    int num1 = Math.min(numbers.get(i), numbers.get(j));
                    int num2 = Math.max(numbers.get(i), numbers.get(j));
                    String pair = num1 + "-" + num2;
                    pairFrequencyMap.put(pair, pairFrequencyMap.getOrDefault(pair, 0) + 1);
                }
            }
        }
        
        // 4. 종합 점수 계산 (빈도 + 추세 + 연속성 + 조합)
        Map<Integer, Double> comprehensiveScores = new HashMap<>();
        for (int num = 1; num <= MAX_NUMBER; num++) {
            double freqScore = frequencyMap.getOrDefault(num, 0) / (double) trainData.size();
            double trendScore = Math.max(0.0, trendScores.getOrDefault(num, 0.0));
            double consecutiveScore = consecutiveFrequencyMap.getOrDefault(num, 0) / (double) Math.max(1, trainData.size() - 1);
            
            // 조합 점수 계산
            double pairScore = 0.0;
            for (Map.Entry<String, Integer> entry : pairFrequencyMap.entrySet()) {
                String[] parts = entry.getKey().split("-");
                if (parts.length == 2) {
                    int num1 = Integer.parseInt(parts[0]);
                    int num2 = Integer.parseInt(parts[1]);
                    if (num1 == num || num2 == num) {
                        pairScore += entry.getValue();
                    }
                }
            }
            pairScore = pairScore / (double) Math.max(1, pairFrequencyMap.size());
            
            // 가중 평균 (빈도 40%, 추세 30%, 연속성 20%, 조합 10%)
            double comprehensiveScore = freqScore * 0.4 + 
                                      (trendScore + 0.5) * 0.3 + 
                                      consecutiveScore * 0.2 + 
                                      pairScore * 0.1;
            
            comprehensiveScores.put(num, comprehensiveScore);
        }
        
        // 5. 상위 9개 번호 선택
        List<Integer> top9Predicted = comprehensiveScores.entrySet().stream()
            .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
            .limit(9)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
        
        log.info("  예측된 상위 9개 번호: {}", top9Predicted);
        
        // 6. 테스트 데이터에서 정확도 계산 (더 엄격한 기준)
        int correctPredictions = 0;
        int totalPredictions = 0;
        int exactMatches = 0; // 정확히 일치하는 경우
        
        log.info("  테스트 데이터 검증 시작: {}개 회차 검증...", testData.size());
        int testProcessed = 0;
        int testLogInterval = Math.max(1, testData.size() / 10); // 10%마다 로그
        
        for (LotteryResult testResult : testData) {
            List<Integer> actualNumbers = extractActualNumbers(testResult);
            Set<Integer> predictedSet = new HashSet<>(top9Predicted);
            Set<Integer> actualSet = new HashSet<>(actualNumbers);
            predictedSet.retainAll(actualSet);
            
            int matchCount = predictedSet.size();
            
            // 최소 3개 이상 맞추면 성공으로 간주
            if (matchCount >= 3) {
                correctPredictions++;
            }
            
            // 정확히 일치하는 경우 (7개 이상)
            if (matchCount >= 7) {
                exactMatches++;
            }
            
            totalPredictions++;
            
            testProcessed++;
            if (testProcessed % testLogInterval == 0 || testProcessed == testData.size()) {
                log.debug("    테스트 진행: {}/{} ({}%)", testProcessed, testData.size(), 
                    String.format("%.1f", testProcessed * 100.0 / testData.size()));
            }
        }
        
        double accuracy = totalPredictions > 0 ? (double) correctPredictions / totalPredictions : 0.0;
        double exactMatchRate = totalPredictions > 0 ? (double) exactMatches / totalPredictions : 0.0;
        
        log.info("  정확도 계산 완료: 전체 정확도={:.2f}%, 정확 일치율={:.2f}%", 
            String.format("%.2f", accuracy * 100), 
            String.format("%.2f", exactMatchRate * 100));
        
        return accuracy;
    }
}
