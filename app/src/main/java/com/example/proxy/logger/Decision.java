package com.example.proxy.logger;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class Decision {
    private final String requestID;
    private final long timestampOfReceivingRequest;
    private Long timestampOfForwardingRequest;
    private Long timestampOfReceivingEdgeResponse;
    private Long timestampOfSendingResponse;
    private final String requestPath;
    private final String chosenEdgeIp;
    private final String edgeBatteryPercent;
    private Long imageSizeBytes;
    private Long fileSizeBytes;
    private String status = "In_Progress";

    public Decision(String requestID, long timestampOfReceivingRequest, String requestPath, String chosenEdgeIp, String edgeBatteryPercent) {
        this.requestID = requestID;
        this.timestampOfReceivingRequest = timestampOfReceivingRequest;
        this.requestPath = requestPath;
        this.chosenEdgeIp = chosenEdgeIp;
        this.edgeBatteryPercent = edgeBatteryPercent;
    }

    public String getRequestID() {
        return requestID;
    }

    public long getTimestampOfReceivingRequest() {
        return timestampOfReceivingRequest;
    }

    public Long getTimestampOfForwardingRequest() {
        return timestampOfForwardingRequest;
    }

    public void setTimestampOfForwardingRequest(Long timestampOfForwardingRequest) {
        this.timestampOfForwardingRequest = timestampOfForwardingRequest;
    }

    public Long getTimestampOfReceivingEdgeResponse() {
        return timestampOfReceivingEdgeResponse;
    }

    public void setTimestampOfReceivingEdgeResponse(Long timestampOfReceivingEdgeResponse) {
        this.timestampOfReceivingEdgeResponse = timestampOfReceivingEdgeResponse;
    }

    public Long getTimestampOfSendingResponse() {
        return timestampOfSendingResponse;
    }

    public void setTimestampOfSendingResponse(Long timestampOfSendingResponse) {
        this.timestampOfSendingResponse = timestampOfSendingResponse;
    }

    public String getRequestPath() {
        return requestPath;
    }

    public String getChosenEdgeIp() {
        return chosenEdgeIp;
    }

    public String getEdgeBatteryPercent() {
        return edgeBatteryPercent;
    }

    public Long getImageSizeBytes() {
        return imageSizeBytes;
    }

    public void setImageSizeBytes(Long imageSizeBytes) {
        this.imageSizeBytes = imageSizeBytes;
    }

    public Long getFileSizeBytes() {
        return fileSizeBytes;
    }

    public void setFileSizeBytes(Long fileSizeBytes) {
        this.fileSizeBytes = fileSizeBytes;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getRttMs() {
        if (timestampOfSendingResponse != null) {
            return timestampOfSendingResponse - timestampOfReceivingRequest;
        } else {
            return null;
        }
    }

    public String toCsvLine() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());

        String receivedTimestamp = sdf.format(new Date(timestampOfReceivingRequest));
        String forwardTimestamp = (timestampOfForwardingRequest != null) ? sdf.format(new Date(timestampOfForwardingRequest)) : "";
        String edgeReceiveTimestamp = (timestampOfReceivingEdgeResponse != null) ? sdf.format(new Date(timestampOfReceivingEdgeResponse)) : "";
        String sentTimestamp = (timestampOfSendingResponse != null) ? sdf.format(new Date(timestampOfSendingResponse)) : "";

        Long rtt = getRttMs();

        return requestID + "," +
                receivedTimestamp + "," +
                forwardTimestamp + "," +
                edgeReceiveTimestamp + "," +
                sentTimestamp + "," +
                requestPath + "," +
                chosenEdgeIp + "," +
                edgeBatteryPercent + "," +
                (imageSizeBytes != null ? imageSizeBytes : "") + "," +
                (fileSizeBytes != null ? fileSizeBytes : "") + "," +
                (rtt != null ? rtt : "") + "," +
                status + "\n";
    }

    public static String getCsvHeader() {
        return "RequestID," +
                "TimestampReceive," +
                "TimestampForward," +
                "TimestampEdgeReceive," +
                "TimestampSend," +
                "RequestPath," +
                "ChosenEdgeIp," +
                "EdgeBatteryPercent," +
                "ImageSizeBytes," +
                "FileSizeBytes," +
                "RttMS," +
                "Status\n";
    }
}
