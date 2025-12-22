package hyung.jin.seo.coolrunnings.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;

/**
 * Spring Security 설정
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig extends WebSecurityConfigurerAdapter {

    @Override
    protected void configure(HttpSecurity http) throws Exception {
        http
            .csrf().disable() // CSRF 비활성화 (API의 경우)
            .authorizeRequests()
                .antMatchers("/api/**").permitAll() // /api/** 경로는 인증 없이 접근 가능
                .anyRequest().authenticated() // 나머지 요청은 인증 필요
            .and()
            .httpBasic().disable(); // HTTP Basic 인증 비활성화
    }
}
