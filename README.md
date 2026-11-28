# 선불카드 서비스

선불카드 발급, 잔액, 거래 내역, 관리자 감사 기록을 관리하는 독립 프로젝트예요. 실거래 충전에는 KG이니시스 가맹점 자격과 운영 환경이 필요해요.

## 브라우저에서 바로 체험하기

Java 17과 Maven을 설치한 뒤 다음 명령을 실행하고 [선불카드 대시보드](http://127.0.0.1:8080/)를 열어요.

```bash
mvn spring-boot:run "-Dspring-boot.run.profiles=sandbox"
```

1. 회원가입 후 로그인하고 **카드 발급**을 눌러요.
2. 거래 유형에서 **샌드박스 충전**을 선택해 10,000원을 넣어요.
3. **연습 결제**를 선택해 3,500원을 사용해요. 잔액은 6,500원이 되고 거래 내역에서 충전과 결제를 필터링하거나 정렬할 수 있어요.
4. 관리자 화면도 보려면 실행 전에 `BOOTSTRAP_ADMIN_USERNAME`과 `BOOTSTRAP_ADMIN_PASSWORD`를 설정해요. 비밀번호는 16자 이상이어야 해요. 관리자 로그인 시 전체 카드와 최근 감사 기록을 확인할 수 있어요.

샌드박스는 기본적으로 로컬 주소에 바인딩하고, 전용 H2 메모리 DB를 사용해 재시작하면 데이터가 사라져요. 이 프로필에서는 이니시스 결제 주문 및 콜백 처리가 비활성화돼요. PostgreSQL과 함께 실행할 수 없어요. 대시보드의 샌드박스 충전 메뉴는 이 프로필에서만 표시돼요.

`mvn verify`로 회원가입, CSRF 검사, 로그인, 카드 소유권, 동시 충전 재시도, 잔액 초과 결제, 관리자 권한을 검증할 수 있어요.

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

`method`는 `CARD`(신용카드) 또는 `BANK`(실시간 계좌이체)예요. 응답에는 이니시스 결제창 스크립트 주소와 폼에 넣을 결제 파라미터가 와요. PG 결제창을 열려면 별도의 웹 화면에서 이 파라미터를 이니시스 공식 JavaScript에 전달해야 해요. 결제창이 호출되기 전에 로그인된 브라우저 세션과 CSRF를 적용하는 쇼핑 화면을 구성하세요. 인증 뒤 이니시스가 콜백으로 돌아오면 서버가 이니시스 승인 API를 직접 부릅니다. 브라우저가 보낸 “성공” 메시지만으로 잔액을 충전하지 않아요.

주문 상태 확인은 로그인한 사용자가 `GET /api/payments/{orderId}`로 볼 수 있어요. `PAID`가 아니면 충전이 완료된 것이 아니에요. `APPROVAL_UNKNOWN`이거나 오랫동안 `APPROVING`이면 이니시스 가맹점 관리자에서 거래를 확인해야 해요. 같은 주문을 다시 승인하지 마세요.

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

앱 주소는 `http://localhost:8080`이에요. 대시보드 로그인 화면이 나오면 앱이 실행된 상태예요. 관리자 로그인은 `.env`의 `BOOTSTRAP_ADMIN_USERNAME`과 `BOOTSTRAP_ADMIN_PASSWORD` 값을 사용해요.

## 모바일 앱에서 API 부르기

Android/iOS 앱은 먼저 `GET /api/auth/csrf`를 불러 CSRF 토큰과 `XSRF-TOKEN` 쿠키를 받아요. 회원가입 `POST /api/mobile/auth/register`와 로그인 `POST /api/mobile/auth/login`에는 쿠키를 보관해 함께 보내고, 토큰을 `X-XSRF-TOKEN` 헤더에 넣어요. 로그인 전 보호를 풀지 않았기 때문이에요. 로그인 답에 들어 있는 `accessToken`은 12시간 동안 쓸 수 있는 열쇠예요. 앱은 열쇠를 Android Keystore 또는 iOS Keychain 같은 안전한 저장소에 보관하고, 화면에 적거나 로그로 출력하지 마세요. 만료되면 다시 로그인하면 돼요.

로그인 뒤 모든 API 요청에 아래 헤더를 붙여요. 웹 쿠키용 CSRF 토큰 없이도 기존 카드·결제 API를 사용할 수 있어요.

```http
Authorization: Bearer 로그인응답의_accessToken
```

예를 들어 카드 목록은 `GET /api/cards`, 충전 주문은 `POST /api/cards/{카드번호}/charge`, 잔액 사용 연습은 `POST /api/cards/{카드번호}/pay`예요. 로그아웃은 `POST /api/mobile/auth/logout`을 부르면 그 토큰을 바로 폐기해요. 이 서버는 앱 전용 HTTPS 주소에 배포해야 해요. 지금 프로젝트에는 특정 회사의 비공개 모빌카드 API 주소나 데이터 형식이 없어서, 여기까지는 일반 Android/iOS 앱과 연결할 수 있는 표준 API예요.

## 운영 프로필

빌드 후 배포 환경의 비밀 저장소나 환경 변수로 아래 값을 설정하고 앱을 시작해요.

| 변수 | 설정 |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `production` |
| `DATABASE_URL` | 운영 PostgreSQL JDBC 주소 |
| `DATABASE_USERNAME`, `DATABASE_PASSWORD` | 운영 DB 인증 정보 |
| `INICIS_MODE` | `production` |
| `INICIS_MID`, `INICIS_HASH_KEY` | 가맹점에서 발급받은 운영값 |
| `APP_PUBLIC_URL` | HTTPS 서비스 주소 (경로 없이 입력) |

```bash
mvn verify
java -jar target/prepaid-card-demo-1.2.0.jar
```

`production`은 PostgreSQL 프로필과 Flyway를 활성화해요. HTTPS 종료를 담당하는 프록시에서 앱으로 연결할 수 있고, 세션 쿠키는 HTTPS 접속에서 사용돼요. 실제 고객 데이터를 받기 전에 가맹점 승인 응답, 이중 요청, 통신 장애 후 거래 대사, 환불 및 취소, 운영 모니터링을 가맹점 테스트 환경에서 검증해야 해요. 현재 구현의 운영 프로필과 주문 API는 별도 결제창 연동 및 실거래 검증이 필요해요.

## 공식 참고

- [KG이니시스 INIpay PRO 공식 연동 문서](https://manual.inicis.com/inipaypro/)
- [KG이니시스 INIpay PRO 테스트 안내](https://manual.inicis.com/pay/demo/)
