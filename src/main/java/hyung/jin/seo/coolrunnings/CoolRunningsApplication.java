package hyung.jin.seo.coolrunnings;

import hyung.jin.seo.coolrunnings.config.AppConfig;
import hyung.jin.seo.coolrunnings.repository.LotteryResultRepository;
import hyung.jin.seo.coolrunnings.service.AdvancedPredictionService;
import hyung.jin.seo.coolrunnings.service.DailyPipeline;
import hyung.jin.seo.coolrunnings.service.EmailService;
import hyung.jin.seo.coolrunnings.service.LotteryCrawlerService;
import hyung.jin.seo.coolrunnings.service.MachineLearningService;
import hyung.jin.seo.coolrunnings.service.NumberGuessService;
import hyung.jin.seo.coolrunnings.service.OverfittingValidationService;
import hyung.jin.seo.coolrunnings.service.TimeSeriesDeepLearningPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Standalone entry point. Runs the daily pipeline once and exits.
 */
public class CoolRunningsApplication {

    private static final Logger log = LoggerFactory.getLogger(CoolRunningsApplication.class);

    public static void main(String[] args) {
        log.info("cool-runnings starting (standalone once-run)");
        AppConfig config = AppConfig.load();
        validateConfig(config);

        LotteryResultRepository repository = new LotteryResultRepository(
                config.datasourceUrl(),
                config.datasourceUsername(),
                config.datasourcePassword());

        MachineLearningService machineLearningService = new MachineLearningService(repository);
        AdvancedPredictionService advancedPredictionService =
                new AdvancedPredictionService(repository, machineLearningService);
        OverfittingValidationService overfittingValidationService =
                new OverfittingValidationService(machineLearningService);
        TimeSeriesDeepLearningPipeline timeSeriesDeepLearningPipeline =
                new TimeSeriesDeepLearningPipeline();

        EmailService emailService = new EmailService(
                config.mailHost(),
                config.mailPort(),
                config.mailUsername(),
                config.mailPassword(),
                config.emailSendTo());

        NumberGuessService numberGuessService = new NumberGuessService(
                repository,
                emailService,
                machineLearningService,
                advancedPredictionService,
                overfittingValidationService,
                timeSeriesDeepLearningPipeline);
        advancedPredictionService.setNumberGuessService(numberGuessService);

        LotteryCrawlerService lotteryCrawlerService = new LotteryCrawlerService(
                repository,
                numberGuessService,
                config.lotteryCrawlerUrl());

        DailyPipeline pipeline = new DailyPipeline(
                lotteryCrawlerService,
                numberGuessService,
                emailService,
                config.lotteryCrawlerEnabled());

        pipeline.runOnce();
        log.info("cool-runnings finished");
    }

    private static void validateConfig(AppConfig config) {
        requireNonBlank(config.datasourceUrl(), "spring.datasource.url");
        requireNonBlank(config.datasourceUsername(), "spring.datasource.username");
        requireNonBlank(config.datasourcePassword(), "spring.datasource.password");
        requireNonBlank(config.mailUsername(), "spring.mail.username");
        requireNonBlank(config.mailPassword(), "spring.mail.password");
        requireNonBlank(config.emailSendTo(), "email.send.to");

        String url = config.datasourceUrl();
        if (url.contains("YOUR_PROJECT_REF") || url.contains("YOUR_DB_PASSWORD")) {
            throw new IllegalStateException(
                    "Datasource still uses placeholder values. Set SPRING_DATASOURCE_* secrets/env vars.");
        }
        if (config.mailUsername().contains("YOUR_GMAIL")
                || config.emailSendTo().contains("YOUR_RECIPIENT")) {
            throw new IllegalStateException(
                    "Mail still uses placeholder values. Set SPRING_MAIL_* and EMAIL_SEND_TO secrets/env vars.");
        }
        // GitHub Actions (and many CI runners) are IPv4-only; direct db.*.supabase.co is IPv6-only.
        if (url.contains("db.") && url.contains(".supabase.co") && !url.contains("pooler.supabase.com")) {
            log.warn(
                    "Using direct Supabase host (often IPv6-only). "
                            + "If CI fails with Network is unreachable, switch SPRING_DATASOURCE_URL "
                            + "to the Session pooler (aws-0-<region>.pooler.supabase.com:5432) "
                            + "and username postgres.<project-ref>.");
        }
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
    }
}
