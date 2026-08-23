// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.28;

/// @title LifeOS digest-only anchor registry
/// @notice Stores only a Merkle/document digest and its first anchor timestamp. User content,
/// account identifiers, filenames, and prompts must never be sent to this contract. `isAnchored`
/// distinguishes an absent root from a root first anchored at timestamp zero.
contract AnchorRegistry {
    mapping(bytes32 => uint256) public anchoredAt;
    mapping(bytes32 => bool) public isAnchored;

    event RootAnchored(bytes32 indexed digest, uint256 timestamp);

    function anchorRoot(bytes32 digest) external {
        require(digest != bytes32(0), "empty digest");
        if (!isAnchored[digest]) {
            isAnchored[digest] = true;
            anchoredAt[digest] = block.timestamp;
            emit RootAnchored(digest, block.timestamp);
        }
    }
}
