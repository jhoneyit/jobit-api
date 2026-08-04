# 엔드포인트 명세

**이 문서가 `jobit-front`와의 계약 원본이다.** 엔드포인트를 바꾸면 여기부터 고치고 프론트를 맞춘다.

구현 상태: `POST /api/jd/parse` ✅ / `GET /api/questions` (SSE) ✅ / `GET /api/stats/stacks` ✅

## 공통 규약

- **응답은 JSON.** 2026-08-03 결정으로 Thymeleaf·htmx를 쓰지 않으므로 HTML 조각을 반환하지 않는다.
  예외는 질문 생성뿐이며 `text/event-stream`이다.
- **에러는 `{ "error": "<사용자에게 보여줄 한국어 문장>" }`** 한 가지 형태로 통일한다.
  프론트가 그대로 화면에 띄울 수 있어야 하므로, 내부 예외 메시지나 스택을 담지 않는다.
- **인증은 이 서버가 하지 않는다.** 프론트(Auth.js)가 로그인 상태를 판단하고, 호출할 때
  `owner_key`를 넘긴다. 이 서버는 그 값으로 소유자를 식별할 뿐이다.

  ```
  로그인   user:<user_id>
  비로그인 anon:<세션 쿠키>
  ```

  컨트롤러 진입점에서 `OwnerKey.requireValid`로 형식을 검증한다. 접두사 없는 값을 통과시키면
  조회가 조용히 0건을 반환해 규약 위반이 드러나지 않는다.

  > ⚠️ **형식 검증은 사칭을 막지 못한다.** 호출자 인증(서비스 토큰 등)은 아직 **미정**이며,
  > 그때까지 이 서버를 공개망에 노출하면 안 된다 (`architecture.md` 미결).
- **레이트 리밋**: 세션당 LLM 호출 횟수 제한 (스펙 §6). 초과 시 `429` + `Retry-After` 헤더(초).
  **캐시로 처리되는 요청은 한도를 소비하지 않는다** — LLM을 부르지 않았기 때문이다.
- 상태 코드: `400` 입력 오류 / `401` 인증 / `403` 소유자 불일치 / `404` 없음 /
  `429` 한도 초과 / `502` LLM 장애 / `500` 그 외.

### 소유자 검사

`jd_submission`·`resume` 같은 개인 자산은 **소유자가 아니면 존재 여부도 알려주지 않는다.**
`JdSubmissionService.getOwned`가 이미 그렇게 동작한다 (조회 조건에 `ownerKey`가 들어간다).
컨트롤러에서 소유자를 다시 비교하지 말고 이 메서드를 쓴다.

## 스트리밍

질문 생성은 SSE로 흘려보낸다. `text/event-stream`을 반환하고 브라우저의 `EventSource`가 받는다.
`EventSource`는 **GET만 지원하므로** 스트리밍 엔드포인트는 GET + 쿼리 파라미터다.

리버스 프록시가 버퍼링하면 스트리밍이 통째로 무의미해진다. 다음 헤더를 함께 낸다:

```
Content-Type: text/event-stream; charset=utf-8
Cache-Control: no-cache, no-transform
X-Accel-Buffering: no
```

## 구현된 엔드포인트

### `POST /api/jd/parse` — JD 파싱 (스펙 §4.1) ✅

정규화 → `content_hash` → 캐시 조회 → 없으면 LLM 파싱 후 저장.

**요청**

```jsonc
// 헤더: X-Owner-Key (선택) — 있으면 입력 이력에 남긴다
{
  "text": "채용공고 본문",   // 필수, 100~50,000자
  "sourceUrl": "https://..." // 선택, 2,000자 이하
}
```

**응답 `200`**

```jsonc
{
  "jobPostingId": "uuid",
  "company": "토스",              // 공고에 없으면 null
  "title": "백엔드 개발자",         // 공고에 없으면 null
  "parsed": { ... },              // 스택·연차·도메인 등. JSON 객체 그대로
  "cached": true,                 // LLM을 부르지 않고 재사용했는가
  "requirements": [
    { "id": "uuid", "text": "...", "kind": "REQUIRED",
      "keywords": ["Java"], "sortOrder": 0 }
  ]
}
```

`kind`는 `REQUIRED` | `PREFERRED` | `RESPONSIBILITY`. `requirements`는 `sortOrder` 순이며
**공고에 나온 순서를 유지한다** — 종류별로 재정렬하지 않는다.

**`cached`를 프론트가 봐야 하는 이유**: 캐시 적중이면 LLM을 부르지 않았으므로 레이트 리밋을
소비하지 않아야 한다. 프론트가 이 값으로 한도 차감 여부를 판단한다.

**에러**

| 상태 | 상황 |
| --- | --- |
| `400` | 본문 누락·길이 위반, 잘못된 JSON |
| `429` | 제공자 레이트 리밋 |
| `502` | LLM 장애, 또는 재검증 3회 실패 |
| `500` | `ANTHROPIC_API_KEY` 미설정 등 서버 설정 문제 |

**동시성**: 같은 공고가 동시에 들어오면 둘 다 캐시 미스로 판단해 나란히 파싱할 수 있다.
`content_hash` 유니크 제약이 최종 방어선이고, 진 쪽은 저장된 결과를 재사용한다.
LLM 호출 한 번이 낭비되지만 락을 잡는 것보다 낫다.

**`X-Owner-Key`**: 형식만 검증한다. **이력 기록이 실패해도 파싱 결과는 정상 반환한다** —
사용자가 원한 것은 분석 결과이고 이력은 부가 기능이다.

---

## 이관 대상 — 아직 `jobit-front`에 있는 구현

옮길 때 이 계약을 유지하면 프론트 호출부 변경이 최소화된다.

### `GET /api/questions?jobPostingId=...` — 질문 생성 (스펙 §4.2, SSE)

원본: `jobit-front/src/app/api/questions/route.ts`
서버측 대응: **없음.** `QuestionSet`/`Question` 엔티티만 있고 생성 로직이 없다.

| 항목 | 내용 |
| --- | --- |
| 요청 | 쿼리 `jobPostingId` |
| 응답 | `text/event-stream` |
| 에러 | `400` 파라미터 누락 / `429` 한도. 스트림 도중 실패는 `error` 이벤트로 내보내고 정상 종료 |

이벤트는 `data:` 한 줄에 JSON 하나씩:

```
{ "type": "meta",     "questionSetId": "...", "total": 10 }
{ "type": "question", "question": { ... } }
{ "type": "done",     "count": 10 }
{ "type": "error",    "message": "질문 생성 중 오류가 발생했습니다." }
```

**스트림이 열린 뒤의 실패는 HTTP 상태 코드로 알릴 수 없다.** `error` 이벤트를 보내고 닫는다.

캐시 키는 `(job_posting_id, prompt_version)`이다. 프롬프트를 고치면 버전을 올려야 옛 질문이
재사용되지 않는다.

### `GET /api/cost` — 비용 대시보드 (스펙 §3.5, dev 전용)

원본: `jobit-front/src/app/api/cost/route.ts`
서버측 대응: `LlmCallLog` 엔티티 + 리포지토리는 있고 집계 로직이 없다.

운영에 노출하지 않는다.

---

## 아직 계약이 없는 것

로드맵 3단계 이후(스펙 §5). 해당 단계에 들어갈 때 여기에 적는다.

- 이력서 업로드 · bullet 분해 (§3.3) — 개인정보라 암호화·TTL 결정이 선행되어야 한다
- 갭 분석 (§4.3) · 리라이트 (§4.4)
- 회원 · 입력 이력 조회 (§4.6) — `JdSubmissionService`는 이미 구현되어 있다
- 인증 경로 (가입 · 로그인 · 비밀번호 재설정) — 서비스는 구현되어 있고 컨트롤러만 없다
