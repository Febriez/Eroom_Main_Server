# Meshy AI 3D 모델 생성 시스템

## 개요

이 프로젝트는 Meshy AI API를 사용하여 텍스트 프롬프트로부터 3D 모델을 생성하는 시스템입니다. 
프롬프트를 입력하면 Preview 모델을 생성하고, 텍스처 프롬프트를 통해 최종 Refine 모델을 생성합니다.

## 주요 기능

- 텍스트 프롬프트로부터 3D 모델 생성
- Preview 및 Refine 태스크 관리
- 태스크 상태 모니터링 및 로깅
- Firebase 연동을 통한 서버 로그 저장
- 결과물 자동 저장 및 정리

## 클래스 구조

### 모델 클래스

- `MeshyTask`: Meshy 태스크 정보를 담는 모델 클래스
- `MeshyLogData`: Firebase 로깅을 위한 로그 데이터 모델

### 서비스 클래스

- `MeshyTaskService`: Meshy 태스크 생성 및 관리 서비스
- `MeshyLogService`: 로깅 관련 기능을 담당하는 서비스

### API 클래스

- `MeshyTextTo3D`: Meshy API 호출을 담당하는 클래스
- `MeshyTaskTracker`: 태스크 상태 추적 및 관리 클래스
- `MeshyExample`: 예제 코드 및 레거시 메소드 모음

## 사용 예시

```java
// 새로운 방식 (권장)
MeshyTaskService taskService = new MeshyTaskService();
MeshyTask result = taskService.createFullModel(
        "a basic room key", "realistic", "shiny gold");

if (result != null && result.isSucceeded()) {
    System.out.println("모델 생성 성공: " + result.getTaskId());
    System.out.println("썸네일 URL: " + result.getThumbnailUrl());
    // 모델 URL 출력
}

// 기존 방식 (하위 호환성 유지)
MeshyExample.createFullModel("a basic room key", "realistic", "shiny gold");
```

## Firebase 로깅

MeshyLogData 클래스를 사용하여 Firebase에 로그를 저장합니다. 다음과 같은 로그 타입을 지원합니다:

- 진행 중인 태스크 로그: `MeshyLogData.progress()`
- 완료된 태스크 로그: `MeshyLogData.completed()`
- 실패한 태스크 로그: `MeshyLogData.error()`

## 개선된 아키텍처

기존 모놀리식 코드 구조에서 다음과 같이 개선되었습니다:

1. **관심사 분리**: 기능별로 클래스를 분리하여 유지보수성 향상
2. **모델-서비스 패턴**: 비즈니스 로직과 데이터 모델 분리
3. **중복 코드 제거**: 공통 로직을 추상화하여 재사용성 향상
4. **가독성 개선**: 메소드 분할 및 네이밍 개선

## 요구사항

- Java 17 이상
- Lombok
- OkHttp3
- Firebase Admin SDK
- Gson
