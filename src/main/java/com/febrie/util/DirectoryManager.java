package com.febrie.util;

import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;
import java.util.stream.Stream;

public class DirectoryManager {

    private static volatile DirectoryManager instance;

    private DirectoryManager() {
    }

    public static DirectoryManager getInstance() {
        if (instance == null) {
            synchronized (DirectoryManager.class) {
                if (instance == null) {
                    instance = new DirectoryManager();
                }
            }
        }
        return instance;
    }

    public boolean cleanDirectory(String directory) {
        return processDirectory(directory, false);
    }

    public boolean deleteDirectory(String directory) {
        return processDirectory(directory, true);
    }

    public boolean createDirectory(String directory) {
        if (!validateInput(directory)) return false;

        try {
            Files.createDirectories(Paths.get(directory));
            return true;
        } catch (IOException e) {
            System.err.println("[ERROR] 디렉토리 생성 실패: " + directory + " - " + e.getMessage());
            return false;
        }
    }

    public boolean createFile(String filePath) {
        return createFile(filePath, "");
    }

    public boolean createFile(String filePath, String content) {
        if (!validateInput(filePath)) return false;

        Path path = Paths.get(filePath);
        Path parentDir = path.getParent();

        if (parentDir != null && !Files.exists(parentDir)) {
            try {
                Files.createDirectories(parentDir);
            } catch (IOException e) {
                System.err.println("[ERROR] 부모 디렉토리 생성 실패: " + parentDir);
                return false;
            }
        }

        try {
            Files.writeString(path, content,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return true;
        } catch (IOException e) {
            System.err.println("[ERROR] 파일 생성 실패: " + filePath + " - " + e.getMessage());
            return false;
        }
    }

    public boolean deleteFile(String filePath) {
        if (!validateInput(filePath)) return false;

        Path path = Paths.get(filePath);

        if (!Files.exists(path)) return true;

        try {
            Files.delete(path);
            return true;
        } catch (IOException e) {
            System.err.println("[ERROR] 파일 삭제 실패: " + filePath + " - " + e.getMessage());
            return false;
        }
    }

    private boolean processDirectory(String directory, boolean deleteRootDirectory) {
        if (!validateInput(directory)) return false;

        Path dirPath = Paths.get(directory);
        if (!validateDirectory(dirPath, directory, deleteRootDirectory)) return false;

        try (Stream<Path> pathStream = Files.walk(dirPath)) {
            pathStream.sorted(Comparator.reverseOrder())
                    .filter(path -> deleteRootDirectory || !path.equals(dirPath))
                    .forEach(this::deletePath);
            return true;
        } catch (IOException e) {
            System.err.println("[ERROR] 디렉토리 처리 실패: " + directory + " - " + e.getMessage());
            return false;
        }
    }

    private boolean validateInput(String path) {
        if (path == null || path.trim().isEmpty()) {
            System.err.println("[ERROR] 경로가 null이거나 비어있습니다.");
            return false;
        }
        return true;
    }

    private boolean validateDirectory(Path dirPath, String directory, boolean deleteRootDirectory) {
        if (!Files.exists(dirPath)) {
            System.err.println("[ERROR] 디렉토리가 존재하지 않습니다: " + directory);
            return false;
        }

        if (!Files.isDirectory(dirPath)) {
            System.err.println("[ERROR] 지정된 경로가 디렉토리가 아닙니다: " + directory);
            return false;
        }

        if (!Files.isReadable(dirPath) || !Files.isWritable(dirPath)) {
            System.err.println("[ERROR] 디렉토리 권한이 없습니다: " + directory);
            return false;
        }

        if (deleteRootDirectory) {
            Path parentDir = dirPath.getParent();
            if (parentDir != null && !Files.isWritable(parentDir)) {
                System.err.println("[ERROR] 부모 디렉토리 쓰기 권한이 없습니다: " + parentDir);
                return false;
            }
        }
        return true;
    }

    private void deletePath(Path path) {
        try {
            Files.delete(path);
        } catch (NoSuchFileException e) {
            System.err.println("[WARNING] 파일이 이미 존재하지 않음: " + path.getFileName());
        } catch (DirectoryNotEmptyException e) {
            System.err.println("[ERROR] 디렉토리가 비어있지 않음: " + path.getFileName());
        } catch (AccessDeniedException e) {
            System.err.println("[ERROR] 접근 권한 없음: " + path.getFileName());
        } catch (IOException e) {
            System.err.println("[ERROR] 삭제 실패: " + path.getFileName() + " - " + e.getMessage());
        }
    }
}