---
name: change-email-copy
description: >-
  cool-runnings 예측 결과 이메일의 제목·본문 문구를 안전하게 변경한다.
  사용자가 이메일 제목, subject, 메일 문구, 헤더, EmailService 카피 변경을
  요청할 때 사용한다.
---

# 이메일 문구 변경

## 대상 파일

오직 이 파일만 수정한다.

- `src/main/java/hyung/jin/seo/coolrunnings/service/EmailService.java`

SMTP 설정·수신자·Secrets·`DailyPipeline`·예측 로직은 건드리지 않는다.

## 문구 위치

### 일일 합의 메일 (`sendMultipleRunsPredictionResults`) — Actions가 보내는 메인 메일

| 위치 | 코드 |
| --- | --- |
| MIME subject | `String subject = "...";` in `sendMultipleRunsPredictionResults` |
| 본문 헤더 | `html.append("<h1>...</h1>");` in `buildMultipleRunsEmailContent` |
| 도입 문장 | `N회 반복 합의 예측 결과입니다...` |
| 섹션 제목 | `최종 상위 7개 번호 (합의)` / `최종 39%~42% 범위 번호` |

### 레거시 단일 패스 메일 (`sendNumberPredictionResults`) — 현재 파이프라인 미사용

| 위치 | 코드 |
| --- | --- |
| MIME subject | `String subject = "...";` |
| 본문 헤더 | `html.append("<h1>...</h1>");` in `buildEmailContent` |

사용자가 “이메일 제목”만 말하면 **합의 메일**의 subject와 `<h1>`을 함께 맞춘다.  
레거시 단일 패스 메일까지 바꿀지는 사용자에게 한 줄로 확인한다.

## 작업 절차

1. `EmailService.java`에서 해당 문자열만 정확히 교체한다.
2. HTML 태그·CSS·테이블·확률 포맷(`%.2f`)·섹션 구조는 유지한다.
3. 사용자가 준 문구는 **그대로**(철자·공백·느낌표 포함) 넣는다.
4. 시크릿/수신자/`application.properties`는 수정하지 않는다.
5. 변경 후 사용자에게 요약한다: 어떤 subject / h1이 바뀌었는지, 다음 Actions(또는 로컬 실행)부터 반영된다는 점.

## 하지 말 것

- 이메일 HTML 레이아웃을 새로 디자인하지 말 것
- 예측 알고리즘·크롤러·워크플로 YAML을 같이 바꾸지 말 것
- 커밋/푸시는 사용자가 요청할 때만
