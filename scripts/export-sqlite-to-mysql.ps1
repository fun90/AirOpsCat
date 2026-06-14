param(
    [Parameter(Mandatory = $true)]
    [string]$SqliteExe,

    [Parameter(Mandatory = $true)]
    [string]$SourceDb,

    [Parameter(Mandatory = $true)]
    [string]$OutputFile,

    [string]$DatabaseName = 'airopscat'
)

$ErrorActionPreference = 'Stop'

$tablesOrder = @(
    'user', 'tag', 'server', 'account', 'domain', 'node',
    'server_node', 'server_traffic_stats', 'account_traffic_stats',
    'account_online_ip', 'node_tag', 'account_tag', 'transactions'
)

$datetimeColumns = @{
    account = @('create_time', 'from_date', 'to_date', 'update_time')
    account_online_ip = @('create_time', 'last_online_time', 'update_time')
    account_traffic_stats = @('create_time', 'period_end', 'period_start', 'update_time')
    domain = @('create_time', 'update_time')
    node = @('create_time', 'update_time')
    server = @('create_time', 'update_time')
    server_node = @('create_time', 'update_time')
    server_traffic_stats = @('create_time', 'period_end', 'period_start', 'update_time')
    tag = @('create_time', 'update_time')
    transactions = @('create_time', 'transaction_date', 'update_time')
    user = @('create_time', 'lock_time', 'update_time')
}

$dateColumns = @{
    domain = @('expire_date')
    server = @('expire_date', 'bandwidth_date')
}

$autoIncrementTables = @(
    'account', 'account_online_ip', 'account_traffic_stats', 'domain', 'node',
    'server', 'server_node', 'server_traffic_stats', 'tag',
    'transactions', 'user'
)

function Invoke-SqliteJson {
    param(
        [string]$Db,
        [string]$Sql
    )

    $raw = & $SqliteExe -json $Db $Sql
    if ($LASTEXITCODE -ne 0) {
        throw "sqlite3 command failed: $Sql"
    }
    $json = [string]::Join("`n", @($raw))
    if ([string]::IsNullOrWhiteSpace($json)) {
        return @()
    }
    $parsed = $json | ConvertFrom-Json
    return @($parsed)
}

function Invoke-SqliteCsv {
    param(
        [string]$Db,
        [string]$Sql,
        [string[]]$Headers
    )

    $raw = & $SqliteExe -csv $Db $Sql
    if ($LASTEXITCODE -ne 0) {
        throw "sqlite3 command failed: $Sql"
    }
    if ([string]::IsNullOrWhiteSpace($raw)) {
        return @()
    }
    if ($Headers -and $Headers.Count -gt 0) {
        return $raw | ConvertFrom-Csv -Header $Headers
    }
    return $raw | ConvertFrom-Csv
}

function Convert-ToMySqlType {
    param(
        [string]$DeclaredType,
        [string]$ColumnName,
        [string[]]$PrimaryKeys
    )

    if ($null -eq $DeclaredType) {
        $normalized = ''
    } else {
        $normalized = $DeclaredType.Trim().ToLowerInvariant()
    }
    switch -Regex ($normalized) {
        '^integer$' {
            if ($ColumnName -eq 'id' -or $ColumnName.EndsWith('_id')) {
                return 'BIGINT'
            }
            return 'INT'
        }
        '^bigint$' { return 'BIGINT' }
        '^varchar\(\d+\)$' { return $DeclaredType.ToUpperInvariant() }
        '^text$' { return 'TEXT' }
        '^timestamp$' { return 'DATETIME(3)' }
        '^date$' { return 'DATE' }
        '^json$' { return 'JSON' }
        '^numeric\(.+\)$' { return $DeclaredType.ToUpperInvariant().Replace('NUMERIC', 'DECIMAL') }
        default {
            if ([string]::IsNullOrWhiteSpace($DeclaredType)) {
                return 'VARCHAR(255)'
            }
            return $DeclaredType.ToUpperInvariant()
        }
    }
}

function Escape-MySqlString {
    param([string]$Value)

    return $Value.Replace('\', '\\').Replace("'", "''").Replace("`r", '\r').Replace("`n", '\n')
}

function Format-MySqlLiteral {
    param(
        [string]$Table,
        [string]$Column,
        $Value
    )

    if ($null -eq $Value) {
        return 'NULL'
    }

    if ($datetimeColumns.ContainsKey($Table) -and $datetimeColumns[$Table] -contains $Column) {
        if ($Value -is [long] -or $Value -is [int] -or $Value -is [double]) {
            return "FROM_UNIXTIME($Value / 1000.0)"
        }
        return "'$(Escape-MySqlString ([string]$Value))'"
    }

    if ($dateColumns.ContainsKey($Table) -and $dateColumns[$Table] -contains $Column) {
        if ($Value -is [long] -or $Value -is [int] -or $Value -is [double]) {
            return "DATE(FROM_UNIXTIME($Value / 1000.0))"
        }
        return "'$(Escape-MySqlString ([string]$Value))'"
    }

    if ($Value -is [string]) {
        return "'$(Escape-MySqlString $Value)'"
    }

    if ($Value -is [bool]) {
        return $(if ($Value) { '1' } else { '0' })
    }

    return [string]$Value
}

$tables = Invoke-SqliteJson -Db $SourceDb -Sql "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name;"
$tableNames = @($tables | ForEach-Object { $_.name })
$orderedTables = @($tablesOrder | Where-Object { $tableNames -contains $_ })
$orderedTables += @($tableNames | Where-Object { $tablesOrder -notcontains $_ } | Sort-Object)

$builder = [System.Text.StringBuilder]::new()
[void]$builder.AppendLine("-- Generated from SQLite database: $SourceDb")
[void]$builder.AppendLine('SET NAMES utf8mb4;')
[void]$builder.AppendLine('SET FOREIGN_KEY_CHECKS = 0;')
[void]$builder.AppendLine("CREATE DATABASE IF NOT EXISTS ``$DatabaseName`` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;")
[void]$builder.AppendLine("USE ``$DatabaseName``;")
[void]$builder.AppendLine()

foreach ($table in $orderedTables) {
    $columns = @(Invoke-SqliteCsv -Db $SourceDb -Sql "PRAGMA table_info('$table');" -Headers @('cid', 'name', 'type', 'notnull', 'dflt_value', 'pk'))
    $primaryKeys = @($columns | Where-Object { $_.pk -gt 0 } | Sort-Object pk | ForEach-Object { $_.name })

    $uniqueKeys = @()
    $indexList = @(Invoke-SqliteCsv -Db $SourceDb -Sql "PRAGMA index_list('$table');" -Headers @('seq', 'name', 'unique', 'origin', 'partial'))
    foreach ($index in $indexList) {
        if ($index.unique -ne 1 -or $index.origin -eq 'pk') {
            continue
        }
        $indexColumns = @(Invoke-SqliteCsv -Db $SourceDb -Sql "PRAGMA index_info('$($index.name)');" -Headers @('seqno', 'cid', 'name') | ForEach-Object { $_.name })
        $uniqueKeys += [pscustomobject]@{
            Name = $index.name
            Columns = $indexColumns
        }
    }

    [void]$builder.AppendLine("DROP TABLE IF EXISTS ``$table``;")
    [void]$builder.AppendLine("CREATE TABLE ``$table`` (")

    $definitionLines = New-Object System.Collections.Generic.List[string]
    foreach ($column in $columns) {
        $columnName = $column.name
        $columnType = Convert-ToMySqlType -DeclaredType $column.type -ColumnName $columnName -PrimaryKeys $primaryKeys
        $nullable = if ($column.notnull -eq 1 -or $primaryKeys -contains $columnName) { 'NOT NULL' } else { 'NULL' }
        $extra = ''

        if ($autoIncrementTables -contains $table -and $columnName -eq 'id' -and $primaryKeys.Count -eq 1 -and $primaryKeys[0] -eq 'id') {
            $columnType = 'BIGINT'
            $extra = ' AUTO_INCREMENT'
        }

        $definitionLines.Add("  ``$columnName`` $columnType $nullable$extra")
    }

    if ($primaryKeys.Count -gt 0) {
        $quotedPrimaryKeys = $primaryKeys | ForEach-Object { "``$_``" }
        $definitionLines.Add("  PRIMARY KEY ($($quotedPrimaryKeys -join ', '))")
    }

    foreach ($uniqueKey in $uniqueKeys) {
        $quotedColumns = $uniqueKey.Columns | ForEach-Object { "``$_``" }
        $definitionLines.Add("  UNIQUE KEY ``$($uniqueKey.Name)`` ($($quotedColumns -join ', '))")
    }

    [void]$builder.AppendLine(($definitionLines -join ",`n"))
    [void]$builder.AppendLine(') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;')
    [void]$builder.AppendLine()

    $rows = @(Invoke-SqliteJson -Db $SourceDb -Sql "SELECT * FROM '$table';")
    if ($rows.Count -gt 0) {
        $quotedColumnNames = $columns | ForEach-Object { "``$($_.name)``" }
        foreach ($row in $rows) {
            $values = foreach ($column in $columns) {
                Format-MySqlLiteral -Table $table -Column $column.name -Value $row.($column.name)
            }
            [void]$builder.AppendLine("INSERT INTO ``$table`` ($($quotedColumnNames -join ', ')) VALUES ($($values -join ', '));")
        }
        [void]$builder.AppendLine()
    }
}

[void]$builder.AppendLine('SET FOREIGN_KEY_CHECKS = 1;')

[System.IO.File]::WriteAllText($OutputFile, $builder.ToString(), [System.Text.UTF8Encoding]::new($false))

Write-Host "Wrote $OutputFile"
foreach ($table in $orderedTables) {
    $rowCount = (& $SqliteExe $SourceDb "SELECT COUNT(*) FROM '$table';").Trim()
    Write-Host "${table}: $rowCount"
}
