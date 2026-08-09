package hyung.jin.seo.coolrunnings.service;

import lombok.extern.slf4j.Slf4j;

import javax.mail.Authenticator;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 이메일 전송 서비스 (Gmail SMTP 사용)
 */
@Slf4j
public class EmailService {

    private final Session mailSession;
    private final String senderAddress;
    private final String recipientAddress;

    public EmailService(
            String mailHost,
            int mailPort,
            String mailUsername,
            String mailPassword,
            String recipientAddress) {
        this.senderAddress = mailUsername;
        this.recipientAddress = recipientAddress;

        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.host", mailHost);
        props.put("mail.smtp.port", String.valueOf(mailPort));

        this.mailSession = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(mailUsername, mailPassword);
            }
        });
    }

    public void sendNumberPredictionResults(
            List<NumberGuessService.NumberProbability> top7Numbers,
            List<NumberGuessService.NumberProbability> bottom7Numbers,
            List<NumberGuessService.NumberProbability> mid7Numbers,
            long elapsedTime) {

        String subject = "JAC Automator Test Bot...";
        String htmlContent = buildEmailContent(top7Numbers, bottom7Numbers, mid7Numbers, elapsedTime);
        try {
            sendEmail(subject, htmlContent);
            log.info("번호 예측 결과 이메일 전송 완료: {}", recipientAddress);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "번호 예측 결과 이메일 전송 실패 (" + recipientAddress + "): " + e.getMessage(), e);
        }
    }

    private String buildEmailContent(
            List<NumberGuessService.NumberProbability> top7Numbers,
            List<NumberGuessService.NumberProbability> bottom7Numbers,
            List<NumberGuessService.NumberProbability> mid7Numbers,
            long elapsedTime) {

        long hours = elapsedTime / (1000 * 60 * 60);
        long minutes = (elapsedTime % (1000 * 60 * 60)) / (1000 * 60);
        long seconds = (elapsedTime % (1000 * 60)) / 1000;

        String timeStr;
        if (hours > 0) {
            timeStr = String.format("%d시간 %d분 %d초", hours, minutes, seconds);
        } else if (minutes > 0) {
            timeStr = String.format("%d분 %d초", minutes, seconds);
        } else {
            timeStr = String.format("%d초", seconds);
        }

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset='UTF-8'><style>");
        html.append("body { font-family: Arial, sans-serif; line-height: 1.6; color: #333; }");
        html.append("h2 { color: #2c3e50; border-bottom: 2px solid #3498db; padding-bottom: 10px; }");
        html.append("table { width: 100%; border-collapse: collapse; margin: 20px 0; }");
        html.append("th { background-color: #3498db; color: white; padding: 12px; text-align: left; }");
        html.append("td { padding: 10px; border-bottom: 1px solid #ddd; }");
        html.append("tr:nth-child(even) { background-color: #f2f2f2; }");
        html.append(".top-section { background-color: #e8f5e9; padding: 15px; border-radius: 5px; margin-bottom: 20px; }");
        html.append(".mid-section { background-color: #e3f2fd; padding: 15px; border-radius: 5px; margin-bottom: 20px; }");
        html.append(".bottom-section { background-color: #fff3e0; padding: 15px; border-radius: 5px; margin-bottom: 20px; }");
        html.append(".time-section { background-color: #f3e5f5; padding: 15px; border-radius: 5px; margin-bottom: 20px; }");
        html.append(".probability { font-weight: bold; color: #2e7d32; }");
        html.append(".mid-probability { font-weight: bold; color: #1976d2; }");
        html.append(".low-probability { font-weight: bold; color: #d32f2f; }");
        html.append("</style></head><body>");
        html.append("<h1>Let's roll the dice for.......Set for Life</h1>");
        html.append("<p>다음 회차에 나올 가능성이 높은/낮은 번호를 분석한 결과입니다.</p>");

        html.append("<div class='time-section'><h2>총 소요 시간</h2>");
        html.append("<p style='font-size: 18px; font-weight: bold;'>").append(timeStr).append("</p></div>");

        html.append("<div class='top-section'><h2>추천 번호 (확률 높은 상위 7개)</h2><table>");
        html.append("<tr><th>순위</th><th>번호</th><th>확률</th></tr>");
        for (int i = 0; i < top7Numbers.size(); i++) {
            NumberGuessService.NumberProbability np = top7Numbers.get(i);
            html.append("<tr><td>").append(i + 1).append("위</td><td><strong>")
                    .append(np.getNumber()).append("</strong></td><td><span class='probability'>")
                    .append(String.format("%.2f", np.getProbability() * 100)).append("%</span></td></tr>");
        }
        html.append("</table></div>");

        html.append("<div class='mid-section'><h2>중간 확률 번호 (High7 + Low7 혼합)</h2><table>");
        html.append("<tr><th>순위</th><th>번호</th><th>확률</th></tr>");
        for (int i = 0; i < mid7Numbers.size(); i++) {
            NumberGuessService.NumberProbability np = mid7Numbers.get(i);
            html.append("<tr><td>").append(i + 1).append("위</td><td><strong>")
                    .append(np.getNumber()).append("</strong></td><td><span class='mid-probability'>")
                    .append(String.format("%.2f", np.getProbability() * 100)).append("%</span></td></tr>");
        }
        html.append("</table></div>");

        html.append("<div class='bottom-section'><h2>비추천 번호 (확률 낮은 하위 7개)</h2><table>");
        html.append("<tr><th>순위</th><th>번호</th><th>확률</th></tr>");
        for (int i = 0; i < bottom7Numbers.size(); i++) {
            NumberGuessService.NumberProbability np = bottom7Numbers.get(i);
            html.append("<tr><td>").append(i + 1).append("위</td><td><strong>")
                    .append(np.getNumber()).append("</strong></td><td><span class='low-probability'>")
                    .append(String.format("%.2f", np.getProbability() * 100)).append("%</span></td></tr>");
        }
        html.append("</table></div>");

        html.append("<hr><p style='color: #666; font-size: 12px;'>이 예측은 과거 데이터를 기반으로 한 통계적 분석이며, 실제 당첨을 보장하지 않습니다.</p>");
        html.append("</body></html>");
        return html.toString();
    }

    private void sendEmail(String subject, String htmlContent) throws Exception {
        if (senderAddress == null || recipientAddress == null || subject == null || htmlContent == null) {
            throw new IllegalArgumentException("이메일 전송에 필요한 정보가 누락되었습니다.");
        }
        try {
            MimeMessage message = new MimeMessage(mailSession);
            message.setFrom(new InternetAddress(senderAddress));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipientAddress));
            message.setSubject(subject, "UTF-8");
            message.setContent(htmlContent, "text/html; charset=UTF-8");
            Transport.send(message);
            log.info("이메일 전송 성공: {} -> {}", senderAddress, recipientAddress);
        } catch (MessagingException e) {
            log.error("Gmail SMTP 이메일 전송 실패: {}", e.getMessage(), e);
            throw new Exception("이메일 전송 실패: " + e.getMessage(), e);
        }
    }

    public void sendMultipleRunsPredictionResults(
            List<NumberGuessService.NumberProbability> top7Numbers,
            List<NumberGuessService.NumberProbability> midRange7Numbers,
            Map<Integer, Integer> top7Frequencies,
            Map<Integer, Integer> midRange7Frequencies,
            long elapsedTime,
            int runsCount) {

        String subject = "JAC Automator Bot....";
        String htmlContent = buildMultipleRunsEmailContent(
                top7Numbers, midRange7Numbers, top7Frequencies, midRange7Frequencies, elapsedTime, runsCount);
        try {
            sendEmail(subject, htmlContent);
            log.info("{}회 반복 예측 결과 이메일 전송 완료: {}", runsCount, recipientAddress);
        } catch (Exception e) {
            throw new IllegalStateException(
                    runsCount + "회 반복 예측 결과 이메일 전송 실패 (" + recipientAddress + "): " + e.getMessage(),
                    e);
        }
    }

    private String buildMultipleRunsEmailContent(
            List<NumberGuessService.NumberProbability> top7Numbers,
            List<NumberGuessService.NumberProbability> midRange7Numbers,
            Map<Integer, Integer> top7Frequencies,
            Map<Integer, Integer> midRange7Frequencies,
            long elapsedTime,
            int runsCount) {

        long hours = elapsedTime / (1000 * 60 * 60);
        long minutes = (elapsedTime % (1000 * 60 * 60)) / (1000 * 60);
        long seconds = (elapsedTime % (1000 * 60)) / 1000;

        String timeStr;
        if (hours > 0) {
            timeStr = String.format("%d시간 %d분 %d초", hours, minutes, seconds);
        } else if (minutes > 0) {
            timeStr = String.format("%d분 %d초", minutes, seconds);
        } else {
            timeStr = String.format("%d초", seconds);
        }

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset='UTF-8'><style>");
        html.append("body { font-family: Arial, sans-serif; line-height: 1.6; color: #333; }");
        html.append("table { width: 100%; border-collapse: collapse; margin: 20px 0; }");
        html.append("th { background-color: #3498db; color: white; padding: 12px; text-align: left; }");
        html.append("td { padding: 10px; border-bottom: 1px solid #ddd; }");
        html.append("</style></head><body>");
        html.append("<h1>").append(runsCount).append("회 반복 예측 분석 결과</h1>");
        html.append("<p>총 소요 시간: <strong>").append(timeStr).append("</strong></p>");

        html.append("<h2>최종 상위 7개 번호</h2><table><tr><th>순위</th><th>번호</th><th>등장횟수</th><th>확률(%)</th></tr>");
        for (int i = 0; i < top7Numbers.size(); i++) {
            NumberGuessService.NumberProbability np = top7Numbers.get(i);
            html.append("<tr><td>").append(i + 1).append("</td><td>").append(np.getNumber())
                    .append("</td><td>").append(top7Frequencies.getOrDefault(np.getNumber(), 0))
                    .append("</td><td>").append(String.format("%.4f", np.getProbability() * 100))
                    .append("</td></tr>");
        }
        html.append("</table>");

        html.append("<h2>최종 39%~42% 범위 번호</h2><table><tr><th>순위</th><th>번호</th><th>등장횟수</th><th>확률(%)</th></tr>");
        for (int i = 0; i < midRange7Numbers.size(); i++) {
            NumberGuessService.NumberProbability np = midRange7Numbers.get(i);
            html.append("<tr><td>").append(i + 1).append("</td><td>").append(np.getNumber())
                    .append("</td><td>").append(midRange7Frequencies.getOrDefault(np.getNumber(), 0))
                    .append("</td><td>").append(String.format("%.4f", np.getProbability() * 100))
                    .append("</td></tr>");
        }
        html.append("</table></body></html>");
        return html.toString();
    }
}
