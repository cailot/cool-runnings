package hyung.jin.seo.coolrunnings.service;

import hyung.jin.seo.coolrunnings.model.LotteryResult;
import hyung.jin.seo.coolrunnings.repository.LotteryResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * CSV 파일에서 복권 결과를 읽어서 데이터베이스에 저장하는 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LotteryCsvService {

    private final LotteryResultRepository lotteryResultRepository;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * CSV 파일을 읽어서 데이터베이스에 저장
     * 
     * @param csvFilePath CSV 파일 경로 (classpath 기준)
     * @return 저장된 레코드 수
     */
    @Transactional
    public int importFromCsv(String csvFilePath) {
        if (csvFilePath == null || csvFilePath.trim().isEmpty()) {
            throw new IllegalArgumentException("CSV 파일 경로가 비어있습니다.");
        }
        
        log.info("CSV 파일 임포트 시작: {}", csvFilePath);
        
        int savedCount = 0;
        int skippedCount = 0;
        int errorCount = 0;
        
        try {
            ClassPathResource resource = new ClassPathResource(csvFilePath.trim());
            
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                
                // 헤더 라인 건너뛰기
                String headerLine = reader.readLine();
                if (headerLine == null) {
                    log.warn("CSV 파일이 비어있습니다.");
                    return 0;
                }
                log.debug("CSV 헤더: {}", headerLine);
                
                String line;
                int lineNumber = 1; // 헤더 포함
                
                while ((line = reader.readLine()) != null) {
                    lineNumber++;
                    
                    try {
                        LotteryResult result = parseCsvLine(line);
                        
                        if (result == null) {
                            skippedCount++;
                            continue;
                        }
                        
                        // Draw 번호로 중복 확인
                        Optional<LotteryResult> existing = lotteryResultRepository.findByDraw(result.getDraw());
                        
                        if (existing.isPresent()) {
                            log.debug("이미 존재하는 Draw 번호: {}", result.getDraw());
                            skippedCount++;
                            continue;
                        }
                        
                        lotteryResultRepository.save(result);
                        savedCount++;
                        
                        if (savedCount % 100 == 0) {
                            log.info("진행 상황: {}개 저장 완료", savedCount);
                        }
                        
                    } catch (Exception e) {
                        errorCount++;
                        log.error("라인 {} 파싱 실패: {}", lineNumber, e.getMessage());
                        log.debug("라인 내용: {}", line);
                    }
                }
            }
            
            log.info("CSV 임포트 완료: 총 {}개 저장, {}개 건너뜀, {}개 오류", 
                    savedCount, skippedCount, errorCount);
            
        } catch (Exception e) {
            log.error("CSV 파일 읽기 실패: {}", e.getMessage(), e);
            throw new RuntimeException("CSV 파일 임포트 실패", e);
        }
        
        return savedCount;
    }

    /**
     * CSV 라인을 파싱하여 LotteryResult 객체로 변환
     * 
     * CSV 형식: Draw,Date,Winning Number 1,2,3,4,5,6,7,Bonus Number 1,2,From Last,Low,High,Odd,Even,1-10,11-20,21-30,31-40,41-50
     * 
     * @param line CSV 라인
     * @return LotteryResult 객체 (파싱 실패 시 null)
     */
    private LotteryResult parseCsvLine(String line) {
        if (line == null || line.trim().isEmpty()) {
            return null;
        }
        
        // CSV 파싱 (쉼표로 구분, 따옴표 처리)
        List<String> fields = parseCsvFields(line);
        
        if (fields.size() < 11) {
            log.warn("필드 수가 부족합니다: {}", fields.size());
            return null;
        }
        
        try {
            // Draw 번호
            Integer draw = parseInteger(fields.get(0));
            if (draw == null) {
                return null;
            }
            
            // Date
            LocalDate drawDate = LocalDate.parse(fields.get(1), DATE_FORMATTER);
            
            // Winning Numbers (7개) - 개별 필드로 저장
            Integer winningNumber1 = parseInteger(fields.get(2));
            Integer winningNumber2 = parseInteger(fields.get(3));
            Integer winningNumber3 = parseInteger(fields.get(4));
            Integer winningNumber4 = parseInteger(fields.get(5));
            Integer winningNumber5 = parseInteger(fields.get(6));
            Integer winningNumber6 = parseInteger(fields.get(7));
            Integer winningNumber7 = parseInteger(fields.get(8));
            
            // Bonus Numbers (2개) - 개별 필드로 저장
            Integer bonusNumber1 = parseInteger(fields.get(9));
            Integer bonusNumber2 = parseInteger(fields.get(10));
            
            // From Last (쉼표로 구분된 번호들) - 문자열로 저장
            String fromLast = null;
            if (fields.size() > 11 && !fields.get(11).trim().isEmpty()) {
                fromLast = fields.get(11).replace("\"", "").trim();
            }
            
            // 통계 정보
            Integer lowCount = parseInteger(fields.size() > 12 ? fields.get(12) : null);
            Integer highCount = parseInteger(fields.size() > 13 ? fields.get(13) : null);
            Integer oddCount = parseInteger(fields.size() > 14 ? fields.get(14) : null);
            Integer evenCount = parseInteger(fields.size() > 15 ? fields.get(15) : null);
            Integer range1To10 = parseInteger(fields.size() > 16 ? fields.get(16) : null);
            Integer range11To20 = parseInteger(fields.size() > 17 ? fields.get(17) : null);
            Integer range21To30 = parseInteger(fields.size() > 18 ? fields.get(18) : null);
            Integer range31To40 = parseInteger(fields.size() > 19 ? fields.get(19) : null);
            Integer range41To50 = parseInteger(fields.size() > 20 ? fields.get(20) : null);
            
            return LotteryResult.builder()
                    .draw(draw)
                    .drawDate(drawDate)
                    .winningNumber1(winningNumber1)
                    .winningNumber2(winningNumber2)
                    .winningNumber3(winningNumber3)
                    .winningNumber4(winningNumber4)
                    .winningNumber5(winningNumber5)
                    .winningNumber6(winningNumber6)
                    .winningNumber7(winningNumber7)
                    .bonusNumber1(bonusNumber1)
                    .bonusNumber2(bonusNumber2)
                    .fromLast(fromLast)
                    .lowCount(lowCount)
                    .highCount(highCount)
                    .oddCount(oddCount)
                    .evenCount(evenCount)
                    .range1To10(range1To10)
                    .range11To20(range11To20)
                    .range21To30(range21To30)
                    .range31To40(range31To40)
                    .range41To50(range41To50)
                    .build();
                    
        } catch (Exception e) {
            log.error("CSV 라인 파싱 실패: {}", e.getMessage());
            log.debug("라인 내용: {}", line);
            return null;
        }
    }

    /**
     * CSV 필드를 파싱 (쉼표로 구분, 따옴표 처리)
     * 
     * @param line CSV 라인
     * @return 필드 리스트
     */
    private List<String> parseCsvFields(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder currentField = new StringBuilder();
        boolean inQuotes = false;
        
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                fields.add(currentField.toString().trim());
                currentField = new StringBuilder();
            } else {
                currentField.append(c);
            }
        }
        
        // 마지막 필드 추가
        fields.add(currentField.toString().trim());
        
        return fields;
    }

    /**
     * 문자열을 정수로 변환 (빈 문자열이나 null은 null 반환)
     * 
     * @param str 문자열
     * @return 정수 또는 null
     */
    private Integer parseInteger(String str) {
        if (str == null || str.trim().isEmpty()) {
            return null;
        }
        
        try {
            return Integer.parseInt(str.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
