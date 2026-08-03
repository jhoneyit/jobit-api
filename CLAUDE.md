# jobit (API 서버)

JD(채용공고) 기반 기술 면접 준비 + 이력서 첨삭 서비스의 **백엔드**.

제품 스펙은 `docs/jd-interview-prep-spec.md`가 원본이다. **제품 판단이 필요하면 먼저 읽는다.**

## 두 레포 구성 (중요)

이 제품은 레포 두 개로 나뉜다. 나란히 체크아웃해 두고 쓴다.

```
프로젝트/
  jobit/        ← 여기. Spring Boot REST API + LLM + DB
  jobit-front/  Next.js. UI/SSR 전용, 이 서버를 fetch 로 호출
```

- **경계**: 도메인 로직·LLM 호출·영속성은 전부 이쪽이다. `jobit-front`는 화면을 그린다.
- **인증은 프론트(Auth.js)에 남긴다.** 이 서버는 자격증명을 보지 않고, 호출 시 넘어오는
  `owner_key`로 소유자를 식별할 뿐이다. 그래서 **이 서버의 `member` 테이블에는 행이 생기지 않는다**
  — 회원은 프론트의 `user` 테이블에 있다. `member` 패키지는 현재 미사용이다.
- **API 계약의 원본은 `docs/api.md`**다. 엔드포인트를 바꾸면 여기부터 고치고 프론트를 맞춘다.
- `jobit-front`에는 **아직 자체 구현(Drizzle DB·LLM 호출)이 남아 있다.** 그쪽이 이 서버를
  호출하도록 옮기는 것은 진행 예정 작업이다. 자세한 현황은 `jobit-front/README.md`.

> **호출자 인증이 아직 없다.** `owner_key`만 알면 남의 이력을 읽을 수 있으므로
> 이 서버를 공개망에 노출하면 안 된다 (`docs/architecture.md` 미결).

## 핵심 구조 (한 문단)

JD를 파싱해 `requirement` 목록을 뽑고, 이 하나의 자산을 **질문 생성**과 **이력서 갭 분석** 양쪽에서
재사용한다. `requirement`가 두 기능의 공통 앵커라는 점이 설계의 중심이다 — 질문도 요구사항에서
파생되고, 갭 판정도 요구사항 기준으로 내려진다.

```
JD 텍스트 → [파싱] → requirement[] ─┬→ question (질문·꼬리질문·답변뼈대)
                                   └→ gap_item (MET/WEAK/MISSING) → rewrite_suggestion
```

## 스택

- Java 21 (Gradle toolchain), Spring Boot 4.1.0, Gradle wrapper
- Spring MVC, Spring Data JPA, Bean Validation
- PostgreSQL + Flyway, `pgvector` 확장 (임베딩 기반 후보 추림에 사용)
- `spring-security-crypto` (BCrypt strength 12) — 해싱만. `starter-security`는 넣지 않았다
- Lombok, DevTools
- LLM: API 종량제, 구조화 출력(JSON schema) 필수 (**아직 의존성 미추가**)

### 스택 결정 (중요)

스펙 §2의 기준안은 **Next.js 단일 레포**였고, 2026-08-02에는 **Spring Boot 단일 + Thymeleaf +
htmx**로 정했었다. 둘 다 현재 결정이 아니다.

**2026-08-03 확정: Spring = REST API 서버 / Next(`jobit-front`) = 프론트.**
따라서 **Thymeleaf·htmx는 도입하지 않는다.** 응답은 HTML 조각이 아니라 JSON이고,
질문 생성 스트리밍은 SSE(`text/event-stream`)를 브라우저의 `EventSource`가 직접 받는다.

**§3 데이터 모델과 §4 핵심 흐름은 스택과 무관하게 그대로 적용된다** (스펙 명시).

## 명령어

```bash
./gradlew build          # 컴파일 + 테스트
./gradlew test           # 테스트만
./gradlew bootRun        # 앱 실행 (Docker 실행 중이어야 함 — 아래 참고)
./gradlew test --tests '*ClassName*'   # 단일 테스트
```

### 로컬 DB

`spring-boot-docker-compose`가 `compose.yaml`을 감지해 Postgres 컨테이너를 자동 기동하고
DataSource를 연결한다. **`application.properties`에 접속 정보를 쓰지 않는다** — 수동 설정을
추가하면 자동 연결과 충돌한다.

- Docker Desktop이 떠 있어야 `bootRun`이 동작한다.
- 이미지는 `pgvector/pgvector:pg17` (기본 postgres 이미지 아님). `vector` 확장이 필요하다.
- 컨테이너 데이터는 `jobit-pgdata` 볼륨에 유지된다. 초기화하려면 `docker compose down -v`.

> **이 자동 연결은 테스트에는 적용되지 않는다.** `spring-boot-docker-compose`가
> `developmentOnly` 스코프라 테스트 classpath에 없다. 그래서 DB가 필요한 테스트는 접속 정보를
> 스스로 마련해야 한다 (아래 "현재 상태" 참고).

## 구조

```
src/main/java/com/jobit/
  jd/          JD 파싱, requirement 추출
  question/    질문 생성
  resume/      이력서 업로드, bullet 분해
  gap/         갭 분석, 리라이트 제안
  submission/  JD 입력 이력 (owner_key 소유)
  member/      회원, 로컬 가입, 비밀번호 재설정 — 현재 미사용
  llm/         LLM 클라이언트, 구조화 출력, 비용 로그
  common/      OwnerKey, PasswordConfig, TimeConfig
src/main/resources/
  db/migration/  Flyway (V1~V5)
```

레이어 규칙과 패키지 경계 근거는 `docs/architecture.md`에 있다.

### owner_key

개인 자산의 소유자 식별자. 규약은 `common.OwnerKey`가 강제한다 — **접두사가 규약의 전부다.**

```
로그인   user:<user_id>    ← jobit-front 의 user 테이블 ID
비로그인 anon:<세션 쿠키>
```

회원 ID와 익명 세션 키가 한 컬럼을 공유하므로 접두사가 없으면 두 네임스페이스가 충돌한다.
새로 만드는 조회 경로는 `owner_key` 하나만 보고, 로그인 여부로 분기하지 않는다.

### 비밀값

키 목록은 `.env.example` 참고. 실제 값은 커밋하지 않는다.

**`ANTHROPIC_API_KEY`는 OS 환경변수로 넣는다.** Anthropic Java SDK의
`AnthropicOkHttpClient.fromEnv()`는 OS 환경변수만 읽는다 —
`application-local.properties`나 `spring.config.import`로 로드한 값은 Spring Environment에만
올라가므로 `fromEnv()`가 찾지 못한다. Spring 프로퍼티로 관리하려면 `@Value`로 주입해
`AnthropicOkHttpClient.builder().apiKey(...)`를 직접 호출해야 한다.

## 문서

- `docs/jd-interview-prep-spec.md` — **제품 스펙 원본**. 데이터 모델(§3), 핵심 흐름(§4), 로드맵(§5)
- `docs/architecture.md` — Spring 구현 매핑, 레이어 규칙, 두 레포 경계, 결정 기록
- `docs/api.md` — **엔드포인트 명세 = 프론트와의 계약 원본**

## 작업 원칙

스펙에서 도출된, 구현 시 반드시 지킬 것들.

- **MISSING 항목은 절대 지어내지 않는다.** 근거 없는 요구사항에 대해 이력서 문장을 창작하는 코드나
  프롬프트를 작성하지 말 것. "근거 없음"을 그대로 노출하고 질문 생성으로 넘긴다. (스펙 §4.5)
- **리라이트는 문장 단위.** 이력서 전체를 LLM에 보내지 않는다. `resume_bullet` 하나씩 처리한다.
- **갭 분석은 2단계.** 임베딩으로 후보 3개 추림 → LLM은 판정만. 요구사항×문장 전수 LLM 호출 금지.
- **캐시 먼저.** JD는 `content_hash`, 갭 분석은 `(resume_id, job_posting_id)` 유니크로 재사용.
- **`llm_call_log`는 처음부터.** LLM 호출 경로를 새로 만들면 비용 로깅을 함께 넣는다.
- **개인정보.** 이력서 원문은 암호화 저장 + `expires_at` TTL. 로그에 원문을 남기지 않는다.
- **스키마 변경은 Flyway로.** `ddl-auto` 사용 금지. 적용된 마이그레이션은 수정하지 말고 새 버전 추가.

## 현재 상태

**로드맵 1단계의 절반 — JD 파싱이 끝까지 동작한다. 질문 생성이 다음이다.**

있는 것:

- **`POST /api/jd/parse` 동작** (docs/api.md). 정규화 → 캐시 → LLM 파싱 → 저장까지 전 경로
- **LLM 연동** — Anthropic Java SDK, 구조화 출력 + 서버 재검증 + 3회 재시도, `llm_call_log` 비용 기록
- 엔티티 + 리포지토리 전체 (`jd` / `question` / `resume` / `gap` / `submission` / `member` / `llm`)
- Flyway V1~V5 — pgvector 확장, 코어 스키마, 로컬 가입 컬럼, `password_reset_token`,
  `owner_key` 전환 + 유니크 제약
- 서비스: `JdParsingService`(캐시·경합 처리),
  `JdSubmissionService`(이력 목록 + 갭 요약, N+1 회피, 익명→계정 승계)
- 미사용 서비스: `LocalAccountService`, `PasswordResetService`, `MemberService` — 인증이 프론트에 있다
- 테스트 70개 전부 통과. `contextLoads()`가 Testcontainers로 실제 Postgres를 띄워
  **Flyway 마이그레이션·엔티티 매핑·JPQL을 매번 검증한다** — 여기가 유일한 통합 검증 지점이다

없는 것 (= 다음 작업 후보):

- **질문 생성** (스펙 §4.2). `QuestionSet`/`Question` 엔티티만 있고 로직이 없다.
  SSE 스트리밍 + 증분 파서가 필요하다 (docs/architecture.md 참고)
- 레이트 리밋 — 지금은 호출 한도가 없다. 프론트에만 있고 이 서버에는 없다
- 갭 분석·리라이트 (3~4단계)
- pgvector Hibernate 타입 매핑 — `resume_bullet.embedding`은 JPA 표준 타입이 아니다
- 인증 관련 컨트롤러 — 인증이 프론트에 있으므로 당분간 필요 없다

### LLM 연동에서 알아둘 것

- **API 키가 없으면 `AnthropicJdParser` 빈이 등록되지 않고** `JdParserFallbackConfig`의 폴백이
  자리를 지킨다. 앱은 뜨고, 파싱을 호출하면 명확한 예외가 난다 (캐시 적중은 정상 동작).
  이 갈림을 `JdParserWiringTest`가 양쪽 다 고정한다.
- **`outputConfig(Class)`가 effort를 조용히 지운다.** SDK가 클래스에서 스키마를 파생시키며
  `OutputConfig`를 통째로 새로 만들기 때문이다. `StructuredOutput.withEffort`로 다시 조립해야
  하고, 이걸 놓치면 파싱이 기본 effort(high)로 돌아 비용이 몇 배가 된다.
  `JdParseParamsTest`가 막고 있다 — SDK를 올릴 때 이 테스트가 깨지면 거기부터 본다.
- **`thinking`을 끄지 않는다.** Opus 5에서 끄면 도구 호출이 일반 텍스트로 새거나
  `<thinking>` 태그가 응답에 섞이는 실패 모드가 있다. 비용은 effort로 낮춘다.
- **재시도도 돈이 나가므로 시도마다 `llm_call_log`에 기록한다.**

### 알려진 문제

- **호출자 인증이 없다.** `owner_key`는 프론트가 HTTP로 넘기는 값이라, 지어내면 남의 이력을
  읽을 수 있다. `OwnerKey.requireValid`는 형식만 본다. 공개망에 노출하지 말 것.
- **테스트에 Docker가 필요하다.** `contextLoads()`가 Testcontainers로 Postgres를 띄운다.
  Docker가 없으면 이 테스트만 실패한다.

## 주의

**Boot 4 기준.** 의존성 좌표가 Boot 3과 다르다 (`spring-boot-starter-web` → `spring-boot-starter-webmvc`).
Boot 3 문서를 그대로 따르지 말 것.
