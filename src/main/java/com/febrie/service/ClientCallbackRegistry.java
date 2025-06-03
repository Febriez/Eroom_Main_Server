package com.febrie.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 클라이언트 콜백 URL을 등록하고 관리하는 레지스트리
 */
public class ClientCallbackRegistry {
    private static final Logger log = LoggerFactory.getLogger(ClientCallbackRegistry.class);
    private static final Map<String, String> callbackUrls = new ConcurrentHashMap<>();

    /**
     * 특정 PUID에 대한 콜백 URL 등록
     * 
     * @param puid 사용자 ID
     * @param callbackUrl 콜백 URL
     */
    public static void registerCallbackUrl(String puid, String callbackUrl) {
        callbackUrls.put(puid, callbackUrl);
        log.info("[{}] 콜백 URL 등록: {}", puid, callbackUrl);
    }

    /**
     * 특정 PUID에 대한 콜백 URL 조회
     * 
     * @param puid 사용자 ID
     * @return 콜백 URL 또는 null
     */
    public static String getCallbackUrl(String puid) {
        return callbackUrls.get(puid);
    }

    /**
     * 특정 PUID에 대한 콜백 URL 제거
     * 
     * @param puid 사용자 ID
     */
    public static void removeCallbackUrl(String puid) {
        callbackUrls.remove(puid);
        log.info("[{}] 콜백 URL 제거됨", puid);
    }
}
