#!/usr/bin/env node

'use strict';

const fs = require('node:fs');

let cyclonedx;
let PackageURL;
try {
    cyclonedx = require('@cyclonedx/cyclonedx-library');
    ({ PackageURL } = require('packageurl-js'));
} catch {
    console.error('CycloneDX validation dependencies are unavailable; run npm ci.');
    process.exitCode = 69;
}

const supportedVersions = new Set(['1.5', '1.6', '1.7']);
const serializedPurlCharacters = /^[A-Za-z0-9._~%:/@?=&#-]+$/;
const serializedQualifierKey = /^[a-z][a-z0-9._-]*$/;

function splitSerializedPurl(purl) {
    const fragmentIndex = purl.indexOf('#');
    const beforeFragment = fragmentIndex === -1 ? purl : purl.slice(0, fragmentIndex);
    const subpath = fragmentIndex === -1 ? undefined : purl.slice(fragmentIndex + 1);
    const queryIndex = beforeFragment.indexOf('?');

    return {
        path: queryIndex === -1 ? beforeFragment : beforeFragment.slice(0, queryIndex),
        query: queryIndex === -1 ? undefined : beforeFragment.slice(queryIndex + 1),
        subpath,
    };
}

function decodeSerializedPurlComponent(component) {
    try {
        return decodeURIComponent(component);
    } catch {
        return undefined;
    }
}

function validateSerializedPurlPath(path) {
    if (!path.startsWith('pkg:')) {
        return 'must use the pkg: scheme';
    }

    const schemeSpecificPath = path.slice('pkg:'.length);
    const typeSeparator = schemeSpecificPath.indexOf('/');
    if (typeSeparator <= 0 || typeSeparator === schemeSpecificPath.length - 1) {
        return 'must serialize a package type and name as pkg:type/name';
    }

    const coordinatesAndVersion = schemeSpecificPath.slice(typeSeparator + 1);
    if (coordinatesAndVersion.includes('&') || coordinatesAndVersion.includes('=')) {
        return 'must percent-encode & and = outside qualifier values';
    }

    const versionSeparator = coordinatesAndVersion.indexOf('@');
    if (versionSeparator !== -1) {
        if (
            versionSeparator !== coordinatesAndVersion.lastIndexOf('@') ||
            versionSeparator <= coordinatesAndVersion.lastIndexOf('/') ||
            versionSeparator === coordinatesAndVersion.length - 1
        ) {
            return 'must use @ only as a non-empty version delimiter';
        }
    }

    const coordinates = versionSeparator === -1
        ? coordinatesAndVersion
        : coordinatesAndVersion.slice(0, versionSeparator);
    for (const segment of coordinates.split('/')) {
        if (segment.length === 0) {
            return 'must not contain empty namespace or name segments';
        }

        const decodedSegment = decodeSerializedPurlComponent(segment);
        if (decodedSegment === undefined || decodedSegment.includes('/')) {
            return 'must not contain an encoded namespace or name separator';
        }
    }

    return undefined;
}

function validateSerializedPurlQualifiers(query) {
    if (query === undefined) {
        return undefined;
    }
    if (query.length === 0 || query.includes('?')) {
        return 'must contain non-empty key=value qualifiers without raw ? characters';
    }

    const keys = new Set();
    for (const qualifier of query.split('&')) {
        const equalsIndex = qualifier.indexOf('=');
        if (
            equalsIndex <= 0 ||
            equalsIndex === qualifier.length - 1 ||
            equalsIndex !== qualifier.lastIndexOf('=')
        ) {
            return 'must contain non-empty key=value qualifiers with encoded = in values';
        }

        const key = qualifier.slice(0, equalsIndex);
        if (!serializedQualifierKey.test(key)) {
            return `has an invalid qualifier key ${JSON.stringify(key)}`;
        }
        if (keys.has(key)) {
            return `contains a duplicate qualifier key ${JSON.stringify(key)}`;
        }
        keys.add(key);
    }

    return undefined;
}

function validateSerializedPurlSubpath(subpath) {
    if (subpath === undefined) {
        return undefined;
    }
    if (subpath.length === 0 || subpath.includes('#') || subpath.includes('?')) {
        return 'must contain non-empty slash-delimited segments without raw ? or # characters';
    }

    for (const segment of subpath.split('/')) {
        const decodedSegment = decodeSerializedPurlComponent(segment);
        if (
            segment.length === 0 ||
            decodedSegment === undefined ||
            decodedSegment.includes('/') ||
            decodedSegment === '.' ||
            decodedSegment === '..'
        ) {
            return 'must not contain empty, dot, or encoded-separator segments';
        }
    }

    return undefined;
}

function validateSerializedPurlSafety(purl) {
    // SBOM PURLs are stored as ASCII, percent-encoded serializations. The
    // upstream parser normalizes permissive input, so retain our explicit
    // source-safety contract before that normalization can hide ambiguity.
    if (!serializedPurlCharacters.test(purl)) {
        return 'must use only ASCII PURL characters and percent-encode unsafe characters';
    }
    if (decodeSerializedPurlComponent(purl) === undefined) {
        return 'contains malformed percent encoding or invalid percent-encoded UTF-8';
    }

    const { path, query, subpath } = splitSerializedPurl(purl);
    if (subpath !== undefined && subpath.includes('#')) {
        return 'must not contain multiple fragment delimiters';
    }

    return (
        validateSerializedPurlPath(path) ??
        validateSerializedPurlQualifiers(query) ??
        validateSerializedPurlSubpath(subpath)
    );
}

function validatePurl(purl) {
    try {
        PackageURL.fromString(purl);
    } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        return `is not a valid package URL: ${message}`;
    }

    return validateSerializedPurlSafety(purl);
}

function isObject(value) {
    return value !== null && !Array.isArray(value) && typeof value === 'object';
}

function validateComponentArray(components, pointer, errors) {
    if (!Array.isArray(components)) {
        return;
    }

    components.forEach((component, index) => {
        validateComponentPurls(component, `${pointer}/${index}`, errors);
    });
}

function validateComponentPurls(component, pointer, errors) {
    if (!isObject(component)) {
        return;
    }

    const hasPurl = Object.hasOwn(component, 'purl');
    if (component.type === 'library' && !hasPurl) {
        errors.push(`${pointer}/purl: library components require a PURL`);
    } else if (hasPurl) {
        if (typeof component.purl !== 'string') {
            errors.push(`${pointer}/purl: must be a string`);
        } else {
            const error = validatePurl(component.purl);
            if (error !== undefined) {
                errors.push(`${pointer}/purl: ${error}`);
            }
        }
    }

    validateComponentArray(component.components, `${pointer}/components`, errors);

    if (isObject(component.pedigree)) {
        for (const relationship of ['ancestors', 'descendants', 'variants']) {
            validateComponentArray(
                component.pedigree[relationship],
                `${pointer}/pedigree/${relationship}`,
                errors
            );
        }
    }
}

function validateBomPurls(bom) {
    const errors = [];
    validateComponentArray(bom.components, '/components', errors);

    if (isObject(bom.metadata)) {
        if (isObject(bom.metadata.component)) {
            validateComponentPurls(bom.metadata.component, '/metadata/component', errors);
        }
        if (isObject(bom.metadata.tools)) {
            validateComponentArray(bom.metadata.tools.components, '/metadata/tools/components', errors);
        }
    }

    if (Array.isArray(bom.vulnerabilities)) {
        bom.vulnerabilities.forEach((vulnerability, index) => {
            if (isObject(vulnerability) && isObject(vulnerability.tools)) {
                validateComponentArray(
                    vulnerability.tools.components,
                    `/vulnerabilities/${index}/tools/components`,
                    errors
                );
            }
        });
    }

    if (Array.isArray(bom.annotations)) {
        bom.annotations.forEach((annotation, index) => {
            if (isObject(annotation) && isObject(annotation.annotator) && isObject(annotation.annotator.component)) {
                validateComponentPurls(
                    annotation.annotator.component,
                    `/annotations/${index}/annotator/component`,
                    errors
                );
            }
        });
    }

    if (Array.isArray(bom.formulation)) {
        bom.formulation.forEach((formula, index) => {
            if (isObject(formula)) {
                validateComponentArray(formula.components, `/formulation/${index}/components`, errors);
            }
        });
    }

    if (isObject(bom.declarations) && isObject(bom.declarations.targets)) {
        validateComponentArray(bom.declarations.targets.components, '/declarations/targets/components', errors);
    }

    return errors;
}

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
    const schemaErrors = await validator.validate(source);
    if (schemaErrors !== null) {
        console.error(`CycloneDX ${specVersion} schema validation failed for ${JSON.stringify(sbomPath)}:`);
        for (const error of Array.isArray(schemaErrors) ? schemaErrors : [schemaErrors]) {
            const location = error.instancePath === '' ? '/' : error.instancePath ?? '/';
            console.error(`${location}: ${error.message ?? 'schema validation failed'}`);
        }
        process.exitCode = 65;
        return;
    }

    const purlErrors = validateBomPurls(bom);
    if (purlErrors.length > 0) {
        console.error(`CycloneDX PURL validation failed for ${JSON.stringify(sbomPath)}:`);
        for (const error of purlErrors) {
            console.error(error);
        }
        process.exitCode = 65;
    }
}

if (process.exitCode === undefined) {
    validateSchema().catch((error) => {
        if (error instanceof cyclonedx.Validation.MissingOptionalDependencyError) {
            console.error('CycloneDX validation dependencies are unavailable; run npm ci.');
            process.exitCode = 69;
            return;
        }

        const message = error instanceof Error ? error.message : String(error);
        console.error(`CycloneDX schema validation failed: ${JSON.stringify(message)}`);
        process.exitCode = 70;
    });
}
