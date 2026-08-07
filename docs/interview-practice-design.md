# 면접 연습 (음성) — 아키텍처 설계

분석한 공고의 예상 질문을 **소리 내어 답하고, 답변 뼈대와 얼마나 맞는지 점수로 확인**하는 기능.
2026-08-07 설계.

> **이 문서는 스펙(`jd-interview-prep-spec.md`)에 없는 새 축이다.** 스펙 §5 로드맵은
> 1 질문 생성 → 2 공유 → 3 이력서·갭 분석 → 4 리라이트 → 5 RAG 순인데, 면접 연습은 그 어디에도
> 없다. 스펙을 고치지 않고 이 문서를 따로 두는 이유는 §7 "열린 결정"과 달리 이건 **제품 범위의
> 추가**라서, 스펙 본문에 섞으면 원래 계획과 나중에 붙인 것이 구분되지 않기 때문이다.

## 1. 설계의 중심 — 채점 기준을 새로 만들지 않는다

이 기능의 핵심 질문은 "무엇을 기준으로 점수를 매기는가"다. **이미 있다.**

`question.answer_outline`(답변 뼈대 = 핵심 포인트 목록)이 질문 생성 때부터 모든 질문에
붙어 있다 (스펙 §3.2). 사용자가 결과 화면에서 본 그 뼈대가 그대로 채점 기준이 된다.

```
requirement ─┬→ question ─┬→ answer_outline ─→ [채점 기준]   ← 여기
             │            └→ followups
             └→ gap_item
```

이게 중요한 이유는 셋이다.

- **모범 답안 생성을 위한 LLM 호출이 필요 없다.** 질문 생성이 이미 만들어 뒀다.
- **사용자가 본 것과 채점 기준이 같다.** 화면엔 A를 보여주고 B로 채점하면 점수를 납득할 수 없다.
- **`requirement`가 공통 앵커라는 설계 중심이 유지된다** (`CLAUDE.md` "핵심 구조"). 면접 연습도
  결국 요구사항에서 파생된 질문을 다루므로 새 축이 아니라 기존 축의 연장이다.

따라서 **면접 연습은 질문 생성이 끝난 공고에서만 시작할 수 있다.** 이건 제약이 아니라 진입점이다 —
`/interview`는 내 기록에서 공고를 고르는 화면이 된다.

## 2. 경계 — 무엇이 어디서 도는가

```
브라우저                          jobit-front                jobit (Spring)
─────────────────────────        ────────────────           ──────────────────
마이크 권한
SpeechRecognition (STT)  ──┐
타이머 (제한 시간)          │
                          └─ transcript(텍스트)만 ──→ POST /api/interviews/{id}/answers
                                                          │
                                                          ├─ answer_outline 로 채점 (LLM)
                                                          └─ interview_answer 저장
```

**오디오는 브라우저 밖으로 나가지 않는다.** 이 한 줄이 이 설계의 개인정보 대책 전부다.
서버는 오디오를 받지도, 저장하지도, 지울 필요도 없다.

- **STT 는 브라우저가 한다** — Web Speech API (`SpeechRecognition`). 비용 0, 서버 코드 0.
- **`MediaRecorder` 를 쓰지 않는다.** 음성을 보관하지 않기로 했으므로 오디오 버퍼를 우리가
  다룰 이유가 없다. `SpeechRecognition` 은 권한만 받고 텍스트만 돌려준다.
- **백엔드는 텍스트만 안다.** 기존 경계(`CLAUDE.md`: 도메인 로직·LLM·영속성은 이쪽)와 일치한다.

> ⚠️ **사용자에게 고지해야 할 것.** Chrome 의 `SpeechRecognition` 은 브라우저 구현상
> **오디오를 구글 서버로 보내 인식한다.** "우리 서버에 저장하지 않는다"는 사실이지만
> "음성이 아무 데도 안 간다"는 사실이 아니다. 마이크를 켜기 전 화면에 이 문장을 그대로 둔다.
> 이걸 숨기면 개발자 타깃 제품에서 신뢰를 잃는다 (스펙 §6 개인정보의 취지).

> **Firefox 는 `SpeechRecognition` 이 없다.** 지원 여부를 먼저 확인해
> **없으면 텍스트 입력으로 떨어뜨린다.** 채점 경로는 transcript 만 보므로 입력 수단이 무엇이든
> 똑같이 동작한다 — 마이크는 입력기일 뿐 기능의 본체가 아니다.

## 3. 데이터 모델 (Flyway V9)

```sql
interview_session (
  id               uuid pk,
  owner_key        text not null,          -- user:<id> | anon:<쿠키>
  job_posting_id   uuid not null fk,
  question_set_id  uuid not null fk,       -- 어느 세트로 봤는지. prompt_version 이 여기 걸린다
  question_count   smallint not null,      -- 이 세션에 출제된 문항 수
  answered_count   smallint not null default 0,
  total_score      smallint null,          -- 0~100. 종료 전이면 null
  started_at       timestamptz not null default now(),
  finished_at      timestamptz null,       -- null = 진행 중이거나 중단됨
  created_at       timestamptz not null default now()
)
create index idx_interview_session_owner on interview_session (owner_key, started_at desc);

interview_answer (
  id               uuid pk,
  session_id       uuid not null fk on delete cascade,
  question_id      uuid not null fk,
  sort_order       smallint not null,      -- 출제 순서 = 표시 순서
  transcript       text null,              -- STT 결과. null = 시간 내 답하지 못함
  duration_ms      int not null,
  time_limit_sec   smallint not null,      -- 그때의 제한 시간. 설정이 바뀌어도 기록은 그대로다
  score            smallint null,          -- 0~100. 채점 전이면 null
  covered          jsonb null,             -- answer_outline 중 짚은 포인트 인덱스 [0,2]
  missed           jsonb null,             -- 놓친 포인트 인덱스 [1,3]
  feedback         text null,              -- 한 줄 피드백
  scored_at        timestamptz null,
  transcript_expires_at timestamptz null,  -- §7 개인정보
  created_at       timestamptz not null default now(),
  unique (session_id, question_id)
)
```

**설계 근거**

- **`question_set_id` 를 세션에 박는다.** 프롬프트 버전이 올라가면 질문이 바뀌는데, 기록에는
  "그때 그 질문"이 남아야 한다. `job_posting_id` 만으로는 나중에 다른 질문이 딸려 온다.
- **`covered`/`missed` 는 텍스트가 아니라 `answer_outline` 의 인덱스다.** 뼈대 문구를 복사해
  두면 같은 문장이 두 군데 살고, 화면은 어차피 뼈대를 나란히 보여줘야 하므로 인덱스면 충분하다.
- **`time_limit_sec` 를 행마다 남긴다.** 설정을 60초에서 90초로 바꿔도 과거 기록의 의미가
  변하지 않아야 한다. 설정값을 참조만 하면 과거 점수의 근거가 소급해서 바뀐다.
- **`transcript` 는 nullable.** 시간 내에 한마디도 못 한 경우가 정상 경로다 — 그 자체가 결과다.
- **`unique (session_id, question_id)`** — 한 세션에서 한 질문은 한 번. 마이크가 안 잡혔을 때
  다시 제출하면 덮어쓴다(upsert). 이게 없으면 같은 질문의 점수가 여러 개 남아 총점이 흔들린다.
- **`on delete cascade`** — 기록 삭제는 세션 단위다. 답변만 남으면 고아가 된다.

**세션에 담을 문항은 `question_set` 의 `sort_order` 앞에서부터 N개**로 시작한다. 모델이
난이도·카테고리를 섞어 배치한 순서가 이미 있으므로(스펙 §3.2) 따로 고르지 않는다.
매번 같은 문제가 지루해지는 문제는 실제로 재연습이 일어나는 것을 본 뒤에 다룬다.

## 4. 흐름

```
1. /interview            내 기록에서 공고 선택
                         → 질문이 아직 없는 공고는 "질문 먼저 만들기"로 유도
2. POST /api/interviews  세션 생성. 출제할 질문 N개를 함께 내려준다
3. 화면에서 문항 1개씩
     · 질문 + 제한 시간 표시 (뼈대는 감춘다 — 보고 답하면 연습이 아니다)
     · 마이크 시작 → interim transcript 실시간 표시 → 제한 시간 도달 시 자동 정지
4. POST .../answers      transcript 제출 → 즉시 채점 → 점수·짚은 포인트·놓친 포인트 반환
     · 여기서 비로소 answer_outline 을 펼쳐 보여준다
5. 마지막 문항 후
   POST .../finish       총점 계산 후 세션 종료
6. /profile/interviews   기록 목록 · 상세
```

**왜 문항마다 즉시 채점하고 끝에 몰아서 하지 않는가.** 답하고 나서 바로 "무엇을 놓쳤는지"를
봐야 다음 문항에서 고쳐 말한다. 끝에 몰아 보여주면 그건 채점표지 연습이 아니다.
비용상으로는 몰아서 한 번 부르는 쪽이 싸지만(호출 1회), 그러면 기능의 목적이 사라진다.

**총점 = 출제된 전 문항의 평균.** 답하지 못한 문항은 **0점으로 센다** — 답한 것만 평균 내면
한 문항만 답하고 나가는 쪽이 유리해진다.

## 5. 채점 — LLM 호출 하나

`LlmFeature.ANSWER_SCORING` 을 추가하고, 기존 구조화 출력 경로를 그대로 탄다.

**입력**: 질문 텍스트 + `answer_outline` + `requirement.text` + transcript
**출력**(구조화):

```jsonc
{
  "score": 72,            // 0~100
  "covered": [0, 2],      // answer_outline 인덱스
  "feedback": "핵심은 짚었지만 …"  // 한 줄
}
```

> **구현하며 바꾼 것: `missed`를 모델에게 묻지 않는다.** 원래 둘 다 받으려 했는데, 그러면
> 서로 겹치거나 합쳐도 전체가 안 되는 응답을 걸러내야 한다. `covered`의 여집합으로 계산하면
> **"겹치지 않고 합치면 전체"라는 성질이 계산에서 따라 나온다** — 화면이 뼈대 옆에 ✅/❌를
> 붙일 때 의존하는 성질이 정확히 그것이다. 검증할 것을 줄이는 대신 구조에서 없앴다.
> (`AnswerScoreNormalizer`)

> **재시도하지 않는다.** JD 파싱은 검증 실패 시 3회까지 다시 부르지만, 채점은 범위 밖 인덱스를
> **버리고** 점수를 **자를** 뿐이다. 채점은 한 문항에 한 번씩 나가는 호출이라 재시도가 곧
> 비용이고, 인덱스 하나가 이상하다고 멀쩡한 나머지를 버릴 이유가 없다.

> **답하지 않았으면 LLM을 부르지 않는다.** 제한 시간이 있는 이상 자주 일어나는 정상 경로다.
> 0점 + 전부 `missed`로 즉시 처리한다.

**지켜야 할 것 — 스펙 §4.5 "지어내지 않는다"를 채점에도 적용한다.**

- **모범 답변을 대신 써주지 않는다.** 놓친 포인트를 뼈대에서 짚어 줄 뿐이다. 답을 써 주면
  다음 연습에서 그걸 외워 말하게 되고, 점수는 오르지만 면접 실력은 오르지 않는다.
- **`covered`/`missed` 는 `answer_outline` 인덱스 범위를 벗어날 수 없다.** 서버에서 재검증한다
  (JD 파싱이 구조화 출력을 재검증하는 것과 같은 이유 — 모델은 인덱스를 지어낸다).
- **transcript 를 로그에 남기지 않는다.** 개인 발화다.

**모델 설정**: `LlmModelConfig` 에 항목을 추가하고 **effort 를 낮춘다.** 판정 작업이라 깊은
추론이 필요 없다 — JD 파싱과 같은 성격이다. `thinking` 은 끄지 않는다 (`CLAUDE.md` 참고).
`outputConfig(Class)` 가 effort 를 조용히 지우는 함정도 그대로 적용되므로
`StructuredOutput.withEffort` 로 다시 조립해야 한다.

## 6. 지출 방어 — 여기서 기존 한도가 깨진다

**이 기능은 지금까지의 어떤 기능과도 비용 구조가 다르다.** 파싱과 질문 생성은 요청 1건 = LLM
1회였는데, **면접 연습은 세션 1건 = LLM N회**다.

**실측** (2026-08-07, `llm_call_log`. Opus 5, in $5/M · out $25/M):

| | 입력 | 출력 | 지연 | 1회 | 5문항 세션 |
|---|---|---|---|---|---|
| 답변 채점 | 2,246 | 174 | 6.6s | **$0.0156** | **~$0.078** |
| (비교) JD 파싱 | 2,864 | 515 | 11.5s | $0.0272 | — |
| (비교) 질문 생성 | 2,756 | 3,184 | 53.7s | $0.0934 | — |

추정치($0.014)와 총액은 거의 맞았지만 **구성이 달랐다.** 출력이 예상의 절반 이하(174)인 대신
입력이 세 배(2,246)다 — 채점 응답은 원래 짧고, 부피는 거의 전부 **고정된 시스템 프롬프트**다.

> **그래서 프롬프트 캐싱이 유독 잘 듣는 자리다.** 한 세션의 채점 호출 N번이 같은 시스템
> 프롬프트를 매번 새로 보낸다. 캐시 읽기는 단가의 0.1배라 답변당 비용이 거의 반으로 준다.
> 다른 기능은 호출이 드물어 캐시가 만료되지만, 여기는 한 세션 안에서 연달아 부른다.
> **아직 넣지 않았다** — 3단계 범위 밖이고, 넣기 전에 세션 내 호출 간격이 캐시 TTL 안에
> 들어오는지 확인해야 한다.

전역 일일 상한 $5 기준 하루 약 **64세션**. 소유자당 6세션이면 한 사람이 하루 $0.47까지 쓴다.
**`sessions-per-day=6`은 이 실측으로 확정한다** (잠정치가 아니다).

**기존 한도(소유자당 시간당 20회)를 그대로 두면 5문항 세션 4번이면 소진된다.** 파싱·질문
생성과 한도를 공유하므로 면접 연습을 두 번 하면 공고 분석이 막힌다. 이건 받아들일 수 없다.

**결정: `LlmGuard` 를 그대로 쓰되, 면접 연습에 자체 상한을 둔다.**

```properties
jobit.interview.questions-per-session=5     # 세션당 문항 수 = 세션당 LLM 호출 수
jobit.interview.sessions-per-day=6          # 소유자별. 시간당이 아니라 일별이다
jobit.interview.time-limit-sec=90
```

- **채점 1회마다 `LlmGuard.consume` 을 부른다.** "돈이 나가기 직전 한 지점에서 부른다"는 기존
  원칙을 어기지 않는다 — 채점 한 번이 돈 한 번이다.
- **세션 수는 별도 상한으로 막는다.** 시간당 호출 한도만으로는 "한 사람이 하루 종일 조금씩"을
  못 막는데, 이 기능은 다른 기능보다 한 번에 N배를 먹으므로 그 틈이 실제 위험이 된다.
  카운터는 `rate_limit_bucket` 을 그대로 쓴다 (창 단위만 day).
- **전역 일일 상한($5)은 그대로다.** 세션당 $0.07 이면 전역 하루 ~70세션. 사이드 프로젝트
  규모에서는 충분하고, 넘치면 그때 상한을 올릴지 모델을 낮출지 `/api/cost` 를 보고 정한다.
- **`calls-per-hour` 는 손대지 않는다.** 올리면 파싱·질문 생성 쪽 방어까지 같이 헐거워진다.

## 7. 개인정보

transcript 는 **사용자가 자기 경력을 말한 내용**이다. 이력서에 준해 다룬다 (스펙 §6).

- **오디오 미보관** (§2).
- **transcript 에 TTL 을 건다** — `transcript_expires_at`, 기본 90일. 만료되면 transcript 만
  지우고 **점수·`covered`/`missed`·피드백은 남긴다.** "내 면접 기록"이 기능이므로 기록 전체를
  지우면 기능이 죽고, 발화 원문 없이도 점수 추이는 그대로 읽힌다.
  → `TranscriptCleanup` (하루 한 번). `RateLimitCleanup` 과 달리 실행 결과를 `info` 로 남긴다 —
  캐시 정리가 아니라 **개인정보 삭제 약속의 이행 기록**이라, 돌지 않았다는 사실을 나중에
  로그로 확인할 수 있어야 한다.

  > **구현하며 드러난 것: "답했는가"를 원문에서 파생시키면 안 된다.** `answered` 를
  > `transcript != null` 로 두었더니, TTL 이 원문을 지우는 순간 80점짜리 답변이 "무응답"으로
  > 바뀌었다. 개인정보 삭제의 취지는 **내용을 지우는 것이지 사실을 지우는 것이 아니다** —
  > 점수·피드백을 남기기로 한 것과 같은 이유로 "답했다"도 남아야 한다.
  > V10 에서 `interview_answer.answered` 컬럼을 추가했다.
- **로그에 transcript 를 남기지 않는다.** LLM 호출 실패 로그에도 마찬가지다.
- **삭제는 사용자가 언제든** — `DELETE /api/interviews/{id}`.

## 8. API 계약 (확정 시 `api.md` 로 옮긴다)

모두 `X-Owner-Key` 필수. 에러 형태·404 규칙은 `/api/submissions` 와 동일하다.

| 메서드 | 경로 | 용도 |
|---|---|---|
| `POST` | `/api/interviews` | 세션 시작. body `{jobPostingId}` → 세션 + 출제 질문 |
| `POST` | `/api/interviews/{id}/answers` | 답변 제출 + 즉시 채점 |
| `POST` | `/api/interviews/{id}/finish` | 종료 · 총점 확정 |
| `GET` | `/api/interviews` | 내 면접 기록 목록 (페이지) |
| `GET` | `/api/interviews/{id}` | 세션 상세 — 문항별 점수·뼈대 대조 |
| `DELETE` | `/api/interviews/{id}` | 기록 삭제 |

**SSE 를 쓰지 않는다.** 채점은 답변 하나당 짧은 단발 호출이라 스트리밍할 것이 없다.
질문 생성이 SSE 인 것은 10개를 60초에 걸쳐 만들기 때문이고, 여기는 그 상황이 아니다.

## 9. 화면

| 경로 | 화면 | 비고 |
|---|---|---|
| `/interview` | 공고 선택 | 헤더 `공고 분석` 옆 새 메뉴 |
| `/interview/{sessionId}` | 연습 진행 | 마이크·타이머·문항 1개씩 |
| `/profile/interviews` | 면접 기록 목록 | 프로필 사이드 메뉴 네 번째 |
| `/profile/interviews/{id}` | 세션 상세 | 문항별 점수 + 뼈대 대조 |

- 헤더: `공고 분석` · **`면접 연습`** · `프로필`
- 프로필 사이드 메뉴: `내 정보` · `내 기록` · **`면접 기록`** · `내 설정`
- **연습 화면은 검색에 올리지 않는다** (`robots: noindex`). 질문 결과 페이지가 공유·SEO
  자산인 것과 반대로, 이건 개인 기록이다 — `/profile/history` 와 같은 취급이다.
- **비로그인도 연습할 수 있다.** `owner_key` 의 `anon:` 네임스페이스가 그대로 동작하고,
  로그인 시 승계도 `/api/submissions/claim` 과 같은 방식으로 붙는다.

## 10. 아직 결정하지 않은 것

- **재연습 시 문항을 바꿀 것인가.** 1차는 항상 앞에서 N개. 실제로 재연습이 일어나는지 본 뒤 정한다.
- **꼬리질문(`followups`)을 연습에 넣을 것인가.** 데이터는 이미 있다. 1차 범위에서는 뺀다 —
  꼬리질문까지 넣으면 세션당 호출이 배로 늘어 비용 구조가 다시 바뀐다.
- **점수 추이 그래프.** 기록이 몇 건 쌓이기 전에는 보여줄 것이 없다.
- **호출자 인증** — 이 기능도 `owner_key` 위조에 그대로 노출된다. 전체 미결 사항과 같다.

## 11. 구현 순서

한 단계씩 동작하는 상태로 끊는다.

1. ~~**Flyway V9 + 엔티티·리포지토리**~~ ✅ 2026-08-07
   — `contextLoads`가 매핑을 검증한다는 전제가 **틀렸던 것을 발견**해 함께 고쳤다
   (`ddl-auto=validate` 부재). 저장·조회는 `InterviewPersistenceTest`가 따로 본다
2. ~~**채점 경로**~~ ✅ 2026-08-07 (`AnswerScorer` · `AnswerScoreNormalizer` ·
   `AnswerScorePrompts` · `AnthropicAnswerScorer` + 폴백 배선)
3. ~~**세션 API** (시작 · 답변 제출 · 종료) + 지출 방어~~ ✅ 2026-08-07
   — 비용 실측 완료(§6). 일별 세션 상한은 별도 카운터 없이 `interview_session`을 센다
4. ~~**기록 조회 API** (목록 · 상세 · 삭제)~~ ✅ 2026-08-07
   — 승계(`POST /api/interviews/claim`)도 함께 넣었다. 없으면 로그인 직후 익명 연습 기록이
   사라져 제출 이력과 같은 문제가 생긴다
5. ~~**프론트 연습 화면**~~ ✅ 2026-08-07
   — 지원 게이트 + 텍스트 폴백 포함. **`GET /api/interviews/{id}` 에 `questions` 를 추가**했다:
   시작 응답에만 문항이 있으면 새로고침·새 탭에서 세션이 미아가 된다. 그 목록에는 뼈대가
   없으므로 "보고 답하는" 문제는 생기지 않는다
6. ~~**프론트 기록 화면** — `/profile/interviews`~~ ✅ 2026-08-07
   — 목록·상세·삭제 + 익명 기록 승계 연결. 미완료 세션은 "이어서 연습하기"로 되돌아간다
7. ~~**transcript TTL 스케줄러**~~ ✅ 2026-08-07 (`TranscriptCleanup`)
   — 여기서 `answered` 를 원문에서 파생시킨 결함이 드러나 V10 으로 고쳤다 (§7)

---

**로드맵 완료 (2026-08-07).** 남은 후속 과제는 §10 "아직 결정하지 않은 것"과
§6 의 프롬프트 캐싱이다.

**2번을 3번보다 먼저 하는 이유**: 채점 품질이 이 기능의 전부다. 세션 관리는 그 다음 문제이고,
채점이 쓸 만하지 않으면 나머지를 만들 이유가 없다.
