package hyung.jin.seo.coolrunnings.service;

import hyung.jin.seo.coolrunnings.model.LotteryResult;
import hyung.jin.seo.coolrunnings.repository.LotteryResultRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 1차 통계 분석 레이어
 * 번호별 출현 빈도, 최근 k회 이동평균, 연속 미출현 횟수, 홀짝·구간 분포 등
 * "설명 가능한" 지표를 제공하는 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatisticalAnalysisService {

    private final LotteryResultRepository lotteryResultRepository;
    
    // 번호 범위 (Set for Life는 1-44)
    private static final int MAX_NUMBER = 44;
    // 당첨 번호 7개 + 보너스 번호 2개 = 총 9개
    private static final int TOTAL_DRAWN_NUMBERS = 9;
    
    /**
     * 번호별 출현 빈도 통계
     */
    @Data
    public static class NumberFrequencyStats {
        private int number;
        private long totalAppearances;        // 전체 출현 횟수
        private double overallFrequency;     // 전체 출현 빈도 (0.0 ~ 1.0)
        private double recentFrequency;       // 최근 k회 출현 빈도
        private double theoreticalFrequency; // 이론적 출현 빈도 (9/44)
        private double frequencyDeviation;   // 이론적 빈도와의 편차
    }
    
    /**
     * 이동평균 통계
     */
    @Data
    public static class MovingAverageStats {
        private int number;
        private Map<Integer, Double> movingAverages; // k값별 이동평균 (k=10, 20, 30, 50, 100)
        private double trend; // 추세 (양수: 상승, 음수: 하락)
    }
    
    /**
     * 연속 미출현 통계
     */
    @Data
    public static class ConsecutiveAbsenceStats {
        private int number;
        private int currentAbsenceCount;     // 현재 연속 미출현 횟수
        private int maxAbsenceCount;          // 최대 연속 미출현 횟수
        private int averageAbsenceInterval;  // 평균 미출현 간격
        private double absenceProbability;    // 다음 회차 미출현 확률 (통계 기반)
    }
    
    /**
     * 홀짝 분포 통계
     */
    @Data
    public static class OddEvenDistributionStats {
        private int oddCount;                // 홀수 개수
        private int evenCount;                // 짝수 개수
        private double oddRatio;              // 홀수 비율
        private double evenRatio;             // 짝수 비율
        private Map<Integer, Integer> oddEvenPatterns; // 최근 N회차의 홀짝 패턴
    }
    
    /**
     * 구간 분포 통계
     */
    @Data
    public static class RangeDistributionStats {
        private Map<String, Integer> rangeCounts; // 구간별 번호 개수
        private Map<String, Double> rangeRatios;  // 구간별 비율
        // 구간: 1-10, 11-20, 21-30, 31-40, 41-44
    }
    
    /**
     * 번호별 통합 통계 DTO
     * 모든 통계 지표를 하나의 객체로 묶어서 제공
     */
    @Data
    public static class NumberStatisticsDTO {
        private int number;                          // 번호 (1-44)
        
        // 출현 빈도 관련
        private double hitRate;                     // 출현률 (0.0 ~ 1.0)
        private long totalAppearances;               // 전체 출현 횟수
        private double overallFrequency;            // 전체 출현 빈도
        private double recentFrequency;             // 최근 k회 출현 빈도
        
        // 미출현 관련
        private int missStreak;                     // 현재 연속 미출현 횟수
        private int maxMissStreak;                  // 최대 연속 미출현 횟수
        private int averageAbsenceInterval;         // 평균 미출현 간격
        
        // 이동평균 관련
        private double recentKMovingAvg;            // 최근 k회 이동평균 (기본 k=20)
        private Map<Integer, Double> movingAverages; // k값별 이동평균
        
        // 패턴 관련
        private String oddEvenPattern;              // 홀짝 패턴 ("ODD" 또는 "EVEN")
        private String rangeBucket;                 // 구간 버킷 ("1-10", "11-20", "21-30", "31-40", "41-44")
        
        // 추세 관련
        private double trend;                       // 추세 (양수: 상승, 음수: 하락)
        
        // 종합 점수 (랭킹용)
        private double compositeScore;               // 종합 점수 (0.0 ~ 1.0)
    }
    
    /**
     * 번호 랭킹 정보
     */
    @Data
    public static class NumberRanking {
        private List<NumberStatisticsDTO> rankedNumbers; // 랭킹 순서대로 정렬된 번호 리스트
        private int totalDraws;                          // 분석에 사용된 총 회차 수
        private int topK;                                // 상위 K개 번호 개수
    }
    
    /**
     * 종합 통계 분석 결과
     */
    @Data
    public static class ComprehensiveStats {
        private List<NumberFrequencyStats> frequencyStats;
        private List<MovingAverageStats> movingAverageStats;
        private List<ConsecutiveAbsenceStats> absenceStats;
        private OddEvenDistributionStats oddEvenStats;
        private RangeDistributionStats rangeStats;
        private int totalDraws; // 분석에 사용된 총 회차 수
        
        // 개선: 통합 DTO 및 랭킹 추가
        private List<NumberStatisticsDTO> numberStatistics; // 번호별 통합 통계
        private NumberRanking ranking;                      // 번호 랭킹
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
     * 모든 번호의 출현 빈도 통계 계산
     * 
     * @param recentK 최근 k회차 (null이면 전체 데이터 사용)
     * @return 번호별 출현 빈도 통계 리스트
     */
    public List<NumberFrequencyStats> calculateNumberFrequencyStats(Integer recentK) {
        List<LotteryResult> allResults = lotteryResultRepository.findAllByOrderByDrawDesc();
        
        if (allResults.isEmpty()) {
            log.warn("분석할 데이터가 없습니다.");
            return new ArrayList<>();
        }
        
        List<LotteryResult> analysisData = recentK != null && recentK > 0 && recentK < allResults.size()
            ? allResults.subList(0, recentK)
            : allResults;
        
        int totalDraws = analysisData.size();
        double theoreticalFreq = (double) TOTAL_DRAWN_NUMBERS / MAX_NUMBER;
        
        List<NumberFrequencyStats> statsList = new ArrayList<>();
        
        for (int num = 1; num <= MAX_NUMBER; num++) {
            final int number = num;
            
            long totalAppearances = analysisData.stream()
                .filter(r -> containsNumber(r, number))
                .count();
            
            double overallFreq = totalDraws > 0 ? (double) totalAppearances / totalDraws : 0.0;
            double deviation = overallFreq - theoreticalFreq;
            
            NumberFrequencyStats stats = new NumberFrequencyStats();
            stats.setNumber(number);
            stats.setTotalAppearances(totalAppearances);
            stats.setOverallFrequency(overallFreq);
            stats.setRecentFrequency(overallFreq); // recentK가 지정되면 최근 빈도
            stats.setTheoreticalFrequency(theoreticalFreq);
            stats.setFrequencyDeviation(deviation);
            
            statsList.add(stats);
        }
        
        return statsList;
    }
    
    /**
     * 번호별 이동평균 통계 계산
     * 
     * @param kValues 이동평균을 계산할 k 값 리스트 (예: [10, 20, 30, 50, 100])
     * @return 번호별 이동평균 통계 리스트
     */
    public List<MovingAverageStats> calculateMovingAverageStats(List<Integer> kValues) {
        List<LotteryResult> allResults = lotteryResultRepository.findAllByOrderByDrawDesc();
        
        if (allResults.isEmpty()) {
            log.warn("분석할 데이터가 없습니다.");
            return new ArrayList<>();
        }
        
        if (kValues == null || kValues.isEmpty()) {
            kValues = Arrays.asList(10, 20, 30, 50, 100);
        }
        
        List<MovingAverageStats> statsList = new ArrayList<>();
        
        for (int num = 1; num <= MAX_NUMBER; num++) {
            final int number = num;
            
            MovingAverageStats stats = new MovingAverageStats();
            stats.setNumber(number);
            stats.setMovingAverages(new HashMap<>());
            
            // 각 k값에 대해 이동평균 계산
            for (Integer k : kValues) {
                if (k > allResults.size()) {
                    continue;
                }
                
                List<LotteryResult> recentK = allResults.subList(0, k);
                long appearances = recentK.stream()
                    .filter(r -> containsNumber(r, number))
                    .count();
                
                double movingAvg = (double) appearances / k;
                stats.getMovingAverages().put(k, movingAvg);
            }
            
            // 추세 계산: 최근 20회와 그 이전 20회 비교
            if (allResults.size() >= 40) {
                long recent20 = allResults.subList(0, 20).stream()
                    .filter(r -> containsNumber(r, number))
                    .count();
                long previous20 = allResults.subList(20, 40).stream()
                    .filter(r -> containsNumber(r, number))
                    .count();
                
                double recentFreq = (double) recent20 / 20.0;
                double previousFreq = (double) previous20 / 20.0;
                stats.setTrend(recentFreq - previousFreq);
            } else {
                stats.setTrend(0.0);
            }
            
            statsList.add(stats);
        }
        
        return statsList;
    }
    
    /**
     * 번호별 연속 미출현 통계 계산
     * 
     * @return 번호별 연속 미출현 통계 리스트
     */
    public List<ConsecutiveAbsenceStats> calculateConsecutiveAbsenceStats() {
        List<LotteryResult> allResults = lotteryResultRepository.findAllByOrderByDrawDesc();
        
        if (allResults.isEmpty()) {
            log.warn("분석할 데이터가 없습니다.");
            return new ArrayList<>();
        }
        
        List<ConsecutiveAbsenceStats> statsList = new ArrayList<>();
        
        for (int num = 1; num <= MAX_NUMBER; num++) {
            final int number = num;
            
            ConsecutiveAbsenceStats stats = new ConsecutiveAbsenceStats();
            stats.setNumber(number);
            
            // 출현 회차 추출
            List<Integer> appearanceDraws = new ArrayList<>();
            for (LotteryResult result : allResults) {
                if (containsNumber(result, number)) {
                    appearanceDraws.add(result.getDraw());
                }
            }
            
            if (appearanceDraws.isEmpty()) {
                // 한 번도 출현하지 않은 경우
                stats.setCurrentAbsenceCount(allResults.size());
                stats.setMaxAbsenceCount(allResults.size());
                stats.setAverageAbsenceInterval(0);
                stats.setAbsenceProbability(1.0);
                statsList.add(stats);
                continue;
            }
            
            // 현재 연속 미출현 횟수 계산
            int latestDraw = allResults.get(0).getDraw();
            int lastAppearanceDraw = appearanceDraws.get(0);
            int currentAbsence = latestDraw - lastAppearanceDraw;
            stats.setCurrentAbsenceCount(currentAbsence);
            
            // 최대 연속 미출현 횟수 계산
            int maxAbsence = 0;
            for (int i = 1; i < appearanceDraws.size(); i++) {
                int interval = appearanceDraws.get(i - 1) - appearanceDraws.get(i);
                maxAbsence = Math.max(maxAbsence, interval);
            }
            // 첫 출현 이전의 간격도 고려
            if (!allResults.isEmpty()) {
                int firstAppearanceDraw = appearanceDraws.get(appearanceDraws.size() - 1);
                int oldestDraw = allResults.get(allResults.size() - 1).getDraw();
                maxAbsence = Math.max(maxAbsence, firstAppearanceDraw - oldestDraw);
            }
            stats.setMaxAbsenceCount(maxAbsence);
            
            // 평균 미출현 간격 계산
            if (appearanceDraws.size() > 1) {
                int totalInterval = 0;
                for (int i = 1; i < appearanceDraws.size(); i++) {
                    totalInterval += appearanceDraws.get(i - 1) - appearanceDraws.get(i);
                }
                stats.setAverageAbsenceInterval(totalInterval / (appearanceDraws.size() - 1));
            } else {
                stats.setAverageAbsenceInterval(currentAbsence);
            }
            
            // 다음 회차 미출현 확률 계산 (간격 기반)
            if (stats.getAverageAbsenceInterval() > 0) {
                // 평균 간격보다 현재 간격이 짧으면 출현 확률 높음
                double ratio = (double) currentAbsence / stats.getAverageAbsenceInterval();
                if (ratio < 0.8) {
                    stats.setAbsenceProbability(0.3); // 출현 가능성 높음
                } else if (ratio < 1.2) {
                    stats.setAbsenceProbability(0.5); // 평균
                } else {
                    stats.setAbsenceProbability(0.7); // 출현 가능성 낮음
                }
            } else {
                stats.setAbsenceProbability(0.5);
            }
            
            statsList.add(stats);
        }
        
        return statsList;
    }
    
    /**
     * 홀짝 분포 통계 계산
     * 
     * @param recentK 최근 k회차 (null이면 전체 데이터 사용)
     * @return 홀짝 분포 통계
     */
    public OddEvenDistributionStats calculateOddEvenDistributionStats(Integer recentK) {
        List<LotteryResult> allResults = lotteryResultRepository.findAllByOrderByDrawDesc();
        
        if (allResults.isEmpty()) {
            log.warn("분석할 데이터가 없습니다.");
            return null;
        }
        
        List<LotteryResult> analysisData = recentK != null && recentK > 0 && recentK < allResults.size()
            ? allResults.subList(0, recentK)
            : allResults;
        
        OddEvenDistributionStats stats = new OddEvenDistributionStats();
        stats.setOddEvenPatterns(new HashMap<>());
        
        int totalOdd = 0;
        int totalEven = 0;
        
        // 각 회차별 홀짝 패턴 분석
        for (int i = 0; i < analysisData.size(); i++) {
            LotteryResult result = analysisData.get(i);
            int oddCount = countOddNumbers(result);
            int evenCount = TOTAL_DRAWN_NUMBERS - oddCount;
            
            totalOdd += oddCount;
            totalEven += evenCount;
            
            // 패턴 기록 (홀수 개수만 저장)
            stats.getOddEvenPatterns().put(i, oddCount);
        }
        
        int totalNumbers = totalOdd + totalEven;
        stats.setOddCount(totalOdd);
        stats.setEvenCount(totalEven);
        stats.setOddRatio(totalNumbers > 0 ? (double) totalOdd / totalNumbers : 0.0);
        stats.setEvenRatio(totalNumbers > 0 ? (double) totalEven / totalNumbers : 0.0);
        
        return stats;
    }
    
    /**
     * 구간 분포 통계 계산
     * 
     * @param recentK 최근 k회차 (null이면 전체 데이터 사용)
     * @return 구간 분포 통계
     */
    public RangeDistributionStats calculateRangeDistributionStats(Integer recentK) {
        List<LotteryResult> allResults = lotteryResultRepository.findAllByOrderByDrawDesc();
        
        if (allResults.isEmpty()) {
            log.warn("분석할 데이터가 없습니다.");
            return null;
        }
        
        List<LotteryResult> analysisData = recentK != null && recentK > 0 && recentK < allResults.size()
            ? allResults.subList(0, recentK)
            : allResults;
        
        RangeDistributionStats stats = new RangeDistributionStats();
        stats.setRangeCounts(new HashMap<>());
        stats.setRangeRatios(new HashMap<>());
        
        int range1To10 = 0;
        int range11To20 = 0;
        int range21To30 = 0;
        int range31To40 = 0;
        int range41To44 = 0;
        
        for (LotteryResult result : analysisData) {
            range1To10 += countNumbersInRange(result, 1, 10);
            range11To20 += countNumbersInRange(result, 11, 20);
            range21To30 += countNumbersInRange(result, 21, 30);
            range31To40 += countNumbersInRange(result, 31, 40);
            range41To44 += countNumbersInRange(result, 41, 44);
        }
        
        int totalNumbers = range1To10 + range11To20 + range21To30 + range31To40 + range41To44;
        
        stats.getRangeCounts().put("1-10", range1To10);
        stats.getRangeCounts().put("11-20", range11To20);
        stats.getRangeCounts().put("21-30", range21To30);
        stats.getRangeCounts().put("31-40", range31To40);
        stats.getRangeCounts().put("41-44", range41To44);
        
        if (totalNumbers > 0) {
            stats.getRangeRatios().put("1-10", (double) range1To10 / totalNumbers);
            stats.getRangeRatios().put("11-20", (double) range11To20 / totalNumbers);
            stats.getRangeRatios().put("21-30", (double) range21To30 / totalNumbers);
            stats.getRangeRatios().put("31-40", (double) range31To40 / totalNumbers);
            stats.getRangeRatios().put("41-44", (double) range41To44 / totalNumbers);
        }
        
        return stats;
    }
    
    /**
     * 번호별 통합 통계 DTO 생성
     * 모든 통계 지표를 하나의 DTO로 묶어서 제공
     * 
     * @param recentK 최근 k회차 (null이면 전체 데이터 사용)
     * @param kValues 이동평균 k값 리스트
     * @return 번호별 통합 통계 DTO 리스트
     */
    public List<NumberStatisticsDTO> generateNumberStatisticsDTOs(Integer recentK, List<Integer> kValues) {
        List<LotteryResult> allResults = lotteryResultRepository.findAllByOrderByDrawDesc();
        
        if (allResults.isEmpty()) {
            log.warn("분석할 데이터가 없습니다.");
            return new ArrayList<>();
        }
        
        // 각 통계 계산
        List<NumberFrequencyStats> freqStats = calculateNumberFrequencyStats(recentK);
        List<MovingAverageStats> movingAvgStats = calculateMovingAverageStats(kValues);
        List<ConsecutiveAbsenceStats> absenceStats = calculateConsecutiveAbsenceStats();
        // oddEvenStats와 rangeStats는 현재 DTO 생성에 직접 사용되지 않지만,
        // 향후 확장성을 위해 계산은 유지 (경고 무시)
        calculateOddEvenDistributionStats(recentK);
        calculateRangeDistributionStats(recentK);
        
        // 번호별 통합 DTO 생성
        List<NumberStatisticsDTO> dtos = new ArrayList<>();
        
        for (int num = 1; num <= MAX_NUMBER; num++) {
            final int number = num; // final 변수로 변환
            NumberStatisticsDTO dto = new NumberStatisticsDTO();
            dto.setNumber(number);
            
            // 출현 빈도 정보
            NumberFrequencyStats freqStat = freqStats.stream()
                .filter(s -> s.getNumber() == number)
                .findFirst()
                .orElse(null);
            if (freqStat != null) {
                dto.setHitRate(freqStat.getOverallFrequency());
                dto.setTotalAppearances(freqStat.getTotalAppearances());
                dto.setOverallFrequency(freqStat.getOverallFrequency());
                dto.setRecentFrequency(freqStat.getRecentFrequency());
            }
            
            // 미출현 정보
            ConsecutiveAbsenceStats absenceStat = absenceStats.stream()
                .filter(s -> s.getNumber() == number)
                .findFirst()
                .orElse(null);
            if (absenceStat != null) {
                dto.setMissStreak(absenceStat.getCurrentAbsenceCount());
                dto.setMaxMissStreak(absenceStat.getMaxAbsenceCount());
                dto.setAverageAbsenceInterval(absenceStat.getAverageAbsenceInterval());
            }
            
            // 이동평균 정보
            MovingAverageStats movingAvgStat = movingAvgStats.stream()
                .filter(s -> s.getNumber() == number)
                .findFirst()
                .orElse(null);
            if (movingAvgStat != null) {
                dto.setMovingAverages(movingAvgStat.getMovingAverages());
                dto.setTrend(movingAvgStat.getTrend());
                // 기본 k=20 이동평균 사용
                dto.setRecentKMovingAvg(movingAvgStat.getMovingAverages().getOrDefault(20, 0.0));
            }
            
            // 홀짝 패턴
            dto.setOddEvenPattern(number % 2 == 1 ? "ODD" : "EVEN");
            
            // 구간 버킷
            if (number >= 1 && number <= 10) {
                dto.setRangeBucket("1-10");
            } else if (number >= 11 && number <= 20) {
                dto.setRangeBucket("11-20");
            } else if (number >= 21 && number <= 30) {
                dto.setRangeBucket("21-30");
            } else if (number >= 31 && number <= 40) {
                dto.setRangeBucket("31-40");
            } else {
                dto.setRangeBucket("41-44");
            }
            
            // 종합 점수 계산 (랭킹용)
            // hitRate, recentFrequency, missStreak(높을수록 좋음), trend 등을 종합
            double compositeScore = calculateCompositeScore(dto);
            dto.setCompositeScore(compositeScore);
            
            dtos.add(dto);
        }
        
        return dtos;
    }
    
    /**
     * 종합 점수 계산 (랭킹용)
     */
    private double calculateCompositeScore(NumberStatisticsDTO dto) {
        // 가중치 기반 점수 계산
        double score = 0.0;
        
        // 1. 출현 빈도 (40% 가중치)
        score += dto.getHitRate() * 0.4;
        
        // 2. 최근 빈도 (30% 가중치)
        score += dto.getRecentFrequency() * 0.3;
        
        // 3. 미출현 횟수 (20% 가중치) - 너무 오래 안 나온 번호는 높은 점수
        // missStreak이 평균보다 크면 높은 점수
        double missStreakScore = Math.min(1.0, dto.getMissStreak() / 10.0);
        score += missStreakScore * 0.2;
        
        // 4. 추세 (10% 가중치) - 상승 추세면 높은 점수
        double trendScore = Math.max(0.0, Math.min(1.0, (dto.getTrend() + 0.1) / 0.2));
        score += trendScore * 0.1;
        
        return Math.max(0.0, Math.min(1.0, score));
    }
    
    /**
     * 번호 랭킹 생성
     * 현재 회차 기준으로 정렬된 랭킹 제공
     * 
     * @param recentK 최근 k회차 (null이면 전체 데이터 사용)
     * @param kValues 이동평균 k값 리스트
     * @param topK 상위 K개 번호 (기본값: 10)
     * @return 번호 랭킹 정보
     */
    public NumberRanking generateNumberRanking(Integer recentK, List<Integer> kValues, Integer topK) {
        List<NumberStatisticsDTO> dtos = generateNumberStatisticsDTOs(recentK, kValues);
        
        // 종합 점수 기준으로 정렬 (내림차순)
        dtos.sort((a, b) -> Double.compare(b.getCompositeScore(), a.getCompositeScore()));
        
        NumberRanking ranking = new NumberRanking();
        ranking.setRankedNumbers(dtos);
        
        List<LotteryResult> allResults = lotteryResultRepository.findAllByOrderByDrawDesc();
        ranking.setTotalDraws(allResults.size());
        ranking.setTopK(topK != null ? topK : 10);
        
        log.info("번호 랭킹 생성 완료 (상위 {}개 번호)", ranking.getTopK());
        for (int i = 0; i < Math.min(ranking.getTopK(), dtos.size()); i++) {
            NumberStatisticsDTO dto = dtos.get(i);
            log.info("  {}위: 번호 {} (점수: {:.3f}, hitRate: {:.3f}, missStreak: {})",
                i + 1, dto.getNumber(), dto.getCompositeScore(), dto.getHitRate(), dto.getMissStreak());
        }
        
        return ranking;
    }
    
    /**
     * 종합 통계 분석 수행
     * 
     * @param recentK 최근 k회차 (null이면 전체 데이터 사용)
     * @param kValues 이동평균 k값 리스트
     * @return 종합 통계 분석 결과
     */
    public ComprehensiveStats performComprehensiveAnalysis(Integer recentK, List<Integer> kValues) {
        log.info("종합 통계 분석 시작 (recentK: {})", recentK);
        
        ComprehensiveStats comprehensive = new ComprehensiveStats();
        
        comprehensive.setFrequencyStats(calculateNumberFrequencyStats(recentK));
        comprehensive.setMovingAverageStats(calculateMovingAverageStats(kValues));
        comprehensive.setAbsenceStats(calculateConsecutiveAbsenceStats());
        comprehensive.setOddEvenStats(calculateOddEvenDistributionStats(recentK));
        comprehensive.setRangeStats(calculateRangeDistributionStats(recentK));
        
        List<LotteryResult> allResults = lotteryResultRepository.findAllByOrderByDrawDesc();
        comprehensive.setTotalDraws(allResults.size());
        
        // 개선: 통합 DTO 및 랭킹 추가
        List<NumberStatisticsDTO> numberStatistics = generateNumberStatisticsDTOs(recentK, kValues);
        comprehensive.setNumberStatistics(numberStatistics);
        
        NumberRanking ranking = generateNumberRanking(recentK, kValues, 10);
        comprehensive.setRanking(ranking);
        
        log.info("종합 통계 분석 완료 (총 회차: {})", comprehensive.getTotalDraws());
        
        return comprehensive;
    }
    
    /**
     * 홀수 개수 계산
     */
    private int countOddNumbers(LotteryResult result) {
        int count = 0;
        List<Integer> numbers = Arrays.asList(
            result.getWinningNumber1(), result.getWinningNumber2(), result.getWinningNumber3(),
            result.getWinningNumber4(), result.getWinningNumber5(), result.getWinningNumber6(),
            result.getWinningNumber7(), result.getBonusNumber1(), result.getBonusNumber2()
        );
        
        for (Integer num : numbers) {
            if (num != null && num % 2 == 1) {
                count++;
            }
        }
        
        return count;
    }
    
    /**
     * 특정 범위의 번호 개수 계산
     */
    private int countNumbersInRange(LotteryResult result, int min, int max) {
        int count = 0;
        List<Integer> numbers = Arrays.asList(
            result.getWinningNumber1(), result.getWinningNumber2(), result.getWinningNumber3(),
            result.getWinningNumber4(), result.getWinningNumber5(), result.getWinningNumber6(),
            result.getWinningNumber7(), result.getBonusNumber1(), result.getBonusNumber2()
        );
        
        for (Integer num : numbers) {
            if (num != null && num >= min && num <= max) {
                count++;
            }
        }
        
        return count;
    }
}

