#!/usr/bin/env node

'use strict';

const fs = require('node:fs');

let cyclonedx;
try {
    cyclonedx = require('@cyclonedx/cyclonedx-library');
} catch {
    console.error('CycloneDX schema validator dependencies are unavailable; run npm ci.');
    process.exitCode = 69;
}

const supportedVersions = new Set(['1.5', '1.6', '1.7']);

async function validateSchema() {
    const args = process.argv.slice(2);
    if (args.length !== 1) {
        console.error('Usage: validate-cyclonedx-schema.js <sbom-path>');
        process.exitCode = 64;
        return;
    }

    const sbomPath = args[0];
    let bom;
    let source;
    try {
        source = fs.readFileSync(sbomPath, 'utf8');
        bom = JSON.parse(source);
    } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        console.error(`Unable to parse CycloneDX SBOM ${JSON.stringify(sbomPath)}: ${JSON.stringify(message)}`);
        process.exitCode = 65;
        return;
    }

    if (bom === null || Array.isArray(bom) || typeof bom !== 'object') {
        console.error(`CycloneDX SBOM ${JSON.stringify(sbomPath)} must be a JSON object.`);
        process.exitCode = 65;
        return;
    }

    const specVersion = bom.specVersion;
    if (!supportedVersions.has(specVersion)) {
        console.error(
            `Unsupported CycloneDX specVersion in ${JSON.stringify(sbomPath)}: ${JSON.stringify(specVersion)}. ` +
            'Supported versions are 1.5, 1.6, and 1.7.'
        );
        process.exitCode = 65;
        return;
    }

    const validator = new cyclonedx.Validation.JsonStrictValidator(specVersion);
    const errors = await validator.validate(source);
    if (errors === null) {
        return;
    }

    console.error(`CycloneDX ${specVersion} schema validation failed for ${JSON.stringify(sbomPath)}:`);
    for (const error of Array.isArray(errors) ? errors : [errors]) {
        const location = error.instancePath === '' ? '/' : error.instancePath ?? '/';
        console.error(`${location}: ${error.message ?? 'schema validation failed'}`);
    }
    process.exitCode = 65;
}

if (process.exitCode === undefined) {
    validateSchema().catch((error) => {
        if (error instanceof cyclonedx.Validation.MissingOptionalDependencyError) {
            console.error('CycloneDX schema validator dependencies are unavailable; run npm ci.');
            process.exitCode = 69;
            return;
        }

        const message = error instanceof Error ? error.message : String(error);
        console.error(`CycloneDX schema validation failed: ${JSON.stringify(message)}`);
        process.exitCode = 70;
    });
}
