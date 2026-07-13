package komm.ui.screenshare;

import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.platform.DesktopWindow;
import com.sun.jna.platform.WindowUtils;
import com.sun.jna.platform.win32.*;
import com.sun.jna.platform.win32.WinDef.*;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import javafx.scene.image.Image;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/**
 * Fetches the icon for an open window on Windows or Linux.
 *
 * Windows strategy (tried in order):
 *  1. WM_GETICON via SendMessage          — returns the live window icon, including
 *                                           any custom theme icon the app set at runtime
 *                                           (e.g. Opera GX GX-Store themes, Electron apps)
 *  2. ExtractIconEx from the process EXE  — fallback when the window has no explicit icon
 *
 * HWND lookup uses suffix matching so browser windows whose page title changes between
 * modal-open time and icon-fetch time are still found (e.g. "Speed Dial | Opera GX"
 * stored → "YouTube | Opera GX" current: suffix "opera gx" still matches).
 *
 * We intentionally skip WindowUtils.getWindowIcon() because it returns
 * BufferedImage.TYPE_INT_RGB (no alpha channel) for Chromium-based apps
 * (Opera, Chrome, Discord, Electron…), which causes a solid black square
 * background instead of transparency.
 */
public class WindowIconFetcher {

    // ── Win32 constants ────────────────────────────────────────────────────────
    private static final int DI_NORMAL  = 0x0003;

    // ── Extended User32 interface (only what we actually need) ─────────────────
    public interface ExtUser32 extends StdCallLibrary {
        ExtUser32 INSTANCE = Native.load("user32", ExtUser32.class, W32APIOptions.DEFAULT_OPTIONS);

        boolean DrawIconEx(HDC hdc, int xLeft, int yTop, HICON hIcon,
                           int cxWidth, int cyWidth,
                           UINT istepIfAniCur, HBRUSH hbrFlickerFreeDraw, UINT diFlags);

        boolean GetIconInfo(HICON hIcon, WinGDI.ICONINFO piconinfo);
        HDC  GetDC(HWND hWnd);
        int  ReleaseDC(HWND hWnd, HDC hDC);
    }

    // ── Extended Kernel32 interface ────────────────────────────────────────────
    // QueryFullProcessImageNameW needs only PROCESS_QUERY_LIMITED_INFORMATION (0x1000),
    // unlike GetModuleFileNameExW which also needs PROCESS_VM_READ — making it
    // accessible on sandboxed Chromium renderer processes.
    public interface ExtKernel32 extends StdCallLibrary {
        ExtKernel32 INSTANCE = Native.load("kernel32", ExtKernel32.class, W32APIOptions.DEFAULT_OPTIONS);
        boolean QueryFullProcessImageNameW(WinNT.HANDLE hProcess, int dwFlags,
                                           char[] lpExeName, IntByReference lpdwSize);
    }

    // ── Entry point ────────────────────────────────────────────────────────────

    public static void fetchWindowIcon(SourceCard card) {
        try {
            Image icon = null;

            if (Platform.isWindows()) {
                icon = fetchIconWindows(card.getSourceName());
            } else if (Platform.isLinux()) {
                icon = fetchIconLinux(card.getSourceName());
            }

            if (icon != null) {
                final Image finalIcon = icon;
                javafx.application.Platform.runLater(() -> card.setIcon(finalIcon));
            }
        } catch (Throwable t) {
            // non-fatal
        }
    }

    // ── Windows ────────────────────────────────────────────────────────────────

    private static Image fetchIconWindows(String windowTitle) {
        try {
            List<DesktopWindow> windows = WindowUtils.getAllWindows(false);
            String titleLower = windowTitle.toLowerCase(Locale.ROOT);

            // Extract the stable app-name suffix from titles like "Page Title | Opera GX"
            // or "Page Title - Discord". The page portion can change while the modal is
            // open (browser navigation), but the suffix stays the same.
            String appSuffix = extractAppSuffix(titleLower);

            HWND bestHwnd  = null;
            String bestExe = null;
            int bestScore  = -1;

            for (DesktopWindow dw : windows) {
                String dwTitle = dw.getTitle().toLowerCase(Locale.ROOT);
                if (dwTitle.isBlank()) continue;

                int score;
                if      (dwTitle.equals(titleLower))        score = 4;
                else if (dwTitle.contains(titleLower))      score = 3;
                else if (titleLower.contains(dwTitle))      score = 2;
                else if (appSuffix != null && dwTitle.contains(appSuffix)) score = 1;
                else continue;

                if (score > bestScore) {
                    bestScore = score;
                    bestHwnd  = dw.getHWND();
                    bestExe   = getExePathForHwnd(dw.getHWND());
                }
            }

            if (bestHwnd == null) return null;

            // Strategy 1: WM_GETICON — returns the live icon the app registered at runtime,
            // which includes any custom theme icon (e.g. Opera GX GX-Store icons).
            Image live = fetchIconViaSendMessage(bestHwnd);
            if (live != null) return live;

            // Strategy 2: ExtractIconEx from EXE — fallback when the window has no
            // explicit icon set (returns the icon embedded in the executable).
            if (bestExe != null) {
                Image exe = fetchIconFromExe(bestExe);
                if (exe != null) return exe;
            }

        } catch (Throwable t) {
            // non-fatal
        }
        return null;
    }

    /**
     * Extracts the app-name suffix from a window title so that icon lookup stays
     * robust when the page portion changes (common in browsers).
     *
     * "Speed Dial | Opera GX"   →  "opera gx"
     * "YouTube - Google Chrome" →  "google chrome"
     * "Discord"                 →  null  (no separator → no suffix to extract)
     */
    private static String extractAppSuffix(String titleLower) {
        for (String sep : new String[]{" | ", " - ", " – ", " — "}) {
            int idx = titleLower.lastIndexOf(sep);
            if (idx > 0) {
                String candidate = titleLower.substring(idx + sep.length()).trim();
                if (!candidate.isBlank()) return candidate;
            }
        }
        return null;
    }

    // ── ExtractIconEx ──────────────────────────────────────────────────────────

    private static Image fetchIconFromExe(String exePath) {
        try {
            // Try large icon first (32×32), then small (16×16)
            HICON[] large = new HICON[1];
            HICON[] small = new HICON[1];
            int count = Shell32.INSTANCE.ExtractIconEx(exePath, 0, large, small, 1);
            if (count <= 0) return null;
            try {
                HICON best = (large[0] != null) ? large[0] : small[0];
                if (best == null) return null;
                return hIconToFxImage(best);
            } finally {
                if (large[0] != null) User32.INSTANCE.DestroyIcon(large[0]);
                if (small[0] != null) User32.INSTANCE.DestroyIcon(small[0]);
            }
        } catch (Throwable t) {
            // non-fatal
        }
        return null;
    }

    // ── WM_GETICON fallback ────────────────────────────────────────────────────

    private static Image fetchIconViaSendMessage(HWND hwnd) {
        // ICON_BIG=1, ICON_SMALL2=2, ICON_SMALL=0
        for (int type : new int[]{1, 2, 0}) {
            try {
                LRESULT result = User32.INSTANCE.SendMessage(
                        hwnd, WinUser.WM_GETICON, new WPARAM(type), new LPARAM(0));
                if (result == null || result.longValue() == 0) continue;
                HICON hIcon = new HICON(Pointer.createConstant(result.longValue()));
                Image img = hIconToFxImage(hIcon);
                if (img != null) return img;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    // ── HICON → JavaFX Image ───────────────────────────────────────────────────

    /**
     * Renders an HICON into a 32-bpp DIB section and converts it to a JavaFX Image.
     *
     * Key decisions:
     * - We always use a top-down DIB (biHeight negative) so row 0 = top.
     * - We clear the buffer to full transparency before DrawIconEx so any pixels
     *   that DrawIconEx leaves untouched are correctly transparent.
     * - For icons that already carry per-pixel alpha (32 bpp colour bitmap) we
     *   keep the alpha as-is.
     * - For old-style 1-bpp mask icons (hasAlpha=false) we derive transparency
     *   from the XOR/AND mask pair that Windows bakes into the DrawIconEx output:
     *   after clearing to 0x00000000 and calling DrawIconEx with DI_NORMAL,
     *   transparent areas are left as A=0 by the OS, so we don't need to do any
     *   extra mask work — we just read A directly.
     */
    private static Image hIconToFxImage(HICON hIcon) {
        if (hIcon == null) return null;

        GDI32     gdi    = GDI32.INSTANCE;
        ExtUser32 user32 = ExtUser32.INSTANCE;

        WinGDI.ICONINFO iconInfo = new WinGDI.ICONINFO();
        if (!user32.GetIconInfo(hIcon, iconInfo)) return null;

        int  width    = 32;
        int  height   = 32;

        try {
            // Determine icon dimensions from the colour bitmap
            if (iconInfo.hbmColor != null) {
                WinGDI.BITMAP bmp = new WinGDI.BITMAP();
                gdi.GetObject(iconInfo.hbmColor, bmp.size(), bmp.getPointer());
                bmp.read();
                width  = bmp.bmWidth.intValue();
                height = Math.abs(bmp.bmHeight.intValue());
            }

            HDC screenDC = user32.GetDC(null);
            HDC memDC    = gdi.CreateCompatibleDC(screenDC);

            // Create a 32-bpp top-down DIB section
            WinGDI.BITMAPINFO bmi = new WinGDI.BITMAPINFO();
            bmi.bmiHeader.biSize        = bmi.bmiHeader.size();
            bmi.bmiHeader.biWidth       = width;
            bmi.bmiHeader.biHeight      = -height;          // negative = top-down
            bmi.bmiHeader.biPlanes      = 1;
            bmi.bmiHeader.biBitCount    = 32;
            bmi.bmiHeader.biCompression = WinGDI.BI_RGB;
            bmi.write();

            PointerByReference ppvBits = new PointerByReference();
            HBITMAP dib = gdi.CreateDIBSection(
                    screenDC, bmi, WinGDI.DIB_RGB_COLORS, ppvBits, null, 0);

            if (dib == null) {
                gdi.DeleteDC(memDC);
                user32.ReleaseDC(null, screenDC);
                return null;
            }

            WinNT.HANDLE old = gdi.SelectObject(memDC, dib);

            // Zero the buffer so untouched pixels stay fully transparent
            Pointer pixels = ppvBits.getValue();
            if (pixels != null) {
                byte[] zeros = new byte[width * height * 4];
                pixels.write(0, zeros, 0, zeros.length);
            }

            // Draw icon — DI_NORMAL handles both old-style mask icons and
            // modern 32-bpp icons with per-pixel alpha correctly.
            boolean ok = user32.DrawIconEx(
                    memDC, 0, 0, hIcon, width, height,
                    new UINT(0), null, new UINT(DI_NORMAL));

            gdi.SelectObject(memDC, old);

            Image result = null;

            if (ok && pixels != null) {
                int   stride     = width * 4;
                int[] argbBuffer = new int[width * height];

                for (int y = 0; y < height; y++) {
                    long row = (long) y * stride;
                    for (int x = 0; x < width; x++) {
                        long off = row + x * 4L;
                        int b = pixels.getByte(off)     & 0xFF;
                        int g = pixels.getByte(off + 1) & 0xFF;
                        int r = pixels.getByte(off + 2) & 0xFF;
                        int a = pixels.getByte(off + 3) & 0xFF;
                        argbBuffer[y * width + x] = (a << 24) | (r << 16) | (g << 8) | b;
                    }
                }

                BufferedImage bi = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                bi.setRGB(0, 0, width, height, argbBuffer, 0, width);
                result = bufferedImageToFxImage(bi);
            }

            gdi.DeleteObject(dib);
            gdi.DeleteDC(memDC);
            user32.ReleaseDC(null, screenDC);
            return result;

        } catch (Throwable t) {
            // non-fatal
            return null;
        } finally {
            if (iconInfo.hbmColor != null) gdi.DeleteObject(iconInfo.hbmColor);
            if (iconInfo.hbmMask  != null) gdi.DeleteObject(iconInfo.hbmMask);
        }
    }

    // ── EXE path from HWND ─────────────────────────────────────────────────────

    private static String getExePathForHwnd(HWND hwnd) {
        try {
            IntByReference pid = new IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(hwnd, pid);
            if (pid.getValue() == 0) return null;

            // PROCESS_QUERY_LIMITED_INFORMATION (0x1000) is sufficient for
            // QueryFullProcessImageNameW and is granted even on sandboxed/protected
            // processes (Chromium renderers, UWP, etc.) where the broader
            // PROCESS_QUERY_INFORMATION | PROCESS_VM_READ (0x0410) is denied.
            WinNT.HANDLE hProcess = Kernel32.INSTANCE.OpenProcess(0x1000, false, pid.getValue());
            if (hProcess == null) {
                hProcess = Kernel32.INSTANCE.OpenProcess(0x0410, false, pid.getValue());
            }
            if (hProcess == null) return null;

            try {
                char[] buf = new char[1024];
                IntByReference size = new IntByReference(buf.length);
                if (ExtKernel32.INSTANCE.QueryFullProcessImageNameW(hProcess, 0, buf, size)
                        && size.getValue() > 0) {
                    String path = new String(buf, 0, size.getValue());
                    if (!path.isBlank()) return path;
                }
                // Fallback for older Windows versions where QueryFullProcessImageNameW
                // might not behave correctly — requires the broader access rights.
                Psapi.INSTANCE.GetModuleFileNameExW(hProcess, null, buf, buf.length);
                String path = Native.toString(buf);
                return path.isBlank() ? null : path;
            } finally {
                Kernel32.INSTANCE.CloseHandle(hProcess);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    // ── Linux ──────────────────────────────────────────────────────────────────

    private static Image fetchIconLinux(String windowTitle) {
        try {
            String iconName = findDesktopIconName(windowTitle);
            List<Path> dirs = buildXdgIconDirs();
            if (iconName != null) {
                Image img = searchXdgIcon(iconName, dirs);
                if (img != null) return img;
            }
            // Fallback: try first word of the title as icon name
            String fragment = windowTitle.split("[^a-zA-Z0-9]")[0].toLowerCase(Locale.ROOT);
            return searchXdgIcon(fragment, dirs);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String findDesktopIconName(String windowTitle) {
        String titleLower = windowTitle.toLowerCase(Locale.ROOT);
        List<Path> desktopDirs = List.of(
                Path.of("/usr/share/applications"),
                Path.of(System.getProperty("user.home"), ".local/share/applications"),
                Path.of("/var/lib/flatpak/exports/share/applications"),
                Path.of(System.getProperty("user.home"),
                        ".local/share/flatpak/exports/share/applications")
        );
        for (Path dir : desktopDirs) {
            if (!Files.isDirectory(dir)) continue;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.desktop")) {
                for (Path f : ds) {
                    String icon = parseDesktopIcon(f, titleLower);
                    if (icon != null) return icon;
                }
            } catch (IOException ignored) {}
        }
        return null;
    }

    private static String parseDesktopIcon(Path file, String titleLower) {
        try {
            String name = null, exec = null, icon = null;
            for (String line : Files.readAllLines(file)) {
                if (line.startsWith("Name="))  name = line.substring(5).toLowerCase(Locale.ROOT);
                if (line.startsWith("Exec="))  exec = line.substring(5).toLowerCase(Locale.ROOT);
                if (line.startsWith("Icon="))  icon = line.substring(5).trim();
            }
            String bin = exec != null
                    ? Path.of(exec.split(" ")[0]).getFileName().toString() : "";
            if ((name != null && titleLower.contains(name))
                    || (!bin.isEmpty() && titleLower.contains(bin))) {
                return icon;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static List<Path> buildXdgIconDirs() {
        String home     = System.getProperty("user.home");
        String dataHome = System.getenv().getOrDefault("XDG_DATA_HOME", home + "/.local/share");
        String dataDirs = System.getenv().getOrDefault("XDG_DATA_DIRS", "/usr/local/share:/usr/share");

        List<Path> dirs = new ArrayList<>();
        dirs.add(Path.of(dataHome, "icons"));
        for (String d : dataDirs.split(":")) dirs.add(Path.of(d, "icons"));
        dirs.add(Path.of("/usr/share/pixmaps"));
        return dirs;
    }

    private static Image searchXdgIcon(String iconName, List<Path> searchDirs) {
        if (iconName == null || iconName.isBlank()) return null;
        if (iconName.startsWith("/")) return loadImageFile(Path.of(iconName));

        String[] sizes      = {"48x48", "32x32", "64x64", "128x128", "256x256", "scalable"};
        String[] categories = {"apps", "applications"};
        String[] themes     = {"hicolor", "Adwaita", "breeze", "Papirus"};
        String[] exts       = {".png", ".svg", ".xpm"};

        for (Path base : searchDirs) {
            for (String theme : themes)
                for (String size : sizes)
                    for (String cat : categories)
                        for (String ext : exts) {
                            Image img = loadImageFile(
                                    base.resolve(theme).resolve(size).resolve(cat)
                                            .resolve(iconName + ext));
                            if (img != null) return img;
                        }
            for (String ext : exts) {
                Image img = loadImageFile(base.resolve(iconName + ext));
                if (img != null) return img;
            }
        }
        return null;
    }

    private static Image loadImageFile(Path path) {
        if (!Files.exists(path)) return null;
        try (InputStream is = Files.newInputStream(path)) {
            String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.endsWith(".svg") || name.endsWith(".xpm")) return null;
            return new Image(is, 48, 48, true, true);
        } catch (Throwable ignored) {}
        return null;
    }

    // ── Shared helpers ─────────────────────────────────────────────────────────

    private static Image bufferedImageToFxImage(BufferedImage bi) {
        try {
            if (bi.getType() != BufferedImage.TYPE_INT_ARGB) {
                BufferedImage argb = new BufferedImage(
                        bi.getWidth(), bi.getHeight(), BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = argb.createGraphics();
                g.setComposite(AlphaComposite.Src);
                g.drawImage(bi, 0, 0, null);
                g.dispose();
                bi = argb;
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(bi, "png", baos);
            return new Image(new ByteArrayInputStream(baos.toByteArray()), 0, 0, true, true);
        } catch (Exception e) {
            return null;
        }
    }
}