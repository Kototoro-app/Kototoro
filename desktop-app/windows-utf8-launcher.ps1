# Marks a bundled executable's application manifest with <activeCodePage>UTF-8</activeCodePage>.
# The jpackage launcher hands its own location to the JVM through ANSI APIs, so an image under a path the system code
# page cannot represent (a CJK user profile on an English Windows, Thai on a Chinese one, ...) fails with
# "could not find java.dll" (as do the bundled java/javaw commands); the ncnn tools likewise crash on such image paths. With the UTF-8 active code page
# (Windows 10 1903+) every path survives. Uses only kernel32 resource APIs, so it needs no Windows SDK; running it
# again leaves an already patched executable unchanged.
# Not "-ExePath": powershell.exe -File swallows script arguments that abbreviate its own -ExecutionPolicy.
param([Parameter(Mandatory = $true)][string] $LauncherPath)

$ErrorActionPreference = 'Stop'
Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Runtime.InteropServices;

public static class KototoroLauncherManifest {
    const uint LoadAsDataFile = 0x2, LoadAsImageResource = 0x20;
    static readonly IntPtr ManifestType = (IntPtr)24, ManifestId = (IntPtr)1;

    delegate bool EnumLanguages(IntPtr module, IntPtr type, IntPtr name, ushort language, IntPtr parameter);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    static extern IntPtr LoadLibraryExW(string path, IntPtr file, uint flags);
    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool FreeLibrary(IntPtr module);
    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool EnumResourceLanguagesW(IntPtr module, IntPtr type, IntPtr name, EnumLanguages callback, IntPtr parameter);
    [DllImport("kernel32.dll", SetLastError = true)]
    static extern IntPtr FindResourceExW(IntPtr module, IntPtr type, IntPtr name, ushort language);
    [DllImport("kernel32.dll", SetLastError = true)]
    static extern IntPtr LoadResource(IntPtr module, IntPtr info);
    [DllImport("kernel32.dll", SetLastError = true)]
    static extern IntPtr LockResource(IntPtr data);
    [DllImport("kernel32.dll", SetLastError = true)]
    static extern uint SizeofResource(IntPtr module, IntPtr info);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    static extern IntPtr BeginUpdateResourceW(string path, bool deleteExisting);
    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool UpdateResourceW(IntPtr update, IntPtr type, IntPtr name, ushort language, byte[] data, uint size);
    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool EndUpdateResourceW(IntPtr update, bool discard);

    public static ushort Language;

    public static byte[] Read(string path) {
        IntPtr module = LoadLibraryExW(path, IntPtr.Zero, LoadAsDataFile | LoadAsImageResource);
        if (module == IntPtr.Zero) throw new Win32Exception();
        try {
            var languages = new List<ushort>();
            EnumResourceLanguagesW(module, ManifestType, ManifestId, (m, t, n, language, p) => { languages.Add(language); return true; }, IntPtr.Zero);
            if (languages.Count != 1) throw new InvalidOperationException("Expected one launcher manifest, found " + languages.Count);
            Language = languages[0];
            IntPtr info = FindResourceExW(module, ManifestType, ManifestId, Language);
            if (info == IntPtr.Zero) throw new Win32Exception();
            uint size = SizeofResource(module, info);
            IntPtr pointer = LockResource(LoadResource(module, info));
            byte[] bytes = new byte[size];
            Marshal.Copy(pointer, bytes, 0, (int)size);
            return bytes;
        } finally { FreeLibrary(module); }
    }

    public static void Write(string path, byte[] manifest) {
        IntPtr update = BeginUpdateResourceW(path, false);
        if (update == IntPtr.Zero) throw new Win32Exception();
        if (!UpdateResourceW(update, ManifestType, ManifestId, Language, manifest, (uint)manifest.Length)) {
            int error = Marshal.GetLastWin32Error();
            EndUpdateResourceW(update, true);
            throw new Win32Exception(error);
        }
        if (!EndUpdateResourceW(update, false)) throw new Win32Exception();
    }
}
'@

$settingsNamespace = 'http://schemas.microsoft.com/SMI/2019/WindowsSettings'
$asmV3 = 'urn:schemas-microsoft-com:asm.v3'

function Read-Manifest([string] $Path) {
    $document = New-Object System.Xml.XmlDocument
    $document.PreserveWhitespace = $true
    $document.LoadXml([Text.Encoding]::UTF8.GetString([KototoroLauncherManifest]::Read($Path)).TrimStart([char] 0xFEFF))
    $document
}

function Get-ActiveCodePage([System.Xml.XmlDocument] $Document) {
    $namespaces = New-Object System.Xml.XmlNamespaceManager $Document.NameTable
    $namespaces.AddNamespace('cp', $settingsNamespace)
    $Document.SelectSingleNode('//cp:activeCodePage', $namespaces)
}

$exe = (Resolve-Path -LiteralPath $LauncherPath).Path
$document = Read-Manifest $exe
$existing = Get-ActiveCodePage $document
if ($existing -ne $null) {
    if ($existing.InnerText -ne 'UTF-8') { throw "Launcher already declares activeCodePage '$($existing.InnerText)'." }
    Write-Output "Launcher already uses the UTF-8 code page: $exe"
    return
}

$namespaces = New-Object System.Xml.XmlNamespaceManager $document.NameTable
$namespaces.AddNamespace('asmv3', $asmV3)
$settings = $document.SelectSingleNode('/*/asmv3:application/asmv3:windowsSettings', $namespaces)
if ($settings -eq $null) {
    $application = $document.SelectSingleNode('/*/asmv3:application', $namespaces)
    if ($application -eq $null) {
        $application = $document.CreateElement('asmv3', 'application', $asmV3)
        [void] $document.DocumentElement.AppendChild($application)
    }
    $settings = $document.CreateElement('asmv3', 'windowsSettings', $asmV3)
    [void] $application.AppendChild($settings)
}
$codePage = $document.CreateElement('activeCodePage', $settingsNamespace)
$codePage.InnerText = 'UTF-8'
[void] $settings.AppendChild($codePage)

$output = New-Object System.IO.MemoryStream
$writerSettings = New-Object System.Xml.XmlWriterSettings
$writerSettings.Encoding = New-Object System.Text.UTF8Encoding $false
$writerSettings.OmitXmlDeclaration = $false
$writer = [System.Xml.XmlWriter]::Create($output, $writerSettings)
$document.Save($writer)
$writer.Dispose()
# jpackage leaves the launcher read-only; resource updates need write access, so restore the attribute afterwards.
$item = Get-Item -LiteralPath $exe
$readOnly = $item.IsReadOnly
$item.IsReadOnly = $false
try { [KototoroLauncherManifest]::Write($exe, $output.ToArray()) } finally { (Get-Item -LiteralPath $exe).IsReadOnly = $readOnly }

$check = Get-ActiveCodePage (Read-Manifest $exe)
if ($check -eq $null -or $check.InnerText -ne 'UTF-8') { throw "Launcher manifest update did not persist: $exe" }
Write-Output "Launcher now uses the UTF-8 code page: $exe"
