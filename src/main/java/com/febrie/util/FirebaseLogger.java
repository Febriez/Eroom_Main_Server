package com.febrie.util;

import com.febrie.Main;
import com.febrie.log.FirebaseLogData;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.cloud.FirestoreClient;
import com.google.gson.Gson;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

public class FirebaseLogger {
    private static final Logger log = LoggerFactory.getLogger(FirebaseLogger.class);

    private static final String COLLECTION_NAME = "Server_Log";
    private static final Gson GSON = new Gson();

    @NotNull
    private static String generateTimeBasedId() {
        LocalDateTime now = LocalDateTime.now();
        return now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH:mm:ss.SSS"));
    }

    public static void saveServerLogSync(FirebaseLogData logData) {
        try {
            // Main.app이 null이면 로깅 건너뛰기
            if (Main.app == null) {
                log.warn("Firebase 앱이 초기화되지 않아 로그를 저장할 수 없습니다.");
                return;
            }

            long startTime = System.currentTimeMillis();
            log.debug("Firebase 로그 저장 시작");

            Firestore db = FirestoreClient.getFirestore(Main.app);
            String documentId = generateTimeBasedId();

            // TypeToken을 사용하여 올바른 제네릭 타입 정보 제공
            String jsonString = logData.toJson().toString();
            log.debug("직렬화할 JSON 문자열: {}", jsonString);

            Map<String, Object> documentData = GSON.fromJson(
                    jsonString,
                    new com.google.gson.reflect.TypeToken<Map<String, Object>>() {
                    }.getType()
            );

            // 변환된 맵 내용 디버깅 로그
            log.debug("Firestore에 저장될 맵 크기: {}", documentData.size());
            if (log.isTraceEnabled()) {
                documentData.forEach((key, value) ->
                        log.trace("맵 항목 - 키: {}, 값 유형: {}", key,
                                (value != null ? value.getClass().getName() : "null"))
                );
            }

            db.collection(COLLECTION_NAME)
                    .document(documentId)
                    .set(documentData)
                    .get();

            log.debug("Firebase 로그 저장 완료 - 소요시간: {}ms", (System.currentTimeMillis() - startTime));

            // 로컬 로그 파일에도 동일한 정보 저장
            boolean isSuccess = logData.isSuccess();
            String logMessage = String.format("UUID: %s, PUID: %s, 상태: %s\n%s",
                    logData.getUuid(), logData.getPuid(), logData.getStatus(), logData.toJson());
            LogUtility.writeLog(isSuccess, logMessage);
        } catch (Exception e) {
            log.error("Firebase 로그 저장 실패: {}", e.getMessage(), e);

            // 상세한 오류 정보 구성
            StringBuilder errorDetails = new StringBuilder();
            errorDetails.append("=== Firebase 로그 저장 실패 상세 정보 ===").append("\n");
            errorDetails.append("시간: ").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\n");
            errorDetails.append("오류 메시지: ").append(e).append("\n");
            errorDetails.append("오류 클래스: ").append(e.getClass().getName()).append("\n\n");

            // 로그 데이터 정보 추가
            if (logData != null) {
                errorDetails.append("로그 데이터 정보:\n");
                errorDetails.append("UUID: ").append(logData.getUuid()).append("\n");
                errorDetails.append("PUID: ").append(logData.getPuid()).append("\n");
                errorDetails.append("상태: ").append(logData.getStatus()).append("\n");
                errorDetails.append("JSON 내용: ").append(logData.toJson()).append("\n\n");
            }

            // 스택 트레이스 추가
            errorDetails.append("스택 트레이스:\n");
            for (StackTraceElement element : e.getStackTrace()) {
                errorDetails.append("  at ").append(element.toString()).append("\n");
            }

            // 원인 예외가 있는 경우 추가
            Throwable cause = e.getCause();
            if (cause != null) {
                errorDetails.append("\n원인 예외:\n");
                errorDetails.append("클래스: ").append(cause.getClass().getName()).append("\n");
                errorDetails.append("메시지: ").append(cause.getMessage()).append("\n");
                errorDetails.append("스택 트레이스:\n");
                for (StackTraceElement element : cause.getStackTrace()) {
                    errorDetails.append("  at ").append(element.toString()).append("\n");
                }
            }

            // 실패한 경우에도 로컬 로그 파일에 상세 오류 정보 저장
            LogUtility.writeErrorLog(errorDetails.toString());
        }
    }
}
