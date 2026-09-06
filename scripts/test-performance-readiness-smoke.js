#!/usr/bin/env node
'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const repositoryRoot = path.resolve(__dirname, '..');
process.chdir(repositoryRoot);

const scenarioPath = path.resolve('scripts/performance/readiness-smoke.js');
const scenarioSource = fs.readFileSync('scripts/performance/readiness-smoke.js', 'utf8');

async function loadScenario(environment) {
    const requestCalls = [];
    const checkCalls = [];
    const sleepCalls = [];
    const jsonFields = [];
    const readinessResponse = Object.freeze({
        status: 200,
        json(field) {
            jsonFields.push(field);
            return field === 'status' ? 'UP' : undefined;
        },
    });
    const http = Object.freeze({
        get(url, options) {
            requestCalls.push({url, options});
            return readinessResponse;
        },
    });
    const k6 = Object.freeze({
        check(response, predicates) {
            checkCalls.push({response, predicates});
            return Object.values(predicates).every((predicate) => predicate(response));
        },
        sleep(seconds) {
            sleepCalls.push(seconds);
        },
    });
    const context = vm.createContext({__ENV: environment});
    const httpModule = new vm.SyntheticModule(
        ['default'],
        function initializeHttpModule() {
            this.setExport('default', http);
        },
        {context, identifier: 'k6/http'},
    );
    const k6Module = new vm.SyntheticModule(
        ['check', 'sleep'],
        function initializeK6Module() {
            this.setExport('check', k6.check);
            this.setExport('sleep', k6.sleep);
        },
        {context, identifier: 'k6'},
    );
    const scenario = new vm.SourceTextModule(scenarioSource, {
        context,
        identifier: scenarioPath,
    });

    await scenario.link((specifier) => {
        if (specifier === 'k6/http') {
            return httpModule;
        }
        if (specifier === 'k6') {
            return k6Module;
        }
        throw new Error(`Unexpected readiness scenario import: ${specifier}`);
    });
    await scenario.evaluate();
    return {
        scenario,
        recorders: {requestCalls, checkCalls, sleepCalls, jsonFields, readinessResponse},
    };
}

async function executeReadinessScenario() {
    const {scenario, recorders} = await loadScenario({
        TARGET_URL: 'https://gateway.example.test///',
        VUS: '3',
        DURATION: '5s',
    });
    const {requestCalls, checkCalls, sleepCalls, jsonFields, readinessResponse} = recorders;

    assert.equal(scenario.namespace.options.vus, 3);
    assert.equal(scenario.namespace.options.duration, '5s');
    assert.equal(scenario.namespace.options.thresholds.http_req_failed[0], 'rate<0.01');
    assert.equal(scenario.namespace.options.thresholds.http_req_duration[0], 'p(95)<500');
    assert.equal(scenario.namespace.options.thresholds.checks[0], 'rate==1');

    await scenario.namespace.default();

    assert.equal(requestCalls.length, 1);
    assert.equal(requestCalls[0].url, 'https://gateway.example.test/actuator/health/readiness');
    assert.equal(requestCalls[0].options.tags.operation, 'readiness-smoke');
    assert.equal(requestCalls[0].options.timeout, '5s');
    assert.deepEqual(jsonFields, ['status']);
    assert.deepEqual(sleepCalls, [0.1]);
    assert.equal(checkCalls.length, 1);

    const [{response, predicates}] = checkCalls;
    assert.equal(response, readinessResponse);
    assert.equal(predicates['readiness status is 200']({status: 200}), true);
    assert.equal(predicates['readiness status is 200']({status: 503}), false);
    assert.equal(predicates['readiness payload is UP']({json: () => 'UP'}), true);
    assert.equal(predicates['readiness payload is UP']({json: () => 'DOWN'}), false);
}

async function executeMissingTargetUrlScenario() {
    await assert.rejects(
        () => loadScenario({VUS: '3', DURATION: '5s'}),
        /TARGET_URL is required/,
    );
}

async function executeInvalidVusScenarios() {
    for (const value of ['1.5', '0', '-1', 'Infinity', 'not-a-number']) {
        await assert.rejects(
            () => loadScenario({TARGET_URL: 'https://gateway.example.test', VUS: value}),
            /VUS must be a positive integer/,
        );
    }
}

async function executeDefaultVusScenario() {
    const {scenario} = await loadScenario({TARGET_URL: 'https://gateway.example.test'});
    assert.equal(scenario.namespace.options.vus, 10);
}

Promise.all([
    executeReadinessScenario(),
    executeMissingTargetUrlScenario(),
    executeInvalidVusScenarios(),
    executeDefaultVusScenario(),
])
    .then(() => {
        console.log('Readiness smoke scenario behavior passed');
    })
    .catch((error) => {
        console.error(error);
        process.exitCode = 1;
    });
