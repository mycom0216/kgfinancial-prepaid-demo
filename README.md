# KG파이낸셜 직무 맞춤 프로젝트: 선불카드 미니 서버

이 프로젝트는 사람인·잡코리아의 KG파이낸셜 개발자 공고에 나온 **Java, Spring Boot, REST API, RDBMS, 백오피스/웹사이트 및 선불카드 결제** 업무 키워드를 연습하는 학습용 예제입니다.

> KG파이낸셜의 실제 시스템이 아닙니다. 실제 카드, 실제 돈, 실제 고객정보를 사용하지 않습니다.

## 무엇을 할 수 있나요?

- 시험용 카드 만들기
- 카드에 가짜 돈 충전하기
- 가짜 상품 결제하기
- 거래 내역 보기
- 관리자가 모든 카드 보기

데이터는 H2라는 작은 연습용 데이터베이스에 저장됩니다. 서버를 끄면 연습 데이터도 사라집니다.

## 실행하기

JDK 17 이상과 Maven이 필요합니다.

```bash
mvn spring-boot:run
```

서버가 켜지면 `http://localhost:8080`을 사용합니다. 아래 주소를 브라우저에서 열면 데이터베이스 화면을 볼 수 있습니다.

- `http://localhost:8080/h2-console`
- JDBC URL: `jdbc:h2:mem:kgdemo`
- 사용자 이름: `sa`
- 비밀번호: 비워 두기

## API 사용 예시

카드 만들기:

```bash
curl -X POST http://localhost:8080/api/cards -H "Content-Type: application/json" -d "{\"ownerName\":\"민수\"}"
```

충전하기(카드 번호가 1일 때):

```bash
curl -X POST http://localhost:8080/api/cards/1/charge -H "Content-Type: application/json" -d "{\"amount\":10000}"
```

결제하기:

```bash
curl -X POST http://localhost:8080/api/cards/1/pay -H "Content-Type: application/json" -d "{\"amount\":2500,\"description\":\"연습용 문구점\"}"
```

거래 내역:

```bash
curl http://localhost:8080/api/cards/1/transactions
```

모든 카드(백오피스용):

```bash
curl http://localhost:8080/api/admin/cards
```

## 파일 안내

- `KgFinancialDemoApplication.java`: 서버 시작점, 카드 API, 돈 계산 규칙, 데이터 표
- `pom.xml`: Java에 필요한 도구 목록
- `src/main/resources/application.properties`: 연습 데이터베이스 설정

## 실제 금융 서비스라면 더 필요한 것

로그인과 권한 확인, 개인정보 보호, 요청 중복 방지, 기록 감사, 실제 관계형 DB 운영, 보안 점검 등이 더 필요합니다. 이 예제에는 실제 결제 연결이나 운영 보안 기능이 없습니다.
