package bonun.bustime_backend.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import bonun.bustime_backend.dto.ArrivalInfo;
import bonun.bustime_backend.service.StopTimeClient;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/bus")
@RequiredArgsConstructor
public class StopTimeController {

    private final StopTimeClient stopTimeClient;

    @GetMapping
    public List<ArrivalInfo> getArrivalInfo(@RequestParam String nodeId) {
        return stopTimeClient.getArrivalInfoByNodeId(nodeId);
    }
}