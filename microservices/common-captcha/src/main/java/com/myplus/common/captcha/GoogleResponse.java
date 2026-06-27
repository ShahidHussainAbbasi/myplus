package com.myplus.common.captcha;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Arrays;

/**
 * Google siteverify response (slice 33, Phase 9). Error codes are kept as raw strings so the actual
 * reason (e.g. {@code invalid-keys}, {@code timeout-or-duplicate}, {@code hostname-mismatch}) is visible
 * in logs — Google adds codes over time, so we never map them to a fixed enum.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({ "success", "challenge_ts", "hostname", "error-codes" })
public class GoogleResponse {

    @JsonProperty("success")
    private boolean success;
    @JsonProperty("challenge_ts")
    private String challengeTs;
    @JsonProperty("hostname")
    private String hostname;
    @JsonProperty("error-codes")
    private String[] errorCodes;

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getChallengeTs() {
        return challengeTs;
    }

    public void setChallengeTs(String challengeTs) {
        this.challengeTs = challengeTs;
    }

    public String getHostname() {
        return hostname;
    }

    public void setHostname(String hostname) {
        this.hostname = hostname;
    }

    public String[] getErrorCodes() {
        return errorCodes;
    }

    public void setErrorCodes(String[] errorCodes) {
        this.errorCodes = errorCodes;
    }

    /** True when the failure is the user's fault (missing/invalid response) — counts toward throttling. */
    @JsonIgnore
    public boolean hasClientError() {
        if (errorCodes == null) {
            return false;
        }
        for (final String code : errorCodes) {
            if ("missing-input-response".equals(code) || "invalid-input-response".equals(code)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "GoogleResponse{success=" + success + ", challengeTs='" + challengeTs + '\''
                + ", hostname='" + hostname + '\'' + ", errorCodes=" + Arrays.toString(errorCodes) + '}';
    }
}
