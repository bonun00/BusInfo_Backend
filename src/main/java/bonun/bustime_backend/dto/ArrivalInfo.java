package bonun.bustime_backend.dto;

import java.io.Serializable;

public record ArrivalInfo(
    String nodeId,
    String nodeNm,
    String routeId,
    String routeNo,
    String routeTp,
    String vehicleTp,
    int arrTime,
    int arrPrevStationCnt
)implements Serializable {}