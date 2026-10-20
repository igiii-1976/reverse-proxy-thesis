package com.example.proxy;

import android.util.Log;

import com.example.proxy.logger.Decision;
import com.example.proxy.logger.DecisionLogger;
import com.example.proxy.mdnsDiscovery.EdgeDevice;
import com.example.proxy.mdnsDiscovery.EdgeRegistry;
import com.example.proxy.mdnsDiscovery.TaskType;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import fi.iki.elonen.NanoHTTPD;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

public class ProxyServer extends NanoHTTPD {

    private final EdgeRegistry edgeRegistry;
    private final UiLogger.LogListener logger;
    private final OkHttpClient client;

    public ProxyServer(EdgeRegistry edgeRegistry, int port, UiLogger.LogListener logger) {
        super(port);
        this.edgeRegistry = edgeRegistry;
        this.logger = logger;

        this.client = new OkHttpClient.Builder()
                .connectTimeout(0, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .writeTimeout(0, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    private void log(String message) {
        if (logger != null) {
            logger.log(message);
        }
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();

        log("Incoming request: " + method + " " + uri);
        Log.i("ProxyServer", "Incoming request: " + method + " " + uri);

        if (uri.equals("/") && method == Method.GET) {
            return handleHtmlRequest();
        } else if (uri.equals("/login") && method == Method.POST) {
            return handleLoginRequest(session);
        } else if (uri.equals("/status") && method == Method.POST) {
            return handleStatusUpdateFromEdge(session);
        } else if (uri.equals("/recognize") && method == Method.POST) {
            return handleRecognitionRequest(session);
        } else if ((uri.startsWith("/result/") && method == Method.GET) ||
                (uri.equals("/queue-status") && method == Method.GET) ||
                (uri.equals("/set-concurrency") && method == Method.POST)) {
            return handleForwardingRequest(session);
        } else if (uri.equals("/battery") && method == Method.GET) {
            return handleBatteryRequest(session);
        } else if (uri.equals("/download") && method == Method.GET) {
            return handleFileDownloadRequest(session);
        } else {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found");
        }
    }

    private Response handleHtmlRequest() {
        EdgeDevice edge = edgeRegistry.getBestEdge(TaskType.SHORT);
        if (edge == null) {
            return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "No edge servers available");
        }

        String edgeUrl = "http://" + edge.getIp() + ":8080/";
        log("Fetching HTML from edge: " + edgeUrl);
        Log.i("ProxyServer", "Fetching HTML from edge: " + edgeUrl);

        try {
            Request request = new Request.Builder().url(edgeUrl).build();
            try (okhttp3.Response response = client.newCall(request).execute()) {
                String body = (response.body() != null) ? response.body().string() : "";
                String mime = response.header("Content-Type");
                if (mime == null) mime = "text/html";

                Response.Status status = Response.Status.lookup(response.code());
                if (status == null) status = Response.Status.OK;

                return newFixedLengthResponse(status, mime, body);
            }
        } catch (Exception e) {
            log("Error fetching HTML");
            Log.e("ProxyServer", "Error fetching HTML", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Proxy Error: " + e.getMessage());
        }
    }

    private Response handleLoginRequest(IHTTPSession session) {
        EdgeDevice edge = edgeRegistry.getBestEdge(TaskType.SHORT);
        if (edge == null) {
            return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "No edge servers available");
        }

        String edgeUrl = "http://" + edge.getIp() + ":8080" + session.getUri();
        log("Forwarding login to " + edgeUrl);
        Log.i("ProxyServer", "Forwarding login to " + edgeUrl);

        try {
            Map<String, String> tempFiles = new HashMap<>();
            session.parseBody(tempFiles);

            StringBuilder formData = new StringBuilder();
            Map<String, List<String>> parameters = session.getParameters();
            boolean first = true;
            for (Map.Entry<String, List<String>> entry : parameters.entrySet()) {
                if (!first) {
                    formData.append("&");
                }
                first = false;
                String encodedKey = URLEncoder.encode(entry.getKey(), "UTF-8");
                String val = (entry.getValue() != null && !entry.getValue().isEmpty()) ? entry.getValue().get(0) : "";
                String encodedValue = URLEncoder.encode(val, "UTF-8");
                formData.append(encodedKey).append("=").append(encodedValue);
            }

            MediaType mediaType = MediaType.parse("application/x-www-form-urlencoded");
            RequestBody body = RequestBody.create(mediaType, formData.toString());
            Request request = new Request.Builder()
                    .url(edgeUrl)
                    .post(body)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .build();

            try (okhttp3.Response edgeResponse = client.newCall(request).execute()) {
                byte[] bytes = (edgeResponse.body() != null) ? edgeResponse.body().bytes() : new byte[0];
                String mime = edgeResponse.header("Content-Type");
                if (mime == null) mime = "application/json";

                Response.Status status = Response.Status.lookup(edgeResponse.code());
                if (status == null) status = Response.Status.OK;

                return newFixedLengthResponse(status, mime, new ByteArrayInputStream(bytes), bytes.length);
            }
        } catch (Exception e) {
            log("Error forwarding /login");
            Log.e("ProxyServer", "Error forwarding /login", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Proxy Error: " + e.getMessage());
        }
    }

    private Response handleStatusUpdateFromEdge(IHTTPSession session) {
        try {
            Map<String, String> files = new HashMap<>();
            session.parseBody(files);

            Map<String, List<String>> postData = session.getParameters();
            List<String> statusValues = postData.get("status");
            String status = (statusValues != null && !statusValues.isEmpty()) ? statusValues.get(0) : "AVAILABLE";
            String edgeIp = session.getRemoteIpAddress();

            Log.i("ProxyServer", "Status update from " + edgeIp + ": " + status);

            edgeRegistry.updateStatus(edgeIp, status);

            Response response = newFixedLengthResponse(Response.Status.OK, "text/plain", "ACK");
            response.addHeader("Connection", "keep-alive");
            return response;
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.getMessage());
        }
    }

    private Response handleRecognitionRequest(IHTTPSession session) {
        EdgeDevice edge = edgeRegistry.getBestEdge(TaskType.LONG);
        if (edge == null) {
            return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "No edge servers available for recognition");
        }

        edgeRegistry.incrementQueue(edge.getIp());

        String clientRequestId = session.getHeaders().get("x-client-request-id");
        if (clientRequestId == null) clientRequestId = "unknown";

        Decision decision = DecisionLogger.createRecord(session.getUri(), edge, clientRequestId);

        String edgeUrl = "http://" + edge.getIp() + ":8080/recognize";
        log("Forwarding image request to edge: " + edgeUrl);

        String contentType = session.getHeaders().get("content-type");
        if (contentType == null) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Content-Type header is missing");
        }

        if (!contentType.toLowerCase().startsWith("multipart/form-data")) {
            decision.setStatus("Client_Error_400");
            DecisionLogger.finalizeAndWrite(decision);
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid Content-Type");
        }

        String authorization = session.getHeaders().get("authorization");
        File tempImageFile = null;

        try {
            Map<String, String> tempFiles = new HashMap<>();
            session.parseBody(tempFiles);

            String tempImageFilePath = tempFiles.get("imageFile");
            if (tempImageFilePath == null) {
                throw new IOException("File 'imageFile' not found in multipart request.");
            }

            tempImageFile = new File(tempImageFilePath);
            decision.setImageSizeBytes(tempImageFile.length());

            MediaType mediaType = MediaType.parse(contentType);
            RequestBody fileBody = RequestBody.create(mediaType, tempImageFile);

            MultipartBody requestBody = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("imageFile", tempImageFile.getName(), fileBody)
                    .build();

            Request.Builder requestBuilder = new Request.Builder()
                    .url(edgeUrl)
                    .post(requestBody)
                    .header("X-Client-Request-ID", clientRequestId);

            if (authorization != null) {
                requestBuilder.header("Authorization", authorization);
            }

            decision.setTimestampOfForwardingRequest(System.currentTimeMillis());

            try (okhttp3.Response edgeResponse = client.newCall(requestBuilder.build()).execute()) {
                decision.setTimestampOfReceivingEdgeResponse(System.currentTimeMillis());

                byte[] responseBody = (edgeResponse.body() != null) ? edgeResponse.body().bytes() : new byte[0];

                decision.setTimestampOfSendingResponse(System.currentTimeMillis());
                Long totalRtt = decision.getRttMs();
                long rttValue = (totalRtt != null) ? totalRtt : 0L;

                if (edgeResponse.isSuccessful()) {
                    decision.setStatus("Success");
                    edgeRegistry.updateRtt(edge.getIp(), rttValue, TaskType.LONG);
                } else {
                    decision.setStatus("Edge_Error_" + edgeResponse.code());
                }

                DecisionLogger.finalizeAndWrite(decision);

                String mime = edgeResponse.header("Content-Type");
                if (mime == null) mime = "application/json";

                Response.Status status = Response.Status.lookup(edgeResponse.code());
                if (status == null) status = Response.Status.OK;

                return newFixedLengthResponse(
                        status,
                        mime,
                        new ByteArrayInputStream(responseBody),
                        responseBody.length
                );
            }
        } catch (Exception e) {
            decision.setStatus("Proxy_Error");
            DecisionLogger.finalizeAndWrite(decision);
            Log.e("ProxyServer", "Error forwarding /recognize", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Proxy Error: " + e.getMessage());
        } finally {
            edgeRegistry.decrementQueue(edge.getIp());
            if (tempImageFile != null && tempImageFile.exists()) {
                tempImageFile.delete();
            }
        }
    }

    private Response handleBatteryRequest(IHTTPSession session) {
        EdgeDevice edge = edgeRegistry.getBestEdge(TaskType.SHORT);
        if (edge == null) {
            return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "No edge");
        }

        String clientRequestId = session.getHeaders().get("x-client-request-id");
        if (clientRequestId == null) clientRequestId = "unknown";

        Decision decision = DecisionLogger.createRecord(session.getUri(), edge, clientRequestId);

        String edgeUrl = "http://" + edge.getIp() + ":8080/battery";
        Request.Builder requestBuilder = new Request.Builder()
                .url(edgeUrl)
                .header("X-Client-Request-ID", clientRequestId);

        String auth = session.getHeaders().get("authorization");
        if (auth != null) {
            requestBuilder.header("Authorization", auth);
        }

        decision.setTimestampOfForwardingRequest(System.currentTimeMillis());

        try {
            try (okhttp3.Response edgeResponse = client.newCall(requestBuilder.build()).execute()) {
                long endTime = System.currentTimeMillis();
                long rtt = endTime - decision.getTimestampOfForwardingRequest();

                if (edgeResponse.isSuccessful()) {
                    edgeRegistry.updateRtt(edge.getIp(), rtt, TaskType.SHORT);
                }

                decision.setTimestampOfReceivingEdgeResponse(System.currentTimeMillis());

                byte[] bytes = (edgeResponse.body() != null) ? edgeResponse.body().bytes() : new byte[0];

                if (edgeResponse.isSuccessful()) {
                    decision.setStatus("Battery_Success");
                } else {
                    decision.setStatus("Battery_Edge_Error_" + edgeResponse.code());
                }

                DecisionLogger.finalizeAndWrite(decision);

                String mime = edgeResponse.header("Content-Type");
                if (mime == null) mime = "application/json";

                Response.Status status = Response.Status.lookup(edgeResponse.code());
                if (status == null) status = Response.Status.OK;

                return newFixedLengthResponse(status, mime, new ByteArrayInputStream(bytes), bytes.length);
            }
        } catch (Exception e) {
            decision.setStatus("Battery_Proxy_Error");
            DecisionLogger.finalizeAndWrite(decision);
            Log.e("ProxyServer", "Battery request failed", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: " + e.getMessage());
        }
    }

    private Response handleFileDownloadRequest(IHTTPSession session) {
        EdgeDevice edge = edgeRegistry.getBestEdge(TaskType.SHORT);
        if (edge == null) {
            return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "No edge servers available for file download");
        }

        String clientRequestId = session.getHeaders().get("x-client-request-id");
        if (clientRequestId == null) clientRequestId = "unknown";

        Decision decision = DecisionLogger.createRecord(session.getUri(), edge, clientRequestId);

        String queryString = (session.getQueryParameterString() != null) ? "?" + session.getQueryParameterString() : "";
        String edgeUrl = "http://" + edge.getIp() + ":8080" + session.getUri() + queryString;

        log("Forwarding download request to edge: " + edgeUrl);

        try {
            Request.Builder requestBuilder = new Request.Builder()
                    .url(edgeUrl)
                    .header("X-Client-Request-ID", clientRequestId);

            String auth = session.getHeaders().get("authorization");
            if (auth != null) {
                requestBuilder.header("Authorization", auth);
            }

            decision.setTimestampOfForwardingRequest(System.currentTimeMillis());

            try (okhttp3.Response edgeResponse = client.newCall(requestBuilder.build()).execute()) {
                decision.setTimestampOfReceivingEdgeResponse(System.currentTimeMillis());

                if (!edgeResponse.isSuccessful()) {
                    decision.setStatus("Download_Error_" + edgeResponse.code());
                    decision.setTimestampOfSendingResponse(System.currentTimeMillis());
                    DecisionLogger.finalizeAndWrite(decision);
                    return newFixedLengthResponse(Response.Status.lookup(edgeResponse.code()), "text/plain", "Error");
                }

                byte[] fileBytes = (edgeResponse.body() != null) ? edgeResponse.body().bytes() : new byte[0];
                decision.setFileSizeBytes((long) fileBytes.length);

                decision.setTimestampOfSendingResponse(System.currentTimeMillis());
                Long totalRtt = decision.getRttMs();
                long rttVal = (totalRtt != null) ? totalRtt : 0L;

                decision.setStatus("Success");
                edgeRegistry.updateRtt(edge.getIp(), rttVal, TaskType.SHORT);

                DecisionLogger.finalizeAndWrite(decision);

                String mime = edgeResponse.header("Content-Type");
                if (mime == null) mime = "application/octet-stream";

                return newFixedLengthResponse(
                        Response.Status.OK,
                        mime,
                        new ByteArrayInputStream(fileBytes),
                        fileBytes.length
                );
            }
        } catch (Exception e) {
            decision.setStatus("Download_Proxy_Error");
            DecisionLogger.finalizeAndWrite(decision);

            log("Error forwarding /download request");
            Log.e("ProxyServer", "Error forwarding /download request", e);

            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Proxy Error: " + e.getMessage());
        }
    }

    private Response handleForwardingRequest(IHTTPSession session) {
        EdgeDevice edge = edgeRegistry.getBestEdge(TaskType.LONG);
        if (edge == null) {
            return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "No edge");
        }

        String edgeUrl = "http://" + edge.getIp() + ":8080" + session.getUri();

        String clientRequestId = session.getHeaders().get("x-client-request-id");
        if (clientRequestId == null) clientRequestId = "unknown";
        Decision decision = DecisionLogger.getRecord(clientRequestId);

        Request request = new Request.Builder()
                .url(edgeUrl)
                .get()
                .build();

        try (okhttp3.Response edgeResponse = client.newCall(request).execute()) {
            if (decision != null) {
                decision.setTimestampOfReceivingEdgeResponse(System.currentTimeMillis());
            }

            byte[] body = (edgeResponse.body() != null) ? edgeResponse.body().bytes() : new byte[0];

            if (edgeResponse.code() == 200 && decision != null) {
                decision.setStatus("Success");
                DecisionLogger.finalizeAndWrite(decision);
            } else if (edgeResponse.code() >= 400 && decision != null) {
                decision.setStatus("Final_Error_" + edgeResponse.code());
                DecisionLogger.finalizeAndWrite(decision);
            }

            String mime = edgeResponse.header("Content-Type");
            if (mime == null) mime = "application/json";

            Response.Status status = Response.Status.lookup(edgeResponse.code());
            if (status == null) status = Response.Status.OK;

            return newFixedLengthResponse(
                    status,
                    mime,
                    new ByteArrayInputStream(body),
                    body.length
            );
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: " + e.getMessage());
        }
    }
}
