param(
  [Parameter(Mandatory = $true)][string]$File,   # 확인할 .hwp
  [string]$OutDir = "",                          # 캡처 PNG를 저장할 폴더(비우면 열림 여부만 확인)
  [int]$Screens = 3,                             # 캡처할 화면 수(한 화면 ≈ 1쪽 남짓)
  [int]$Wait = 15
)
# 한컴 뷰어로 파일을 열어 (1) 정상으로 열리는지 OK/FAIL을 출력하고 (2) 뷰어 창만 화면 단위로 캡처한다.
# 데스크톱 전체는 찍지 않는다. Claude Code에서는 샌드박스 밖에서 실행해야 뷰어 창이 보인다.
Add-Type -AssemblyName System.Drawing
Add-Type @"
using System; using System.Text; using System.Runtime.InteropServices;
public static class KV {
  public delegate bool P(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] public static extern bool EnumWindows(P cb, IntPtr l);
  [DllImport("user32.dll")] public static extern bool EnumChildWindows(IntPtr p, P cb, IntPtr l);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [DllImport("user32.dll")] public static extern int GetClassName(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr hdc, uint f);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out R r);
  [DllImport("user32.dll")] public static extern bool MoveWindow(IntPtr h, int x, int y, int w, int hh, bool rp);
  [DllImport("user32.dll")] public static extern IntPtr SendMessage(IntPtr h, uint m, IntPtr w, IntPtr l);
  [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr h, uint m, IntPtr w, IntPtr l);
  public struct R { public int L, T, Rt, B; }
  // pid == 0 이면 모든 프로세스에서 찾는다(뷰어가 이미 떠 있으면 새 프로세스는 기존 뷰어에 파일을 넘기고 끝나기 때문)
  public static IntPtr Find(uint pid, string cls, string title) {
    IntPtr found = IntPtr.Zero;
    EnumWindows((h, l) => { uint p; GetWindowThreadProcessId(h, out p); var c = new StringBuilder(256); var t = new StringBuilder(512);
      GetClassName(h, c, 256); GetWindowText(h, t, 512);
      if ((pid == 0 || p == pid) && IsWindowVisible(h) && c.ToString().StartsWith(cls) && t.ToString().Contains(title)) { found = h; return false; } return true; }, IntPtr.Zero);
    return found;
  }
  public static uint Pid(IntPtr h) { uint p; GetWindowThreadProcessId(h, out p); return p; }
  // 숨겨진 창까지 포함해, 제목에 title이 들어간 창의 프로세스 ID들
  public static System.Collections.Generic.List<uint> Owners(string cls, string title) {
    var o = new System.Collections.Generic.List<uint>();
    EnumWindows((h, l) => { uint p; GetWindowThreadProcessId(h, out p); var c = new StringBuilder(256); var t = new StringBuilder(512);
      GetClassName(h, c, 256); GetWindowText(h, t, 512);
      if (c.ToString().StartsWith(cls) && t.ToString().Contains(title) && !o.Contains(p)) o.Add(p); return true; }, IntPtr.Zero);
    return o;
  }
  public static void CloseScriptErrors(uint pid) {
    EnumWindows((h, l) => { uint p; GetWindowThreadProcessId(h, out p); var t = new StringBuilder(256); GetWindowText(h, t, 256);
      if (p == pid && IsWindowVisible(h) && t.ToString() == "스크립트 오류") PostMessage(h, 0x0010, IntPtr.Zero, IntPtr.Zero); return true; }, IntPtr.Zero);
  }
  public static void PageDown(IntPtr top) {
    EnumChildWindows(top, (h, l) => { SendMessage(h, 0x0115, (IntPtr)3, IntPtr.Zero); return true; }, IntPtr.Zero);
  }
}
"@
$viewer = "C:\Program Files (x86)\HNC\Office NEO\HOfficeViewer96\Bin\HwpViewer.exe"
if (-not (Test-Path $viewer)) { "SKIP 한컴 뷰어 없음: $viewer"; exit 2 }
# 뷰어는 창 하나에 문서를 쌓으므로 동시에 여러 개를 열면 창 제목이 엇갈린다 → 한 번에 하나씩만 실행
$mutex = New-Object System.Threading.Mutex($false, "Global\KoscomHwpPreview")
if (-not $mutex.WaitOne(300000)) { "FAIL`t(다른 미리보기가 끝나지 않음)"; exit 1 }
$path = (Resolve-Path $File).Path
$name = [IO.Path]::GetFileName($path)
# 같은 파일을 보여 주는 뷰어 창이 남아 있으면 먼저 닫는다(남은 창을 찾으면 예전 내용을 캡처하게 됨)
function KillWindow($w) {
  if ($w -eq [IntPtr]::Zero) { return }
  $owner = [KV]::Pid($w)
  [KV]::PostMessage($w, 0x0010, [IntPtr]::Zero, [IntPtr]::Zero) | Out-Null
  for ($t = 0; $t -lt 6 -and [KV]::IsWindowVisible($w); $t++) { Start-Sleep -Milliseconds 500 }
  if ([KV]::IsWindowVisible($w)) { Stop-Process -Id $owner -Force -ErrorAction SilentlyContinue; Start-Sleep -Milliseconds 800 }
}
# 뷰어는 창을 닫아도 문서를 메모리에 남겨 두고, 같은 파일을 다시 열면 예전 내용을 보여 준다 → 그 파일을 연 뷰어 프로세스를 끝낸다
function KillOwners() { foreach ($p in [KV]::Owners("HwpViewerApp", $path)) { Stop-Process -Id $p -Force -ErrorAction SilentlyContinue }; Start-Sleep -Milliseconds 800 }
KillOwners
$proc = Start-Process -FilePath $viewer -ArgumentList "`"$path`"" -PassThru
$h = [IntPtr]::Zero; $dialog = [IntPtr]::Zero
for ($i = 0; $i -lt $Wait -and $h -eq [IntPtr]::Zero; $i++) {
  Start-Sleep -Seconds 1
  $dialog = [KV]::Find([uint32]$proc.Id, "HNC_DIALOG", "")
  if ($dialog -ne [IntPtr]::Zero) { break }
  $h = [KV]::Find(0, "HwpViewerApp", $path)
  if ($h -eq [IntPtr]::Zero) { $h = [KV]::Find(0, "HwpViewerApp", $name) }
}
function CloseViewer() {
  KillWindow $h  # 이 파일 창만 닫고, 닫히지 않으면 그 창의 뷰어를 끝낸다
  KillOwners     # 창을 숨기고 문서를 붙들고 있는 뷰어도 끝낸다(다음 미리보기가 예전 내용을 보지 않게)
  if (-not $proc.HasExited -and [KV]::Find([uint32]$proc.Id, "HwpViewerApp", "") -eq [IntPtr]::Zero) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
}
if ($h -eq [IntPtr]::Zero) {
  CloseViewer; $mutex.ReleaseMutex()
  if ($dialog -ne [IntPtr]::Zero) { "FAIL`t$name`t(한글 오류 대화상자 — 파일이 손상됨)" } else { "FAIL`t$name`t(시간 초과 — -Wait 값을 늘려 다시 시도)" }
  exit 1
}
"OK`t$name"
if ($OutDir) {
  New-Item -ItemType Directory -Force $OutDir | Out-Null
  Start-Sleep -Seconds 3
  [KV]::CloseScriptErrors([uint32]$proc.Id)
  [KV]::MoveWindow($h, 0, 0, 1300, 1400, $true) | Out-Null
  Start-Sleep -Seconds 3
  for ($k = 1; $k -le $Screens; $k++) {
    $r = New-Object KV+R; [KV]::GetWindowRect($h, [ref]$r) | Out-Null
    $bmp = New-Object System.Drawing.Bitmap ($r.Rt - $r.L), ($r.B - $r.T)
    $g = [System.Drawing.Graphics]::FromImage($bmp); $hdc = $g.GetHdc()
    [KV]::PrintWindow($h, $hdc, 2) | Out-Null
    $g.ReleaseHdc($hdc); $g.Dispose()
    # 뷰어 테두리·도구 모음을 빼고 쪽 영역만 남긴다(창 크기를 넘지 않게 자름)
    $x0 = [Math]::Min(300, $bmp.Width - 1); $y0 = [Math]::Min(90, $bmp.Height - 1)
    $cw = [Math]::Min(715, $bmp.Width - $x0); $ch = [Math]::Min(1270, $bmp.Height - $y0)
    $crop = New-Object System.Drawing.Bitmap $cw, $ch
    $cg = [System.Drawing.Graphics]::FromImage($crop)
    $cg.DrawImage($bmp, (New-Object System.Drawing.Rectangle 0, 0, $cw, $ch), (New-Object System.Drawing.Rectangle $x0, $y0, $cw, $ch), [System.Drawing.GraphicsUnit]::Pixel)
    $cg.Dispose()
    $png = Join-Path $OutDir ("screen{0:D2}.png" -f $k)
    try {
      if (Test-Path $png) { [IO.File]::Delete($png) }  # 예전 캡처가 남아 있으면 그걸 보고 오판하게 되므로 먼저 지운다
      $crop.Save($png, [System.Drawing.Imaging.ImageFormat]::Png)
      "SAVED`t$png"
    } catch { "ERROR`t캡처 저장 실패: $png — $($_.Exception.Message)" }
    $crop.Dispose(); $bmp.Dispose()
    [KV]::PageDown($h); Start-Sleep -Seconds 2
  }
}
CloseViewer
$mutex.ReleaseMutex()
exit 0
