package com.jobit.resume;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/resumes} 요청 본문 (docs/api.md).
 *
 * <p><b>파일이 아니라 텍스트를 받는다.</b> 면접 연습이 오디오를 받지 않는 것과 같은 판단이다 —
 * 파일을 받으면 PDF·DOCX 파서, 업로드 상한, 임시 파일 정리, 그리고 그 임시 파일이 어디에
 * 남는지까지 전부 개인정보 관리 대상이 된다. 텍스트만 받으면 그 표면이 통째로 사라진다.
 * 붙여넣기는 브라우저가 이미 잘한다.
 *
 * <p>길이 상한은 {@code JdParseRequest} 와 같은 이유로 <b>비용 방어</b>다. 다만 하한이 더 낮다 —
 * 신입 이력서는 공고보다 짧을 수 있다.
 */
public record ResumeUploadRequest(

		@NotBlank(message = "이력서 내용을 입력해 주세요.")
		@Size(min = 50, max = 50_000,
				message = "이력서는 50자 이상 50,000자 이하여야 합니다.") String text) {
}
