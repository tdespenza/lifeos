package com.lifeos.trustledger.api;

/** Non-content-bearing result of a canonical document proof operation. */
public record DocumentProofResponse(String algorithm, String digest, long contentBytes) {
}
