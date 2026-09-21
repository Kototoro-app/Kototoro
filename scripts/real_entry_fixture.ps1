# Put the real-entry measurement fixture into the app's own local library, or take it out again.
#
# The real-entry A/B (scripts/interleave_ab_real_entry.ps1) needs deterministic, offline content
# that a cold launch of the production ReaderActivity can open by manga id. The CS-7 fixture pages
# are already on the device (the benchmark generates them), so they are copied into the app's local
# storage root and indexed by the app itself; the one thing the app will not create for a purely
# local manga is a `chapters` row, which scripts/real_entry_db_row.py adds (see its docstring).
#
# Usage:
#   ./scripts/real_entry_fixture.ps1 -Action setup
#   ./scripts/real_entry_fixture.ps1 -Action verify
#   ./scripts/real_entry_fixture.ps1 -Action teardown

param(
    [Parameter(Mandatory = $true)][ValidateSet("setup", "verify", "teardown", "reset", "position")][string]$Action,
    [string]$Serial = "ecd4369c",
    [string]$Package = "org.skepsun.kototoro",
    [string]$FixtureName = "bench_large_paged",
    [string]$SourceDir = "v1_paged_large",
    [int]$IndexWaitSeconds = 90
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$dbRow = Join-Path $repoRoot "scripts\real_entry_db_row.py"
$work = Join-Path ([System.IO.Path]::GetTempPath()) "real_entry_fixture"
New-Item -ItemType Directory -Force -Path $work | Out-Null

function Adb([string[]]$Arguments) {
    return (cmd /c ("adb -s $Serial " + ($Arguments -join " ") + " 2>&1")) -join "`n"
}

function AdbRoot([string]$Command) {
    return Adb @("shell", "su", "-c", "`"$Command`"")
}

function Pull-Database([string]$suffix = "") {
    # The app keeps recent writes in the WAL, so all three files travel together or the copy is stale.
    # The local copies must be *removed* first, not overwritten: when the device has no -wal (after a
    # push, the file is deliberately absent) a failed copy would leave the previous -wal in place and
    # SQLite would replay it, resurrecting rows that were just deleted.
    Get-ChildItem -Path $work -Filter "kb*" -File -ErrorAction SilentlyContinue | Remove-Item -Force
    # The staging copies on the device have to go too: when the app has no -wal (the state a push
    # leaves behind), `cp` fails and the *previous* /sdcard/Download/refix-wal would be pulled back
    # and replayed by SQLite, which resurrects rows that were just deleted.
    AdbRoot "rm -f /sdcard/Download/refix /sdcard/Download/refix-wal /sdcard/Download/refix-shm" | Out-Null
    foreach ($part in @("", "-wal", "-shm")) {
        Adb @("shell", "su", "-c", "`"cp /data/data/$Package/databases/kototoro-db$part /sdcard/Download/refix$part`"") | Out-Null
        Adb @("shell", "su", "-c", "`"chmod 666 /sdcard/Download/refix$part`"") | Out-Null
        cmd /c "adb -s $Serial pull /sdcard/Download/refix$part `"$work\kb$part`" 2>nul" | Out-Null
    }
    return (Join-Path $work "kb")
}

function Push-Database([string]$localPath) {
    # Push the database *and its WAL*. A modification made against the copy lives in the WAL until a
    # checkpoint, and SQLite's close-time checkpoint is not guaranteed, so pushing only the main file
    # silently drops the change (which is how a "cleared" reading position kept coming back). The
    # -shm is deliberately left behind: SQLite rebuilds it from the WAL.
    #
    # The staging files are removed first and every push is checked: a stale /sdcard/Download/refix-push
    # left over from an earlier run would otherwise be copied back as if it were the new database,
    # silently restoring the state that was just edited.
    AdbRoot "rm -f /sdcard/Download/refix-push /sdcard/Download/refix-push-wal"
    $pushed = Adb @("push", "`"$localPath`"", "/sdcard/Download/refix-push")
    if ($pushed -notmatch "1 file pushed") { throw "push failed for $localPath`n$pushed" }
    AdbRoot "rm -f /data/data/$Package/databases/kototoro-db-wal /data/data/$Package/databases/kototoro-db-shm"
    AdbRoot "cp /sdcard/Download/refix-push /data/data/$Package/databases/kototoro-db"
    if (Test-Path "$localPath-wal") {
        $pushedWal = Adb @("push", "`"$localPath-wal`"", "/sdcard/Download/refix-push-wal")
        if ($pushedWal -notmatch "1 file pushed") { throw "push failed for $localPath-wal`n$pushedWal" }
        AdbRoot "cp /sdcard/Download/refix-push-wal /data/data/$Package/databases/kototoro-db-wal"
    }
    AdbRoot "chown u0_a363:u0_a363 /data/data/$Package/databases/kototoro-db"
    AdbRoot "chmod 660 /data/data/$Package/databases/kototoro-db"
    if (Test-Path "$localPath-wal") {
        AdbRoot "chown u0_a363:u0_a363 /data/data/$Package/databases/kototoro-db-wal"
        AdbRoot "chmod 660 /data/data/$Package/databases/kototoro-db-wal"
    }
}

function Get-FixtureMangaCount([string]$dbPath) {
    $value = python $dbRow count --db $dbPath --dir-name $FixtureName
    return [int]($value | Select-Object -Last 1)
}

switch ($Action) {
    "setup" {
        Write-Output "== installing fixture '$FixtureName' from $SourceDir"
        Adb @("shell", "am", "force-stop", $Package) | Out-Null
        AdbRoot "rm -rf /data/data/$Package/files/manga/$FixtureName"
        AdbRoot "cp -r /data/data/$Package/files/reader-benchmark/$SourceDir /data/data/$Package/files/manga/$FixtureName"
        AdbRoot "chown -R u0_a363:u0_a363 /data/data/$Package/files/manga/$FixtureName"
        $files = AdbRoot "ls /data/data/$Package/files/manga/$FixtureName | wc -l"
        Write-Output ("    fixture files on device: " + ($files -replace "\s+", ""))

        # Page files must follow the app's own local-storage naming convention: the index the app
        # writes for this directory records `entries = "%08d_%04d\d{4}"`, and
        # `LocalMangaParser.getPages` filters the directory's files through that regex
        # (`ContentIndex.getChapterNamesPattern`). Files named `page_000.jpg` therefore resolve to
        # ZERO pages: the reader opens, shows nothing, and never turns a page - which is exactly the
        # trap this rename exists to avoid.
        for ($index = 0; $index -lt 8; $index++) {
            $from = "page_{0:000}.jpg" -f $index
            $to = "00000000_0001{0:0000}.jpg" -f ($index + 1)
            AdbRoot "mv /data/data/$Package/files/manga/$FixtureName/$from /data/data/$Package/files/manga/$FixtureName/$to"
        }

        # The index is built by the app's own worker on the next first start; poll for the row
        # instead of guessing a sleep.
        Adb @("shell", "am", "start", "-n", "$Package/$Package.main.ui.MainActivity") | Out-Null
        $watch = [System.Diagnostics.Stopwatch]::StartNew()
        $found = 0
        while ($watch.Elapsed.TotalSeconds -lt $IndexWaitSeconds) {
            Start-Sleep -Seconds 10
            $dbPath = Pull-Database
            $found = Get-FixtureMangaCount $dbPath
            Write-Output "    indexed=$found after $([int]$watch.Elapsed.TotalSeconds)s"
            if ($found -gt 0) { break }
        }
        if ($found -eq 0) { throw "the app never indexed the fixture directory" }
        Adb @("shell", "am", "force-stop", $Package) | Out-Null
        Start-Sleep -Seconds 2

        # The chapter id must come from the app's own index.json, not from a guess.
        AdbRoot "cp /data/data/$Package/files/manga/$FixtureName/index.json /sdcard/Download/refix-index.json" | Out-Null
        AdbRoot "chmod 666 /sdcard/Download/refix-index.json" | Out-Null
        cmd /c "adb -s $Serial pull /sdcard/Download/refix-index.json `"$work\index.json`" 2>nul" | Out-Null

        $dbPath = Pull-Database
        python $dbRow inject --db $dbPath --index-json (Join-Path $work "index.json") --dir-name $FixtureName
        Push-Database $dbPath

        $verify = Pull-Database
        python $dbRow verify --db $verify --dir-name $FixtureName
    }
    "verify" {
        $dbPath = Pull-Database
        python $dbRow verify --db $dbPath --dir-name $FixtureName
    }
    "position" {
        $dbPath = Pull-Database
        python $dbRow position --db $dbPath --dir-name $FixtureName
    }
    "reset" {
        # Between measured runs the reader would otherwise resume where the last run left off.
        Adb @("shell", "am", "force-stop", $Package) | Out-Null
        Start-Sleep -Seconds 1
        $dbPath = Pull-Database
        python $dbRow reset-position --db $dbPath --dir-name $FixtureName
        Push-Database $dbPath
    }
    "teardown" {
        Write-Output "== removing fixture '$FixtureName'"
        Adb @("shell", "am", "force-stop", $Package) | Out-Null
        Start-Sleep -Seconds 2
        AdbRoot "rm -rf /data/data/$Package/files/manga/$FixtureName"
        $dbPath = Pull-Database
        python $dbRow remove --db $dbPath --dir-name $FixtureName
        Push-Database $dbPath
        $verify = Pull-Database
        python $dbRow verify --db $verify --dir-name $FixtureName
        Write-Output (AdbRoot "ls -A /data/data/$Package/files/manga/")
    }
}
