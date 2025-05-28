package com.febrie.firebase;

import com.google.cloud.firestore.QueryDocumentSnapshot;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

public class FirestoreExample {

    public static void main(String[] args) {
        System.out.println(System.getProperty("user.dir"));
    }

    public static void a() {

        try {
            String credentialsPath = "resources/servicesKey.json";
            FirestoreApp firestoreService = FirestoreApp.getInstance("resources/servicesKey.json");

            // 컬렉션 이름
            String collectionName = "users";

            // 데이터 추가
            Map<String, Object> userData = new HashMap<>();
            userData.put("name", "홍길동");
            userData.put("age", 30);
            userData.put("email", "hong@example.com");

            String documentId = firestoreService.addDocument(collectionName, userData);
            System.out.println("추가된 문서 ID: " + documentId);

            // 모든 문서 가져오기
            List<QueryDocumentSnapshot> documents = firestoreService.getAllDocuments(collectionName);
            System.out.println("사용자 수: " + documents.size());

            for (QueryDocumentSnapshot document : documents) {
                System.out.println("문서 ID: " + document.getId());
                System.out.println("사용자 데이터: " + document.getData());
            }

            // 특정 문서 가져오기
            Map<String, Object> user = firestoreService.getDocument(collectionName, documentId);
            System.out.println("특정 사용자: " + user);

            // 문서 업데이트
            Map<String, Object> updates = new HashMap<>();
            updates.put("age", 31);
            firestoreService.updateDocument(collectionName, documentId, updates);

            // 문서 쿼리
            List<QueryDocumentSnapshot> queryResults = firestoreService.queryDocuments(collectionName, "name", "홍길동");
            System.out.println("쿼리 결과 수: " + queryResults.size());

            // 문서 삭제
            firestoreService.deleteDocument(collectionName, documentId);
            System.out.println("문서 삭제 완료");

        } catch (IOException e) {
            System.err.println("Firebase 초기화 오류: " + e.getMessage());
            e.printStackTrace();
        } catch (ExecutionException | InterruptedException e) {
            System.err.println("Firestore 작업 오류: " + e.getMessage());
            e.printStackTrace();
        }
    }
}