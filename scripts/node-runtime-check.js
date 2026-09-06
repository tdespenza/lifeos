#!/usr/bin/env node
'use strict';

const MINIMUM_NODE_MAJOR = 20;
const MINIMUM_NODE_MINOR = 18;
const MINIMUM_NODE_VERSION = `${MINIMUM_NODE_MAJOR}.${MINIMUM_NODE_MINOR}.0`;

function isSupportedNodeVersion(version) {
    const match = /^(\d+)\.(\d+)(?:\.(\d+))?(?:[-+].*)?$/.exec(version);
    if (!match) {
        return false;
    }

    const major = Number(match[1]);
    const minor = Number(match[2]);
    return Number.isInteger(major)
        && Number.isInteger(minor)
        && (major > MINIMUM_NODE_MAJOR
            || (major === MINIMUM_NODE_MAJOR && minor >= MINIMUM_NODE_MINOR));
}

function main() {
    const version = process.env.LIFEOS_NODE_VERSION_OVERRIDE || process.versions.node;
    if (!isSupportedNodeVersion(version)) {
        console.error(
            `Node.js >=${MINIMUM_NODE_VERSION} is required for performance readiness checks; found ${version}`,
        );
        process.exitCode = 1;
    }
}

if (require.main === module) {
    main();
}

module.exports = {
    isSupportedNodeVersion,
    MINIMUM_NODE_VERSION,
};
