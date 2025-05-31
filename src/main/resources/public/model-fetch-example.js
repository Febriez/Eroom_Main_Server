/**
 * 생성된 3D 모델 URL 조회 예제
 */
async function fetchModelUrls(puid) {
  try {
    const response = await fetch('/api/models', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({ puid: puid })
    });

    if (!response.ok) {
      throw new Error('모델 URL 조회 실패: ' + response.status);
    }

    const data = await response.json();
    return data;
  } catch (error) {
    console.error('모델 URL 조회 중 오류:', error);
    return { models: {} };
  }
}

/**
 * Unity C#에서 사용할 수 있는 모델 URL 조회 코드 예제
 *
 * ```csharp
 * using System;
 * using System.Collections;
 * using System.Collections.Generic;
 * using UnityEngine;
 * using UnityEngine.Networking;
 * using Newtonsoft.Json;
 * 
 * public class ModelFetcher : MonoBehaviour
 * {
 *     [Serializable]
 *     private class ModelUrlsResponse
 *     {
 *         public Dictionary<string, string> models = new Dictionary<string, string>();
 *     }
 * 
 *     public IEnumerator FetchModelUrls(string puid, Action<Dictionary<string, string>> onComplete)
 *     {
 *         string url = "http://localhost:7568/api/models";
 *         string jsonData = JsonConvert.SerializeObject(new { puid = puid });
 * 
 *         using (UnityWebRequest request = new UnityWebRequest(url, "POST"))
 *         {
 *             byte[] bodyRaw = System.Text.Encoding.UTF8.GetBytes(jsonData);
 *             request.uploadHandler = new UploadHandlerRaw(bodyRaw);
 *             request.downloadHandler = new DownloadHandlerBuffer();
 *             request.SetRequestHeader("Content-Type", "application/json");
 * 
 *             yield return request.SendWebRequest();
 * 
 *             if (request.result != UnityWebRequest.Result.Success)
 *             {
 *                 Debug.LogError($"모델 URL 조회 실패: {request.error}");
 *                 onComplete?.Invoke(new Dictionary<string, string>());
 *                 yield break;
 *             }
 * 
 *             string jsonResponse = request.downloadHandler.text;
 *             ModelUrlsResponse response = JsonConvert.DeserializeObject<ModelUrlsResponse>(jsonResponse);
 *             
 *             onComplete?.Invoke(response.models);
 *         }
 *     }
 * }
 * ```
 */
