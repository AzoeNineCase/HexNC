<#
    HexNC Windows MSI post-processing.

    Run by the `packageMsi` Gradle task right after jpackage/light have produced
    the MSI (see build.gradle.kts). It rewrites three things in the compiled MSI
    database:

      1. Property.ProductCode            -> a build-unique ProductCode.
      2. Upgrade(<upgradable row>)       -> Attributes |= VersionMaxInclusive.
      3. Shortcut                        -> an extra "Uninstall HexNC" link in the
                                            Start-menu folder.

    Why:

      * jpackage derives ProductCode from packageName + packageVersion only, so
        every rebuild of 1.0.0 carried the ProductCode of the already installed
        HexNC 1.0.0. Windows Installer then treated the second install as a
        maintenance run of the very same product and replaced nothing while
        still returning 0 ("Skipping RemoveExistingProducts action: current
        configuration is maintenance mode or an uninstall"). A fresh ProductCode
        plus the unchanged UpgradeCode makes the installer see the old product as
        an *older product of the same family* and run RemoveExistingProducts:
        remove-then-install, one ARP entry, no side-by-side install.
      * jpackage's WiX template compiles its own-version UpgradeVersion row with
        IncludeMaximum="no" (observed attributes 257), so a product of exactly
        1.0.0 matches neither Upgrade row and would never be removed. Setting
        the VersionMaxInclusive bit lifts the row's VersionMax to "<= 1.0.0"
        (attributes 769).
      * The Start menu only contained "HexNC"; the Start-menu uninstall link is
        what users look for.

    Implementation notes (all measured against this MSI):

      * The database is opened in transact mode (1) and committed at the end.
        Any failure therefore leaves the MSI byte-identical (the Gradle task
        fails the build instead of shipping a half-patched MSI).
      * MSI SQL refuses to UPDATE a column that is part of the table's primary
        key - `UPDATE Upgrade SET Attributes=...` always fails with
        "Execute,Params" - so that row is deleted and re-inserted.
      * `Registry.Key` cannot even be named in a query ("SELECT Key FROM
        Registry" -> "OpenView,Sql"), so no new component/registry keypath is
        created: the uninstall link is added to the component that already owns
        the Start-menu shortcut (it already has a valid key path and is already
        in DefaultFeature, and it is conditioned on
        JP_INSTALL_STARTMENU_SHORTCUT, so the uninstall link appears exactly
        when the application link does).
      * PackageCode is left alone: light.exe already stamps a fresh one per
        build, and property 9 of the summary information is not writable through
        the COM automation interface anyway.
#>
[CmdletBinding()]
param(
    # MSI to patch, e.g. build\compose\binaries\main\msi\HexNC-1.0.0.msi
    [Parameter(Mandatory = $true)][string]$Msi,
    # Per-build ProductCode, e.g. "{7B3E1F0C-...}"
    [Parameter(Mandatory = $true)][string]$ProductCode
)

$ErrorActionPreference = 'Stop'
$step = 'init'

$uninstallShortcutId = 'hexncUninstallHexNC'
$uninstallShortcutName = 'Uninstall HexNC'

function Write-Step {
    param([string]$Text)
    Write-Host "  $Text"
}

function Fail {
    param([string]$Message)
    Write-Host "PATCH_FAILED at '$step': $Message"
    exit 1
}

if (-not (Test-Path -LiteralPath $Msi -PathType Leaf)) { $step = 'check input'; Fail "no such MSI: $Msi" }

$installer = New-Object -ComObject WindowsInstaller.Installer

# 1 = msiOpenDatabaseModeTransact; use the late-bound API, which the Windows
# PowerShell COM binder drives correctly as long as every void call is swallowed
# with Out-Null (their $null results would otherwise join a helper's output).
$step = 'open database'
$db = $installer.OpenDatabase($Msi, 1)
if ($null -eq $db) { Fail "could not open $Msi" }

function Invoke-MsiSql {
    param([string]$Sql)
    $view = $db.OpenView($Sql)
    if ($null -eq $view) { throw "OpenView returned nothing for: $Sql" }
    $view.Execute() | Out-Null
    $view.Close() | Out-Null
}

function Get-MsiRows {
    param([string]$Sql, [int]$FieldCount)
    $rows = @()
    $view = $db.OpenView($Sql)
    if ($null -eq $view) { throw "OpenView returned nothing for: $Sql" }
    $view.Execute() | Out-Null
    while ($true) {
        $record = $view.Fetch()
        if ($null -eq $record) { break }
        $fields = @()
        for ($i = 1; $i -le $FieldCount; $i++) {
            $value = ''
            try { $value = [string]$record.StringData($i) } catch { $value = '' }
            $fields += $value
        }
        $rows += , $fields
    }
    $view.Close() | Out-Null
    return , $rows
}

try {
    # ---------------------------------------------------------------- 1. ProductCode
    $step = 'read ProductCode'
    $rows = Get-MsiRows "SELECT Value FROM Property WHERE Property='ProductCode'" 1
    if ($rows.Count -ne 1) { Fail "expected exactly one ProductCode row, found $($rows.Count)" }
    $oldProductCode = $rows[0][0]
    if ($oldProductCode -eq $ProductCode) { Fail "MSI already carries ProductCode $ProductCode" }

    $step = 'write ProductCode'
    Invoke-MsiSql "UPDATE Property SET Value='$ProductCode' WHERE Property='ProductCode'"
    Write-Step "ProductCode  : $oldProductCode -> $ProductCode"

    # ------------------------------------------------- 2. Upgrade row (version max)
    $step = 'read Upgrade row'
    $rows = Get-MsiRows "SELECT UpgradeCode, VersionMin, VersionMax, Language, Attributes, Remove, ActionProperty FROM Upgrade WHERE ActionProperty='JP_UPGRADABLE_FOUND'" 7
    if ($rows.Count -ne 1) { Fail "expected exactly one JP_UPGRADABLE_FOUND row, found $($rows.Count)" }
    $row = $rows[0]
    $attributes = 0
    if (-not [int]::TryParse($row[4], [ref]$attributes)) { Fail "cannot parse Upgrade Attributes '$($row[4])'" }
    $newAttributes = $attributes -bor 0x200   # VersionMaxInclusive

    if ($newAttributes -eq $attributes) {
        Write-Step "Upgrade row  : already version-max inclusive (attributes $attributes)"
    }
    else {
        $step = 'write Upgrade row'
        $upgradeCode = $row[0]
        $versionMin = if ($row[1] -eq '') { 'NULL' } else { "'$($row[1])'" }
        $versionMax = if ($row[2] -eq '') { 'NULL' } else { "'$($row[2])'" }
        $language = if ($row[3] -eq '') { 'NULL' } else { $row[3] }
        $remove = if ($row[5] -eq '') { 'NULL' } else { "'$($row[5])'" }
        $actionProperty = $row[6]
        # Attributes is part of the Upgrade table's primary key: UPDATE is refused
        # by the MSI SQL engine, so the row has to be replaced as a whole.
        Invoke-MsiSql "DELETE FROM Upgrade WHERE ActionProperty='JP_UPGRADABLE_FOUND'"
        Invoke-MsiSql ("INSERT INTO Upgrade (UpgradeCode, VersionMin, VersionMax, Language, Attributes, Remove, ActionProperty) " +
            "VALUES ('$upgradeCode', $versionMin, $versionMax, $language, $newAttributes, $remove, '$actionProperty')")
        Write-Step "Upgrade row  : attributes $attributes -> $newAttributes ($actionProperty, max $versionMax)"
    }

    # --------------------------------------------- 3. Start menu uninstall shortcut
    $step = 'find Start menu directory'
    $menuRows = Get-MsiRows "SELECT Directory, DefaultDir FROM Directory WHERE Directory_Parent='ProgramMenuFolder'" 2
    if ($menuRows.Count -ne 1) { Fail "expected exactly one Start menu directory, found $($menuRows.Count)" }
    $menuDir = $menuRows[0][0]

    $step = 'find application shortcut'
    $shortcutRows = Get-MsiRows "SELECT Component_ FROM Shortcut WHERE Directory_='$menuDir'" 1
    if ($shortcutRows.Count -ne 1) { Fail "expected exactly one shortcut in '$menuDir', found $($shortcutRows.Count)" }
    $shortcutComponent = $shortcutRows[0][0]

    $step = 'read Icon table'
    $iconName = ''
    $iconRows = Get-MsiRows "SELECT Name FROM Icon" 1
    if ($iconRows.Count -eq 1) { $iconName = $iconRows[0][0] }

    $step = 'check existing uninstall shortcut'
    $existing = Get-MsiRows "SELECT Shortcut FROM Shortcut WHERE Shortcut='$uninstallShortcutId'" 1
    if ($existing.Count -ge 1) {
        Write-Step "Uninstall    : already present ($uninstallShortcutId)"
    }
    else {
        $step = 'write uninstall shortcut'
        $target = '[SystemFolder]msiexec.exe'
        $arguments = '/x [ProductCode]'
        Invoke-MsiSql ("INSERT INTO Shortcut (Shortcut, Directory_, Name, Component_, Target, Arguments, Description) " +
            "VALUES ('$uninstallShortcutId', '$menuDir', '$uninstallShortcutName', '$shortcutComponent', " +
            "'$target', '$arguments', '$uninstallShortcutName')")
        if ($iconName -ne '') {
            $step = 'write uninstall shortcut icon'
            Invoke-MsiSql "UPDATE Shortcut SET Icon_='$iconName' WHERE Shortcut='$uninstallShortcutId'"
        }
        Write-Step "Uninstall    : shortcut '$uninstallShortcutName' in '$menuDir' (component $shortcutComponent, icon '$iconName')"
    }

    $step = 'commit'
    $db.Commit() | Out-Null
    Write-Step 'MSI database committed'
}
catch {
    Fail $_.Exception.Message
}

exit 0
