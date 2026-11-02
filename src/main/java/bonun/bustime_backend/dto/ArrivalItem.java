package bonun.bustime_backend.dto;

// 공공 API 응답 한 건 (도착 예정 시각을 계산하기 전의 원본 값)
public record ArrivalItem(
	String nodeId,
	String nodeNm,
	String routeId,
	String routeNo,
	String routeTp,
	String vehicleTp,
	int arrTime,
	int arrPrevStationCnt
) {

	public ArrivalInfo toArrivalInfo(long expireAt) {
		return new ArrivalInfo(nodeId, nodeNm, routeId, routeNo, routeTp, vehicleTp, arrTime, arrPrevStationCnt,
			expireAt);
	}
}
