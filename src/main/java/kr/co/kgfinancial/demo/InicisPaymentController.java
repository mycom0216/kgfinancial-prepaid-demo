package kr.co.kgfinancial.demo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

// /api/payments에 보내는 입력 상자예요. 돈을 얼마나 넣을지와 어떤 결제 방법인지 적어요.
// 숫자 검사표(@Positive, @Max)는 0원이나 너무 큰 값을 미리 막아줘요.
record PaymentStartRequest(@NotNull Long cardId, @jakarta.validation.constraints.Positive @Max(999999999L) long amount,
                           @NotNull @Pattern(regexp="CARD|BANK") String method) { }
// 예전 카드 충전 주소에서 받는 입력이에요. 결제 방법을 빼면 카드(CARD)로 진행해요.
record CardChargeRequest(@jakarta.validation.constraints.Positive @Max(999999999L) long amount,
                         @Pattern(regexp="CARD|BANK") String method) { }
// 브라우저가 이니시스 결제창을 열 수 있도록 서버가 주는 답이에요.
record PaymentStartResponse(String orderId, String scriptUrl, Map<String,String> parameters) { }
// 로그인한 사용자가 주문 상태를 볼 때 사용하는 답이에요. 결제 전체 정보는 보여주지 않아요.
record PaymentStatus(String orderId, long amount, String method, String status) { }

// 이 클래스는 결제 준비, 이니시스 승인 확인, 충전 기록 저장을 맡아요.
@RestController
@RequestMapping("/api/payments")
class InicisPaymentController {
    // 필요한 저장소들을 준비해요. 저장소는 데이터베이스 표를 읽고 쓰는 도우미예요.
    private final UserRepository users;
    private final CardRepository cards;
    private final PaymentOrderRepository orders;
    private final TransactionRepository transactions;
    private final AuditRepository audits;
    // MID와 Hash Key는 비밀이므로 코드에 적지 않고 실행할 때 환경 변수에서 받아요.
    private final String mid, hashKey, publicUrl, mode;
    private final Environment environment;
    private final TransactionTemplate approvalTransactions;
    private final RestClient gatewayClient;

    InicisPaymentController(UserRepository users, CardRepository cards, PaymentOrderRepository orders,
                            TransactionRepository transactions, AuditRepository audits,
                            @Value("${inicis.mid:}") String mid, @Value("${inicis.hash-key:}") String hashKey,
                            @Value("${app.public-url:}") String publicUrl, @Value("${inicis.mode:staging}") String mode,
                            Environment environment, PlatformTransactionManager transactionManager) {
        this.users=users; this.cards=cards; this.orders=orders; this.transactions=transactions; this.audits=audits;
        this.environment=environment;
        this.approvalTransactions = new TransactionTemplate(transactionManager);
        var requestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build());
        requestFactory.setReadTimeout(Duration.ofSeconds(15));
        this.gatewayClient = RestClient.builder().requestFactory(requestFactory).build();
        this.mid=mid; this.hashKey=hashKey; this.publicUrl=publicUrl.replaceFirst("/$", ""); this.mode=mode;
    }

    // 1단계: 결제창에 보낼 주문 정보를 서버가 만들어요.
    // 카드번호나 계좌번호는 PG 결제창에서만 입력하고, 우리 서버는 그 번호를 받거나 저장하지 않아요.
    @PostMapping
    @Transactional
    PaymentStartResponse start(@Valid @RequestBody PaymentStartRequest request,
                               @RequestHeader("Idempotency-Key") String requestKey, Authentication auth) {
        return prepareOrder(request.cardId(), request.amount(), request.method(), requestKey, auth);
    }

    PaymentStartResponse prepareOrder(Long cardId, long amount, String method, String requestKey, Authentication auth) {
        // 비밀값이나 HTTPS 주소가 빠졌다면 결제 주문을 만들지 않아요.
        // HTTPS는 브라우저와 서버 사이의 통신을 잠가서 엿보거나 바꾸기 어렵게 해줘요.
        if (!gatewayProfileEnabled() || mid.isBlank() || hashKey.isBlank() || publicUrl.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "이니시스 MID, 해시키, HTTPS 공개 주소를 환경 변수에 설정해 주세요.");
        }
        if (!publicUrl.startsWith("https://") || publicUrl.length() > 400)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "결제 결과를 받을 주소는 HTTPS여야 해요.");
        if (requestKey.length()<10 || requestKey.length()>100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key는 10~100글자여야 해요.");
        // 로그인한 사람의 계정을 찾고, 요청한 카드가 바로 그 사람 카드인지 확인해요.
        // 카드 번호만 알아내서 다른 사람 돈을 충전하는 일을 막는 검사예요.
        UserAccount user=users.findByUsernameForUpdate(auth.getName()).orElseThrow();
        DemoCard card=cards.findById(cardId).filter(c->c.getOwnerId().equals(user.getId()))
                .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"카드를 찾지 못했어요."));
        // 원래 요청 키를 DB에 그대로 보관하지 않도록 지문처럼 바꿔 저장해요.
        // 같은 키를 다시 보내면 같은 주문인지 찾을 수 있고, 다른 내용에 재사용하면 거절해요.
        String key=sha256(requestKey);
        PaymentOrder order=orders.findByUserIdAndIdempotencyKey(user.getId(),key).orElse(null);
        if (order!=null) {
            if (!order.getCardId().equals(card.getId()) || order.getAmount()!=amount || !order.getPayType().equals(method))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"같은 요청 키를 다른 결제에 사용할 수 없어요.");
            if (!order.getStatus().equals("READY")) throw new ResponseStatusException(HttpStatus.CONFLICT,"이 주문은 이미 결제 절차를 시작했어요.");
        } else {
            // 아직 결제가 된 게 아니므로 상태는 READY(결제 준비)예요.
            // 주문 번호는 매번 새로 뽑아 다른 주문과 겹치지 않게 해요.
            order=orders.save(new PaymentOrder("KG"+UUID.randomUUID().toString().replace("-",""), user.getId(), card.getId(), amount, method, key));
            audits.save(new AuditLog(auth.getName(),"이니시스 결제 주문 생성","order:"+order.getOrderId()));
        }
        // 이니시스가 금액과 주문 번호가 중간에 바뀌지 않았는지 확인할 수 있게 서명 재료를 만들어요.
        String stamp=Long.toString(System.currentTimeMillis());
        Map<String,String> fields=Map.ofEntries(
                Map.entry("P_MID",mid),Map.entry("P_OID",order.getOrderId()),Map.entry("P_PAY_TYPE",method),Map.entry("P_DEVICE_TYPE","WEB"),
                Map.entry("P_IDCCODE","Y"),Map.entry("P_AMT",Long.toString(amount)),Map.entry("P_GOODS","선불카드 충전"),
                Map.entry("P_UNAME","회원"),Map.entry("P_NEXT_URL",publicUrl+"/api/payments/inicis/next"),
                Map.entry("P_TIMESTAMP",stamp),Map.entry("P_CHKFAKE",chkFake(amount,order.getOrderId(),stamp)),Map.entry("P_CHARSET","UTF-8"));
        return new PaymentStartResponse(order.getOrderId(),"https://paypro.inicis.com/std/payment/js/INIPayPro_v2.js",fields);
    }

    @RequestMapping(value="/inicis/next", method={RequestMethod.POST,RequestMethod.GET}, produces=MediaType.TEXT_HTML_VALUE)
    String next(@RequestParam MultiValueMap<String,String> form) {
        if (!gatewayProfileEnabled() || mid.isBlank() || hashKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        String oid = value(form, "P_OID");
        if (!oid.matches("KG[0-9a-f]{32}") || !mid.equals(value(form, "P_MID"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "주문 정보가 일치하지 않아요.");
        }
        String amount = value(form, "P_AMT");
        String authTid = value(form, "P_AUTH_TID");
        String host = approvalHost(value(form, "P_IDCNAME"));
        boolean canApprove = "00".equals(value(form, "P_STATUS")) && host != null
                && !authTid.isBlank() && authTid.length() <= 40;

        String status = approvalTransactions.execute(transaction -> {
            PaymentOrder order = lockedOrder(oid);
            if (!Long.toString(order.getAmount()).equals(amount)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "주문 금액이 일치하지 않아요.");
            }
            if (canApprove && "READY".equals(order.getStatus())) {
                order.markApproving();
                return "SEND_APPROVAL";
            }
            return order.getStatus();
        });
        if (!"SEND_APPROVAL".equals(status)) return resultPage("PAID".equals(status), oid);

        // 승인 요청 전에 처리 중 상태를 커밋합니다. 재시작이나 중복 콜백에서 자동으로 재승인하지 않습니다.
        Map<String, String> response = requestApproval(host, amount, authTid);
        try {
            return resultPage(Boolean.TRUE.equals(approvalTransactions.execute(transaction ->
                    applyApproval(lockedOrder(oid), response, authTid))), oid);
        } catch (RuntimeException persistenceFailure) {
            // 저장에 실패해도 승인 시도 상태는 별도 트랜잭션에 남습니다.
            approvalTransactions.executeWithoutResult(transaction -> {
                PaymentOrder order = lockedOrder(oid);
                if ("APPROVING".equals(order.getStatus())) markApprovalUnknown(order);
            });
            return resultPage(false, oid);
        }
    }

    private PaymentOrder lockedOrder(String oid) {
        return orders.findByOrderIdForUpdate(oid)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private String approvalHost(String idc) {
        if ("staging".equals(mode) && "stg".equals(idc)) return "stgpaypro.inicis.com";
        if ("production".equals(mode) && ("fc".equals(idc) || "ks".equals(idc))) return idc + "paypro.inicis.com";
        return null;
    }

    private Map<String, String> requestApproval(String host, String amount, String authTid) {
        MultiValueMap<String, String> payload = new LinkedMultiValueMap<>();
        payload.add("P_MID", mid);
        payload.add("P_AUTH_TID", authTid);
        payload.add("P_AMT", amount);
        payload.add("P_CHARSET", "UTF-8");
        try {
            String response = gatewayClient.post().uri("https://" + host + "/payment/v1/rest/payAppl.ini")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(payload).retrieve().body(String.class);
            return parseResult(response);
        } catch (org.springframework.web.client.RestClientException failure) {
            return Map.of();
        }
    }

    private boolean applyApproval(PaymentOrder order, Map<String, String> result, String authTid) {
        String oid = order.getOrderId();
        String code = result.getOrDefault("P_STATUS", "");
        String providerTid = result.getOrDefault("P_APPL_TID", "");
        boolean matchingOrder = mid.equals(result.get("P_MID")) && oid.equals(result.get("P_OID"))
                && authTid.equals(result.get("P_AUTH_TID"));
        if (matchingOrder && "00".equals(code)
                && Long.toString(order.getAmount()).equals(result.get("P_AMT"))
                && order.getPayType().equals(result.get("P_TYPE"))
                && !providerTid.isBlank() && providerTid.length() <= 40) {
            DemoCard card = cards.findOwnedByIdForUpdate(order.getCardId(), order.getUserId()).orElseThrow();
            card.addMoney(order.getAmount());
            order.markPaid(providerTid);
            transactions.save(new DemoTransaction(order.getUserId(), order.getCardId(), "이니시스 충전",
                    order.getAmount(), sha256("pg:" + oid)));
            audits.save(new AuditLog("inicis", "결제 승인 및 충전 완료", "order:" + oid));
            return true;
        }
        if (matchingOrder && !code.isBlank() && !"00".equals(code)) {
            order.markFailed();
            audits.save(new AuditLog("inicis", "결제 승인 거절", "order:" + oid));
        } else {
            markApprovalUnknown(order);
        }
        return false;
    }

    private void markApprovalUnknown(PaymentOrder order) {
        order.markUnknown();
        audits.save(new AuditLog("inicis", "결제 승인 확인 필요", "order:" + order.getOrderId()));
    }

    @GetMapping("/{orderId}")
    PaymentStatus status(@PathVariable String orderId, Authentication auth) {
        // 주문 번호를 아는 것만으로 남의 결제 상태를 볼 수 없어요. 로그인한 주인인지 확인해요.
        UserAccount user=users.findByUsername(auth.getName()).orElseThrow();
        PaymentOrder order=orders.findByOrderId(orderId).filter(o->o.getUserId().equals(user.getId()))
                .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        return new PaymentStatus(order.getOrderId(),order.getAmount(),order.getPayType(),order.getStatus());
    }

    private boolean gatewayProfileEnabled() {
        return !environment.matchesProfiles("sandbox")
                && ("staging".equals(mode) || ("production".equals(mode) && environment.matchesProfiles("production")));
    }

    private String chkFake(long amount,String oid,String stamp) {
        // SHA-512는 글자를 긴 지문으로 바꾸는 방법이에요. 이니시스와 우리 서버가 같은 지문을 계산해요.
        try { byte[] hash=MessageDigest.getInstance("SHA-512").digest((amount+oid+stamp+hashKey).getBytes(StandardCharsets.UTF_8)); return Base64.getEncoder().encodeToString(hash); }
        catch(Exception e) { throw new IllegalStateException("결제 서명을 만들 수 없어요.",e); }
    }
    private String sha256(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    private String value(MultiValueMap<String,String> map,String key) { return map.getFirst(key)==null?"":map.getFirst(key); }
    private Map<String,String> parseResult(String body) {
        if(body==null) return Map.of();
        try {
            // XML 파서가 인터넷에서 온 문서 안의 외부 파일이나 주소를 읽지 못하게 막아요.
            // 결제 답장 하나를 읽다가 서버의 다른 자료가 새어 나오는 일을 방지해요.
            var factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
            var doc=factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
            var nodes=doc.getDocumentElement().getChildNodes(); var out=new java.util.HashMap<String,String>();
            for(int i=0;i<nodes.getLength();i++) if(nodes.item(i).getNodeType()==org.w3c.dom.Node.ELEMENT_NODE) out.put(nodes.item(i).getNodeName(),nodes.item(i).getTextContent());
            return out;
        } catch(Exception invalid) { return Map.of(); }
    }
    private String resultPage(boolean ok,String oid) {
        String message=ok?"결제가 확인되어 연습 카드 잔액에 반영됐어요.":"결제 결과를 확인할 수 없어요. 주문 상태를 확인해 주세요.";
        return "<!doctype html><meta charset='utf-8'><title>결제 결과</title><p>"+message+"</p><p>주문 번호: "+oid+"</p><a href='/'>처음으로</a>";
    }
}

// 예전 주소를 계속 쓰는 화면도 새 결제 흐름으로 이어지도록 연결하는 다리예요.
// 이 주소는 주문 준비만 하고, 결제가 진짜 승인될 때까지 카드 잔액은 건드리지 않아요.
@RestController
@RequestMapping("/api/cards")
class CardChargeController {
    private final InicisPaymentController payments;
    CardChargeController(InicisPaymentController payments) { this.payments=payments; }

    @PostMapping("/{cardId}/charge")
    @Transactional
    PaymentStartResponse charge(@PathVariable Long cardId, @Valid @RequestBody CardChargeRequest request,
                                @RequestHeader("Idempotency-Key") String requestKey, Authentication auth) {
        // 화면이 결제 방법을 생략하면 카드 결제로 진행해요. 계좌이체는 BANK를 보내면 돼요.
        String method=request.method()==null?"CARD":request.method();
        if (!method.equals("CARD") && !method.equals("BANK"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"결제 방식은 CARD 또는 BANK여야 해요.");
        return payments.prepareOrder(cardId,request.amount(),method,requestKey,auth);
    }
}
