package com.jobit.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@code /api/**} 앞단의 호출자 인증 (docs/api.md, docs/architecture.md).
 *
 * <p><b>왜 인터셉터나 컨트롤러가 아니라 필터인가.</b> 새 엔드포인트를 추가할 때 <b>아무것도 하지
 * 않아도 보호되어야</b> 한다. 컨트롤러마다 검사를 붙이는 방식은 하나만 빠뜨려도 그 경로가
 * 뒷문이 되고, 빠뜨린 사실은 사고가 나야 드러난다. 여기서 한 번 막으면 기본값이 "닫힘"이다.
 *
 * <p>SSE({@code /api/questions})도 여기서 걸린다 — 스트림이 열리기 전이라 정상 응답으로
 * 거절할 수 있다.
 *
 * <p><b>비밀키가 없으면 인증을 건너뛴다.</b> 로컬에서 설정 없이 바로 돌려 볼 수 있어야 하기
 * 때문이다. 대신 부팅 로그에 크게 남기고, {@code prod} 프로파일에서는
 * {@link ServiceAuthConfig}가 아예 뜨지 못하게 막는다 — 운영에서 조용히 열려 있는 것이
 * 가장 나쁘다.
 *
 * <p><b>{@code @Component} 가 아니라 {@link ServiceAuthConfig}가 등록한다.</b> 컴포넌트 스캔에
 * 두면 {@code @WebMvcTest} 슬라이스가 이 필터만 가져가고 설정 클래스는 두고 가서 컨텍스트가
 * 뜨지 않는다. 적용 경로({@code /api/*})도 등록 지점 한 곳에서만 정해진다.
 */
@Slf4j
public class ServiceAuthFilter extends OncePerRequestFilter {

	private final String secret;

	private final Clock clock;

	public ServiceAuthFilter(ServiceAuthConfig config, Clock clock) {
		this.secret = config.secret();
		this.clock = clock;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain chain) throws ServletException, IOException {

		if (secret == null) {
			chain.doFilter(request, response);
			return;
		}

		ServiceAuth.Result result = ServiceAuth.verify(request.getHeader(ServiceAuth.OWNER_HEADER),
				request.getHeader(ServiceAuth.AUTH_HEADER), secret, Instant.now(clock));

		if (result == ServiceAuth.Result.OK) {
			chain.doFilter(request, response);
			return;
		}

		// 이유를 응답에 담지 않는다 — "서명이 틀렸다"와 "만료됐다"를 구분해 주면 공격자가
		// 무엇을 고쳐야 하는지 알게 된다. 운영자는 로그에서 본다.
		log.warn("호출자 인증 실패 ({}): {} {}", result, request.getMethod(),
				request.getRequestURI());

		response.setStatus(HttpStatus.UNAUTHORIZED.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		response.getWriter().write("{\"error\":\"인증되지 않은 요청입니다.\"}");
	}
}
