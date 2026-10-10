#!/usr/bin/env node
'use strict';

const assert = require('node:assert/strict');
const {isSupportedNodeVersion} = require('./node-runtime-check.js');

assert.equal(isSupportedNodeVersion('20.17.9'), false);
assert.equal(isSupportedNodeVersion('20.18.0'), true);
assert.equal(isSupportedNodeVersion('21.0.0'), true);
assert.equal(isSupportedNodeVersion('20.invalid'), false);
console.log('Node runtime version checks passed');
