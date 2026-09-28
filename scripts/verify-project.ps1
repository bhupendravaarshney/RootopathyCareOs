$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

function Invoke-CareOsCommand {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath,
        [string[]]$ArgumentList = @()
    )

    & $FilePath @ArgumentList
    if ($LASTEXITCODE -ne 0) {
        throw "CareOS QA command failed with exit code $LASTEXITCODE`: $FilePath $($ArgumentList -join ' ')"
    }
}

Push-Location $repositoryRoot
try {
    Invoke-CareOsCommand "node" @(
        "-e",
        'const [major, minor] = process.versions.node.split(".").map(Number); if (major !== 24 || minor < 15) { console.error(`CareOS QA requires Node >=24.15.0 <25; found ${process.versions.node}`); process.exit(1); }'
    )

    Invoke-CareOsCommand "docker" @("compose", "--env-file", ".env.example", "-f", "compose.yaml", "config", "--quiet")
    Invoke-CareOsCommand "docker" @("compose", "--env-file", ".env.example", "-f", "compose.yaml", "-f", "compose.scanner.yaml", "config", "--quiet")

    Invoke-CareOsCommand "node" @("scripts/verify-prototype-register.mjs")
    Invoke-CareOsCommand "node" @("scripts/verify-api-contract.mjs")
    Invoke-CareOsCommand "node" @("--test", "scripts/tests/verify-api-contract.test.mjs")
    Invoke-CareOsCommand "node" @("scripts/verify-module-1-inputs.mjs", "--require-approved")
    Invoke-CareOsCommand "node" @("--test", "scripts/tests/verify-module-1-inputs.test.mjs")
    Invoke-CareOsCommand "node" @("scripts/verify-module-1-review-drafts.mjs")
    Invoke-CareOsCommand "node" @("--test", "scripts/tests/verify-module-1-review-drafts.test.mjs")
    Invoke-CareOsCommand "node" @("scripts/verify-module-1-candidate-inputs.mjs")
    Invoke-CareOsCommand "node" @("--test", "scripts/tests/verify-module-1-candidate-inputs.test.mjs")
    Invoke-CareOsCommand "node" @("scripts/verify-module-1-facility-scope-candidate.mjs")
    Invoke-CareOsCommand "node" @("--test", "scripts/tests/verify-module-1-facility-scope-candidate.test.mjs")
    Invoke-CareOsCommand "node" @("scripts/verify-module-2-candidate-inputs.mjs")
    Invoke-CareOsCommand "node" @("--test", "scripts/tests/verify-module-2-candidate-inputs.test.mjs")
    Invoke-CareOsCommand "node" @("scripts/verify-module-2-inputs.mjs", "--require-approved")
    Invoke-CareOsCommand "node" @("--test", "scripts/tests/verify-module-2-inputs.test.mjs")
    Invoke-CareOsCommand "node" @("scripts/verify-module-3-candidate-inputs.mjs")
    Invoke-CareOsCommand "node" @("--test", "scripts/tests/verify-module-3-candidate-inputs.test.mjs")
    Invoke-CareOsCommand "node" @("scripts/verify-module-3-inputs.mjs", "--require-approved")
    Invoke-CareOsCommand "node" @("--test", "scripts/tests/verify-module-3-inputs.test.mjs")
    Invoke-CareOsCommand "node" @("scripts/verify-ci-security.mjs")
    Invoke-CareOsCommand "node" @("--test", "scripts/tests/verify-ci-security.test.mjs")

    Push-Location (Join-Path $repositoryRoot "frontend")
    try {
        Invoke-CareOsCommand "npm.cmd" @("ci")
        Invoke-CareOsCommand "npm.cmd" @("audit", "--audit-level=high")
        Invoke-CareOsCommand "npm.cmd" @("run", "api:check")
        Invoke-CareOsCommand "npm.cmd" @("run", "architecture:check")
        Invoke-CareOsCommand "npm.cmd" @("run", "typecheck")
        Invoke-CareOsCommand "npm.cmd" @("run", "lint")
        Invoke-CareOsCommand "npm.cmd" @("run", "format:check")
        Invoke-CareOsCommand "npm.cmd" @("test")
        Invoke-CareOsCommand "npm.cmd" @("run", "build")
        Invoke-CareOsCommand "npm.cmd" @("run", "test:e2e")
    }
    finally {
        Pop-Location
    }

    Push-Location (Join-Path $repositoryRoot "backend")
    try {
        Invoke-CareOsCommand ".\mvnw.cmd" @("-B", "-ntp", "clean", "verify")
    }
    finally {
        Pop-Location
    }
}
finally {
    Pop-Location
}
