# cool-runnings

호주 Set for Life 일일 배치: 크롤링 → 예측 → 이메일 → 종료.
이메일 최종 번호는 **1500회 반복 합의** 결과(`predictWithMultipleRuns`)를 사용한다. 단일 패스 top7은 참고 로그용이다.

## 런타임

- Java 17 + Maven
- **Spring Boot 아님** (standalone 1회 실행 앱)
- 진입점: `CoolRunningsApplication` → `DailyPipeline`

## 데이터

- Supabase Postgres 프로젝트 `cool-running` (`gdzaaspzwaaqtihvquel`)
- 주요 테이블: `public.archive_entry` (JDBC)
- DB/스키마/데이터 작업: Supabase MCP 사용 — `.cursor/rules/supabase-mcp.mdc` 참고

## CI

- 워크플로: `.github/workflows/daily-lottery-report.yml`
- 스케줄: 매일 08:00 Australia/Sydney (`0 22 * * *` UTC)
- GitHub Actions는 Supabase **Session pooler**(IPv4) 필요. 직접 `db.*.supabase.co` 연결은 `Network is unreachable`로 실패함

## 하드 룰

- 실제 자격 증명이 담긴 `application.properties`를 커밋하지 말 것
- 크롤링 / 예측 / 이메일 실패 시 프로세스가 실패해야 함 (Actions용 non-zero exit)
- 사용자가 명시적으로 요청하지 않는 한 Spring Boot / JPA / WAR를 다시 도입하지 말 것
