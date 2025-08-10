package bonun.bustime_backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import bonun.bustime_backend.dto.ArrivalInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@RequiredArgsConstructor
@Service
@Slf4j
public class StopTimeClient {

    @Value("${public-api.service-key}")
    private String serviceKey;

    private final ObjectMapper objectMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    private final RestTemplate restTemplate = new RestTemplate();

    private static final Duration TTL = Duration.ofSeconds(60);

    public List<ArrivalInfo> getArrivalInfoByNodeId(String nodeId) {
        String redisKey = "arrivalInfo:" + nodeId;


        Object cached = redisTemplate.opsForValue().get(redisKey);
        if (cached != null && cached instanceof List<?>) {
            log.info("Redis 캐시에서 조회됨: {}", redisKey);
            return (List<ArrivalInfo>) cached;
        }


        try {
            URI uri = UriComponentsBuilder
                .fromHttpUrl("https://apis.data.go.kr/1613000/ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList")
                .queryParam("serviceKey", serviceKey)
                .queryParam("pageNo", 1)
                .queryParam("numOfRows", 100)
                .queryParam("_type", "json")
                .queryParam("cityCode", 38320)
                .queryParam("nodeId", nodeId)
                .build(true)
                .toUri();

            log.info("외부 API 요청: {}", uri);
            ResponseEntity<String> response = restTemplate.getForEntity(uri, String.class);

            JsonNode itemsNode = objectMapper.readTree(response.getBody())
                .path("response").path("body").path("items").path("item");

            List<ArrivalInfo> result = new ArrayList<>();

            if (itemsNode.isArray()) {
                for (JsonNode item : itemsNode) {
                    String routeno = item.path("routeno").asText();
                    if (routeno.startsWith("113") || routeno.startsWith("250")) {
                        result.add(parseArrivalInfo(item));
                    }
                }
            } else if (itemsNode.isObject()) {
                String routeno = itemsNode.path("routeno").asText();
                if (routeno.startsWith("113") || routeno.startsWith("250")) {
                    result.add(parseArrivalInfo(itemsNode));
                }
            }

            log.info(" API 조회 후 Redis에 저장: {}", redisKey);
            redisTemplate.opsForValue().set(redisKey, result, TTL);

            return result;

        } catch (Exception e) {
            log.error("API 호출 실패 - nodeId={}", nodeId, e);
            return Collections.emptyList();
        }
    }

    private ArrivalInfo parseArrivalInfo(JsonNode item) {
        return new ArrivalInfo(
            item.path("nodeid").asText(),
            item.path("nodenm").asText(),
            item.path("routeid").asText(),
            item.path("routeno").asText(),
            item.path("routetp").asText(),
            item.path("vehicletp").asText(),
            item.path("arrtime").asInt() / 60,
            item.path("arrprevstationcnt").asInt()
        );
    }
}