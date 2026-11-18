package bonun.bustime_backend.service;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import bonun.bustime_backend.dto.ArrivalItem;
import lombok.extern.slf4j.Slf4j;

// 공공데이터포털(TAGO) 정류소별 도착 예정 정보 API 호출과 파싱
@Component
@Slf4j
public class ArrivalApiClient {

	private static final String PATH = "/getSttnAcctoArvlPrearngeInfoList";

	private final RestTemplate restTemplate;
	private final ObjectMapper objectMapper;
	private final DailyCallCounter callCounter;
	private final String serviceKey;
	private final String baseUrl;

	public ArrivalApiClient(
		RestTemplate restTemplate,
		ObjectMapper objectMapper,
		DailyCallCounter callCounter,
		@Value("${public-api.service-key}") String serviceKey,
		@Value("${public-api.base-url:https://apis.data.go.kr/1613000/ArvlInfoInqireService}") String baseUrl
	) {
		this.restTemplate = restTemplate;
		this.objectMapper = objectMapper;
		this.callCounter = callCounter;
		this.serviceKey = serviceKey;
		this.baseUrl = baseUrl;
	}

	// 113·250번 노선만 반환한다. 호출이나 파싱에 실패하면 예외를 던진다.
	public List<ArrivalItem> fetch(String nodeId) throws JsonProcessingException {
		URI uri = UriComponentsBuilder
			.fromUriString(baseUrl + PATH)
			.queryParam("serviceKey", serviceKey)
			.queryParam("pageNo", 1)
			.queryParam("numOfRows", 100)
			.queryParam("_type", "json")
			.queryParam("cityCode", 38320)
			.queryParam("nodeId", nodeId)
			.build(true)
			.toUri();

		log.info("외부 API 요청: nodeId={}", nodeId);
		callCounter.increment();
		return parse(restTemplate.getForObject(uri, String.class));
	}

	List<ArrivalItem> parse(String body) throws JsonProcessingException {
		if (body == null || body.isBlank()) {
			throw new PublicApiException("EMPTY", "빈 응답");
		}
		// 공공데이터포털 에러는 _type=json이어도 XML로만 온다
		String trimmed = body.stripLeading();
		if (trimmed.startsWith("<")) {
			throw new PublicApiException(
				between(trimmed, "<returnReasonCode>", "</returnReasonCode>"),
				between(trimmed, "<returnAuthMsg>", "</returnAuthMsg>"));
		}

		JsonNode response = objectMapper.readTree(body).path("response");
		// 제공기관 에러는 정상 JSON 구조에 resultCode로 온다 (00이 정상)
		JsonNode header = response.path("header");
		String resultCode = header.path("resultCode").asText();
		if (!"00".equals(resultCode)) {
			throw new PublicApiException(resultCode, header.path("resultMsg").asText());
		}

		JsonNode itemsNode = response.path("body").path("items").path("item");

		// 결과가 한 건이면 배열이 아니라 객체로, 없으면 빈 문자열로 온다
		List<JsonNode> nodes = new ArrayList<>();
		if (itemsNode.isArray()) {
			itemsNode.forEach(nodes::add);
		} else if (itemsNode.isObject()) {
			nodes.add(itemsNode);
		}

		List<ArrivalItem> items = new ArrayList<>();
		for (JsonNode node : nodes) {
			String routeNo = node.path("routeno").asText();
			if (routeNo.startsWith("113") || routeNo.startsWith("250")) {
				items.add(new ArrivalItem(
					node.path("nodeid").asText(),
					node.path("nodenm").asText(),
					node.path("routeid").asText(),
					routeNo,
					node.path("routetp").asText(),
					node.path("vehicletp").asText(),
					node.path("arrtime").asInt(),
					node.path("arrprevstationcnt").asInt()
				));
			}
		}
		return items;
	}

	private static String between(String text, String start, String end) {
		int from = text.indexOf(start);
		if (from < 0) {
			return "UNKNOWN";
		}
		from += start.length();
		int to = text.indexOf(end, from);
		return to < 0 ? "UNKNOWN" : text.substring(from, to).trim();
	}
}
