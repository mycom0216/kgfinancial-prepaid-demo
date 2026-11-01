# KG파이낸셜 개발 직무 연습: 보안 선불카드와 KG이니시스 결제

사람인·잡코리아의 KG파이낸셜 개발자 공고에서 보이는 Java, Spring Boot, REST API, 관계형 DB, 백오피스와 선불카드 업무를 연습하는 프로젝트예요. 어려운 기능도 초등학생이 읽기 쉽게 한국어 주석으로 설명해요.

> 이 프로젝트는 KG파이낸셜의 실제 서비스가 아니에요. 결제를 사용하려면 본인 명의 KG이니시스 테스트 가맹점과 공개 HTTPS 서버가 필요해요. 이 프로젝트는 결제 정보를 저장하지 않으며, 실제 고객 정보를 넣지 마세요.

## 준비된 기능

- BCrypt 비밀번호 저장, 세션 로그인, CSRF 방어, 일반 사용자와 관리자 권한 분리
- 모바일 앱용 짧은 기간 Bearer 로그인 토큰과 로그아웃 폐기 기능
- 사용자 카드 소유권 확인, PostgreSQL 관계형 DB, 중복 요청 키, 관리자 감사 기록
- KG이니시스 INIpay PRO 카드·실시간 계좌이체 인증 및 서버 승인 흐름
- 이니시스 승인 응답의 가맹점 번호, 주문번호, 금액, 결제수단을 확인한 후에만 잔액 충전
- `POST /api/cards/{카드번호}/charge`도 실제 이니시스 결제 주문을 만들어요. 예전 가짜 잔액 충전 동작은 제거했어요.
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

결제 시작 API는 로그인 후 `POST /api/payments` 또는 `POST /api/cards/{카드번호}/charge`에 다음 JSON과 고유한 `Idempotency-Key` 헤더를 보내요. 충전 주소에서는 `cardId`를 URL에 넣고 JSON에서 빼며, `method`는 생략하면 카드 결제로 선택돼요. 먼저 `GET /api/auth/csrf`에서 CSRF 토큰을 받아 `X-XSRF-TOKEN` 헤더에도 넣어야 해요.

```json
{"cardId":1,"amount":1000,"method":"CARD"}
```

`method`는 `CARD`(신용카드) 또는 `BANK`(실시간 계좌이체)예요. 응답에는 이니시스 결제창 스크립트 주소와 폼에 넣을 결제 파라미터가 와요. 웹 화면은 이 파라미터를 이니시스 공식 JavaScript 결제창에 전달해야 해요. 결제창이 호출되기 전에 로그인된 브라우저 세션과 CSRF를 적용하는 쇼핑 화면을 구성하세요. 인증 뒤 이니시스가 콜백으로 돌아오면 서버가 이니시스 승인 API를 직접 부릅니다. 브라우저가 보낸 “성공” 메시지만으로 잔액을 충전하지 않아요.

주문 상태 확인은 로그인한 사용자가 `GET /api/payments/{orderId}`로 볼 수 있어요. `PAID`가 아니면 충전이 완료된 것이 아니에요. `APPROVAL_UNKNOWN`은 통신이 끊겨 이니시스 가맹점 관리자에서 거래를 확인해야 하는 상태예요. 같은 주문을 다시 승인하지 마세요.

## 내 컴퓨터에서 PostgreSQL과 앱 실행하기 (Windows PowerShell)

필요한 프로그램: Java 17, Maven, Docker Desktop. 저장소에는 Docker Compose 설정이 있어 PostgreSQL 서버와 데이터를 준비해 줘요.

1. 저장소 폴더에서 비밀값 보관용 파일을 만들어요.

```powershell
Copy-Item .env.example .env
```

2. 메모장으로 `.env`를 열고 `DATABASE_PASSWORD`와 `BOOTSTRAP_ADMIN_PASSWORD`의 예시 값을 각각 다른 긴 비밀번호로 바꿔요. 테스트 MID, Hash Key, HTTPS 주소가 없다면 이 값들은 빈 값으로 둘 수 있어요. **실 비밀번호나 키는 GitHub에 올리지 마세요.** `.env`는 Git 제외 목록에 들어 있어요.

3. DB와 앱을 한 번에 실행해요.

```powershell
.\run-local.ps1
```

이 스크립트는 `.env`를 이 PowerShell 창에서만 읽고, Docker 안의 PostgreSQL을 켠 다음 앱을 실행해요. 처음 실행할 때에는 앱이 Flyway로 DB 표를 자동으로 만들어요. 데이터는 Docker의 `kgfinancial-db` 저장 공간에 남기 때문에 앱이나 컴퓨터를 다시 시작해도 보존돼요. 서버를 멈추려면 별도 PowerShell 창에서 `docker compose down`을 실행하세요. 데이터까지 지우려면 `docker compose down -v`를 실행하지만, 저장된 DB 데이터가 모두 삭제돼요.

앱 주소는 `http://localhost:8080`이에요. 로그인 안내가 나오면 앱이 실행된 상태예요. 관리자 로그인은 `.env`의 `BOOTSTRAP_ADMIN_USERNAME`과 `BOOTSTRAP_ADMIN_PASSWORD` 값을 사용해요.

## 모바일 앱에서 API 부르기

Android/iOS 앱은 먼저 `GET /api/auth/csrf`를 불러 CSRF 토큰과 `XSRF-TOKEN` 쿠키를 받아요. 회원가입 `POST /api/mobile/auth/register`와 로그인 `POST /api/mobile/auth/login`에는 쿠키를 보관해 함께 보내고, 토큰을 `X-XSRF-TOKEN` 헤더에 넣어요. 로그인 전 보호를 풀지 않았기 때문이에요. 로그인 답에 들어 있는 `accessToken`은 12시간 동안 쓸 수 있는 열쇠예요. 앱은 열쇠를 Android Keystore 또는 iOS Keychain 같은 안전한 저장소에 보관하고, 화면에 적거나 로그로 출력하지 마세요. 만료되면 다시 로그인하면 돼요.

로그인 뒤 모든 API 요청에 아래 헤더를 붙여요. 웹 쿠키용 CSRF 토큰 없이도 기존 카드·결제 API를 사용할 수 있어요.

```http
Authorization: Bearer 로그인응답의_accessToken
```

예를 들어 카드 목록은 `GET /api/cards`, 충전 주문은 `POST /api/cards/{카드번호}/charge`, 잔액 사용 연습은 `POST /api/cards/{카드번호}/pay`예요. 로그아웃은 `POST /api/mobile/auth/logout`을 부르면 그 토큰을 바로 폐기해요. 이 서버는 앱 전용 HTTPS 주소에 배포해야 해요. 지금 프로젝트에는 특정 회사의 비공개 모빌카드 API 주소나 데이터 형식이 없어서, 여기까지는 일반 Android/iOS 앱과 연결할 수 있는 표준 API예요.

## 운영 배포 전 남은 절차

GitHub 저장소는 private으로 유지해요. 운영 결제는 `INICIS_MODE=production`과 실 MID·키, 가맹점 운영 계약 및 도메인/IP 등록이 모두 필요해요. 지금 값이 없어 테스트 거래도 아직 실제 가맹점으로 실행하지 않았어요. 운영 전에는 KG이니시스가 발급한 승인 응답 샘플로 필드 형식을 확인하고, 접근 제한·속도 제한·비밀 저장소·DB 백업/복구·로그 보존·보안 점검·개인정보 처리 검토가 필요해요. 환불/취소는 별도 INIAPI 키와 정책이 필요하므로 이 예제에서 완료된 기능으로 표시하지 않아요.

## 공식 참고

- [KG이니시스 INIpay PRO 공식 연동 문서](https://manual.inicis.com/inipaypro/)
- [KG이니시스 INIpay PRO 테스트 안내](https://manual.inicis.com/pay/demo/)
