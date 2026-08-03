# 아키텍처 — Spring 구현 매핑

스펙(`jd-interview-prep-spec.md`)은 스택 중립으로 쓰여 있다. 이 문서는 그것을 Spring Boot 구현으로
옮길 때의 결정만 담는다. **제품 요구사항·데이터 모델·흐름은 스펙이 원본이므로 여기 복제하지 않는다.**

## 패키지 구조

도메인형으로 나눈다. 스펙 §3의 테이블 묶음이 그대로 패키지 경계가 된다.

```
com.jobit/
  jd/          job_posting, requirement          — JD 파싱, 캐시(content_hash)
  question/    question_set, question            — 질문 생성
  resume/      resume, resume_bullet             — 업로드, bullet 분해, 임베딩
  gap/         gap_analysis, gap_item,
               rewrite_suggestion                — 2단계 갭 분석, 리라이트
  member/      member, jd_submission             — 인증, 입력 이력 (스펙 §3.6)
  llm/         llm_call_log                      — 클라이언트, 구조화 출력, 비용 로깅
  common/                                        — 공통 예외, 응답 포맷, 설정
```

`jd_submission`은 `member` 패키지에 둔다. `member`와 `job_posting`을 잇지만 소유자는 회원이고,
조회 진입점이 전부 `/me/*`(스펙 §4.6)이기 때문이다.

`llm`은 다른 모든 패키지가 의존하는 하위 레이어다. 반대 방향(도메인 → `llm` 외 도메인) 의존은 만들지 않는다.

## 레이어 규칙

- **Controller** — 요청/응답 변환, 검증, SSE 스트림 열기. 비즈니스 로직 없음.
- **Service** — 트랜잭션 경계, 도메인 조합, LLM 호출 오케스트레이션.
- **Repository** — 영속성.

금지: Controller가 Repository 직접 호출.

## 스택 고유 결정사항

### pgvector

`resume_bullet.embedding vector(1536)`은 JPA 표준 타입이 아니다. Hibernate 커스텀 타입 매핑 또는
네이티브 쿼리로 처리해야 한다. 유사도 검색(코사인 상위 3개)은 네이티브 쿼리가 현실적이다.

### SSE 스트리밍

스펙이 "체감 품질을 좌우한다"고 명시한 부분이다. Spring MVC에서는 `SseEmitter` 또는
`ResponseBodyEmitter`를 쓴다. htmx는 SSE 확장(`hx-ext="sse"`)으로 수신한다.

> 스펙 §2가 Next를 기준안으로 삼은 이유 중 하나가 스트리밍 구현 편의였다. Spring에서는 이 부분에
> 명시적인 작업이 필요하다는 것을 인지하고 간다.

### 구조화 출력

LLM 응답 JSON을 DTO로 역직렬화한 뒤 **서버에서 재검증**하고, 실패 시 재시도한다 (스펙 §6 체크리스트).
검증 실패를 그대로 저장하지 않는다.

## 결정 기록

| 날짜 | 결정 | 이유 |
| --- | --- | --- |
| 2026-08-02 | 스키마 관리는 Flyway 마이그레이션 (`ddl-auto` 미사용) | 운영 DB 변경 추적 |
| 2026-08-02 | 스펙 §7 프론트 구성 → **Spring Boot 단일 + Thymeleaf + htmx** | 익숙한 스택, 서버 하나. 로드맵 4단계에서 재검토 |
| 2026-08-02 | 벡터 DB 분리하지 않고 `pgvector` 사용 | 스펙 §2. 인프라 추가 시 관리 비용만 증가 |

## 미결

- [ ] 패키지 구조 확정 (위 안대로 갈지)
- [ ] LLM SDK 선택 및 build.gradle 추가
- [ ] 익명 세션 키(`owner_key`) 발급 방식
- [ ] 이력서 원문 암호화 방식 (컬럼 암호화 vs 애플리케이션 레벨)
