package com.febrie.service;

import com.febrie.dto.ScriptResponse;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 스크립트 생성 서비스
 */
public class ScriptService {

    private static final Logger log = LoggerFactory.getLogger(ScriptService.class);

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 스크립트 생성
     * 
     * @param puzzleData 퍼즐 데이터
     * @param roomPrefabUrl 방 프리팹 URL
     * @return 스크립트 생성 응답
     */
    public ScriptResponse generateScript(JsonObject puzzleData, String roomPrefabUrl) {
        log.info("스크립트 생성 시작 - Room Prefab URL: {}", roomPrefabUrl);

        long startTime = System.currentTimeMillis();

        // 스크립트 생성 로직 구현 필요
        // 여기서는 간단한 예제로 구현

        JsonObject scriptData = new JsonObject();
        scriptData.addProperty("scriptVersion", "1.0");
        scriptData.addProperty("generatedAt", LocalDateTime.now().format(DATE_FORMATTER));

        // 퍼즐 데이터 처리
        JsonObject processedPuzzleData = processPuzzleData(puzzleData);
        scriptData.add("puzzleScript", processedPuzzleData);

        long processingTime = System.currentTimeMillis() - startTime;
        log.info("스크립트 생성 완료 - 처리 시간: {} ms", processingTime);

        // 응답 생성
        ScriptResponse response = new ScriptResponse();
        response.setPuid(extractPuidFromUrl(roomPrefabUrl));
        response.setStatus("success");
        response.setScriptData(scriptData);
        response.setProcessingTimeMs(processingTime);
        response.setCreatedAt(LocalDateTime.now().format(DATE_FORMATTER));

        return response;
    }

    /**
     * 스크립트 상태 조회
     * 
     * @param puid 프로젝트 고유 ID
     * @return 스크립트 상태 응답
     */
    public ScriptResponse getScriptStatus(String puid) {
        log.info("스크립트 상태 조회 - PUID: {}", puid);

        // 스크립트 상태 조회 로직 구현 필요
        // 여기서는 간단한 예제로 구현

        ScriptResponse response = new ScriptResponse();
        response.setPuid(puid);

        // 스크립트가 있는 경우
        if (isScriptExist(puid)) {
            response.setStatus("success");
            response.setCreatedAt(LocalDateTime.now().minusMinutes(5).format(DATE_FORMATTER));
            response.setScriptData(getStoredScriptData(puid));
        } else {
            response.setStatus("not_found");
            response.setErrorMessage("스크립트를 찾을 수 없습니다.");
        }

        return response;
    }

    /**
     * URL에서 PUID 추출
     */
    private String extractPuidFromUrl(String url) {
        // 실제 URL에서 PUID를 추출하는 로직 구현 필요
        // 여기서는 간단한 예시 제공
        if (url != null && url.contains("puid=")) {
            return url.split("puid=")[1].split("&")[0];
        }
        return "unknown-puid";
    }

    /**
     * 퍼즐 데이터 처리
     */
    private JsonObject processPuzzleData(JsonObject puzzleData) {
        // 실제 퍼즐 데이터 처리 로직 구현 필요
        // 여기서는 원본 데이터를 그대로 반환
        return puzzleData;
    }

    /**
     * 스크립트 존재 여부 확인
     */
    private boolean isScriptExist(String puid) {
        // 실제 스크립트 존재 여부 확인 로직 구현 필요
        // 여기서는 간단한 예시 제공 (항상 true 반환)
        return true;
    }

    /**
     * 저장된 스크립트 데이터 조회
     */
    private JsonObject getStoredScriptData(String puid) {
        // 실제 저장된 스크립트 데이터 조회 로직 구현 필요
        // 여기서는 간단한 예시 제공
        JsonObject scriptData = new JsonObject();
        scriptData.addProperty("scriptVersion", "1.0");
        scriptData.addProperty("puid", puid);
        scriptData.addProperty("generatedAt", LocalDateTime.now().minusMinutes(5).format(DATE_FORMATTER));
        return scriptData;
    }
}
