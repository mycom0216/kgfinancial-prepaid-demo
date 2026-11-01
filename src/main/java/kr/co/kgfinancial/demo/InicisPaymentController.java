package kr.co.kgfinancial.demo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

record PaymentStartRequest(@NotNull Long cardId, @jakarta.validation.constraints.Positive @Max(1000000000L) long amount,
                           @NotNull @Pattern(regexp="CARD|BANK") String method) { }
record CardChargeRequest(@jakarta.validation.constraints.Positive @Max(1000000000L) long amount,
                         @Pattern(regexp="CARD|BANK") String method) { }
record PaymentStartResponse(String orderId, String scriptUrl, Map<String,String> parameters) { }
record PaymentStatus(String orderId, long amount, String method, String status) { }

@RestController
@RequestMapping("/api/payments")
class InicisPaymentController {
    private final UserRepository users;
    private final CardRepository cards;
    private final PaymentOrderRepository orders;
    private final TransactionRepository transactions;
    private final AuditRepository audits;
    private final String mid, hashKey, publicUrl, mode;

    InicisPaymentController(UserRepository users, CardRepository cards, PaymentOrderRepository orders,
                            TransactionRepository transactions, AuditRepository audits,
                            @Value("${inicis.mid:}") String mid, @Value("${inicis.hash-key:}") String hashKey,
                            @Value("${app.public-url:}") String publicUrl, @Value("${inicis.mode:staging}") String mode) {
        this.users=users; this.cards=cards; this.orders=orders; this.transactions=transactions; this.audits=audits;
        this.mid=mid; this.hashKey=hashKey; this.publicUrl=publicUrl; this.mode=mode;
    }

    // 결제창에 보낼 주문 정보를 서버가 만들어요. 카드번호나 계좌번호는 우리 서버에 오지 않아요.
    @PostMapping
    @Transactional
    PaymentStartResponse start(@Valid @RequestBody PaymentStartRequest request,
                               @RequestHeader("Idempotency-Key") String requestKey, Authentication auth) {
        return prepareOrder(request.cardId(), request.amount(), request.method(), requestKey, auth);
    }

    PaymentStartResponse prepareOrder(Long cardId, long amount, String method, String requestKey, Authentication auth) {
        if (mid.isBlank() || hashKey.isBlank() || publicUrl.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "이니시스 MID, 해시키, HTTPS 공개 주소를 환경 변수에 설정해 주세요.");
        }
        if (!publicUrl.startsWith("https://") || publicUrl.length() > 400)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "결제 결과를 받을 주소는 HTTPS여야 해요.");
        if (requestKey.length()<10 || requestKey.length()>100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key는 10~100글자여야 해요.");
        UserAccount user=users.findByUsername(auth.getName()).orElseThrow();
        DemoCard card=cards.findById(cardId).filter(c->c.getOwnerId().equals(user.getId()))
                .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"카드를 찾지 못했어요."));
        String key=sha256(requestKey);
        PaymentOrder order=orders.findByUserIdAndIdempotencyKey(user.getId(),key).orElse(null);
        if (order!=null) {
            if (!order.getCardId().equals(card.getId()) || order.getAmount()!=amount || !order.getPayType().equals(method))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"같은 요청 키를 다른 결제에 사용할 수 없어요.");
            if (!order.getStatus().equals("READY")) throw new ResponseStatusException(HttpStatus.CONFLICT,"이 주문은 이미 결제 절차를 시작했어요.");
        } else {
            order=orders.save(new PaymentOrder("KG"+UUID.randomUUID().toString().replace("-",""), user.getId(), card.getId(), amount, method, key));
            audits.save(new AuditLog(auth.getName(),"이니시스 결제 주문 생성","order:"+order.getOrderId()));
        }
        String stamp=Long.toString(System.currentTimeMillis());
        Map<String,String> fields=Map.ofEntries(
                Map.entry("P_MID",mid),Map.entry("P_OID",order.getOrderId()),Map.entry("P_PAY_TYPE",method),Map.entry("P_DEVICE_TYPE","WEB"),
                Map.entry("P_IDCCODE","Y"),Map.entry("P_AMT",Long.toString(amount)),Map.entry("P_GOODS","선불카드 충전"),
                Map.entry("P_UNAME","회원"),Map.entry("P_NEXT_URL",publicUrl+"/api/payments/inicis/next"),
                Map.entry("P_TIMESTAMP",stamp),Map.entry("P_CHKFAKE",chkFake(amount,order.getOrderId(),stamp)),Map.entry("P_CHARSET","UTF-8"));
        return new PaymentStartResponse(order.getOrderId(),"https://paypro.inicis.com/std/payment/js/INIPayPro_v2.js",fields);
    }

    // 이니시스 인증 결과는 참고만 해요. 실제 승인은 아래 서버 통신 결과로 확인해요.
    @RequestMapping(value="/inicis/next", method={RequestMethod.POST,RequestMethod.GET}, produces=MediaType.TEXT_HTML_VALUE)
    @Transactional
    String next(@RequestParam MultiValueMap<String,String> form) {
        String oid=value(form,"P_OID"), status=value(form,"P_STATUS"), callbackMid=value(form,"P_MID");
        PaymentOrder order=orders.findByOrderIdForUpdate(oid).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!mid.equals(callbackMid) || !Long.toString(order.getAmount()).equals(value(form,"P_AMT"))) {
            order.markFailed(); throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"주문 정보가 일치하지 않아요.");
        }
        if (order.getStatus().equals("PAID")) return resultPage(true,oid);
        if (!order.getStatus().equals("READY")) return resultPage(false,oid);
        if (!"00".equals(status)) { order.markFailed(); audits.save(new AuditLog("inicis","결제 인증 실패","order:"+oid)); return resultPage(false,oid); }
        String idc=value(form,"P_IDCNAME"), authTid=value(form,"P_AUTH_TID");
        String host;
        if (mode.equals("staging") && "stg".equals(idc)) host="stgpaypro.inicis.com";
        else if (mode.equals("production") && ("fc".equals(idc)||"ks".equals(idc))) host=idc+"paypro.inicis.com";
        else { order.markFailed(); return resultPage(false,oid); }
        if (authTid.isBlank() || authTid.length()>100) { order.markFailed(); return resultPage(false,oid); }
        order.markApproving();
        try {
            MultiValueMap<String,String> payload=new LinkedMultiValueMap<>();
            payload.add("P_MID",mid); payload.add("P_AUTH_TID",authTid); payload.add("P_AMT",Long.toString(order.getAmount())); payload.add("P_CHARSET","UTF-8");
            String response=RestClient.create("https://"+host).post().uri("/payment/v1/rest/payAppl.ini")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(payload).retrieve().body(String.class);
            Map<String,String> result=parseResult(response);
            if ("00".equals(result.get("P_STATUS")) && mid.equals(result.get("P_MID")) && oid.equals(result.get("P_OID"))
                    && Long.toString(order.getAmount()).equals(result.get("P_AMT"))
                    && order.getPayType().equals(result.get("P_TYPE")) && !result.getOrDefault("P_APPL_TID","").isBlank()) {
                DemoCard card=cards.findById(order.getCardId()).orElseThrow();
                card.addMoney(order.getAmount());
                order.markPaid(result.get("P_APPL_TID"));
                transactions.save(new DemoTransaction(order.getUserId(),order.getCardId(),"이니시스 충전",order.getAmount(),sha256("pg:"+oid)));
                audits.save(new AuditLog("inicis","결제 승인 및 충전 완료","order:"+oid));
                return resultPage(true,oid);
            }
            order.markFailed(); audits.save(new AuditLog("inicis","결제 승인 거절","order:"+oid));
            return resultPage(false,oid);
        } catch (Exception uncertain) {
            // 승인 응답을 못 받았으면 실패로 단정하지 않아요. 다시 결제해 이중 승인되는 걸 막아요.
            order.markUnknown(); audits.save(new AuditLog("inicis","결제 승인 확인 필요","order:"+oid));
            return resultPage(false,oid);
        }
    }

    @GetMapping("/{orderId}")
    PaymentStatus status(@PathVariable String orderId, Authentication auth) {
        UserAccount user=users.findByUsername(auth.getName()).orElseThrow();
        PaymentOrder order=orders.findByOrderId(orderId).filter(o->o.getUserId().equals(user.getId()))
                .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        return new PaymentStatus(order.getOrderId(),order.getAmount(),order.getPayType(),order.getStatus());
    }

    private String chkFake(long amount,String oid,String stamp) {
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

// 예전 주소로 들어온 충전도 이니시스 결제 주문을 만들어요. 여기서는 잔액을 직접 바꾸지 않아요.
@RestController
@RequestMapping("/api/cards")
class CardChargeController {
    private final InicisPaymentController payments;
    CardChargeController(InicisPaymentController payments) { this.payments=payments; }

    @PostMapping("/{cardId}/charge")
    @Transactional
    PaymentStartResponse charge(@PathVariable Long cardId, @Valid @RequestBody CardChargeRequest request,
                                @RequestHeader("Idempotency-Key") String requestKey, Authentication auth) {
        String method=request.method()==null?"CARD":request.method();
        if (!method.equals("CARD") && !method.equals("BANK"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"결제 방식은 CARD 또는 BANK여야 해요.");
        return payments.prepareOrder(cardId,request.amount(),method,requestKey,auth);
    }
}

