package com.lifeos.trustledger.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Explicitly opt-in, bounded Besu/Web3j anchor controls. */
@ConfigurationProperties(prefix = "trust-ledger.besu")
@Validated
public class BesuAnchorProperties {

    private boolean enabled;

    @NotBlank
    private String rpcUrl = "http://localhost:8545";

    private String privateKey;
    private String contractAddress;
    private String functionName = "anchorRoot";

    @Min(1)
    private long chainId = 1337L;

    @Min(21_000)
    private long gasLimit = 120_000L;

    @Min(1)
    private long gasPriceWei = 1_000_000_000L;

    private Duration requestTimeout = Duration.ofSeconds(5);

    private Duration receiptPollingInterval = Duration.ofMillis(250);

    @Min(1)
    private int receiptPollingAttempts = 20;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getRpcUrl() { return rpcUrl; }
    public void setRpcUrl(String rpcUrl) { this.rpcUrl = rpcUrl; }
    public String getPrivateKey() { return privateKey; }
    public void setPrivateKey(String privateKey) { this.privateKey = privateKey; }
    public String getContractAddress() { return contractAddress; }
    public void setContractAddress(String contractAddress) { this.contractAddress = contractAddress; }
    public String getFunctionName() { return functionName; }
    public void setFunctionName(String functionName) { this.functionName = functionName; }
    public long getChainId() { return chainId; }
    public void setChainId(long chainId) { this.chainId = chainId; }
    public long getGasLimit() { return gasLimit; }
    public void setGasLimit(long gasLimit) { this.gasLimit = gasLimit; }
    public long getGasPriceWei() { return gasPriceWei; }
    public void setGasPriceWei(long gasPriceWei) { this.gasPriceWei = gasPriceWei; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; }
    public Duration getReceiptPollingInterval() { return receiptPollingInterval; }
    public void setReceiptPollingInterval(Duration receiptPollingInterval) { this.receiptPollingInterval = receiptPollingInterval; }
    public int getReceiptPollingAttempts() { return receiptPollingAttempts; }
    public void setReceiptPollingAttempts(int receiptPollingAttempts) { this.receiptPollingAttempts = receiptPollingAttempts; }

    @AssertTrue(message = "enabled Besu anchoring requires a 64-hex private key and 40-hex contract address")
    public boolean isCredentialsValid() {
        if (!enabled) return true;
        return privateKey != null && privateKey.matches("[0-9a-fA-F]{64}")
                && contractAddress != null && contractAddress.matches("0x[0-9a-fA-F]{40}")
                && functionName != null && functionName.matches("[A-Za-z_][A-Za-z0-9_]{0,63}");
    }

    @AssertTrue(message = "Besu RPC must use HTTPS except for loopback development")
    public boolean isRpcUrlValid() {
        if (rpcUrl == null || rpcUrl.isBlank()) return false;
        try {
            URI uri = URI.create(rpcUrl);
            if (!uri.isAbsolute() || uri.getHost() == null) return false;
            return "https".equalsIgnoreCase(uri.getScheme())
                    || ("http".equalsIgnoreCase(uri.getScheme()) && isLoopback(uri.getHost()));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    @AssertTrue(message = "Besu timeouts must be bounded")
    public boolean areTimingControlsValid() {
        return requestTimeout != null && !requestTimeout.isNegative() && !requestTimeout.isZero()
                && requestTimeout.compareTo(Duration.ofSeconds(30)) <= 0
                && receiptPollingInterval != null && !receiptPollingInterval.isNegative()
                && !receiptPollingInterval.isZero() && receiptPollingInterval.compareTo(Duration.ofSeconds(5)) <= 0
                && receiptPollingAttempts <= 120;
    }

    private static boolean isLoopback(String host) {
        try { return InetAddress.getByName(host).isLoopbackAddress(); }
        catch (UnknownHostException ignored) { return "localhost".equalsIgnoreCase(host); }
    }
}
