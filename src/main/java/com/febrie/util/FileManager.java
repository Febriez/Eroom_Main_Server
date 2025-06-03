package com.febrie.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;
import java.util.stream.Stream;

public class FileManager {
    private static final Logger log = LoggerFactory.getLogger(FileManager.class);

    private static class LazyHolder {
        private static final FileManager INSTANCE = new FileManager();
    }

    private FileManager() {
        // private 생성자로 외부에서 인스턴스 생성 방지
    }

    public static FileManager getInstance() {
        return LazyHolder.INSTANCE;
    }

    /**
     * 경로 문자열 정규화
     *
     * @param path 정규화할 경로
     * @return 정규화된 경로
     */
    public static String normalizePath(String path) {
        if (path == null) return null;

        // 모든 백슬래시를 슬래시로 변환
        String normalized = path.replace("\\", "/");

        // 연속된 슬래시를 단일 슬래시로 변환
        normalized = normalized.replaceAll("/+", "/");

        return normalized;
    }

    /**
     * 디렉토리 존재 여부 확인 및 생성
     *
     * @param path 생성할 디렉토리 경로
     */
    public static void ensureDirectoryExists(String path) {
        if (path == null || path.trim().isEmpty()) {
            log.error("디렉토리 경로가 null이거나 비어있습니다.");
            return;
        }

        Path dirPath = Paths.get(normalizePath(path));
        try {
            if (!Files.exists(dirPath)) {
                Files.createDirectories(dirPath);
                log.info("디렉토리 생성 완료: {}", dirPath.toAbsolutePath());
            }
        } catch (Exception e) {
            log.error("디렉토리 확인/생성 실패: {} - {}", path, e.getMessage(), e);
        }
    }

    public void createFile(String filePath) {
        createFile(filePath, "");
    }

    public void createFile(String filePath, String content) {
        long startTime = System.currentTimeMillis();

        if (invalidateInput(filePath)) {
            log.error("파일 경로 유효성 검사 실패: {}", filePath);
            return;
        }

        Path path = Paths.get(filePath);
        Path parentDir = path.getParent();

        if (parentDir != null && !Files.exists(parentDir)) {
            long dirStartTime = System.currentTimeMillis();
            try {
                Files.createDirectories(parentDir);
                log.info("디렉토리 생성 완료: {} - 소요시간: {}ms", parentDir, (System.currentTimeMillis() - dirStartTime));
            } catch (IOException e) {
                log.error("디렉토리 생성 실패: {} - {}", parentDir, e.getMessage(), e);
                return;
            }
        }

        long writeStartTime = System.currentTimeMillis();
        try {
            Files.writeString(path, content,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("파일 작성 완료: {} - 크기: {}바이트, 소요시간: {}ms", path, content.length(), (System.currentTimeMillis() - writeStartTime));
        } catch (IOException e) {
            log.error("파일 생성 실패: {} - {}", filePath, e.getMessage(), e);
        }

        log.info("파일 처리 완료 - 총 소요시간: {}ms", (System.currentTimeMillis() - startTime));
    }

    public boolean deleteFile(String filePath) {
        if (invalidateInput(filePath)) return false;

        Path path = Paths.get(filePath);

        if (!Files.exists(path)) return true;

        try {
            Files.delete(path);
            return true;
        } catch (IOException e) {
            log.error("파일 삭제 실패: {} - {}", filePath, e.getMessage(), e);
            return false;
        }
    }

    private boolean processDirectory(String directory, boolean deleteRootDirectory) {
        if (invalidateInput(directory)) return false;

        Path dirPath = Paths.get(directory);
        if (!validateDirectory(dirPath, directory, deleteRootDirectory)) return false;

        try (Stream<Path> pathStream = Files.walk(dirPath)) {
            pathStream.sorted(Comparator.reverseOrder())
                    .filter(path -> deleteRootDirectory || !path.equals(dirPath))
                    .forEach(this::deletePath);
            return true;
        } catch (IOException e) {
            log.error("디렉토리 처리 실패: {} - {}", directory, e.getMessage(), e);
            return false;
        }
    }

    private boolean invalidateInput(String path) {
        if (path == null || path.trim().isEmpty()) {
            log.error("경로가 null이거나 비어있습니다.");
            return true;
        }
        return false;
    }

    private boolean validateDirectory(Path dirPath, String directory, boolean deleteRootDirectory) {
        if (!Files.exists(dirPath)) {
            log.error("디렉토리가 존재하지 않습니다: {}", directory);
            return false;
        }

        if (!Files.isDirectory(dirPath)) {
            log.error("지정된 경로가 디렉토리가 아닙니다: {}", directory);
            return false;
        }

        if (!Files.isReadable(dirPath) || !Files.isWritable(dirPath)) {
            log.error("디렉토리 권한이 없습니다: {}", directory);
            return false;
        }

        if (deleteRootDirectory) {
            Path parentDir = dirPath.getParent();
            if (parentDir != null && !Files.isWritable(parentDir)) {
                log.error("부모 디렉토리 쓰기 권한이 없습니다: {}", parentDir);
                return false;
            }
        }
        return true;
    }

    private void deletePath(Path path) {
        if (path == null) {
            log.error("삭제할 경로가 null입니다.");
            return;
        }

        try {
            Files.delete(path);
        } catch (NoSuchFileException e) {
            log.warn("파일이 이미 존재하지 않음: {}", path.getFileName());
        } catch (DirectoryNotEmptyException e) {
            log.error("디렉토리가 비어있지 않음: {}", path.getFileName());
        } catch (AccessDeniedException e) {
            log.error("접근 권한 없음: {}", path.getFileName());
        } catch (IOException e) {
            log.error("삭제 실패: {} - {}", path.getFileName(), e.getMessage(), e);
        }
    }
}