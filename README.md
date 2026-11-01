# KG파이낸셜 개발 직무 연습: 보안 선불카드와 KG이니시스 결제

사람인·잡코리아의 KG파이낸셜 개발자 공고에서 보이는 Java, Spring Boot, REST API, 관계형 DB, 백오피스와 선불카드 업무를 연습하는 프로젝트예요. 어려운 기능도 초등학생이 읽기 쉽게 한국어 주석으로 설명해요.

> 이 프로젝트는 KG파이낸셜의 실제 서비스가 아니에요. 결제를 사용하려면 본인 명의 KG이니시스 테스트 가맹점과 공개 HTTPS 서버가 필요해요. 이 프로젝트는 결제 정보를 저장하지 않으며, 실제 고객 정보를 넣지 마세요.

## 준비된 기능

- BCrypt 비밀번호 저장, 세션 로그인, CSRF 방어, 일반 사용자와 관리자 권한 분리
- 사용자 카드 소유권 확인, PostgreSQL 관계형 DB, 중복 요청 키, 관리자 감사 기록
- KG이니시스 INIpay PRO 카드·실시간 계좌이체 인증 및 서버 승인 흐름
- 이니시스 승인 응답의 가맹점 번호, 주문번호, 금액, 결제수단을 확인한 후에만 잔액 충전
- 응답이 끊겨 승인 여부를 모르면 `APPROVAL_UNKNOWN`으로 남겨 다시 결제해 이중 충전하는 일을 막음
- GitHub Actions에서 Maven 빌드와 취약점 스캔

## 이니시스 연결 준비

1. KG이니시스와 가맹점 계약을 맺고, 카드·실시간 계좌이체가 켜진 **테스트 MID와 모바일 금액위변조 Hash Key**를 발급받아요. MID와 키는 이니시스 가맹점 관리자에서 발급받는 값이라 이 저장소나 채팅에 적지 마세요.
2. 앱을 인터넷에서 접속할 수 있는 HTTPS 주소로 배포해요. 로컬 `localhost`는 결제 결과 주소로 쓸 수 없어요. 이 주소가 콜백 주소의 앞부분이에요. 전체 콜백 주소는 `https://내-도메인/api/payments/inicis/next`예요.
3. `.env.example`을 `.env`로 복사해 값들을 채워요. `.env`는 Git에 올라가지 않아요.

```dotenv
INICIS_MODE=staging
INICIS_MID=발급받은_테스트_MID
INICIS_HASH_KEY=발급받은_테스트_Hash_Key
APP_PUBLIC_URL=https://내-HTTPS-도메인
```

결제 시작 API는 로그인 후 `POST /api/payments`에 다음 JSON과 고유한 `Idempotency-Key` 헤더를 보내요. 먼저 `GET /api/auth/csrf`에서 CSRF 토큰을 받아 `X-XSRF-TOKEN` 헤더에도 넣어야 해요.

```json
{"cardId":1,"amount":1000,"method":"CARD"}
```

`method`는 `CARD`(신용카드) 또는 `BANK`(실시간 계좌이체)예요. 응답에는 이니시스 결제창 스크립트 주소와 폼에 넣을 결제 파라미터가 와요. 웹 화면은 이 파라미터를 이니시스 공식 JavaScript 결제창에 전달해야 해요. 결제창이 호출되기 전에 로그인된 브라우저 세션과 CSRF를 적용하는 쇼핑 화면을 구성하세요. 인증 뒤 이니시스가 콜백으로 돌아오면 서버가 이니시스 승인 API를 직접 부릅니다. 브라우저가 보낸 “성공” 메시지만으로 잔액을 충전하지 않아요.

주문 상태 확인은 로그인한 사용자가 `GET /api/payments/{orderId}`로 볼 수 있어요. `PAID`가 아니면 충전이 완료된 것이 아니에요. `APPROVAL_UNKNOWN`은 통신이 끊겨 이니시스 가맹점 관리자에서 거래를 확인해야 하는 상태예요. 같은 주문을 다시 승인하지 마세요.

## PostgreSQL 실행

1. `.env.example`을 `.env`로 복사하고 DB 관리자 비밀번호, 앱 관리자 비밀번호, 이니시스 테스트 정보를 설정해요.
2. `docker compose --env-file .env up -d`로 PostgreSQL을 켜요.
3. 터미널에서 `SPRING_PROFILES_ACTIVE=postgres`와 `.env`의 값들을 환경 변수로 불러온 뒤 `mvn spring-boot:run`을 실행해요. PowerShell에서는 `$env:SPRING_PROFILES_ACTIVE='postgres'`처럼 설정할 수 있어요.

## 운영 배포 전 남은 절차

GitHub 저장소는 private으로 유지해요. 운영 결제는 `INICIS_MODE=production`과 실 MID·키, 가맹점 운영 계약 및 도메인/IP 등록이 모두 필요해요. 지금 값이 없어 테스트 거래도 아직 실제 가맹점으로 실행하지 않았어요. 운영 전에는 KG이니시스가 발급한 승인 응답 샘플로 필드 형식을 확인하고, 접근 제한·속도 제한·비밀 저장소·DB 백업/복구·로그 보존·보안 점검·개인정보 처리 검토가 필요해요. 환불/취소는 별도 INIAPI 키와 정책이 필요하므로 이 예제에서 완료된 기능으로 표시하지 않아요.

## 공식 참고

- [KG이니시스 INIpay PRO 공식 연동 문서](https://manual.inicis.com/inipaypro/)
- [KG이니시스 INIpay PRO 테스트 안내](https://manual.inicis.com/pay/demo/)

