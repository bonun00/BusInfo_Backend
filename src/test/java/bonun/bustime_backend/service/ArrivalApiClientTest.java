package bonun.bustime_backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import bonun.bustime_backend.dto.ArrivalItem;

class ArrivalApiClientTest {

	private final ArrivalApiClient client = new ArrivalApiClient(null, new ObjectMapper(), "key", "http://localhost");

	private static String response(String items) {
		return "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":" + items + "}}}";
	}

	private static String item(String routeNo, int arrTime, int stations) {
		return "{\"nodeid\":\"N1\",\"nodenm\":\"정류장\",\"routeid\":\"R" + routeNo + "\",\"routeno\":\"" + routeNo
			+ "\",\"routetp\":\"농어촌버스\",\"vehicletp\":\"일반차량\",\"arrtime\":" + arrTime
			+ ",\"arrprevstationcnt\":" + stations + "}";
	}

	@Test
	void 배열_응답에서_113번과_250번_노선만_남긴다() throws Exception {
		String body = response("{\"item\":[" + item("113-1", 300, 5) + "," + item("11", 100, 1) + ","
			+ item("250", 900, 12) + "]}");

		List<ArrivalItem> items = client.parse(body);

		assertThat(items).extracting(ArrivalItem::routeNo).containsExactly("113-1", "250");
		assertThat(items.get(0).arrTime()).isEqualTo(300);
		assertThat(items.get(0).arrPrevStationCnt()).isEqualTo(5);
		assertThat(items.get(0).nodeId()).isEqualTo("N1");
	}

	@Test
	void 결과가_한_건이면_객체로_와도_파싱한다() throws Exception {
		List<ArrivalItem> items = client.parse(response("{\"item\":" + item("250", 120, 2) + "}"));

		assertThat(items).hasSize(1);
		assertThat(items.get(0).routeNo()).isEqualTo("250");
	}

	@Test
	void 결과가_없으면_빈_목록을_돌려준다() throws Exception {
		assertThat(client.parse(response("\"\""))).isEmpty();
	}
}
