# 엔드포인트 명세

**이 문서가 `jobit-front`와의 계약 원본이다.** 엔드포인트를 바꾸면 여기부터 고치고 프론트를 맞춘다.

구현 상태: `POST /api/jd/parse` ✅ / `GET /api/questions` (SSE) ✅ / `GET /api/stats/stacks` ✅ /
`GET·DELETE /api/submissions` ✅ / `POST /api/submissions/claim` ✅ /
`POST /api/interviews` · `.../answers` · `.../finish` ✅ (기록 조회는 아직)

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

### `GET /api/submissions` — 입력 이력 목록 (스펙 §3.6, §4.6) ✅

`X-Owner-Key`의 이력을 최근순으로 준다. **`X-Owner-Key`는 여기서 필수다** — 개인 자산이므로
소유자 없이 조회할 대상이 없다. 없거나 형식이 틀리면 `400`이며, 빈 목록으로 얼버무리지 않는다.

**요청**

```
GET /api/submissions?page=0&size=20
헤더: X-Owner-Key (필수)
```

`size`는 1~100으로 잘린다. 화면이 페이지를 쓰지 않더라도 목록은 언젠가 길어지므로 계약에 둔다.

**응답 `200`**

```jsonc
{
  "items": [
    {
      "submissionId": "uuid",       // 삭제 대상 식별자. jobPostingId 가 아니다 (아래 참고)
      "jobPostingId": "uuid",       // 결과 화면 링크용
      "company": "토스",             // 공고에 없으면 null
      "title": "백엔드 개발자",       // 공고에 없으면 null
      "parsed": { ... },            // 스택·도메인 등. /api/jd/parse 의 parsed 와 같은 객체
      "memo": null,                 // 사용자 메모
      "requirementCount": 12,
      "questionCount": 10,          // 아직 생성 전이면 0
      "updatedAt": "2026-08-07T09:12:33Z",
      "gapSummary": null            // 갭 분석 전이면 null (0/0/0 으로 채우지 않는다)
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 3,
  "totalPages": 1
}
```

**`gapSummary`가 `null`인 것과 `0/0/0`인 것은 다르다.** 전자는 "아직 분석하지 않음",
후자는 "분석했는데 요구사항이 없음"이다. 화면이 이 둘을 구분해야 하므로 채워서 내리지 않는다.
요약 기준은 **가장 최근 이력서 하나**다 (이력서가 없으면 전부 `null`).

**`updatedAt`은 제출 시각이 아니라 마지막으로 넣은 시각이다.** 같은 공고를 다시 넣으면 줄을
새로 만들지 않고 이 값만 갱신한다 (스펙 §3.6) — 목록에 같은 공고가 여러 번 뜨지 않게 하려는
것이고, 대신 "몇 번 넣었는지"는 남지 않는다.

**에러**: `400` `X-Owner-Key` 누락·형식 위반.

---

### `DELETE /api/submissions/{submissionId}` — 이력 한 줄 삭제 ✅

**지우는 것은 `jd_submission` 한 줄뿐이고 `job_posting`은 남긴다.** 공고는 `content_hash`
기준 전역 캐시라(§4.1) 한 사람이 목록에서 치웠다고 지우면 다른 사람의 캐시 적중까지 깨진다.

```
DELETE /api/submissions/{submissionId}
헤더: X-Owner-Key (필수)
→ 204 No Content
```

**`jobPostingId`가 아니라 `submissionId`로 지운다.** 같은 공고를 여러 사람이 갖고 있으므로
공고 ID는 이 목록에서 한 줄을 지목하지 못한다 — 소유자까지 함께 봐야 비로소 한 줄이 정해진다.
목록이 `submissionId`를 내려주는 이유가 이것이다.

**에러**

| 상태 | 상황 |
| --- | --- |
| `400` | `X-Owner-Key` 누락·형식 위반, `submissionId`가 UUID가 아님 |
| `404` | 없거나 **내 것이 아님** |

**남의 이력은 `403`이 아니라 `404`다.** `403`은 "그 자원이 존재한다"를 알려주는 것이라,
ID를 넣어 보는 것만으로 남의 이력 존재 여부를 훑을 수 있다 (위 "소유자 검사").

---

### `POST /api/submissions/claim` — 익명 이력을 계정으로 승계 (스펙 §3.6) ✅

로그인 직후 프론트가 한 번 부른다. 이게 없으면 "질문 만들어 보고 마음에 들어서 로그인했더니
방금 만든 게 사라진" 상태가 된다.

```jsonc
// 헤더: X-Owner-Key — 받는 쪽. user: 여야 한다
{ "fromOwnerKey": "anon:<세션 쿠키>" }   // 넘겨줄 쪽. anon: 여야 한다
```

**응답 `200`**

```jsonc
{ "moved": 3 }   // 실제로 옮겨진 줄 수. 화면의 "기록 3건을 옮겼습니다" 문구가 이 값을 쓴다
```

**양쪽에 같은 공고가 있으면 익명 쪽을 버린다.** 익명일 때와 로그인 후에 같은 공고를 넣었으면
줄이 둘인데, 그대로 소유자만 바꾸면 `(owner_key, job_posting_id)` 유니크 제약에 걸려 승계
전체가 실패한다. 버려진 줄은 `moved`에 세지 않는다.

**방향을 강제한다**: `from`은 `anon:`, `X-Owner-Key`는 `user:`여야 한다. 반대 방향을 허용하면
계정 기록을 익명 키로 빼내는 경로가 생긴다. 위반은 `400`이다.

> ⚠️ 이 엔드포인트는 **호출자 인증이 없다는 사실이 가장 아프게 드러나는 지점**이다.
> `fromOwnerKey`를 지어내면 남의 익명 기록을 자기 계정으로 가져올 수 있다. 프론트는 이 값을
> 세션 쿠키에서 직접 읽어 넘기지만, 이 서버는 그것을 확인할 방법이 없다.
> 호출자 인증이 붙기 전까지 이 서버를 공개망에 노출하면 안 된다.

---

## 면접 연습 (docs/interview-practice-design.md)

**오디오를 받지 않는다.** STT는 브라우저(Web Speech API)가 하고 이 서버로는 텍스트만 온다.
멀티파트도 업로드 상한도 없다 — 이 기능의 개인정보 대책이 사실상 이 한 줄이다.

모두 `X-Owner-Key` 필수. 에러 형태·404 규칙은 `/api/submissions`와 같다.

### `POST /api/interviews` — 세션 시작 ✅

```jsonc
{ "jobPostingId": "uuid" }
```

**응답 `200`**

```jsonc
{
  "sessionId": "uuid",
  "jobPostingId": "uuid",
  "questionCount": 5,
  "questions": [
    { "questionId": "uuid", "text": "트랜잭션 격리 수준을…",
      "category": "CS", "difficulty": 3, "timeLimitSec": 90 }
  ]
}
```

**`answer_outline`(답변 뼈대)이 응답에 없다.** 보고 답하면 연습이 아니다 — 뼈대는 채점 응답에서
처음 나온다.

**`questionCount`가 설정값(`questions-per-session`)보다 작을 수 있다.** 뼈대가 없는 질문은
채점 기준이 없어 건너뛴다. 그런 문항을 넣으면 총점의 분모에는 들어가면서 절대 점수를 얻지 못해
총점이 부당하게 깎인다.

**에러**

| 상태 | 상황 |
| --- | --- |
| `400` | `jobPostingId` 누락, **이 공고의 질문이 아직 없음** (`"이 공고의 예상 질문을 먼저 만들어 주세요."`) |
| `404` | 공고 없음 |
| `429` | 일별 세션 상한 (`"오늘 면접 연습 횟수를 모두 사용했습니다…"`) |

> **질문이 있는 공고에서만 시작할 수 있다.** 제약이 아니라 진입점이다 — 채점 기준이 질문 생성의
> 산물이므로, 프론트는 "내 기록"에서 질문이 만들어진 공고를 고르게 한다.

---

### `POST /api/interviews/{sessionId}/answers` — 답변 제출 + 즉시 채점 ✅

```jsonc
{
  "questionId": "uuid",
  "transcript": "격리 수준은 네 가지가 있고요…",  // 시간 내 답하지 못했으면 null 또는 ""
  "durationMs": 42000
}
```

**응답 `200`**

```jsonc
{
  "questionId": "uuid",
  "answered": true,
  "score": 62,
  "outline": ["격리 수준 4가지", "이상 현상", "DBMS별 기본값", "실무 선택 기준"],
  "covered": [0, 1, 2],      // outline 인덱스
  "missed": [3],
  "feedback": "네 가지 수준을 정확히 나열하고…",
  "answeredCount": 3,
  "questionCount": 5
}
```

**`covered`와 `missed`는 겹치지 않고, 합치면 항상 `outline` 전체다.** 서버가 `covered`의
여집합으로 `missed`를 계산하므로 이 성질이 계산에서 따라 나온다 — 화면은 이걸 믿고 뼈대 옆에
✅/❌를 붙이면 된다.

**`transcript`가 비어 있는 것은 오류가 아니다.** 제한 시간 안에 한마디도 못 한 경우가 정상
경로이고 그 자체가 결과다. `answered: false`, `score: 0`, `covered: []`로 기록되며
**LLM을 부르지 않고 한도도 소비하지 않는다** — 마이크가 안 잡힌 사용자가 자기 한도를 스스로
태우면 안 된다.

**같은 질문에 다시 제출하면 덮어쓴다.** 마이크가 안 잡혔을 때의 재시도 경로다. 이전 채점 결과는
함께 지워진다 — 답이 바뀌었는데 점수만 남으면 둘이 어긋난다. `answeredCount`는 늘지 않는다.

**에러**

| 상태 | 상황 |
| --- | --- |
| `400` | `questionId` 누락, `transcript` 10,000자 초과, **이미 종료된 세션** |
| `404` | 세션이 없거나 **내 것이 아님**, 이 세션에 출제되지 않은 질문 |
| `429` | LLM 호출 한도 (`Retry-After` 포함) 또는 전역 일일 비용 상한 |
| `500` | `ANTHROPIC_API_KEY` 미설정 (`"답변 채점 기능이 아직 설정되지 않았습니다."`) |

---

### `POST /api/interviews/{sessionId}/finish` — 종료 · 총점 확정 ✅

본문 없음.

**응답 `200`**

```jsonc
{
  "sessionId": "uuid",
  "totalScore": 46,
  "answeredCount": 3,
  "questionCount": 5,
  "finishedAt": "2026-08-07T10:22:11Z"
}
```

**총점은 출제된 전 문항의 평균이다.** 답한 것만 평균 내면 한 문항만 답하고 나가는 쪽이
유리해지므로, **미답변은 0점으로 센다.**

**멱등이다.** 이미 닫힌 세션에 다시 불러도 같은 결과를 준다 — 마지막 문항 제출과 종료가
겹치거나 사용자가 새로고침하는 것은 정상 경로라 오류로 만들 이유가 없다.

**에러**: `404` 세션이 없거나 내 것이 아님.

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
- 갭 분석 (§4.3) · 리라이트 (§4.4). 목록의 `gapSummary`가 채워지는 것도 이때다
- 이력 **상세**·메모 수정 — `JdSubmissionService.getOwned`/`updateMemo`는 있고 경로가 없다.
  화면(§4.6)이 아직 목록만 쓰므로 계약을 먼저 만들지 않았다
- 인증 경로 (가입 · 로그인 · 비밀번호 재설정) — 서비스는 구현되어 있고 컨트롤러만 없다
