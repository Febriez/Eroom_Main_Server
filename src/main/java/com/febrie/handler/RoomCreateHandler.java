package com.febrie.handler;

import com.febrie.dto.NecessaryData;
import com.febrie.http.HttpResponseType;
import com.febrie.util.DirectoryManager;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.util.stream.Collectors;

public class RoomCreateHandler implements HttpHandler {

    @Override
    public void handle(@NotNull HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String[] path = exchange.getRequestURI().getPath().replace("/room/", "").split("/");
        switch (method) {
            case "GET":
                handleGetRequest(exchange, path[0]);
                break;
            case "PUT":
            case "POST":
                handlePutNPostRequest(exchange, path[0]);
                break;
            case "DELETE":
                handleDeleteRequest(exchange);
                break;
            default:
                break;
        }
    }

    private void handleGetRequest(@NotNull HttpExchange exchange, String action) throws IOException {
        HttpResponseType responseType = HttpResponseType.OK;

        exchange.sendResponseHeaders(responseType.getCode(), 0);
        exchange.getResponseBody().close();
    }

    private void handlePutNPostRequest(@NotNull HttpExchange exchange, @NotNull String action) throws IOException {
        HttpResponseType responseType;
        JsonObject data = getBody(exchange.getRequestBody());
        if (data != null) {
            responseType = switch (action) {
                case "create" -> {
                    NecessaryData d = NecessaryData.fromJson(data);
                    createDataFiles(d);
                    yield HttpResponseType.OK;
                }
                case "update" -> HttpResponseType.INVALID_METHOD;
                default -> HttpResponseType.BAD_REQUEST;
            };
        } else {
            responseType = HttpResponseType.BAD_REQUEST;
        }
        exchange.sendResponseHeaders(responseType.getCode(), 0);
        exchange.getResponseBody().close();
    }

    private void createDataFiles(@NotNull NecessaryData d) throws IOException {
        DirectoryManager manager = DirectoryManager.getInstance();
        String logPath = System.getProperty("user.dir") + "/" + d.uuid() + "/" + d.puid() + "/log.txt";
        manager.createFile(logPath);
        File file = new File(logPath);
        if (!file.exists()) manager.deleteDirectory(file.toPath().toString());

    }

    private void handleDeleteRequest(@NotNull HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(200, 0);
    }

    private JsonObject getBody(InputStream body) {
        return JsonParser.parseString(new BufferedReader(new InputStreamReader(body)).lines().collect(Collectors.joining())).getAsJsonObject();
    }

}
