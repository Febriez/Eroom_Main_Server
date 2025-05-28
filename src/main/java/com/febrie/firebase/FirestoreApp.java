package com.febrie.firebase;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.firestore.*;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.cloud.FirestoreClient;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

public class FirestoreApp {
    private final Firestore firestore;
    private static FirestoreApp instance;

    private FirestoreApp(String credentialsPath) throws IOException {
        try (InputStream serviceAccount = new FileInputStream(credentialsPath)) {
            FirebaseOptions options = FirebaseOptions.builder().setCredentials(GoogleCredentials.fromStream(serviceAccount)).build();
            if (FirebaseApp.getApps().isEmpty()) FirebaseApp.initializeApp(options);

            this.firestore = FirestoreClient.getFirestore();
        }
    }


    public static synchronized FirestoreApp getInstance(String credentialsPath) throws IOException {
        if (instance == null) {
            instance = new FirestoreApp(credentialsPath);
        }
        return instance;
    }

    public List<QueryDocumentSnapshot> getAllDocuments(String collectionName) throws ExecutionException, InterruptedException {
        CollectionReference collectionRef = firestore.collection(collectionName);

        QuerySnapshot querySnapshot = collectionRef.get().get();

        return querySnapshot.getDocuments();
    }

    public Map<String, Object> getDocument(String collectionName, String documentId) throws ExecutionException, InterruptedException {
        DocumentReference docRef = firestore.collection(collectionName).document(documentId);

        DocumentSnapshot document = docRef.get().get();

        if (document.exists()) {
            return document.getData();
        } else {
            return null;
        }
    }

    public List<QueryDocumentSnapshot> queryDocuments(String collectionName, String field, Object value) throws ExecutionException, InterruptedException {
        Query query = firestore.collection(collectionName).whereEqualTo(field, value);

        QuerySnapshot querySnapshot = query.get().get();
        return querySnapshot.getDocuments();
    }

    public String addDocument(String collectionName, Map<String, Object> data) throws ExecutionException, InterruptedException {
        return firestore.collection(collectionName).add(data).get().getId();
    }

    public void updateDocument(String collectionName, String documentId, Map<String, Object> data) throws ExecutionException, InterruptedException {
        firestore.collection(collectionName).document(documentId).update(data).get();
    }

    public void deleteDocument(String collectionName, String documentId) throws ExecutionException, InterruptedException {
        firestore.collection(collectionName).document(documentId).delete().get();
    }
}