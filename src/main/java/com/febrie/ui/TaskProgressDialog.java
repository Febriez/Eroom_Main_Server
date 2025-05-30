package com.febrie.ui;

import com.febrie.api.MeshyTextTo3D;
import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Meshy 태스크 진행 상황을 표시하는 디버그 창
 */
@Slf4j
public class TaskProgressDialog extends JDialog {

    private JProgressBar progressBar;
    private JLabel statusLabel;
    private JLabel taskIdLabel;
    private JLabel elapsedTimeLabel;
    private JLabel currentProgressLabel;
    private JTextArea logTextArea;
    private JScrollPane scrollPane;
    private Timer timer;
    private long startTime;
    private boolean isCancelled = false;

    /**
     * 디버그 창 생성자
     * 
     * @param taskId 태스크 ID
     * @param taskType 태스크 유형
     */
    public TaskProgressDialog(String taskId, String taskType) {
        super((Frame) null, taskType.toUpperCase() + " 태스크 진행 상황", false);
        initializeUI(taskId, taskType);
        startTimer();
    }

    /**
     * UI 초기화
     * 
     * @param taskId 태스크 ID
     * @param taskType 태스크 유형
     */
    private void initializeUI(String taskId, String taskType) {
        setSize(500, 400);
        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        setLocationRelativeTo(null);

        // 메인 패널
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // 상단 정보 패널
        JPanel infoPanel = new JPanel(new GridLayout(4, 1, 5, 5));
        taskIdLabel = new JLabel("태스크 ID: " + taskId);
        statusLabel = new JLabel("상태: 대기 중...");
        elapsedTimeLabel = new JLabel("경과 시간: 0초");
        currentProgressLabel = new JLabel("진행률: 0%");

        infoPanel.add(taskIdLabel);
        infoPanel.add(statusLabel);
        infoPanel.add(elapsedTimeLabel);
        infoPanel.add(currentProgressLabel);

        // 진행 상황 바
        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);

        // 로그 영역
        logTextArea = new JTextArea();
        logTextArea.setEditable(false);
        logTextArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        scrollPane = new JScrollPane(logTextArea);
        scrollPane.setPreferredSize(new Dimension(450, 250));

        // 로그 추가
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
        appendLog("[" + sdf.format(new Date()) + "] " + taskType.toUpperCase() + " 태스크 모니터링 시작 - ID: " + taskId);

        // 하단 버튼 패널
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton closeButton = new JButton("닫기");
        closeButton.addActionListener(e -> {
            isCancelled = true;
            dispose();
        });
        buttonPanel.add(closeButton);

        // 컴포넌트 배치
        mainPanel.add(infoPanel, BorderLayout.NORTH);
        mainPanel.add(progressBar, BorderLayout.CENTER);
        mainPanel.add(scrollPane, BorderLayout.SOUTH);
        getContentPane().add(mainPanel, BorderLayout.CENTER);
        getContentPane().add(buttonPanel, BorderLayout.SOUTH);

        // 창 닫기 이벤트 처리
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                isCancelled = true;
                if (timer != null) {
                    timer.stop();
                }
            }
        });

        startTime = System.currentTimeMillis();
        setVisible(true);
    }

    /**
     * 타이머 시작 (경과 시간 업데이트용)
     */
    private void startTimer() {
        timer = new Timer(1000, e -> updateElapsedTime());
        timer.start();
    }

    /**
     * 경과 시간 업데이트
     */
    private void updateElapsedTime() {
        long elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000;
        long minutes = elapsedSeconds / 60;
        long seconds = elapsedSeconds % 60;
        elapsedTimeLabel.setText(String.format("경과 시간: %d분 %d초", minutes, seconds));
    }

    /**
     * 로그 메시지 추가
     * 
     * @param message 로그 메시지
     */
    public void appendLog(String message) {
        SwingUtilities.invokeLater(() -> {
            logTextArea.append(message + "\n");
            logTextArea.setCaretPosition(logTextArea.getDocument().getLength());
        });
    }

    /**
     * 진행 상황 업데이트
     * 
     * @param status 태스크 상태 객체
     */
    public void updateProgress(MeshyTextTo3D.TaskStatus status) {
        if (isCancelled) return;

        SwingUtilities.invokeLater(() -> {
            // 진행률 업데이트
            progressBar.setValue(status.progress);
            currentProgressLabel.setText("진행률: " + status.progress + "%");

            // 상태 업데이트
            statusLabel.setText("상태: " + getStatusText(status.status));

            // 로그에 기록
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
            String timeStr = sdf.format(new Date());
            appendLog("[" + timeStr + "] 진행률: " + status.progress + "%, 상태: " + status.status);

            // 완료 처리
            if ("SUCCEEDED".equals(status.status) || "FAILED".equals(status.status)) {
                appendLog("[" + timeStr + "] 태스크 완료: " + status.status);
                if ("SUCCEEDED".equals(status.status)) {
                    appendLog("[" + timeStr + "] 썸네일 URL: " + (status.thumbnailUrl != null ? status.thumbnailUrl : "없음"));
                    if (status.modelUrls != null) {
                        appendLog("[" + timeStr + "] GLB URL: " + (status.modelUrls.glb != null ? status.modelUrls.glb : "없음"));
                    }
                } else {
                    appendLog("[" + timeStr + "] 오류 메시지: " + (status.error != null ? status.error : "알 수 없는 오류"));
                }
            }
        });
    }

    /**
     * 상태 코드를 한글 텍스트로 변환
     * 
     * @param status API 상태 코드
     * @return 한글 상태 텍스트
     */
    private String getStatusText(String status) {
        if (status == null) return "알 수 없음";

        return switch (status) {
            case "PENDING" -> "대기 중";
            case "IN_PROGRESS" -> "진행 중";
            case "SUCCEEDED" -> "성공";
            case "FAILED" -> "실패";
            case "CANCELED" -> "취소됨";
            default -> status;
        };
    }

    /**
     * 디버그 창 닫기
     */
    public void close() {
        if (timer != null) {
            timer.stop();
        }
        dispose();
    }

    /**
     * 취소 여부 확인
     * 
     * @return 취소 여부
     */
    public boolean isCancelled() {
        return isCancelled;
    }
}
