# 엔드포인트 명세

**이 문서가 `jobit-front`와의 계약 원본이다.** 엔드포인트를 바꾸면 여기부터 고치고 프론트를 맞춘다.

구현 상태: `POST /api/jd/parse` ✅ / `GET /api/questions` (SSE) ✅ / `GET /api/stats/stacks` ✅ /
`GET·DELETE /api/submissions` ✅ / `POST /api/submissions/claim` ✅ /
면접 연습 6종 ✅ (`POST /api/interviews` · `.../answers` · `.../finish` ·
`GET /api/interviews` · `GET·DELETE /api/interviews/{id}` · `POST /api/interviews/claim`) /
이력서 5종 ✅ (`POST /api/resumes` · `GET /api/resumes` · `GET·DELETE /api/resumes/{id}` ·
`POST /api/resumes/claim`) / 갭 분석 2종 ✅ (`POST·GET /api/gap-analyses`) /
리라이트 2종 ✅ (`POST /api/gap-items/{id}/rewrite` · `PATCH /api/rewrite-suggestions/{id}`) /
영상 요약 6종 ✅ (`POST·GET·DELETE /api/video-summaries` · `…/qna` · `…/frame/{t}`)

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

  **형식 검증만으로는 사칭을 막지 못하므로 이 값에 서명을 요구한다** (2026-08-07):

  ```
  X-Owner-Auth: v1.<만료 epoch 초>.<base64url HMAC-SHA256>
  ```

  서명 대상은 `"v1." + owner_key + "." + exp` 다. `owner_key` 와 만료가 서명 안에 들어 있어
  헤더만 바꿔치기하거나 만료를 늘릴 수 없다. **`owner_key` 가 없는 요청(공개 통계)도 서명한다** —
  예외를 두면 그 경로가 뒷문이 된다. 실패는 이유를 구분하지 않고 `401` 이다.

  비밀키는 두 레포가 같은 값을 쓴다 (`jobit.auth.service-secret` / `JOBIT_SERVICE_SECRET`).
  자세한 근거는 `architecture.md` "호출자 인증".
- **레이트 리밋**: 세션당 LLM 호출 횟수 제한 (스펙 §6). 초과 시 `429` + `Retry-After` 헤더(초).
  **캐시로 처리되는 요청은 한도를 소비하지 않는다** — LLM을 부르지 않았기 때문이다.
- 상태 코드: `400` 입력 오류 / `401` 호출자 인증 실패 / `403` 미사용 / `404` 없음 /
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
| `500` | `ollama.base-url` 미설정 등 서버 설정 문제 |

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

> ⚠️ **서명은 `X-Owner-Key`(받는 쪽)만 보증한다.** `fromOwnerKey` 는 본문이라 서명 대상이
> 아니다 — 프론트를 통과할 수 있는 호출자라면 남의 익명 세션 쿠키를 알 때 그 기록을 가져올 수
> 있다. 익명 키는 프론트가 쿠키에서 직접 읽어 넘기므로 실제로 알아내기는 어렵지만,
> 구조적으로 남아 있는 구멍이다.

---

## 이력서 (스펙 §3.3) ✅

**파일이 아니라 텍스트를 받는다.** 면접 연습이 오디오를 받지 않는 것과 같은 판단이다 — 파일을
받으면 PDF·DOCX 파서, 업로드 상한, 임시 파일 정리까지 전부 개인정보 관리 대상이 된다.
붙여넣기는 브라우저가 이미 잘한다.

**어느 응답에도 이력서 원문이 없다.** 누락이 아니라 규약이다. 원문은 AES-256-GCM 으로 암호화해
저장하고(`resume.raw_text`), 화면이 필요로 하는 것은 분해된 문장 목록이다. 원문을 내려 주는
순간 암호화는 "DB 를 직접 본 사람만 막는" 장치로 격하되고 응답 로그·프록시·브라우저 히스토리에
평문이 남는다. **복호화 경로는 리라이트가 들어와서도 열리지 않았다** (2026-08-14) — 리라이트가
문장 단위(작업 원칙)라 고칠 문장은 평문인 `resume_bullet.text` 로 충분하다. `TextCipher.decrypt`
는 여전히 테스트 외 호출부가 없고, 그게 정상이다.

모두 `X-Owner-Key` 필수다. JD 파싱은 선택이었지만(결과가 공용 자산이라 익명으로도 의미가 있다)
이력서는 처음부터 끝까지 개인 자산이라 소유자 없이 할 수 있는 일이 없다.

> **보관 기간이 있다.** 기본 90일(`jobit.resume.ttl-days`)이며 지나면 문장·벡터까지 통째로
> 지운다. 면접 답변이 원문만 비우고 점수를 남긴 것과 다른데, 이력서는 문장 자체가 내용의
> 전부라 원문을 지우고 남길 것이 없기 때문이다.

### `POST /api/resumes` — 업로드 · 문장 분해 · 임베딩 ✅

```jsonc
{ "text": "이력서 본문" }   // 필수, 50~50,000자
```

**응답 `200`**

```jsonc
{
  "resumeId": "uuid",
  "bulletCount": 12,                        // 몇 문장으로 나뉘었는가
  "expiresAt": "2026-11-07T00:00:00Z"
}
```

**느리다.** LLM 분해가 수십 초 걸리므로 프론트는 로딩 상태를 반드시 보여 줘야 한다. 질문 생성과
달리 스트리밍하지 않는데, 문장 목록은 **전부 모여야** 의미가 있고(임베딩이 뒤따른다) 중간 결과를
보여 줄 화면도 없기 때문이다.

**`bulletCount`를 프론트가 봐야 하는 이유**: 분해 결과가 기대와 다르면(예: 1문장으로 뭉쳤다)
사용자가 즉시 알아채고 다시 올릴 수 있어야 한다.

**캐시가 없다.** JD 는 `content_hash` 로 전역 재사용하지만 이력서는 개인 자산이라 공유할 수 없고,
같은 사람이 같은 이력서를 다시 올리는 것은 대개 **내용을 고쳤기 때문**이라 재사용이 오히려 틀린
동작이다. 그래서 레이트 리밋을 조건 없이 소비한다.

**에러**

| 상태 | 상황 |
| --- | --- |
| `400` | `text` 누락·길이 위반, `X-Owner-Key` 누락·형식 위반 |
| `429` | 소유자별 시간당 한도 (`Retry-After` 포함) 또는 전역 일일 비용 상한 |
| `502` | LLM 장애, 또는 재검증 3회 실패 |
| `500` | `ollama.base-url` / `jobit.resume.encryption-key` 미설정 |

> **`500` 문구가 셋 다 같다** (`"이력서 분석 기능이 아직 설정되지 않았습니다."`). 사용자에게
> "임베딩 키가 없다"는 말은 아무 의미가 없고, 어느 키가 빠졌는지는 운영자가 로그에서 볼 일이다.

> ⚠️ **암호화 키가 없으면 평문으로 저장하지 않고 거부한다.** 호출자 인증이 키 없이도 꺼진 채
> 동작하는 것과 반대 결정인데, 인증이 꺼진 것은 로그를 보면 알지만 평문으로 저장된 이력서는
> 나중에 되돌릴 방법이 없기 때문이다.

---

### `GET /api/resumes` — 내 이력서 목록 ✅

**응답 `200`**

```jsonc
{
  "items": [
    { "resumeId": "uuid", "bulletCount": 12,
      "createdAt": "2026-08-09T00:00:00Z", "expiresAt": "2026-11-07T00:00:00Z" }
  ]
}
```

페이지가 없다. 한 사람이 가진 이력서가 수십 개가 되는 상황을 상정하기 어렵고, 제출 이력과 달리
자동으로 쌓이지 않는다 — 올릴 때마다 사용자가 직접 붙여넣는다.

---

### `GET /api/resumes/{resumeId}` — 분해된 문장 목록 ✅

**응답 `200`**

```jsonc
{
  "resumeId": "uuid",
  "embeddedCount": 12,                      // 벡터가 채워진 문장 수
  "createdAt": "2026-08-09T00:00:00Z",
  "expiresAt": "2026-11-07T00:00:00Z",
  "bullets": [
    { "bulletId": "uuid", "text": "결제 서버 개발", "company": "토스",
      "period": "2022.03 ~ 2024.08", "sortOrder": 0 }
  ]
}
```

**`embeddedCount`를 노출하는 이유는 갭 분석 가능 여부가 이 값에 달려 있기 때문**이다. 0이면
유사도 검색이 후보를 하나도 찾지 못한다. 정상이면 `bullets.length` 와 같다 — 업로드가 한
트랜잭션에서 문장과 벡터를 함께 넣으므로 반쪽 상태가 생기지 않는다.

**`period` 는 이력서에 적힌 표기 그대로다.** 날짜로 파싱하지 않는다 — 표기가 제각각이라
정규화하려다 틀리느니 원문을 그대로 보여 주는 편이 낫다.

**에러**: `404` 없거나 **내 것이 아님**.

---

### `DELETE /api/resumes/{resumeId}` — 삭제 ✅

```
→ 204 No Content
```

**문장과 벡터가 함께 사라진다** (`on delete cascade`). 제출 이력이 `job_posting` 을 남겼던 것과
다른데, 공고는 `content_hash` 전역 캐시라 지우면 남의 캐시 적중이 깨지지만 이력서는 처음부터
끝까지 이 사람의 것이기 때문이다.

**에러**: `404` 없거나 내 것이 아님.

---

### `POST /api/resumes/claim` — 익명 이력서를 계정으로 승계 ✅

```jsonc
// 헤더: X-Owner-Key — 받는 쪽. user: 여야 한다
{ "fromOwnerKey": "anon:<세션 쿠키>" }
```

**응답 `200`**: `{ "moved": 2 }`

`/api/submissions/claim` 과 같은 규약이고 방향도 같이 강제한다. **제출 이력·면접 기록과 달리
충돌 처리가 없다** — 같은 사람이 이력서를 여러 개 갖는 것이 정상이라 유니크 제약 자체가 없다.

---

## 갭 분석 (스펙 §4.3, §4.5) ✅

이력서 × 공고 조합마다 요구사항별로 `MET`(충족) / `WEAK`(약함) / `MISSING`(근거 없음)을 판정한다.
2단계 구조다 — 임베딩이 요구사항마다 이력서 문장 후보 3개를 추리고, LLM 은 판정만 한다.

모두 `X-Owner-Key` 필수. **이력서가 내 것이어야 한다** — 남의 것이면 404 다. 공고는 전역 캐시
자산이라 소유자 검사가 없다.

### `POST /api/gap-analyses` — 분석 실행 (또는 기존 결과 반환) ✅

```jsonc
{ "resumeId": "uuid", "jobPostingId": "uuid" }   // 둘 다 필수
```

**응답 `200`**

```jsonc
{
  "gapAnalysisId": "uuid",
  "cached": false,                    // true 면 LLM 을 부르지 않고 기존 결과를 돌려준 것
  "createdAt": "2026-08-14T00:00:00Z",
  "summary": { "met": 8, "weak": 3, "missing": 2 },   // 제출 이력의 gapSummary 와 같은 형태
  "items": [                          // 요구사항 순서 (공고에 나온 순서)
    {
      "gapItemId": "uuid",            // 리라이트 진입점 — POST /api/gap-items/{gapItemId}/rewrite
      "requirementId": "uuid",
      "requirementText": "RDBMS 스키마 설계와 쿼리 튜닝 경험",
      "kind": "REQUIRED",             // REQUIRED | PREFERRED | RESPONSIBILITY
      "status": "MET",                // MET | WEAK | MISSING
      "evidence": {                   // 근거가 된 이력서 문장. MISSING 이면 null
        "bulletId": "uuid",
        "text": "정산 테이블 인덱스 재설계로 배치 시간을 40% 단축"
      },
      "rationale": "스키마 설계와 튜닝 경험이 수치와 함께 드러난다"
    }
  ]
}
```

**느리다.** 판정이 요구사항 수만큼 반복되어 **몇 분**이 걸릴 수 있다 — 프론트는 로딩 상태를
반드시 보여 줘야 한다. 캐시 적중이면 즉시 돌아온다.

**같은 조합은 재분석하지 않는다** (`(resumeId, jobPostingId)` 유니크). 이력서를 고쳤다면 새로
올리면 되고, 새 이력서는 새 `resumeId` 라 캐시 키가 자연히 갈린다 — 이력서에 수정 개념이 없는
것이 캐시 무효화 문제를 없앤다.

**`MISSING` 은 `evidence` 가 `null` 이다.** 지어내지 않는다 (스펙 §4.5) — 화면은 "충족 근거가
없습니다. 면접에서 물어볼 가능성이 높으니 인접 경험으로 준비하세요"를 안내하고 질문 생성으로
넘긴다.

**공고 메타(회사·직함)는 싣지 않는다.** 이 화면에 오기 전에 파싱 응답이나 제출 이력에서 이미
알고 있는 값이다.

**에러**

| 상태 | 상황 |
| --- | --- |
| `400` | `resumeId`/`jobPostingId` 누락, `X-Owner-Key` 누락·형식 위반 |
| `404` | 이력서가 없거나 남의 것, 공고가 없음 |
| `409` | 이력서에 임베딩이 없음 (V11 이전 업로드) — **다시 올려야 한다**. 문구가 그걸 안내한다 |
| `429` | 소유자별 시간당 한도 (`Retry-After` 포함). 분석 1건 = 한도 1회 소비, 캐시 적중은 소비 없음 |
| `502` | LLM 장애 |
| `500` | `ollama.base-url` 미설정 (`"갭 분석 기능이 아직 설정되지 않았습니다."`) |

### `GET /api/gap-analyses?resumeId=...&jobPostingId=...` — 캐시된 결과 조회 ✅

**응답 `200`**: POST 와 같은 형태 (`cached` 는 항상 `true`).

**없으면 `404` — 분석을 시작하지 않는다.** GET 이 분석까지 해 버리면 재방문 화면을 그리려던
프론트가 의도치 않게 몇 분짜리 LLM 경로를 태우고 한도까지 소비한다. 시작은 언제나 명시적인
POST 다. 프론트는 404 를 "아직 분석 안 함"으로 읽고 분석 버튼을 보여 준다.

---

## 리라이트 (스펙 §4.4, §4.5) ✅

`WEAK` 판정을 받은 항목의 근거 문장 하나를 요구사항 관점에서 고쳐 쓴다. **문장 단위다** —
이력서 전체를 LLM 에 보내지 않는다 (작업 원칙). 모두 `X-Owner-Key` 필수.

**서버는 수정안과 채택 표시만 관리한다.** 문장을 실제로 바꾸는 것은 사용자가 자기 이력서에서
할 일이다 — 이력서에 수정 개념이 없으므로(고치면 새로 올린다) 서버가 문장을 덮어쓸 자리가 없다.

### `POST /api/gap-items/{gapItemId}/rewrite` — 수정안 생성 (또는 기존 제안 반환) ✅

**응답 `200`**

```jsonc
{
  "suggestionId": "uuid",
  "gapItemId": "uuid",
  "bulletId": "uuid",
  "original": "정산 배치를 운영했습니다.",
  "suggested": "[배치 규모]건 규모의 정산 배치 파이프라인을 운영하며 장애 시 재처리를 담당했습니다.",
  "reason": "운영 규모와 역할이 드러나도록 정리했다. 수치는 자리 표시로 남겼다.",
  "accepted": false,
  "cached": false                  // true 면 LLM 을 부르지 않고 기존 제안을 돌려준 것
}
```

**느리다.** thinking 을 켜는 기능이라(문장 품질이 곧 제품 가치) **수십 초** 걸린다 — 프론트는
로딩 상태를 반드시 보여 줘야 한다. 제안은 `gap_item` 당 하나이며(V12 유니크) 두 번째 호출부터는
즉시 돌아온다.

**`suggested` 의 대괄호는 자리 표시다.** `[배치 규모]` 처럼 **지원자가 채워야 할 값의 이름**이
들어 있다. 모델이 수치를 지어내는 대신 빈자리를 남기는 것이 규약이고, **서버가 재검증한다** —
수정안의 모든 숫자는 원문·요구사항에 이미 있었거나 자리 표시 안에 있어야 하며, 어기면 재시도
후 502 다. 프론트는 자리 표시를 시각적으로 구분해 입력을 유도하면 좋다.

**에러**

| 상태 | 상황 |
| --- | --- |
| `400` | WEAK 가 아닌 항목 (MET: "이미 충족", MISSING: "근거 문장이 없음" — 문구가 구분된다), `X-Owner-Key` 누락·형식 위반 |
| `404` | 항목이 없거나 남의 것 |
| `429` | 소유자별 시간당 한도 (`Retry-After` 포함). 캐시 적중은 소비 없음 |
| `502` | LLM 장애, 또는 재검증 2회 실패 |
| `500` | `ollama.base-url` 미설정 (`"리라이트 기능이 아직 설정되지 않았습니다."`) |

### `PATCH /api/rewrite-suggestions/{suggestionId}` — 채택 여부 기록 ✅

```jsonc
{ "accepted": true }   // 필수. false 로 철회할 수 있다
```

**응답 `200`**: POST 와 같은 형태.

`accepted` 는 단순 플래그가 아니라 **품질 지표**다 (스펙 §3.4) — 어떤 수정안이 실제로
채택되는지가 프롬프트 개선의 근거가 된다. 프론트는 "내 이력서에 반영했어요" 같은 명시적 행동에
붙인다.

**에러**: `400` 본문/헤더 오류 · `404` 제안이 없거나 남의 것.

---

## 영상 요약 (스펙 외 새 축) ✅

유튜브 URL 을 받아 자막(없으면 Whisper STT)을 추출하고, 로컬 LLM 이 보고서(한 줄 요약 ·
개요 · 타임스탬프 섹션 · 핵심 정리)로 정리한다. `video_id` 기준 **전역 캐시**다 — 같은 영상을
두 사람이 넣으면 한 번만 처리한다 (공고와 같은 구조).

**처음으로 동기 응답이 불가능한 기능이다.** 자막 영상도 수 분, STT 는 수십 분 —
POST 는 접수만 하고 바로 돌아오며, 프론트는 GET 으로 **폴링**한다.
상태 기계: `PENDING → RUNNING → DONE | FAILED | REJECTED`.

**주제 게이트가 있다.** 면접·취업·커리어·개발 기술 학습과 무관한 영상(음악·게임·브이로그 등)은
LLM 판정으로 걸러 `REJECTED` 가 된다 — `errorMessage` 에 판정 근거가 실린다. 자막 없는 영상은
메타데이터 판정을 **STT 전에** 한 번 더 거친다 (무관 영상에 수십 분 전사를 태우지 않는다).
REJECTED 영상을 다시 넣으면 재판정한다 (판정이 틀렸을 때의 항의 수단이고, 한도를 소비한다).

### `POST /api/video-summaries` — 요약 요청 (접수만) ✅

```jsonc
// 헤더: X-Owner-Key (필수)
{ "url": "https://www.youtube.com/watch?v=..." }   // youtu.be·shorts·embed·live 형태도 받는다
```

**응답 `200`** — 아래 GET 과 같은 형태. 이미 요약된 영상이면 `status: "DONE"` 과 보고서가
바로 온다. FAILED 상태의 영상을 다시 넣으면 **재시도로 되살린다** (이때만 한도를 소비한다 —
DONE·진행 중 재요청은 소비하지 않는다).

**에러**: `400` 유튜브 주소 아님 · 헤더 누락 / `429` 한도.

**길이 제한이 있다** (`jobit.video.max-duration-sec`, 기본 2시간). 넘는 영상은 접수는 되지만
probe 직후 `REJECTED` 가 된다 — 자막 다운로드·STT 에 들어가기 전에 끊고, `errorMessage` 가
실제 길이와 상한을 말한다. 길이를 모르는 영상(라이브 등)은 통과하고 청크 상한(약 4시간)이
최후 방어선이다.

### `GET /api/video-summaries/{summaryId}` — 상태 폴링 + 보고서 ✅

**`X-Owner-Key` 가 없다** — 보고서는 공유 링크가 목적인 전역 캐시 자산이라(공고와 같다)
UUID 를 아는 사람은 읽는다. 서명(`X-Owner-Auth`)은 여전히 필수다.

```jsonc
{
  "summaryId": "uuid",
  "videoId": "dQw4w9WgXcQ",
  "url": "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
  "title": "영상 제목", "channel": "채널명", "durationSec": 212,
  "status": "DONE",              // PENDING | RUNNING | DONE | FAILED | REJECTED
  "source": "CAPTION",           // CAPTION | STT. 완료 전엔 null
  "errorMessage": null,          // FAILED·REJECTED 일 때만. 그대로 화면에 띄워도 되는 문구
  "report": {                    // DONE 일 때만
    "oneLine": "…",
    "overview": "…",
    "sections": [ { "heading": "…", "startSec": 130, "summary": "…" } ],
    "takeaways": ["…"]
  },
  "capturedFrames": [0, 130],    // 캡처가 존재하는 섹션 시각 — 이미지 영역을 그릴지 판단
  "createdAt": "2026-08-20T00:00:00Z"
}
```

**`sections[].startSec` 은 null 일 수 있다** — 모델이 확신하지 못한 좌표는 지어내는 대신
비운다. 있으면 `https://youtu.be/<videoId>?t=<startSec>` 딥링크로 쓴다.

**폴링 간격은 5초면 충분하다.** 자막 영상은 수 분, STT 는 수십 분 걸린다 — 화면이 그 사실을
말해야 한다 (source 가 아직 null 이면 어느 쪽인지 모르는 단계다).

### `POST /api/video-summaries/{summaryId}/qna` — 영상 내용 질문 ✅

3분할 화면의 우측 채팅. **검색된 자막 발췌만이 근거다** — 영상에 없는 내용은 "찾지 못했다"고
답한다 (일반 지식으로 메꾸지 않는다). 질문 하나 = GPU 추론 하나라 소유자 한도를 소비한다.

```jsonc
// 헤더: X-Owner-Key (필수)
{ "question": "이 사람은 어떤 회사에 합격했어?", "history": ["질문: …", "답변: …"] }  // history 선택, 최근 6개만 반영
```

**응답 `200`**: `{ "answer": "…", "refs": [332] }` — `refs` 는 근거 구간의 시각(초)으로,
서버가 발췌 목록과 대조해 재검증한 값만 온다 (플레이어 시킹 버튼이 된다).

**에러**: `409` 요약 미완/질문 기능 없음(V16 이전 요약 — 재요약하면 생긴다) · `429` 한도 ·
`502` LLM 장애.

### `GET /api/video-summaries/{summaryId}/frame/{startSec}` — 섹션 캡처 이미지 ✅

`image/jpeg`, 7일 캐시 헤더. 캡처가 없으면 `404` — 화면은 `capturedFrames` 로 미리 알 수 있다.
소유자 없이 읽는다 (보고서와 같은 공유 자산).

### `GET /api/video-summaries` — 내 요약 목록 ✅

`X-Owner-Key` 필수. `{ "items": [ { summaryId, videoId, title, channel, durationSec, status, submittedAt } ] }` 최근순.

### `DELETE /api/video-summaries/{summaryId}` — 내 이력에서 삭제 ✅

`X-Owner-Key` 필수, `204`. **지우는 것은 내 이력 한 줄뿐**이고 요약은 전역 캐시라 남는다
(제출 이력 삭제와 같은 규약). `404` 없거나 내 이력이 아님.

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
| `500` | `ollama.base-url` 미설정 (`"답변 채점 기능이 아직 설정되지 않았습니다."`) |

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

### `GET /api/interviews` — 내 면접 기록 목록 ✅

```
GET /api/interviews?page=0&size=20
헤더: X-Owner-Key (필수)
```

**응답 `200`**

```jsonc
{
  "items": [
    {
      "sessionId": "uuid",
      "jobPostingId": "uuid",
      "company": "토스",
      "title": "백엔드 개발자",
      "totalScore": 72,        // 종료 전이면 null
      "answeredCount": 4,
      "questionCount": 5,
      "startedAt": "2026-08-07T09:30:00Z",
      "finishedAt": "2026-08-07T09:42:11Z"   // null이면 중간에 이탈했거나 진행 중
    }
  ],
  "page": 0, "size": 20, "totalElements": 3, "totalPages": 1
}
```

**`totalScore`가 `null`인 것과 `0`인 것은 다르다.** 전자는 "아직 안 끝냈다", 후자는 "끝냈는데
한 문항도 못 짚었다"이다. 제출 이력의 `gapSummary`와 같은 규약이다.

**집계 쿼리가 없다.** 총점·답변 수가 이미 세션 행에 있어 한 번의 조회로 끝난다 —
`/api/submissions`가 세 종류의 집계를 배치로 모으는 것과 대조적이다.

---

### `GET /api/interviews/{sessionId}` — 세션 상세 ✅

**응답 `200`** (목록 필드 + `questions` + `answers`)

```jsonc
{
  "sessionId": "uuid", "company": "토스", "totalScore": 72, …,

  // 이 세션에 출제된 문항. **답변 뼈대가 없다** — 뼈대는 아래 answers 안에만 있다.
  // 연습 화면이 새로고침·새 탭에서 이어서 하려면 이 목록이 필요하다.
  "questions": [
    { "questionId": "uuid", "text": "…", "category": "CS",
      "difficulty": 3, "timeLimitSec": 90 }
  ],

  "answers": [
    {
      "questionId": "uuid",
      "questionText": "Kafka consumer에서 중복 처리와 순서 보장을…",
      "category": "DESIGN",
      "difficulty": 4,
      "answered": true,
      "transcript": "네 카프카에서 중복 처리랑…",   // null 일 수 있다 (아래)
      "score": 52,
      "outline": ["consumer 멱등성 확보 수단…", "at-least-once 전제와…"],
      "covered": [0, 3],
      "missed": [1, 2],
      "feedback": "파티션 키를 통한 순서 보장과…",
      "durationMs": 47000,
      "timeLimitSec": 90
    }
  ]
}
```

**`transcript`가 `null`인 경우가 둘이고, `answered`가 그 둘을 가른다.**

| `answered` | `transcript` | 뜻 |
| --- | --- | --- |
| `false` | `null` | 제한 시간 안에 답하지 못했다 |
| `true` | `null` | **TTL이 지나 발화 원문만 지웠다.** 점수·피드백은 남는다 |
| `true` | 있음 | 정상 |

> `answered`는 **저장된 컬럼이지 `transcript`에서 파생된 값이 아니다.** 파생시키면 원문을
> 지우는 순간 점수를 받은 답변이 무응답으로 바뀐다 — 개인정보 삭제는 내용을 지우는 것이지
> 사실을 지우는 것이 아니다 (V10).

**`timeLimitSec`은 그때의 값이다.** 설정을 바꿔도 과거 기록의 의미가 변하지 않는다.

**에러**: `404` 없거나 내 것이 아님.

---

### `DELETE /api/interviews/{sessionId}` — 기록 삭제 ✅

```
→ 204 No Content
```

**답변은 함께 지워지고 질문·공고는 남는다.** 둘 다 공유 자산이고, 특히 공고는 `content_hash`
전역 캐시라 지우면 남의 캐시 적중까지 깨진다.

**에러**: `404` 없거나 내 것이 아님.

---

### `POST /api/interviews/claim` — 익명 연습 기록을 계정으로 승계 ✅

```jsonc
// 헤더: X-Owner-Key — 받는 쪽. user: 여야 한다
{ "fromOwnerKey": "anon:<세션 쿠키>" }
```

**응답 `200`**: `{ "moved": 2 }`

`/api/submissions/claim`과 같은 규약이고 방향도 같이 강제한다. **엔드포인트를 따로 두는 이유는
자원이 다르기 때문**이고, 프론트는 로그인 직후 제출 이력·프로필과 함께 이것도 부른다
(이미 두 개를 따로 부르고 있다).

**제출 이력과 달리 충돌 처리가 없다.** 그쪽은 `(owner_key, job_posting_id)` 유니크 제약 때문에
양쪽에 같은 공고가 있으면 익명 쪽을 버려야 하지만, 세션은 같은 공고로 몇 번이든 연습할 수 있어
제약 자체가 없다.

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

- 이력 **상세**·메모 수정 — `JdSubmissionService.getOwned`/`updateMemo`는 있고 경로가 없다.
  화면(§4.6)이 아직 목록만 쓰므로 계약을 먼저 만들지 않았다
- 인증 경로 (가입 · 로그인 · 비밀번호 재설정) — 서비스는 구현되어 있고 컨트롤러만 없다
