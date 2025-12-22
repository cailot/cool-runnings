# Cool Runnings Spring Boot Application

Spring Boot 기반 웹 애플리케이션입니다.

## 요구사항

- Java 17 이상
- Maven 3.6 이상

## 실행 방법

### Maven을 사용한 실행

```bash
mvn spring-boot:run
```

### JAR 파일로 빌드 및 실행

```bash
mvn clean package
java -jar target/cool-runnings-1.jar
```

## API 엔드포인트

- `GET /api/hello` - 기본 Hello World 엔드포인트

## 프로젝트 구조

```
cool-runnings/
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── hyung/jin/seo/coolrunnings/
│   │   │       ├── CoolRunningsApplication.java
│   │   │       └── controller/
│   │   │           └── HelloController.java
│   │   └── resources/
│   │       └── application.properties
│   └── test/
│       └── java/
│           └── hyung/jin/seo/coolrunnings/
│               └── CoolRunningsApplicationTests.java
├── pom.xml
└── README.md
```

## 개발

애플리케이션은 기본적으로 `http://localhost:8080`에서 실행됩니다.

### 주요 의존성

- Spring Boot Web Starter
- Spring Boot Test Starter
- Spring Boot DevTools (개발 시 자동 재시작)
- Lombok (코드 간소화)

