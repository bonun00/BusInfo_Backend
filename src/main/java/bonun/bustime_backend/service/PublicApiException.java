package bonun.bustime_backend.service;

import java.util.Set;

/**
 * 공공 API가 응답은 줬지만 에러 코드를 담아 보낸 경우.
 * 코드는 TAGO 활용가이드 기준 (포털 에러: XML의 returnReasonCode, 제공기관 에러: JSON의 resultCode).
 */
public class PublicApiException extends RuntimeException {

	// 재시도해도 낫지 않아 사람이 조치해야 하는 코드
	// 12 서비스 없음/폐기, 20 접근 거부, 22 요청 한도 초과, 30 미등록 키, 31 활용기간 만료, 32 미등록 IP
	private static final Set<String> FATAL_CODES = Set.of("12", "20", "22", "30", "31", "32");

	private final String code;

	public PublicApiException(String code, String reason) {
		super("공공 API 에러 code=" + code + ", reason=" + reason);
		this.code = code;
	}

	public String code() {
		return code;
	}

	public boolean isFatal() {
		return FATAL_CODES.contains(code);
	}
}
