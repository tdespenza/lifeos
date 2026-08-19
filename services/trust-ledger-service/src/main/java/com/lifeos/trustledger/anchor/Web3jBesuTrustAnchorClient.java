package com.lifeos.trustledger.anchor;

import com.lifeos.trustledger.config.BesuAnchorProperties;
import com.lifeos.trustledger.proof.TrustDocumentProofRequest;
import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthGetTransactionCount;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;
import org.web3j.protocol.exceptions.TransactionException;
import org.web3j.tx.RawTransactionManager;
import org.web3j.utils.Numeric;
import org.web3j.tx.response.PollingTransactionReceiptProcessor;

/** Web3j adapter for a reviewed Besu contract that accepts a digest-only anchorRoot(bytes32) call. */
@Component
@ConditionalOnProperty(value = "trust-ledger.besu.enabled", havingValue = "true")
public class Web3jBesuTrustAnchorClient implements TrustAnchorClient, AutoCloseable {

    private final BesuAnchorProperties properties;
    private final Web3j web3j;
    private final Credentials credentials;

    public Web3jBesuTrustAnchorClient(BesuAnchorProperties properties) {
        this.properties = properties;
        web3j = Web3j.build(new HttpService(properties.getRpcUrl()));
        credentials = Credentials.create(properties.getPrivateKey());
    }

    @Override
    public AnchorResult anchor(TrustDocumentProofRequest request) {
        return anchorDigest(request.getChecksumSha256());
    }

    @Override
    public AnchorResult anchorDigest(String checksumSha256) {
        try {
            EthGetTransactionCount nonceResponse = web3j.ethGetTransactionCount(
                    credentials.getAddress(), DefaultBlockParameterName.PENDING)
                    .sendAsync()
                    .get(properties.getRequestTimeout().toMillis(), TimeUnit.MILLISECONDS);
            BigInteger nonce = nonceResponse.getTransactionCount();
            if (checksumSha256 == null || !checksumSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("anchor digest must be a SHA-256 value");
            }
            byte[] digest = Numeric.hexStringToByteArray("0x" + checksumSha256);
            String data = FunctionEncoder.encode(new Function(
                    properties.getFunctionName(), List.of(new Bytes32(digest)), List.of()));
            RawTransaction transaction = RawTransaction.createTransaction(
                    nonce,
                    BigInteger.valueOf(properties.getGasPriceWei()),
                    BigInteger.valueOf(properties.getGasLimit()),
                    properties.getContractAddress(),
                    data);
            byte[] signed = TransactionEncoder.signMessage(transaction, properties.getChainId(), credentials);
            EthSendTransaction response = web3j.ethSendRawTransaction(Numeric.toHexString(signed))
                    .sendAsync()
                    .get(properties.getRequestTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (response.hasError()) {
                throw new TrustAnchorUnavailableException(new IllegalStateException("Besu rejected anchor transaction"));
            }
            String transactionHash = response.getTransactionHash();
            TransactionReceipt receipt = new PollingTransactionReceiptProcessor(
                    web3j,
                    properties.getReceiptPollingInterval().toMillis(),
                    properties.getReceiptPollingAttempts())
                    .waitForTransactionReceipt(transactionHash);
            long blockNumber = receipt.getBlockNumber().longValueExact();
            return new AnchorResult(transactionHash, blockNumber);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new TrustAnchorUnavailableException(interrupted);
        } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException
                | java.io.IOException | TransactionException | ArithmeticException exception) {
            throw new TrustAnchorUnavailableException(exception);
        }
    }

    @Override
    public void close() {
        web3j.shutdown();
    }
}
