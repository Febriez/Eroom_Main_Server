package com.febrie.util;

import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 경로 관련 유틸리티 기능을 제공하는 클래스
 */
public class PathUtils {

    /**
     * 경로 문자열을 정규화하여 플랫폼에 맞는 형식으로 변환
     * 
     * @param path 변환할 경로 문자열
     * @return 정규화된 경로 문자열
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
     * 문자열 경로를 Path 객체로 변환
     * 
     * @param path 변환할 경로 문자열
     * @return 변환된 Path 객체
     */
    @NotNull
    public static Path toPath(String path) {
        return Paths.get(normalizePath(path));
    }

    /**
     * 디렉토리가 존재하는지 확인하고, 없으면 생성
     *
     * @param path 확인할 디렉토리 경로
     */
    public static void ensureDirectoryExists(String path) {
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
