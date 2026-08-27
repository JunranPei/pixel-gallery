param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,
    [string]$OutputDirectory = (Join-Path $PSScriptRoot "..\build\adb-logs")
)

$ErrorActionPreference = "Stop"
$resolvedOutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
[IO.Directory]::CreateDirectory($resolvedOutputDirectory) | Out-Null
$startedAt = Get-Date -Format "yyyyMMdd-HHmmss"
$outputPath = Join-Path $resolvedOutputDirectory "viewer-$Serial-$startedAt.log"
$errorPath = Join-Path $resolvedOutputDirectory "viewer-$Serial-$startedAt.err.log"

while ($true) {
    try {
        & adb -s $Serial wait-for-device
        $writer = [IO.StreamWriter]::new($outputPath, $true, [Text.UTF8Encoding]::new($false))
        $errorWriter = [IO.StreamWriter]::new($errorPath, $true, [Text.UTF8Encoding]::new($false))
        try {
            $writer.WriteLine("--- adb connected $(Get-Date -Format o) serial=$Serial ---")
            $writer.Flush()

            $startInfo = [Diagnostics.ProcessStartInfo]::new()
            $startInfo.FileName = "adb"
            $startInfo.Arguments = "-s `"$Serial`" logcat -b main -b crash -v epoch -T 1 ViewerLoadMetrics:I IndexedJpeg:V IndexedJpegStore:V AndroidRuntime:E libc:F *:S"
            $startInfo.UseShellExecute = $false
            $startInfo.CreateNoWindow = $true
            $startInfo.RedirectStandardOutput = $true
            $startInfo.RedirectStandardError = $true

            $logcatProcess = [Diagnostics.Process]::new()
            $logcatProcess.StartInfo = $startInfo
            $logcatProcess.Start() | Out-Null
            while (!$logcatProcess.HasExited) {
                while (!$logcatProcess.StandardOutput.EndOfStream) {
                    $writer.WriteLine($logcatProcess.StandardOutput.ReadLine())
                    $writer.Flush()
                }
                while (!$logcatProcess.StandardError.EndOfStream) {
                    $errorWriter.WriteLine($logcatProcess.StandardError.ReadLine())
                    $errorWriter.Flush()
                }
            }
            $logcatProcess.WaitForExit()
            $writer.WriteLine("--- adb disconnected $(Get-Date -Format o) exitCode=$($logcatProcess.ExitCode) ---")
            $writer.Flush()
        } finally {
            $writer.Dispose()
            $errorWriter.Dispose()
        }
    } catch {
        [IO.File]::AppendAllText(
            $errorPath,
            "$(Get-Date -Format o) $($_.Exception.Message)$([Environment]::NewLine)",
            [Text.UTF8Encoding]::new($false)
        )
    }
    Start-Sleep -Seconds 1
}
