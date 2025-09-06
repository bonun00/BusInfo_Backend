package bonun.bustime_backend.dto;

public record ArrivalInfo(
    String nodeId,
    String nodeNm,
    String routeId,
    String routeNo,
    String routeTp,
    String vehicleTp,
    int arrTime,
    int arrPrevStationCnt,
	long expireAt
) {}