# JD 기반 기술 면접 준비 + 이력서 첨삭 서비스

> 채용공고(JD)를 붙여넣으면 그 공고에 맞는 예상 질문과 답변 뼈대를 만들어주고,
> 같은 공고 기준으로 내 이력서의 부족한 부분을 짚어 첨삭해주는 웹 서비스.

---

## 1. 제품 개요

### 이름

**jobit — 직업과 나를 잇다.**

`job` + `it`(잇다). 공고와 지원자를 잇는다는 뜻이고, 그것이 곧 제품의 동작이다 —
공고에서 뽑은 요구사항(`requirement`)을 축으로 질문과 이력서를 잇는 구조(아래 핵심 아이디어)와
이름이 같은 것을 가리킨다.

### 핵심 아이디어

두 기능이 **JD 파싱 결과라는 하나의 자산**을 공유한다.

```
JD 텍스트
  └→ [추출] { 스택, 연차, 도메인, 우대사항, 책임범위, 키워드 }
        ├→ 면접 질문 생성       (이 스택 기준으로)
        └→ 이력서 갭 분석       (내 이력서 ↔ 이 요구사항 매칭)
```

LLM 호출을 두 배로 늘리는 구조가 아니라, 한 번 구조화한 데이터를 양쪽에서 재사용한다.

### 차별점

기존 이력서 첨삭 도구는 대부분 JD와 무관하게 "일반적으로 좋은 문장"으로 고쳐준다.
**같은 이력서라도 공고에 따라 강조할 항목이 달라진다**는 것이 이 제품의 핵심 가치이고,
이건 JD 파싱을 이미 하고 있는 구조에서만 자연스럽게 나온다.

### 수익 관점

- 면접 질문 생성: 한 번 써보고 마는 성격 → **신규 유입** 담당
- 이력서 첨삭: 지원할 때마다 돌림 → **재방문·유료화** 담당
- 공유 링크 / 질문 페이지: 검색 유입 자산 (SSR 필요한 이유)

---

## 2. 기술 스택

### 기준안: Next.js 단일 레포

| 영역 | 선택 | 비고 |
|---|---|---|
| 프레임워크 | Next.js (App Router) + TypeScript | 화면 = React, API = route handler |
| DB | Postgres (Neon / Supabase 무료 티어) | `pgvector` 확장 처음부터 활성화 |
| ORM | Drizzle 또는 Prisma | |
| LLM | API 종량제, 구조화 출력(JSON schema) 필수 | 파싱은 저렴한 모델 / 리라이트는 상위 모델 |
| 임베딩 | API 임베딩 모델 → `pgvector` 저장 | 생성 대비 비용 수십 분의 1 |
| 인증 | 초기 없음 → 익명 세션 쿠키 → GitHub OAuth | 이력서 저장 시점에 도입. 회원 모델은 §3.6 |
| 배포 | Vercel + Neon | 고정비 0에 수렴 |
| 큐 | 초기 불필요 | 리라이트 지연 길어지면 도입 |

**선택 이유**

- 스트리밍이 제품의 체감 품질을 좌우하는데, Next에서 LLM 응답 흘려보내기가 몇 줄이면 끝난다
- 고정비가 없다 — 수익 나기 전 서버 비용이 나가면 먼저 지친다
- 레포 하나 = 혼자 굴릴 때 왕복 비용 없음
- SSR이 있어 공유 링크·질문 페이지의 SEO가 살아있다

**벡터 DB는 따로 두지 않는다.** 질문 은행 RAG는 `pgvector`로 충분하고,
인프라를 하나 더 늘리면 관리 비용만 늘어난다.

### 대안 (§7 열린 결정 참고)

- **Spring Boot + Thymeleaf + htmx** — 익숙한 스택, SSR 유지, 서버 하나. 4단계 UI에서 한계
- **Next(BFF) + Spring Boot(코어 API)** — 백엔드 설계 역량 노출. 고정비 + 배포 2벌

어느 쪽을 골라도 **아래 데이터 모델은 그대로 쓴다.**

---

## 3. 데이터 모델

핵심은 `requirement`가 모든 것의 연결 고리라는 점.
질문도 요구사항에서 파생되고 갭 분석도 요구사항 기준으로 판정되므로,
이걸 독립 테이블로 빼야 두 기능이 하나로 묶인다.

### 3.1 공고

```sql
-- 채용공고
job_posting (
  id            uuid pk,
  content_hash  text unique,      -- 정규화한 본문의 해시. 캐시 키
  raw_text      text,
  source_url    text null,
  company       text null,
  title         text null,
  parsed        jsonb,            -- 스택, 연차, 도메인 등
  created_at    timestamptz
)

-- 공고에서 뽑아낸 요구사항 (질문·갭분석 공통 앵커)
requirement (
  id              uuid pk,
  job_posting_id  uuid fk,
  text            text,
  kind            enum('REQUIRED','PREFERRED','RESPONSIBILITY'),
  keywords        text[],         -- 매칭·검색용
  sort_order      int
)
```

### 3.2 면접 질문

```sql
question_set (
  id              uuid pk,
  job_posting_id  uuid fk,
  prompt_version  text,           -- 프롬프트 바뀌면 재생성 판단용
  model           text,
  created_at      timestamptz
)

question (
  id              uuid pk,
  question_set_id uuid fk,
  requirement_id  uuid fk null,   -- 어느 요구사항에서 나온 질문인지
  text            text,
  category        enum('CS','STACK','EXPERIENCE','DESIGN','CULTURE'),
  difficulty      smallint,
  followups       jsonb,          -- 꼬리질문 배열
  answer_outline  jsonb           -- 답변 뼈대 (핵심 포인트 목록)
)
```

### 3.3 이력서

**문장 단위(bullet)로 쪼개는 것이 필수.**
전체를 LLM에 보내면 토큰 낭비이고, 사용자가 원치 않는 부분까지 바뀐다.

```sql
resume (
  id           uuid pk,
  owner_key    text,              -- 익명 세션 키 또는 user_id
  raw_text     text,              -- 암호화 저장
  parsed       jsonb,
  expires_at   timestamptz,       -- TTL. 지나면 배치로 삭제
  created_at   timestamptz
)

resume_bullet (
  id           uuid pk,
  resume_id    uuid fk,
  company      text null,
  period       text null,
  text         text,
  embedding    vector(1536) null, -- 요구사항 매칭에 사용
  sort_order   int
)
```

### 3.4 갭 분석 · 첨삭

```sql
gap_analysis (
  id              uuid pk,
  resume_id       uuid fk,
  job_posting_id  uuid fk,
  created_at      timestamptz,
  unique (resume_id, job_posting_id)   -- 같은 조합은 캐시 재사용
)

gap_item (
  id                 uuid pk,
  gap_analysis_id    uuid fk,
  requirement_id     uuid fk,
  status             enum('MET','WEAK','MISSING'),
  evidence_bullet_id uuid fk null,     -- 근거가 된 이력서 문장
  rationale          text              -- 왜 이렇게 판정했는지
)

rewrite_suggestion (
  id            uuid pk,
  gap_item_id   uuid fk,
  bullet_id     uuid fk,
  original      text,
  suggested     text,
  reason        text,
  accepted      boolean default false  -- 사용자 채택 여부 = 품질 지표
)
```

### 3.5 비용 추적

**처음부터 넣는다.** 나중에 넣으려면 귀찮고, 없으면 어느 기능이 돈을 먹는지 안 보인다.

```sql
llm_call_log (
  id, feature, model, input_tokens, output_tokens,
  cost_usd, cache_hit boolean, latency_ms, created_at
)
```

### 3.6 회원 · 입력 이력

익명 세션만으로는 "지난주에 넣었던 그 공고" 를 다시 찾을 수 없다.
재방문이 제품의 수익 축(§1)인 이상 이력 조회는 회원 기능으로 올라온다.

```sql
member (
  id             uuid pk,
  provider       enum('LOCAL','GITHUB'),
  provider_uid   text,            -- GITHUB: 제공자 측 고유 ID / LOCAL: 정규화한 이메일
  email          text null,
  password_hash  text null,       -- LOCAL만 채운다. BCrypt.
  nickname       text,
  created_at     timestamptz,
  unique (provider, provider_uid)
)
```

**가입 수단은 두 가지다.**

| provider | provider_uid | password_hash | 용도 |
|---|---|---|---|
| `LOCAL` | 정규화한 이메일 (소문자·trim) | 필수 | 이메일 + 비밀번호 |
| `GITHUB` | GitHub 계정 ID | 없음 | OAuth |

`provider_uid`에 이메일을 넣어 두면 `unique (provider, provider_uid)` 하나로 이메일 중복까지
막힌다. 제약을 따로 추가하지 않는다.

> **같은 이메일로 LOCAL과 GITHUB 계정이 각각 생길 수 있다.** 유니크 제약이 provider별이기
> 때문이다. 계정 통합을 할지, 한다면 어느 시점에 할지는 §7에서 정한다. 통합하지 않기로 하면
> 가입 화면에서 "이미 GitHub으로 가입된 이메일입니다" 정도는 안내해야 한다.

**`job_posting`에 `member_id`를 달지 않는다.**
`job_posting`은 `content_hash` 기준 **전역 캐시**다(§4.1). 회원을 직접 붙이면 같은 공고를
넣은 다른 사용자가 캐시를 재사용하지 못해 파싱 비용이 인원수만큼 늘어난다.
소유 관계는 별도 테이블로 뺀다.

```sql
-- 누가 어떤 공고를 넣었는가. 공고 1개 : 제출 N개
jd_submission (
  id              uuid pk,
  member_id       uuid fk,
  job_posting_id  uuid fk,
  memo            text null,       -- "지원 완료", "1차 탈락" 등 사용자 메모
  created_at      timestamptz,
  updated_at      timestamptz,     -- 같은 공고 재입력 시 갱신
  unique (member_id, job_posting_id)
)
```

> 같은 공고를 다시 넣으면 행을 새로 만들지 않고 `updated_at`만 갱신한다.
> 목록이 중복으로 지저분해지는 것을 막기 위한 선택이며, 대신 "몇 번 봤는지" 는 남지 않는다.

`resume.owner_key`(§3.3)는 익명 세션 키와 `member.id`를 모두 담는 필드다.
로그인 시 익명 세션으로 만든 이력서·갭분석의 `owner_key`를 `member.id`로 옮긴다 —
비회원으로 써보고 마음에 들면 가입하는 흐름을 유지하기 위한 것이고,
이관을 빠뜨리면 가입 직후 자기 데이터가 사라진 것처럼 보인다.

### 3.7 비밀번호 재설정

```sql
password_reset_token (
  id          uuid pk,
  member_id   uuid fk,
  token_hash  text unique,     -- SHA-256. 원문은 저장하지 않는다
  expires_at  timestamptz,
  used_at     timestamptz null,
  created_at  timestamptz
)
```

**토큰 원문은 저장하지 않는다.** DB가 유출되면 저장된 토큰으로 아무 계정이나 탈취할 수 있다.
메일로 보내는 것은 원문, DB에 남기는 것은 해시이고, 검증은 들어온 값을 해싱해 비교한다.

**해시는 SHA-256을 쓴다. 비밀번호와 반대다.**
비밀번호는 사람이 만든 저엔트로피 문자열이라 BCrypt 같은 느린 해시가 필요하지만, 이 토큰은
256비트 난수라 대입이 불가능하다. 오히려 BCrypt는 salt 때문에 해시로 조회할 수 없어 못 쓴다.

| 규칙 | 값 | 이유 |
|---|---|---|
| 만료 | 30분 | 메일함이 털렸을 때의 노출 창을 줄인다 |
| 사용 횟수 | 1회 (`used_at`) | 메일이 전달·전달되며 재사용되는 것을 막는다 |
| 재발급 | 이전 토큰 즉시 무효화 | 유효한 토큰이 여러 장 떠다니지 않게 한다 |
| 연속 요청 | 60초 내 재요청은 무시 | 메일 폭탄 방지 (스펙 §6 레이트 리밋) |

**LOCAL 계정에만 적용된다.** GitHub 가입자는 비밀번호가 없으므로 토큰을 만들지 않는다.
다만 **응답은 구분하지 않는다** — 가입 여부도, 가입 수단도 노출되면 안 된다 (§6).

---

## 4. 핵심 흐름

### 4.1 JD 파싱

1. 본문 정규화 (공백·특수문자 정리) → 해시
2. `content_hash`로 조회 → 있으면 그대로 재사용
3. 없으면 LLM 호출 → `job_posting` + `requirement` 저장

> 인기 공고는 여러 사용자가 붙여넣기 때문에 캐시 적중률이 생각보다 높다.

### 4.2 질문 생성

`requirement` 목록을 컨텍스트로 넣고 구조화 출력으로 질문 배열 수신.
`prompt_version`이 같으면 재생성하지 않는다.

### 4.3 갭 분석 — 2단계 구조

요구사항 × 이력서 문장을 전부 LLM에 넣으면 비싸다. 두 단계로 나눈다.

**1단계 — 임베딩으로 후보 추림**
각 requirement마다 코사인 유사도 상위 3개 bullet만 선별

**2단계 — LLM은 판정만**
요구사항 1개 + 후보 문장 3개 → `MET` / `WEAK` / `MISSING` + 근거

> 임베딩은 생성 대비 비용이 수십 분의 1이라, 요구사항이 20개여도 부담이 없다.
> RAG 실습 목적도 여기서 자연스럽게 달성된다.

### 4.4 리라이트

`WEAK` 항목만, 해당 bullet 하나씩 처리.
원문 + 요구사항 + 판정 근거만 전송 → 입력 토큰 수백 수준.

### 4.5 결과 UX 원칙

**갭 분석 표가 먼저 나온다.** 첨삭보다 이게 "쓸모있다"고 느끼는 지점이다.

| 요구사항 | 상태 | 근거 |
|---|---|---|
| Spring Boot 3년+ | ✅ 충족 | "결제 API 개발" 항목 |
| 대용량 트래픽 경험 | ⚠️ 약함 | 언급은 있으나 수치 없음 |
| Kubernetes 운영 | ❌ 없음 | — |

- `⚠️ WEAK` → 원문 옆에 수정안을 나란히 + "왜 이렇게 고쳤는지" 한 줄.
  **전체를 갈아엎지 않는다.** 자기 이력서가 아니게 느껴지면 안 쓴다.
- `❌ MISSING` → **절대 지어내지 않는다.**
  "충족 근거가 없습니다. 면접에서 물어볼 가능성이 높으니 인접 경험으로 준비하세요"
  라고 안내하고 **질문 생성 기능으로 넘긴다.** 여기서 두 기능이 하나의 흐름으로 닫힌다.

### 4.6 화면 구성

경로는 예시이며 프론트 구성(§7)에 따라 조정한다.

| 경로 | 화면 | 로그인 | 비고 |
|---|---|---|---|
| `/` | JD 입력 | 불필요 | 1단계 진입점 |
| `/postings/{id}/questions` | 질문 결과 | **불필요** | 공유 링크 · SEO 자산 |
| `/signup` | 일반 회원가입 (이메일·비밀번호) | 불필요 | |
| `/login` | 로그인 — 이메일·비밀번호 + GitHub OAuth | 불필요 | 두 수단을 한 화면에 둔다 |
| `/password/forgot` | 재설정 메일 요청 | 불필요 | 결과 문구는 가입 여부와 무관하게 동일 |
| `/password/reset?token=…` | 새 비밀번호 입력 | 불필요 | 토큰이 인증을 대신한다 |
| `/me` | 대시보드 | 필요 | 최근 제출 몇 건 + 이력서 상태 |
| `/me/submissions` | JD 입력 이력 목록 | 필요 | `jd_submission` 목록 |
| `/me/submissions/{id}` | 제출 상세 | 필요 | 공고 요약 · 질문 · 갭분석 요약 |
| `/me/submissions/{id}/gap` | 갭 분석 결과 | 필요 | §4.5의 표 |
| `/me/resumes` | 이력서 관리 | 필요 | 업로드 · 교체 · 삭제 |

**질문 결과 페이지는 로그인 뒤에 두지 않는다.** §1에서 공유 링크를 검색 유입 자산으로
잡았는데 인증을 걸면 크롤링도 공유도 되지 않는다. 반대로 이력서가 얽힌 화면(`/me/*`)은
전부 인증 뒤에 둔다.

**목록 화면에 필요한 것**

- 회사·제목·제출일, 그리고 **갭 분석 요약 한 줄**(예: `충족 8 / 약함 3 / 없음 2`)
- 정렬은 최근순. 필터는 회원이 수십 건을 쌓기 전까지 필요 없다.
- `memo` 필드로 "지원 완료", "1차 탈락" 같은 사용자 메모를 단다 —
  지원 현황 추적까지 하려는 것이 아니라, 목록에서 공고를 구분하기 위한 최소 장치다.

**제출 상세는 두 기능의 합류 지점이다.**
같은 공고에 대한 질문과 갭 분석이 한 화면에서 보여야 §4.5의 흐름
(MISSING → 질문 생성으로 넘김)이 이력에서도 이어진다.

---

## 5. 단계별 로드맵

| 단계 | 범위 | 목표 |
|---|---|---|
| 1 | JD 붙여넣기 → 파싱 → 질문 10개 (스트리밍) | 배포까지 완료. 저장·로그인 없음 |
| 2 | 꼬리질문, 답변 뼈대, 질문 저장/공유 링크 | 유입 채널 확보 (공유 링크 = SEO 자산) |
| 3 | GitHub 로그인, 이력서 업로드 → 갭 분석 표, 제출 이력 조회(§4.6) | 재방문 지점 확보 |
| 4 | WEAK 항목 리라이트 + 채택 UI | 유료화 후보 |
| 5 | 질문 은행 축적 → 유사 공고 질문 추천 (RAG) | 캐시 적중률·품질 동시 상승 |

**1단계는 로그인도 DB 저장도 없이 만든다.**
로그인부터 붙이면 제품 가치를 확인하기도 전에 주말 세 번이 날아간다.

**회원·이력 조회는 3단계에 묶는다.** 이력서가 들어오는 시점이 소유자를 특정해야 하는
시점이고(§2), 그 전까지는 저장할 것이 공유 링크뿐이라 익명으로 충분하다.
1~2단계에서 회원을 먼저 만들면 §4.6의 `/me/*` 화면에 보여줄 내용이 없다.

---

## 6. 운영 고려사항

### 개인정보

이력서에는 이름·연락처·회사명이 그대로 담긴다. 개발자 타깃이라 이 부분이 허술하면 바로 티가 난다.

- 처리 방침 명시
- 저장하지 않거나, 저장 시 암호화 + `expires_at` TTL
- 클라이언트에서 개인정보 마스킹 후 API 전송하는 방식도 검토

**회원 도입이 TTL 전제를 바꾼다.** 익명 이력서는 `expires_at`으로 자동 소멸시키면 되지만,
회원이 저장해둔 이력서를 말없이 지우면 그건 장애로 보인다. 회원 이력서는 TTL을 걸지 않거나
만료 전 고지가 필요하다 — 어느 쪽이든 §7에서 정한다.

**일반 회원가입은 보관 책임을 늘린다.** OAuth만 쓸 때는 비밀번호를 갖고 있지 않았다.
자체 계정을 받는 순간 이메일과 비밀번호 해시가 우리 DB에 남고, 유출 시 피해가 이력서에 그치지
않는다 — 사용자가 다른 서비스와 비밀번호를 돌려쓰기 때문이다. 최소한 다음은 지킨다.

- **BCrypt 등 느린 해시**로 저장한다. SHA-256 계열은 쓰지 않는다.
- 비밀번호는 **로그·에러 메시지·예외 스택 어디에도 남기지 않는다.**
- 로그인 실패 응답에서 **이메일 존재 여부를 구분해 노출하지 않는다.**
  "이메일이 없습니다" / "비밀번호가 틀렸습니다"를 나누면 가입 여부가 조회된다.
- 이메일 인증 여부는 §7에서 정한다. 없으면 오타 가입과 타인 이메일 도용을 막지 못한다.

**탈퇴 시 삭제 범위도 정해야 한다.** `member`만 지우면 `resume`, `gap_analysis`,
`jd_submission`이 고아로 남는다. 반면 `job_posting`과 `question_set`은 개인정보가 아니고
전역 캐시(§4.1)이므로 **남긴다** — 이걸 같이 지우면 다른 사용자의 캐시가 깨진다.

### 비용 통제

| 항목 | 대책 |
|---|---|
| JD 파싱 | `content_hash` 캐싱 (적중률 높음) |
| 갭 분석 | 임베딩 1차 필터 → LLM은 판정만 |
| 리라이트 | WEAK 항목의 해당 문장만 전송 |
| 전반 | 세션당 호출 횟수 제한 + 결과 캐싱 |

> 셀프 호스팅은 초기에 권하지 않는다. 트래픽이 적을 때 GPU 고정비가 API 종량제보다 훨씬 비싸고,
> 텍스트 품질이 곧 제품 가치인 유형이라 작은 로컬 모델로 내리면 서비스 설득력이 사라진다.
> 월 비용이 유의미해진 뒤에 옮기는 순서가 안전하다.

### 엔지니어링 체크리스트

- [ ] SSE 스트리밍 (토큰 단위 렌더링)
- [ ] 구조화 출력 + 서버 재검증, 실패 시 재시도
- [ ] 동일 입력 캐싱
- [ ] 레이트 리밋 / 프롬프트 주입 방어
- [ ] 모델 장애 시 폴백
- [ ] `llm_call_log` 기반 비용 대시보드

---

## 7. 열린 결정

### 프론트엔드 구성

| 안 | 장점 | 단점 |
|---|---|---|
| **Next 단일** (기준안) | 스트리밍·SEO·배포 한 번에, 고정비 0 | 백엔드 설계 역량은 덜 드러남 |
| Spring + Thymeleaf + htmx | 익숙한 스택, 서버 하나, SSR 유지 | 4단계 리라이트 UI에서 상태 관리 한계 |
| Next(BFF) + Spring(코어) | SEO + 백엔드 역량 동시 확보 | 고정비 발생, 배포 파이프라인 2개 |

**판단 기준**

- React 필요성은 단계별로 다르다. 1~3단계는 htmx로도 충분하고,
  4단계(수정안 채택/되돌리기/실시간 미리보기)부터 클라이언트 상태가 본격적으로 쌓인다.
- 분리형으로 갈 거라도 **1번으로 시작해서 나중에 무거운 로직만 떼어내는 순서**를 권한다.
  형태가 안 잡힌 상태에서 미리 그은 API 경계는 거의 항상 틀린다.

### 미정 항목

- [ ] 프론트 구성 최종 선택
- [ ] 유료화 시점과 과금 단위 (횟수제 / 구독)
- [ ] 어필리에이트 배치 위치 (질문 페이지 하단 vs 갭 분석 결과)
- [ ] 프롬프트 구조화 출력 스키마 상세 설계
- [ ] 회원 이력서에 TTL을 걸 것인가 (§6)
- [ ] 탈퇴 시 삭제 범위와 유예 기간
- [ ] 익명 세션 → 회원 데이터 이관 트리거 (로그인 즉시 vs 사용자 확인 후)
- [ ] GitHub 외 로그인 수단 추가 여부 (`member.provider`가 enum인 이유)
- [ ] 같은 이메일의 LOCAL·GITHUB 계정 통합 여부와 시점 (§3.6)
- [ ] 이메일 인증 도입 여부 — 안 하면 오타 가입·타인 이메일 도용을 막지 못한다
- [x] ~~비밀번호 재설정 흐름~~ → §3.7에서 확정
- [ ] **메일 발송 수단** (SES / Resend / SMTP) — 재설정 토큰을 전달할 방법이 아직 없다.
      정해지기 전까지 재설정은 완성되지 않는다
- [ ] 재설정 완료 시 기존 세션을 끊을 것인가 (세션 방식 결정 후)
- [ ] 세션 저장 방식 (쿠키 세션 vs JWT) — 익명 세션 키와 함께 설계
