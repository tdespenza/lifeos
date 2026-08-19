// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.28;

/// @title LifeOS digest-only anchor registry
/// @notice Stores only a Merkle/document digest and its first anchor timestamp. User content,
/// account identifiers, filenames, and prompts must never be sent to this contract.
contract AnchorRegistry {
    mapping(bytes32 => uint256) public anchoredAt;

    event RootAnchored(bytes32 indexed digest, uint256 timestamp);

    function anchorRoot(bytes32 digest) external {
        require(digest != bytes32(0), "empty digest");
        if (anchoredAt[digest] == 0) {
            anchoredAt[digest] = block.timestamp;
            emit RootAnchored(digest, block.timestamp);
        }
    }
}
