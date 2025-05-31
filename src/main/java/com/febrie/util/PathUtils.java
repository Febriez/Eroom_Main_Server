package com.febrie.util;

import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class PathUtils {
    public static String normalizePath(String path) {
        if (path == null) return null;

        // 모든 백슬래시를 슬래시로 변환
        String normalized = path.replace("\\", "/");

        // 연속된 슬래시를 단일 슬래시로 변환
        normalized = normalized.replaceAll("/+", "/");

        return normalized;
    }

    @NotNull
    public static Path toPath(String path) {
        if (path == null) {
            throw new IllegalArgumentException("경로가 null입니다.");
        }
        return Paths.get(normalizePath(path));
    }

    public static void ensureDirectoryExists(String path) {
        if (path == null) {
            System.err.println("[ERROR] 디렉토리 경로가 null입니다.");
            return;
        }

        try {
            Path dirPath = toPath(path);
            if (!Files.exists(dirPath)) {
                Files.createDirectories(dirPath);
                System.out.println("[INFO] 디렉토리 생성 완료: " + dirPath.toAbsolutePath());
            }
        } catch (Exception e) {
            System.err.println("[ERROR] 디렉토리 확인/생성 실패: " + path + " - " + e.getMessage());
        }
    }
}
