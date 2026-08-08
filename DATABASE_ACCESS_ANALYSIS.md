# 데이터베이스 접근 분석

## 문제점
`predictWithMultipleRuns()` 실행 시 **66,000번**의 데이터베이스 접근이 발생했습니다:
- 1500 runs × 44 numbers = 66,000번의 `findAllByOrderByDrawDesc()` 호출

## 수정 사항
1. `calculateProbability()` 메서드를 오버로드하여 데이터를 파라미터로 받도록 수정
2. `calculateAllProbabilities()` 메서드를 오버로드하여 데이터를 파라미터로 받도록 수정
3. `getMidNumbersInRange()` 메서드를 오버로드하여 데이터를 파라미터로 받도록 수정
4. `predictWithMultipleRuns()`에서 데이터를 **한 번만** 로드하고 모든 계산에 재사용

## 현재 데이터베이스 접근이 발생하는 경우

### 1. `predictWithMultipleRuns()` 시작 시
- **위치**: `NumberGuessService.predictWithMultipleRuns()` (line 1751)
- **호출**: `lotteryResultRepository.findAllByOrderByDrawDesc()`
- **횟수**: **1회** (수정 전: 66,000회)
- **용도**: 딥러닝 캐시 생성 및 반복 예측에 사용

### 2. `calculateProbability()` 단독 호출 시
- **위치**: `NumberGuessService.calculateProbability(int number)` (line 133)
- **호출**: `lotteryResultRepository.findAllByOrderByDrawDesc()`
- **횟수**: 호출될 때마다 1회
- **용도**: 단일 번호 확률 계산 (다른 메서드에서 호출 시)

### 3. `calculateAllProbabilities()` 단독 호출 시
- **위치**: `NumberGuessService.calculateAllProbabilities()` (line 1552)
- **호출**: `lotteryResultRepository.findAllByOrderByDrawDesc()`
- **횟수**: 호출될 때마다 1회
- **용도**: 모든 번호(1-44)의 확률 계산

### 4. `getMidNumbersInRange()` 단독 호출 시
- **위치**: `NumberGuessService.getMidNumbersInRange()` (line 1699)
- **호출**: `lotteryResultRepository.findAllByOrderByDrawDesc()`
- **횟수**: 호출될 때마다 1회
- **용도**: 39%~42% 확률 범위 번호 추출

### 5. `MachineLearningService` 메서드들
- **위치**: 
  - `performMLPrediction()` (line 410)
  - `calculateRecommendationScores()` (line 123)
  - `performTimeSeriesAnalysis()` (line 123)
  - `performLSTMLikeAnalysis()` (line 181)
  - `calculateFrequencyAnalysis()` (line 241)
  - `calculatePatternAnalysis()` (line 342)
- **호출**: `lotteryResultRepository.findAllByOrderByDrawDesc()`
- **횟수**: 각 메서드 호출 시 1회
- **용도**: ML 예측 수행

### 6. `AdvancedPredictionService` 메서드들
- **위치**:
  - `predict()` (line 791)
  - `trainAndCacheWeights()` (line 504)
  - `performCrossValidation()` (line 767)
- **호출**: `lotteryResultRepository.findAllByOrderByDrawDesc()`
- **횟수**: 각 메서드 호출 시 1회
- **용도**: 앙상블 예측 및 가중치 학습

### 7. `StatisticalAnalysisService` 메서드들
- **위치**: 여러 메서드에서 사용
- **호출**: `lotteryResultRepository.findAllByOrderByDrawDesc()`
- **횟수**: 각 메서드 호출 시 1회
- **용도**: 통계 분석

### 8. `ValidationService` 메서드들
- **위치**: 
  - `validatePredictionStrategy()` (line 360)
  - `validateWithHistoricalData()` (line 769)
- **호출**: `lotteryResultRepository.findAllByOrderByDrawDesc()`
- **횟수**: 각 메서드 호출 시 1회
- **용도**: 예측 전략 검증

### 9. `LotteryCrawlerStartupRunner` 시작 시
- **위치**: `LotteryCrawlerStartupRunner.run()` (line 66)
- **호출**: `numberGuessService.preloadDeepLearningCache()` → 내부적으로 DB 접근
- **횟수**: 1회
- **용도**: 딥러닝 캐시 사전 생성

### 10. 크롤링 관련
- **위치**: `LotteryCrawlerService`, `LotteryCsvService`
- **호출**: `findByDraw()`, `findFirstByOrderByDrawDesc()`, `save()`
- **횟수**: 크롤링 시마다
- **용도**: 데이터 저장 및 중복 확인

## 성능 개선 효과

### 수정 전
- `predictWithMultipleRuns()`: **66,000회** DB 접근
- 예상 실행 시간: 매우 오래 걸림 (수십 분 ~ 수시간)

### 수정 후
- `predictWithMultipleRuns()`: **1회** DB 접근
- 예상 실행 시간: 대폭 단축 (수 분 이내)

## 권장 사항

1. **캐싱 활용**: 이미 구현된 딥러닝 캐시 메커니즘을 활용
2. **데이터 재사용**: 가능한 한 데이터를 한 번만 로드하고 재사용
3. **배치 처리**: 여러 번호를 한 번에 처리할 때는 데이터를 파라미터로 전달
4. **모니터링**: 로그를 통해 실제 DB 접근 횟수 확인
