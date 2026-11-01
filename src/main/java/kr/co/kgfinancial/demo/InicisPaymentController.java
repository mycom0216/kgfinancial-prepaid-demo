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

// /api/payments에 보내는 입력 상자예요. 돈을 얼마나 넣을지와 어떤 결제 방법인지 적어요.
// 숫자 검사표(@Positive, @Max)는 0원이나 너무 큰 값을 미리 막아줘요.
record PaymentStartRequest(@NotNull Long cardId, @jakarta.validation.constraints.Positive @Max(1000000000L) long amount,
                           @NotNull @Pattern(regexp="CARD|BANK") String method) { }
// 예전 카드 충전 주소에서 받는 입력이에요. 결제 방법을 빼면 카드(CARD)로 진행해요.
record CardChargeRequest(@jakarta.validation.constraints.Positive @Max(1000000000L) long amount,
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

    InicisPaymentController(UserRepository users, CardRepository cards, PaymentOrderRepository orders,
                            TransactionRepository transactions, AuditRepository audits,
                            @Value("${inicis.mid:}") String mid, @Value("${inicis.hash-key:}") String hashKey,
                            @Value("${app.public-url:}") String publicUrl, @Value("${inicis.mode:staging}") String mode) {
        this.users=users; this.cards=cards; this.orders=orders; this.transactions=transactions; this.audits=audits;
        this.mid=mid; this.hashKey=hashKey; this.publicUrl=publicUrl; this.mode=mode;
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
        if (mid.isBlank() || hashKey.isBlank() || publicUrl.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "이니시스 MID, 해시키, HTTPS 공개 주소를 환경 변수에 설정해 주세요.");
        }
        if (!publicUrl.startsWith("https://") || publicUrl.length() > 400)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "결제 결과를 받을 주소는 HTTPS여야 해요.");
        if (requestKey.length()<10 || requestKey.length()>100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key는 10~100글자여야 해요.");
        // 로그인한 사람의 계정을 찾고, 요청한 카드가 바로 그 사람 카드인지 확인해요.
        // 카드 번호만 알아내서 다른 사람 돈을 충전하는 일을 막는 검사예요.
        UserAccount user=users.findByUsername(auth.getName()).orElseThrow();
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

    // 2단계: PG 결제창이 끝나면 이니시스가 이 주소로 결과를 보내요.
    // 여기서 받은 값은 인터넷에서 온 값이라 바로 믿지 않아요. 아래에서 이니시스 서버에 다시 물어봐요.
    @RequestMapping(value="/inicis/next", method={RequestMethod.POST,RequestMethod.GET}, produces=MediaType.TEXT_HTML_VALUE)
    @Transactional
    String next(@RequestParam MultiValueMap<String,String> form) {
        String oid=value(form,"P_OID"), status=value(form,"P_STATUS"), callbackMid=value(form,"P_MID");
        // DB 잠금은 같은 주문 결과가 동시에 두 번 와도 한 번씩 차례대로 처리하게 해요.
        // 그래서 이중 클릭이나 중복 콜백으로 잔액이 두 번 오르는 일을 막아요.
        PaymentOrder order=orders.findByOrderIdForUpdate(oid).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!mid.equals(callbackMid) || !Long.toString(order.getAmount()).equals(value(form,"P_AMT"))) {
            order.markFailed(); throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"주문 정보가 일치하지 않아요.");
        }
        if (order.getStatus().equals("PAID")) return resultPage(true,oid);
        // 이미 처리 중이거나 실패한 주문은 다시 처리하지 않아요.
        if (!order.getStatus().equals("READY")) return resultPage(false,oid);
        // 00은 '인증을 통과했다'는 뜻일 뿐, 아직 실제 승인 완료는 아니에요.
        // 아래 서버 통신의 응답까지 성공해야 잔액을 올릴 수 있어요.
        if (!"00".equals(status)) { order.markFailed(); audits.save(new AuditLog("inicis","결제 인증 실패","order:"+oid)); return resultPage(false,oid); }
        String idc=value(form,"P_IDCNAME"), authTid=value(form,"P_AUTH_TID");
        String host;
        // 결제 결과에 들어온 서버 주소를 그대로 접속하면 위험해요.
        // 스테이징/운영에서 허용한 이름만 골라 서버가 직접 접속할 주소를 정해요.
        if (mode.equals("staging") && "stg".equals(idc)) host="stgpaypro.inicis.com";
        else if (mode.equals("production") && ("fc".equals(idc)||"ks".equals(idc))) host=idc+"paypro.inicis.com";
        else { order.markFailed(); return resultPage(false,oid); }
        if (authTid.isBlank() || authTid.length()>100) { order.markFailed(); return resultPage(false,oid); }
        // 여기부터는 우리 서버가 이니시스 승인 API를 직접 호출해요.
        // 사용자의 브라우저가 보내는 '성공' 화면은 돈이 들어왔다는 증거로 쓰지 않아요.
        order.markApproving();
        try {
            MultiValueMap<String,String> payload=new LinkedMultiValueMap<>();
            payload.add("P_MID",mid); payload.add("P_AUTH_TID",authTid); payload.add("P_AMT",Long.toString(order.getAmount())); payload.add("P_CHARSET","UTF-8");
            String response=RestClient.create("https://"+host).post().uri("/payment/v1/rest/payAppl.ini")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(payload).retrieve().body(String.class);
            // 이니시스 답장에 주문 번호, 금액, 결제 방법까지 우리 주문과 같은지 모두 대조해요.
            Map<String,String> result=parseResult(response);
            if ("00".equals(result.get("P_STATUS")) && mid.equals(result.get("P_MID")) && oid.equals(result.get("P_OID"))
                    && Long.toString(order.getAmount()).equals(result.get("P_AMT"))
                    && order.getPayType().equals(result.get("P_TYPE")) && !result.getOrDefault("P_APPL_TID","").isBlank()) {
                // 승인 결과와 금액이 모두 맞을 때만 카드 잔액과 거래 기록을 함께 저장해요.
                // @Transactional 덕분에 한쪽만 저장되고 다른 쪽이 빠지는 일을 줄여요.
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
            // 인터넷이 끊기면 이니시스는 승인했지만 우리 서버가 답장을 못 받았을 수 있어요.
            // 이럴 때 실패라고 단정하고 다시 결제시키면 두 번 돈이 나갈 수 있어요.
            // 그래서 확인이 필요한 상태로 남겨두고 가맹점 거래내역을 확인하도록 해요.
            order.markUnknown(); audits.save(new AuditLog("inicis","결제 승인 확인 필요","order:"+oid));
            return resultPage(false,oid);
        }
    }

    @GetMapping("/{orderId}")
    PaymentStatus status(@PathVariable String orderId, Authentication auth) {
        // 주문 번호를 아는 것만으로 남의 결제 상태를 볼 수 없어요. 로그인한 주인인지 확인해요.
        UserAccount user=users.findByUsername(auth.getName()).orElseThrow();
        PaymentOrder order=orders.findByOrderId(orderId).filter(o->o.getUserId().equals(user.getId()))
                .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        return new PaymentStatus(order.getOrderId(),order.getAmount(),order.getPayType(),order.getStatus());
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

