package com.febrie.util;

import com.febrie.config.PathConfig;
import com.febrie.manager.file.FileManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 애플리케이션 에러를 상세하게 로깅하는 유틸리티 클래스
 */
public class ErrorLogger {
    private static final Logger log = LoggerFactory.getLogger(ErrorLogger.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /**
     * 예외 정보와 실행 컨텍스트를 파일에 로깅
     * 
     * @param context 실행 컨텍스트 설명 (어디서 발생했는지)
     * @param exception 발생한 예외
     */
    public static void logException(String context, Throwable exception) {
        log.error("오류 발생: {} - {}", context, exception.getMessage(), exception);

        StringBuilder sb = new StringBuilder();
        sb.append("[" + LocalDateTime.now().format(TIME_FORMATTER) + "] ");
        sb.append("실행 컨텍스트: ").append(context).append("\n");
        sb.append("예외 유형: ").append(exception.getClass().getName()).append("\n");
        sb.append("예외 메시지: ").append(exception.getMessage()).append("\n\n");

        // 스택 트레이스 캡처
        StringWriter sw = new StringWriter();
        exception.printStackTrace(new PrintWriter(sw));
        sb.append("스택 트레이스:\n").append(sw.toString()).append("\n");

        // 현재 실행 환경 정보 추가
        sb.append("시스템 정보:\n");
        sb.append("  OS: ").append(System.getProperty("os.name")).append(" ")
          .append(System.getProperty("os.version")).append("\n");
        sb.append("  Java 버전: ").append(System.getProperty("java.version")).append("\n");
        sb.append("  총 메모리: ").append(Runtime.getRuntime().totalMemory() / 1048576).append("MB\n");
        sb.append("  사용 가능 메모리: ").append(Runtime.getRuntime().freeMemory() / 1048576).append("MB\n");
        sb.append("  최대 메모리: ").append(Runtime.getRuntime().maxMemory() / 1048576).append("MB\n");

        // 실행 중인 스레드 정보
        sb.append("\n현재 스레드: ").append(Thread.currentThread().getName())
          .append(" (ID: ").append(Thread.currentThread().getId()).append(")\n");

        // 시스템 환경 정보
        sb.append("\n애플리케이션 정보:\n");
        sb.append("  애플리케이션 이름: Claude 모델 생성 서버\n");
        sb.append("  모델 생성 API: Anthropic Claude, Meshy 3D\n");

        // 로그 파일에 저장
        LogUtility.writeErrorLog(sb.toString());
    }

    /**
     * API 응답 내용을 포함하여 로깅 (API 호출 실패 시 유용)
     * 
     * @param context 실행 컨텍스트
     * @param requestData 요청 데이터
     * @param responseData 응답 데이터 
     * @param exception 발생한 예외 (null일 수 있음)
     */
    public static void logApiFailure(String context, String requestData, String responseData, Throwable exception) {
        StringBuilder sb = new StringBuilder();
        String timestamp = LocalDateTime.now().format(TIME_FORMATTER);
        sb.append("[" + timestamp + "] ");
        sb.append("API 호출 실패: ").append(context).append("\n\n");

        // 요청 및 응답 데이터
        sb.append("요청 데이터:\n").append(requestData).append("\n\n");
        sb.append("응답 데이터:\n").append(responseData).append("\n\n");

        // 예외가 있는 경우 예외 정보 추가
        if (exception != null) {
            sb.append("예외 유형: ").append(exception.getClass().getName()).append("\n");
            sb.append("예외 메시지: ").append(exception.getMessage()).append("\n\n");

            // 스택 트레이스 캡처
            StringWriter sw = new StringWriter();
            exception.printStackTrace(new PrintWriter(sw));
            sb.append("스택 트레이스:\n").append(sw.toString()).append("\n");
        }

        // 로그 파일에 저장
        LogUtility.writeErrorLog(sb.toString());

        // 디버그 로그에도 저장
        FileManager.getInstance().createFile(
            PathConfig.getInstance().getDebugLogFilePath(), 
            sb.toString()
        );
    }
}
