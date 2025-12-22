package hyung.jin.seo.coolrunnings.service;

import com.sendgrid.Method;
import com.sendgrid.Request;
import com.sendgrid.SendGrid;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Content;
import com.sendgrid.helpers.mail.objects.Email;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 이메일 전송 서비스 (SendGrid 사용)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    @Value("${email.api.key}")
    private String sendGridApiKey;

    @Value("${email.sender.address}")
    private String senderAddress;

    @Value("${email.send.to}")
    private String recipientAddress;

    /**
     * 번호 예측 결과를 이메일로 전송
     * 
     * @param top7Numbers 상위 7개 번호와 확률
     * @param bottom7Numbers 하위 7개 번호와 확률
     * @param mid7Numbers 중간 7개 번호와 확률
     */
    public void sendNumberPredictionResults(
            List<NumberGuessService.NumberProbability> top7Numbers,
            List<NumberGuessService.NumberProbability> bottom7Numbers,
            List<NumberGuessService.NumberProbability> mid7Numbers) {
        
        try {
            String subject = "JAC Automator Test Bot...";
            String htmlContent = buildEmailContent(top7Numbers, bottom7Numbers, mid7Numbers);
            
            // sendEmail(subject, htmlContent);
            log.info("번호 예측 결과 이메일 전송 완료: {}", recipientAddress);
            
        } catch (Exception e) {
            log.error("번호 예측 결과 이메일 전송 실패: {}", e.getMessage(), e);
        }
    }

    /**
     * 이메일 본문 생성
     */
    private String buildEmailContent(
            List<NumberGuessService.NumberProbability> top7Numbers,
            List<NumberGuessService.NumberProbability> bottom7Numbers,
            List<NumberGuessService.NumberProbability> mid7Numbers) {
        
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>");
        html.append("<html>");
        html.append("<head>");
        html.append("<meta charset='UTF-8'>");
        html.append("<style>");
        html.append("body { font-family: Arial, sans-serif; line-height: 1.6; color: #333; }");
        html.append("h2 { color: #2c3e50; border-bottom: 2px solid #3498db; padding-bottom: 10px; }");
        html.append("table { width: 100%; border-collapse: collapse; margin: 20px 0; }");
        html.append("th { background-color: #3498db; color: white; padding: 12px; text-align: left; }");
        html.append("td { padding: 10px; border-bottom: 1px solid #ddd; }");
        html.append("tr:nth-child(even) { background-color: #f2f2f2; }");
        html.append(".top-section { background-color: #e8f5e9; padding: 15px; border-radius: 5px; margin-bottom: 20px; }");
        html.append(".mid-section { background-color: #e3f2fd; padding: 15px; border-radius: 5px; margin-bottom: 20px; }");
        html.append(".bottom-section { background-color: #fff3e0; padding: 15px; border-radius: 5px; }");
        html.append(".probability { font-weight: bold; color: #2e7d32; }");
        html.append(".mid-probability { font-weight: bold; color: #1976d2; }");
        html.append(".low-probability { font-weight: bold; color: #d32f2f; }");
        html.append("</style>");
        html.append("</head>");
        html.append("<body>");
        html.append("<h1>Let's roll the dice for.......Set for Life</h1>");
        html.append("<p>다음 회차에 나올 가능성이 높은/낮은 번호를 분석한 결과입니다.</p>");
        
        // 상위 7개 번호
        html.append("<div class='top-section'>");
        html.append("<h2>🎯 추천 번호 (확률 높은 상위 7개)</h2>");
        html.append("<table>");
        html.append("<tr><th>순위</th><th>번호</th><th>확률</th></tr>");
        for (int i = 0; i < top7Numbers.size(); i++) {
            NumberGuessService.NumberProbability np = top7Numbers.get(i);
            String probabilityStr = String.format("%.2f", np.getProbability() * 100);
            html.append("<tr>");
            html.append("<td>").append(i + 1).append("위</td>");
            html.append("<td><strong>").append(np.getNumber()).append("</strong></td>");
            html.append("<td><span class='probability'>").append(probabilityStr).append("%</span></td>");
            html.append("</tr>");
        }
        html.append("</table>");
        html.append("</div>");
        
        // 중간 7개 번호
        html.append("<div class='mid-section'>");
        html.append("<h2>⚖️ 중간 확률 번호 (High7 + Low7 혼합)</h2>");
        html.append("<table>");
        html.append("<tr><th>순위</th><th>번호</th><th>확률</th></tr>");
        for (int i = 0; i < mid7Numbers.size(); i++) {
            NumberGuessService.NumberProbability np = mid7Numbers.get(i);
            String probabilityStr = String.format("%.2f", np.getProbability() * 100);
            html.append("<tr>");
            html.append("<td>").append(i + 1).append("위</td>");
            html.append("<td><strong>").append(np.getNumber()).append("</strong></td>");
            html.append("<td><span class='mid-probability'>").append(probabilityStr).append("%</span></td>");
            html.append("</tr>");
        }
        html.append("</table>");
        html.append("</div>");
        
        // 하위 7개 번호
        html.append("<div class='bottom-section'>");
        html.append("<h2>⚠️ 비추천 번호 (확률 낮은 하위 7개)</h2>");
        html.append("<table>");
        html.append("<tr><th>순위</th><th>번호</th><th>확률</th></tr>");
        for (int i = 0; i < bottom7Numbers.size(); i++) {
            NumberGuessService.NumberProbability np = bottom7Numbers.get(i);
            String probabilityStr = String.format("%.2f", np.getProbability() * 100);
            html.append("<tr>");
            html.append("<td>").append(i + 1).append("위</td>");
            html.append("<td><strong>").append(np.getNumber()).append("</strong></td>");
            html.append("<td><span class='low-probability'>").append(probabilityStr).append("%</span></td>");
            html.append("</tr>");
        }
        html.append("</table>");
        html.append("</div>");
        
        html.append("<hr>");
        html.append("<p style='color: #666; font-size: 12px;'>이 예측은 과거 데이터를 기반으로 한 통계적 분석이며, 실제 당첨을 보장하지 않습니다.</p>");
        html.append("</body>");
        html.append("</html>");
        
        return html.toString();
    }

    /**
     * SendGrid를 사용하여 이메일 전송
     */
    private void sendEmail(String subject, String htmlContent) throws Exception {
        Email from = new Email(senderAddress);
        Email to = new Email(recipientAddress);
        Content content = new Content("text/html", htmlContent);
        Mail mail = new Mail(from, subject, to, content);

        SendGrid sg = new SendGrid(sendGridApiKey);
        Request request = new Request();
        
        try {
            request.setMethod(Method.POST);
            request.setEndpoint("mail/send");
            request.setBody(mail.build());
            
            com.sendgrid.Response response = sg.api(request);
            
            if (response.getStatusCode() >= 200 && response.getStatusCode() < 300) {
                log.info("이메일 전송 성공: Status Code {}", response.getStatusCode());
            } else {
                log.warn("이메일 전송 응답: Status Code {}, Body {}", 
                    response.getStatusCode(), response.getBody());
            }
        } catch (Exception e) {
            log.error("SendGrid API 호출 실패: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 반복 예측 결과를 이메일로 전송
     * 
     * @param top7Numbers 상위 7개 번호 (등장횟수 순)
     * @param midRange7Numbers 39%~42% 범위 7개 번호 (등장횟수 순)
     * @param top7Frequencies 상위 7개 번호의 등장횟수 맵
     * @param midRange7Frequencies 39%~42% 범위 7개 번호의 등장횟수 맵
     * @param elapsedTime 총 소요 시간 (밀리초)
     * @param runsCount 반복 실행 횟수
     */
    public void sendMultipleRunsPredictionResults(
            List<NumberGuessService.NumberProbability> top7Numbers,
            List<NumberGuessService.NumberProbability> midRange7Numbers,
            Map<Integer, Integer> top7Frequencies,
            Map<Integer, Integer> midRange7Frequencies,
            long elapsedTime,
            int runsCount) {
        
        try {
            String subject = "JAC Automator Bot....";
            String htmlContent = buildMultipleRunsEmailContent(
                top7Numbers, midRange7Numbers, top7Frequencies, midRange7Frequencies, elapsedTime, runsCount);
            
            sendEmail(subject, htmlContent);
            log.info("{}회 반복 예측 결과 이메일 전송 완료: {}", runsCount, recipientAddress);
            
        } catch (Exception e) {
            log.error("{}회 반복 예측 결과 이메일 전송 실패: {}", runsCount, e.getMessage(), e);
        }
    }

    /**
     * 반복 예측 결과 이메일 본문 생성
     */
    private String buildMultipleRunsEmailContent(
            List<NumberGuessService.NumberProbability> top7Numbers,
            List<NumberGuessService.NumberProbability> midRange7Numbers,
            Map<Integer, Integer> top7Frequencies,
            Map<Integer, Integer> midRange7Frequencies,
            long elapsedTime,
            int runsCount) {
        
        // 시간, 분, 초로 변환
        long hours = elapsedTime / (1000 * 60 * 60);
        long minutes = (elapsedTime % (1000 * 60 * 60)) / (1000 * 60);
        long seconds = (elapsedTime % (1000 * 60)) / 1000;
        long milliseconds = elapsedTime % 1000;
        
        String timeStr;
        if (hours > 0) {
            timeStr = String.format("%d시간 %d분 %d초 (%d밀리초)", hours, minutes, seconds, milliseconds);
        } else if (minutes > 0) {
            timeStr = String.format("%d분 %d초 (%d밀리초)", minutes, seconds, milliseconds);
        } else {
            timeStr = String.format("%d초 (%d밀리초)", seconds, milliseconds);
        }
        
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>");
        html.append("<html>");
        html.append("<head>");
        html.append("<meta charset='UTF-8'>");
        html.append("<style>");
        html.append("body { font-family: Arial, sans-serif; line-height: 1.6; color: #333; }");
        html.append("h1 { color: #2c3e50; }");
        html.append("h2 { color: #2c3e50; border-bottom: 2px solid #3498db; padding-bottom: 10px; }");
        html.append("table { width: 100%; border-collapse: collapse; margin: 20px 0; }");
        html.append("th { background-color: #3498db; color: white; padding: 12px; text-align: left; }");
        html.append("td { padding: 10px; border-bottom: 1px solid #ddd; }");
        html.append("tr:nth-child(even) { background-color: #f2f2f2; }");
        html.append(".top-section { background-color: #e8f5e9; padding: 15px; border-radius: 5px; margin-bottom: 20px; }");
        html.append(".mid-section { background-color: #e3f2fd; padding: 15px; border-radius: 5px; margin-bottom: 20px; }");
        html.append(".time-section { background-color: #fff3e0; padding: 15px; border-radius: 5px; margin-bottom: 20px; }");
        html.append(".probability { font-weight: bold; color: #2e7d32; }");
        html.append(".mid-probability { font-weight: bold; color: #1976d2; }");
        html.append(".frequency { font-weight: bold; color: #d32f2f; }");
        html.append("</style>");
        html.append("</head>");
        html.append("<body>");
        html.append("<h1>").append(runsCount).append("회 반복 예측 분석 결과</h1>");
        html.append("<p>").append(runsCount).append("회 반복 실행 후 등장횟수가 많은 번호들을 분석한 결과입니다.</p>");
        
        // 소요 시간
        html.append("<div class='time-section'>");
        html.append("<h2>⏱️ 총 소요 시간</h2>");
        html.append("<p style='font-size: 18px; font-weight: bold;'>").append(timeStr).append("</p>");
        html.append("</div>");
        
        // 상위 7개 번호
        html.append("<div class='top-section'>");
        html.append("<h2>🎯 최종 상위 7개 번호 (등장횟수 순)</h2>");
        html.append("<table>");
        html.append("<tr><th>순위</th><th>번호</th><th>등장횟수</th><th>확률(%)</th></tr>");
        for (int i = 0; i < top7Numbers.size(); i++) {
            NumberGuessService.NumberProbability np = top7Numbers.get(i);
            String probabilityStr = String.format("%.4f", np.getProbability() * 100);
            int freq = top7Frequencies.getOrDefault(np.getNumber(), 0);
            html.append("<tr>");
            html.append("<td>").append(i + 1).append("위</td>");
            html.append("<td><strong>").append(np.getNumber()).append("</strong></td>");
            html.append("<td><span class='frequency'>").append(freq).append("회</span></td>");
            html.append("<td><span class='probability'>").append(probabilityStr).append("%</span></td>");
            html.append("</tr>");
        }
        html.append("</table>");
        html.append("</div>");
        
        // 39%~42% 범위 7개 번호
        html.append("<div class='mid-section'>");
        html.append("<h2>⚖️ 최종 39%~42% 범위 번호 (등장횟수 순, 7개)</h2>");
        html.append("<table>");
        html.append("<tr><th>순위</th><th>번호</th><th>등장횟수</th><th>확률(%)</th></tr>");
        for (int i = 0; i < midRange7Numbers.size(); i++) {
            NumberGuessService.NumberProbability np = midRange7Numbers.get(i);
            String probabilityStr = String.format("%.4f", np.getProbability() * 100);
            int freq = midRange7Frequencies.getOrDefault(np.getNumber(), 0);
            html.append("<tr>");
            html.append("<td>").append(i + 1).append("위</td>");
            html.append("<td><strong>").append(np.getNumber()).append("</strong></td>");
            html.append("<td><span class='frequency'>").append(freq).append("회</span></td>");
            html.append("<td><span class='mid-probability'>").append(probabilityStr).append("%</span></td>");
            html.append("</tr>");
        }
        html.append("</table>");
        html.append("</div>");
        
        html.append("<hr>");
        html.append("<p style='color: #666; font-size: 12px;'>이 예측은 ").append(runsCount).append("회 반복 실행 후 등장횟수를 분석한 결과이며, 실제 당첨을 보장하지 않습니다.</p>");
        html.append("</body>");
        html.append("</html>");
        
        return html.toString();
    }
}
