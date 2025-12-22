package hyung.jin.seo.coolrunnings.controller;

import hyung.jin.seo.coolrunnings.service.LotteryCrawlerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 복권 결과 크롤링 컨트롤러
 */
@Slf4j
@RestController
@RequestMapping("/api/crawler")
@RequiredArgsConstructor
public class LotteryCrawlerController {

    private final LotteryCrawlerService lotteryCrawlerService;

    /**
     * 최신 회차 확인 및 업데이트
     * 
     * @return 업데이트 결과
     */
    @PostMapping("/check-and-update")
    public ResponseEntity<Map<String, Object>> checkAndUpdate() {
        log.info("크롤링 요청 수신");
        
        Map<String, Object> response = new HashMap<>();
        
        try {
            int savedCount = lotteryCrawlerService.checkAndUpdateLatestDraws();
            
            response.put("success", true);
            if (savedCount == 0) {
                response.put("message", "크롤링 완료 (저장된 데이터 없음 - 이미 최신이거나 크롤링 실패)");
            } else {
                response.put("message", "크롤링 완료");
            }
            response.put("savedCount", savedCount);
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("크롤링 실패: {}", e.getMessage(), e);
            
            response.put("success", false);
            response.put("message", "크롤링 실패: " + e.getMessage());
            response.put("savedCount", 0);
            
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }
}
