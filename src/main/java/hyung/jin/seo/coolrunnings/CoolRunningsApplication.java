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

        if (config.datasourceUrl().isBlank()) {
            throw new IllegalStateException("spring.datasource.url is required");
        }

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
}
