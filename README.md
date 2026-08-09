# Cool Runnings

Australia Set for Life 복권 데이터를 크롤링·분석하고 일일 예측 결과를 이메일로 보내는 **standalone Java** 애플리케이션입니다.

## 요구사항

- Java 17+
- Maven 3.6+
- Supabase Postgres (`public.archive_entry`)

## 빠른 시작

1. 설정 파일 준비

```bash
cp src/main/resources/application.properties.example \
   src/main/resources/application.properties
```

2. DB/메일 값을 채운 뒤 실행

```bash
./run.sh
# 또는
mvn -q compile exec:java -Dexec.cleanupDaemonThreads=false
```

1회 실행(크롤링 → 예측 → 이메일) 후 프로세스가 종료됩니다.

### JAR

```bash
mvn -q package
java -jar target/cool-runnings.jar
```

## GitHub Actions

워크플로: [`.github/workflows/daily-lottery-report.yml`](.github/workflows/daily-lottery-report.yml)

- 스케줄: 매일 **08:00 Australia/Sydney** (`0 22 * * *` UTC, AEST 기준 / AEDT≈09:00)
- 수동 실행: Actions → **Daily lottery report** → **Run workflow**

### Secrets

| Secret | 용도 |
| --- | --- |
| `SPRING_DATASOURCE_URL` | Supabase JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | DB 사용자 (`postgres`) |
| `SPRING_DATASOURCE_PASSWORD` | DB 비밀번호 |
| `SPRING_MAIL_USERNAME` | Gmail 계정 |
| `SPRING_MAIL_PASSWORD` | Gmail 앱 비밀번호 |
| `EMAIL_SEND_TO` | 리포트 수신 메일 |

## 파이프라인

1. Lottolyzer 크롤링 → Supabase 저장
2. 딥러닝/앙상블 기반 번호 예측 (1500회 반복 포함)
3. Gmail SMTP로 결과 전송
4. 프로세스 종료

## 프로젝트 구조

```
src/main/java/hyung/jin/seo/coolrunnings/
  CoolRunningsApplication.java   # entry
  config/AppConfig.java
  model/LotteryResult.java
  repository/LotteryResultRepository.java  # JDBC
  service/DailyPipeline.java
  service/...                      # crawler / prediction / email
.github/workflows/daily-lottery-report.yml
run.sh
```
