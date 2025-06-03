package com.febrie.util;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import lombok.Getter;

import java.io.IOException;
import java.io.InputStream;

@Getter
public class FirebaseManager {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FirebaseManager.class);

    private static class LazyHolder {
        private static final FirebaseManager INSTANCE = new FirebaseManager();
    }

    private FirebaseApp firebaseApp;

    private FirebaseManager() {
        initializeFirebase();
    }

    public static FirebaseManager getInstance() {
        return LazyHolder.INSTANCE;
    }

    private void initializeFirebase() {
        try {
            log.info("Firebase 초기화 시작");

            String serviceAccountPath = System.getenv("FIREBASE_SERVICE_ACCOUNT_PATH");
            InputStream serviceAccount;

            if (serviceAccountPath != null && !serviceAccountPath.isEmpty()) {
                try {
                    serviceAccount = new java.io.FileInputStream(serviceAccountPath);
                    log.info("환경 변수에 지정된 경로에서 Firebase 서비스 계정 키 파일을 로드했습니다: {}", serviceAccountPath);
                } catch (IOException e) {
                    log.warn("환경 변수에 지정된 경로에서 파일을 찾을 수 없습니다: {}", serviceAccountPath);
                    serviceAccount = getClass().getClassLoader().getResourceAsStream("serviceAccountKey.json");
                }
            } else {
                serviceAccount = getClass().getClassLoader().getResourceAsStream("serviceAccountKey.json");
            }

            if (serviceAccount == null) {
                log.warn("serviceAccountKey.json 파일을 찾을 수 없습니다. Firebase 초기화가 무시됩니다.");
                return;
            }

            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                    .build();

            try {
                firebaseApp = FirebaseApp.getInstance();
                log.info("기존 Firebase 앱이 사용됩니다: {}", firebaseApp.getName());
            } catch (IllegalStateException e) {
                // 기존 앱이 없으면 새로 초기화
                firebaseApp = FirebaseApp.initializeApp(options);
                log.info("Firebase 앱이 새로 초기화되었습니다: {}", firebaseApp.getName());
            }

        } catch (IOException e) {
            log.error("Firebase 초기화 실패: {}", e.getMessage(), e);
            // 예외를 던지지 않고 null 값으로 둠
        } catch (Exception e) {
            log.error("Firebase 초기화 중 예상치 못한 오류: {}", e.getMessage(), e);
            // 예외를 던지지 않고 null 값으로 둠
        }
    }

}
