package hyung.jin.seo.coolrunnings.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.function.Function;

/**
 * Configuration loader.
 *
 * <p>Local: {@code application.properties} (copy from example).
 * CI: non-blank environment variables override the properties file.
 */
public final class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    private final Properties properties;
    private final Function<String, String> envLookup;

    private AppConfig(Properties properties, Function<String, String> envLookup) {
        this.properties = properties;
        this.envLookup = envLookup == null ? System::getenv : envLookup;
    }

    public static AppConfig load() {
        return load(System::getenv);
    }

    static AppConfig load(Function<String, String> envLookup) {
        Properties props = new Properties();
        loadClasspathProperties(props, "application.properties", true);
        if (props.isEmpty()) {
            log.info("application.properties missing; falling back to application.properties.example");
            loadClasspathProperties(props, "application.properties.example", false);
        }
        return new AppConfig(props, envLookup);
    }

    private static void loadClasspathProperties(Properties props, String resource, boolean optional) {
        try (InputStream in = AppConfig.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                if (!optional) {
                    throw new IllegalStateException(resource + " not found on classpath");
                }
                return;
            }
            props.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load " + resource, e);
        }
    }

    public String datasourceUrl() {
        return get("spring.datasource.url", "");
    }

    public String datasourceUsername() {
        return get("spring.datasource.username", "postgres");
    }

    public String datasourcePassword() {
        return get("spring.datasource.password", "");
    }

    public String mailHost() {
        return get("spring.mail.host", "smtp.gmail.com");
    }

    public int mailPort() {
        return Integer.parseInt(get("spring.mail.port", "587"));
    }

    public String mailUsername() {
        return get("spring.mail.username", "");
    }

    public String mailPassword() {
        return get("spring.mail.password", "");
    }

    public String emailSendTo() {
        return get("email.send.to", "");
    }

    public String lotteryCrawlerUrl() {
        return get("lottery.crawler.url", "https://en.lottolyzer.com/history/australia/set-for-life");
    }

    public boolean lotteryCrawlerEnabled() {
        return Boolean.parseBoolean(get("lottery.crawler.enabled", "true"));
    }

    public String get(String key, String defaultValue) {
        for (String envKey : envKeysFor(key)) {
            String fromEnv = envLookup.apply(envKey);
            if (fromEnv != null && !fromEnv.isBlank()) {
                return fromEnv;
            }
        }
        return properties.getProperty(key, defaultValue);
    }

    static List<String> envKeysFor(String propertyKey) {
        String normalized = propertyKey.replace('.', '_').replace('-', '_').toUpperCase(Locale.ROOT);
        List<String> keys = new ArrayList<>(2);
        keys.add(normalized);
        keys.add("COOL_RUNNINGS_" + normalized);
        return keys;
    }
}
