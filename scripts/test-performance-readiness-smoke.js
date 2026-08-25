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

async function executeReadinessScenario() {
    const context = vm.createContext({
        __ENV: {
            TARGET_URL: 'https://gateway.example.test/',
            VUS: '3',
            DURATION: '5s',
        },
    });
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

    assert.equal(scenario.namespace.options.vus, 3);
    assert.equal(scenario.namespace.options.duration, '5s');
    assert.equal(scenario.namespace.options.thresholds.http_req_failed[0], 'rate<0.01');
    assert.equal(scenario.namespace.options.thresholds.http_req_duration[0], 'p(95)<500');
    assert.equal(scenario.namespace.options.thresholds.checks[0], 'rate==1');

    await scenario.namespace.default();

    assert.equal(requestCalls.length, 1);
    assert.equal(requestCalls[0].url, 'https://gateway.example.test/actuator/health/readiness');
    assert.equal(requestCalls[0].options.tags.operation, 'readiness-smoke');
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

executeReadinessScenario()
    .then(() => {
        console.log('Readiness smoke scenario behavior passed');
    })
    .catch((error) => {
        console.error(error);
        process.exitCode = 1;
    });
