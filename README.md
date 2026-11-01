# KG파이낸셜 개발 직무 연습: 안전한 선불카드 서버

사람인·잡코리아의 KG파이낸셜 개발자 공고에 나온 Java, Spring Boot, REST API, 관계형 DB, 백오피스와 선불카드 업무 키워드를 연습하는 프로젝트예요. 주석과 안내를 쉬운 한국어로 적었습니다.

> 이 코드는 KG파이낸셜의 실제 시스템이 아니고, 실제 돈이나 카드망과 연결되지 않습니다. 실제 고객의 개인정보를 넣지 마세요.

## 들어 있는 기능

- 회원가입과 로그인: 비밀번호 원문 대신 BCrypt 해시를 저장하고, 세션 쿠키를 HttpOnly로 관리해요.
- 사용자와 관리자 권한 분리: 가입하면 일반 사용자만 돼요. 관리자 계정은 개발자가 환경 변수로 따로 만들어요.
- 내 카드만 보기: 다른 사람 카드 번호를 알아도 내용을 볼 수 없어요.
- 개인정보 최소 수집: 로그인 아이디만 저장하고 이름, 주소, 전화번호, 생년월일, 실제 카드 번호는 받지 않아요. 아이디에도 실명이나 이메일 대신 별명을 사용해요.
- 중복 요청 방지: 충전/결제 때 `Idempotency-Key`를 받으며 같은 키와 같은 요청은 한 번만 처리해요.
- 감사 기록: 가입, 카드 만들기, 충전/결제와 관리자 조회를 기록해요. 비밀번호나 요청 본문은 기록하지 않아요.
- 관계형 DB: 간단히 시작할 때는 H2, PostgreSQL 프로필로 실행할 때는 PostgreSQL을 사용해요.
- 자동 보안 점검: GitHub Actions에서 Java 의존성 취약점 검사와 Maven 빌드를 실행하도록 설정했어요.

## 빠르게 시작하기 (H2 연습 DB)

Java 17 이상과 Maven을 설치하고 프로젝트 폴더에서 실행해요.

```bash
mvn spring-boot:run
```

이 모드는 메모리 DB를 써서 서버를 끄면 자료가 사라져요. H2 화면은 `http://localhost:8080/h2-console`이고 JDBC URL은 `jdbc:h2:mem:kgdemo`, 아이디는 `sa`, 비밀번호는 비워 둡니다.

## PostgreSQL로 실행하기

1. `.env.example`을 `.env`로 복사하고 두 비밀번호를 충분히 긴 임의의 값으로 바꿔요. `.env`는 Git에 올리면 안 됩니다.
2. 데이터베이스를 켜요.

```bash
docker compose --env-file .env up -d
```

3. 앱을 PostgreSQL 프로필로 켜요. Bash 예시입니다.

```bash
export SPRING_PROFILES_ACTIVE=postgres
export DATABASE_PASSWORD='위에서 정한 DB 비밀번호'
export BOOTSTRAP_ADMIN_USERNAME='원하는 관리자 아이디'
export BOOTSTRAP_ADMIN_PASSWORD='관리자용 긴 비밀번호 16자 이상'
mvn spring-boot:run
```

앱이 DB에 연결하려면 `DATABASE_PASSWORD`가 Compose에 넣은 DB 비밀번호와 같아야 해요. 관리자 비밀번호는 코드나 GitHub에 넣지 말고 각자 터미널 환경 변수로만 넣습니다. 관리자 계정은 두 환경 변수를 모두 설정하고 서버를 시작할 때 한 번 생성돼요. 비밀번호가 16자보다 짧으면 관리자 계정을 만들지 않아요.

## 로그인과 CSRF 보호 사용법

브라우저 기반 세션 로그인과 CSRF 토큰을 함께 사용해요. 상태를 바꾸는 요청(가입, 로그인, 로그아웃, 카드 만들기, 충전, 결제)은 먼저 `GET /api/auth/csrf`에서 토큰을 받아 `X-XSRF-TOKEN` 헤더로 보내야 해요. 브라우저는 쿠키도 함께 보냅니다. 로그인은 `application/x-www-form-urlencoded` 형식으로 아이디와 비밀번호를 `/api/auth/login`에 보내고, 로그인 후 CSRF 토큰을 다시 받아요.

회원가입 JSON 예시:

```json
{"username":"minsu01","password":"replace-with-a-long-password"}
```

로그인한 뒤 아래 주소를 사용할 수 있어요.

- `GET /api/auth/me` — 내 아이디와 역할
- `POST /api/cards` — 빈 연습 카드 만들기
- `GET /api/cards` — 내 카드만 보기
- `POST /api/cards/{카드번호}/charge` — 가짜 금액 충전
- `POST /api/cards/{카드번호}/pay` — 가짜 결제
- `GET /api/cards/{카드번호}/transactions` — 내 거래 기록 보기
- `GET /api/admin/cards` — 관리자만 전체 카드 보기
- `GET /api/admin/audit-logs` — 관리자만 최근 감사 기록 보기

충전·결제 요청에는 예를 들어 `Idempotency-Key: charge-2026-11-01-001` 같은 고유한 헤더를 넣어야 해요. 요청이 실패하지 않았는지 애매해 다시 보낼 때 같은 키를 사용하면 잔액이 두 번 바뀌지 않아요. 같은 키를 다른 카드, 금액, 행동에 재사용하면 거절해요.

## 운영 전에 꼭 더 해야 하는 일

이것은 보안 기능을 배우는 개발용 예제이지, 운영 준비가 끝난 금융 시스템은 아니에요. 운영 배포 전에는 HTTPS와 비밀키 관리, DB 암호화·백업·접근 통제, 로그인 시도 제한, 세션 만료, 감사 기록의 별도 보관과 보존 기간, 장애 복구, 규정·개인정보 처리 검토와 전문가 보안 점검이 필요해요. 감사 기록은 앱에서 추가만 하지만 DB 관리자가 바꾸거나 지울 수 있으므로 변조 방지 저장소는 아직 없어요.

## 실제 결제 연결

PG사를 아직 지정하지 않아 실제 결제 API나 결제 키는 연결하지 않았어요. `/pay`는 데이터베이스 잔액을 줄이는 가짜 결제입니다. PG사의 테스트 가맹점, 테스트 API 문서와 서버 측 비밀키가 준비되면 테스트 결제 기능을 이어 붙일 수 있어요. 비밀키를 채팅이나 GitHub에 보내지 말고 환경 변수/비밀 저장소에서 관리해야 합니다.


