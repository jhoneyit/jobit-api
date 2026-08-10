# 아키텍처 — Spring 구현 매핑

스펙(`jd-interview-prep-spec.md`)은 스택 중립으로 쓰여 있다. 이 문서는 그것을 Spring Boot 구현으로
옮길 때의 결정만 담는다. **제품 요구사항·데이터 모델·흐름은 스펙이 원본이므로 여기 복제하지 않는다.**

## 레포 경계 (2026-08-03 확정)

제품은 레포 두 개다. 이 문서와 `api.md`가 그 사이 계약의 원본이다.

```
jobit-front (Next.js)          jobit (Spring Boot)
  화면 · SSR · 세션 쿠키   ──▶   REST API · LLM 호출 · DB · 도메인 로직
```

경계를 가르는 기준 하나: **LLM 호출과 DB 커넥션은 이쪽에만 있다.** 프론트가 Ollama나
Postgres에 직접 붙는 코드가 생기면 그 시점에 경계가 무너진 것이다.

| | jobit | jobit-front |
| --- | --- | --- |
| JD 파싱 · 질문 생성 · 갭 분석 | ✅ | ❌ 호출만 |
| DB 스키마 · 마이그레이션 | ✅ Flyway | ❌ |
| LLM 호출 · 비용 로깅 | ✅ | ❌ |
| **인증 (가입·로그인·세션·재설정)** | ❌ | **✅ Auth.js** |
| 화면 · 라우팅 | ❌ | ✅ |

**인증은 프론트에 남긴다** (2026-08-03). 이미 동작하는 Auth.js 흐름(OAuth 콜백·세션·재설정 메일)을
Spring에서 다시 만드는 비용이 크고, 해시 방식도 BCrypt↔scrypt로 호환되지 않는다. 이 서버는 호출
시점에 넘어오는 `owner_key`로 소유자를 식별할 뿐 "누가 로그인했는가"를 판단하지 않는다.

그 결과 **이 서버의 `member` · `password_reset_token` 테이블에는 행이 생기지 않는다.** 회원은
프론트의 `user` 테이블에 있다. 관련 코드(`member` 패키지)는 지우지 않고 두되 사용하지 않는다 —
3단계에서 이력서(개인정보)를 다룰 때 재검토한다.

> **현황**: `jobit-front`에는 아직 자체 Drizzle 스키마·Auth.js·LLM 호출이 남아 있어 위 표대로
> 동작하지 않는다. 이관은 진행 예정 작업이며, 현재 상태는 `jobit-front/README.md`에 적혀 있다.

전환 순서는 이쪽에 엔드포인트가 먼저 생겨야 프론트가 갈아탈 수 있다는 제약을 따른다 —
`api.md`에 계약을 적고 → 컨트롤러를 구현하고 → 프론트가 자체 구현을 걷어낸다.

## 패키지 구조

도메인형으로 나눈다. 스펙 §3의 테이블 묶음이 그대로 패키지 경계가 된다.

```
com.jobit/
  jd/          job_posting, requirement          — JD 파싱, 캐시(content_hash)
  question/    question_set, question            — 질문 생성
  resume/      resume, resume_bullet             — 업로드, bullet 분해, 임베딩
  gap/         gap_analysis, gap_item,
               rewrite_suggestion                — 2단계 갭 분석, 리라이트
  submission/  jd_submission                     — 입력 이력 (스펙 §3.6, §4.6)
  member/      member, password_reset_token      — 인증. 현재 미사용 (위 레포 경계 참고)
  llm/         llm_call_log                      — 클라이언트, 구조화 출력, 비용 로깅
  common/                                        — OwnerKey, 공통 설정
```

`jd_submission`은 원래 `member` 패키지에 있었다. 소유자가 회원이라고 봤기 때문인데,
`owner_key`로 바꾸면서 **비회원도 소유자가 되므로** 그 전제가 사라졌다. `member`가 미사용으로
남는 동안 활성 코드가 그 안에 섞여 있으면 혼란스러워 `submission`으로 분리했다 (2026-08-03).

`llm`은 다른 모든 패키지가 의존하는 하위 레이어다. 반대 방향(도메인 → `llm` 외 도메인) 의존은 만들지 않는다.

### owner_key

개인 자산(`jd_submission.owner_key`, `resume.owner_key`)의 소유자 식별자. 규약은 `common.OwnerKey`가
강제한다.

```
로그인   user:<user_id>    ← jobit-front 의 user 테이블 ID
비로그인 anon:<세션 쿠키>
```

회원 ID와 익명 세션 키가 한 컬럼을 공유하므로 **접두사가 규약의 전부다.** 조회는 `owner_key`
하나만 보면 되고 로그인 여부로 분기하지 않는다.

> **이 값은 HTTP로 프론트에서 넘어온다.** `OwnerKey.requireValid`는 형식만 검사하며 사칭을
> 막지 못한다. 그래서 **2026-08-07부터 이 값에 HMAC 서명을 요구한다** (`ServiceAuth`) —
> 서명은 특정 `owner_key`와 만료 시각에 묶여 있어 헤더만 바꿔치기할 수 없다.

## 레이어 규칙

- **Controller** — 요청/응답 변환, 검증, SSE 스트림 열기. 비즈니스 로직 없음.
- **Service** — 트랜잭션 경계, 도메인 조합, LLM 호출 오케스트레이션.
- **Repository** — 영속성.

금지: Controller가 Repository 직접 호출.

## 스택 고유 결정사항

### pgvector

`resume_bullet.embedding vector(1024)`는 JPA 표준 타입이 아니다. Hibernate 커스텀 타입 매핑 또는
네이티브 쿼리로 처리해야 한다. 유사도 검색(코사인 상위 3개)은 네이티브 쿼리가 현실적이다.

### SSE 스트리밍

스펙이 "체감 품질을 좌우한다"고 명시한 부분이다. Spring MVC에서는 `SseEmitter` 또는
`ResponseBodyEmitter`를 쓴다. 수신은 브라우저의 `EventSource`이므로 서버는 표준
`text/event-stream`만 지키면 되고, 프론트 프레임워크에 맞춘 처리는 필요 없다.

여기서 끝이 아니다. 구조화 출력을 쓰면 모델은 `{"questions":[{...},{...}]}` 전체를 토큰 단위로
흘려보낸다. 다 받아서 파싱하면 스트리밍의 의미가 없으므로, **배열 원소 하나가 닫히는 순간
그 조각만 파싱해 검증하고 즉시 SSE 프레임으로 밀어내는 증분 파서**가 필요하다.
`jobit-front/src/lib/llm/incremental-array.ts`에 같은 문제를 푼 구현이 있다 — 옮길 때 참고한다.

> 스펙 §2가 Next를 기준안으로 삼은 이유 중 하나가 스트리밍 구현 편의였다. Spring에서는 이 부분에
> 명시적인 작업이 필요하다는 것을 인지하고 간다.

### 프론트와의 인증 경계

인증은 전부 프론트다. 이 서버는 자격증명을 보지 않고, 호출 시점에 넘어온 `owner_key`로 소유자를
식별할 뿐이다. `member` 패키지의 `LocalAccountService`·`PasswordResetService`는 그래서 현재
호출되지 않는다.

### 호출자 인증 (2026-08-07 결정)

후보는 서비스 토큰 / mTLS / 네트워크 격리였다. 뒤의 둘은 인프라 영역이라 코드로 붙일 수 없고,
남은 서비스 토큰 안에서 **공유 토큰 하나가 아니라 `owner_key`에 대한 HMAC 서명**을 택했다.

문을 잠그는 효과는 같다 — 비밀키 없이는 서명을 만들 수 없으므로 호출 자체가 되지 않는다.
차이는 **새어 나갔을 때**다. 토큰이면 요청 하나가 로그·프록시로 새는 순간 모든 소유자를
사칭할 수 있지만, 서명은 그 `owner_key`로 만료 전까지만 쓸 수 있다.

- 헤더: `X-Owner-Auth: v1.<만료 epoch 초>.<base64url HMAC-SHA256>`
- 서명 대상은 `"v1." + owner_key + "." + exp` — **만료를 서명 안에 넣어야** 늘려서 재사용할 수 없다
- `owner_key`가 없는 요청(공개 통계)도 서명한다. 예외를 두면 그 경로가 그대로 뒷문이 된다
- `/api/*` 앞단의 **필터 한 곳**에서 판정한다. 컨트롤러마다 붙이면 하나만 빠뜨려도 뒷문이 되고,
  빠뜨린 사실은 사고가 나야 드러난다 — 여기서 막으면 새 엔드포인트의 기본값이 "닫힘"이다
- 비밀키(`jobit.auth.service-secret`)가 없으면 **인증이 꺼진다.** 로컬에서 두 레포를 설정하지
  않고도 돌려 보기 위한 것이며, 부팅 로그에 경고가 남고 `prod` 프로파일에서는 앱이 뜨지 않는다

**남은 위험**: 비밀키 자체가 새면 모든 소유자를 사칭할 수 있다. 비대칭 키나 mTLS 로 좁힐 수
있지만 사이드 프로젝트 규모에서는 과하다고 봤다. 키 회전 절차는 아직 없다.

### 구조화 출력

LLM 응답 JSON을 DTO로 역직렬화한 뒤 **서버에서 재검증**하고, 실패 시 재시도한다 (스펙 §6 체크리스트).
검증 실패를 그대로 저장하지 않는다.

## 결정 기록

| 날짜 | 결정 | 이유 |
| --- | --- | --- |
| 2026-08-02 | 스키마 관리는 Flyway 마이그레이션 (`ddl-auto` 미사용) | 운영 DB 변경 추적 |
| 2026-08-02 | ~~스펙 §7 프론트 구성 → Spring Boot 단일 + Thymeleaf + htmx~~ | **2026-08-03에 뒤집힘** |
| 2026-08-02 | 벡터 DB 분리하지 않고 `pgvector` 사용 | 스펙 §2. 인프라 추가 시 관리 비용만 증가 |
| 2026-08-02 | 비밀번호 해싱은 BCrypt(strength 12), `starter-security`는 미도입 | 해싱만 필요한데 스타터를 넣으면 전 경로에 폼 로그인이 걸린다 |
| 2026-08-03 | **Spring = REST API 서버 / Next(`jobit-front`) = 프론트** | 이미 동작하는 Next 화면을 살리면서 도메인·LLM·DB를 한쪽으로 모은다. Thymeleaf·htmx는 도입하지 않는다 |
| 2026-08-03 | **인증은 프론트(Auth.js)에 남긴다.** 이 서버는 `owner_key`만 받는다 | Auth.js 흐름을 Spring에서 재구현하는 비용이 크고 해시가 BCrypt↔scrypt로 호환되지 않는다. `member` 패키지는 미사용으로 남긴다 |
| 2026-08-03 | `jd_submission.member_id` → `owner_key` (V5) | 인증이 프론트에 있으면 이 서버의 `member`에 행이 없어 FK를 채울 수 없다. 비회원 이력도 이걸로 지원된다 |
| 2026-08-03 | `jd_submission`을 `member` → `submission` 패키지로 이동 | 소유자가 회원이 아니게 되어 `member`에 둘 근거가 사라졌다 |
| 2026-08-03 | 컨텍스트 테스트는 Testcontainers로 DB를 마련한다 | `spring-boot-docker-compose`가 `developmentOnly`라 테스트 classpath에 없어 `contextLoads()`가 항상 실패하고 있었다 |

2026-08-02 결정을 뒤집은 이유: 그 시점에는 프론트가 없다고 보고 서버 하나로 끝내려 했으나,
`jobit-front`가 로드맵 1단계를 이미 구현한 상태였다. 화면을 버리고 Thymeleaf로 다시 만드는 비용이,
API 계약을 하나 두는 비용보다 크다고 판단했다.

## 미결

- [x] ~~**호출자 인증**~~ → `owner_key` HMAC 서명 (2026-08-07, 위 참고)
- [x] ~~**레이트 리밋**~~ → `LlmGuard` (소유자별 시간당 + 전역 일일 비용 + 면접 연습 일별 세션)
- [ ] **비밀키 회전 절차** — 지금은 양쪽을 동시에 바꿔야 하므로 무중단 회전이 안 된다
- [ ] 이력서 원문 암호화 방식 (컬럼 암호화 vs 애플리케이션 레벨)
- [x] ~~LLM SDK 선택~~ → Anthropic Java SDK (2026-08-03) → **Ollama + Qwen3 로 전환, SDK 없이 RestClient 직접 호출** (2026-08-10)
- [ ] `member` 패키지의 최종 처리 — 3단계에서 인증을 가져올지, 삭제할지
- [x] ~~`owner_key` 규약 통일~~ → `common.OwnerKey` (2026-08-03)
- [x] ~~`contextLoads()` DataSource 확보~~ → Testcontainers (2026-08-03)
- [x] ~~익명 세션 키 발급 주체~~ → 프론트 쿠키. 이 서버는 받기만 한다 (2026-08-03)
