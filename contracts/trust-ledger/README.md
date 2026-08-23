# Trust ledger contract verification

`AnchorRegistry.sol` is intentionally kept as a source contract in this Java contract module.
The repository does not currently include a Solidity compiler or EVM test harness (`forge`,
Hardhat, `solc`, or a web3j-native test network), so `:contracts:trust-ledger:check` cannot deploy
or execute the contract. Adding an unexecuted Solidity test fixture would provide false confidence.

The contract behavior that must be covered when the blockchain integration module adds its harness
is:

- zero-digest rejection;
- first anchoring and `RootAnchored` emission;
- repeated anchoring without timestamp changes or a second event; and
- a first anchor at timestamp zero remaining anchored, as reported by `isAnchored`.

The Java trust-ledger check covers the deterministic digest and Merkle proof primitives today.
