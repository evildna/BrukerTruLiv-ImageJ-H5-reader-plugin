/*
 * Lux_H5_Reader - ImageJ/Fiji plugin to open Bruker TruLive3D (Luxendo) .lux.h5 raw stacks
 * with proper spatial calibration and acquisition metadata.
 *
 * File format (as written by Luxendo/Bruker TruLive3D Embedded software):
 *   - HDF5 file, dataset "Data" of shape (Z, Y, X), uint16,
 *     with float attribute "element_size_um" = [z, y, x] voxel size in micrometers.
 *   - Scalar string dataset "metadata" containing the full acquisition metadata as JSON
 *     (identical to the .json sidecar file written next to the .lux.h5).
 *   - File naming: Cam_<camera>_<timepoint>.lux.h5  (e.g. Cam_long_00000.lux.h5)
 *     inside a folder like raw/stack_0_channel_0_obj_bottom/.
 *
 * Open modes:
 *   1. Single file                                -> XYZ stack
 *   2. All time points (this camera)              -> XYZT hyperstack
 *   3. All time points, all cameras as channels   -> XYCZT composite hyperstack
 *
 * The full JSON metadata is stored in the image "Info" property
 * (Image > Show Info..., or getMetadata("Info") in a macro), preceded by a
 * human-readable acquisition summary. Voxel size is applied to the Calibration.
 *
 * Requires the CISD jhdf5 library (sis-jhdf5), which ships with Fiji
 * (used by BigDataViewer). For plain ImageJ, put jhdf5-19.04.1.jar in the
 * plugins or jars folder.
 */

import ij.CompositeImage;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.Prefs;
import ij.gui.GenericDialog;
import ij.io.OpenDialog;
import ij.measure.Calibration;
import ij.plugin.PlugIn;
import ij.process.LUT;
import ij.process.ShortProcessor;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ch.systemsx.cisd.base.mdarray.MDShortArray;
import ch.systemsx.cisd.hdf5.HDF5DataSetInformation;
import ch.systemsx.cisd.hdf5.HDF5Factory;
import ch.systemsx.cisd.hdf5.IHDF5Reader;

public class Lux_H5_Reader implements PlugIn {

    public static final String MODE_SINGLE = "Single file";
    public static final String MODE_TIME   = "All time points (this camera)";
    public static final String MODE_ALL    = "All time points, all cameras as channels";
    private static final String[] MODES = { MODE_SINGLE, MODE_TIME, MODE_ALL };

    private static final Pattern FILE_PATTERN =
            Pattern.compile("^Cam_(.+)_(\\d+)\\.lux\\.h5$", Pattern.CASE_INSENSITIVE);

    @Override
    public void run(String arg) {
        String path = (arg != null && !arg.trim().isEmpty()) ? arg.trim() : null;
        if (path == null) {
            OpenDialog od = new OpenDialog("Open Bruker TruLive .lux.h5", null);
            if (od.getPath() == null) return;
            path = od.getPath();
        }
        if (!path.toLowerCase(Locale.ROOT).endsWith(".h5")) {
            IJ.error("Bruker TruLive Reader", "Not an HDF5 file: " + path);
            return;
        }

        GenericDialog gd = new GenericDialog("Bruker TruLive Reader");
        gd.addChoice("Open", MODES, Prefs.get("lux.reader.mode", MODE_SINGLE));
        gd.addCheckbox("Print acquisition summary to Log", Prefs.get("lux.reader.log", true));
        gd.showDialog();
        if (gd.wasCanceled()) return;
        String mode = gd.getNextChoice();
        boolean log = gd.getNextBoolean();
        Prefs.set("lux.reader.mode", mode);
        Prefs.set("lux.reader.log", log);

        try {
            ImagePlus imp = openLux(path, mode);
            if (imp == null) return;
            if (log) {
                String info = (String) imp.getProperty("Info");
                if (info != null) {
                    int cut = info.indexOf("---- Full metadata");
                    IJ.log(cut > 0 ? info.substring(0, cut) : info);
                }
            }
            imp.show();
        } catch (Exception e) {
            IJ.handleException(e);
        }
    }

    // ------------------------------------------------------------------
    // Core API (usable from scripts: Lux_H5_Reader.openLux(path, mode))
    // ------------------------------------------------------------------

    public static ImagePlus openLux(String path, String mode) throws IOException {
        File file = new File(path);
        if (!file.isFile()) throw new IOException("File not found: " + path);

        if (MODE_SINGLE.equals(mode)) {
            return openSingle(file);
        }

        // Scan the folder for sibling time points / cameras
        File dir = file.getParentFile();
        Matcher m = FILE_PATTERN.matcher(file.getName());
        if (!m.matches()) {
            IJ.log("File name does not match Cam_<camera>_<index>.lux.h5 - opening as single file.");
            return openSingle(file);
        }
        String selCam = m.group(1);

        // camera -> (timepoint -> file)
        Map<String, TreeMap<Integer, File>> series = new TreeMap<String, TreeMap<Integer, File>>();
        File[] listing = dir.listFiles();
        if (listing == null) listing = new File[0];
        for (File f : listing) {
            Matcher fm = FILE_PATTERN.matcher(f.getName());
            if (fm.matches()) {
                String cam = fm.group(1);
                int tp = Integer.parseInt(fm.group(2));
                if (!series.containsKey(cam)) series.put(cam, new TreeMap<Integer, File>());
                series.get(cam).put(tp, f);
            }
        }

        List<String> cameras = new ArrayList<String>();
        if (MODE_TIME.equals(mode)) {
            cameras.add(selCam);
        } else {
            cameras.addAll(series.keySet());
        }

        // time points common to all requested cameras
        TreeSet<Integer> tps = new TreeSet<Integer>(series.get(cameras.get(0)).keySet());
        for (String cam : cameras) tps.retainAll(series.get(cam).keySet());
        if (tps.isEmpty()) throw new IOException("No common time points found in " + dir);

        return openSeries(dir, series, cameras, new ArrayList<Integer>(tps));
    }

    private static ImagePlus openSingle(File file) throws IOException {
        Map<String, TreeMap<Integer, File>> series = new TreeMap<String, TreeMap<Integer, File>>();
        TreeMap<Integer, File> one = new TreeMap<Integer, File>();
        one.put(0, file);
        series.put("file", one);
        return openSeries(file.getParentFile(), series,
                Collections.singletonList("file"), Collections.singletonList(0));
    }

    private static ImagePlus openSeries(File dir, Map<String, TreeMap<Integer, File>> series,
                                        List<String> cameras, List<Integer> tps) throws IOException {
        int nc = cameras.size();
        int nt = tps.size();

        ImageStack stack = null;
        int nz = 0, ny = 0, nx = 0;
        double[] voxel = null;             // z, y, x in um
        String[] channelJson = new String[nc];
        String firstFileName = null;

        int total = nc * nt;
        int done = 0;
        for (int ti = 0; ti < nt; ti++) {
            for (int ci = 0; ci < nc; ci++) {
                File f = series.get(cameras.get(ci)).get(tps.get(ti));
                IJ.showStatus("Reading " + f.getName() + " (" + (done + 1) + "/" + total + ")");
                IHDF5Reader reader = HDF5Factory.openForReading(f);
                try {
                    HDF5DataSetInformation dsInfo = reader.object().getDataSetInformation("/Data");
                    long[] dims = dsInfo.getDimensions();
                    int fz = (dims.length == 3) ? (int) dims[0] : 1;
                    int fy = (int) dims[dims.length - 2];
                    int fx = (int) dims[dims.length - 1];

                    if (stack == null) {
                        nz = fz; ny = fy; nx = fx;
                        stack = new ImageStack(nx, ny);
                        firstFileName = f.getName();
                    } else if (fz != nz || fy != ny || fx != nx) {
                        throw new IOException("Dimension mismatch in " + f.getName()
                                + " (" + fx + "x" + fy + "x" + fz + " vs " + nx + "x" + ny + "x" + nz + ")");
                    }

                    if (voxel == null) voxel = readVoxelSize(reader);
                    if (channelJson[ci] == null) channelJson[ci] = readMetadataJson(reader, f);

                    // read slice by slice to keep peak memory = 1 plane above the stack itself
                    for (int z = 0; z < nz; z++) {
                        MDShortArray plane = (dims.length == 3)
                                ? reader.int16().readMDArrayBlockWithOffset("/Data",
                                        new int[] { 1, ny, nx }, new long[] { z, 0, 0 })
                                : reader.int16().readMDArrayBlockWithOffset("/Data",
                                        new int[] { ny, nx }, new long[] { 0, 0 });
                        short[] pix = plane.getAsFlatArray();
                        String label = "c=" + cameras.get(ci) + " t=" + tps.get(ti) + " z=" + (z + 1);
                        stack.addSlice(label, pix);
                        // slice order: c fastest, then z, then t  -> XYCZT (ImageJ default)
                        // achieved below by reordering: we add per (t, c, z) so we must reorder
                    }
                } finally {
                    reader.close();
                }
                done++;
                IJ.showProgress(done, total);
            }
        }
        IJ.showProgress(1.0);

        // Reorder slices from (t,c,z) blocks to ImageJ CZT order: index = c + z*nc + t*nc*nz
        ImageStack ordered = stack;
        if (nc > 1) {
            ordered = new ImageStack(nx, ny);
            for (int t = 0; t < nt; t++)
                for (int z = 0; z < nz; z++)
                    for (int c = 0; c < nc; c++) {
                        int src = t * (nc * nz) + c * nz + z + 1; // 1-based
                        ordered.addSlice(stack.getSliceLabel(src), stack.getPixels(src));
                    }
        }

        String title = dir != null ? dir.getName() : firstFileName;
        if (nc == 1 && nt == 1) title = firstFileName;
        ImagePlus imp = new ImagePlus(title, ordered);
        imp.setDimensions(nc, nz, nt);
        if (nc * nz * nt > 1) imp.setOpenAsHyperStack(true);

        // ---- calibration ----
        Calibration cal = imp.getCalibration();
        cal.setUnit("micron");
        if (voxel != null) {
            cal.pixelDepth  = voxel[0];
            cal.pixelHeight = voxel[1];
            cal.pixelWidth  = voxel[2];
        }
        String json0 = channelJson[0];
        if (json0 != null) {
            Double interval = jsonNumber(json0, "interval_s");
            if (interval != null && interval > 0) {
                cal.frameInterval = interval;
                cal.setTimeUnit("sec");
            }
        }
        imp.setCalibration(cal);

        // ---- metadata -> Info property ----
        StringBuilder info = new StringBuilder();
        for (int ci = 0; ci < nc; ci++) {
            if (channelJson[ci] == null) continue;
            info.append(buildSummary(channelJson[ci], nc > 1 ? ("Channel " + (ci + 1)
                    + " (Cam " + cameras.get(ci) + ")") : null, nx, ny, nz, nt, voxel));
            info.append('\n');
        }
        for (int ci = 0; ci < nc; ci++) {
            if (channelJson[ci] == null) continue;
            info.append("---- Full metadata (JSON), camera ").append(cameras.get(ci)).append(" ----\n");
            info.append(channelJson[ci]).append('\n');
        }
        imp.setProperty("Info", info.toString());

        // ---- display: composite with filter-derived LUTs, sensible ranges ----
        if (nc > 1) {
            CompositeImage ci = new CompositeImage(imp, CompositeImage.COMPOSITE);
            for (int c = 1; c <= nc; c++) {
                Color col = channelColor(channelJson[c - 1]);
                if (col != null) ci.setChannelLut(LUT.createLutFromColor(col), c);
            }
            imp = ci;
        }
        autoDisplayRange(imp);
        return imp;
    }

    // ------------------------------------------------------------------
    // HDF5 helpers
    // ------------------------------------------------------------------

    private static double[] readVoxelSize(IHDF5Reader reader) {
        try {
            if (reader.object().hasAttribute("/Data", "element_size_um")) {
                float[] es = reader.float32().getArrayAttr("/Data", "element_size_um");
                if (es.length >= 3) return new double[] { es[0], es[1], es[2] };
            }
        } catch (Exception ignored) {}
        // fallback: voxel_size_um from embedded JSON
        try {
            String json = readMetadataJson(reader, null);
            if (json != null) {
                int i = json.indexOf("voxel_size_um");
                if (i >= 0) {
                    String block = json.substring(i, Math.min(json.length(), i + 300));
                    Double w = jsonNumber(block, "width");
                    Double h = jsonNumber(block, "height");
                    Double d = jsonNumber(block, "depth");
                    if (w != null && h != null && d != null)
                        return new double[] { d, h, w };
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String readMetadataJson(IHDF5Reader reader, File h5file) {
        try {
            if (reader.exists("/metadata")) return reader.string().read("/metadata");
        } catch (Exception ignored) {}
        if (h5file != null) {  // sidecar fallback: Cam_long_00000.json
            String name = h5file.getName().replaceAll("\\.lux\\.h5$", ".json");
            File sidecar = new File(h5file.getParentFile(), name);
            if (sidecar.isFile()) {
                try {
                    return new String(Files.readAllBytes(sidecar.toPath()), StandardCharsets.UTF_8);
                } catch (IOException ignored) {}
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Metadata summary (best-effort extraction from the acquisition JSON)
    // ------------------------------------------------------------------

    private static String buildSummary(String json, String channelHeader,
                                       int nx, int ny, int nz, int nt, double[] voxel) {
        StringBuilder s = new StringBuilder();
        if (channelHeader != null) s.append("== ").append(channelHeader).append(" ==\n");
        else s.append("== Bruker TruLive3D (Luxendo) acquisition ==\n");

        String scope   = jsonString(json, "scopeType");
        String serial  = jsonString(json, "serialNumber");
        String version = jsonString(json, "softwareVersion");
        String time    = jsonString(json, "timeStamp");
        if (scope != null)
            s.append("Microscope: ").append(scope)
             .append(serial != null ? "  SN " + serial : "")
             .append(version != null ? "  software " + version : "").append('\n');
        if (time != null) s.append("Acquired: ").append(time).append('\n');

        String stackNo = jsonString(json, "stack");
        String chan    = jsonString(json, "channel");
        String tp      = jsonString(json, "time_point");
        String obj     = jsonString(json, "objective");
        String cam     = jsonString(json, "camera");
        s.append("Stack ").append(nvl(stackNo)).append(", channel ").append(nvl(chan))
         .append(", time point ").append(nvl(tp)).append(", objective ").append(nvl(obj))
         .append(", camera ").append(nvl(cam)).append('\n');

        s.append("Image: ").append(nx).append(" x ").append(ny).append(" x ").append(nz);
        if (nt > 1) s.append(" x ").append(nt).append("t");
        s.append(" (16-bit)").append('\n');
        if (voxel != null)
            s.append(String.format(Locale.ROOT, "Voxel size: %.4g x %.4g x %.4g micron%n",
                    voxel[2], voxel[1], voxel[0]));

        Double exp = jsonNumber(json, "exposure_ms");
        if (exp != null) s.append("Exposure: ").append(trim(exp)).append(" ms\n");

        // filter wheels + dichroics
        Matcher fm = Pattern.compile(
                "\"name\"\\s*:\\s*\"((?:fw|dichroic)[^\"]*)\"\\s*,\\s*\"selection\"\\s*:\\s*\"([^\"]*)\"",
                Pattern.DOTALL).matcher(json);
        List<String> filters = new ArrayList<String>();
        while (fm.find()) filters.add(fm.group(1) + ": " + fm.group(2));
        if (!filters.isEmpty()) s.append("Filters: ").append(join(filters)).append('\n');

        // lasers that are on
        Matcher lm = Pattern.compile(
                "\"intensity\"\\s*:\\s*([\\d.]+)[^{}]*?\"name\"\\s*:\\s*\"([^\"]*)\"[^{}]*?\"on\"\\s*:\\s*(true|false)",
                Pattern.DOTALL).matcher(json);
        List<String> lasers = new ArrayList<String>();
        while (lm.find())
            if ("true".equals(lm.group(3)))
                lasers.add(lm.group(2) + " @ " + trim(Double.valueOf(lm.group(1))) + "%");
        if (!lasers.isEmpty()) s.append("Lasers on: ").append(join(lasers)).append('\n');

        String id = jsonString(json, "image_id");
        if (id != null) s.append("Image ID: ").append(id).append('\n');
        return s.toString();
    }

    /** Pick a display color from the emission bandpass of this channel's filter wheel. */
    private static Color channelColor(String json) {
        if (json == null) return null;
        Matcher fm = Pattern.compile(
                "\"name\"\\s*:\\s*\"fw[^\"]*\"\\s*,\\s*\"selection\"\\s*:\\s*\"[^\\d\"]*(\\d{3})[-/ ]?(\\d{3})?",
                Pattern.DOTALL).matcher(json);
        // choose the filter wheel that belongs to this camera: imagingBranch lists it under
        // "filterwheels"; simplest robust choice = the filter named in imagingBranch detection
        String detFw = null;
        int ib = json.indexOf("\"imagingBranch\"");
        if (ib >= 0) {
            Matcher dm = Pattern.compile("\"filterwheels\"\\s*:\\s*\\[\\s*\"([^\"]+)\"",
                    Pattern.DOTALL).matcher(json.substring(ib));
            if (dm.find()) detFw = dm.group(1);
        }
        double center = -1;
        while (fm.find()) {
            double lo = Double.parseDouble(fm.group(1));
            double hi = fm.group(2) != null ? Double.parseDouble(fm.group(2)) : lo;
            double c = (lo + hi) / 2;
            if (detFw != null) {
                // check whether this match is the detection filter wheel
                int p = json.indexOf("\"" + detFw + "\"");
                if (p >= 0 && Math.abs(p - fm.start()) < 200) { center = c; break; }
            }
            if (center < 0) center = c; // first as fallback
        }
        if (center < 0) return null;
        if (center < 480) return new Color(0, 128, 255);   // blue
        if (center < 560) return Color.GREEN;
        if (center < 620) return Color.ORANGE;
        return Color.RED;
    }

    private static void autoDisplayRange(ImagePlus imp) {
        int nc = imp.getNChannels();
        int midZ = Math.max(1, imp.getNSlices() / 2);
        for (int c = 1; c <= nc; c++) {
            int idx = imp.getStackIndex(c, midZ, 1);
            short[] pix = (short[]) imp.getStack().getPixels(idx);
            int min = 65535, max = 0;
            for (short p : pix) {
                int v = p & 0xffff;
                if (v < min) min = v;
                if (v > max) max = v;
            }
            if (max <= min) max = min + 1;
            if (imp instanceof CompositeImage) {
                ((CompositeImage) imp).setPosition(c, midZ, 1);
                imp.setDisplayRange(min, max);
            } else {
                imp.setDisplayRange(min, max);
            }
        }
        if (imp instanceof CompositeImage) imp.setPosition(1, midZ, 1);
    }

    // ------------------------------------------------------------------
    // tiny JSON value extractors (best effort, full JSON is kept verbatim)
    // ------------------------------------------------------------------

    private static String jsonString(String json, String key) {
        if (json == null) return null;
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static Double jsonNumber(String json, String key) {
        if (json == null) return null;
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?[0-9.eE+]+)")
                .matcher(json);
        if (!m.find()) return null;
        try { return Double.valueOf(m.group(1)); } catch (NumberFormatException e) { return null; }
    }

    private static String nvl(String s) { return s == null ? "?" : s; }

    private static String trim(Double d) {
        if (d == null) return "?";
        return (d == Math.floor(d) && !d.isInfinite()) ? String.valueOf(d.longValue()) : String.valueOf(d);
    }

    private static String join(List<String> parts) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) b.append("; ");
            b.append(parts.get(i));
        }
        return b.toString();
    }
}
